package net.i2p.data.i2np;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Properties;

import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyFactory;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;

/**
 * As of 0.9.48, supports two formats.
 * The original 222-byte ElGamal format and the new 464-byte ECIES format.
 * See proposal 152 for details on the new format.
 *
 * None of the readXXX() calls are cached. For efficiency,
 * they should only be called once.
 *
 * Original ElGamal format:
 *
 * Holds the unencrypted 222-byte tunnel request record,
 * with a constructor for ElGamal decryption and a method for ElGamal encryption.
 * Iterative AES encryption/decryption is done elsewhere.
 *
 * Cleartext:
 * <pre>
 *   bytes     0-3: tunnel ID to receive messages as
 *   bytes    4-35: local router identity hash (Unused and no accessor here)
 *   bytes   36-39: next tunnel ID
 *   bytes   40-71: next router identity hash
 *   bytes  72-103: AES-256 tunnel layer key
 *   bytes 104-135: AES-256 tunnel IV key
 *   bytes 136-167: AES-256 reply key
 *   bytes 168-183: reply IV
 *   byte      184: flags
 *   bytes 185-188: request time (in hours since the epoch)
 *   bytes 189-192: next message ID
 *   bytes 193-221: uninterpreted / random padding
 * </pre>
 *
 * Encrypted:
 * <pre>
 *   bytes    0-15: First 16 bytes of router hash
 *   bytes  16-527: ElGamal encrypted block (discarding zero bytes at elg[0] and elg[257])
 * </pre>
 *
 * New ECIES format, ref: proposal 152:
 *
 * Holds the unencrypted 464-byte tunnel request record,
 * with a constructor for ECIES decryption and a method for ECIES encryption.
 * Iterative AES encryption/decryption is done elsewhere.
 *
 * Cleartext:
 * <pre>
 *   bytes     0-3: tunnel ID to receive messages as, nonzero
 *   bytes     4-7: next tunnel ID, nonzero
 *   bytes    8-39: next router identity hash
 *   bytes   40-71: AES-256 tunnel layer key
 *   bytes  72-103: AES-256 tunnel IV key
 *   bytes 104-135: AES-256 reply key
 *   bytes 136-151: AES-256 reply IV
 *   byte      152: flags
 *   bytes 153-155: more flags, unused, set to 0 for compatibility
 *   bytes 156-159: request time (in minutes since the epoch, rounded down)
 *   bytes 160-163: request expiration (in seconds since creation)
 *   bytes 164-167: next message ID
 *   bytes   168-x: tunnel build options (Mapping)
 *   bytes     x-x: other data as implied by flags or options
 *   bytes   x-463: random padding
 * </pre>
 *
 * Encrypted:
 * <pre>
 *   bytes    0-15: Hop's truncated identity hash
 *   bytes   16-47: Sender's ephemeral X25519 public key
 *   bytes  48-511: ChaCha20 encrypted BuildRequestRecord
 *   bytes 512-527: Poly1305 MAC
 * </pre>
 *
 */
public class BuildRequestRecord {
    private final byte[] _data;
    private final boolean _isEC;
    private SessionKey _chachaReplyKey;
    private byte[] _chachaReplyAD;
    
    /** 
     * If set in the flag byte, any peer may send a message into this tunnel, but if
     * not set, only the current predecessor may send messages in.  This is only set on
     * an inbound tunnel gateway.
     */
    private static final int FLAG_UNRESTRICTED_PREV = (1 << 7); 
    /**
     * If set in the flag byte, this is an outbound tunnel endpoint, which means that
     * there is no 'next hop' and that the next hop fields contain the tunnel to which the
     * reply message should be sent.
     */
    private static final int FLAG_OUTBOUND_ENDPOINT = (1 << 6);
    
    public static final int IV_SIZE = 16;
    /** we show 16 bytes of the peer hash outside the elGamal block */
    public static final int PEER_SIZE = 16;
    
    /**
     *  @return 222 (ElG) or 464 (ECIES) bytes, non-null
     */
    public byte[] getData() { return _data; }

    // Original ElGamal format
    private static final int OFF_RECV_TUNNEL = 0;
    private static final int OFF_OUR_IDENT = OFF_RECV_TUNNEL + 4;
    private static final int OFF_SEND_TUNNEL = OFF_OUR_IDENT + Hash.HASH_LENGTH;
    private static final int OFF_SEND_IDENT = OFF_SEND_TUNNEL + 4;
    private static final int OFF_LAYER_KEY = OFF_SEND_IDENT + Hash.HASH_LENGTH;
    private static final int OFF_IV_KEY = OFF_LAYER_KEY + SessionKey.KEYSIZE_BYTES;
    public static final int OFF_REPLY_KEY = OFF_IV_KEY + SessionKey.KEYSIZE_BYTES;
    private static final int OFF_REPLY_IV = OFF_REPLY_KEY + SessionKey.KEYSIZE_BYTES;
    private static final int OFF_FLAG = OFF_REPLY_IV + IV_SIZE;
    private static final int OFF_REQ_TIME = OFF_FLAG + 1;
    private static final int OFF_SEND_MSG_ID = OFF_REQ_TIME + 4;
    private static final int PADDING_SIZE = 29;
    // 222
    private static final int LENGTH = OFF_SEND_MSG_ID + 4 + PADDING_SIZE;

    // New ECIES format
    private static final int OFF_SEND_TUNNEL_EC = OFF_OUR_IDENT;
    private static final int OFF_SEND_IDENT_EC = OFF_SEND_TUNNEL_EC + 4;
    private static final int OFF_LAYER_KEY_EC = OFF_SEND_IDENT_EC + Hash.HASH_LENGTH;
    private static final int OFF_IV_KEY_EC = OFF_LAYER_KEY_EC + SessionKey.KEYSIZE_BYTES;
    public static final int OFF_REPLY_KEY_EC = OFF_IV_KEY_EC + SessionKey.KEYSIZE_BYTES;
    private static final int OFF_REPLY_IV_EC = OFF_REPLY_KEY_EC + SessionKey.KEYSIZE_BYTES;
    private static final int OFF_FLAG_EC = OFF_REPLY_IV_EC + IV_SIZE;
    private static final int OFF_REQ_TIME_EC = OFF_FLAG_EC + 4;
    private static final int OFF_SEND_MSG_ID_EC = OFF_REQ_TIME_EC + 4;
    private static final int OFF_OPTIONS = OFF_SEND_MSG_ID_EC + 4;
    private static final int LENGTH_EC = 464;
    private static final int MAX_OPTIONS_LENGTH = LENGTH_EC - OFF_OPTIONS; // includes options length
    
    private static final boolean TEST = false;
    private static KeyFactory TESTKF;
    
    /** what tunnel ID should this receive messages on */
    public long readReceiveTunnelId() { 
        return DataHelper.fromLong(_data, OFF_RECV_TUNNEL, 4);
    }

    /**
     * What tunnel ID the next hop receives messages on.  If this is the outbound tunnel endpoint,
     * this specifies the tunnel ID to which the reply should be sent.
     */
    public long readNextTunnelId() {
        int off = _isEC ? OFF_SEND_TUNNEL_EC : OFF_SEND_TUNNEL;
        return DataHelper.fromLong(_data, off, 4);
    }

    /**
     * Read the next hop from the record.  If this is the outbound tunnel endpoint, this specifies
     * the gateway to which the reply should be sent.
     */
    public Hash readNextIdentity() {
        int off = _isEC ? OFF_SEND_IDENT_EC : OFF_SEND_IDENT;
        return Hash.create(_data, off);
    }

    /**
     * Tunnel layer encryption key that the current hop should use
     */
    public SessionKey readLayerKey() {
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        int off = _isEC ? OFF_LAYER_KEY_EC : OFF_LAYER_KEY;
        System.arraycopy(_data, off, key, 0, SessionKey.KEYSIZE_BYTES);
        return new SessionKey(key);
    }

    /**
     * Tunnel IV encryption key that the current hop should use
     */
    public SessionKey readIVKey() {
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        int off = _isEC ? OFF_IV_KEY_EC : OFF_IV_KEY;
        System.arraycopy(_data, off, key, 0, SessionKey.KEYSIZE_BYTES);
        return new SessionKey(key);
    }

    /**
     * Session key that should be used to encrypt the reply
     */
    public SessionKey readReplyKey() {
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        int off = _isEC ? OFF_REPLY_KEY_EC : OFF_REPLY_KEY;
        System.arraycopy(_data, off, key, 0, SessionKey.KEYSIZE_BYTES);
        return new SessionKey(key);
    }

    /**
     * IV that should be used to encrypt the reply
     */
    public byte[] readReplyIV() {
        byte iv[] = new byte[IV_SIZE];
        int off = _isEC ? OFF_REPLY_IV_EC : OFF_REPLY_IV;
        System.arraycopy(_data, off, iv, 0, IV_SIZE);
        return iv;
    }

    /** 
     * The current hop is the inbound gateway.  If this is true, it means anyone can send messages to
     * this tunnel, but if it is false, only the current predecessor can.
     *
     */
    public boolean readIsInboundGateway() {
        int off = _isEC ? OFF_FLAG_EC : OFF_FLAG;
        return (_data[off] & FLAG_UNRESTRICTED_PREV) != 0;
    }

    /**
     * The current hop is the outbound endpoint.  If this is true, the next identity and next tunnel
     * fields refer to where the reply should be sent.
     */
    public boolean readIsOutboundEndpoint() { 
        int off = _isEC ? OFF_FLAG_EC : OFF_FLAG;
        return (_data[off] & FLAG_OUTBOUND_ENDPOINT) != 0;
    }

    /**
     * For ElGamal, time that the request was sent (ms), truncated to the nearest hour.
     * For ECIES, time that the request was sent (ms), truncated to the nearest minute.
     * This ignores leap seconds.
     */
    public long readRequestTime() {
        if (_isEC)
            return DataHelper.fromLong(_data, OFF_REQ_TIME_EC, 4) * (60 * 1000L);
        return DataHelper.fromLong(_data, OFF_REQ_TIME, 4) * (60 * 60 * 1000L);
    }

    /**
     * What message ID should we send the request to the next hop with.  If this is the outbound tunnel endpoint,
     * this specifies the message ID with which the reply should be sent.
     */
    public long readReplyMessageId() {
        int off = _isEC ? OFF_SEND_MSG_ID_EC : OFF_SEND_MSG_ID;
        return DataHelper.fromLong(_data, off, 4);
    }

    /**
     * ECIES only.
     * @return null for ElGamal or on error
     * @since 0.9.48
     */
    public Properties readOptions() {
        if (!_isEC)
            return null;
        ByteArrayInputStream in = new ByteArrayInputStream(_data, OFF_OPTIONS, MAX_OPTIONS_LENGTH);
        try {
            return DataHelper.readProperties(in, null);
        } catch (DataFormatException dfe) {
            return null;
        } catch (IOException ioe) {
            return null;
        }
    }
    
    /**
     * Encrypt the record to the specified peer.  The result is formatted as: <pre>
     *   bytes 0-15: truncated SHA-256 of the current hop's identity (the toPeer parameter)
     * bytes 15-527: ElGamal-2048 encrypted block
     * </pre>
     *
     * ElGamal only
     *
     * @return non-null
     */
    public EncryptedBuildRecord encryptRecord(I2PAppContext ctx, PublicKey toKey, Hash toPeer) {
        EncType type = toKey.getType();
        if (type != EncType.ELGAMAL_2048)
            throw new IllegalArgumentException();
        byte[] out = new byte[EncryptedBuildRecord.LENGTH];
        System.arraycopy(toPeer.getData(), 0, out, 0, PEER_SIZE);
        byte encrypted[] = ctx.elGamalEngine().encrypt(_data, toKey);
        // the elg engine formats it kind of weird, giving 257 bytes for each part rather than 256, so
        // we want to strip out that excess byte and store it in the record
        System.arraycopy(encrypted, 1, out, PEER_SIZE, 256);
        System.arraycopy(encrypted, 258, out, 256 + PEER_SIZE, 256);
        return new EncryptedBuildRecord(out);
    }
    
    /**
     * Encrypt the record to the specified peer. ECIES only.
     * The ChaCha reply key and IV will be available via the getters
     * after this call.
     * See class javadocs for format.
     * See proposal 152.
     *
     * @return non-null
     * @since 0.9.48
     */
    public EncryptedBuildRecord encryptECIESRecord(RouterContext ctx, PublicKey toKey, Hash toPeer) {
        EncType type = toKey.getType();
        if (type != EncType.ECIES_X25519)
            throw new IllegalArgumentException();
        byte[] out = new byte[EncryptedBuildRecord.LENGTH];
        System.arraycopy(toPeer.getData(), 0, out, 0, PEER_SIZE);
        HandshakeState state = null;
        try {
            KeyFactory kf = TEST ? TESTKF : ctx.commSystem().getXDHFactory();
            state = new HandshakeState(HandshakeState.PATTERN_ID_N, HandshakeState.INITIATOR, kf);
            state.getRemotePublicKey().setPublicKey(toKey.getData(), 0);
            state.start();
            state.writeMessage(out, PEER_SIZE, _data, 0, LENGTH_EC);
            EncryptedBuildRecord rv = new EncryptedBuildRecord(out);
            _chachaReplyKey = new SessionKey(state.getChainingKey());
            _chachaReplyAD = new byte[32];
            System.arraycopy(state.getHandshakeHash(), 0, _chachaReplyAD, 0, 32);
            return rv;
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("failed", gse);
        } finally {
            if (state != null)
                state.destroy();
        }
    }

    /**
     * Valid after calling encryptECIESRecord() or after the decrypting constructor
     * with an ECIES private key.
     * See proposal 152.
     *
     * @return null if no ECIES encrypt/decrypt operation was performed
     * @since 0.9.48
     */
    public SessionKey getChaChaReplyKey() { return _chachaReplyKey; }

    /**
     * Valid after calling encryptECIESRecord() or after the decrypting constructor
     * with an ECIES private key.
     * See proposal 152.
     *
     * @return null if no ECIES encrypt/decrypt operation was performed
     * @since 0.9.48
     */
    public byte[] getChaChaReplyAD() { return _chachaReplyAD; }

    
    /**
     * Decrypt the data from the specified record, writing the decrypted record into this instance's
     * data buffer
     *
     * Caller MUST check that first 16 bytes of our hash matches first 16 bytes of encryptedRecord
     * before calling this. Not checked here.
     *
     * The ChaCha reply key and IV will be available via the getters
     * after this call if ourKey is ECIES.
     *
     * @throws DataFormatException on decrypt fail
     * @since 0.9.48
     */
    public BuildRequestRecord(RouterContext ctx, PrivateKey ourKey,
                              EncryptedBuildRecord encryptedRecord) throws DataFormatException {
        byte decrypted[];
        EncType type = ourKey.getType();
        if (type == EncType.ELGAMAL_2048) {
            byte preDecrypt[] = new byte[514];
            System.arraycopy(encryptedRecord.getData(), PEER_SIZE, preDecrypt, 1, 256);
            System.arraycopy(encryptedRecord.getData(), PEER_SIZE + 256, preDecrypt, 258, 256);
            decrypted = ctx.elGamalEngine().decrypt(preDecrypt, ourKey);
            _isEC = false;
        } else if (type == EncType.ECIES_X25519) {
            HandshakeState state = null;
            try {
                KeyFactory kf = TEST ? TESTKF : ctx.commSystem().getXDHFactory();
                state = new HandshakeState(HandshakeState.PATTERN_ID_N, HandshakeState.RESPONDER, kf);
                state.getLocalKeyPair().setKeys(ourKey.getData(), 0,
                                                ourKey.toPublic().getData(), 0);
                state.start();
                decrypted = new byte[LENGTH_EC];
                state.readMessage(encryptedRecord.getData(), PEER_SIZE, EncryptedBuildRecord.LENGTH - PEER_SIZE,
                                  decrypted, 0);
                _chachaReplyKey = new SessionKey(state.getChainingKey());
                _chachaReplyAD = new byte[32];
                System.arraycopy(state.getHandshakeHash(), 0, _chachaReplyAD, 0, 32);
            } catch (GeneralSecurityException gse) {
                throw new DataFormatException("decrypt fail", gse);
            } finally {
                if (state != null)
                    state.destroy();
            }
            _isEC = true;
        } else {
            throw new DataFormatException("Unsupported EncType " + type);
        }
        if (decrypted != null) {
            _data = decrypted;
        } else {
            throw new DataFormatException("decrypt fail");
        }
    }

    /**
     * Populate this instance with data.  A new buffer is created to contain the data, with the 
     * necessary randomized padding.
     *
     * ElGamal only. ECIES constructor below.
     *
     * @param receiveTunnelId tunnel the current hop will receive messages on
     * @param peer current hop's identity, unused, no read() method
     * @param nextTunnelId id for the next hop, or where we send the reply (if we are the outbound endpoint)
     * @param nextHop next hop's identity, or where we send the reply (if we are the outbound endpoint)
     * @param nextMsgId message ID to use when sending on to the next hop (or for the reply)
     * @param layerKey tunnel layer key to be used by the peer
     * @param ivKey tunnel IV key to be used by the peer
     * @param replyKey key to be used when encrypting the reply to this build request
     * @param iv iv to be used when encrypting the reply to this build request
     * @param isInGateway are we the gateway of an inbound tunnel?
     * @param isOutEndpoint are we the endpoint of an outbound tunnel?
     * @since 0.9.18, was createRecord()
     */
    public BuildRequestRecord(I2PAppContext ctx, long receiveTunnelId, Hash peer, long nextTunnelId, Hash nextHop, long nextMsgId,
                             SessionKey layerKey, SessionKey ivKey, SessionKey replyKey, byte iv[], boolean isInGateway,
                             boolean isOutEndpoint) {
        byte buf[] = new byte[LENGTH];
        _data = buf;
        _isEC = false;
        
       /*   bytes     0-3: tunnel ID to receive messages as
        *   bytes    4-35: local router identity hash
        *   bytes   36-39: next tunnel ID
        *   bytes   40-71: next router identity hash
        *   bytes  72-103: AES-256 tunnel layer key
        *   bytes 104-135: AES-256 tunnel IV key
        *   bytes 136-167: AES-256 reply key
        *   bytes 168-183: reply IV
        *   byte      184: flags
        *   bytes 185-188: request time (in hours since the epoch)
        *   bytes 189-192: next message ID
        *   bytes 193-221: uninterpreted / random padding
        */
        DataHelper.toLong(buf, OFF_RECV_TUNNEL, 4, receiveTunnelId);
        System.arraycopy(peer.getData(), 0, buf, OFF_OUR_IDENT, Hash.HASH_LENGTH);
        DataHelper.toLong(buf, OFF_SEND_TUNNEL, 4, nextTunnelId);
        System.arraycopy(nextHop.getData(), 0, buf, OFF_SEND_IDENT, Hash.HASH_LENGTH);
        System.arraycopy(layerKey.getData(), 0, buf, OFF_LAYER_KEY, SessionKey.KEYSIZE_BYTES);
        System.arraycopy(ivKey.getData(), 0, buf, OFF_IV_KEY, SessionKey.KEYSIZE_BYTES);
        System.arraycopy(replyKey.getData(), 0, buf, OFF_REPLY_KEY, SessionKey.KEYSIZE_BYTES);
        System.arraycopy(iv, 0, buf, OFF_REPLY_IV, IV_SIZE);
        if (isInGateway)
            buf[OFF_FLAG] |= FLAG_UNRESTRICTED_PREV;
        else if (isOutEndpoint)
            buf[OFF_FLAG] |= FLAG_OUTBOUND_ENDPOINT;
        long truncatedHour = ctx.clock().now();
        // prevent hop identification at top of the hour
        truncatedHour -= ctx.random().nextInt(90*1000);
        // this ignores leap seconds
        truncatedHour /= (60l*60l*1000l);
        DataHelper.toLong(buf, OFF_REQ_TIME, 4, truncatedHour);
        DataHelper.toLong(buf, OFF_SEND_MSG_ID, 4, nextMsgId);
        ctx.random().nextBytes(buf, OFF_SEND_MSG_ID+4, PADDING_SIZE);
    }

    /**
     * Populate this instance with data.  A new buffer is created to contain the data, with the 
     * necessary randomized padding.
     *
     * ECIES only. ElGamal constructor above.
     *
     * @param receiveTunnelId tunnel the current hop will receive messages on
     * @param nextTunnelId id for the next hop, or where we send the reply (if we are the outbound endpoint)
     * @param nextHop next hop's identity, or where we send the reply (if we are the outbound endpoint)
     * @param nextMsgId message ID to use when sending on to the next hop (or for the reply)
     * @param layerKey tunnel layer key to be used by the peer
     * @param ivKey tunnel IV key to be used by the peer
     * @param replyKey key to be used when encrypting the reply to this build request
     * @param iv iv to be used when encrypting the reply to this build request
     * @param isInGateway are we the gateway of an inbound tunnel?
     * @param isOutEndpoint are we the endpoint of an outbound tunnel?
     * @param options 296 bytes max when serialized
     * @since 0.9.48
     * @throws IllegalArgumentException if options too long
     */
    public BuildRequestRecord(I2PAppContext ctx, long receiveTunnelId, long nextTunnelId, Hash nextHop, long nextMsgId,
                             SessionKey layerKey, SessionKey ivKey, SessionKey replyKey, byte iv[], boolean isInGateway,
                             boolean isOutEndpoint, Properties options) {
        byte buf[] = new byte[LENGTH_EC];
        _data = buf;
        _isEC = true;
        
        DataHelper.toLong(buf, OFF_RECV_TUNNEL, 4, receiveTunnelId);
        DataHelper.toLong(buf, OFF_SEND_TUNNEL_EC, 4, nextTunnelId);
        System.arraycopy(nextHop.getData(), 0, buf, OFF_SEND_IDENT_EC, Hash.HASH_LENGTH);
        System.arraycopy(layerKey.getData(), 0, buf, OFF_LAYER_KEY_EC, SessionKey.KEYSIZE_BYTES);
        System.arraycopy(ivKey.getData(), 0, buf, OFF_IV_KEY_EC, SessionKey.KEYSIZE_BYTES);
        System.arraycopy(replyKey.getData(), 0, buf, OFF_REPLY_KEY_EC, SessionKey.KEYSIZE_BYTES);
        System.arraycopy(iv, 0, buf, OFF_REPLY_IV_EC, IV_SIZE);
        if (isInGateway)
            buf[OFF_FLAG_EC] |= FLAG_UNRESTRICTED_PREV;
        else if (isOutEndpoint)
            buf[OFF_FLAG_EC] |= FLAG_OUTBOUND_ENDPOINT;
        long truncatedMinute = ctx.clock().now();
        // prevent hop identification at top of the minute
        truncatedMinute -= ctx.random().nextInt(2048);
        // this ignores leap seconds
        truncatedMinute /= (60*1000L);
        DataHelper.toLong(buf, OFF_REQ_TIME_EC, 4, truncatedMinute);
        DataHelper.toLong(buf, OFF_SEND_MSG_ID_EC, 4, nextMsgId);
        try {
            int off = DataHelper.toProperties(buf, OFF_OPTIONS, options);
            int sz = LENGTH_EC - off;
            if (sz > 0)
                ctx.random().nextBytes(buf, off, sz);
        } catch (Exception e) {
            throw new IllegalArgumentException("options", e);
        }
    }

    /**
     *  @since 0.9.24
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append(_isEC ? "ECIES" : "ElGamal");
        buf.append(" BRR ");
        boolean isIBGW = readIsInboundGateway();
        boolean isOBEP = readIsOutboundEndpoint();
        if (isIBGW) {
            buf.append("IBGW in: ").append(readReceiveTunnelId())
               .append(" out ").append(readNextTunnelId());
        } else if (isOBEP) {
            buf.append("OBEP in: ").append(readReceiveTunnelId());
        } else {
            buf.append("part. in: ").append(readReceiveTunnelId())
               .append(" out: ").append(readNextTunnelId());
        }
        buf.append(" to: ").append(readNextIdentity())
           .append(" layer key: ").append(readLayerKey())
           .append(" IV key: ").append(readIVKey())
           .append(" reply key: ").append(readReplyKey())
           .append(" reply IV: ").append(Base64.encode(readReplyIV()))
           .append(" time: ").append(DataHelper.formatTime(readRequestTime()))
           .append(" reply msg id: ").append(readReplyMessageId());
        if (_isEC) {
            buf.append(" options: ").append(readOptions());
            if (_chachaReplyKey != null) {
                buf.append(" chacha reply key: ").append(_chachaReplyKey)
                   .append(" chacha reply IV: ").append(Base64.encode(_chachaReplyAD));
            }
        }
        // to chase i2pd bug
        //buf.append('\n').append(net.i2p.util.HexDump.dump(readReplyKey().getData()));
        return buf.toString();
    }

/****
    public static void main(String[] args) throws Exception {
        RouterContext ctx = new RouterContext(null);
        TESTKF = new net.i2p.router.transport.crypto.X25519KeyFactory(ctx);
        byte[] h = new byte[32];
        ctx.random().nextBytes(h);
        Hash bh = new Hash(h);
        SessionKey k1 = ctx.keyGenerator().generateSessionKey();
        SessionKey k2 = ctx.keyGenerator().generateSessionKey();
        SessionKey k3 = ctx.keyGenerator().generateSessionKey();
        byte[] iv = new byte[16];
        ctx.random().nextBytes(iv);
        Properties props = new Properties();
        props.setProperty("foo", "bar");
        BuildRequestRecord brr = new BuildRequestRecord(ctx, 1, 2, bh, 3, k1, k2, k3, iv, false, false, props);
        System.out.println(brr.toString());
        System.out.println("\nplaintext request:\n" + net.i2p.util.HexDump.dump(brr.getData()));
        net.i2p.crypto.KeyPair kp = ctx.keyGenerator().generatePKIKeys(net.i2p.crypto.EncType.ECIES_X25519);
        PublicKey bpub = kp.getPublic();
        PrivateKey bpriv = kp.getPrivate();
        EncryptedBuildRecord record = brr.encryptECIESRecord(ctx, bpub, bh);
        System.out.println("\nencrypted request:\n" + net.i2p.util.HexDump.dump(record.getData()));
        System.out.println("reply key: " + brr.getChaChaReplyKey());
        System.out.println("reply IV: " + net.i2p.data.Base64.encode(brr.getChaChaReplyAD()));
        BuildRequestRecord brr2 = new BuildRequestRecord(ctx, bpriv, record);
        System.out.println(brr2.toString());
        System.out.println("\nreply key: " + brr2.getChaChaReplyKey());
        System.out.println("reply IV: " + net.i2p.data.Base64.encode(brr2.getChaChaReplyAD()));
        props.setProperty("yes", "no");
        EncryptedBuildRecord ebr = BuildResponseRecord.create(ctx, 1, brr.getChaChaReplyKey(), brr.getChaChaReplyAD(), props);
        System.out.println("\nencrypted reply:\n" + net.i2p.util.HexDump.dump(ebr.getData()));
        BuildResponseRecord.decrypt(ebr, brr2.getChaChaReplyKey(), brr2.getChaChaReplyAD());
        System.out.println("\nplaintext reply:\n" + net.i2p.util.HexDump.dump(ebr.getData()));
        Properties p2 = new net.i2p.util.OrderedProperties();
        DataHelper.fromProperties(ebr.getData(), 0, p2);
        System.out.println("reply props: " + p2);
        System.out.println("reply status: " + (ebr.getData()[511] & 0xff));
    }
****/
}   
