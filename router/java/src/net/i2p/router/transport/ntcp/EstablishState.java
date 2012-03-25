package net.i2p.router.transport.ntcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.Signature;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import net.i2p.util.Log;

/**
 * Handle the 4-phase establishment, which is as follows:
 *
 * <pre>
 *
 * Alice                   contacts                      Bob
 * =========================================================
 *
 * Message 1 (Session Request):
 *  X+(H(X) xor Bob.identHash)----------------------------->
 *
 * Message 2 (Session Created):
 *  <----------------------------------------Y+E(H(X+Y)+tsB, sk, Y[239:255])
 *
 * Message 3 (Session Confirm A):
 *  E(sz+Alice.identity+tsA+padding+S(X+Y+Bob.identHash+tsA+tsB), sk, hX_xor_Bob.identHash[16:31])--->
 *
 * Message 4 (Session Confirm B):
 *  <----------------------E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev)
 *
 *  Key:
 *
 *    X, Y: 256 byte DH keys
 *    H(): 32 byte SHA256 Hash
 *    E(data, session key, IV): AES256 Encrypt
 *    S(): 40 byte DSA Signature
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
 */
class EstablishState {
    private final RouterContext _context;
    private final Log _log;

    // bob receives (and alice sends)
    private final byte _X[];
    private final byte _hX_xor_bobIdentHash[];
    private int _aliceIdentSize;
    /** contains the decrypted aliceIndexSize + aliceIdent + tsA + padding + aliceSig */
    private ByteArrayOutputStream _sz_aliceIdent_tsA_padding_aliceSig;
    /** how long we expect _sz_aliceIdent_tsA_padding_aliceSig to be when its full */
    private int _sz_aliceIdent_tsA_padding_aliceSigSize;
    // alice receives (and bob sends)
    private byte _Y[];
    private transient byte _e_hXY_tsB[];
    /** Bob's Timestamp in seconds */
    private transient long _tsB;
    /** Alice's Timestamp in seconds */
    private transient long _tsA;
    private transient byte _e_bobSig[];

    /** previously received encrypted block (or the IV) */
    private byte _prevEncrypted[];
    /** current encrypted block we are reading */
    private byte _curEncrypted[];
    /**
     * next index in _curEncrypted to write to (equals _curEncrypted length if the block is
     * ready to decrypt)
     */
    private int _curEncryptedOffset;
    /** decryption buffer */
    private final byte _curDecrypted[];

    /** bytes received so far */
    private int _received;
    /** bytes sent so far */
    private int _sent;

    private byte _extra[];

    private final DHSessionKeyBuilder _dh;

    private final NTCPTransport _transport;
    private final NTCPConnection _con;
    private boolean _corrupt;
    /** error causing the corruption */
    private String _err;
    /** exception causing the error */
    private Exception _e;
    private boolean _verified;
    private boolean _confirmWritten;
    private boolean _failedBySkew;

    public EstablishState(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _con = con;
        _dh = _transport.getDHBuilder();
        _hX_xor_bobIdentHash = new byte[Hash.HASH_LENGTH];
        if (_con.isInbound()) {
            _X = new byte[256];
            _sz_aliceIdent_tsA_padding_aliceSig = new ByteArrayOutputStream(512);
        } else {
            _X = _dh.getMyPublicValueBytes();
            _Y = new byte[256];
            byte hx[] = ctx.sha().calculateHash(_X).getData();
            DataHelper.xor(hx, 0, con.getRemotePeer().calculateHash().getData(), 0, _hX_xor_bobIdentHash, 0, hx.length);
        }

        _prevEncrypted = new byte[16];
        _curEncrypted = new byte[16];
        _curDecrypted = new byte[16];
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
    public void receive(ByteBuffer src) {
        if (_corrupt || _verified)
            throw new IllegalStateException(prefix() + "received after completion [corrupt?" + _corrupt + " verified? " + _verified + "] on " + _con);
        if (!src.hasRemaining())
            return; // nothing to receive

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(prefix()+"receive " + src);
        if (_con.isInbound())
            receiveInbound(src);
        else
            receiveOutbound(src);
    }

    /**
     * we have written all of the data required to confirm the connection
     * establishment
     */
    public boolean confirmWritten() { return _confirmWritten; }

    public boolean getFailedBySkew() { return _failedBySkew; }

    /**
     *  we are Bob, so receive these bytes as part of an inbound connection
     *  This method receives messages 1 and 3, and sends messages 2 and 4.
     *
     *  All data must be copied out of the buffer as Reader.processRead()
     *  will return it to the pool.
     */
    private void receiveInbound(ByteBuffer src) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(prefix()+"Receiving inbound: prev received=" + _received + " src.remaining=" + src.remaining());
        while (_received < _X.length && src.hasRemaining()) {
            byte c = src.get();
            _X[_received++] = c;
            //if (_log.shouldLog(Log.DEBUG)) _log.debug("recv x" + (int)c + " received=" + _received);
            if (_received >= _X.length) {
                if (isCheckInfo(_context, _context.routerHash(), _X)) {
                    _context.statManager().addRateData("ntcp.inboundCheckConnection", 1, 0);
                    fail("Incoming connection was a check connection");
                    return;
                }
            }
        }
        while (_received < _X.length + _hX_xor_bobIdentHash.length && src.hasRemaining()) {
            int i = _received-_X.length;
            _received++;
            byte c = src.get();
            _hX_xor_bobIdentHash[i] = c;
            //if (_log.shouldLog(Log.DEBUG)) _log.debug("recv bih" + (int)c + " received=" + _received);
        }

        if (_received >= _X.length + _hX_xor_bobIdentHash.length) {
            if (_dh.getSessionKey() == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix()+"Enough data for a DH received");

                // first verify that Alice knows who she is trying to talk with and that the X
                // isn't corrupt
                Hash hX = _context.sha().calculateHash(_X);
                byte realXor[] = DataHelper.xor(hX.getData(), _context.routerHash().getData());
                if (_log.shouldLog(Log.DEBUG)) {
                    //_log.debug(prefix()+"_X = " + Base64.encode(_X));
                    _log.debug(prefix()+"hx = " + Base64.encode(hX.getData()));
                    _log.debug(prefix()+"bih=" + Base64.encode(_context.routerHash().getData()));
                    _log.debug(prefix()+"xor=" + Base64.encode(realXor));
                }
                if (!DataHelper.eq(realXor, _hX_xor_bobIdentHash)) {
                    _context.statManager().addRateData("ntcp.invalidHXxorBIH", 1, 0);
                    fail("Invalid hX_xor");
                    return;
                }
                try {
                    // ok, they're actually trying to talk to us, and we got their (unauthenticated) X
                    _dh.setPeerPublicValue(_X);
                    _dh.getSessionKey(); // force the calc
                    System.arraycopy(realXor, 16, _prevEncrypted, 0, _prevEncrypted.length);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(prefix()+"DH session key calculated (" + _dh.getSessionKey().toBase64() + ")");

                    // now prepare our response: Y+E(H(X+Y)+tsB+padding, sk, Y[239:255])
                    _Y = _dh.getMyPublicValueBytes();
                    byte xy[] = new byte[_X.length+_Y.length];
                    System.arraycopy(_X, 0, xy, 0, _X.length);
                    System.arraycopy(_Y, 0, xy, _X.length, _Y.length);
                    Hash hxy = _context.sha().calculateHash(xy);
                    _tsB = (_context.clock().now() + 500) / 1000l; // our (Bob's) timestamp in seconds
                    byte toEncrypt[] = new byte[hxy.getData().length + (4 + 12)];
                    System.arraycopy(hxy.getData(), 0, toEncrypt, 0, hxy.getData().length);
                    byte tsB[] = DataHelper.toLong(4, _tsB);
                    System.arraycopy(tsB, 0, toEncrypt, hxy.getData().length, tsB.length);
                    //DataHelper.toLong(toEncrypt, hxy.getData().length, 4, _tsB);
                    _context.random().nextBytes(toEncrypt, hxy.getData().length + 4, 12);
                    if (_log.shouldLog(Log.DEBUG)) {
                        //_log.debug(prefix()+"Y="+Base64.encode(_Y));
                        //_log.debug(prefix()+"x+y="+Base64.encode(xy));
                        _log.debug(prefix()+"h(x+y)="+Base64.encode(hxy.getData()));
                        _log.debug(prefix()+"tsb="+Base64.encode(tsB));
                        _log.debug(prefix()+"unencrypted H(X+Y)+tsB+padding: " + Base64.encode(toEncrypt));
                        _log.debug(prefix()+"encryption iv= " + Base64.encode(_Y, _Y.length-16, 16));
                        _log.debug(prefix()+"encryption key= " + _dh.getSessionKey().toBase64());
                    }
                    _e_hXY_tsB = new byte[toEncrypt.length];
                    _context.aes().encrypt(toEncrypt, 0, _e_hXY_tsB, 0, _dh.getSessionKey(), _Y, _Y.length-16, toEncrypt.length);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(prefix()+"encrypted H(X+Y)+tsB+padding: " + Base64.encode(_e_hXY_tsB));
                    byte write[] = new byte[_Y.length + _e_hXY_tsB.length];
                    System.arraycopy(_Y, 0, write, 0, _Y.length);
                    System.arraycopy(_e_hXY_tsB, 0, write, _Y.length, _e_hXY_tsB.length);

                    // ok, now that is prepared, we want to actually send it, so make sure we are up for writing
                    _transport.getPumper().wantsWrite(_con, write);
                    if (!src.hasRemaining()) return;
                } catch (DHSessionKeyBuilder.InvalidPublicParameterException e) {
                    _context.statManager().addRateData("ntcp.invalidDH", 1, 0);
                    fail("Invalid X", e);
                    return;
                }
            }

            // ok, we are onto the encrypted area
            while (src.hasRemaining() && !_corrupt) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix()+"Encrypted bytes available (" + src.hasRemaining() + ")");
                while (_curEncryptedOffset < _curEncrypted.length && src.hasRemaining()) {
                    _curEncrypted[_curEncryptedOffset++] = src.get();
                    _received++;
                }
                if (_curEncryptedOffset >= _curEncrypted.length) {
                    _context.aes().decrypt(_curEncrypted, 0, _curDecrypted, 0, _dh.getSessionKey(), _prevEncrypted, 0, _curEncrypted.length);
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug(prefix()+"full block read and decrypted: " + Base64.encode(_curDecrypted));

                    byte swap[] = new byte[16];
                    _prevEncrypted = _curEncrypted;
                    _curEncrypted = swap;
                    _curEncryptedOffset = 0;

                    if (_aliceIdentSize <= 0) { // we are on the first decrypted block
                        _aliceIdentSize = (int)DataHelper.fromLong(_curDecrypted, 0, 2);
                        _sz_aliceIdent_tsA_padding_aliceSigSize = 2 + _aliceIdentSize + 4 + Signature.SIGNATURE_BYTES;
                        int rem = (_sz_aliceIdent_tsA_padding_aliceSigSize % 16);
                        int padding = 0;
                        if (rem > 0)
                            padding = 16-rem;
                        _sz_aliceIdent_tsA_padding_aliceSigSize += padding;
                        try {
                            _sz_aliceIdent_tsA_padding_aliceSig.write(_curDecrypted);
                        } catch (IOException ioe) {
                            if (_log.shouldLog(Log.ERROR)) _log.error(prefix()+"Error writing to the baos?", ioe);
                        }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(prefix()+"alice ident size decrypted as " + _aliceIdentSize + ", making the padding at " + padding + " and total size at " + _sz_aliceIdent_tsA_padding_aliceSigSize);
                    } else {
                        // subsequent block...
                        try {
                            _sz_aliceIdent_tsA_padding_aliceSig.write(_curDecrypted);
                        } catch (IOException ioe) {
                            if (_log.shouldLog(Log.ERROR)) _log.error(prefix()+"Error writing to the baos?", ioe);
                        }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(prefix()+"subsequent block decrypted (" + _sz_aliceIdent_tsA_padding_aliceSig.size() + ")");

                        if (_sz_aliceIdent_tsA_padding_aliceSig.size() >= _sz_aliceIdent_tsA_padding_aliceSigSize) {
                            verifyInbound();
                            if (!_corrupt && _verified && src.hasRemaining())
                                prepareExtra(src);
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug(prefix()+"verifying size (sz=" + _sz_aliceIdent_tsA_padding_aliceSig.size()
                                           + " expected=" + _sz_aliceIdent_tsA_padding_aliceSigSize
                                           + " corrupt=" + _corrupt
                                           + " verified=" + _verified + " extra=" + (_extra != null ? _extra.length : 0) + ")");
                            return;
                        }
                    }
                } else {
                    // no more bytes available in the buffer, and only a partial
                    // block was read, so we can't decrypt it.
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(prefix()+"end of available data with only a partial block read (" + _curEncryptedOffset + ", " + _received + ")");
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(prefix()+"done with the data, not yet complete or corrupt");
        }
    }

    /**
     *  We are Alice, so receive these bytes as part of an outbound connection.
     *  This method receives messages 2 and 4, and sends message 3.
     *
     *  All data must be copied out of the buffer as Reader.processRead()
     *  will return it to the pool.
     */
    private void receiveOutbound(ByteBuffer src) {
        if (_log.shouldLog(Log.DEBUG)) _log.debug(prefix()+"Receive outbound " + src + " received=" + _received);

        // recv Y+E(H(X+Y)+tsB, sk, Y[239:255])
        while (_received < _Y.length && src.hasRemaining()) {
            byte c = src.get();
            _Y[_received++] = c;
            //if (_log.shouldLog(Log.DEBUG)) _log.debug("recv x" + (int)c + " received=" + _received);
            if (_received >= _Y.length) {
                try {
                    _dh.setPeerPublicValue(_Y);
                    _dh.getSessionKey(); // force the calc
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(prefix()+"DH session key calculated (" + _dh.getSessionKey().toBase64() + ")");
                    _e_hXY_tsB = new byte[Hash.HASH_LENGTH+4+12];
                } catch (DHSessionKeyBuilder.InvalidPublicParameterException e) {
                    _context.statManager().addRateData("ntcp.invalidDH", 1, 0);
                    fail("Invalid X", e);
                    return;
                }
            }
        }
        if (_e_hXY_tsB == null) return; // !src.hasRemaining

        while (_received < _Y.length + _e_hXY_tsB.length && src.hasRemaining()) {
            int i = _received-_Y.length;
            _received++;
            byte c = src.get();
            _e_hXY_tsB[i] = c;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(prefix() + "recv _e_hXY_tsB " + (int)c + " received=" + _received);
            if (i+1 >= _e_hXY_tsB.length) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug(prefix() + "received _e_hXY_tsB fully");
                byte hXY_tsB[] = new byte[_e_hXY_tsB.length];
                _context.aes().decrypt(_e_hXY_tsB, 0, hXY_tsB, 0, _dh.getSessionKey(), _Y, _Y.length-16, _e_hXY_tsB.length);
                byte XY[] = new byte[_X.length + _Y.length];
                System.arraycopy(_X, 0, XY, 0, _X.length);
                System.arraycopy(_Y, 0, XY, _X.length, _Y.length);
                Hash h = _context.sha().calculateHash(XY);
                if (_log.shouldLog(Log.DEBUG)) _log.debug(prefix() + "h(XY)=" + h.toBase64());
                if (!DataHelper.eq(h.getData(), 0, hXY_tsB, 0, Hash.HASH_LENGTH)) {
                    _context.statManager().addRateData("ntcp.invalidHXY", 1, 0);
                    fail("Invalid H(X+Y) - mitm attack attempted?");
                    return;
                }
                _tsB = DataHelper.fromLong(hXY_tsB, Hash.HASH_LENGTH, 4); // their (Bob's) timestamp in seconds
                _tsA = (_context.clock().now() + 500) / 1000; // our (Alice's) timestamp in seconds
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix()+"h(X+Y) is correct, tsA-tsB=" + (_tsA-_tsB));

                // the skew is not authenticated yet, but it is certainly fatal to
                // the establishment, so fail hard if appropriate
                long diff = 1000*Math.abs(_tsA-_tsB);
                if (!_context.clock().getUpdatedSuccessfully()) {
                    // Adjust the clock one time in desperation
                    _context.clock().setOffset(1000 * (_tsB - _tsA), true);
                    _tsA = _tsB;
                    if (diff != 0)
                        _log.logAlways(Log.WARN, "NTP failure, NTCP adjusting clock by " + DataHelper.formatDuration(diff));
                } else if (diff >= Router.CLOCK_FUDGE_FACTOR) {
                    _context.statManager().addRateData("ntcp.invalidOutboundSkew", diff, 0);
                    _transport.markReachable(_con.getRemotePeer().calculateHash(), false);
                    // Only shitlist if we know what time it is
                    _context.shitlist().shitlistRouter(DataHelper.formatDuration(diff),
                                                       _con.getRemotePeer().calculateHash(),
                                                       _x("Excessive clock skew: {0}"));
                    _transport.setLastBadSkew(_tsA- _tsB);
                    fail("Clocks too skewed (" + diff + " ms)", null, true);
                    return;
                } else if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug(prefix()+"Clock skew: " + diff + " ms");
                }

                // now prepare and send our response
                // send E(#+Alice.identity+tsA+padding+S(X+Y+Bob.identHash+tsA+tsB), sk, hX_xor_Bob.identHash[16:31])
                int sigSize = _X.length+_Y.length+Hash.HASH_LENGTH+4+4;//+12;
                byte preSign[] = new byte[sigSize];
                System.arraycopy(_X, 0, preSign, 0, _X.length);
                System.arraycopy(_Y, 0, preSign, _X.length, _Y.length);
                System.arraycopy(_con.getRemotePeer().calculateHash().getData(), 0, preSign, _X.length+_Y.length, Hash.HASH_LENGTH);
                DataHelper.toLong(preSign, _X.length+_Y.length+Hash.HASH_LENGTH, 4, _tsA);
                DataHelper.toLong(preSign, _X.length+_Y.length+Hash.HASH_LENGTH+4, 4, _tsB);
                // hXY_tsB has 12 bytes of padding (size=48, tsB=4 + hXY=32)
                //System.arraycopy(hXY_tsB, hXY_tsB.length-12, preSign, _X.length+_Y.length+Hash.HASH_LENGTH+4+4, 12);
                //byte sigPad[] = new byte[padSig];
                //_context.random().nextBytes(sigPad);
                //System.arraycopy(sigPad, 0, preSign, _X.length+_Y.length+Hash.HASH_LENGTH+4+4, padSig);
                Signature sig = _context.dsa().sign(preSign, _context.keyManager().getSigningPrivateKey());

                //if (_log.shouldLog(Log.DEBUG)) {
                //    _log.debug(prefix()+"signing " + Base64.encode(preSign));
                //}

                byte ident[] = _context.router().getRouterInfo().getIdentity().toByteArray();
                int min = 2+ident.length+4+Signature.SIGNATURE_BYTES;
                int rem = min % 16;
                int padding = 0;
                if (rem > 0)
                    padding = 16 - rem;
                byte preEncrypt[] = new byte[min+padding];
                DataHelper.toLong(preEncrypt, 0, 2, ident.length);
                System.arraycopy(ident, 0, preEncrypt, 2, ident.length);
                DataHelper.toLong(preEncrypt, 2+ident.length, 4, _tsA);
                if (padding > 0)
                    _context.random().nextBytes(preEncrypt, 2 + ident.length + 4, padding);
                System.arraycopy(sig.getData(), 0, preEncrypt, 2+ident.length+4+padding, Signature.SIGNATURE_BYTES);

                _prevEncrypted = new byte[preEncrypt.length];
                _context.aes().encrypt(preEncrypt, 0, _prevEncrypted, 0, _dh.getSessionKey(), _hX_xor_bobIdentHash, _hX_xor_bobIdentHash.length-16, preEncrypt.length);

                if (_log.shouldLog(Log.DEBUG)) {
                    //_log.debug(prefix() + "unencrypted response to Bob: " + Base64.encode(preEncrypt));
                    //_log.debug(prefix() + "encrypted response to Bob: " + Base64.encode(_prevEncrypted));
                }
                // send 'er off (when the bw limiter says, etc)
                _transport.getPumper().wantsWrite(_con, _prevEncrypted);
            }
        }
        if (_received >= _Y.length + _e_hXY_tsB.length && src.hasRemaining()) {
            // we are receiving their confirmation

            // recv E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev)
            int off = 0;
            if (_e_bobSig == null) {
                _e_bobSig = new byte[48];
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix() + "receiving E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev) (remaining? " + src.hasRemaining() + ")");
            } else {
                off = _received - _Y.length - _e_hXY_tsB.length;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix() + "continuing to receive E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev) (remaining? " + src.hasRemaining() + " off=" + off + " recv=" + _received + ")");
            }
            while (src.hasRemaining() && off < _e_bobSig.length) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug(prefix()+"recv bobSig received=" + _received);
                _e_bobSig[off++] = src.get();
                _received++;

                if (off >= _e_bobSig.length) {
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug(prefix() + "received E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev): " + Base64.encode(_e_bobSig));
                    byte bobSig[] = new byte[_e_bobSig.length];
                    _context.aes().decrypt(_e_bobSig, 0, bobSig, 0, _dh.getSessionKey(), _e_hXY_tsB, _e_hXY_tsB.length-16, _e_bobSig.length);
                    // ignore the padding
                    byte bobSigData[] = new byte[Signature.SIGNATURE_BYTES];
                    System.arraycopy(bobSig, 0, bobSigData, 0, Signature.SIGNATURE_BYTES);
                    Signature sig = new Signature(bobSigData);

                    byte toVerify[] = new byte[_X.length+_Y.length+Hash.HASH_LENGTH+4+4];
                    int voff = 0;
                    System.arraycopy(_X, 0, toVerify, voff, _X.length); voff += _X.length;
                    System.arraycopy(_Y, 0, toVerify, voff, _Y.length); voff += _Y.length;
                    System.arraycopy(_context.routerHash().getData(), 0, toVerify, voff, Hash.HASH_LENGTH); voff += Hash.HASH_LENGTH;
                    DataHelper.toLong(toVerify, voff, 4, _tsA); voff += 4;
                    DataHelper.toLong(toVerify, voff, 4, _tsB); voff += 4;

                    _verified = _context.dsa().verifySignature(sig, toVerify, _con.getRemotePeer().getSigningPublicKey());
                    if (!_verified) {
                        _context.statManager().addRateData("ntcp.invalidSignature", 1, 0);
                        fail("Signature was invalid - attempt to spoof " + _con.getRemotePeer().calculateHash().toBase64() + "?");
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(prefix() + "signature verified from Bob.  done!");
                        prepareExtra(src);
                        byte nextWriteIV[] = new byte[16];
                        System.arraycopy(_prevEncrypted, _prevEncrypted.length-16, nextWriteIV, 0, 16);
                        byte nextReadIV[] = new byte[16];
                        System.arraycopy(_e_bobSig, _e_bobSig.length-16, nextReadIV, 0, nextReadIV.length);
                        _con.finishOutboundEstablishment(_dh.getSessionKey(), (_tsA-_tsB), nextWriteIV, nextReadIV); // skew in seconds
                        // if socket gets closed this will be null - prevent NPE
                        InetAddress ia = _con.getChannel().socket().getInetAddress();
                        if (ia != null)
                            _transport.setIP(_con.getRemotePeer().calculateHash(), ia.getAddress());
                    }
                    return;
                }
            }
        }
    }

    /** did the handshake fail for some reason? */
    public boolean isCorrupt() { return _err != null; }
    /** @return is the handshake complete and valid? */
    public boolean isComplete() { return _verified; }

    /**
     * We are Alice.
     * We are establishing an outbound connection, so prepare ourselves by
     * queueing up the write of the first part of the handshake
     * This method sends message #1 to Bob.
     */
    public void prepareOutbound() {
        if (_received <= 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(prefix() + "write out the first part of our handshake");
            byte toWrite[] = new byte[_X.length + _hX_xor_bobIdentHash.length];
            System.arraycopy(_X, 0, toWrite, 0, _X.length);
            System.arraycopy(_hX_xor_bobIdentHash, 0, toWrite, _X.length, _hX_xor_bobIdentHash.length);
            _transport.getPumper().wantsWrite(_con, toWrite);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(prefix()+"prepare outbound with received=" + _received);
        }
    }

    /**
     * We are Bob. Verify message #3 from Alice, then send message #4 to Alice.
     *
     * Make sure the signatures are correct, and if they are, update the
     * NIOConnection with the session key / peer ident / clock skew / iv.
     * The NIOConnection itself is responsible for registering with the
     * transport
     */
    private void verifyInbound() {
        if (_corrupt) return;
        byte b[] = _sz_aliceIdent_tsA_padding_aliceSig.toByteArray();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(prefix()+"decrypted sz(etc) data: " + Base64.encode(b));

        try {
            RouterIdentity alice = new RouterIdentity();
            int sz = (int)DataHelper.fromLong(b, 0, 2); // TO-DO: Hey zzz... Throws an NPE for me... see below, for my "quick fix", need to find out the real reason
            if ( (sz <= 0) || (sz > b.length-2-4-Signature.SIGNATURE_BYTES) ) {
                _context.statManager().addRateData("ntcp.invalidInboundSize", sz, 0);
                fail("size is invalid", new Exception("size is " + sz));
                return;
            }
            byte aliceData[] = new byte[sz];
            System.arraycopy(b, 2, aliceData, 0, sz);
            alice.fromByteArray(aliceData);
            long tsA = DataHelper.fromLong(b, 2+sz, 4);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(768);
            baos.write(_X);
            baos.write(_Y);
            baos.write(_context.routerHash().getData());
            baos.write(DataHelper.toLong(4, tsA));
            baos.write(DataHelper.toLong(4, _tsB));
            //baos.write(b, 2+sz+4, b.length-2-sz-4-Signature.SIGNATURE_BYTES);

            byte toVerify[] = baos.toByteArray();
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(prefix()+"checking " + Base64.encode(toVerify, 0, 16));
                //_log.debug(prefix()+"check pad " + Base64.encode(b, 2+sz+4, 12));
            }

            byte s[] = new byte[Signature.SIGNATURE_BYTES];
            System.arraycopy(b, b.length-s.length, s, 0, s.length);
            Signature sig = new Signature(s);
            _verified = _context.dsa().verifySignature(sig, toVerify, alice.getSigningPublicKey());
            if (_verified) {
				// get inet-addr
				InetAddress addr = this._con.getChannel().socket().getInetAddress();
                byte[] ip = (addr == null) ? null : addr.getAddress();
                if (_context.shitlist().isShitlistedForever(alice.calculateHash())) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping inbound connection from permanently shitlisted peer: " + alice.calculateHash().toBase64());
                    // So next time we will not accept the con from this IP,
                    // rather than doing the whole handshake
					if(ip != null)
						_context.blocklist().add(ip);
                    fail("Peer is shitlisted forever: " + alice.calculateHash().toBase64());
                    return;
                }
				if(ip != null)
					_transport.setIP(alice.calculateHash(), ip);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix() + "verification successful for " + _con);

                long diff = 1000*Math.abs(tsA-_tsB);
                if (!_context.clock().getUpdatedSuccessfully()) {
                    // Adjust the clock one time in desperation
                    // This isn't very likely, outbound will do it first
                    _context.clock().setOffset(1000 * (_tsB - tsA), true);
                    tsA = _tsB;
                    if (diff != 0)
                        _log.logAlways(Log.WARN, "NTP failure, NTCP adjusting clock by " + DataHelper.formatDuration(diff));
                } else if (diff >= Router.CLOCK_FUDGE_FACTOR) {
                    _context.statManager().addRateData("ntcp.invalidInboundSkew", diff, 0);
                    _transport.markReachable(alice.calculateHash(), true);
                    // Only shitlist if we know what time it is
                    _context.shitlist().shitlistRouter(DataHelper.formatDuration(diff),
                                                       alice.calculateHash(),
                                                       _x("Excessive clock skew: {0}"));
                    _transport.setLastBadSkew(tsA- _tsB);
                    fail("Clocks too skewed (" + diff + " ms)", null, true);
                    return;
                } else if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug(prefix()+"Clock skew: " + diff + " ms");
                }

                sendInboundConfirm(alice, tsA);
                _con.setRemotePeer(alice);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(prefix()+"e_bobSig is " + _e_bobSig.length + " bytes long");
                byte iv[] = new byte[16];
                System.arraycopy(_e_bobSig, _e_bobSig.length-16, iv, 0, 16);
                _con.finishInboundEstablishment(_dh.getSessionKey(), (tsA-_tsB), iv, _prevEncrypted); // skew in seconds
                if (_log.shouldLog(Log.INFO))
                    _log.info(prefix()+"Verified remote peer as " + alice.calculateHash().toBase64());
            } else {
                _context.statManager().addRateData("ntcp.invalidInboundSignature", 1, 0);
                fail("Peer verification failed - spoof of " + alice.calculateHash().toBase64() + "?");
            }
        } catch (IOException ioe) {
            _context.statManager().addRateData("ntcp.invalidInboundIOE", 1, 0);
            fail("Error verifying peer", ioe);
        } catch (DataFormatException dfe) {
            _context.statManager().addRateData("ntcp.invalidInboundDFE", 1, 0);
            fail("Error verifying peer", dfe);
        } catch(NullPointerException npe) {
            fail("Error verifying peer", npe); // TO-DO: zzz This is that quick-fix. -- Sponge
        }
    }

    /**
     *  We are Bob. Send message #4 to Alice.
     */
    private void sendInboundConfirm(RouterIdentity alice, long tsA) {
        // send Alice E(S(X+Y+Alice.identHash+tsA+tsB), sk, prev)
        byte toSign[] = new byte[256+256+32+4+4];
        int off = 0;
        System.arraycopy(_X, 0, toSign, off, 256); off += 256;
        System.arraycopy(_Y, 0, toSign, off, 256); off += 256;
        Hash h = alice.calculateHash();
        System.arraycopy(h.getData(), 0, toSign, off, 32); off += 32;
        DataHelper.toLong(toSign, off, 4, tsA); off += 4;
        DataHelper.toLong(toSign, off, 4, _tsB); off += 4;

        Signature sig = _context.dsa().sign(toSign, _context.keyManager().getSigningPrivateKey());
        byte preSig[] = new byte[Signature.SIGNATURE_BYTES+8];
        System.arraycopy(sig.getData(), 0, preSig, 0, Signature.SIGNATURE_BYTES);
        _context.random().nextBytes(preSig, Signature.SIGNATURE_BYTES, 8);
        _e_bobSig = new byte[preSig.length];
        _context.aes().encrypt(preSig, 0, _e_bobSig, 0, _dh.getSessionKey(), _e_hXY_tsB, _e_hXY_tsB.length-16, _e_bobSig.length);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(prefix() + "Sending encrypted inbound confirmation");
        _transport.getPumper().wantsWrite(_con, _e_bobSig);
    }

    /** Anything left over in the byte buffer after verification is extra
     *
     *  All data must be copied out of the buffer as Reader.processRead()
     *  will return it to the pool.
     */
    private void prepareExtra(ByteBuffer buf) {
        int remaining = buf.remaining();
        if (remaining > 0) {
            _extra = new byte[remaining];
            buf.get(_extra);
            _received += remaining;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(prefix() + "prepare extra " + remaining + " (total received: " + _received + ")");
    }

    /**
     * if complete, this will contain any bytes received as part of the
     * handshake that were after the actual handshake.  This may return null.
     */
    public byte[] getExtraBytes() { return _extra; }

    private void fail(String reason) { fail(reason, null); }
    private void fail(String reason, Exception e) { fail(reason, e, false); }
    private void fail(String reason, Exception e, boolean bySkew) {
        _corrupt = true;
        _failedBySkew = bySkew;
        _err = reason;
        _e = e;
        if (_log.shouldLog(Log.WARN))
            _log.warn(prefix()+"Failed to establish: " + _err, e);
    }

    public String getError() { return _err; }
    public Exception getException() { return _e; }

    private String prefix() { return toString(); }
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("est").append(System.identityHashCode(this));
        if (_con.isInbound()) buf.append(" inbound");
        else buf.append(" outbound");
        if (_corrupt) buf.append(" corrupt");
        if (_verified) buf.append(" verified");
        if (_con.isEstablished()) buf.append(" established");
        buf.append(": ");
        return buf.toString();
    }

    /**
     * a check info connection will receive 256 bytes containing:
     * - 32 bytes of uninterpreted, ignored data
     * - 1 byte size
     * - that many bytes making up the local router's IP address (as reached by the remote side)
     * - 2 byte port number that the local router was reached on
     * - 4 byte i2p network time as known by the remote side (seconds since the epoch)
     * - uninterpreted padding data, up to byte 223
     * - xor of the local router's identity hash and the SHA256 of bytes 32 through bytes 223
     *
     * @return should always be false since nobody ever sends a check info message
     *
     */
    private static boolean isCheckInfo(I2PAppContext ctx, Hash us, byte first256[]) {
        Log log = ctx.logManager().getLog(EstablishState.class);
        int off = 32; // ignore the first 32 bytes
        Hash h = ctx.sha().calculateHash(first256, off, first256.length-32-off);
        byte xor[] = DataHelper.xor(h.getData(), us.getData());
        if (log.shouldLog(Log.DEBUG))
            log.debug("check hash: " + h.toBase64() + " xor: " + Base64.encode(xor));
        if (DataHelper.eq(xor, 0, first256, first256.length-32, 32)) {
            // ok, data is as expected
            // parse our IP/port/etc out of the first256
            int ipSize = (int)DataHelper.fromLong(first256, off, 1);
            off++;
            byte ip[] = new byte[ipSize];
            System.arraycopy(first256, off, ip, 0, ipSize);
            try {
                InetAddress ourIP = InetAddress.getByAddress(ip);
                off += ipSize;
                int port = (int)DataHelper.fromLong(first256, off, 2);
                off += 2;
                long now = DataHelper.fromLong(first256, off, 4);
                off += 4;
                long skewSeconds = (ctx.clock().now()/1000)-now;
                if (log.shouldLog(Log.INFO))
                    log.info("Check info received: our IP: " + ourIP + " our port: " + port
                             + " skew: " + skewSeconds + " s");
            } catch (UnknownHostException uhe) {
                // ipSize is invalid
                if (log.shouldLog(Log.WARN))
                    log.warn("Invalid IP received on check connection (size: " + ipSize + ")");
            }
            return true;
        } else {
            if (log.shouldLog(Log.DEBUG))
                log.debug("Not a checkInfo connection");
            return false;
        }
    }

    /** @deprecated unused */
/*********
    public static void checkHost(String args[]) {
        if (args.length != 3) {
            System.err.println("Usage: EstablishState ipOrHostname portNum peerHashBase64");
            return;
        }
        try {
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            String host = args[0];
            int port = Integer.parseInt(args[1]);
            byte peer[] = Base64.decode(args[2]);
            Socket s = new Socket(host, port);
            OutputStream out = s.getOutputStream();
            byte toSend[] = new byte[256];
            ctx.random().nextBytes(toSend);
            int off = 32;
            byte ip[] = s.getInetAddress().getAddress();
            DataHelper.toLong(toSend, off, 1, ip.length);
            off++;
            System.arraycopy(ip, 0, toSend, off, ip.length);
            off += ip.length;
            DataHelper.toLong(toSend, off, 2, port);
            off += 2;
            long now = ctx.clock().now()/1000;
            DataHelper.toLong(toSend, off, 4, now);
            off += 4;
            Hash h = ctx.sha().calculateHash(toSend, 32, toSend.length-32-32);
            DataHelper.xor(peer, 0, h.getData(), 0, toSend, toSend.length-32, peer.length);
            System.out.println("check hash: " + h.toBase64());

            out.write(toSend);
            out.flush();
            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            s.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
*******/

/*******
    public static void main(String args[]) {
        if (args.length == 3) {
            checkHost(args);
            return;
        }
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        try {
            java.net.Socket s = new java.net.Socket("localhost", 9094);
            OutputStream out = s.getOutputStream();
            DHSessionKeyBuilder dh = new DHSessionKeyBuilder();
            byte X[] = dh.getMyPublicValueBytes();

            //  SEND X+(H(X) xor Bob.identHash)----------------------------->
            out.write(X);
            System.out.println("sent X =" + Base64.encode(X));
            byte bih[] = Base64.decode("HuRdDx9t-RaZfYkYvacRwP~6s9mvbdkYzIMrpUCsZIo=");
            System.out.println("bih = " + Base64.encode(bih));
            Hash hx = ctx.sha().calculateHash(X);
            System.out.println("hx  = " + Base64.encode(hx.getData()));
            byte hx_xor_bih[] = DataHelper.xor(bih, hx.getData());
            System.out.println("xor = " + Base64.encode(hx_xor_bih));
            out.write(hx_xor_bih);
            out.flush();
            //  DONE SENDING X+(H(X) xor Bob.identHash)----------------------------->

            //  NOW READ Y+E(H(X+Y)+tsB+padding, sk, Y[239:255])
            InputStream in = s.getInputStream();
            byte toRead[] = new byte[256+(32+4+12)];
            int read = 0;
            while (read < toRead.length) {
                int r = in.read(toRead, read, toRead.length-read);
                if (r == -1)
                    throw new EOFException("eof at read=" + read);
                read += r;
            }
            byte Y[] = new byte[256];
            System.arraycopy(toRead, 0, Y, 0, Y.length);
            dh.setPeerPublicValue(Y);
            byte decrypted[] = new byte[(32+4+12)];
            ctx.aes().decrypt(toRead, Y.length, decrypted, 0, dh.getSessionKey(), Y, Y.length-16, decrypted.length);
            //display y, encrypted, decrypted, hx+y, tsb, padding
            //unencrypted H(X+Y)+tsB+padding: bSJIv1ynFw9MhIqbObOpCqeZxtFvKEx-ilcsZQ31zYNEnVXyHCZagLbdQYRmd1oq
            System.out.println("dh session key: " + dh.getSessionKey().toBase64());
            System.out.println("decryption iv: " + Base64.encode(Y, Y.length-16, 16));
            System.out.println("Y = " + Base64.encode(Y));
            byte xy[] = new byte[512];
            System.arraycopy(X, 0, xy, 0, X.length);
            System.arraycopy(Y, 0, xy, X.length, Y.length);
            System.out.println("h(x+y): " + ctx.sha().calculateHash(xy).toBase64());
            System.out.println("encrypted H(X+Y)+tsB+padding: " + Base64.encode(toRead, Y.length, toRead.length-Y.length));
            System.out.println("unencrypted H(X+Y)+tsB+padding: " + Base64.encode(decrypted));
            long tsB = DataHelper.fromLong(decrypted, 32, 4);

            //try { Thread.sleep(40*1000); } catch (InterruptedException ie) {}

            RouterIdentity alice = new RouterIdentity();
            Object k[] = ctx.keyGenerator().generatePKIKeypair();
            PublicKey pub = (PublicKey)k[0];
            PrivateKey priv = (PrivateKey)k[1];
            k = ctx.keyGenerator().generateSigningKeypair();
            SigningPublicKey spub = (SigningPublicKey)k[0];
            SigningPrivateKey spriv = (SigningPrivateKey)k[1];
            alice.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
            alice.setPublicKey(pub);
            alice.setSigningPublicKey(spub);

            //  SEND E(#+Alice.identity+tsA+padding+S(X+Y+Bob.identHash+tsA+tsB+padding), sk, hX_xor_Bob.identHash[16:31])--->

            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            byte aliceb[] = alice.toByteArray();
            long tsA = ctx.clock().now()/1000l;
            baos.write(DataHelper.toLong(2, aliceb.length));
            baos.write(aliceb);
            baos.write(DataHelper.toLong(4, tsA));

            int base = baos.size() + Signature.SIGNATURE_BYTES;
            int rem = base % 16;
            int padding = 0;
            if (rem > 0)
                padding = 16 - rem;
            byte pad[] = new byte[padding];
            ctx.random().nextBytes(pad);
            baos.write(pad);
            base += padding;

            ByteArrayOutputStream sbaos = new ByteArrayOutputStream(512);
            sbaos.write(X);
            sbaos.write(Y);
            sbaos.write(bih);
            sbaos.write(DataHelper.toLong(4, tsA));
            sbaos.write(DataHelper.toLong(4, tsB));
            //sbaos.write(pad);
            Signature sig = ctx.dsa().sign(sbaos.toByteArray(), spriv);
            baos.write(sig.toByteArray());

            byte unencrypted[] = baos.toByteArray();
            byte toWrite[] = new byte[unencrypted.length];
            System.out.println("unencrypted.length = " + unencrypted.length + " alice.size = " + aliceb.length + " padding = " + padding + " base = " + base);
            ctx.aes().encrypt(unencrypted, 0, toWrite, 0, dh.getSessionKey(), hx_xor_bih, 16, unencrypted.length);

            out.write(toWrite);
            out.flush();

            System.out.println("unencrypted: " + Base64.encode(unencrypted));
            System.out.println("encrypted: " + Base64.encode(toWrite));
            System.out.println("Local peer: " + alice.calculateHash().toBase64());

            // now check bob's signature

            SigningPublicKey bobPubKey = null;
            try {
                RouterInfo info = new RouterInfo();
                info.readBytes(new FileInputStream("/home/jrandom/routers/router1/netDb/routerInfo-HuRdDx9t-RaZfYkYvacRwP~6s9mvbdkYzIMrpUCsZIo=.dat"));
                bobPubKey = info.getIdentity().getSigningPublicKey();
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }

            System.out.println("Reading in bob's sig");

            byte bobRead[] = new byte[48];
            read = 0;
            while (read < bobRead.length) {
                int r = in.read(bobRead, read, bobRead.length-read);
                if (r == -1)
                    throw new EOFException("eof at read=" + read);
                read += r;
            }

            // bob should have sent E(S(X+Y+Alice.identHash+tsA+tsB)+padding, sk, prev)
            byte preSig[] = new byte[Signature.SIGNATURE_BYTES+8];
            ctx.aes().decrypt(bobRead, 0, preSig, 0, dh.getSessionKey(), toRead, toRead.length-16, preSig.length);
            byte bobSigData[] = new byte[Signature.SIGNATURE_BYTES];
            System.arraycopy(preSig, 0, bobSigData, 0, Signature.SIGNATURE_BYTES); // ignore the padding
            System.out.println("Bob's sig: " + Base64.encode(bobSigData));

            byte signed[] = new byte[256+256+32+4+4];
            int off = 0;
            System.arraycopy(X, 0, signed, off, 256); off += 256;
            System.arraycopy(Y, 0, signed, off, 256); off += 256;
            Hash h = alice.calculateHash();
            System.arraycopy(h.getData(), 0, signed, off, 32); off += 32;
            DataHelper.toLong(signed, off, 4, tsA); off += 4;
            DataHelper.toLong(signed, off, 4, tsB); off += 4;

            Signature bobSig = new Signature(bobSigData);
            boolean ok = ctx.dsa().verifySignature(bobSig, signed, bobPubKey);

            System.out.println("bob's sig matches? " + ok);

            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
            byte fakeI2NPbuf[] = new byte[128];
            ctx.random().nextBytes(fakeI2NPbuf);
            out.write(fakeI2NPbuf);
            out.flush();

            try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
            s.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
*******/

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {
        return s;
    }

}
