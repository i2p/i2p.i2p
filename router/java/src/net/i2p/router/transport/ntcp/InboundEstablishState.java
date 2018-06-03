package net.i2p.router.transport.ntcp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.Signature;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 *
 *  NTCP 1 or 2. We are Bob.
 *
 *  @since 0.9.35 pulled out of EstablishState
 */
class InboundEstablishState extends EstablishBase {

    /** current encrypted block we are reading (IB only) or an IV buf used at the end for OB */
    private byte _curEncrypted[];

    private int _aliceIdentSize;
    private RouterIdentity _aliceIdent;

    /** contains the decrypted aliceIndexSize + aliceIdent + tsA + padding + aliceSig */
    private final ByteArrayOutputStream _sz_aliceIdent_tsA_padding_aliceSig;

    /** how long we expect _sz_aliceIdent_tsA_padding_aliceSig to be when its full */
    private int _sz_aliceIdent_tsA_padding_aliceSigSize;

    private static final int NTCP1_MSG1_SIZE = XY_SIZE + HXY_SIZE;
    
    public InboundEstablishState(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        super(ctx, transport, con);
        _state = State.IB_INIT;
        _sz_aliceIdent_tsA_padding_aliceSig = new ByteArrayOutputStream(512);
        _prevEncrypted = SimpleByteCache.acquire(AES_SIZE);
        _curEncrypted = SimpleByteCache.acquire(AES_SIZE);
    }

    /**
     * parse the contents of the buffer as part of the handshake.  if the
     * handshake is completed and there is more data remaining, the data are
     * copieed out so that the next read will be the (still encrypted) remaining
     * data (available from getExtraBytes)
     *
     * All data must be copied out of the buffer as Reader.processRead()
     * will return it to the pool.
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
            // TODO NTCP2 states
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
     *  FIXME none of the _state comparisons use _stateLock, but whole thing
     *  is synchronized, should be OK. See isComplete()
     */
    private void receiveInbound(ByteBuffer src) {
        if (_state == State.IB_INIT && src.hasRemaining()) {
            int remaining = src.remaining();
            //if (remaining < NTCP1_MSG1_SIZE && _transport.isNTCP2Enabled()) {
            //    // NTCP2
            //}
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
                            verifyInbound();
                            if (_state == State.VERIFIED && src.hasRemaining())
                                prepareExtra(src);
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug(prefix()+"verifying size (sz=" + _sz_aliceIdent_tsA_padding_aliceSig.size()
                                           + " expected=" + _sz_aliceIdent_tsA_padding_aliceSigSize
                                           + ' ' + _state
                                           + ')');
                            return;
                    }
                } else {
                }
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
     *  Caller must synch.
     */
    private void verifyInbound() {
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
            if (ok) {
                // get inet-addr
                InetAddress addr = this._con.getChannel().socket().getInetAddress();
                byte[] ip = (addr == null) ? null : addr.getAddress();
                if (_context.banlist().isBanlistedForever(_aliceIdent.calculateHash())) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping inbound connection from permanently banlisted peer: " + _aliceIdent.calculateHash());
                    // So next time we will not accept the con from this IP,
                    // rather than doing the whole handshake
                    if(ip != null)
                       _context.blocklist().add(ip);
                    fail("Peer is banlisted forever: " + _aliceIdent.calculateHash());
                    return;
                }
                if(ip != null)
                   _transport.setIP(_aliceIdent.calculateHash(), ip);
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
                    _transport.markReachable(_aliceIdent.calculateHash(), true);
                    // Only banlist if we know what time it is
                    _context.banlist().banlistRouter(DataHelper.formatDuration(diff),
                                                       _aliceIdent.calculateHash(),
                                                       _x("Excessive clock skew: {0}"));
                    _transport.setLastBadSkew(_peerSkew);
                    fail("Clocks too skewed (" + diff + " ms)", null, true);
                    return;
                } else if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug(prefix()+"Clock skew: " + diff + " ms");
                }

                _con.setRemotePeer(_aliceIdent);
                sendInboundConfirm(_aliceIdent, tsA);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix()+"e_bobSig is " + _e_bobSig.length + " bytes long");
                byte iv[] = _curEncrypted;  // reuse buf
                System.arraycopy(_e_bobSig, _e_bobSig.length-AES_SIZE, iv, 0, AES_SIZE);
                // this does not copy the IV, do not release to cache
                // We are Bob, she is Alice, clock skew is Alice-Bob
                _con.finishInboundEstablishment(_dh.getSessionKey(), _peerSkew, iv, _prevEncrypted); // skew in seconds
                releaseBufs(true);
                if (_log.shouldLog(Log.INFO))
                    _log.info(prefix()+"Verified remote peer as " + _aliceIdent.calculateHash());
                changeState(State.VERIFIED);
            } else {
                _context.statManager().addRateData("ntcp.invalidInboundSignature", 1);
                fail("Peer verification failed - spoof of " + _aliceIdent.calculateHash() + "?");
            }
        } catch (IOException ioe) {
            _context.statManager().addRateData("ntcp.invalidInboundIOE", 1);
            fail("Error verifying peer", ioe);
        }
    }

    /**
     *  We are Bob. Send message #4 to Alice.
     *
     *  State must be VERIFIED.
     *  Caller must synch.
     */
    private void sendInboundConfirm(RouterIdentity alice, long tsA) {
        // send Alice E(S(X+Y+Alice.identHash+tsA+tsB), sk, prev)
        byte toSign[] = new byte[XY_SIZE + XY_SIZE + 32+4+4];
        int off = 0;
        System.arraycopy(_X, 0, toSign, off, XY_SIZE); off += XY_SIZE;
        System.arraycopy(_Y, 0, toSign, off, XY_SIZE); off += XY_SIZE;
        Hash h = alice.calculateHash();
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

    /**
     *  Only call once. Caller must synch.
     *  @since 0.9.16
     */
    @Override
    protected void releaseBufs(boolean isVerified) {
        super.releaseBufs(isVerified);
        // Do not release _curEncrypted if verified, it is passed to
        // NTCPConnection to use as the IV
        if (!isVerified)
            SimpleByteCache.release(_curEncrypted);
        SimpleByteCache.release(_X);
    }
}
