package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Inbound NTCP 2 only.
 * OutboundNTCP2State does not extend this.
 *
 * @since 0.9.35 pulled out of EstablishState
 */
abstract class EstablishBase implements EstablishState {
    
    public static final VerifiedEstablishState VERIFIED = new VerifiedEstablishState();
    public static final FailedEstablishState FAILED = new FailedEstablishState();
    
    protected final RouterContext _context;
    protected final Log _log;

    // bob receives (and alice sends)
    protected final byte _X[];
    // alice receives (and bob sends)
    protected final byte _Y[];
    /**
     *  OUR clock minus HIS clock, in seconds
     *
     *  Inbound: tsB - tsA - rtt/2
     *  Outbound: tsA - tsB - rtt/2
     */
    protected transient long _peerSkew;

    /** previously received encrypted block (or the IV) */
    protected byte _prevEncrypted[];

    /** bytes received so far */
    protected int _received;

    protected final NTCPTransport _transport;
    protected final NTCPConnection _con;
    
    protected static final int MIN_RI_SIZE = 387;
    protected static final int MAX_RI_SIZE = 3072;

    protected static final int AES_SIZE = 16;
    protected static final int XY_SIZE = 256;

    protected final Object _stateLock = new Object();
    protected volatile State _state;
    private final AtomicBoolean _isCorrupt = new AtomicBoolean();
    private final AtomicBoolean _isComplete = new AtomicBoolean();

    protected enum State {
        OB_INIT,
        IB_INIT,

        /**
         * Next state IB_NTCP2_GOT_X
         * @since 0.9.36
         */
        IB_NTCP2_INIT,
        /**
         * Got Noise part of msg 1
         * Next state IB_NTCP2_GOT_PADDING or IB_NTCP2_READ_RANDOM on fail
         * @since 0.9.36
         */
        IB_NTCP2_GOT_X,
        /**
         * Got msg 1 incl. padding
         * Next state IB_NTCP2_SENT_Y
         * @since 0.9.36
         */
        IB_NTCP2_GOT_PADDING,
        /**
         * Sent msg 2 and padding
         * Next state IB_NTCP2_GOT_RI
         * @since 0.9.36
         */
        IB_NTCP2_SENT_Y,
        /**
         * Got msg 3
         * Next state VERIFIED
         * @since 0.9.36
         */
        IB_NTCP2_GOT_RI,
        /**
         * Got msg 1 and failed AEAD
         * Next state CORRUPT
         * @since 0.9.36
         */
        IB_NTCP2_READ_RANDOM,

        /** OB: got and verified 4; IB: got and verified 3 and sent 4 */
        VERIFIED,
        CORRUPT
    }

    protected static final Set<State> STATES_DONE = EnumSet.of(State.VERIFIED, State.CORRUPT);

    private EstablishBase() {
        _context = null;
        _log = null;
        _X = null;
        _Y = null;
        _transport = null;
        _con = null;
    }

    protected EstablishBase(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _con = con;
        if (_con.isInbound()) {
            _X = SimpleByteCache.acquire(XY_SIZE);
            _Y = null;
        } else {
            // OutboundNTCP2State does not extend this,
                throw new IllegalStateException();
        }
    }

    /** @since 0.9.16 */
    protected void changeState(State state) {
        synchronized (_stateLock) {
            _state = state;
            _isCorrupt.set(state == State.CORRUPT);
            _isComplete.set(state == State.VERIFIED);
        }
    }

    /**
     * Parse the contents of the buffer as part of the handshake.
     *
     * All data must be copied out of the buffer as Reader.processRead()
     * will return it to the pool.
     *
     * If there are additional data in the buffer after the handshake is complete,
     * the EstablishState is responsible for passing it to NTCPConnection.
     */
    public synchronized void receive(ByteBuffer src) {
        synchronized(_stateLock) {    
            if (STATES_DONE.contains(_state))
                throw new IllegalStateException(prefix() + "received unexpected data on " + _con);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(prefix() + "Receiving: " + src.remaining() + " Received: " + _received);
    }

    /**
     * Does nothing. Outbound (Alice) must override.
     * We are establishing an outbound connection, so prepare ourselves by
     * queueing up the write of the first part of the handshake
     */
    public void prepareOutbound() {}

    /** did the handshake fail for some reason? */
    public boolean isCorrupt() {
        return _isCorrupt.get();
    }

    /**
     *  If synchronized on this, fails with
     *  deadlocks from all over via CSFI.isEstablished().
     *  Also CSFI.getFramedAveragePeerClockSkew().
     *
     *  @return is the handshake complete and valid?
     */
    public boolean isComplete() {
        return _isComplete.get();
    }

    /**
     *  Get the NTCP version
     *  @return 1, 2, or 0 if unknown
     *  @since 0.9.35
     */
    public abstract int getVersion();

    /**
     *  Release resources on timeout.
     *  @param e may be null
     *  @since 0.9.16
     */
    public synchronized void close(String reason, Exception e) {
        fail(reason, e);
    }

    /** Caller must synch. */
    protected void fail(String reason) { fail(reason, null); }

    /** Caller must synch. */
    protected void fail(String reason, Exception e) { fail(reason, e, false); }

    /** Caller must synch. */
    protected void fail(String reason, Exception e, boolean bySkew) {
        synchronized(_stateLock) {    
            if (STATES_DONE.contains(_state))
                return;
            changeState(State.CORRUPT);
        }
        if (_log.shouldLog(Log.WARN))
            _log.warn(prefix() + "Failed to establish: " + reason, e);
        if (!bySkew)
            _context.statManager().addRateData("ntcp.receiveCorruptEstablishment", 1);
        releaseBufs(false);
        // con.close()?
    }

    /**
     *  Only call once. Caller must synch.
     *  @since 0.9.16
     */
    protected void releaseBufs(boolean isVerified) {
        // null or longer for OB
        if (_prevEncrypted != null && _prevEncrypted.length == AES_SIZE)
            SimpleByteCache.release(_prevEncrypted);
    }

    protected String prefix() { return toString(); }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        if (_con.isInbound())
            buf.append("IBES ");
        else
            buf.append("OBES ");
        buf.append(_con.toString());
        buf.append(' ').append(_state);
        if (_con.isEstablished()) buf.append(" established");
        buf.append(": ");
        return buf.toString();
    }

    /**
     *  @since 0.9.8
     */
    private static class VerifiedEstablishState extends EstablishBase {

        public VerifiedEstablishState() {
            super();
            changeState(State.VERIFIED);
        }

        public int getVersion() { return 1; }

        /*
         * @throws IllegalStateException always
         */
        @Override
        public void receive(ByteBuffer src) {
            throw new IllegalStateException("receive() " + src.remaining() + " on verified state, doing nothing!");
        }

        /*
         * @throws IllegalStateException always
         */
        @Override
        public void prepareOutbound() {
            throw new IllegalStateException("prepareOutbound() on verified state, doing nothing!");
        }

        @Override
        public String toString() { return "VerifiedEstablishState: ";}
    }

    /**
     *  @since 0.9.16
     */
    private static class FailedEstablishState extends EstablishBase {

        public FailedEstablishState() {
            super();
            changeState(State.CORRUPT);
        }

        public int getVersion() { return 1; }

        /*
         * @throws IllegalStateException always
         */
        @Override
        public void receive(ByteBuffer src) {
            throw new IllegalStateException("receive() " + src.remaining() + " on failed state, doing nothing!");
        }

        /*
         * @throws IllegalStateException always
         */
        @Override
        public void prepareOutbound() {
            throw new IllegalStateException("prepareOutbound() on failed state, doing nothing!");
        }

        @Override
        public String toString() { return "FailedEstablishState: ";}
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    protected static final String _x(String s) {
        return s;
    }

}
