package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.HKDF;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.ntcp.NTCP2Payload.Block;
import net.i2p.util.Log;

/**
 *
 *  NTCP 2 only. We are Alice.
 *
 *  Also contains static constants and methods used by InboundEstablishState for NTCP2.
 *  Does not extend EstablishBase.
 *
 *  @since 0.9.35
 */
class OutboundNTCP2State implements EstablishState {
    
    private final RouterContext _context;
    private final Log _log;
    private final NTCPTransport _transport;
    private final NTCPConnection _con;
    private final byte[] _tmp;
    /** bytes received so far */
    private int _received;
    private long _peerSkew;

    public static final int KEY_SIZE = 32;
    public static final int MAC_SIZE = 16;
    public static final int IV_SIZE = 16;
    public static final int OPTIONS1_SIZE = 16;
    /** 64 */
    public static final int MSG1_SIZE = KEY_SIZE + OPTIONS1_SIZE + MAC_SIZE;
    /** one less than 288 byte NTCP1 msg 1 */
    public static final int TOTAL1_MAX = 287;
    private static final int PADDING1_MAX = 64;
    private static final int PADDING3_MAX = 64;
    public static final int OPTIONS2_SIZE = 16;
    public static final int MSG2_SIZE = KEY_SIZE + OPTIONS2_SIZE + MAC_SIZE;
    /** 48 */
    public static final int MSG3P1_SIZE = KEY_SIZE + MAC_SIZE;
    private static final int OPTIONS3_SIZE = 12;
    /** in SECONDS */
    public static final long MAX_SKEW = 60;
    // SipHash KDF things
    private static final byte[] ZEROLEN = new byte[0];
    private static final byte[] ONE = new byte[] { 1 };
    public static final byte[] ZEROKEY = new byte[KEY_SIZE];
    /** for SipHash keygen */
    private static final byte[] ASK = new byte[] { (byte) 'a', (byte) 's', (byte) 'k', 1 };
    /** for SipHash keygen */
    private static final byte[] SIPHASH = DataHelper.getASCII("siphash");

    private final Object _stateLock = new Object();
    private State _state;

    private final HandshakeState _handshakeState;
    private final RouterInfo _aliceRI;
    private final int _aliceRISize;
    private int _padlen1;
    private int _padlen2;
    private final int _padlen3;
    private final SessionKey _bobHash;
    private final byte[] _bobIV;

    private enum State {
        OB_INIT,
        /** sent 1 */
        OB_SENT_X,
        /** sent 1, got 2 but not padding */
        OB_GOT_HXY,
        /** sent 1, got 2 incl. padding */
        OB_GOT_PADDING,
        /** sent 1, got 2 incl. padding, sent 3 */
        VERIFIED,
        CORRUPT
    }
    
    /**
     * @throws IllegalArgumentException on bad address in the con
     */
    public OutboundNTCP2State(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _con = con;
        _state = State.OB_INIT;
        _tmp = new byte[TOTAL1_MAX];
        try {
            _handshakeState = new HandshakeState(HandshakeState.INITIATOR, _transport.getXDHFactory());
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("bad proto", gse);
        }
        // save because we must know length
        _aliceRI = ctx.router().getRouterInfo();
        if (_aliceRI == null)
            throw new IllegalStateException("no RI yet");
        _aliceRISize = _aliceRI.toByteArray().length;
        _padlen3 = _context.random().nextInt(PADDING3_MAX);

        Hash h = _con.getRemotePeer().calculateHash();
        _bobHash = new SessionKey(h.getData());
        String s = _con.getRemoteAddress().getOption("i");
        if (s == null)
            throw new IllegalArgumentException("no NTCP2 IV");
        _bobIV = Base64.decode(s);
        if (_bobIV == null || _bobIV.length != IV_SIZE ||
            DataHelper.eq(_bobIV, 0, ZEROKEY, 0, IV_SIZE))
            throw new IllegalArgumentException("bad NTCP2 IV");
    }

    private void changeState(State state) {
        synchronized (_stateLock) {
            _state = state;
        }
    }

    /**
     * Parse the contents of the buffer as part of the handshake.
     *
     * All data must be copied out of the buffer as Reader.processRead()
     * will return it to the pool.
     */
    @Override
    public synchronized void receive(ByteBuffer src) {
        if (_state == State.VERIFIED || _state == State.CORRUPT)
            throw new IllegalStateException(this + "received unexpected data on " + _con);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(this + "Receiving: " + src.remaining() + " Received: " + _received);
        if (!src.hasRemaining())
            return; // nothing to receive
        receiveOutbound(src);
    }

    /** did the handshake fail for some reason? */
    public boolean isCorrupt() {
        synchronized (_stateLock) {
            return _state == State.CORRUPT;
        }
    }

    /**
     *  Don't synchronize this, deadlocks all over.
     *
     *  @return is the handshake complete and valid?
     */
    public boolean isComplete() {
        synchronized (_stateLock) {
            return _state == State.VERIFIED;
        }
    }

    /**
     *  Get the NTCP version
     *  @return 2
     */
    public int getVersion() { return 2; }

    /**
     *  We are Alice.
     *  We are establishing an outbound connection, so prepare ourselves by
     *  writing the first message in the handshake.
     *  Encrypt X and write X, the options block, and the padding.
     *  Save last half of encrypted X as IV for message 2 AES.
     *
     *  @throws IllegalStateException
     */
    public synchronized void prepareOutbound() {
        if (!(_state == State.OB_INIT)) {
            throw new IllegalStateException(this + "unexpected prepareOutbound()");
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(this + "send X");
        byte options[] = new byte[OPTIONS1_SIZE];
        options[1] = NTCPTransport.NTCP2_INT_VERSION;
        int padlen1 = _context.random().nextInt(PADDING1_MAX);
        DataHelper.toLong(options, 2, 2, padlen1);
        int msg3p2len = NTCP2Payload.BLOCK_HEADER_SIZE + 1 + _aliceRISize +
                        NTCP2Payload.BLOCK_HEADER_SIZE + OPTIONS3_SIZE +
                        NTCP2Payload.BLOCK_HEADER_SIZE + _padlen3 +
                        MAC_SIZE;
        DataHelper.toLong(options, 4, 2, msg3p2len);
        long now = (_context.clock().now() + 500) / 1000;
        DataHelper.toLong(options, 8, 4, now);

        // set keys
        String s = _con.getRemoteAddress().getOption("s");
        if (s == null) {
            fail("no NTCP2 S");
            return;
        }
        byte[] bk = Base64.decode(s);
        if (bk == null || bk.length != KEY_SIZE ||
            DataHelper.eq(bk, 0, ZEROKEY, 0, KEY_SIZE)) {
            fail("bad NTCP2 S: " + s);
            return;
        }
        _handshakeState.getRemotePublicKey().setPublicKey(bk, 0);
        _handshakeState.getLocalKeyPair().setPublicKey(_transport.getNTCP2StaticPubkey(), 0);
        _handshakeState.getLocalKeyPair().setPrivateKey(_transport.getNTCP2StaticPrivkey(), 0);
        // output to _tmp
        try {
            _handshakeState.start();
            if (_log.shouldDebug())
                _log.debug("After start: " + _handshakeState.toString());
            _handshakeState.writeMessage(_tmp, 0, options, 0, OPTIONS1_SIZE);
        } catch (GeneralSecurityException gse) {
            // buffer length error
            if (!_log.shouldWarn())
                _log.error("Bad msg 1 out", gse);
            fail("Bad msg 1 out", gse);
            return;
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad msg 1 out", re);
            fail("Bad msg 1 out", re);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("After msg 1: " + _handshakeState.toString());

        // encrypt key before writing
        _context.aes().encrypt(_tmp, 0, _tmp, 0, _bobHash, _bobIV, KEY_SIZE);
        // overwrite _bobIV with last 16 encrypted bytes, CBC for message 2
        System.arraycopy(_tmp, KEY_SIZE - IV_SIZE, _bobIV, 0, IV_SIZE);
        // add padding
        if (padlen1 > 0) {
            _context.random().nextBytes(_tmp, MSG1_SIZE, padlen1);
            _handshakeState.mixHash(_tmp, MSG1_SIZE, padlen1);
            if (_log.shouldDebug())
                _log.debug("After mixhash padding " + padlen1 + " msg 1: " + _handshakeState.toString());
        }

        changeState(State.OB_SENT_X);
        // send it all at once
        _transport.getPumper().wantsWrite(_con, _tmp, 0, MSG1_SIZE + padlen1);
    }

    /**
     *  We are Alice, so receive these bytes as part of an outbound connection.
     *  This method receives message 2, and sends message 3.
     *
     *  IV (CBC from msg 1) must be in _bobIV
     *
     *  All data must be copied out of the buffer as Reader.processRead()
     *  will return it to the pool.
     *
     *  Caller must synch
     */
    private void receiveOutbound(ByteBuffer src) {
        // Read in message #2 except for the padding
        if (_state == State.OB_SENT_X && src.hasRemaining()) {
            int toGet = Math.min(src.remaining(), MSG2_SIZE - _received);
            src.get(_tmp, _received, toGet);
            _received += toGet;
            if (_received < MSG2_SIZE)
                return;
            _context.aes().decrypt(_tmp, 0, _tmp, 0, _bobHash, _bobIV, KEY_SIZE);
            if (DataHelper.eqCT(_tmp, 0, ZEROKEY, 0, KEY_SIZE)) {
                fail("Bad msg 2, Y = 0");
                return;
            }
            byte[] options2 = new byte[OPTIONS2_SIZE];
            try {
                _handshakeState.readMessage(_tmp, 0, MSG2_SIZE, options2, 0);
            } catch (GeneralSecurityException gse) {
                fail("Bad msg 2, Y = " + Base64.encode(_tmp, 0, KEY_SIZE), gse);
                return;
            } catch (RuntimeException re) {
                fail("Bad msg 2, Y = " + Base64.encode(_tmp, 0, KEY_SIZE), re);
                return;
            }
            if (_log.shouldDebug())
                _log.debug("After msg 2: " + _handshakeState.toString());
            _padlen2 = (int) DataHelper.fromLong(options2, 2, 2);
            long tsB = DataHelper.fromLong(options2, 8, 4);
            long now = _context.clock().now();
            // rtt from sending #1 to receiving #2
            long rtt = now - _con.getCreated();
            _peerSkew = (now - (tsB * 1000) - (rtt / 2) + 500) / 1000; 
            if (_peerSkew > MAX_SKEW || _peerSkew < 0 - MAX_SKEW) {
                fail("Clock Skew: " + _peerSkew, null, true);
                return;
            }
            changeState(State.OB_GOT_HXY);
            _received = 0;
        }

        // Read in the padding for message #2
        if (_state == State.OB_GOT_HXY && src.hasRemaining()) {
            int toGet = Math.min(src.remaining(), _padlen2 - _received);
            src.get(_tmp, _received, toGet);
            _received += toGet;
            if (_received < _padlen2)
                return;
            if (_padlen2 > 0) {
                _handshakeState.mixHash(_tmp, 0, _padlen2);
                if (_log.shouldDebug())
                    _log.debug("After mixhash padding " + _padlen2 + " msg 2: " + _handshakeState.toString());
            }
            changeState(State.OB_GOT_PADDING);
            if (src.hasRemaining()) {
                // Outbound conn can never have extra data after msg 2
                fail("Extra data after msg 2: " + src.remaining());
                return;
            }
            prepareOutbound3();
            return;
        }

        // check for remaining data
        if ((_state == State.VERIFIED || _state == State.CORRUPT) && src.hasRemaining()) {
            if (_log.shouldWarn())
                _log.warn("Received unexpected " + src.remaining() + " on " + this, new Exception());
        }
    }

    /**
     *  We are Alice.
     *  Write the 3rd message.
     *
     *  Caller must synch
     */
    private void prepareOutbound3() {
        // create msg 3 part 2 payload
        // payload without MAC
        int msg3p2len = NTCP2Payload.BLOCK_HEADER_SIZE + 1 + _aliceRISize +
                        NTCP2Payload.BLOCK_HEADER_SIZE + OPTIONS3_SIZE +
                        NTCP2Payload.BLOCK_HEADER_SIZE + _padlen3;

        // total for parts 1 and 2 with mac
        byte[] tmp = new byte[MSG3P1_SIZE + msg3p2len + MAC_SIZE];
        List<Block> blocks = new ArrayList<Block>(3);
        Block block = new NTCP2Payload.RIBlock(_aliceRI, false);
        blocks.add(block);
        byte[] opts = new byte[OPTIONS3_SIZE];
        opts[0] = NTCPConnection.PADDING_MIN_DEFAULT_INT;
        opts[1] = NTCPConnection.PADDING_MAX_DEFAULT_INT;
        opts[2] = NTCPConnection.PADDING_MIN_DEFAULT_INT;
        opts[3] = NTCPConnection.PADDING_MAX_DEFAULT_INT;
        DataHelper.toLong(opts, 4, 2, NTCPConnection.DUMMY_DEFAULT);
        DataHelper.toLong(opts, 6, 2, NTCPConnection.DUMMY_DEFAULT);
        DataHelper.toLong(opts, 8, 2, NTCPConnection.DELAY_DEFAULT);
        DataHelper.toLong(opts, 10, 2, NTCPConnection.DELAY_DEFAULT);
        block = new NTCP2Payload.OptionsBlock(opts);
        blocks.add(block);
        // all zeros is fine here
        //block = new NTCP2Payload.PaddingBlock(_context, _padlen3);
        block = new NTCP2Payload.PaddingBlock(_padlen3);
        blocks.add(block);
        // we put it at the offset so it doesn't get overwritten by HandshakeState
        // when it copies the static key in there
        int newoff = NTCP2Payload.writePayload(tmp, MSG3P1_SIZE, blocks);
        int expect = MSG3P1_SIZE + msg3p2len;
        if (newoff != expect)
            throw new IllegalStateException("msg3 size mismatch expected " + expect + " got " + newoff);
        try {
            _handshakeState.writeMessage(tmp, 0, tmp, MSG3P1_SIZE, msg3p2len);
        } catch (GeneralSecurityException gse) {
            // buffer length error
            if (!_log.shouldWarn())
                _log.error("Bad msg 3 out", gse);
            fail("Bad msg 3 out", gse);
            return;
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad msg 3 out", re);
            fail("Bad msg 3 out", re);
            return;
        }
        // send it all at once
        if (_log.shouldDebug())
            _log.debug("Sending msg3, part 1 is:\n" + net.i2p.util.HexDump.dump(tmp, 0, MSG3P1_SIZE));
        _transport.getPumper().wantsWrite(_con, tmp);
        if (_log.shouldDebug())
            _log.debug("After msg 3: " + _handshakeState.toString());
        setDataPhase();
    }

    /**
     *  KDF for data phase,
     *  then calls con.finishOutboundEstablishment(),
     *  passing over the final keys and states to the con.
     *
     *  Caller must synch
     */
    private void setDataPhase() {
        // Data phase ChaChaPoly keys
        CipherStatePair ckp = _handshakeState.split();
        CipherState rcvr = ckp.getReceiver();
        CipherState sender = ckp.getSender();

        // Data phase SipHash keys
        byte[][] sipkeys = generateSipHashKeys(_context, _handshakeState);
        byte[] sip_ab = sipkeys[0];
        byte[] sip_ba = sipkeys[1];

        if (_log.shouldDebug()) {
            _log.debug("Finished establishment for " + this +
                      "\nGenerated SipHash key for A->B: " + Base64.encode(sip_ab) +
                      "\nGenerated SipHash key for B->A: " + Base64.encode(sip_ba));
        }
        // skew in seconds
        _con.finishOutboundEstablishment(sender, rcvr, sip_ab, sip_ba, _peerSkew);
        changeState(State.VERIFIED);
        // no extra data possible
        releaseBufs(true);
        _handshakeState.destroy();
        Arrays.fill(sip_ab, (byte) 0);
        Arrays.fill(sip_ba, (byte) 0);
    }

    /**
     *  KDF for SipHash
     *
     *  @return rv[0] is sip_ab, rv[1] is sip_ba
     */
    static byte[][] generateSipHashKeys(RouterContext ctx, HandshakeState state) {
        // TODO use noise HMAC or HKDF method instead?
        // ask_master = HKDF(ck, zerolen, info="ask")
        HKDF hkdf = new HKDF(ctx);
        byte[] ask_master = new byte[32];
        hkdf.calculate(state.getChainingKey(), ZEROLEN, "ask", ask_master);
        byte[] tmp = new byte[32 + SIPHASH.length];
        byte[] hash = state.getHandshakeHash();
        System.arraycopy(hash, 0, tmp, 0, 32);
        System.arraycopy(SIPHASH, 0, tmp, 32, SIPHASH.length); 
        byte[] sip_master = new byte[32];
        hkdf.calculate(ask_master, tmp, sip_master);
        Arrays.fill(ask_master, (byte) 0);
        Arrays.fill(tmp, (byte) 0);
        byte[] sip_ab = new byte[32];
        byte[] sip_ba = new byte[32];
        hkdf.calculate(sip_master, ZEROLEN, sip_ab, sip_ba, 0);
        Arrays.fill(sip_master, (byte) 0);
        return new byte[][] { sip_ab, sip_ba };
    }

    /**
     *  Release resources on timeout.
     *  @param e may be null
     *  @since 0.9.16
     */
    public synchronized void close(String reason, Exception e) {
        fail(reason, e);
    }

    protected void fail(String reason) { fail(reason, null); }

    protected void fail(String reason, Exception e) { fail(reason, e, false); }

    protected synchronized void fail(String reason, Exception e, boolean bySkew) {
        if (_state == State.CORRUPT || _state == State.VERIFIED)
            return;
        changeState(State.CORRUPT);
        if (_log.shouldWarn()) {
            _log.warn(this + "Failed to establish: " + reason, e);
            _log.warn("State at failure: " + _handshakeState.toString());
        }
        _handshakeState.destroy();
        if (!bySkew)
            _context.statManager().addRateData("ntcp.receiveCorruptEstablishment", 1);
        releaseBufs(false);
    }

    /**
     *  Only call once.
     *
     *  Caller must synch
     */
    private void releaseBufs(boolean isVerified) {
        Arrays.fill(_tmp, (byte) 0);
        // TODO
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("OBES2 ");
        buf.append(_con.toString());
        buf.append(' ').append(_state);
        if (_con.isEstablished()) buf.append(" established");
        buf.append(": ");
        return buf.toString();
    }
}
