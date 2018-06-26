package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Inbound NTCP 1 or 2. Outbound NTCP 1 only.
 * OutboundNTCP2State does not extend this.
 *
 * NTCP 1 establishement overview:
 *
 * Handle the 4-phase establishment, which is as follows:
 *
 * <pre>
 *
 * Alice                   contacts                      Bob
 * =========================================================
 *
 * Message 1 (Session Request):
 *  X+(H(X) xor Bob.identHash)-----------------------------&gt;
 *
 * Message 2 (Session Created):
 *  &lt;----------------------------------------Y+E(H(X+Y)+tsB, sk, Y[239:255])
 *
 * Message 3 (Session Confirm A):
 *  E(sz+Alice.identity+tsA+padding+S(X+Y+Bob.identHash+tsA+tsB), sk, hX_xor_Bob.identHash[16:31])---&gt;
 *
 * Message 4 (Session Confirm B):
 *  &lt;----------------------E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev)
 *
 *  Key:
 *
 *    X, Y: 256 byte DH keys
 *    H(): 32 byte SHA256 Hash
 *    E(data, session key, IV): AES256 Encrypt
 *    S(): 40 byte DSA Signature, or length as implied by sig type
 *    tsA, tsB: timestamps (4 bytes, seconds since epoch)
 *    sk: 32 byte Session key
 *    sz: 2 byte size of Alice identity to follow
 *
 * </pre>
 *
 *
 * Alternately, when Bob receives a connection, it could be a
 * check connection (perhaps prompted by Bob asking for someone
 * to verify his listener).  check connections are formatted per
 * isCheckInfo()
 * NOTE: Check info is unused.
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
    protected final byte _hX_xor_bobIdentHash[];
    // alice receives (and bob sends)
    protected final byte _Y[];
    protected final byte _e_hXY_tsB[];
    /** Bob's timestamp in seconds, this is in message #2, *before* _tsA */
    protected transient long _tsB;
    /** Alice's timestamp in seconds, this is in message #3, *after* _tsB
     *  Only saved for outbound. For inbound, see verifyInbound().
     */
    protected transient long _tsA;
    /**
     *  OUR clock minus HIS clock, in seconds
     *
     *  Inbound: tsB - tsA - rtt/2
     *  Outbound: tsA - tsB - rtt/2
     */
    protected transient long _peerSkew;
    protected transient byte _e_bobSig[];

    /** previously received encrypted block (or the IV) */
    protected byte _prevEncrypted[];
    /** decryption buffer */
    protected final byte _curDecrypted[];

    /** bytes received so far */
    protected int _received;

    protected final DHSessionKeyBuilder _dh;

    protected final NTCPTransport _transport;
    protected final NTCPConnection _con;
    
    protected static final int MIN_RI_SIZE = 387;
    protected static final int MAX_RI_SIZE = 3072;

    protected static final int AES_SIZE = 16;
    protected static final int XY_SIZE = 256;
    protected static final int HXY_SIZE = 32;  //Hash.HASH_LENGTH;
    protected static final int HXY_TSB_PAD_SIZE = HXY_SIZE + 4 + 12;  // 48

    protected final Object _stateLock = new Object();
    protected volatile State _state;

    protected enum State {
        OB_INIT,
        /** sent 1 */
        OB_SENT_X,
        /** sent 1, got 2 partial */
        OB_GOT_Y,
        /** sent 1, got 2 */
        OB_GOT_HXY,
        /** sent 1, got 2, sent 3 */
        OB_SENT_RI,
        /** sent 1, got 2, sent 3, got 4 */
        OB_GOT_SIG,

        IB_INIT,
        /** got 1 partial */
        IB_GOT_X,
        /** got 1 */
        IB_GOT_HX,
        /** got 1, sent 2 */
        IB_SENT_Y,
        /** got 1, sent 2, got partial 3 */
        IB_GOT_RI_SIZE,
        /** got 1, sent 2, got 3 */
        IB_GOT_RI,

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

    private EstablishBase() {
        _context = null;
        _log = null;
        _X = null;
        _Y = null;
        _hX_xor_bobIdentHash = null;
        _curDecrypted = null;
        _dh = null;
        _transport = null;
        _con = null;
        _e_hXY_tsB = null;
    }

    protected EstablishBase(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _con = con;
        _dh = _transport.getDHBuilder();
        _hX_xor_bobIdentHash = SimpleByteCache.acquire(HXY_SIZE);
        if (_con.isInbound()) {
            _X = SimpleByteCache.acquire(XY_SIZE);
            _Y = _dh.getMyPublicValueBytes();
        } else {
            _X = _dh.getMyPublicValueBytes();
            _Y = SimpleByteCache.acquire(XY_SIZE);
        }

        _e_hXY_tsB = new byte[HXY_TSB_PAD_SIZE];
        _curDecrypted = SimpleByteCache.acquire(AES_SIZE);
    }

    /** @since 0.9.16 */
    protected void changeState(State state) {
        synchronized (_stateLock) {
            _state = state;
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
            if (_state == State.VERIFIED || _state == State.CORRUPT)
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
        synchronized(_stateLock) {
            return _state == State.CORRUPT;
        }
    }

    /**
     *  If synchronized on this, fails with
     *  deadlocks from all over via CSFI.isEstablished().
     *  Also CSFI.getFramedAveragePeerClockSkew().
     *
     *  @return is the handshake complete and valid?
     */
    public boolean isComplete() {
        synchronized(_stateLock) {
            return _state == State.VERIFIED;
        }
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
            if (_state == State.CORRUPT || _state == State.VERIFIED)
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
        SimpleByteCache.release(_curDecrypted);
        SimpleByteCache.release(_hX_xor_bobIdentHash);
        if (_dh.getPeerPublicValue() == null)
            _transport.returnUnused(_dh);
    }

    /**
     *  XOR a into b. Modifies b. a is unmodified.
     *  @param a 32 bytes
     *  @param b 32 bytes
     *  @since 0.9.12
     */
    protected static void xor32(byte[] a, byte[] b) {
        for (int i = 0; i < 32; i++) {
            b[i] ^= a[i];
        }
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
            _state = State.VERIFIED;
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
            _state = State.CORRUPT;
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
