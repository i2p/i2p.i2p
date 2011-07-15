package net.i2p.client;

/*
 * public domain
 */

import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Properties;
import java.util.Set;

import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;

/**
 * I2PSession with protocol and ports
 *
 * Streaming lib has been modified to send I2PSession.PROTO_STREAMING but
 * still receives all. It sends with fromPort and toPort = 0, and receives on all ports.
 *
 * No datagram apps have been modified yet.

 * Therefore the compatibility situation is as follows:
 *
 * Compatibility:
 *    old streaming -> new streaming: sends proto anything, rcvs proto anything
 *    new streaming -> old streaming: sends PROTO_STREAMING, ignores rcvd proto
 *    old datagram -> new datagram: sends proto anything, rcvs proto anything
 *    new datagram -> old datagram: sends PROTO_DATAGRAM, ignores rcvd proto
 *    In all the above cases, streaming and datagram receive traffic for the other
 *    protocol, same as before.
 *
 *    old datagram -> new muxed: doesn't work because the old sends proto 0 but the udp side
 *                               of the mux registers with PROTO_DATAGRAM, so the datagrams
 *                               go to the streaming side, same as before.
 *    old streaming -> new muxed: works
 *
 * Typical Usage:
 *    Streaming + datagrams:
 *        I2PSocketManager sockMgr = getSocketManager();
 *        I2PSession session = sockMgr.getSession();
 *        session.addMuxedSessionListener(myI2PSessionMuxedListener, I2PSession.PROTO_DATAGRAM, I2PSession.PORT_ANY);
 *         * or *
 *        session.addSessionListener(myI2PSessionListener, I2PSession.PROTO_DATAGRAM, I2PSession.PORT_ANY);
 *        session.sendMessage(dest, payload, I2PSession.PROTO_DATAGRAM, fromPort, toPort);
 *
 *    Datagrams only, with multiple ports:
 *        I2PClient client = I2PClientFactory.createClient();
 *        ...
 *        I2PSession session = client.createSession(...);
 *        session.addMuxedSessionListener(myI2PSessionMuxedListener, I2PSession.PROTO_DATAGRAM, I2PSession.PORT_ANY);
 *         * or *
 *        session.addSessionListener(myI2PSessionListener, I2PSession.PROTO_DATAGRAM, I2PSession.PORT_ANY);
 *        session.sendMessage(dest, payload, I2PSession.PROTO_DATAGRAM, fromPort, toPort);
 *
 *    Multiple streaming ports:
 *        Needs some streaming lib hacking
 *
 * @author zzz
 */
class I2PSessionMuxedImpl extends I2PSessionImpl2 implements I2PSession {

    private final I2PSessionDemultiplexer _demultiplexer;

    /*
     * @param destKeyStream stream containing the private key data,
     *                             format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     */
    public I2PSessionMuxedImpl(I2PAppContext ctx, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super(ctx, destKeyStream, options);
        // also stored in _sessionListener but we keep it in _demultipexer
        // as well so we don't have to keep casting
        _demultiplexer =  new I2PSessionDemultiplexer(ctx);
        super.setSessionListener(_demultiplexer);
        // discards the one in super(), sorry about that... (no it wasn't started yet)
        _availabilityNotifier = new MuxedAvailabilityNotifier();
    }
    
    /** listen on all protocols and ports */
    @Override
    public void setSessionListener(I2PSessionListener lsnr) {
        _demultiplexer.addListener(lsnr, PROTO_ANY, PORT_ANY);
    }

    /**
     *  Listen on specified protocol and port.
     *
     *  An existing listener with the same proto and port is replaced.
     *  Only the listener with the best match is called back for each message.
     *
     *  @param proto 1-254 or PROTO_ANY (0) for all; recommended:
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     *  @param port 1-65535 or PORT_ANY (0) for all
     */
    @Override
    public void addSessionListener(I2PSessionListener lsnr, int proto, int port) {
        _demultiplexer.addListener(lsnr, proto, port);
    }

    /**
     *  Listen on specified protocol and port, and receive notification
     *  of proto, fromPort, and toPort for every message.
     *  @param proto 1-254 or PROTO_ANY (0) for all; 255 disallowed
     *  @param port 1-65535 or PORT_ANY (0) for all
     */
    @Override
    public void addMuxedSessionListener(I2PSessionMuxedListener l, int proto, int port) {
        _demultiplexer.addMuxedListener(l, proto, port);
    }

    /** removes the specified listener (only) */
    @Override
    public void removeListener(int proto, int port) {
        _demultiplexer.removeListener(proto, port);
    }

    @Override
    public boolean sendMessage(Destination dest, byte[] payload) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length, null, null,
                           0, PROTO_UNSPECIFIED, PORT_UNSPECIFIED, PORT_UNSPECIFIED);
    }

    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int proto, int fromport, int toport) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length, null, null, 0, proto, fromport, toport);
    }

    /**
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               SessionKey keyUsed, Set tagsSent, long expires)
                   throws I2PSessionException {
        return sendMessage(dest, payload, offset, size, keyUsed, tagsSent, 0, PROTO_UNSPECIFIED, PORT_UNSPECIFIED, PORT_UNSPECIFIED);
    }

    /**
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set tagsSent,
                               int proto, int fromport, int toport) throws I2PSessionException {
        return sendMessage(dest, payload, offset, size, keyUsed, tagsSent, 0, proto, fromport, toport);
    }

    /**
     *  @param keyUsed unused - no end-to-end crypto
     *  @param tagsSent unused - no end-to-end crypto
     *  @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     *  @param fromPort 1-65535 or 0 for unset
     *  @param toPort 1-65535 or 0 for unset
     *  @since 0.7.1
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               SessionKey keyUsed, Set tagsSent, long expires,
                               int proto, int fromPort, int toPort)
                   throws I2PSessionException {
        return sendMessage(dest, payload, offset, size, keyUsed, tagsSent, 0, proto, fromPort, toPort, 0);
    }

    /**
     *  @param keyUsed unused - no end-to-end crypto
     *  @param tagsSent unused - no end-to-end crypto
     *  @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     *  @param fromPort 1-65535 or 0 for unset
     *  @param toPort 1-65535 or 0 for unset
     *  @param flags to be passed to the router
     *  @since 0.8.4
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               SessionKey keyUsed, Set tagsSent, long expires,
                               int proto, int fromPort, int toPort, int flags)
                   throws I2PSessionException {
        if (isClosed()) throw new I2PSessionException("Already closed");
        updateActivity();

        boolean sc = shouldCompress(size);
        if (sc)
            payload = DataHelper.compress(payload, offset, size);
        else
            payload = DataHelper.compress(payload, offset, size, DataHelper.NO_COMPRESSION);

        setProto(payload, proto);
        setFromPort(payload, fromPort);
        setToPort(payload, toPort);

        _context.statManager().addRateData("i2cp.tx.msgCompressed", payload.length, 0);
        _context.statManager().addRateData("i2cp.tx.msgExpanded", size, 0);
        return sendBestEffort(dest, payload, expires, flags);
    }

    /**
     * Receive a payload message and let the app know its available
     */
    @Override
    public void addNewMessage(MessagePayloadMessage msg) {
        Long mid = Long.valueOf(msg.getMessageId());
        _availableMessages.put(mid, msg);
        long id = msg.getMessageId();
        byte data[] = msg.getPayload().getUnencryptedData();
        if ((data == null) || (data.length <= 0)) {
            if (_log.shouldLog(Log.CRIT))
                _log.log(Log.CRIT, getPrefix() + "addNewMessage of a message with no unencrypted data",
                           new Exception("Empty message"));
            return;
        }
        int size = data.length;
        if (size < 10) {
            _log.error(getPrefix() + "length too short for gzip header: " + size);
            return;
        }
        ((MuxedAvailabilityNotifier)_availabilityNotifier).available(id, size, getProto(msg),
                                                                     getFromPort(msg), getToPort(msg));
        SimpleScheduler.getInstance().addEvent(new VerifyUsage(mid), 30*1000);
    }

    protected class MuxedAvailabilityNotifier extends AvailabilityNotifier {
        private LinkedBlockingQueue<MsgData> _msgs;
        private volatile boolean _alive = false;
        private static final int POISON_SIZE = -99999;
        private final AtomicBoolean stopping = new AtomicBoolean(false);

        public MuxedAvailabilityNotifier() {
            _msgs = new LinkedBlockingQueue();
        }

        @Override
        public void stopNotifying() {
            boolean again = true;
            synchronized (stopping) {
                if( !stopping.getAndSet(true)) {
                    if (_alive == true) {
                        // System.out.println("I2PSessionMuxedImpl.stopNotifying()");
                        _msgs.clear();
                        while(again) {
                            try {
                                _msgs.put(new MsgData(0, POISON_SIZE, 0, 0, 0));
                                again = false;
                                // System.out.println("I2PSessionMuxedImpl.stopNotifying() success.");
                            } catch (InterruptedException ie) {
                                continue;
                            }
                        }
                    }
                    _alive = false;
                    stopping.set(false);
                }
                // stopping.notifyAll();
            }
        }
        /** unused */
        @Override
        public void available(long msgId, int size) { throw new IllegalArgumentException("no"); }

        public void available(long msgId, int size, int proto, int fromPort, int toPort) {
            try {
                _msgs.put(new MsgData((int)(msgId & 0xffffffff), size, proto, fromPort, toPort));
            } catch (InterruptedException ie) {}
        }

        @Override
        public void run() {
            MsgData msg;
            _alive=true;
            while (_alive) {
                try {
                    msg = _msgs.take();
                } catch (InterruptedException ie) {
                    _log.debug("I2PSessionMuxedImpl.run() InterruptedException " + String.valueOf(_msgs.size()) + " Messages, Alive " + _alive);
                    continue;
                }
                if (msg.size == POISON_SIZE) {
                    // System.out.println("I2PSessionMuxedImpl.run() POISONED");
                    break;
                }
                try {
                    _demultiplexer.messageAvailable(I2PSessionMuxedImpl.this,
                        msg.id, msg.size, msg.proto, msg.fromPort, msg.toPort);
                } catch (Exception e) {
                    _log.error("Error notifying app of message availability", e);
                }
            }
        }
    }

    /** let's keep this simple */
    private static class MsgData {
        public int id, size, proto, fromPort, toPort;
        public MsgData(int i, int s, int p, int f, int t) {
            id = i;
            size = s;
            proto = p;
            fromPort = f;
            toPort = t;
        }
    }
    
    /**
     *  No, we couldn't put any protocol byte in front of everything and
     *  keep backward compatibility. But there are several bytes that
     *  are unused AND unchecked in the gzip header in releases <= 0.7.
     *  So let's use 5 of them for a protocol and two 2-byte ports.
     *
     *  Following are all the methods to hide the
     *  protocol, fromPort, and toPort in the gzip header
     *
     *  The fields used are all ignored on receive in ResettableGzipInputStream
     *
     *  See also ResettableGzipOutputStream.
     *  Ref: RFC 1952
     *
     */

    /** OS byte in gzip header */
    private static final int PROTO_BYTE = 9;

    /** Upper two bytes of MTIME in gzip header */
    private static final int FROMPORT_BYTES = 4;

    /** Lower two bytes of MTIME in gzip header */
    private static final int TOPORT_BYTES = 6;

    /** Non-muxed sets the OS byte to 0xff */
    private static int getProto(MessagePayloadMessage msg) {
        int rv = getByte(msg, PROTO_BYTE) & 0xff;
        return rv == 0xff ? PROTO_UNSPECIFIED : rv;
    }	

    /** Non-muxed sets the MTIME bytes to 0 */
    private static int getFromPort(MessagePayloadMessage msg) {
        return (((getByte(msg, FROMPORT_BYTES) & 0xff) << 8) |
                 (getByte(msg, FROMPORT_BYTES + 1) & 0xff));
    }	

    /** Non-muxed sets the MTIME bytes to 0 */
    private static int getToPort(MessagePayloadMessage msg) {
        return (((getByte(msg, TOPORT_BYTES) & 0xff) << 8) |
                 (getByte(msg, TOPORT_BYTES + 1) & 0xff));
    }	

    private static int getByte(MessagePayloadMessage msg, int i) {
        return msg.getPayload().getUnencryptedData()[i] & 0xff;
    }	

    private static void setProto(byte[] payload, int p) {
        payload[PROTO_BYTE] = (byte) (p & 0xff);
    }	

    private static void setFromPort(byte[] payload, int p) {
        payload[FROMPORT_BYTES] = (byte) ((p >> 8) & 0xff);
        payload[FROMPORT_BYTES + 1] = (byte) (p & 0xff);
    }	

    private static void setToPort(byte[] payload, int p) {
        payload[TOPORT_BYTES] = (byte) ((p >> 8) & 0xff);
        payload[TOPORT_BYTES + 1] = (byte) (p & 0xff);
    }	
}
