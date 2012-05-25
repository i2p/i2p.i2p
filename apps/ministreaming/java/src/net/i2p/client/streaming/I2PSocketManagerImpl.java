/*
 * licensed under BSD license...
 * (if you know the proper clause for that, add it ...)
 */
package net.i2p.client.streaming;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;


/**
 * Centralize the coordination and multiplexing of the local client's streaming.
 * There should be one I2PSocketManager for each I2PSession, and if an application
 * is sending and receiving data through the streaming library using an
 * I2PSocketManager, it should not attempt to call I2PSession's setSessionListener
 * or receive any messages with its .receiveMessage
 *
 */
class I2PSocketManagerImpl implements I2PSocketManager, I2PSessionListener {
    private I2PAppContext _context;
    private Log _log;
    private /* final */ I2PSession _session;
    private I2PServerSocketImpl _serverSocket = null;
    private final Object lock = new Object(); // for locking socket lists
    private HashMap<String,I2PSocket> _outSockets;
    private HashMap<String,I2PSocket> _inSockets;
    private I2PSocketOptions _defaultOptions;
    private long _acceptTimeout;
    private String _name;
    private final List<DisconnectListener> _listeners = new ArrayList<DisconnectListener>(1);;
    private static int __managerId = 0;
    
    public static final short ACK = 0x51;
    public static final short CLOSE_OUT = 0x52;
    public static final short DATA_OUT = 0x50;
    public static final short SYN = 0xA1;
    public static final short CLOSE_IN = 0xA2;
    public static final short DATA_IN = 0xA0;
    public static final short CHAFF = 0xFF;

    /**
     * How long to wait for the client app to accept() before sending back CLOSE?
     * This includes the time waiting in the queue.  Currently set to 5 seconds.
     */
    private static final long ACCEPT_TIMEOUT_DEFAULT = 5*1000;
    
    public I2PSocketManagerImpl() {
        this("SocketManager " + (++__managerId));
    }
    public I2PSocketManagerImpl(String name) {
        init(I2PAppContext.getGlobalContext(), null, null, name);
    }
    
    public void init(I2PAppContext context, I2PSession session, Properties opts, String name) {
        _name = name;
        _context = context;
        _log = _context.logManager().getLog(I2PSocketManager.class);
        _inSockets = new HashMap<String,I2PSocket>(16);
        _outSockets = new HashMap<String,I2PSocket>(16);
        _acceptTimeout = ACCEPT_TIMEOUT_DEFAULT;
        // _listeners = new ArrayList<DisconnectListener>(1);
        setSession(session);
        setDefaultOptions(buildOptions(opts));
        _context.statManager().createRateStat("streaming.lifetime", "How long before the socket is closed?", "streaming", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("streaming.sent", "How many bytes are sent in the stream?", "streaming", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("streaming.received", "How many bytes are received in the stream?", "streaming", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("streaming.transferBalance", "How many streams send more than they receive (positive means more sent, negative means more received)?", "streaming", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("streaming.synNoAck", "How many times have we sent a SYN but not received an ACK?", "streaming", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("streaming.ackSendFailed", "How many times have we tried to send an ACK to a SYN and failed?", "streaming", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("streaming.nackSent", "How many times have we refused a SYN with a NACK?", "streaming", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("streaming.nackReceived", "How many times have we received a NACK to our SYN?", "streaming", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }

    public I2PSession getSession() {
        return _session;
    }

    public void setSession(I2PSession session) {
        _session = session;
        if (session != null) session.setSessionListener(this);
    }
    
    /**
     * How long should we wait for the client to .accept() a socket before
     * sending back a NACK/Close?  
     *
     * @param ms milliseconds to wait, maximum
     */
    public void setAcceptTimeout(long ms) { _acceptTimeout = ms; }
    public long getAcceptTimeout() { return _acceptTimeout; }

    public void disconnected(I2PSession session) {
        _log.info(getName() + ": Disconnected from the session");
        destroySocketManager();
        List<DisconnectListener> listeners = null;
        synchronized (_listeners) {
            listeners = new ArrayList<DisconnectListener>(_listeners);
            _listeners.clear();
        }
        for (int i = 0; i < listeners.size(); i++) {
            I2PSocketManager.DisconnectListener lsnr = (I2PSocketManager.DisconnectListener)listeners.get(i);
            lsnr.sessionDisconnected();
        }
    }

    public void errorOccurred(I2PSession session, String message, Throwable error) {
        _log.error(getName() + ": Error occurred: [" + message + "]", error);
    }
    
    public void messageAvailable(I2PSession session, int msgId, long size) {
        try {
            I2PSocketImpl s;
            byte msg[] = session.receiveMessage(msgId);
            if (msg.length == 1 && msg[0] == -1) {
                _log.debug(getName() + ": Ping received");
                return;
            }
            if (msg.length < 4) {
                _log.warn(getName() + ": ==== packet too short ====");
                return;
            }
            int type = msg[0] & 0xff;
            String id = toString(new byte[] { msg[1], msg[2], msg[3]});
            byte[] payload = new byte[msg.length - 4];
            System.arraycopy(msg, 4, payload, 0, payload.length);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getName() + ": Message read: type = [" + Integer.toHexString(type) 
                           + "] id = [" + getReadableForm(id)
                           + "] payload length: [" + payload.length + "]");
            switch (type) {
                case ACK:
                    ackAvailable(id, payload);
                    return;
                case CLOSE_OUT:
                    disconnectAvailable(id, payload);
                    return;
                case DATA_OUT:
                    sendOutgoingAvailable(id, payload);
                    return;
                case SYN:
                    synIncomingAvailable(id, payload, session);
                    return;
                case CLOSE_IN:
                    disconnectIncoming(id, payload);
                    return;
                case DATA_IN:
                    sendIncoming(id, payload);
		    return;
                case CHAFF:
                    // ignore
                    return;
                default:
                    handleUnknown(type, id, payload);
                    return;
            }
        } catch (I2PException ise) {
            _log.warn(getName() + ": Error processing", ise);
        } catch (IllegalStateException ise) {
            _log.debug(getName() + ": Error processing", ise);
        }
    }
    
    /**
     * We've received an ACK packet (hopefully, in response to a SYN that we 
     * recently sent out).  Notify the associated I2PSocket that we now have
     * the remote stream ID (which should get things going, since the handshake
     * is complete).
     *
     */
    private void ackAvailable(String id, byte payload[]) {
        long begin = _context.clock().now();
        I2PSocketImpl s = null;
        synchronized (lock) {
            s = (I2PSocketImpl) _outSockets.get(id);
        }

        if (s == null) {
            _log.warn(getName() + ": No socket responsible for ACK packet for id " + getReadableForm(id));
            return;
        }

        long socketRetrieved = _context.clock().now();
        
        String remoteId = null;
        remoteId = s.getRemoteID(false); 

        if ( (payload.length == 3) && (remoteId == null) ) {
            String newID = toString(payload);
            long beforeSetRemId = _context.clock().now();
            s.setRemoteID(newID);
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(getName() + ": ackAvailable - socket retrieval took " 
                           + (socketRetrieved-begin) + "ms, getRemoteId took "
                           + (beforeSetRemId-socketRetrieved) + "ms, setRemoteId took "
                           + (_context.clock().now()-beforeSetRemId) + "ms");
            }
            return;
        } else {
            // (payload.length != 3 || getRemoteId != null)
            if (_log.shouldLog(Log.WARN)) {
                if (payload.length != 3)
                    _log.warn(getName() + ": Ack packet had " + payload.length + " bytes");
                else
                    _log.warn(getName() + ": Remote ID already exists? " + remoteId);
            }
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(getName() + ": invalid ack - socket retrieval took " 
                           + (socketRetrieved-begin) + "ms, overall took "
                           + (_context.clock().now()-begin) + "ms");
            }
            return;
        }
    }
    
    /**
     * We received a disconnect packet, telling us to tear down the specified
     * stream.
     */
    private void disconnectAvailable(String id, byte payload[]) {
        I2PSocketImpl s = null;
        synchronized (lock) {
            s = (I2PSocketImpl) _outSockets.get(id);
        }
        
        _log.debug(getName() + ": *Disconnect outgoing for socket " + s + " on id "
                   + getReadableForm(id));
        try {
            if (s != null) {
                if (payload.length > 0) {
                    _log.debug(getName() + ": Disconnect packet had "
                               + payload.length + " bytes");
                }
                if (s.getRemoteID(false) == null) {
                    s.setRemoteID(null); // Just to wake up socket
                    return;
                }
                s.internalClose();
                synchronized (lock) {
                    _outSockets.remove(id);
                }
            }
            return;
        } catch (Exception t) {
            _log.warn(getName() + ": Ignoring error on disconnect for socket " + s, t);
        }
    }
    
    /**
     * We've received data on a stream we created - toss the data onto
     * the socket for handling.
     *
     * @throws IllegalStateException if the socket isn't open or isn't known
     */
    private void sendOutgoingAvailable(String id, byte payload[]) throws IllegalStateException {
        I2PSocketImpl s = null;
        synchronized (lock) {
            s = (I2PSocketImpl) _outSockets.get(id);
        }

        // packet send outgoing
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getName() + ": *Packet send outgoing [" + payload.length + "] for socket " 
                       + s + " on id " + getReadableForm(id));
        if (s != null) {
            s.queueData(payload);
            return;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getName() + ": Null socket with data available");
            throw new IllegalStateException("Null socket with data available");
        }
    }
    
    /**
     * We've received a SYN packet (a request for a new stream).  If the client has 
     * said they want incoming sockets (by retrieving the serverSocket), the stream
     * will be ACKed, but if they have not, they'll be NACKed)
     *
     * @throws DataFormatException if the destination in the SYN was invalid
     * @throws I2PSessionException if there was an I2P error sending the ACK or NACK
     */
    private void synIncomingAvailable(String id, byte payload[], I2PSession session) 
                                      throws DataFormatException, I2PSessionException {
        Destination d = new Destination();
        d.fromByteArray(payload);

        I2PSocketImpl s = null;
        boolean acceptConnections = (_serverSocket != null);
        String newLocalID = null;
        synchronized (lock) {
            newLocalID = makeID(_inSockets);
            if (acceptConnections) {
                s = new I2PSocketImpl(d, this, false, newLocalID);
                s.setRemoteID(id);
            }
        }    
        _log.debug(getName() + ": *Syn! for socket " + s + " on id " + getReadableForm(newLocalID) 
                   + " from " + d.calculateHash().toBase64().substring(0,6));
        
        if (!acceptConnections) {
            // The app did not instantiate an I2PServerSocket
            byte[] packet = makePacket((byte) CLOSE_OUT, id, toBytes(newLocalID));
            boolean replySentOk = false;
            synchronized (_session) {
                replySentOk = _session.sendMessage(d, packet);
            }
            if (!replySentOk) {
                _log.warn(getName() + ": Error sending close to " + d.calculateHash().toBase64()
                           + " in response to a new con message", 
                           new Exception("Failed creation"));
            }
            _context.statManager().addRateData("streaming.nackSent", 1, 1);
            return;
        }

        if (_serverSocket.addWaitForAccept(s, _acceptTimeout)) {
            _inSockets.put(newLocalID, s);
            byte[] packet = makePacket((byte) ACK, id, toBytes(newLocalID));
            boolean replySentOk = false;
            replySentOk = _session.sendMessage(d, packet);
            if (!replySentOk) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getName() + ": Error sending reply to " + d.calculateHash().toBase64()
                               + " in response to a new con message for socket " + s,
                               new Exception("Failed creation"));
                s.internalClose();
                _context.statManager().addRateData("streaming.ackSendFailed", 1, 1);
            }
        } else {
            // timed out or serverSocket closed
            byte[] packet = toBytes(" " + id);
            packet[0] = CLOSE_OUT;
            boolean nackSent = session.sendMessage(d, packet);
            if (!nackSent) {
                _log.warn(getName() + ": Error sending NACK for session creation for socket " + s);
            }
            s.internalClose();
            _context.statManager().addRateData("streaming,nackSent", 1, 1);
        }
        return;
    }
    
    /**
     * We've received a disconnect for a socket we didn't initiate, so kill
     * the socket.
     *
     */
    private void disconnectIncoming(String id, byte payload[]) {
        I2PSocketImpl s = null;
        synchronized (lock) {
            s = (I2PSocketImpl) _inSockets.get(id);
            if (payload.length == 0 && s != null) {
                _inSockets.remove(id);
            }
        }
        
        _log.debug(getName() + ": *Disconnect incoming for socket " + s);
        
        try {
            if (payload.length == 0 && s != null) {
                s.internalClose();
                return;
            } else {
                if ( (payload.length > 0) && (_log.shouldLog(Log.ERROR)) )
                    _log.warn(getName() + ": Disconnect packet had " + payload.length + " bytes");
                if (s != null) 
                    s.internalClose();
                return;
            }
        } catch (Exception t) {
            _log.warn(getName() + ": Ignoring error on disconnect", t);
            return;
        }
    }
    
    /**
     * We've received data on a stream we received - toss the data onto
     * the socket for handling.
     *
     * @throws IllegalStateException if the socket isn't open or isn't known
     */
    private void sendIncoming(String id, byte payload[]) throws IllegalStateException {
        I2PSocketImpl s = null;
        synchronized (lock) {
            s = (I2PSocketImpl) _inSockets.get(id);
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getName() + ": *Packet send incoming [" + payload.length + "] for socket " + s);
        
        if (s != null) {
            s.queueData(payload);
            return;
        } else {
            _log.info(getName() + ": Null socket with data available");
            throw new IllegalStateException("Null socket with data available");
        }
    }
    
    /**
     * Unknown packet.  moo.
     *
     */
    private void handleUnknown(int type, String id, byte payload[]) {
        _log.error(getName() + ": \n\n=============== Unknown packet! " + "============" 
                   + "\nType: " + type
                   + "\nID:   " + getReadableForm(id) 
                   + "\nBase64'ed Data: " + Base64.encode(payload)
                   + "\n\n\n");
        if (id != null) {
            synchronized (lock) {
                _inSockets.remove(id);
                _outSockets.remove(id);
            }
        }
    }
    
    public void reportAbuse(I2PSession session, int severity) {
        _log.error(getName() + ": Abuse reported [" + severity + "]");
    }

    public void setDefaultOptions(I2PSocketOptions options) {
        _defaultOptions = options;
    }

    public I2PSocketOptions getDefaultOptions() {
        return _defaultOptions;
    }
    
    public I2PSocketOptions buildOptions() { return buildOptions(null); }
    public I2PSocketOptions buildOptions(Properties opts) {
        return new I2PSocketOptionsImpl(opts);
    }

    public I2PServerSocket getServerSocket() {
        if (_serverSocket == null) {
            _serverSocket = new I2PServerSocketImpl(this);
        }
        return _serverSocket;
    }

    /**
     * Create a new connected socket (block until the socket is created)
     *
     * @param peer Destination to connect to
     * @param options I2P socket options to be used for connecting
     *
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws InterruptedIOException if the connection timeouts
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer, I2PSocketOptions options) 
                             throws I2PException, ConnectException, 
                             NoRouteToHostException, InterruptedIOException {
        String localID, lcID;
        I2PSocketImpl s;
        synchronized (lock) {
            localID = makeID(_outSockets);
            lcID = getReadableForm(localID);
            s = new I2PSocketImpl(peer, this, true, localID);
            _outSockets.put(localID, s);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getName() + ": connect(" + peer.calculateHash().toBase64().substring(0,6)
                       + ", ...): localID = " + lcID);
        
        try {
            ByteArrayOutputStream pubkey = new ByteArrayOutputStream();
            _session.getMyDestination().writeBytes(pubkey);
            String remoteID;
            byte[] packet = makePacket((byte) SYN, localID, pubkey.toByteArray());
            boolean sent = false;
            sent = _session.sendMessage(peer, packet);
            if (!sent) {
                _log.info(getName() + ": Unable to send & receive ack for SYN packet for socket " 
                          + s + " with localID = " + lcID);
                synchronized (lock) {
                    _outSockets.remove(s.getLocalID());
                }
                _context.statManager().addRateData("streaming.synNoAck", 1, 1);
                throw new I2PException("Error sending through I2P network");
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getName() + ": syn sent ok to " 
                               + peer.calculateHash().toBase64().substring(0,6) 
                               + " with localID = " + lcID);
            }
            if (options != null)
                remoteID = s.getRemoteID(true, options.getConnectTimeout());
            else
                remoteID = s.getRemoteID(true, getDefaultOptions().getConnectTimeout());
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getName() + ": remoteID received from " 
                           + peer.calculateHash().toBase64().substring(0,6) 
                           + ": " + getReadableForm(remoteID) 
                           + " with localID = " + lcID);
            
            if (remoteID == null) {
                _context.statManager().addRateData("streaming.nackReceived", 1, 1);
                throw new ConnectException("Connection refused by peer for socket " + s);
            }
            if ("".equals(remoteID)) {
                _context.statManager().addRateData("streaming.synNoAck", 1, 1);
                throw new NoRouteToHostException("Unable to reach peer for socket " + s);
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getName() + ": TIMING: s given out for remoteID " 
                           + getReadableForm(remoteID) + " for socket " + s);
            
            return s;
        } catch (InterruptedIOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getName() + ": Timeout waiting for ack from syn for id " 
                           + lcID + " to " + peer.calculateHash().toBase64().substring(0,6) 
                           + " for socket " + s, ioe);
            synchronized (lock) {
                _outSockets.remove(s.getLocalID());
            }
            s.internalClose();
            _context.statManager().addRateData("streaming.synNoAck", 1, 1);
            throw new InterruptedIOException("Timeout waiting for ack");
        } catch (ConnectException ex) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getName() + ": Connection error waiting for ack from syn for id " 
                           + lcID + " to " + peer.calculateHash().toBase64().substring(0,6) 
                           + " for socket " + s, ex);
            s.internalClose();
            throw ex;
        } catch (NoRouteToHostException ex) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getName() + ": No route to host waiting for ack from syn for id " 
                           + lcID + " to " + peer.calculateHash().toBase64().substring(0,6) 
                           + " for socket " + s, ex);
            s.internalClose();
            throw ex;
        } catch (IOException ex) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getName() + ": Error sending syn on id " 
                           + lcID + " to " + peer.calculateHash().toBase64().substring(0,6) 
                           + " for socket " + s, ex);
            synchronized (lock) {
                _outSockets.remove(s.getLocalID());
            }
            s.internalClose();
            throw new I2PException("Unhandled IOException occurred");
        } catch (I2PException ex) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getName() + ": Error sending syn on id " 
                          + lcID + " to " + peer.calculateHash().toBase64().substring(0,6) 
                          + " for socket " + s, ex);
            synchronized (lock) {
                _outSockets.remove(s.getLocalID());
            }
            s.internalClose();
            throw ex;
        } catch (Exception e) {
            s.internalClose();
            _log.warn(getName() + ": Unhandled error connecting on "
                       + lcID + " to " + peer.calculateHash().toBase64().substring(0,6) 
                       + " for socket " + s, e);
            throw new ConnectException("Unhandled error connecting: " + e.getMessage());
        }
    }

    /**
     * Create a new connected socket (block until the socket is created)
     *
     * @param peer Destination to connect to
     *
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws InterruptedIOException if the connection timeouts
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer) throws I2PException, ConnectException, 
                                               NoRouteToHostException, InterruptedIOException {
        return connect(peer, null);
    }

    /**
     * Destroy the socket manager, freeing all the associated resources.  This
     * method will block untill all the managed sockets are closed.
     *
     */
    public void destroySocketManager() {
        if (_serverSocket != null) {
            _serverSocket.close();
            _serverSocket = null;
        }

        synchronized (lock) {
            Iterator iter;
            String id = null;
            I2PSocketImpl sock;

            iter = _inSockets.keySet().iterator();
            while (iter.hasNext()) {
                id = (String)iter.next();
                sock = (I2PSocketImpl)_inSockets.get(id);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getName() + ": Closing inSocket \""
                               + getReadableForm(sock.getLocalID()) + "\"");
                sock.internalClose();
            }
            
            iter = _outSockets.keySet().iterator();
            while (iter.hasNext()) {
                id = (String)iter.next();
                sock = (I2PSocketImpl)_outSockets.get(id);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getName() + ": Closing outSocket \""
                               + getReadableForm(sock.getLocalID()) + "\"");
                sock.internalClose();
            }            
        }

        _log.debug(getName() + ": Waiting for all open sockets to really close...");
        synchronized (lock) {
            while ((_inSockets.size() != 0) || (_outSockets.size() != 0)) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {}
            }
        }

        try {
            _log.debug(getName() + ": Destroying I2P session...");
            _session.destroySession();
            _log.debug(getName() + ": I2P session destroyed");
        } catch (I2PSessionException e) {
            _log.warn(getName() + ": Error destroying I2P session", e);
        }
    }

    /**
     * Retrieve a set of currently connected I2PSockets, either initiated locally or remotely.
     *
     */
    public Set listSockets() {
        Set<I2PSocket> sockets = new HashSet<I2PSocket>(8);
        synchronized (lock) {
            sockets.addAll(_inSockets.values());
            sockets.addAll(_outSockets.values());
        }
        return sockets;
    }

    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     */
    public boolean ping(Destination peer, long timeoutMs) {
        try {
            return _session.sendMessage(peer, new byte[] { (byte) CHAFF});
        } catch (I2PException ex) {
            _log.warn(getName() + ": I2PException:", ex);
            return false;
        }
    }

    public void removeSocket(I2PSocketImpl sock) {
        String localId = sock.getLocalID();
        boolean removed = false;
        synchronized (lock) {
            removed = (null != _inSockets.remove(localId));
            removed = removed || (null != _outSockets.remove(localId));
            lock.notify();
        }

        long now = _context.clock().now();
        long lifetime = now - sock.getCreatedOn();
        long timeSinceClose = now - sock.getClosedOn();
        long sent = sock.getBytesSent();
        long recv = sock.getBytesReceived();

        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug(getName() + ": Removing socket \"" + getReadableForm(localId) + "\" [" + sock 
                       + ", send: " + sent + ", recv: " + recv
                       + ", lifetime: " + lifetime + "ms, time since close: " + timeSinceClose 
                       + " removed? " + removed + ")]",
                       new Exception("removeSocket called"));
        }

        _context.statManager().addRateData("streaming.lifetime", lifetime, lifetime);
        _context.statManager().addRateData("streaming.sent", sent, lifetime);
        _context.statManager().addRateData("streaming.received", recv, lifetime);
        
        if (sent > recv) {
            _context.statManager().addRateData("streaming.transferBalance", 1, lifetime);
        } else if (recv > sent) {
            _context.statManager().addRateData("streaming.transferBalance", -1, lifetime);
        } else {
            // noop
        }
    }

    public String getName() { return _name; }
    public void setName(String name) { _name = name; }
    
    public void addDisconnectListener(I2PSocketManager.DisconnectListener lsnr) { 
        synchronized (_listeners) {
            _listeners.add(lsnr);
        }
    }
    public void removeDisconnectListener(I2PSocketManager.DisconnectListener lsnr) {
        synchronized (_listeners) {
            _listeners.remove(lsnr);
        }
    }
    
    public static String getReadableForm(String id) {
        if (id == null) return "(null)";
        if (id.length() != 3) return "Bogus";
        return Base64.encode(toBytes(id));
    }

    /**
     * Create a new part the connection ID that is locally unique
     *
     * @param uniqueIn map of already known local IDs so we don't collide. WARNING - NOT THREADSAFE!
     */
    private static String makeID(HashMap uniqueIn) {
        String newID;
        do {
            int id = (int) (Math.random() * 16777215 + 1);
            byte[] nid = new byte[3];
            nid[0] = (byte) (id / 65536);
            nid[1] = (byte) ((id / 256) % 256);
            nid[2] = (byte) (id % 256);
            newID = toString(nid);
        } while (uniqueIn.get(newID) != null);
        return newID;
    }

    /**
     * Create a new packet of the given type for the specified connection containing
     * the given payload
     */
    public static byte[] makePacket(byte type, String id, byte[] payload) {
        byte[] packet = new byte[payload.length + 4];
        packet[0] = type;
        byte[] temp = toBytes(id);
        if (temp.length != 3) throw new RuntimeException("Incorrect ID length: " + temp.length);
        System.arraycopy(temp, 0, packet, 1, 3);
        System.arraycopy(payload, 0, packet, 4, payload.length);
        return packet;
    }
    
    private static final String toString(byte data[]) {
        try {
            return new String(data, "ISO-8859-1");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("WTF!  iso-8859-1 isn't supported?");
        }
    }
    
    private static final byte[] toBytes(String str) {
        try {
            return str.getBytes("ISO-8859-1");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("WTF!  iso-8859-1 isn't supported?");
        }
    }
}
