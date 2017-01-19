package net.i2p.data.i2np;

import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;

/**
 * Holds the unencrypted 222-byte tunnel request record,
 * with a constructor for ElGamal decryption and a method for ElGamal encryption.
 * Iterative AES encryption/decryption is done elsewhere.
 *
 * Cleartext:
 * <pre>
 *   bytes     0-3: tunnel ID to receive messages as
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
 * </pre>
 *
 * Encrypted:
 * <pre>
 *   bytes    0-15: First 16 bytes of router hash
 *   bytes  16-527: ElGamal encrypted block (discarding zero bytes at elg[0] and elg[257])
 * </pre>
 *
 */
public class BuildRequestRecord {
    private final byte[] _data;
    
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
     *  @return 222 bytes, non-null
     */
    public byte[] getData() { return _data; }

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
    
    
    /** what tunnel ID should this receive messages on */
    public long readReceiveTunnelId() { 
        return DataHelper.fromLong(_data, OFF_RECV_TUNNEL, 4);
    }

    /**
     * What tunnel ID the next hop receives messages on.  If this is the outbound tunnel endpoint,
     * this specifies the tunnel ID to which the reply should be sent.
     */
    public long readNextTunnelId() {
        return DataHelper.fromLong(_data, OFF_SEND_TUNNEL, 4);
    }

    /**
     * Read the next hop from the record.  If this is the outbound tunnel endpoint, this specifies
     * the gateway to which the reply should be sent.
     */
    public Hash readNextIdentity() {
        //byte rv[] = new byte[Hash.HASH_LENGTH];
        //System.arraycopy(_data, OFF_SEND_IDENT, rv, 0, Hash.HASH_LENGTH);
        //return new Hash(rv);
        return Hash.create(_data, OFF_SEND_IDENT);
    }

    /**
     * Tunnel layer encryption key that the current hop should use
     */
    public SessionKey readLayerKey() {
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        System.arraycopy(_data, OFF_LAYER_KEY, key, 0, SessionKey.KEYSIZE_BYTES);
        return new SessionKey(key);
    }

    /**
     * Tunnel IV encryption key that the current hop should use
     */
    public SessionKey readIVKey() {
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        System.arraycopy(_data, OFF_IV_KEY, key, 0, SessionKey.KEYSIZE_BYTES);
        return new SessionKey(key);
    }

    /**
     * Session key that should be used to encrypt the reply
     */
    public SessionKey readReplyKey() {
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        System.arraycopy(_data, OFF_REPLY_KEY, key, 0, SessionKey.KEYSIZE_BYTES);
        return new SessionKey(key);
    }

    /**
     * IV that should be used to encrypt the reply
     */
    public byte[] readReplyIV() {
        byte iv[] = new byte[IV_SIZE];
        System.arraycopy(_data, OFF_REPLY_IV, iv, 0, IV_SIZE);
        return iv;
    }

    /** 
     * The current hop is the inbound gateway.  If this is true, it means anyone can send messages to
     * this tunnel, but if it is false, only the current predecessor can.
     *
     */
    public boolean readIsInboundGateway() {
        return (_data[OFF_FLAG] & FLAG_UNRESTRICTED_PREV) != 0;
    }

    /**
     * The current hop is the outbound endpoint.  If this is true, the next identity and next tunnel
     * fields refer to where the reply should be sent.
     */
    public boolean readIsOutboundEndpoint() { 
        return (_data[OFF_FLAG] & FLAG_OUTBOUND_ENDPOINT) != 0;
    }

    /**
     * Time that the request was sent (ms), truncated to the nearest hour.
     * This ignores leap seconds.
     */
    public long readRequestTime() {
        return DataHelper.fromLong(_data, OFF_REQ_TIME, 4) * (60 * 60 * 1000L);
    }

    /**
     * What message ID should we send the request to the next hop with.  If this is the outbound tunnel endpoint,
     * this specifies the message ID with which the reply should be sent.
     */
    public long readReplyMessageId() {
        return DataHelper.fromLong(_data, OFF_SEND_MSG_ID, 4);
    }
    
    /**
     * Encrypt the record to the specified peer.  The result is formatted as: <pre>
     *   bytes 0-15: truncated SHA-256 of the current hop's identity (the toPeer parameter)
     * bytes 15-527: ElGamal-2048 encrypted block
     * </pre>
     *
     * @return non-null
     */
    public EncryptedBuildRecord encryptRecord(I2PAppContext ctx, PublicKey toKey, Hash toPeer) {
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
     * Decrypt the data from the specified record, writing the decrypted record into this instance's
     * data buffer
     *
     * Caller MUST check that first 16 bytes of our hash matches first 16 bytes of encryptedRecord
     * before calling this. Not checked here.
     *
     * @throws DataFormatException on decrypt fail
     * @since 0.9.18, was decryptRecord()
     */
    public BuildRequestRecord(I2PAppContext ctx, PrivateKey ourKey,
                              EncryptedBuildRecord encryptedRecord) throws DataFormatException {
            byte preDecrypt[] = new byte[514];
            System.arraycopy(encryptedRecord.getData(), PEER_SIZE, preDecrypt, 1, 256);
            System.arraycopy(encryptedRecord.getData(), PEER_SIZE + 256, preDecrypt, 258, 256);
            byte decrypted[] = ctx.elGamalEngine().decrypt(preDecrypt, ourKey);
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
     * @param receiveTunnelId tunnel the current hop will receive messages on
     * @param peer current hop's identity
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
        
        byte wroteIV[] = readReplyIV();
        if (!DataHelper.eq(iv, wroteIV))
            throw new RuntimeException("foo");
    }

    /**
     *  @since 0.9.24
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("BRR ");
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
           .append(" hour: ").append(new Date(readRequestTime()))
           .append(" reply msg id: ").append(readReplyMessageId());
        // to chase i2pd bug
        //buf.append('\n').append(net.i2p.util.HexDump.dump(readReplyKey().getData()));
        return buf.toString();
    }
}   
