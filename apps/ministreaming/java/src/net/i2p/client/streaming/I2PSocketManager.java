/*
 * licensed under BSD license...
 * (if you know the proper clause for that, add it ...)
 */
package net.i2p.client.streaming;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.data.DataFormatException;
import net.i2p.util.Log;

/**
 * Centralize the coordination and multiplexing of the local client's streaming.
 * There should be one I2PSocketManager for each I2PSession, and if an application
 * is sending and receiving data through the streaming library using an
 * I2PSocketManager, it should not attempt to call I2PSession's setSessionListener
 * or receive any messages with its .receiveMessage
 *
 */
public class I2PSocketManager implements I2PSessionListener {
    private final static Log _log = new Log(I2PSocketManager.class);
    private I2PSession _session;
    private I2PServerSocketImpl _serverSocket = null;
    private Object lock = new Object(); // for locking socket lists
    private HashMap _outSockets;
    private HashMap _inSockets;
    private I2PSocketOptions _defaultOptions;
    private long _acceptTimeout;
    
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
    
    public I2PSocketManager() {
        _session = null;
        _inSockets = new HashMap(16);
        _outSockets = new HashMap(16);
        _acceptTimeout = ACCEPT_TIMEOUT_DEFAULT;
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
        _log.info("Disconnected from the session");
    }

    public void errorOccurred(I2PSession session, String message, Throwable error) {
        _log.error("Error occurred: [" + message + "]", error);
    }
    
    public void messageAvailable(I2PSession session, int msgId, long size) {
        try {
            I2PSocketImpl s;
            byte msg[] = session.receiveMessage(msgId);
            if (msg.length == 1 && msg[0] == -1) {
                _log.debug("Ping received");
                return;
            }
            if (msg.length < 4) {
                _log.error("==== packet too short ====");
                return;
            }
            int type = msg[0] & 0xff;
            String id = toString(new byte[] { msg[1], msg[2], msg[3]});
            byte[] payload = new byte[msg.length - 4];
            System.arraycopy(msg, 4, payload, 0, payload.length);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Message read: type = [" + Integer.toHexString(type) 
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
                case CHAFF:
                    // ignore
                    return;
                default:
                    handleUnknown(type, id, payload);
                    return;
            }
        } catch (I2PException ise) {
            _log.error("Error processing", ise);
        } catch (IllegalStateException ise) {
            _log.debug("Error processing", ise);
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
        I2PSocketImpl s = null;
        synchronized (lock) {
            s = (I2PSocketImpl) _outSockets.get(id);
        }

        if (s == null) {
            _log.warn("No socket responsible for ACK packet");
            return;
        }

        String remoteId = null;
        remoteId = s.getRemoteID(false); 

        if ( (payload.length == 3) && (remoteId == null) ) {
            String newID = toString(payload);
            s.setRemoteID(newID);
            return;
        } else {
            // (payload.length != 3 || getRemoteId != null)
            if (_log.shouldLog(Log.WARN)) {
                if (payload.length != 3)
                    _log.warn("Ack packet had " + payload.length + " bytes");
                else
                    _log.warn("Remote ID already exists? " + remoteId);
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
        
        _log.debug("*Disconnect outgoing!");
        try {
            if (s != null) {
                if (payload.length > 0) {
                    _log.debug("Disconnect packet had "
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
            _log.error("Ignoring error on disconnect", t);
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
            _log.debug("*Packet send outgoing [" + payload.length + "]");
        if (s != null) {
            s.queueData(payload);
            return;
        } else {
            _log.error("Null socket with data available");
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
        _log.debug("*Syn!");
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
        
        if (!acceptConnections) {
            // The app did not instantiate an I2PServerSocket
            byte[] packet = makePacket((byte) CLOSE_OUT, id, toBytes(newLocalID));
            boolean replySentOk = false;
            synchronized (_session) {
                replySentOk = _session.sendMessage(d, packet);
            }
            if (!replySentOk) {
                _log.error("Error sending close to " + d.calculateHash().toBase64()
                           + " in response to a new con message", 
                           new Exception("Failed creation"));
            }
            return;
        }

        if (_serverSocket.addWaitForAccept(s, _acceptTimeout)) {
            _inSockets.put(newLocalID, s);
            byte[] packet = makePacket((byte) ACK, id, toBytes(newLocalID));
            boolean replySentOk = false;
            replySentOk = _session.sendMessage(d, packet);
            if (!replySentOk) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error sending reply to " + d.calculateHash().toBase64()
                               + " in response to a new con message",
                               new Exception("Failed creation"));
                s.internalClose();
            }
        } else {
            // timed out or serverSocket closed
            byte[] packet = toBytes(" " + id);
            packet[0] = CLOSE_OUT;
            boolean nackSent = session.sendMessage(d, packet);
            if (!nackSent) {
                _log.warn("Error sending NACK for session creation");
            }
            s.internalClose();
        }
        return;
    }
    
    /**
     * We've received a disconnect for a socket we didn't initiate, so kill
     * the socket.
     *
     */
    private void disconnectIncoming(String id, byte payload[]) {
        _log.debug("*Disconnect incoming!");
        I2PSocketImpl s = null;
        synchronized (lock) {
            s = (I2PSocketImpl) _inSockets.get(id);
            if (payload.length == 0 && s != null) {
                _inSockets.remove(id);
            }
        }
        
        try {
            if (payload.length == 0 && s != null) {
                s.internalClose();
                return;
            } else {
                if ( (payload.length > 0) && (_log.shouldLog(Log.ERROR)) )
                    _log.error("Disconnect packet had " + payload.length + " bytes");
                if (s != null) 
                    s.internalClose();
                return;
            }
        } catch (Exception t) {
            _log.error("Ignoring error on disconnect", t);
            return;
        }
    }
    
    /**
     * We've received data on a stream we received - toss the data onto
     * the socket for handling.
     *
     * @throws IllegalStateException if the socket isn't open or isn't known
     */
    private void sendIncoming(String id, byte payload[]) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("*Packet send incoming [" + payload.length + "]");
        I2PSocketImpl s = null;
        synchronized (lock) {
            s = (I2PSocketImpl) _inSockets.get(id);
        }
        
        if (s != null) {
            s.queueData(payload);
            return;
        } else {
            _log.info("Null socket with data available");
            throw new IllegalStateException("Null socket with data available");
        }
    }
    
    /**
     * Unknown packet.  moo.
     *
     */
    private void handleUnknown(int type, String id, byte payload[]) {
        _log.error("\n\n=============== Unknown packet! " + "============" 
                   + "\nType: " + (int) type
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
        _log.error("Abuse reported [" + severity + "]");
    }

    public void setDefaultOptions(I2PSocketOptions options) {
        _defaultOptions = options;
    }

    public I2PSocketOptions getDefaultOptions() {
        return _defaultOptions;
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
        try {
            ByteArrayOutputStream pubkey = new ByteArrayOutputStream();
            _session.getMyDestination().writeBytes(pubkey);
            String remoteID;
            byte[] packet = makePacket((byte) SYN, localID, pubkey.toByteArray());
            boolean sent = false;
            sent = _session.sendMessage(peer, packet);
            if (!sent) {
                _log.info("Unable to send & receive ack for SYN packet");
                synchronized (lock) {
                    _outSockets.remove(s.getLocalID());
                }
                throw new I2PException("Error sending through I2P network");
            }
            remoteID = s.getRemoteID(true, options.getConnectTimeout());
            
            if (remoteID == null) 
                throw new ConnectException("Connection refused by peer");
            if ("".equals(remoteID)) 
                throw new NoRouteToHostException("Unable to reach peer");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("TIMING: s given out for remoteID " 
                           + getReadableForm(remoteID));
            
            return s;
        } catch (InterruptedIOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Timeout waiting for ack from syn for id " 
                           + getReadableForm(lcID), ioe);
            synchronized (lock) {
                _outSockets.remove(s.getLocalID());
            }
            s.internalClose();
            throw new InterruptedIOException("Timeout waiting for ack");
        } catch (ConnectException ex) {
            s.internalClose();
            throw ex;
        } catch (NoRouteToHostException ex) {
            s.internalClose();
            throw ex;
        } catch (IOException ex) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error sending syn on id " + getReadableForm(lcID), ex);
            synchronized (lock) {
                _outSockets.remove(s.getLocalID());
            }
            s.internalClose();
            throw new I2PException("Unhandled IOException occurred");
        } catch (I2PException ex) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Error sending syn on id " + getReadableForm(lcID), ex);
            synchronized (lock) {
                _outSockets.remove(s.getLocalID());
            }
            s.internalClose();
            throw ex;
        } catch (Exception e) {
            s.internalClose();
            _log.error("Unhandled error connecting", e);
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
                    _log.debug("Closing inSocket \""
                               + getReadableForm(sock.getLocalID()) + "\"");
                sock.internalClose();
            }
            
            iter = _outSockets.keySet().iterator();
            while (iter.hasNext()) {
                id = (String)iter.next();
                sock = (I2PSocketImpl)_outSockets.get(id);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Closing outSocket \""
                               + getReadableForm(sock.getLocalID()) + "\"");
                sock.internalClose();
            }            
        }

        _log.debug("Waiting for all open sockets to really close...");
        synchronized (lock) {
            while ((_inSockets.size() != 0) || (_outSockets.size() != 0)) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {}
            }
        }

        try {
            _log.debug("Destroying I2P session...");
            _session.destroySession();
            _log.debug("I2P session destroyed");
        } catch (I2PSessionException e) {
            _log.error("Error destroying I2P session", e);
        }
    }

    /**
     * Retrieve a set of currently connected I2PSockets, either initiated locally or remotely.
     *
     */
    public Set listSockets() {
        Set sockets = new HashSet(8);
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
            _log.error("I2PException:", ex);
            return false;
        }
    }

    public void removeSocket(I2PSocketImpl sock) {
        synchronized (lock) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Removing socket \"" + getReadableForm(sock.getLocalID()) + "\"");
            _inSockets.remove(sock.getLocalID());
            _outSockets.remove(sock.getLocalID());
            lock.notify();
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
