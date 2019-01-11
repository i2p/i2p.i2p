package net.i2p.router.transport.ntcp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import static net.i2p.router.transport.ntcp.OutboundNTCP2State.*;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 *
 *  NTCP 1 or 2. We are Bob.
 *
 *  @since 0.9.35 pulled out of EstablishState
 */
class InboundEstablishState extends EstablishBase implements NTCP2Payload.PayloadCallback {

    /** current encrypted block we are reading (IB only) or an IV buf used at the end for OB */
    private byte _curEncrypted[];

    private int _aliceIdentSize;
    private RouterIdentity _aliceIdent;

    /** contains the decrypted aliceIndexSize + aliceIdent + tsA + padding + aliceSig */
    private final ByteArrayOutputStream _sz_aliceIdent_tsA_padding_aliceSig;

    /** how long we expect _sz_aliceIdent_tsA_padding_aliceSig to be when its full */
    private int _sz_aliceIdent_tsA_padding_aliceSigSize;

    private boolean _released;

    //// NTCP2 things

    private HandshakeState _handshakeState;
    private int _padlen1;
    private int _msg3p2len;
    private int _msg3p2FailReason = -1;
    private ByteArray _msg3tmp;
    private NTCP2Options _hisPadding;

    // same as I2PTunnelRunner
    private static final int BUFFER_SIZE = 4*1024;
    private static final int MAX_DATA_READ_BUFS = 32;
    private static final ByteCache _dataReadBufs = ByteCache.getInstance(MAX_DATA_READ_BUFS, BUFFER_SIZE);

    private static final int NTCP1_MSG1_SIZE = XY_SIZE + HXY_SIZE;
    // 287 - 64 = 223
    private static final int PADDING1_MAX = TOTAL1_MAX - MSG1_SIZE;
    private static final int PADDING1_FAIL_MAX = 128;
    private static final int PADDING2_MAX = 64;
    // DSA RI, no options, no addresses
    private static final int RI_MIN = 387 + 8 + 1 + 1 + 2 + 40;
    private static final int MSG3P2_MIN = 1 + 2 + 1 + RI_MIN + MAC_SIZE;
    // absolute max, let's enforce less
    //private static final int MSG3P2_MAX = BUFFER_SIZE - MSG3P1_SIZE;
    private static final int MSG3P2_MAX = 6000;

    private static final Set<State> STATES_NTCP2 =
        EnumSet.of(State.IB_NTCP2_INIT, State.IB_NTCP2_GOT_X, State.IB_NTCP2_GOT_PADDING,
                   State.IB_NTCP2_SENT_Y, State.IB_NTCP2_GOT_RI, State.IB_NTCP2_READ_RANDOM);

    
    public InboundEstablishState(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        super(ctx, transport, con);
        _state = State.IB_INIT;
        _sz_aliceIdent_tsA_padding_aliceSig = new ByteArrayOutputStream(512);
        _prevEncrypted = SimpleByteCache.acquire(AES_SIZE);
        _curEncrypted = SimpleByteCache.acquire(AES_SIZE);
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
    @Override
    public synchronized void receive(ByteBuffer src) {
        super.receive(src);
        if (!src.hasRemaining())
            return; // nothing to receive
        receiveInbound(src);
    }

    /**
     *  Get the NTCP version
     *  @return 1, 2, or 0 if unknown
     *  @since 0.9.35
     */
    public int getVersion() {
        if (!_transport.isNTCP2Enabled())
            return 1;
        synchronized (_stateLock) {
            if (_state == State.IB_INIT)
                return 0;
            if (STATES_NTCP2.contains(_state))
                return 2;
            return 1;
        } 
    } 

    /**
     *  we are Bob, so receive these bytes as part of an inbound connection
     *  This method receives messages 1 and 3, and sends messages 2 and 4.
     *
     *  All data must be copied out of the buffer as Reader.processRead()
     *  will return it to the pool.
     *
     *  Caller must synch.
     *
     */
    private void receiveInbound(ByteBuffer src) {
        if (STATES_NTCP2.contains(_state)) {
            receiveInboundNTCP2(src);
            return;
        }
        if (_state == State.IB_INIT && src.hasRemaining()) {
            int remaining = src.remaining();
            if (_transport.isNTCP2Enabled()) {
                if (remaining + _received < MSG1_SIZE) {
                    // Less than 64 total received, so we defer the NTCP 1 or 2 decision.
                    // Buffer in _X.
                    // Stay in the IB_INIT state, and wait for more data.
                    src.get(_X, _received, remaining);
                    _received += remaining;
                    if (_log.shouldWarn())
                        _log.warn("Short buffer got " + remaining + " total now " + _received + " on " + this);
                    return;
                }
                if (remaining + _received < NTCP1_MSG1_SIZE) {
                    // Less than 288 total received, assume NTCP2
                    // TODO can't change our mind later if we get more than 287
                    _con.setVersion(2);
                    changeState(State.IB_NTCP2_INIT);
                    receiveInboundNTCP2(src);
                    // releaseBufs() will return the unused DH
                    return;
                }
            }
            int toGet = Math.min(remaining, XY_SIZE - _received);
            src.get(_X, _received, toGet);
            _received += toGet;
            if (_received < XY_SIZE)
                return;
            changeState(State.IB_GOT_X);
            _received = 0;
        }

        if (_state == State.IB_GOT_X && src.hasRemaining()) {
            int toGet = Math.min(src.remaining(), HXY_SIZE - _received);
            src.get(_hX_xor_bobIdentHash, _received, toGet);
            _received += toGet;
            if (_received < HXY_SIZE)
                return;
            changeState(State.IB_GOT_HX);
            _received = 0;
        }

        if (_state == State.IB_GOT_HX) {

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix()+"Enough data for a DH received");

                // first verify that Alice knows who she is trying to talk with and that the X
                // isn't corrupt
                byte[] realXor = SimpleByteCache.acquire(HXY_SIZE);
                _context.sha().calculateHash(_X, 0, XY_SIZE, realXor, 0);
                xor32(_context.routerHash().getData(), realXor);
                if (!DataHelper.eq(realXor, _hX_xor_bobIdentHash)) {
                    SimpleByteCache.release(realXor);
                    _context.statManager().addRateData("ntcp.invalidHXxorBIH", 1);
                    fail("Invalid hX_xor");
                    return;
                }
                SimpleByteCache.release(realXor);
                if (!_transport.isHXHIValid(_hX_xor_bobIdentHash)) {
                    // blocklist source? but spoofed IPs could DoS us
                    _context.statManager().addRateData("ntcp.replayHXxorBIH", 1);
                    fail("Replay hX_xor");
                    return;
                }

                try {
                    // ok, they're actually trying to talk to us, and we got their (unauthenticated) X
                    _dh.setPeerPublicValue(_X);
                    _dh.getSessionKey(); // force the calc
                    System.arraycopy(_hX_xor_bobIdentHash, AES_SIZE, _prevEncrypted, 0, AES_SIZE);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(prefix()+"DH session key calculated (" + _dh.getSessionKey().toBase64() + ")");

                    // now prepare our response: Y+E(H(X+Y)+tsB+padding, sk, Y[239:255])
                    byte xy[] = new byte[XY_SIZE + XY_SIZE];
                    System.arraycopy(_X, 0, xy, 0, XY_SIZE);
                    System.arraycopy(_Y, 0, xy, XY_SIZE, XY_SIZE);
                    byte[] hxy = SimpleByteCache.acquire(HXY_SIZE);
                    _context.sha().calculateHash(xy, 0, XY_SIZE + XY_SIZE, hxy, 0);
                    // our (Bob's) timestamp in seconds
                    _tsB = (_context.clock().now() + 500) / 1000l;
                    byte toEncrypt[] = new byte[HXY_TSB_PAD_SIZE];  // 48
                    System.arraycopy(hxy, 0, toEncrypt, 0, HXY_SIZE);
                    byte tsB[] = DataHelper.toLong(4, _tsB);
                    System.arraycopy(tsB, 0, toEncrypt, HXY_SIZE, tsB.length);
                    _context.random().nextBytes(toEncrypt, HXY_SIZE + 4, 12);
                    if (_log.shouldLog(Log.DEBUG)) {
                        _log.debug(prefix()+"h(x+y)="+Base64.encode(hxy));
                        _log.debug(prefix() + "tsb = " + _tsB);
                        _log.debug(prefix()+"unencrypted H(X+Y)+tsB+padding: " + Base64.encode(toEncrypt));
                        _log.debug(prefix()+"encryption iv= " + Base64.encode(_Y, XY_SIZE-AES_SIZE, AES_SIZE));
                        _log.debug(prefix()+"encryption key= " + _dh.getSessionKey().toBase64());
                    }
                    SimpleByteCache.release(hxy);
                    _context.aes().encrypt(toEncrypt, 0, _e_hXY_tsB, 0, _dh.getSessionKey(),
                                           _Y, XY_SIZE-AES_SIZE, HXY_TSB_PAD_SIZE);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(prefix()+"encrypted H(X+Y)+tsB+padding: " + Base64.encode(_e_hXY_tsB));
                    byte write[] = new byte[XY_SIZE + HXY_TSB_PAD_SIZE];
                    System.arraycopy(_Y, 0, write, 0, XY_SIZE);
                    System.arraycopy(_e_hXY_tsB, 0, write, XY_SIZE, HXY_TSB_PAD_SIZE);

                    // ok, now that is prepared, we want to actually send it, so make sure we are up for writing
                    changeState(State.IB_SENT_Y);
                    _transport.getPumper().wantsWrite(_con, write);
                    if (!src.hasRemaining()) return;
                } catch (DHSessionKeyBuilder.InvalidPublicParameterException e) {
                    _context.statManager().addRateData("ntcp.invalidDH", 1);
                    fail("Invalid X", e);
                    return;
                } catch (IllegalStateException ise) {
                    // setPeerPublicValue()
                    fail("reused keys?", ise);
                    return;
                }

        }

        // ok, we are onto the encrypted area, i.e. Message #3
        while ((_state == State.IB_SENT_Y ||
                _state == State.IB_GOT_RI_SIZE ||
                _state == State.IB_GOT_RI) && src.hasRemaining()) {

                // Collect a 16-byte block
                if (_received < AES_SIZE && src.hasRemaining()) {
                    int toGet = Math.min(src.remaining(), AES_SIZE - _received);
                    src.get(_curEncrypted, _received, toGet);
                    _received += toGet;
                    if (_received < AES_SIZE) {
                        // no more bytes available in the buffer, and only a partial
                        // block was read, so we can't decrypt it.
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(prefix() + "end of available data with only a partial block read (" +
                                       + _received + ")");
                        return;
                    }
                }
                // Decrypt the 16-byte block
                if (_received >= AES_SIZE) {
                    _context.aes().decrypt(_curEncrypted, 0, _curDecrypted, 0, _dh.getSessionKey(),
                                           _prevEncrypted, 0, AES_SIZE);

                    byte swap[] = _prevEncrypted;
                    _prevEncrypted = _curEncrypted;
                    _curEncrypted = swap;
                    _received = 0;

                    if (_state == State.IB_SENT_Y) { // we are on the first decrypted block
                        int sz = (int)DataHelper.fromLong(_curDecrypted, 0, 2);
                        if (sz < MIN_RI_SIZE || sz > MAX_RI_SIZE) {
                            _context.statManager().addRateData("ntcp.invalidInboundSize", sz);
                            fail("size is invalid", new Exception("size is " + sz));
                            return;
                        }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(prefix() + "got the RI size: " + sz);
                        _aliceIdentSize  = sz;
                        changeState(State.IB_GOT_RI_SIZE);

                        // We must defer the calculations for total size of the message until
                        //  we get the full alice ident so
                        // we can determine how long the signature is.
                        // See below

                    }
                    try {
                        _sz_aliceIdent_tsA_padding_aliceSig.write(_curDecrypted);
                    } catch (IOException ioe) {
                        if (_log.shouldLog(Log.ERROR)) _log.error(prefix()+"Error writing to the baos?", ioe);
                    }

                    if (_state == State.IB_GOT_RI_SIZE &&
                        _sz_aliceIdent_tsA_padding_aliceSig.size() >= 2 + _aliceIdentSize) {
                        // we have enough to get Alice's RI and determine the sig+padding length
                        readAliceRouterIdentity();
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(prefix() + "got the RI");
                        if (_aliceIdent == null) {
                            // readAliceRouterIdentity already called fail
                            return;
                        }
                        SigType type = _aliceIdent.getSigningPublicKey().getType();
                        if (type == null) {
                            fail("Unsupported sig type");
                            return;
                        }
                        changeState(State.IB_GOT_RI);
                        // handle variable signature size
                        _sz_aliceIdent_tsA_padding_aliceSigSize = 2 + _aliceIdentSize + 4 + type.getSigLen();
                        int rem = (_sz_aliceIdent_tsA_padding_aliceSigSize % AES_SIZE);
                        int padding = 0;
                        if (rem > 0)
                            padding = AES_SIZE-rem;
                        _sz_aliceIdent_tsA_padding_aliceSigSize += padding;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(prefix() + "alice ident size decrypted as " + _aliceIdentSize +
                                       ", making the padding at " + padding + " and total size at " +
                                       _sz_aliceIdent_tsA_padding_aliceSigSize);
                    }

                    if (_state == State.IB_GOT_RI &&
                        _sz_aliceIdent_tsA_padding_aliceSig.size() >= _sz_aliceIdent_tsA_padding_aliceSigSize) {
                        // we have the remainder of Message #3, i.e. the padding+signature
                        // Time to verify.

                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug(prefix() + "got the sig");
                            verifyInbound(src);
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug(prefix()+"verifying size (sz=" + _sz_aliceIdent_tsA_padding_aliceSig.size()
                                           + " expected=" + _sz_aliceIdent_tsA_padding_aliceSigSize
                                           + ' ' + _state
                                           + ')');
                            return;
                    }
                }
        }

        // check for remaining data
        if ((_state == State.VERIFIED || _state == State.CORRUPT) && src.hasRemaining()) {
            if (_log.shouldWarn())
                _log.warn("Received unexpected " + src.remaining() + " on " + this, new Exception());
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(prefix()+"done with the data, not yet complete or corrupt");
    }

    /**
     * We are Bob. We have received enough of message #3 from Alice
     * to get Alice's RouterIdentity.
     *
     * _aliceIdentSize must be set.
     * _sz_aliceIdent_tsA_padding_aliceSig must contain at least 2 + _aliceIdentSize bytes.
     *
     * Sets _aliceIdent so that we
     * may determine the signature and padding sizes.
     *
     * After all of message #3 is received including the signature and
     * padding, verifyIdentity() must be called.
     *
     *  State must be IB_GOT_RI_SIZE.
     *  Caller must synch.
     *
     * @since 0.9.16 pulled out of verifyInbound()
     */
    private void readAliceRouterIdentity() {
        byte b[] = _sz_aliceIdent_tsA_padding_aliceSig.toByteArray();

        try {
            int sz = _aliceIdentSize;
            if (sz < MIN_RI_SIZE || sz > MAX_RI_SIZE ||
                sz > b.length-2) {
                _context.statManager().addRateData("ntcp.invalidInboundSize", sz);
                fail("size is invalid", new Exception("size is " + sz));
                return;
            }
            RouterIdentity alice = new RouterIdentity();
            ByteArrayInputStream bais = new ByteArrayInputStream(b, 2, sz);
            alice.readBytes(bais);
            _aliceIdent = alice;
        } catch (IOException ioe) {
            _context.statManager().addRateData("ntcp.invalidInboundIOE", 1);
            fail("Error verifying peer", ioe);
        } catch (DataFormatException dfe) {
            _context.statManager().addRateData("ntcp.invalidInboundDFE", 1);
            fail("Error verifying peer", dfe);
        }
    }

    /**
     * We are Bob. Verify message #3 from Alice, then send message #4 to Alice.
     * NTCP 1 only.
     *
     * _aliceIdentSize and _aliceIdent must be set.
     * _sz_aliceIdent_tsA_padding_aliceSig must contain at least
     *  (2 + _aliceIdentSize + 4 + padding + sig) bytes.
     *
     * Sets _aliceIdent so that we
     *
     * readAliceRouterIdentity() must have been called previously
     *
     * Make sure the signatures are correct, and if they are, update the
     * NIOConnection with the session key / peer ident / clock skew / iv.
     * The NIOConnection itself is responsible for registering with the
     * transport
     *
     *  State must be IB_GOT_RI.
     *  This will always change the state to VERIFIED or CORRUPT.
     *  Caller must synch.
     *
     *  @param buf possibly containing "extra" data for data phase
     */
    private void verifyInbound(ByteBuffer buf) {
        byte b[] = _sz_aliceIdent_tsA_padding_aliceSig.toByteArray();
        try {
            int sz = _aliceIdentSize;
            // her timestamp from message #3
            long tsA = DataHelper.fromLong(b, 2+sz, 4);
            // _tsB is when we sent message #2
            // Adjust backward by RTT/2
            long now = _context.clock().now();
            // rtt from sending #2 to receiving #3
            long rtt = now - _con.getCreated();
            _peerSkew = (now - (tsA * 1000) - (rtt / 2) + 500) / 1000; 

            ByteArrayOutputStream baos = new ByteArrayOutputStream(768);
            baos.write(_X);
            baos.write(_Y);
            baos.write(_context.routerHash().getData());
            baos.write(DataHelper.toLong(4, tsA));
            baos.write(DataHelper.toLong(4, _tsB));
            //baos.write(b, 2+sz+4, b.length-2-sz-4-Signature.SIGNATURE_BYTES);

            byte toVerify[] = baos.toByteArray();

            // handle variable signature size
            SigType type = _aliceIdent.getSigningPublicKey().getType();
            if (type == null) {
                fail("unsupported sig type");
                return;
            }
            byte s[] = new byte[type.getSigLen()];
            System.arraycopy(b, b.length-s.length, s, 0, s.length);
            Signature sig = new Signature(type, s);
            boolean ok = _context.dsa().verifySignature(sig, toVerify, _aliceIdent.getSigningPublicKey());
            Hash aliceHash = _aliceIdent.calculateHash();
            if (ok) {
                ok = verifyInbound(aliceHash);
            }
            if (ok) {
                _con.setRemotePeer(_aliceIdent);
                sendInboundConfirm(aliceHash, tsA);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix()+"e_bobSig is " + _e_bobSig.length + " bytes long");
                byte iv[] = _curEncrypted;  // reuse buf
                System.arraycopy(_e_bobSig, _e_bobSig.length-AES_SIZE, iv, 0, AES_SIZE);
                // this does not copy the IV, do not release to cache
                // We are Bob, she is Alice, clock skew is Alice-Bob
                // skew in seconds
                _con.finishInboundEstablishment(_dh.getSessionKey(), _peerSkew, iv, _prevEncrypted);
                changeState(State.VERIFIED);
                if (buf.hasRemaining()) {
                    // process "extra" data
                    // This is unlikely for inbound, as we must reply with message 4
                    if (_log.shouldWarn())
                        _log.warn("extra data " + buf.remaining() + " on " + this);
                     _con.recvEncryptedI2NP(buf);
                }
                releaseBufs(true);
                if (_log.shouldLog(Log.INFO))
                    _log.info(prefix()+"Verified remote peer as " + aliceHash);
            } else {
                _context.statManager().addRateData("ntcp.invalidInboundSignature", 1);
                // verifyInbound(aliceHash) called fail()
            }
        } catch (IOException ioe) {
            _context.statManager().addRateData("ntcp.invalidInboundIOE", 1);
            fail("Error verifying peer", ioe);
        }
    }

    /**
     *  Common validation things for both NTCP 1 and 2.
     *  Call after receiving Alice's RouterIdentity (in message 3).
     *  _peerSkew must be set.
     *
     *  Side effect: sets _msg3p2FailReason when returning false
     *
     *  @return success or calls fail() and returns false
     *  @since 0.9.36 pulled out of verifyInbound()
     */
    private boolean verifyInbound(Hash aliceHash) {
        // get inet-addr
        InetAddress addr = this._con.getChannel().socket().getInetAddress();
        byte[] ip = (addr == null) ? null : addr.getAddress();
        if (_context.banlist().isBanlistedForever(aliceHash)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping inbound connection from permanently banlisted peer: " + aliceHash);
            // So next time we will not accept the con from this IP,
            // rather than doing the whole handshake
            if(ip != null)
               _context.blocklist().add(ip);
            if (getVersion() < 2)
                fail("Peer is banlisted forever: " + aliceHash);
            else if (_log.shouldWarn())
                _log.warn("Peer is banlisted forever: " + aliceHash);
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
            return false;
        }
        if(ip != null)
           _transport.setIP(aliceHash, ip);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(prefix() + "verification successful for " + _con);

        long diff = 1000*Math.abs(_peerSkew);
        if (!_context.clock().getUpdatedSuccessfully()) {
            // Adjust the clock one time in desperation
            // This isn't very likely, outbound will do it first
            // We are Bob, she is Alice, adjust to match Alice
            _context.clock().setOffset(1000 * (0 - _peerSkew), true);
            _peerSkew = 0;
            if (diff != 0)
                _log.logAlways(Log.WARN, "NTP failure, NTCP adjusting clock by " + DataHelper.formatDuration(diff));
        } else if (diff >= Router.CLOCK_FUDGE_FACTOR) {
            _context.statManager().addRateData("ntcp.invalidInboundSkew", diff);
            _transport.markReachable(aliceHash, true);
            // Only banlist if we know what time it is
            _context.banlist().banlistRouter(DataHelper.formatDuration(diff),
                                             aliceHash,
                                               _x("Excessive clock skew: {0}"));
            _transport.setLastBadSkew(_peerSkew);
            if (getVersion() < 2)
                fail("Clocks too skewed (" + diff + " ms)", null, true);
            else if (_log.shouldWarn())
                _log.warn("Clocks too skewed (" + diff + " ms)");
            _msg3p2FailReason = NTCPConnection.REASON_SKEW;
            return false;
        } else if (_log.shouldLog(Log.DEBUG)) {
            _log.debug(prefix()+"Clock skew: " + diff + " ms");
        }
        return true;
    }

    /**
     *  Validate network ID, NTCP 2 only.
     *  Call after receiving Alice's RouterInfo,
     *  but before storing it in the netdb.
     *
     *  Side effects: When returning false, sets _msg3p2FailReason,
     *  banlists permanently and blocklists
     *
     *  @return success
     *  @since 0.9.38
     */
    private boolean verifyInboundNetworkID(RouterInfo alice) {
        int aliceID = alice.getNetworkId();
        boolean rv = aliceID == _context.router().getNetworkID();
        if (!rv) {
            Hash aliceHash = alice.getHash();
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping inbound connection from wrong network: " + aliceID + ' ' + aliceHash);
            // So next time we will not accept the con from this IP,
            // rather than doing the whole handshake
            InetAddress addr = _con.getChannel().socket().getInetAddress();
            if (addr != null) {
                byte[] ip = addr.getAddress();
                _context.blocklist().add(ip);
            }
            _context.banlist().banlistRouterForever(aliceHash, "Not in our network: " + aliceID);
            _transport.markUnreachable(aliceHash);
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
        }
        return rv;
    }

    /**
     *  We are Bob. Send message #4 to Alice.
     *
     *  State must be VERIFIED.
     *  Caller must synch.
     *
     *  @param h Alice's Hash
     */
    private void sendInboundConfirm(Hash h, long tsA) {
        // send Alice E(S(X+Y+Alice.identHash+tsA+tsB), sk, prev)
        byte toSign[] = new byte[XY_SIZE + XY_SIZE + 32+4+4];
        int off = 0;
        System.arraycopy(_X, 0, toSign, off, XY_SIZE); off += XY_SIZE;
        System.arraycopy(_Y, 0, toSign, off, XY_SIZE); off += XY_SIZE;
        System.arraycopy(h.getData(), 0, toSign, off, 32); off += 32;
        DataHelper.toLong(toSign, off, 4, tsA); off += 4;
        DataHelper.toLong(toSign, off, 4, _tsB); off += 4;

        // handle variable signature size
        Signature sig = _context.dsa().sign(toSign, _context.keyManager().getSigningPrivateKey());
        int siglen = sig.length();
        int rem = siglen % AES_SIZE;
        int padding;
        if (rem > 0)
            padding = AES_SIZE - rem;
        else
            padding = 0;
        byte preSig[] = new byte[siglen + padding];
        System.arraycopy(sig.getData(), 0, preSig, 0, siglen);
        if (padding > 0)
            _context.random().nextBytes(preSig, siglen, padding);
        _e_bobSig = new byte[preSig.length];
        _context.aes().encrypt(preSig, 0, _e_bobSig, 0, _dh.getSessionKey(), _e_hXY_tsB, HXY_TSB_PAD_SIZE - AES_SIZE, _e_bobSig.length);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(prefix() + "Sending encrypted inbound confirmation");
        _transport.getPumper().wantsWrite(_con, _e_bobSig);
    }

    //// NTCP2 below here

    /**
     *  NTCP2 only. State must be one of IB_NTCP2_*
     *
     *  we are Bob, so receive these bytes as part of an inbound connection
     *  This method receives messages 1 and 3, and sends message 2.
     *
     *  All data must be copied out of the buffer as Reader.processRead()
     *  will return it to the pool.
     *
     *  @since 0.9.36
     */
    private synchronized void receiveInboundNTCP2(ByteBuffer src) {
        if (_state == State.IB_NTCP2_INIT && src.hasRemaining()) {
            // use _X for the buffer
            int toGet = Math.min(src.remaining(), MSG1_SIZE - _received);
            src.get(_X, _received, toGet);
            _received += toGet;
            if (_received < MSG1_SIZE) {
                // Won't get here, now handled in receiveInbound()
                if (_log.shouldWarn())
                    _log.warn("Short buffer got " + toGet + " total now " + _received);
                return;
            }
            changeState(State.IB_NTCP2_GOT_X);
            _received = 0;

            // replay check using encrypted key
            if (!_transport.isHXHIValid(_X)) {
                _context.statManager().addRateData("ntcp.replayHXxorBIH", 1);
                fail("Replay msg 1, eX = " + Base64.encode(_X, 0, KEY_SIZE));
                return;
            }

            try {
                _handshakeState = new HandshakeState(HandshakeState.RESPONDER, _transport.getXDHFactory());
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException("bad proto", gse);
            }
            _handshakeState.getLocalKeyPair().setPublicKey(_transport.getNTCP2StaticPubkey(), 0);
            _handshakeState.getLocalKeyPair().setPrivateKey(_transport.getNTCP2StaticPrivkey(), 0);
            Hash h = _context.routerHash();
            SessionKey bobHash = new SessionKey(h.getData());
            // save encrypted data for CBC for msg 2
            System.arraycopy(_X, KEY_SIZE - IV_SIZE, _prevEncrypted, 0, IV_SIZE);
            _context.aes().decrypt(_X, 0, _X, 0, bobHash, _transport.getNTCP2StaticIV(), KEY_SIZE);
            if (DataHelper.eqCT(_X, 0, ZEROKEY, 0, KEY_SIZE)) {
                fail("Bad msg 1, X = 0");
                return;
            }
            byte options[] = new byte[OPTIONS1_SIZE];
            try {
                _handshakeState.start();
                if (_log.shouldDebug())
                    _log.debug("After start: " + _handshakeState.toString());
                _handshakeState.readMessage(_X, 0, MSG1_SIZE, options, 0);
            } catch (GeneralSecurityException gse) {
                // Read a random number of bytes, store wanted in _padlen1
                _padlen1 = _context.random().nextInt(PADDING1_FAIL_MAX) - src.remaining();
                if (_padlen1 > 0) {
                    // delayed fail for probing resistance
                    // need more bytes before failure
                    if (_log.shouldWarn())
                        _log.warn("Bad msg 1, X = " + Base64.encode(_X, 0, KEY_SIZE) + " with " + src.remaining() +
                                  " more bytes, waiting for " + _padlen1 + " more bytes", gse);
                    changeState(State.IB_NTCP2_READ_RANDOM);
                } else {
                    // got all we need, fail now
                    fail("Bad msg 1, X = " + Base64.encode(_X, 0, KEY_SIZE) + " remaining = " + src.remaining(), gse);
                }
                return;
            } catch (RuntimeException re) {
                fail("Bad msg 1, X = " + Base64.encode(_X, 0, KEY_SIZE), re);
                return;
            }
            if (_log.shouldDebug())
                _log.debug("After msg 1: " + _handshakeState.toString());
            int v = options[1] & 0xff;
            if (v != NTCPTransport.NTCP2_INT_VERSION) {
                fail("Bad version: " + v);
                return;
            }
            _padlen1 = (int) DataHelper.fromLong(options, 2, 2);
            _msg3p2len = (int) DataHelper.fromLong(options, 4, 2);
            long tsA = DataHelper.fromLong(options, 8, 4);
            long now = _context.clock().now();
            // In NTCP1, timestamp comes in msg 3 so we know the RTT.
            // In NTCP2, it comes in msg 1, so just guess.
            // We could defer this to msg 3 to calculate the RTT?
            long rtt = 250;
            _peerSkew = (now - (tsA * 1000) - (rtt / 2) + 500) / 1000; 
            if ((_peerSkew > MAX_SKEW || _peerSkew < 0 - MAX_SKEW) &&
                !_context.clock().getUpdatedSuccessfully()) {
                // If not updated successfully, allow it.
                // This isn't very likely, outbound will do it first
                // See verifyInbound() above.
                fail("Clock Skew: " + _peerSkew, null, true);
                return;
            }
            if (_padlen1 > PADDING1_MAX) {
                fail("bad msg 1 padlen: " + _padlen1);
                return;
            }
            if (_msg3p2len < MSG3P2_MIN || _msg3p2len > MSG3P2_MAX) {
                fail("bad msg3p2 len: " + _msg3p2len);
                return;
            }
            if (_padlen1 <= 0) {
                // No padding specified, go straight to sending msg 2
                changeState(State.IB_NTCP2_GOT_PADDING);
                if (src.hasRemaining()) {
                    // Inbound conn can never have extra data after msg 1
                    fail("Extra data after msg 1: " + src.remaining());
                } else {
                    // write msg 2
                    prepareOutbound2();
                }
                return;
            }
        }

        // delayed fail for probing resistance
        if (_state == State.IB_NTCP2_READ_RANDOM && src.hasRemaining()) {
            // read more bytes before failing
            _received += src.remaining();
            if (_received < _padlen1) {
                if (_log.shouldWarn())
                    _log.warn("Bad msg 1, got " + src.remaining() +
                              " more bytes, waiting for " + (_padlen1 - _received) + " more bytes");
            } else {
                fail("Bad msg 1, failing after getting " + src.remaining() + " more bytes");
            }
            return;
        }

        if (_state == State.IB_NTCP2_GOT_X && src.hasRemaining()) {
            // skip this if _padlen1 == 0;
            // use _X for the buffer
            int toGet = Math.min(src.remaining(), _padlen1 - _received);
            src.get(_X, _received, toGet);
            _received += toGet;
            if (_received < _padlen1)
                return;
            changeState(State.IB_NTCP2_GOT_PADDING);
            _handshakeState.mixHash(_X, 0, _padlen1);
            if (_log.shouldDebug())
                _log.debug("After mixhash padding " + _padlen1 + " msg 1: " + _handshakeState.toString());
            _received = 0;
            if (src.hasRemaining()) {
                // Inbound conn can never have extra data after msg 1
                fail("Extra data after msg 1: " + src.remaining());
            } else {
                // write msg 2
                prepareOutbound2();
            }
            return;
        }

        if (_state == State.IB_NTCP2_SENT_Y && src.hasRemaining()) {
            int msg3tot = MSG3P1_SIZE + _msg3p2len;
            if (_msg3tmp == null)
                _msg3tmp = _dataReadBufs.acquire();
            // use _X for the buffer FIXME too small
            byte[] tmp = _msg3tmp.getData();
            int toGet = Math.min(src.remaining(), msg3tot - _received);
            src.get(tmp, _received, toGet);
            _received += toGet;
            if (_received < msg3tot)
                return;
            changeState(State.IB_NTCP2_GOT_RI);
            _received = 0;
            ByteArray ptmp = _dataReadBufs.acquire();
            byte[] payload = ptmp.getData();
            try {
                _handshakeState.readMessage(tmp, 0, msg3tot, payload, 0);
            } catch (GeneralSecurityException gse) {
                // TODO delayed failure per spec, as in NTCPConnection.delayedClose()
                _dataReadBufs.release(ptmp, false);
                fail("Bad msg 3, part 1 is:\n" + net.i2p.util.HexDump.dump(tmp, 0, MSG3P1_SIZE), gse);
                return;
            } catch (RuntimeException re) {
                _dataReadBufs.release(ptmp, false);
                fail("Bad msg 3", re);
                return;
            }
            if (_log.shouldDebug())
                _log.debug("After msg 3: " + _handshakeState.toString());
            try {
                // calls callbacks below
                NTCP2Payload.processPayload(_context, this, payload, 0, _msg3p2len - MAC_SIZE, true);
            } catch (IOException ioe) {
                if (_log.shouldWarn())
                    _log.warn("Bad msg 3 payload", ioe);
                // probably payload frame/block problems
                // setDataPhase() will send termination
                if (_msg3p2FailReason < 0)
                    _msg3p2FailReason = NTCPConnection.REASON_FRAMING;
            } catch (DataFormatException dfe) {
                if (_log.shouldWarn())
                    _log.warn("Bad msg 3 payload", dfe);
                // probably RI problems
                // setDataPhase() will send termination
                if (_msg3p2FailReason < 0)
                    _msg3p2FailReason = NTCPConnection.REASON_SIGFAIL;
                _context.statManager().addRateData("ntcp.invalidInboundSignature", 1);
            } catch (I2NPMessageException ime) {
                // shouldn't happen, no I2NP msgs in msg3p2
                if (_log.shouldWarn())
                    _log.warn("Bad msg 3 payload", ime);
                // setDataPhase() will send termination
                if (_msg3p2FailReason < 0)
                    _msg3p2FailReason = 0;
            } finally {
                _dataReadBufs.release(ptmp, false);
            }

            // pass buffer for processing of "extra" data
            setDataPhase(src);
        }
        // TODO check for remaining data and log/throw
    }

    /**
     *  Write the 2nd NTCP2 message.
     *  IV (CBC from msg 1) must be in _prevEncrypted
     *
     *  @since 0.9.36
     */
    private synchronized void prepareOutbound2() {
        // create msg 2 payload
        byte[] options2 = new byte[OPTIONS2_SIZE];
        int padlen2 = _context.random().nextInt(PADDING2_MAX);
        DataHelper.toLong(options2, 2, 2, padlen2);
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(options2, 8, 4, now);
        byte[] tmp = new byte[MSG2_SIZE + padlen2];
        try {
            _handshakeState.writeMessage(tmp, 0, options2, 0, OPTIONS2_SIZE);
        } catch (GeneralSecurityException gse) {
            // buffer length error
            if (!_log.shouldWarn())
                _log.error("Bad msg 2 out", gse);
            fail("Bad msg 2 out", gse);
            return;
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad msg 2 out", re);
            fail("Bad msg 2 out", re);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("After msg 2: " + _handshakeState.toString());
        Hash h = _context.routerHash();
        SessionKey bobHash = new SessionKey(h.getData());
        _context.aes().encrypt(tmp, 0, tmp, 0, bobHash, _prevEncrypted, KEY_SIZE);
        if (padlen2 > 0) {
            _context.random().nextBytes(tmp, MSG2_SIZE, padlen2);
            _handshakeState.mixHash(tmp, MSG2_SIZE, padlen2);
            if (_log.shouldDebug())
                _log.debug("After mixhash padding " + padlen2 + " msg 2: " + _handshakeState.toString());
        }

        changeState(State.IB_NTCP2_SENT_Y);
        // send it all at once
        _transport.getPumper().wantsWrite(_con, tmp);
    }

    /**
     *  KDF for NTCP2 data phase.
     *
     *  If _msg3p2FailReason is less than zero,
     *  this calls con.finishInboundEstablishment(),
     *  passing over the final keys and states to the con,
     *  and changes the state to VERIFIED.
     *
     *  Otherwise, it calls con.failInboundEstablishment(),
     *  which will send a termination message,
     *  and changes the state to CORRUPT.
     *
     *  If you don't call this, call fail().
     *
     *  @param buf possibly containing "extra" data for data phase
     *  @since 0.9.36
     */
    private synchronized void setDataPhase(ByteBuffer buf) {
        // Data phase ChaChaPoly keys
        CipherStatePair ckp = _handshakeState.split();
        CipherState rcvr = ckp.getReceiver();
        CipherState sender = ckp.getSender();

        // Data phase SipHash keys
        byte[][] sipkeys = generateSipHashKeys(_context, _handshakeState);
        byte[] sip_ab = sipkeys[0];
        byte[] sip_ba = sipkeys[1];

        if (_msg3p2FailReason >= 0) {
            if (_log.shouldWarn())
                _log.warn("Failed msg3p2, code " + _msg3p2FailReason + " for " + this);
            _con.failInboundEstablishment(sender, sip_ba, _msg3p2FailReason);
            changeState(State.CORRUPT);
        } else {
            if (_log.shouldDebug()) {
                _log.debug("Finished establishment for " + this +
                          "\nGenerated SipHash key for A->B: " + Base64.encode(sip_ab) +
                          "\nGenerated SipHash key for B->A: " + Base64.encode(sip_ba));
            }
            // skew in seconds
            _con.finishInboundEstablishment(sender, rcvr, sip_ba, sip_ab, _peerSkew, _hisPadding);
            changeState(State.VERIFIED);
            if (buf.hasRemaining()) {
                // process "extra" data
                // This is very likely for inbound, as data should come right after message 3
                if (_log.shouldInfo())
                    _log.info("extra data " + buf.remaining() + " on " + this);
                 _con.recvEncryptedI2NP(buf);
            }
        }
        // zero out everything
        releaseBufs(true);
        _handshakeState.destroy();
        Arrays.fill(sip_ab, (byte) 0);
        Arrays.fill(sip_ba, (byte) 0);
    }

    //// PayloadCallbacks

    /**
     *  Get "s" static key out of RI, compare to what we got in the handshake.
     *  Tell NTCPConnection who it is.
     *
     *  @param isHandshake always true
     *  @throws DataFormatException on bad sig, unknown SigType, no static key,
     *                                 static key mismatch, IP checks in verifyInbound()
     *  @since 0.9.36
     */
    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
        // Validate Alice static key
        // find address with matching version
        List<RouterAddress> addrs = ri.getTargetAddresses(NTCPTransport.STYLE, NTCPTransport.STYLE2);
        if (addrs.isEmpty()) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("no NTCP in RI: " + ri);
        }
        String s = null;
        for (RouterAddress addr : addrs) {
            String v = addr.getOption("v");
            if (v == null ||
                (!v.equals(NTCPTransport.NTCP2_VERSION) && !v.startsWith(NTCPTransport.NTCP2_VERSION_ALT))) {
                 continue;
            }
            s = addr.getOption("s");
            if (s != null)
                break;
        }
        if (s == null) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("no s in RI: " + ri);
        }
        byte[] sb = Base64.decode(s);
        if (sb == null || sb.length != KEY_SIZE) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("bad s in RI: " + ri);
        }
        byte[] nb = new byte[32];
        // compare to the _handshakeState
        _handshakeState.getRemotePublicKey().getPublicKey(nb, 0);
        if (!DataHelper.eqCT(sb, 0, nb, 0, KEY_SIZE)) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("s mismatch in RI: " + ri);
        }
        _aliceIdent = ri.getIdentity();
        Hash h = _aliceIdent.calculateHash();
        // this sets the reason
        boolean ok = verifyInbound(h);
        if (!ok)
            throw new DataFormatException("NTCP2 verifyInbound() fail");
        ok = verifyInboundNetworkID(ri);
        if (!ok)
            throw new DataFormatException("NTCP2 network ID mismatch");
        try {
            RouterInfo old = _context.netDb().store(h, ri);
            if (flood && !ri.equals(old)) {
                FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
                if (fndf.floodConditional(ri)) {
                    if (_log.shouldDebug())
                        _log.debug("Flooded the RI: " + h);
                } else {
                    if (_log.shouldInfo())
                        _log.info("Flood request but we didn't: " + h);
                }
            }
        } catch (IllegalArgumentException iae) {
            // hash collision?
            // expired RI?
            _msg3p2FailReason = NTCPConnection.REASON_MSG3;
            throw new DataFormatException("RI store fail: " + ri, iae);
        }
        _con.setRemotePeer(_aliceIdent);
    }

    /** @since 0.9.36 */
    public void gotOptions(byte[] options, boolean isHandshake) {
        NTCP2Options hisPadding = NTCP2Options.fromByteArray(options);
        if (hisPadding == null) {
            if (_log.shouldWarn())
                _log.warn("Got options length " + options.length + " on: " + this);
            return;
        }
        _hisPadding = hisPadding;
    }

    /** @since 0.9.36 */
    public void gotPadding(int paddingLength, int frameLength) {}

    // Following 4 are illegal in handshake, we will never get them

    /** @since 0.9.36 */
    public void gotTermination(int reason, long lastReceived) {}
    /** @since 0.9.36 */
    public void gotUnknown(int type, int len) {}
    /** @since 0.9.36 */
    public void gotDateTime(long time) {}
    /** @since 0.9.36 */
    public void gotI2NP(I2NPMessage msg) {}

    /**
     *  @since 0.9.16
     */
    @Override
    protected synchronized void fail(String reason, Exception e, boolean bySkew) {
        super.fail(reason, e, bySkew);
        if (_handshakeState != null) {
            if (_log.shouldWarn())
                _log.warn("State at failure: " + _handshakeState.toString());
            _handshakeState.destroy();
        }
    }

    /**
     *  Only call once. Caller must synch.
     *  @since 0.9.16
     */
    @Override
    protected void releaseBufs(boolean isVerified) {
        if (_released)
            return;
        _released = true;
        super.releaseBufs(isVerified);
        // Do not release _curEncrypted if verified, it is passed to
        // NTCPConnection to use as the IV
        if (!isVerified)
            SimpleByteCache.release(_curEncrypted);
        Arrays.fill(_X, (byte) 0);
        SimpleByteCache.release(_X);
        if (_msg3tmp != null) {
            _dataReadBufs.release(_msg3tmp, false);
            _msg3tmp = null;
        }
    }
}
