package net.i2p.router.transport.tcp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.util.Date;

import net.i2p.crypto.AESInputStream;
import net.i2p.crypto.AESOutputStream;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterIdentity;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.BandwidthLimitedInputStream;
import net.i2p.router.transport.BandwidthLimitedOutputStream;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * TCPConnection that validates the time and protocol version, dropping connection if
 * the clocks are too skewed or the versions don't match.
 *
 */
class RestrictiveTCPConnection extends TCPConnection {
    private Log _log;
    
    public RestrictiveTCPConnection(RouterContext context, Socket s, boolean locallyInitiated) {
        super(context, s, locallyInitiated);
        _log = context.logManager().getLog(RestrictiveTCPConnection.class);
        _context.statManager().createRateStat("tcp.establishConnectionTime", "How long does it take for us to successfully establish a connection (either locally or remotely initiated)?", "TCP Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
    
    /** passed in the handshake process for the connection, and only equivilant protocols will be accepted */
    private final static long PROTO_ID = 12;
    
    private boolean validateVersion() throws DataFormatException, IOException {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Before validating version");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8);
        DataHelper.writeLong(baos, 4, PROTO_ID);
        byte encr[] = _context.AESEngine().safeEncrypt(baos.toByteArray(),  _key, _iv, 16);
        DataHelper.writeLong(_out, 2, encr.length);
        _out.write(encr);
        
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Version sent");
        // we've sent our version, now read what theirs is
        
        int rlen = (int)DataHelper.readLong(_in, 2);
        byte pencr[] = new byte[rlen];
        int read = DataHelper.read(_in, pencr);
        if (read != rlen)
            throw new DataFormatException("Not enough data in peer version");
        byte decr[] = _context.AESEngine().safeDecrypt(pencr, _key, _iv);
        if (decr == null)
            throw new DataFormatException("Unable to decrypt - failed version?");
        
        ByteArrayInputStream bais = new ByteArrayInputStream(decr);
        long peerProtoId = DataHelper.readLong(bais, 4);
        
        
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Version received [" + peerProtoId + "]");
        
        return validateVersion(PROTO_ID, peerProtoId);
    }
    
    private boolean validateVersion(long us, long them) throws DataFormatException, IOException {
        if (us != them) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("INVALID PROTOCOL VERSIONS!  us = " + us + " them = " + them + ": " + _remoteIdentity.getHash());
            if (them > us)
                _context.router().setHigherVersionSeen(true);
            return false;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Valid protocol version: us = " + us + " them = " + them + ": " + _remoteIdentity.getHash());
            return true;
        }
    }
    
    private boolean validateTime() throws DataFormatException, IOException {
        Date now = new Date(_context.clock().now());
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8);
        DataHelper.writeDate(baos, now);
        
        byte encr[] = _context.AESEngine().safeEncrypt(baos.toByteArray(),  _key, _iv, 16);
        DataHelper.writeLong(_out, 2, encr.length);
        _out.write(encr);
        
        // we've sent our date, now read what theirs is
        
        int rlen = (int)DataHelper.readLong(_in, 2);
        byte pencr[] = new byte[rlen];
        int read = DataHelper.read(_in, pencr);
        if (read != rlen)
            throw new DataFormatException("Not enough data in peer date");
        byte decr[] = _context.AESEngine().safeDecrypt(pencr, _key, _iv);
        if (decr == null)
            throw new DataFormatException("Unable to decrypt - failed date?");
        
        ByteArrayInputStream bais = new ByteArrayInputStream(decr);
        Date theirNow = DataHelper.readDate(bais);
        
        long diff = now.getTime() - theirNow.getTime();
        if ( (diff > Router.CLOCK_FUDGE_FACTOR) || (diff < (0-Router.CLOCK_FUDGE_FACTOR)) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Peer is out of time sync!  They think it is " + theirNow + ": " + _remoteIdentity.getHash(), new Exception("Time sync error - please make sure your clock is correct!"));
            return false;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Peer sync difference: " + diff + "ms: " + _remoteIdentity.getHash());
            return true;
        }
    }
    
    /**
     * Exchange TCP addresses, and if we're didn't establish this connection, validate
     * the peer with validatePeerAddresses(TCPAddress[]).
     *
     * @return true if the peer is valid (and reachable)
     */
    private boolean validatePeerAddress() throws DataFormatException, IOException {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Before sending my addresses");
        TCPAddress me[] = _transport.getMyAddresses();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Sending " + me.length + " addresses");
        DataHelper.writeLong(baos, 1, me.length);
        for (int i = 0; i < me.length; i++) {
            DataHelper.writeString(baos, me[i].getHost());
            DataHelper.writeLong(baos, 2, me[i].getPort());
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Sent my address [" + me[i].getHost() + ":" + me[i].getPort() + "]");
        }
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Sent my " + me.length + " addresses");
        
        byte encr[] = _context.AESEngine().safeEncrypt(baos.toByteArray(), _key, _iv, 256);
        DataHelper.writeLong(_out, 2, encr.length);
        _out.write(encr);
        
        // we've sent our addresses, now read their addresses
        
        int rlen = (int)DataHelper.readLong(_in, 2);
        byte pencr[] = new byte[rlen];
        int read = DataHelper.read(_in, pencr);
        if (read != rlen)
            throw new DataFormatException("Not enough data in peer addresses");
        byte decr[] = _context.AESEngine().safeDecrypt(pencr, _key, _iv);
        if (decr == null)
            throw new DataFormatException("Unable to decrypt - invalid addresses?");
        
        ByteArrayInputStream bais = new ByteArrayInputStream(decr);
        long numAddresses = DataHelper.readLong(bais, 1);
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Peer will send us " + numAddresses + " addresses");
        TCPAddress peer[] = new TCPAddress[(int)numAddresses];
        for (int i = 0; i < peer.length; i++) {
            String host = DataHelper.readString(bais);
            int port = (int)DataHelper.readLong(bais, 2);
            peer[i] = new TCPAddress(host, port);
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Received peer address [" + peer[i].getHost() + ":" + peer[i].getPort() + "]");
        }
        
        // ok, we've received their addresses, now we determine whether we need to
        // validate them or not
        if (weInitiatedConnection()) {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("We initiated the connection, so no need to validate");
            return true; // we connected to them, so we know we can, um, connect to them
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("We received the connection, so validate");
            boolean valid = validatePeerAddresses(peer);
            if (_log.shouldLog(Log.DEBUG)) _log.debug("We received the connection, validated? " + valid);
            return valid;
        }
    }
    
    /**
     * They connected to us, but since we don't want to deal with restricted route topologies
     * (yet), we want to make sure *they* are reachable by other people.  In the long run, we'll
     * likely want to test this by routing messages through random peers to see if *they* can
     * contact them (but only when we want to determine whether to use them as a gateway, etc).
     *
     * Oh, I suppose I should explain what this method does, not just why.  Ok, this iterates
     * through all of the supplied TCP addresses attempting to open a socket.  If it receives
     * any data on that socket, we'll assume their address is valid and we're satisfied.  (yes,
     * this means it could point at random addresses, etc - this is not sufficient for dealing
     * with hostile peers, just with misconfigured peers).  If we can't find a peer address that
     * we can connect to, they suck and can go eat worms.
     *
     */
    private boolean validatePeerAddresses(TCPAddress addresses[]) throws DataFormatException, IOException {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Before validating peer addresses [" + addresses.length + "]...");
        for (int i = 0; i < addresses.length; i++) {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Before validating peer address (" + addresses[i].getHost() + ":"+ addresses[i].getPort() + ")...");
            boolean ok = sendsUsData(addresses[i]);
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Before validating peer address (" + addresses[i].getHost() + ":"+ addresses[i].getPort() + ") [" + ok + "]...");
            if (ok) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Peer address " + addresses[i].getHost() + ":" + addresses[i].getPort() + " validated!");
                return true;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Peer address " + addresses[i].getHost() + ":" + addresses[i].getPort() + " could NOT be validated");
            }
        }
        if (_log.shouldLog(Log.WARN))
            _log.warn("None of the peer addresses could be validated!");
        return false;
    }
    
    private boolean sendsUsData(TCPAddress peer) {
        SocketCreator creator = new SocketCreator(peer.getHost(), peer.getPort(), false);
        I2PThread sockCreator = new I2PThread(creator);
        sockCreator.setDaemon(true);
        sockCreator.setName("PeerCallback");
        sockCreator.setPriority(I2PThread.MIN_PRIORITY);
        sockCreator.start();
        
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Before joining socket creator via peer callback...");
        try {
            synchronized (creator) {
                creator.wait(TCPTransport.SOCKET_CREATE_TIMEOUT);
            }
        } catch (InterruptedException ie) {}
        
        boolean established = creator.couldEstablish();
        // returns a socket if and only if the connection was established and the I2P handshake byte sent and received
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("After joining socket creator via peer callback [could establish? " + established + "]");
        return established;
    }
    
    public RouterIdentity establishConnection() {
        long start = _context.clock().now();
        long success = 0;
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Establishing connection...");
        BigInteger myPub = _builder.getMyPublicValue();
        try {
            _socket.setSoTimeout(ESTABLISHMENT_TIMEOUT);
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Before key exchange...");
            exchangeKey();
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Key exchanged...");
            // key exchanged.  now say who we are and prove it
            boolean ok = identifyStationToStation();
            if (_log.shouldLog(Log.DEBUG)) _log.debug("After station to station [" + ok + "]...");
            
            if (!ok)
                throw new DataFormatException("Station to station identification failed!  MITM?");
            
            
            if (_log.shouldLog(Log.DEBUG)) _log.debug("before validateVersion...");
            boolean versionOk = validateVersion();
            if (_log.shouldLog(Log.DEBUG)) _log.debug("after validateVersion [" + versionOk + "]...");
            
            if (!versionOk) {
                // not only do we remove the reference to the invalid peer
                _context.netDb().fail(_remoteIdentity.getHash());
                // but we make sure that we don't try to talk to them soon even if we get a new ref
                _context.shitlist().shitlistRouter(_remoteIdentity.getHash());
                throw new DataFormatException("Peer uses an invalid version!  dropping");
            }
            
            if (_log.shouldLog(Log.DEBUG)) _log.debug("before validateTime...");
            boolean timeOk = validateTime();
            if (_log.shouldLog(Log.DEBUG)) _log.debug("after validateTime [" + timeOk + "]...");
            if (!timeOk) {
                _context.shitlist().shitlistRouter(_remoteIdentity.getHash());
                throw new DataFormatException("Peer is too far out of sync with the current router's clock!  dropping");
            }
            
            if (_log.shouldLog(Log.DEBUG)) _log.debug("before validate peer address...");
            boolean peerReachable = validatePeerAddress();
            if (_log.shouldLog(Log.DEBUG)) _log.debug("after validatePeerAddress [" + peerReachable + "]...");
            if (!peerReachable) {
                _context.shitlist().shitlistRouter(_remoteIdentity.getHash());
                throw new DataFormatException("Peer provided us with an unreachable router address, and we can't handle restricted routes yet!  dropping");
            }
            
            if (_log.shouldLog(Log.INFO))
                _log.info("TCP connection " + _id + " established with " + _remoteIdentity.getHash().toBase64());
            _in = new AESInputStream(_context, new BandwidthLimitedInputStream(_context, _in, _remoteIdentity), _key, _iv);
            _out = new AESOutputStream(_context, new BandwidthLimitedOutputStream(_context, _out, _remoteIdentity), _key, _iv);
            _socket.setSoTimeout(0);
            success = _context.clock().now();
            established();
            return _remoteIdentity;
            
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection with " + _socket.getInetAddress().getHostAddress() + ":" + _socket.getPort(), ioe);
            closeConnection();
            return null;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error establishing connection with " + _socket.getInetAddress().getHostAddress() + ":" + _socket.getPort(), dfe);
            closeConnection();
            return null;
        } catch (Throwable t) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("jrandom is paranoid so we're catching it all during establishConnection " + _socket.getInetAddress().getHostAddress() + ":" + _socket.getPort(), t);
            closeConnection();
            return null;
        } finally {
            if (success > 0)
                _context.statManager().addRateData("tcp.establishConnectionTime", success-start, success-start);
        }
    }
}
