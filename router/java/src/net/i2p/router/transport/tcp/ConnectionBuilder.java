package net.i2p.router.transport.tcp;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.net.SocketException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.i2p.crypto.AESInputStream;
import net.i2p.crypto.AESOutputStream;
import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.BandwidthLimitedInputStream;
import net.i2p.router.transport.BandwidthLimitedOutputStream;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SimpleTimer;

/**
 * Class responsible for all of the handshaking necessary to establish a 
 * connection with a peer.
 *
 */
public class ConnectionBuilder {
    private Log _log;
    private RouterContext _context;
    private TCPTransport _transport;
    /** who we're trying to talk with */
    private RouterInfo _target;
    /** who we're actually talking with */
    private RouterInfo _actualPeer;
    /** raw socket to the peer */
    private Socket _socket;
    /** raw stream to read from the peer */
    private InputStream _rawIn;
    /** raw stream to write to the peer */
    private OutputStream _rawOut;
    /** secure stream to read from the peer */
    private InputStream _connectionIn;
    /** secure stream to write to the peer */
    private OutputStream _connectionOut;
    /** protocol version agreed to, or -1 */
    private int _agreedProtocol;
    /** IP address the peer says we are at */
    private String _localIP;
    /** IP address of the peer we connected to */
    private TCPAddress _remoteAddress;
    /** connection tag to identify ourselves, or null if no known tag is available */
    private ByteArray _connectionTag;
    /** connection tag to identify ourselves next time */
    private ByteArray _nextConnectionTag;
    /** nonce the peer gave us */
    private ByteArray _nonce;
    /** key that we will be encrypting comm with */
    private SessionKey _key;
    /** initialization vector for the encryption */
    private byte[] _iv;
    /** 
     * Contains a message describing why the connection failed (or null if it
     * succeeded).  This should include a timestamp of some sort.
     */
    private String _error;
    
    /** If the connection hasn't been built in 10 seconds, give up */
    public static final int CONNECTION_TIMEOUT = 10*1000;
    
    public static final int WRITE_BUFFER_SIZE = 2*1024;
    
    public ConnectionBuilder(RouterContext context, TCPTransport transport, RouterInfo info) {
        _context = context;
        _log = context.logManager().getLog(ConnectionBuilder.class);
        _transport = transport;
        _target = info;
        _error = null;
        _agreedProtocol = -1;
    }
    
    /**
     * Blocking call to establish a TCP connection to the given peer through a
     * brand new socket.
     * 
     * @return fully established but not yet running connection, or null on error
     */
    public TCPConnection establishConnection() {
        SimpleTimer.getInstance().addEvent(new DieIfTooSlow(), CONNECTION_TIMEOUT);
        try {
            return doEstablishConnection();
        } catch (Exception e) { // catchall in case the timeout gets us flat footed
            _log.error("Error connecting", e);
            return null;
        }
    }
    private TCPConnection doEstablishConnection() {
        createSocket();        
        if ( (_socket == null) || (_error != null) )
            return null;

        try { _socket.setSoTimeout(CONNECTION_TIMEOUT); } catch (SocketException se) {}
        
        negotiateProtocol();
        
        if ( (_agreedProtocol < 0) || (_error != null) )
            return null;
        
        boolean ok = false;
        if (_connectionTag != null)
            ok = connectExistingSession();
        else
            ok = connectNewSession();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("connection ok? " + ok + " error: " + _error);
        
        if (ok && (_error == null) ) {
            establishComplete();

            try { _socket.setSoTimeout(0); } catch (SocketException se) {}
        
            TCPConnection con = new TCPConnection(_context);
            con.setInputStream(_connectionIn);
            con.setOutputStream(_connectionOut);
            con.setSocket(_socket);
            con.setRemoteRouterIdentity(_actualPeer.getIdentity());
            con.setRemoteAddress(_remoteAddress);
            if (_error == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Establishment successful!  returning the con");
                return con;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }
    
    /**
     * Agree on what protocol to communicate with, and set _agreedProtocol
     * accordingly.  If no common protocols are available, disconnect, set
     * _agreedProtocol to -1, and update the _error accordingly.
     */
    private void negotiateProtocol() {
        ConnectionTagManager mgr = _transport.getTagManager();
        ByteArray tag = mgr.getTag(_target.getIdentity().getHash());
        _key = mgr.getKey(_target.getIdentity().getHash());
        _connectionTag = tag;
        boolean ok = sendPreferredProtocol();
        if (!ok) return;
        ok = receiveAgreedProtocol();
        if (!ok) return;
    }
    
    /**
     * Send <code>#bytesFollowing + #versions + v1 [+ v2 [etc]] + 
     * tag? + tagData + properties</code>
     */
    private boolean sendPreferredProtocol() {
        try {
            // #bytesFollowing + #versions + v1 [+ v2 [etc]] + tag? + tagData + properties
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
            DataHelper.writeLong(baos, 1, TCPTransport.SUPPORTED_PROTOCOLS.length);
            for (int i = 0; i < TCPTransport.SUPPORTED_PROTOCOLS.length; i++) {
                DataHelper.writeLong(baos, 1, TCPTransport.SUPPORTED_PROTOCOLS[i]);
            }
            if (_connectionTag != null) {
                baos.write(0x1);
                baos.write(_connectionTag.getData());
            } else {
                baos.write(0x0);
            }
            DataHelper.writeProperties(baos, null); // no options atm
            byte line[] = baos.toByteArray();
            DataHelper.writeLong(_rawOut, 2, line.length);
            _rawOut.write(line);
            _rawOut.flush();

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("SendProtocol[X]: tag: " 
                           + (_connectionTag != null ? Base64.encode(_connectionTag.getData()) : "none")
                           + " socket: " + _socket);

            return true;
        } catch (IOException ioe) {
            fail("Error sending our handshake to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6) 
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error sending our handshake to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6) 
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
    }
    
    /**
     * Receive <code>#bytesFollowing + versionOk + #bytesIP + IP + tagOk? + nonce + properties</code>
     *
     */
    private boolean receiveAgreedProtocol() {
        try {
            // #bytesFollowing + versionOk + #bytesIP + IP + tagOk? + nonce + properties
            int numBytes = (int)DataHelper.readLong(_rawIn, 2);
            // 0xFFFF is a reserved value
            if ( (numBytes <= 0) || (numBytes >= 0xFFFF) )
                throw new IOException("Invalid number of bytes in response");
            
            byte line[] = new byte[numBytes];
            int read = DataHelper.read(_rawIn, line);
            if (read != numBytes) {
                fail("Handshake too short with " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
            }
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("ReadProtocol1[X]: "
                           + "\nLine: " + Base64.encode(line));
                           
            ByteArrayInputStream bais = new ByteArrayInputStream(line);
            
            int version = (int)DataHelper.readLong(bais, 1);
            for (int i = 0; i < TCPTransport.SUPPORTED_PROTOCOLS.length; i++) {
                if (version == TCPTransport.SUPPORTED_PROTOCOLS[i]) {
                    _agreedProtocol = version;
                    break;
                }
            }
            if (_agreedProtocol == -1) {
                fail("No valid protocol versions to contact "
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
            }
            
            int bytesInIP = (int)DataHelper.readLong(bais, 1);
            byte ip[] = new byte[bytesInIP];
            DataHelper.read(bais, ip); // ignore return value, this is an array
            _localIP = new String(ip);
            // if we don't already know our IP address, this may cause us
            // to fire up a socket listener, so may take a few seconds.
            _transport.ourAddressReceived(_localIP);
            
            int tagOk = (int)DataHelper.readLong(bais, 1);
            if ( (tagOk == 0x01) && (_connectionTag != null) ) {
                // tag is ok
            } else {
                _connectionTag = null;
                _key = null;
            }
            
            byte nonce[] = new byte[4];
            read = DataHelper.read(bais, nonce);
            if (read != 4) {
                fail("No nonce specified by " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
            }
            _nonce = new ByteArray(nonce);
            
            Properties opts = DataHelper.readProperties(bais);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("ReadProtocol[X]: agreed=" + _agreedProtocol + " nonce: " 
                           + Base64.encode(nonce) + " tag: " 
                           + (_connectionTag != null ? Base64.encode(_connectionTag.getData()) : "none")
                           + " props: " + opts
                           + " socket: " + _socket
                           + "\nLine: " + Base64.encode(line));

            // we dont care about any of the properties, so we can just
            // ignore it, and we're done with this step
            return true;
        } catch (IOException ioe) {
            fail("Error reading the handshake from " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error reading the handshake from " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
        
    }
        
    /** Set the next tag to <code>H(E(nonce + tag, sessionKey))</code> */
    private void updateNextTagExisting() {
        byte pre[] = new byte[48];
        byte encr[] = _context.AESEngine().encrypt(pre, _key, _iv);
        Hash h = _context.sha().calculateHash(encr);
        _nextConnectionTag = new ByteArray(h.getData());
    }
    
    /**
     * We have a valid tag, so use it to do the handshaking.  On error, fail()
     * appropriately.
     *
     * @return true if the connection went ok, or false if it failed.
     */
    private boolean connectExistingSession() { 
        // iv to the SHA256 of the tag appended by the nonce.
        byte data[] = new byte[36];
        System.arraycopy(_connectionTag.getData(), 0, data, 0, 32);
        System.arraycopy(_nonce.getData(), 0, data, 32, 4);
        Hash h = _context.sha().calculateHash(data);
        _iv = new byte[16];
        System.arraycopy(h.getData(), 0, _iv, 0, 16);
     
        updateNextTagExisting();

        _rawOut = new BufferedOutputStream(_rawOut, ConnectionBuilder.WRITE_BUFFER_SIZE);

        _rawOut = new AESOutputStream(_context, _rawOut, _key, _iv);
        _rawIn = new AESInputStream(_context, _rawIn, _key, _iv);
        
        // send: H(nonce)
        try {
            h = _context.sha().calculateHash(_nonce.getData());
            h.writeBytes(_rawOut);
            _rawOut.flush();
        } catch (IOException ioe) {
            fail("Error writing the encrypted nonce to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + ioe.getMessage());
            return false;
        } catch (DataFormatException dfe) {
            fail("Error writing the encrypted nonce to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + dfe.getMessage());
            return false;
        }
        
        // read: H(tag)
        try { 
            Hash readHash = new Hash();
            readHash.readBytes(_rawIn);
            
            Hash expectedHash = _context.sha().calculateHash(_connectionTag.getData());
            
            if (!readHash.equals(expectedHash)) {
                fail("Key verification failed with " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
            }
        } catch (IOException ioe) {
            fail("Error reading the initial key verification from " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + ioe.getMessage());
            return false;
        } catch (DataFormatException dfe) {
            fail("Error reading the initial key verification from " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + dfe.getMessage());
            return false;
        }
        
        // send: routerInfo + currentTime + H(routerInfo + currentTime + nonce + tag)
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            _context.router().getRouterInfo().writeBytes(baos);
            DataHelper.writeDate(baos, new Date(_context.clock().now()));
            
            _rawOut.write(baos.toByteArray());
            
            baos.write(_nonce.getData());
            baos.write(_connectionTag.getData());
            Hash verification = _context.sha().calculateHash(baos.toByteArray());

            verification.writeBytes(_rawOut);
            _rawOut.flush();
        } catch (IOException ioe) {
            fail("Error writing the verified info to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + ioe.getMessage());
            return false;
        } catch (DataFormatException dfe) {
            fail("Error writing the verified info to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + dfe.getMessage());
            return false;
        }
        
        // read: routerInfo + status + properties 
        //        + H(routerInfo + status + properties + nonce + tag)
        try {
            RouterInfo peer = new RouterInfo();
            peer.readBytes(_rawIn);
            int status = (int)_rawIn.read() & 0xFF;
            boolean ok = validateStatus(status);
            if (!ok) return false;
            
            Properties props = DataHelper.readProperties(_rawIn);
            // ignore these now
            
            Hash readHash = new Hash();
            readHash.readBytes(_rawIn);
            
            // H(routerInfo + status + properties + nonce + tag)
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            peer.writeBytes(baos);
            baos.write(status);
            DataHelper.writeProperties(baos, props);
            baos.write(_nonce.getData());
            baos.write(_connectionTag.getData());
            Hash expectedHash = _context.sha().calculateHash(baos.toByteArray());
            
            if (!expectedHash.equals(readHash)) {
                fail("Error verifying info from " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                     + " (claiming to be " 
                     + peer.getIdentity().calculateHash().toBase64().substring(0,6) 
                     + ")");
                return false;
            }

            _actualPeer = peer;
            return true;
        } catch (IOException ioe) {
            fail("Error reading the verified info from " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + ioe.getMessage());
            return false;
        } catch (DataFormatException dfe) {
            fail("Error reading the verified info from " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + dfe.getMessage());
            return false;
        }
    }
    
    /**
     * We do not have a valid tag, so exchange a new one and then do the 
     * handshaking.  On error, fail() appropriately.
     *
     * @return true if the connection went ok, or false if it failed.
     */
    private boolean connectNewSession() { 
        DHSessionKeyBuilder builder = null;
        try { 
            builder = DHSessionKeyBuilder.exchangeKeys(_rawIn, _rawOut);
        } catch (IOException ioe) {
            fail("Error exchanging keys with " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6));
            return false;
        }
        
        // load up the key initialize the encrypted streams
        _key = builder.getSessionKey();
        byte extra[] = builder.getExtraBytes().getData();
        _iv = new byte[16];
        System.arraycopy(extra, 0, _iv, 0, 16);
        byte nextTag[] = new byte[32];
        System.arraycopy(extra, 16, nextTag, 0, 32);
        _nextConnectionTag = new ByteArray(nextTag);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("\nNew session[X]: key=" + _key.toBase64() + " iv=" 
                       + Base64.encode(_iv) + " nonce=" + Base64.encode(_nonce.getData())
                       + " socket: " + _socket);

        _rawOut = new BufferedOutputStream(_rawOut, ConnectionBuilder.WRITE_BUFFER_SIZE);

        _rawOut = new AESOutputStream(_context, _rawOut, _key, _iv);
        _rawIn = new AESInputStream(_context, _rawIn, _key, _iv);
        
        // send: H(nonce)
        try {
            Hash h = _context.sha().calculateHash(_nonce.getData());
            h.writeBytes(_rawOut);
            _rawOut.flush();
        } catch (IOException ioe) {
            fail("Error writing the verification to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error writing the verification to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6), dfe);
            return false;
        }
        
        // read: H(nextTag)
        try {
            byte val[] = new byte[32];
            int read = DataHelper.read(_rawIn, val);
            if (read != 32) {
                fail("Not enough data (" + read + ") to read the verification from " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
            }
            
            Hash expected = _context.sha().calculateHash(_nextConnectionTag.getData());
            if (!DataHelper.eq(expected.getData(), val)) {
                fail("Verification failed from " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
            }
        } catch (IOException ioe) {
            fail("Error reading the verification from " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6), ioe);
            return false;
        }
        
        // send: routerInfo + currentTime 
        //       + S(routerInfo + currentTime + nonce + nextTag, routerIdent.signingKey)
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            _context.router().getRouterInfo().writeBytes(baos);
            DataHelper.writeDate(baos, new Date(_context.clock().now()));
            
            _rawOut.write(baos.toByteArray());
            
            baos.write(_nonce.getData());
            baos.write(_nextConnectionTag.getData());
            Signature sig = _context.dsa().sign(baos.toByteArray(), 
                                                _context.keyManager().getSigningPrivateKey());
            
            sig.writeBytes(_rawOut);
            _rawOut.flush();
        } catch (IOException ioe) {
            fail("Error sending the info to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6));
            return false;
        } catch (DataFormatException dfe) {
            fail("Error sending the info to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6));
            return false;
        }
        
        // read: routerInfo + status + properties 
        //       + S(routerInfo + status + properties + nonce + nextTag, routerIdent.signingKey)
        try {
            RouterInfo peer = new RouterInfo();
            peer.readBytes(_rawIn);
            int status = (int)_rawIn.read() & 0xFF;
            boolean ok = validateStatus(status);
            if (!ok) return false;
            
            Properties props = DataHelper.readProperties(_rawIn);
            // ignore these now
            
            Signature sig = new Signature();
            sig.readBytes(_rawIn);
            
            // S(routerInfo + status + properties + nonce + nextTag, routerIdent.signingKey)
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            peer.writeBytes(baos);
            baos.write(status);
            DataHelper.writeProperties(baos, props);
            baos.write(_nonce.getData());
            baos.write(_nextConnectionTag.getData());
            ok = _context.dsa().verifySignature(sig, baos.toByteArray(), 
                                                peer.getIdentity().getSigningPublicKey());
            
            if (!ok) {
                fail("Error verifying info from " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                     + " (claiming to be " 
                     + peer.getIdentity().calculateHash().toBase64().substring(0,6) 
                     + ")");
                return false;
            }

            _actualPeer = peer;
            return true;
        } catch (IOException ioe) {
            fail("Error reading the verified info from " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + ioe.getMessage());
            return false;
        } catch (DataFormatException dfe) {
            fail("Error reading the verified info from " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                 + ": " + dfe.getMessage());
            return false;
        }
    }
    
    /**
     * Is the given status value ok for an existing session?  
     *
     * @return true if ok, false if fail()ed
     */
    private boolean validateStatus(int status) {
        switch (status) {
            case -1:
                fail("Error reading the status from " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
            case 0: // ok
                return true;
            case 1: // not reachable
                fail("According to " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                     + ", we are not reachable on " + _localIP + ":" + _transport.getPort());
                return false;
            case 2: // clock skew
                fail("According to " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6)
                     + ", our clock is off");
                return false;
            case 3: // signature failure (only for new sessions)
                fail("Signature failure talking to " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
            default: // unknown error
                fail("Unknown error [" + status + "] connecting to " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
        }
    }

    /**
     * Finish up the establishment (wrapping the streams, storing the netDb,
     * persisting the connection tags, etc)
     *
     */
    private void establishComplete() {
        _connectionIn = new BandwidthLimitedInputStream(_context, _rawIn, _actualPeer.getIdentity());
        OutputStream blos = new BandwidthLimitedOutputStream(_context, _rawOut, _actualPeer.getIdentity());
        _connectionOut = blos;
        
        Hash peer = _actualPeer.getIdentity().getHash();
        _context.netDb().store(peer, _actualPeer);
        _transport.getTagManager().replaceTag(peer, _nextConnectionTag, _key);
    }
    
    /** 
     * Build a socket to the peer, and populate _socket, _rawIn, and _rawOut
     * accordingly.  On error or timeout, close and null them all and 
     * set _error.
     *
     */
    private void createSocket() {
        CreateSocketRunner r = new CreateSocketRunner();
        I2PThread t = new I2PThread(r);
        t.start();
        try { t.join(CONNECTION_TIMEOUT); } catch (InterruptedException ie) {}
        if (!r.getCreated()) {
            fail("Unable to establish a socket in time to " 
                 + _target.getIdentity().calculateHash().toBase64().substring(0,6));
        }
    }    
    
    /** Brief description of why the connection failed (or null if it succeeded) */
    public String getError() { return _error; }
    
    /**
     * Kill the builder, closing all sockets and streams, setting everything 
     * back to failure states, and setting the given error.
     *
     */
    private void fail(String error) {
        fail(error, null);
    }
    private void fail(String error, Exception e) {
        if (_error == null) {
            // only grab the first error
            _error = error;
        }
        
        if (_rawIn != null) try { _rawIn.close(); } catch (IOException ioe) {}
        if (_rawOut != null) try { _rawOut.close(); } catch (IOException ioe) {}
        if (_socket != null) try { _socket.close(); } catch (IOException ioe) {}
        
        _socket = null;
        _rawIn = null;
        _rawOut = null;
        _agreedProtocol = -1;
        _nonce = null;
        _connectionTag = null;
        _actualPeer = null;
        
        if (_log.shouldLog(Log.WARN))
            _log.warn(error, e);
    }

    /**
     * Lookup and establish a connection to the peer, exposing getCreate() == true
     * once we are done.  This allows for asynchronous timeouts without depending 
     * upon the interruptability of the socket (since it isn't open yet).
     *
     */
    private class CreateSocketRunner implements Runnable {
        private boolean _created;
        public CreateSocketRunner() {
            _created = false;
        }
        
        public boolean getCreated() { return _created; }
        
        public void run() {
            RouterAddress addr = _target.getTargetAddress(_transport.getStyle());
            if (addr == null) {
                fail("Peer " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6) 
                     + " has no TCP addresses");
                return;
            }
            TCPAddress tcpAddr = new TCPAddress(addr);
            if (tcpAddr.getPort() <= 0) {
                fail("Peer " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6) 
                     + " has an invalid TCP address");
                return;
            }

            try {
                _socket = new Socket(tcpAddr.getAddress(), tcpAddr.getPort());
                _rawIn = _socket.getInputStream();
                _rawOut = _socket.getOutputStream();
                _error = null;
                _remoteAddress = tcpAddr;
                _created = true;
            } catch (IOException ioe) {
                fail("Error contacting " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6) 
                     + " on " + tcpAddr.toString() + ": " + ioe.getMessage());
                return;
            }
        }
    }

    /**
     * In addition to the socket creation timeout, we have a timed event for 
     * the overall connection establishment, killing everything if we haven't
     * completed a connection yet.
     *
     */
    private class DieIfTooSlow implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if ( (_actualPeer == null) && (_error == null) ) {
                fail("Took too long to connect with " 
                     + _target.getIdentity().calculateHash().toBase64().substring(0,6));
            }
        }
    }
}
