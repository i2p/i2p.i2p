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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.Base64;
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
public class I2PSocketManager implements I2PSessionListener {
    private final static Log _log = new Log(I2PSocketManager.class);
    private I2PSession _session;
    private I2PServerSocketImpl _serverSocket;
    private Object lock = new Object(); // for locking socket lists
    private HashMap _outSockets;
    private HashMap _inSockets;
    private I2PSocketOptions _defaultOptions;

    public static final int PUBKEY_LENGTH = 387;

    public I2PSocketManager() {
        _session = null;
        _serverSocket = new I2PServerSocketImpl(this);
        _inSockets = new HashMap(16);
        _outSockets = new HashMap(16);
    }

    public I2PSession getSession() {
        return _session;
    }

    public void setSession(I2PSession session) {
        _session = session;
        if (session != null) session.setSessionListener(this);
    }

    public void disconnected(I2PSession session) {
        _log.error("Disconnected from the session");
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
            String id = new String(new byte[] { msg[1], msg[2], msg[3]}, "ISO-8859-1");
            byte[] payload = new byte[msg.length - 4];
            System.arraycopy(msg, 4, payload, 0, payload.length);
            _log.debug("Message read: type = [" + Integer.toHexString(type) + "] id = [" + getReadableForm(id)
                       + "] payload length: " + payload.length + "]");
            synchronized (lock) {
                switch (type) {
                case 0x51:
                    // ACK outgoing
                    s = (I2PSocketImpl) _outSockets.get(id);
                    if (s == null) {
                        _log.warn("No socket responsible for ACK packet");
                        return;
                    }
                    if (payload.length == 3 && s.getRemoteID(false) == null) {
                        String newID = new String(payload, "ISO-8859-1");
                        s.setRemoteID(newID);
                        return;
                    } else {
                        if (payload.length != 3)
                            _log.warn("Ack packet had " + payload.length + " bytes");
                        else
                            _log.warn("Remote ID already exists? " + s.getRemoteID());
                        return;
                    }
                case 0x52:
                    // disconnect outgoing
                    _log.debug("*Disconnect outgoing!");
                    try {
                        s = (I2PSocketImpl) _outSockets.get(id);
                        if (payload.length == 0 && s != null) {
                            s.internalClose();
                            _outSockets.remove(id);
                            return;
                        } else {
                            if (payload.length > 0) _log.warn("Disconnect packet had " + payload.length + " bytes");
                            return;
                        }
                    } catch (Exception t) {
                        _log.error("Ignoring error on disconnect", t);
                    }
                case 0x50:
                    // packet send outgoing
                    _log.debug("*Packet send outgoing [" + payload.length + "]");
                    s = (I2PSocketImpl) _outSockets.get(id);
                    if (s != null) {
                        s.queueData(payload);
                        return;
                    } else {
                        _log.error("Null socket with data available");
                        throw new IllegalStateException("Null socket with data available");
                    }
                case 0xA1:
                    // SYN incoming
                    _log.debug("*Syn!");
                    if (payload.length == PUBKEY_LENGTH) {
                        String newLocalID = makeID(_inSockets);
                        Destination d = new Destination();
                        d.readBytes(new ByteArrayInputStream(payload));

                        s = new I2PSocketImpl(d, this, false, newLocalID);
                        s.setRemoteID(id);
                        if (_serverSocket.getNewSocket(s)) {
                            _inSockets.put(newLocalID, s);
                            byte[] packet = makePacket((byte) 0x51, id, newLocalID.getBytes("ISO-8859-1"));
                            boolean replySentOk = false;
                            synchronized (_session) {
                                replySentOk = _session.sendMessage(d, packet);
                            }
                            if (!replySentOk) {
                                _log.error("Error sending reply to " + d.calculateHash().toBase64()
                                           + " in response to a new con message", new Exception("Failed creation"));
                                s.internalClose();
                            }
                        } else {
                            byte[] packet = (" " + id).getBytes("ISO-8859-1");
                            packet[0] = 0x52;
                            boolean nackSent = session.sendMessage(d, packet);
                            if (!nackSent) {
                                _log.error("Error sending NACK for session creation");
                            }
                            s.internalClose();
                        }
                        return;
                    } else {
                        _log.error("Syn packet that has a payload not equal to the pubkey length (" + payload.length
                                   + " != " + PUBKEY_LENGTH + ")");
                        return;
                    }
                case 0xA2:
                    // disconnect incoming
                    _log.debug("*Disconnect incoming!");
                    try {
                        s = (I2PSocketImpl) _inSockets.get(id);
                        if (payload.length == 0 && s != null) {
                            s.internalClose();
                            _inSockets.remove(id);
                            return;
                        } else {
                            if (payload.length > 0) _log.warn("Disconnect packet had " + payload.length + " bytes");
                            return;
                        }
                    } catch (Exception t) {
                        _log.error("Ignoring error on disconnect", t);
                        return;
                    }
                case 0xA0:
                    // packet send incoming
                    _log.debug("*Packet send incoming [" + payload.length + "]");
                    s = (I2PSocketImpl) _inSockets.get(id);
                    if (s != null) {
                        s.queueData(payload);
                        return;
                    } else {
                        _log.error("Null socket with data available");
                        throw new IllegalStateException("Null socket with data available");
                    }
                case 0xFF:
                    // ignore
                    return;
                }
                _log.error("\n\n=============== Unknown packet! " + "============" + "\nType: " + (int) type
                           + "\nID:   " + getReadableForm(id) + "\nBase64'ed Data: " + Base64.encode(payload)
                           + "\n\n\n");
                if (id != null) {
                    _inSockets.remove(id);
                    _outSockets.remove(id);
                }
            }
        } catch (I2PException ise) {
            _log.error("Error processing", ise);
        } catch (IOException ioe) {
            _log.error("Error processing", ioe);
        } catch (IllegalStateException ise) {
            _log.debug("Error processing", ise);
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
        return _serverSocket;
    }

    /**
     * Create a new connected socket (block until the socket is created)
     *
     * @throws I2PException if there is a problem connecting
     */
    public I2PSocket connect(Destination peer, I2PSocketOptions options) throws I2PException {

        String localID, lcID;
        I2PSocketImpl s;
        synchronized (lock) {
            localID = makeID(_outSockets);
            lcID = getReadableForm(localID);
            s = new I2PSocketImpl(peer, this, true, localID);
            _outSockets.put(s.getLocalID(), s);
        }
        try {
            ByteArrayOutputStream pubkey = new ByteArrayOutputStream();
            _session.getMyDestination().writeBytes(pubkey);
            String remoteID;
            byte[] packet = makePacket((byte) 0xA1, localID, pubkey.toByteArray());
            boolean sent = false;
            synchronized (_session) {
                sent = _session.sendMessage(peer, packet);
            }
            if (!sent) {
                _log.info("Unable to send & receive ack for SYN packet");
                synchronized (lock) {
                    _outSockets.remove(s.getLocalID());
                }
                throw new I2PException("Unable to reach peer");
            }
            remoteID = s.getRemoteID(true, options.getConnectTimeout());
            if ("".equals(remoteID)) { throw new I2PException("Unable to reach peer"); }
            _log.debug("TIMING: s given out for remoteID " + getReadableForm(remoteID));
            return s;
        } catch (InterruptedIOException ioe) {
            _log.error("Timeout waiting for ack from syn for id " + getReadableForm(lcID), ioe);
            synchronized (lock) {
                _outSockets.remove(s.getLocalID());
            }
            throw new I2PException("Timeout waiting for ack");
        } catch (IOException ex) {
            _log.error("Error sending syn on id " + getReadableForm(lcID), ex);
            synchronized (lock) {
                _outSockets.remove(s.getLocalID());
            }
            throw new I2PException("IOException occurred");
        } catch (I2PException ex) {
            _log.info("Error sending syn on id " + getReadableForm(lcID), ex);
            synchronized (lock) {
                _outSockets.remove(s.getLocalID());
            }
            throw ex;
        }
    }

    public I2PSocket connect(Destination peer) throws I2PException {
        return connect(peer, null);
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
            return _session.sendMessage(peer, new byte[] { (byte) 0xFF});
        } catch (I2PException ex) {
            _log.error("I2PException:", ex);
            return false;
        }
    }

    public void removeSocket(I2PSocketImpl sock) {
        synchronized (lock) {
            _inSockets.remove(sock.getLocalID());
            _outSockets.remove(sock.getLocalID());
        }
    }

    public static String getReadableForm(String id) {
        try {
            if (id.length() != 3) return "Bogus";
            return Base64.encode(id.getBytes("ISO-8859-1"));
        } catch (UnsupportedEncodingException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Create a new part the connection ID that is locally unique
     *
     * @param uniqueIn map of already known local IDs so we don't collide. WARNING - NOT THREADSAFE!
     */
    public static String makeID(HashMap uniqueIn) {
        String newID;
        try {
            do {
                int id = (int) (Math.random() * 16777215 + 1);
                byte[] nid = new byte[3];
                nid[0] = (byte) (id / 65536);
                nid[1] = (byte) ((id / 256) % 256);
                nid[2] = (byte) (id % 256);
                newID = new String(nid, "ISO-8859-1");
            } while (uniqueIn.get(newID) != null);
            return newID;
        } catch (UnsupportedEncodingException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Create a new packet of the given type for the specified connection containing
     * the given payload
     */
    public static byte[] makePacket(byte type, String id, byte[] payload) {
        try {
            byte[] packet = new byte[payload.length + 4];
            packet[0] = type;
            byte[] temp = id.getBytes("ISO-8859-1");
            if (temp.length != 3) throw new RuntimeException("Incorrect ID length: " + temp.length);
            System.arraycopy(temp, 0, packet, 1, 3);
            System.arraycopy(payload, 0, packet, 4, payload.length);
            return packet;
        } catch (UnsupportedEncodingException ex) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Error building the packet", ex);
            return new byte[0];
        }
    }
}