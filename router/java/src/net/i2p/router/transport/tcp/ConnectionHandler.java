package net.i2p.router.transport.tcp;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;

import java.text.SimpleDateFormat;

import java.util.Date;
import java.util.Properties;

import net.i2p.crypto.AESInputStream;
import net.i2p.crypto.AESOutputStream;
import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.BandwidthLimitedInputStream;
import net.i2p.router.transport.BandwidthLimitedOutputStream;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;

/**
 * Class responsible for all of the handshaking necessary to turn a socket into
 * a TCPConnection.
 *
 */
public class ConnectionHandler {
    private RouterContext _context;
    private Log _log;    
    private TCPTransport _transport;
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
    /** 
     * Contains a message describing why the connection failed (or null if it
     * succeeded).  This should include a timestamp of some sort.
     */
    private String _error;
    /** 
     * If we're handing a reachability test, set this to true once 
     * we're done 
     */
    private boolean _testComplete;
    /** IP address of the peer who contacted us */
    private String _from;    
    /** Where we verified their address */
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

    /** for reading/comparing, this is the #bytes sent if we are being tested */
    public static final int FLAG_TEST = 0xFFFF;
    /** protocol version sent if no protocols are ok */
    public static final byte FLAG_PROTOCOL_NONE = 0x0;
    /** alice is sending a tag to bob */
    public static final byte FLAG_TAG_FOLLOWING = 0x1;
    /** alice is not sending a tag to bob */
    public static final byte FLAG_TAG_NOT_FOLLOWING = 0x0;
    /** the connection tag is ok (we have an available key for it) */
    public static final byte FLAG_TAG_OK = 0x1;
    /** the connection tag is not ok (must go with a full DH) */
    public static final byte FLAG_TAG_NOT_OK = 0x0;
    /** dunno why the peer is bad */
    public static final int STATUS_UNKNOWN = -1;
    /** the peer's public addresses could not be verified */
    public static final int STATUS_UNREACHABLE = 1;
    /** the peer's clock is too far skewed */
    public static final int STATUS_SKEWED = 2;
    /** the peer's signature failed (either some crazy corruption or MITM) */
    public static final int STATUS_SIGNATURE_FAILED = 3;
    /** the peer is fine */
    public static final int STATUS_OK = 0;
    
    private static final int MAX_VERSIONS = 255;
    
    public ConnectionHandler(RouterContext ctx, TCPTransport transport, Socket socket) {
        _context = ctx;
        _log = ctx.logManager().getLog(ConnectionHandler.class);
        _transport = transport;
        _socket = socket;
        _error = null;
        _agreedProtocol = -1;
        InetAddress addr = _socket.getInetAddress();
        try { _socket.setSoTimeout(TCPListener.HANDLE_TIMEOUT); } catch (SocketException se) {}
        if (addr != null) {
            _from = addr.getHostAddress();
        }
    }
    
    /**
     * Blocking call to establish a TCP connection over the current socket.  
     * At this point, no data whatsoever need to have been transmitted over the
     * socket - the builder is responsible for all aspects of the handshaking.
     * 
     * @return fully established but not yet running connection, or null on error
     */
    public TCPConnection receiveConnection() {
        try {
            _rawIn = _socket.getInputStream();
            _rawOut = _socket.getOutputStream();
        } catch (IOException ioe) {
            fail("Error accessing the socket streams from " + _from, ioe);
            return null;
        }
        
        negotiateProtocol();
        
        if ( (_agreedProtocol < 0) || (_error != null) )
            return null;
        
        boolean ok = false;
        if ( (_connectionTag != null) && (_key != null) )
            ok = connectExistingSession();
        else
            ok = connectNewSession();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("connection ok? " + ok + " error: " + _error);
        
        if (ok && (_error == null) ) {
            establishComplete();
            
            try { _socket.setSoTimeout(0); } catch (SocketException se) {}
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Establishment ok... building the con");
            
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
                if (_log.shouldLog(Log.INFO))
                    _log.info("Establishment ok but we failed?!  error = " + _error);
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
        boolean ok = readPreferredProtocol();
        if (!ok) return;
        sendAgreedProtocol();
    }
    
    /**
     * Receive <code>#bytesFollowing + #versions + v1 [+ v2 [etc]] + tag? + tagData + properties</code>
     *
     */
    private boolean readPreferredProtocol() {
        try {
            int numBytes = (int)DataHelper.readLong(_rawIn, 2);
            if (numBytes <= 0)
                throw new IOException("Invalid number of bytes in connection");
            // reachability test
            if (numBytes == FLAG_TEST) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ReadProtocol[Y]: test called, handle it");
                handleTest();
                return false;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ReadProtocol[Y]: not a test (line len=" + numBytes + ")");
            }
            
            byte line[] = new byte[numBytes];
            int read = DataHelper.read(_rawIn, line);
            if (read != numBytes) {
                fail("Handshake too short from " + _from);
                return false;
            }
            
            ByteArrayInputStream bais = new ByteArrayInputStream(line);
            
            int numVersions = (int)DataHelper.readLong(bais, 1);
            if ( (numVersions <= 0) || (numVersions > MAX_VERSIONS) ) {
                fail("Invalid number of protocol versions from " + _from);
                return false;
            }
            int versions[] = new int[numVersions];
            for (int i = 0; i < numVersions; i++)
                versions[i] = (int)DataHelper.readLong(bais, 1);
            
            for (int i = 0; i < numVersions && _agreedProtocol == -1; i++) {
                for (int j = 0; j < TCPTransport.SUPPORTED_PROTOCOLS.length; j++) {
                    if (versions[i] == TCPTransport.SUPPORTED_PROTOCOLS[j]) {
                        _agreedProtocol = versions[i];
                        break;
                    }
                }
            }
            
            int tag = (int)DataHelper.readLong(bais, 1);
            if (tag == FLAG_TAG_FOLLOWING) {
                byte tagData[] = new byte[32];
                read = DataHelper.read(bais, tagData);
                if (read != 32)
                    throw new IOException("Not enough data for the tag");
                _connectionTag = new ByteArray(tagData);
                _key = _transport.getTagManager().getKey(_connectionTag);
                if (_key == null)
                    _connectionTag = null;
            }
            
            Properties opts = DataHelper.readProperties(bais);
            // ignore them
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("ReadProtocol[Y]: agreed=" + _agreedProtocol + " tag: " 
                           + (_connectionTag != null ? Base64.encode(_connectionTag.getData()) : "none"));
            return true;
        } catch (IOException ioe) {
            fail("Error reading the handshake from " + _from
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error reading the handshake from " + _from
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
    }
    
    /**
     * Send <code>#bytesFollowing + versionOk + #bytesIP + IP + tagOk? + nonce + properties</code>
     */
    private void sendAgreedProtocol() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
            if (_agreedProtocol <= 0)
                baos.write(FLAG_PROTOCOL_NONE);
            else
                baos.write(_agreedProtocol);
            
            byte ip[] = _from.getBytes();
            baos.write(ip.length);
            baos.write(ip);
            
            if (_key != null)
                baos.write(FLAG_TAG_OK);
            else
                baos.write(FLAG_TAG_NOT_OK);
            
            byte nonce[] = new byte[4];
            _context.random().nextBytes(nonce);
            _nonce = new ByteArray(nonce);
            baos.write(nonce);
            
            Properties opts = new Properties();
            opts.setProperty("foo", "bar");
            DataHelper.writeProperties(baos, opts); // no options atm
            
            byte line[] = baos.toByteArray();
            DataHelper.writeLong(_rawOut, 2, line.length);
            _rawOut.write(line);
            _rawOut.flush();
            
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("SendProtocol[Y]: agreed=" + _agreedProtocol + " IP: " + _from 
                           + " nonce: " + Base64.encode(nonce) + " tag: " 
                           + (_connectionTag != null ? Base64.encode(_connectionTag.getData()) : " none")
                           + " props: " + opts
                           + "\nLine: " + Base64.encode(line));
            
            if (_agreedProtocol <= 0) {
                fail("Connection from " + _from + " rejected, since no compatible protocols were found");
                return;
            }
        } catch (IOException ioe) {
            fail("Error writing the handshake to " + _from
                 + ": " + ioe.getMessage(), ioe);
            return;
        } catch (DataFormatException dfe) {
            fail("Error writing the handshake to " + _from
                 + ": " + dfe.getMessage(), dfe);
            return;
        }
    }
    
    /** Set the next tag to <code>H(E(nonce + tag, sessionKey))</code> */
    private void updateNextTagExisting() {
        byte pre[] = new byte[48];
        System.arraycopy(_nonce.getData(), 0, pre, 0, 4);
        System.arraycopy(_connectionTag.getData(), 0, pre, 4, 32);
        _context.aes().encrypt(pre, 0, pre, 0, _key, _iv, pre.length);
        Hash h = _context.sha().calculateHash(pre);
        _nextConnectionTag = new ByteArray(h.getData());
    }
    
    /**
     * We have a valid tag, so use it to do the handshaking.  On error, fail()
     * appropriately.
     *
     * @return true if the connection went ok, or false if it failed.
     */
    private boolean connectExistingSession() { 
        // iv = H(tag+nonce)
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
        
        // read: H(nonce)
        try {
            Hash readHash = new Hash();
            readHash.readBytes(_rawIn);
            
            Hash expected = _context.sha().calculateHash(_nonce.getData());
            if (!expected.equals(readHash)) {
                fail("Verification hash failed from " + _from);
                return false;
            }
        } catch (IOException ioe) {
            fail("Error reading the encrypted nonce from " + _from
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error reading the encrypted nonce from " + _from
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
        
        // send: H(tag)
        try {
            Hash tagHash = _context.sha().calculateHash(_connectionTag.getData());
            tagHash.writeBytes(_rawOut);
            _rawOut.flush();
        } catch (IOException ioe) {
            fail("Error writing the encrypted tag to " + _from
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error writing the encrypted tag to " + _from
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
        
        long clockSkew = 0;
        
        // read: routerInfo + currentTime + H(routerInfo + currentTime + nonce + tag)
        try {
            RouterInfo peer = new RouterInfo();
            peer.readBytes(_rawIn);
            Date now = DataHelper.readDate(_rawIn);
            Hash readHash = new Hash();
            readHash.readBytes(_rawIn);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            peer.writeBytes(baos);
            DataHelper.writeDate(baos, now);
            baos.write(_nonce.getData());
            baos.write(_connectionTag.getData());
            Hash expectedHash = _context.sha().calculateHash(baos.toByteArray());
            
            if (!expectedHash.equals(readHash)) {
                fail("Invalid hash read for the info from " + _from);
                return false;
            }
            
            _actualPeer = peer;
            clockSkew = _context.clock().now() - now.getTime();
        } catch (IOException ioe) {
            fail("Error reading the peer info from " + _from
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error reading the peer info from " + _from
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
        
        // verify routerInfo
        boolean reachable = verifyReachability();
        
        // send routerInfo + status + properties + H(routerInfo + status + properties + nonce + tag)
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            _context.router().getRouterInfo().writeBytes(baos);
            
            Properties props = new Properties();
            
            int status = STATUS_UNKNOWN;
            if (!reachable) {
                status = STATUS_UNREACHABLE;
            } else if ( (clockSkew > Router.CLOCK_FUDGE_FACTOR) 
                        || (clockSkew < 0 - Router.CLOCK_FUDGE_FACTOR) ) {
                status = STATUS_SKEWED;
                SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddhhmmssSSS");
                props.setProperty("SKEW", fmt.format(new Date(_context.clock().now())));
            } else {
                status = STATUS_OK;
            }
            
            baos.write(status);
             
            DataHelper.writeProperties(baos, props);
            byte beginning[] = baos.toByteArray();
            
            baos.write(_nonce.getData());
            baos.write(_connectionTag.getData());
            
            Hash verification = _context.sha().calculateHash(baos.toByteArray());
            
            _rawOut.write(beginning);
            verification.writeBytes(_rawOut);
            _rawOut.flush();
            
            return handleStatus(status, clockSkew);
        } catch (IOException ioe) {
            fail("Error writing the peer info to " + _from
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error writing the peer info to " + _from
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
    }
    
    /**
     *
     * We do not have a valid tag, so DH then do the handshaking.  On error, 
     * fail() appropriately.
     *
     * @return true if the connection went ok, or false if it failed.
     */
    private boolean connectNewSession() {
        DHSessionKeyBuilder builder = null;
        try { 
            builder = DHSessionKeyBuilder.exchangeKeys(_rawIn, _rawOut);
            if (builder == null) {
                fail("Error exchanging the keys with " + _from);
                return false;
            }
        } catch (IOException ioe) {
            fail("Error exchanging keys with " + _from);
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
            _log.debug("\nNew session[Y]: key=" + _key.toBase64() + " iv=" 
                       + Base64.encode(_iv) + " nonce=" + Base64.encode(_nonce.getData())
                       + " socket: " + _socket);
        
        _rawOut = new BufferedOutputStream(_rawOut, ConnectionBuilder.WRITE_BUFFER_SIZE);
        
        _rawOut = new AESOutputStream(_context, _rawOut, _key, _iv);
        _rawIn = new AESInputStream(_context, _rawIn, _key, _iv);
        
        // read: H(nonce)
        try {
            Hash h = new Hash();
            h.readBytes(_rawIn);
            
            Hash expected = _context.sha().calculateHash(_nonce.getData());
            if (!expected.equals(h)) {
                fail("Hash after negotiation from " + _from + " does not match");
                return false;
            }
        } catch (IOException ioe) {
            fail("Error reading the hash from " + _from
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error reading the hash from " + _from
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
        
        // send: H(nextTag)
        try {
            Hash h = _context.sha().calculateHash(_nextConnectionTag.getData());
            h.writeBytes(_rawOut);
            _rawOut.flush();
        } catch (IOException ioe) {
            fail("Error writing the hash to " + _from
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error writing the hash to " + _from
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
        
        long clockSkew = 0;
        boolean sigOk = false;
        
        // read: routerInfo + currentTime 
        //       + S(routerInfo + currentTime + nonce + nextTag, routerIdent.signingKey)
        try {
            RouterInfo info = new RouterInfo();
            info.readBytes(_rawIn);
            Date now = DataHelper.readDate(_rawIn);
            Signature sig = new Signature();
            sig.readBytes(_rawIn);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            info.writeBytes(baos);
            DataHelper.writeDate(baos, now);
            baos.write(_nonce.getData());
            baos.write(_nextConnectionTag.getData());
            
            sigOk = _context.dsa().verifySignature(sig, baos.toByteArray(), 
                                                        info.getIdentity().getSigningPublicKey());
            
            clockSkew = _context.clock().now() - now.getTime();
            _actualPeer = info;
        } catch (IOException ioe) {
            fail("Error reading the info from " + _from
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error reading the info from " + _from
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
        
        // verify routerInfo
        boolean reachable = verifyReachability();
        
        // send: routerInfo + status + properties 
        //       + S(routerInfo + status + properties + nonce + nextTag, routerIdent.signingKey)
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            _context.router().getRouterInfo().writeBytes(baos);
            
            Properties props = new Properties();
            
            int status = STATUS_UNKNOWN;
            if (!reachable) {
                status = STATUS_UNREACHABLE;
            } else if ( (clockSkew > Router.CLOCK_FUDGE_FACTOR) 
                        || (clockSkew < 0 - Router.CLOCK_FUDGE_FACTOR) ) {
                status = STATUS_SKEWED;
                SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddhhmmssSSS");
                props.setProperty("SKEW", fmt.format(new Date(_context.clock().now())));
            } else if (!sigOk) {
                status = STATUS_SIGNATURE_FAILED;
            } else {
                status = STATUS_OK;
            }
         
            if (_actualPeer.getIdentity().getHash().equals(_context.routerHash())) {
                status = STATUS_UNKNOWN;
                props.setProperty("REASON", "wtf, talking to myself?");
            }
            
            baos.write(status);
             
            DataHelper.writeProperties(baos, props);
            byte beginning[] = baos.toByteArray();
            
            baos.write(_nonce.getData());
            baos.write(_nextConnectionTag.getData());
            
            Signature sig = _context.dsa().sign(baos.toByteArray(), 
                                                _context.keyManager().getSigningPrivateKey());
            
            _rawOut.write(beginning);
            sig.writeBytes(_rawOut);
            _rawOut.flush();
            
            return handleStatus(status, clockSkew);
        } catch (IOException ioe) {
            fail("Error writing the info to " + _from
                 + ": " + ioe.getMessage(), ioe);
            return false;
        } catch (DataFormatException dfe) {
            fail("Error writing the info to " + _from
                 + ": " + dfe.getMessage(), dfe);
            return false;
        }
    }
    
    /**
     * Act according to the status code, failing as necessary and returning
     * whether we should continue going or not.
     *
     * @return true if we should keep going.
     */
    private boolean handleStatus(int status, long clockSkew) {
        switch (status) {
            case STATUS_OK:
                return true;
            case STATUS_UNREACHABLE:
                fail("Peer " + _actualPeer.getIdentity().calculateHash().toBase64().substring(0,6)
                     + " at " + _from + " is unreachable");
                return false;
            case STATUS_SKEWED:
                fail("Peer " + _actualPeer.getIdentity().calculateHash().toBase64().substring(0,6)
                     + " was skewed by " + DataHelper.formatDuration(clockSkew));
                return false;
            case STATUS_SIGNATURE_FAILED:
                fail("Forged signature on " + _from + " pretending to be "
                     + _actualPeer.getIdentity().calculateHash().toBase64().substring(0,6));
                return false;
            default:
                fail("Unknown error verifying " 
                     + _actualPeer.getIdentity().calculateHash().toBase64().substring(0,6)
                     + ": " + status);
                return false;
        }
    }
    
    /** 
     * Can the peer be contacted on their public addresses?  If so,
     * be sure to set _remoteAddress.  We can do this without branching onto
     * another thread because we already have a timer killing this handler if
     * it takes too long
     */
    private boolean verifyReachability() { 
        if (_actualPeer == null) return false;
        _remoteAddress = new TCPAddress(_actualPeer.getTargetAddress(TCPTransport.STYLE));
        if ( (_remoteAddress.getPort() <= 0) || (_remoteAddress.getPort() > 65535) )
            return false;
        
        TCPAddress testAddress = _remoteAddress;
        
        // if it is a LAN address, test with that address and not the public one
        if (!TCPAddress.isPubliclyRoutable(_from)) {
            testAddress = new TCPAddress(_from, _remoteAddress.getPort());
        }
        
        try {
            return verifyReachability(testAddress);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error verifying " 
                          + _actualPeer.getIdentity().calculateHash().toBase64().substring(0,6)
                          + "at " + testAddress, ioe);
            return false;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error verifying " 
                          + _actualPeer.getIdentity().calculateHash().toBase64().substring(0,6)
                          + "at " + testAddress, dfe);
            return false;
        }
    }
    
    private static boolean verifyReachability(TCPAddress address) throws IOException, DataFormatException { 
        //if (true) return true;
        Socket s = new Socket(address.getAddress(), address.getPort());
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        try { s.setSoTimeout(TCPListener.HANDLE_TIMEOUT); } catch (SocketException se) {}

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Beginning verification of reachability");

        // send: 0xFFFF + #versions + v1 [+ v2 [etc]] + properties
        DataHelper.writeLong(out, 2, FLAG_TEST);
        out.write(TCPTransport.SUPPORTED_PROTOCOLS.length);
        for (int i = 0; i < TCPTransport.SUPPORTED_PROTOCOLS.length; i++) 
            out.write(TCPTransport.SUPPORTED_PROTOCOLS[i]);
        DataHelper.writeProperties(out, null);
        out.flush();

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Verification of reachability request sent");

        // read: 0xFFFF + versionOk + #bytesIP + IP + currentTime + properties
        int flag = (int)DataHelper.readLong(in, 2);
        if (flag != FLAG_TEST)
            throw new IOException("Unable to verify the peer - invalid response");
        int version = in.read();
        if (version == -1)
            throw new IOException("Unable to verify the peer - invalid version");
        if (version == FLAG_PROTOCOL_NONE)
            throw new IOException("Unable to verify the peer - no matching version");
        int numBytes = in.read();
        if ( (numBytes == -1) || (numBytes > 32) )
            throw new IOException("Unable to verify the peer - invalid num bytes");
        byte ip[] = new byte[numBytes];
        int read = DataHelper.read(in, ip);
        if (read != numBytes)
            throw new IOException("Unable to verify the peer - invalid num bytes");
        Date now = DataHelper.readDate(in);
        Properties opts = DataHelper.readProperties(in);

        return true;
    }
    
    /**
     * The peer contacting us is just testing us.  Verify that we are reachable
     * by following the protocol, then close the socket.  This is called only 
     * after reading the initial 0xFFFF.
     *
     */
    private void handleTest() {
        try {
            // read: #versions + v1 [+ v2 [etc]] + properties
            int numVersions = _rawIn.read();
            if (numVersions == -1) throw new IOException("Unable to read versions");
            if (numVersions > MAX_VERSIONS) throw new IOException("Too many versions");
            int versions[] = new int[numVersions];
            for (int i = 0; i < numVersions; i++) {
                versions[i] = _rawIn.read();
                if (versions[i] == -1)
                    throw new IOException("Not enough versions");
            }
            Properties opts = DataHelper.readProperties(_rawIn);
            
            int version = 0;
            for (int i = 0; i < versions.length && version == 0; i++) {
                for (int j = 0; j < TCPTransport.SUPPORTED_PROTOCOLS.length; j++) {
                    if (TCPTransport.SUPPORTED_PROTOCOLS[j] == versions[i]) {
                        version = versions[i];
                        break;
                    }
                }
            }
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("HandleTest: version=" + version + " opts=" +opts);
            
            // send: 0xFFFF + versionOk + #bytesIP + IP + currentTime + properties
            DataHelper.writeLong(_rawOut, 2, FLAG_TEST);
            _rawOut.write(version);
            byte ip[] = _from.getBytes();
            _rawOut.write(ip.length);
            _rawOut.write(ip);
            DataHelper.writeDate(_rawOut, new Date(_context.clock().now()));
            DataHelper.writeProperties(_rawOut, null);
            _rawOut.flush();
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("HandleTest: result flushed");
            
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to verify test connection from " + _from, ioe);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to verify test connection from " + _from, dfe);
        } finally {
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
            _testComplete = true;
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
        //_connectionIn = _rawIn;
        //_connectionOut = _rawOut;
        
        Hash peer = _actualPeer.getIdentity().getHash();
        _context.netDb().store(peer, _actualPeer);
        _transport.getTagManager().replaceTag(peer, _nextConnectionTag, _key);
    }
    
    public String getError() { return _error; }
    public boolean getTestComplete() { return _testComplete; }
    
    /**
     * Kill the handler, closing all sockets and streams, setting everything 
     * back to failure states, and setting the given error.
     *
     */
    private void fail(String error) {
        fail(error, null);
    }
    private void fail(String error, Exception e) {
        if (_error == null) // only grab the first error
            _error = error;
        
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
     * Verify the reachability of a peer.  
     *   Usage: <code>ConnectionHandler hostname portNum</code>
     */
    public static void main(String args[]) {
        if (false) args = new String[] { "dev.i2p.net", "4108" };
        
        if ( (args == null) || (args.length != 2) ) {
            System.out.println("Usage: ConnectionHandler hostname portNum");
            System.exit(0);
        }
        
        try {
            int port = Integer.parseInt(args[1]);
            TCPAddress addr = new TCPAddress(args[0], port);
            boolean ok = verifyReachability(addr);
            if (ok) 
                System.out.println("Peer is reachable: " + addr.toString());
            else
                System.out.println("Peer is not reachable: " + addr.toString());
        } catch (Exception e) {
            System.out.println("Peer is not reachable: " + args[0] + ":" + args[1]);
            e.printStackTrace();
        }
    }
}
