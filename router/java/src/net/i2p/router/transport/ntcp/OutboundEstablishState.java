package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import net.i2p.crypto.SigType;
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
 *  NTCP 1 only. We are Alice.
 *
 *  @since 0.9.35 pulled out of EstablishState
 */
class OutboundEstablishState extends EstablishBase {
    
    public OutboundEstablishState(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        super(ctx, transport, con);
        _state = State.OB_INIT;
        ctx.sha().calculateHash(_X, 0, XY_SIZE, _hX_xor_bobIdentHash, 0);
        xor32(con.getRemotePeer().calculateHash().getData(), _hX_xor_bobIdentHash);
        // _prevEncrypted will be created later
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
        receiveOutbound(src);
    }

    /**
     *  Get the NTCP version
     *  @return 1
     *  @since 0.9.35
     */
    public int getVersion() { return 1; }

    /**
     *  We are Alice, so receive these bytes as part of an outbound connection.
     *  This method receives messages 2 and 4, and sends message 3.
     *
     *  All data must be copied out of the buffer as Reader.processRead()
     *  will return it to the pool.
     *
     *  Caller must synch.
     *
     *  FIXME none of the _state comparisons use _stateLock, but whole thing
     *  is synchronized, should be OK. See isComplete()
     */
    private void receiveOutbound(ByteBuffer src) {

        // recv Y+E(H(X+Y)+tsB, sk, Y[239:255])
        // Read in Y, which is the first part of message #2
        while (_state == State.OB_SENT_X && src.hasRemaining()) {
            byte c = src.get();
            _Y[_received++] = c;
            if (_received >= XY_SIZE) {
                try {
                    _dh.setPeerPublicValue(_Y);
                    _dh.getSessionKey(); // force the calc
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(prefix()+"DH session key calculated (" + _dh.getSessionKey().toBase64() + ")");
                    changeState(State.OB_GOT_Y);
                } catch (DHSessionKeyBuilder.InvalidPublicParameterException e) {
                    _context.statManager().addRateData("ntcp.invalidDH", 1);
                    fail("Invalid X", e);
                    return;
                }
            }
        }

        // Read in Y, which is the first part of message #2
        // Read in the rest of message #2
        while (_state == State.OB_GOT_Y && src.hasRemaining()) {
            int i = _received-XY_SIZE;
            _received++;
            byte c = src.get();
            _e_hXY_tsB[i] = c;
            if (i+1 >= HXY_TSB_PAD_SIZE) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug(prefix() + "received _e_hXY_tsB fully");
                byte hXY_tsB[] = new byte[HXY_TSB_PAD_SIZE];
                _context.aes().decrypt(_e_hXY_tsB, 0, hXY_tsB, 0, _dh.getSessionKey(), _Y, XY_SIZE-AES_SIZE, HXY_TSB_PAD_SIZE);
                byte XY[] = new byte[XY_SIZE + XY_SIZE];
                System.arraycopy(_X, 0, XY, 0, XY_SIZE);
                System.arraycopy(_Y, 0, XY, XY_SIZE, XY_SIZE);
                byte[] h = SimpleByteCache.acquire(HXY_SIZE);
                _context.sha().calculateHash(XY, 0, XY_SIZE + XY_SIZE, h, 0);
                if (!DataHelper.eq(h, 0, hXY_tsB, 0, HXY_SIZE)) {
                    SimpleByteCache.release(h);
                    _context.statManager().addRateData("ntcp.invalidHXY", 1);
                    fail("Invalid H(X+Y) - mitm attack attempted?");
                    return;
                }
                SimpleByteCache.release(h);
                changeState(State.OB_GOT_HXY);
                // their (Bob's) timestamp in seconds
                _tsB = DataHelper.fromLong(hXY_tsB, HXY_SIZE, 4);
                long now = _context.clock().now();
                // rtt from sending #1 to receiving #2
                long rtt = now - _con.getCreated();
                // our (Alice's) timestamp in seconds
                _tsA = (now + 500) / 1000;
                _peerSkew = (now - (_tsB * 1000) - (rtt / 2) + 500) / 1000; 
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix()+"h(X+Y) is correct, skew = " + _peerSkew);

                // the skew is not authenticated yet, but it is certainly fatal to
                // the establishment, so fail hard if appropriate
                long diff = 1000*Math.abs(_peerSkew);
                if (!_context.clock().getUpdatedSuccessfully()) {
                    // Adjust the clock one time in desperation
                    // We are Alice, he is Bob, adjust to match Bob
                    _context.clock().setOffset(1000 * (0 - _peerSkew), true);
                    _peerSkew = 0;
                    if (diff != 0)
                        _log.logAlways(Log.WARN, "NTP failure, NTCP adjusting clock by " + DataHelper.formatDuration(diff));
                } else if (diff >= Router.CLOCK_FUDGE_FACTOR) {
                    _context.statManager().addRateData("ntcp.invalidOutboundSkew", diff);
                    _transport.markReachable(_con.getRemotePeer().calculateHash(), false);
                    // Only banlist if we know what time it is
                    _context.banlist().banlistRouter(DataHelper.formatDuration(diff),
                                                       _con.getRemotePeer().calculateHash(),
                                                       _x("Excessive clock skew: {0}"));
                    _transport.setLastBadSkew(_peerSkew);
                    fail("Clocks too skewed (" + diff + " ms)", null, true);
                    return;
                } else if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug(prefix()+"Clock skew: " + diff + " ms");
                }

                // now prepare and send our response
                // send E(#+Alice.identity+tsA+padding+S(X+Y+Bob.identHash+tsA+tsB), sk, hX_xor_Bob.identHash[16:31])
                int sigSize = XY_SIZE + XY_SIZE + HXY_SIZE + 4+4;//+12;
                byte preSign[] = new byte[sigSize];
                System.arraycopy(_X, 0, preSign, 0, XY_SIZE);
                System.arraycopy(_Y, 0, preSign, XY_SIZE, XY_SIZE);
                System.arraycopy(_con.getRemotePeer().calculateHash().getData(), 0, preSign, XY_SIZE + XY_SIZE, HXY_SIZE);
                DataHelper.toLong(preSign, XY_SIZE + XY_SIZE + HXY_SIZE, 4, _tsA);
                DataHelper.toLong(preSign, XY_SIZE + XY_SIZE + HXY_SIZE + 4, 4, _tsB);
                // hXY_tsB has 12 bytes of padding (size=48, tsB=4 + hXY=32)
                Signature sig = _context.dsa().sign(preSign, _context.keyManager().getSigningPrivateKey());

                byte ident[] = _context.router().getRouterInfo().getIdentity().toByteArray();
                // handle variable signature size
                int min = 2 + ident.length + 4 + sig.length();
                int rem = min % AES_SIZE;
                int padding = 0;
                if (rem > 0)
                    padding = AES_SIZE - rem;
                byte preEncrypt[] = new byte[min+padding];
                DataHelper.toLong(preEncrypt, 0, 2, ident.length);
                System.arraycopy(ident, 0, preEncrypt, 2, ident.length);
                DataHelper.toLong(preEncrypt, 2+ident.length, 4, _tsA);
                if (padding > 0)
                    _context.random().nextBytes(preEncrypt, 2 + ident.length + 4, padding);
                System.arraycopy(sig.getData(), 0, preEncrypt, 2+ident.length+4+padding, sig.length());

                _prevEncrypted = new byte[preEncrypt.length];
                _context.aes().encrypt(preEncrypt, 0, _prevEncrypted, 0, _dh.getSessionKey(),
                                       _hX_xor_bobIdentHash, _hX_xor_bobIdentHash.length-AES_SIZE, preEncrypt.length);

                changeState(State.OB_SENT_RI);
                _transport.getPumper().wantsWrite(_con, _prevEncrypted);
            }
        }

        // Read in message #4
        if (_state == State.OB_SENT_RI && src.hasRemaining()) {
            // we are receiving their confirmation

            // recv E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev)
            int off = 0;
            if (_e_bobSig == null) {
                // handle variable signature size
                int siglen = _con.getRemotePeer().getSigningPublicKey().getType().getSigLen();
                int rem = siglen % AES_SIZE;
                int padding;
                if (rem > 0)
                    padding = AES_SIZE - rem;
                else
                    padding = 0;
                _e_bobSig = new byte[siglen + padding];
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix() + "receiving E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev) (remaining? " +
                               src.hasRemaining() + ")");
            } else {
                off = _received - XY_SIZE - HXY_TSB_PAD_SIZE;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix() + "continuing to receive E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev) (remaining? " +
                               src.hasRemaining() + " off=" + off + " recv=" + _received + ")");
            }
            while (_state == State.OB_SENT_RI && src.hasRemaining()) {
                _e_bobSig[off++] = src.get();
                _received++;

                if (off >= _e_bobSig.length) {
                    changeState(State.OB_GOT_SIG);
                    byte bobSig[] = new byte[_e_bobSig.length];
                    _context.aes().decrypt(_e_bobSig, 0, bobSig, 0, _dh.getSessionKey(),
                                           _e_hXY_tsB, HXY_TSB_PAD_SIZE - AES_SIZE, _e_bobSig.length);
                    // ignore the padding
                    // handle variable signature size
                    SigType type = _con.getRemotePeer().getSigningPublicKey().getType();
                    int siglen = type.getSigLen();
                    byte bobSigData[] = new byte[siglen];
                    System.arraycopy(bobSig, 0, bobSigData, 0, siglen);
                    Signature sig = new Signature(type, bobSigData);

                    byte toVerify[] = new byte[XY_SIZE + XY_SIZE + HXY_SIZE +4+4];
                    int voff = 0;
                    System.arraycopy(_X, 0, toVerify, voff, XY_SIZE); voff += XY_SIZE;
                    System.arraycopy(_Y, 0, toVerify, voff, XY_SIZE); voff += XY_SIZE;
                    System.arraycopy(_context.routerHash().getData(), 0, toVerify, voff, HXY_SIZE); voff += HXY_SIZE;
                    DataHelper.toLong(toVerify, voff, 4, _tsA); voff += 4;
                    DataHelper.toLong(toVerify, voff, 4, _tsB); voff += 4;

                    boolean ok = _context.dsa().verifySignature(sig, toVerify, _con.getRemotePeer().getSigningPublicKey());
                    if (!ok) {
                        _context.statManager().addRateData("ntcp.invalidSignature", 1);
                        fail("Signature was invalid - attempt to spoof " + _con.getRemotePeer().calculateHash().toBase64() + "?");
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(prefix() + "signature verified from Bob.  done!");
                        prepareExtra(src);
                        byte nextWriteIV[] = SimpleByteCache.acquire(AES_SIZE);
                        System.arraycopy(_prevEncrypted, _prevEncrypted.length-AES_SIZE, nextWriteIV, 0, AES_SIZE);
                        // this does not copy the nextWriteIV, do not release to cache
                        // We are Alice, he is Bob, clock skew is Bob - Alice
                        _con.finishOutboundEstablishment(_dh.getSessionKey(), _peerSkew, nextWriteIV, _e_bobSig); // skew in seconds
                        releaseBufs(true);
                        // if socket gets closed this will be null - prevent NPE
                        InetAddress ia = _con.getChannel().socket().getInetAddress();
                        if (ia != null)
                            _transport.setIP(_con.getRemotePeer().calculateHash(), ia.getAddress());
                        changeState(State.VERIFIED);
                    }
                    return;
                }
            }
        }
    }

    /**
     * We are Alice.
     * We are establishing an outbound connection, so prepare ourselves by
     * queueing up the write of the first part of the handshake
     * This method sends message #1 to Bob.
     */
    public synchronized void prepareOutbound() {
        boolean shouldSend;
        synchronized(_stateLock) {    
            shouldSend = _state == State.OB_INIT;
        }
        if (shouldSend) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(prefix() + "send X");
            byte toWrite[] = new byte[XY_SIZE + _hX_xor_bobIdentHash.length];
            System.arraycopy(_X, 0, toWrite, 0, XY_SIZE);
            System.arraycopy(_hX_xor_bobIdentHash, 0, toWrite, XY_SIZE, _hX_xor_bobIdentHash.length);
            changeState(State.OB_SENT_X);
            _transport.getPumper().wantsWrite(_con, toWrite);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn(prefix() + "unexpected prepareOutbound()");
        }
    }

    /**
     *  Only call once. Caller must synch.
     *  @since 0.9.16
     */
    @Override
    protected void releaseBufs(boolean isVerified) {
        super.releaseBufs(isVerified);
        SimpleByteCache.release(_Y);
    }
}
