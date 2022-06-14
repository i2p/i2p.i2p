package net.i2p.router.transport.udp;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 *  SSU2 Utils and constants
 *
 *  @since 0.9.54
 */
final class SSU2Util {
    public static final int PROTOCOL_VERSION = 2;

    // features
    public static final boolean ENABLE_RELAY = true;
    public static final boolean ENABLE_PEER_TEST = true;
    public static final boolean ENABLE_PATH_CHALLENGE = false;

    // lengths
    /** 32 */
    public static final int KEY_LEN = EncType.ECIES_X25519.getPubkeyLen();
    public static final int MAC_LEN = 16;
    public static final int CHACHA_IV_LEN = 12;
    public static final int INTRO_KEY_LEN = 32;
    public static final int SHORT_HEADER_SIZE = 16;
    public static final int LONG_HEADER_SIZE = 32;
    /** 64 */
    public static final int SESSION_HEADER_SIZE = LONG_HEADER_SIZE + KEY_LEN;

    // header fields
    public static final int DEST_CONN_ID_OFFSET = 0;
    public static final int PKT_NUM_OFFSET = 8;
    public static final int PKT_NUM_LEN = 4;
    public static final int TYPE_OFFSET = 12;
    public static final int VERSION_OFFSET = 13;
    public static final int SHORT_HEADER_FLAGS_OFFSET = 13;
    public static final int SHORT_HEADER_FLAGS_LEN = 3;
    public static final int NETID_OFFSET = 14;
    public static final int LONG_HEADER_FLAGS_OFFSET = 15;
    public static final int SRC_CONN_ID_OFFSET = 16;
    public static final int TOKEN_OFFSET = 24;

    // header protection
    public static final int HEADER_PROT_SAMPLE_LEN = 12;
    public static final int TOTAL_PROT_SAMPLE_LEN = 2 * HEADER_PROT_SAMPLE_LEN;
    public static final int HEADER_PROT_SAMPLE_1_OFFSET = 2 * HEADER_PROT_SAMPLE_LEN;
    public static final int HEADER_PROT_SAMPLE_2_OFFSET = HEADER_PROT_SAMPLE_LEN;
    public static final int HEADER_PROT_DATA_LEN = 8;
    public static final int HEADER_PROT_1_OFFSET = DEST_CONN_ID_OFFSET;
    public static final int HEADER_PROT_2_OFFSET = PKT_NUM_OFFSET;

    public static final int PADDING_MAX = 32;

    // data size minimums, not including IP/UDP headers

    /** 40 */
    public static final int MIN_DATA_LEN = SHORT_HEADER_SIZE + TOTAL_PROT_SAMPLE_LEN;
    /** 56 */
    public static final int MIN_LONG_DATA_LEN = LONG_HEADER_SIZE + TOTAL_PROT_SAMPLE_LEN;
    /** 88 */
    public static final int MIN_HANDSHAKE_DATA_LEN = SESSION_HEADER_SIZE + TOTAL_PROT_SAMPLE_LEN;
    /** 56 */
    public static final int MIN_TOKEN_REQUEST_LEN = MIN_LONG_DATA_LEN;
    /** 56 */
    public static final int MIN_RETRY_LEN = MIN_LONG_DATA_LEN;
    /** 88 */
    public static final int MIN_SESSION_REQUEST_LEN = MIN_HANDSHAKE_DATA_LEN;
    /** 88 */
    public static final int MIN_SESSION_CREATED_LEN = MIN_HANDSHAKE_DATA_LEN;
    /**
     * 380
     * Any RI, even compressed, will be at least 400 bytes.
     * It has a minimum 387 byte ident and 40 byte sig, neither is compressible.
     * Use 300 just to be safe for compression.
     */
    public static final int MIN_SESSION_CONFIRMED_LEN = SHORT_HEADER_SIZE + KEY_LEN + MAC_LEN + 300 + MAC_LEN;

    /** 3 byte block header */
    public static final int FIRST_FRAGMENT_HEADER_SIZE = SSU2Payload.BLOCK_HEADER_SIZE;

    /**
     * 5 for flag and msg number in followon block
     */
    public static final int DATA_FOLLOWON_EXTRA_SIZE = 5;

    /** 3 byte block header + 4 byte msg ID + 1 byte fragment info = 8 */
    public static final int FOLLOWON_FRAGMENT_HEADER_SIZE = SSU2Payload.BLOCK_HEADER_SIZE + DATA_FOLLOWON_EXTRA_SIZE;

    /** 16 byte short header */
    public static final int DATA_HEADER_SIZE = SHORT_HEADER_SIZE;

    /**
     *  The message types, 0-10, as bytes
     */
    public static final byte SESSION_REQUEST_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST;
    public static final byte SESSION_CREATED_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_SESSION_CREATED;
    public static final byte SESSION_CONFIRMED_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED;
    public static final byte DATA_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_DATA;
    public static final byte PEER_TEST_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_TEST;
    public static final byte RETRY_FLAG_BYTE = 9;
    public static final byte TOKEN_REQUEST_FLAG_BYTE = 10;
    public static final byte HOLE_PUNCH_FLAG_BYTE = 11;

    public static final String INFO_CREATED =   "SessCreateHeader";
    public static final String INFO_CONFIRMED = "SessionConfirmed";
    public static final String INFO_DATA =      "HKDFSSU2DataKeys";

    public static final byte[] ZEROLEN = new byte[0];
    public static final byte[] ZEROKEY = new byte[KEY_LEN];

    // relay and peer test
    public static final byte[] RELAY_REQUEST_PROLOGUE = DataHelper.getASCII("RelayRequestData");
    public static final byte[] RELAY_RESPONSE_PROLOGUE = DataHelper.getASCII("RelayAgreementOK");
    public static final byte[] PEER_TEST_PROLOGUE = DataHelper.getASCII("PeerTestValidate");

    public static final int TEST_ACCEPT = 0;
    public static final int TEST_REJECT_BOB_UNSPEC = 1;
    public static final int TEST_REJECT_BOB_NO_CHARLIE = 2;
    public static final int TEST_REJECT_BOB_LIMIT = 3;
    public static final int TEST_REJECT_BOB_SIGFAIL = 4;
    public static final int TEST_REJECT_CHARLIE_UNSPEC = 64;
    public static final int TEST_REJECT_CHARLIE_ADDRESS = 65;
    public static final int TEST_REJECT_CHARLIE_LIMIT = 66;
    public static final int TEST_REJECT_CHARLIE_SIGFAIL = 67;
    public static final int TEST_REJECT_CHARLIE_CONNECTED = 68;
    public static final int TEST_REJECT_CHARLIE_BANNED = 69;
    public static final int TEST_REJECT_CHARLIE_UNKNOWN_ALICE = 70;

    public static final int RELAY_ACCEPT = 0;
    public static final int RELAY_REJECT_BOB_UNSPEC = 1;
    public static final int RELAY_REJECT_BOB_BANNED_CHARLIE = 2;
    public static final int RELAY_REJECT_BOB_LIMIT = 3;
    public static final int RELAY_REJECT_BOB_SIGFAIL = 4;
    public static final int RELAY_REJECT_BOB_NO_TAG = 5;
    public static final int RELAY_REJECT_BOB_UNKNOWN_ALICE = 6;
    public static final int RELAY_REJECT_CHARLIE_UNSPEC = 64;
    public static final int RELAY_REJECT_CHARLIE_ADDRESS = 65;
    public static final int RELAY_REJECT_CHARLIE_LIMIT = 66;
    public static final int RELAY_REJECT_CHARLIE_SIGFAIL = 67;
    public static final int RELAY_REJECT_CHARLIE_CONNECTED = 68;
    public static final int RELAY_REJECT_CHARLIE_BANNED = 69;
    public static final int RELAY_REJECT_CHARLIE_UNKNOWN_ALICE = 70;

    // termination reason codes
    public static final int REASON_UNSPEC = 0;
    public static final int REASON_TERMINATION = 1;
    public static final int REASON_TIMEOUT = 2;
    public static final int REASON_SHUTDOWN = 3;
    public static final int REASON_AEAD = 4;
    public static final int REASON_OPTIONS = 5;
    public static final int REASON_SIGTYPE = 6;
    public static final int REASON_SKEW = 7;
    public static final int REASON_PADDING = 8;
    public static final int REASON_FRAMING = 9;
    public static final int REASON_PAYLOAD = 10;
    public static final int REASON_MSG1 = 11;
    public static final int REASON_MSG2 = 12;
    public static final int REASON_MSG3 = 13;
    public static final int REASON_FRAME_TIMEOUT = 14;
    public static final int REASON_SIGFAIL = 15;
    public static final int REASON_S_MISMATCH = 16;
    public static final int REASON_BANNED = 17;
    public static final int REASON_TOKEN = 18;
    public static final int REASON_LIMITS = 19;
    public static final int REASON_VERSION = 20;
    public static final int REASON_NETID = 21;

    private SSU2Util() {}

    /**
     *  32 byte output, ZEROLEN data
     */
    public static byte[] hkdf(I2PAppContext ctx, byte[] key, String info) {
        HKDF hkdf = new HKDF(ctx);
        byte[] rv = new byte[32];
        hkdf.calculate(key, ZEROLEN, info, rv);
        return rv;
    }

    /**
     *  Make the data for the peer test block
     *
     *  @param h to be included in sig, not included in data
     *  @param h2 may be null, to be included in sig, not included in data
     *  @param role unused
     *  @param ip may be null
     *  @return null on failure
     */
    public static byte[] createPeerTestData(I2PAppContext ctx, Hash h, Hash h2,
                                            PeerTestState.Role role, long nonce, byte[] ip, int port,
                                            SigningPrivateKey spk) {
        int datalen = 12 + (ip != null ? ip.length : 0);
        byte[] data = new byte[datalen + spk.getType().getSigLen()];
        data[0] = 2;  // version
        DataHelper.toLong(data, 1, 4, nonce);
        DataHelper.toLong(data, 5, 4, ctx.clock().now() / 1000);
        int iplen = (ip != null) ? ip.length : 0;
        data[9] = (byte) (ip != null ? iplen + 2 : 0);
        if (ip != null) {
            DataHelper.toLong(data, 10, 2, port);
            System.arraycopy(ip, 0, data, 12, iplen);
        }
        Signature sig = sign(ctx, PEER_TEST_PROLOGUE, h, h2, data, datalen, spk);
        if (sig == null)
            return null;
        byte[] s = sig.getData();
        System.arraycopy(s, 0, data, datalen, s.length);
        return data;
    }

    /**
     *  Make the data for the relay request block
     *
     *  @param h Bob hash to be included in sig, not included in data
     *  @param h2 Charlie hash to be included in sig, not included in data
     *  @param ip non-null
     *  @return null on failure
     *  @since 0.9.55
     */
    public static byte[] createRelayRequestData(I2PAppContext ctx, Hash h, Hash h2,
                                                long nonce, long tag, byte[] ip, int port,
                                                SigningPrivateKey spk) {
        int datalen = 16 + ip.length;
        byte[] data = new byte[datalen];
        DataHelper.toLong(data, 0, 4, nonce);
        DataHelper.toLong(data, 4, 4, tag);
        DataHelper.toLong(data, 8, 4, ctx.clock().now() / 1000);
        data[12] = 2;  // version
        data[13] = (byte) (ip.length + 2);
        DataHelper.toLong(data, 14, 2, port);
        System.arraycopy(ip, 0, data, 16, ip.length);
        Signature sig = sign(ctx, RELAY_REQUEST_PROLOGUE, h, h2, data, datalen, spk);
        if (sig == null)
            return null;
        int len = 1 + datalen + spk.getType().getSigLen();
        byte[] rv = new byte[len];
        //rv[0] = 0;  // flag
        System.arraycopy(data, 0, rv, 1, data.length);
        byte[] s = sig.getData();
        System.arraycopy(s, 0, rv, 1 + datalen, s.length);
        return rv;
    }

    /**
     *  Make the data for the relay response block
     *
     *  @param h Bob hash to be included in sig, not included in data
     *  @param ip may be null
     *  @param port ignored if ip is null
     *  @param token if nonzero, append it
     *  @return null on failure
     *  @since 0.9.55
     */
    public static byte[] createRelayResponseData(I2PAppContext ctx, Hash h, int code,
                                                 long nonce, byte[] ip, int port,
                                                 SigningPrivateKey spk, long token) {
        int datalen = 10;
        if (ip != null)
            datalen += 2 + ip.length;
        byte[] data = new byte[datalen];
        DataHelper.toLong(data, 0, 4, nonce);
        DataHelper.toLong(data, 4, 4, ctx.clock().now() / 1000);
        data[8] = 2;  // version
        if (ip != null) {
            data[9] = (byte) (ip.length + 2);
            DataHelper.toLong(data, 10, 2, port);
            System.arraycopy(ip, 0, data, 12, ip.length);
        } else {
            // data[9] = 0;
        }
        Signature sig = sign(ctx, RELAY_RESPONSE_PROLOGUE, h, null, data, datalen, spk);
        if (sig == null)
            return null;
        int len = 2 + datalen + spk.getType().getSigLen();
        if (token != 0)
            len += 8;
        byte[] rv = new byte[len];
        //rv[0] = 0;  // flag
        rv[1] = (byte) code;
        System.arraycopy(data, 0, rv, 2, data.length);
        byte[] s = sig.getData();
        System.arraycopy(s, 0, rv, 2 + datalen, s.length);
        if (token != 0)
            DataHelper.toLong8(rv, 2 + datalen + s.length, token);
        return rv;
    }

    /**
     *  Sign the relay or peer test data, using
     *  the prologue and hash as the initial data,
     *  and then the provided data.
     *
     *  @param data if desired, leave room at end for sig
     *  @param datalen the length of the data to be signed
     *  @param h to be included in sig, not included in data
     *  @param h2 may be null, to be included in sig, not included in data
     *  @return null on failure
     */
    public static Signature sign(I2PAppContext ctx, byte[] prologue, Hash h, Hash h2,
                                 byte[] data, int datalen, SigningPrivateKey spk) {
        int len = prologue.length + Hash.HASH_LENGTH + datalen;
        if (h2 != null)
            len += Hash.HASH_LENGTH;
        byte[] buf = new byte[len];
        System.arraycopy(prologue, 0, buf, 0, prologue.length);
        System.arraycopy(h.getData(), 0, buf, prologue.length, Hash.HASH_LENGTH);
        int off = prologue.length + Hash.HASH_LENGTH;
        if (h2 != null) {
            System.arraycopy(h2.getData(), 0, buf, off, Hash.HASH_LENGTH);
            off += Hash.HASH_LENGTH;
        }
        System.arraycopy(data, 0, buf, off, datalen);
        return ctx.dsa().sign(buf, spk);
    }

    /**
     *  Validate the signed relay or peer test data, using
     *  the prologue and hash as the initial data,
     *  and then the provided data which ends with a signature of the specified type.
     *
     *  @param h2 may be null
     *  @param data not including relay response token
     */
    public static boolean validateSig(I2PAppContext ctx, byte[] prologue, Hash h, Hash h2, byte[] data, SigningPublicKey spk) {
        SigType type = spk.getType();
        int siglen = type.getSigLen();
        int len = prologue.length + Hash.HASH_LENGTH + data.length - siglen;
        if (h2 != null)
            len += Hash.HASH_LENGTH;
        byte[] buf = new byte[len];
        System.arraycopy(prologue, 0, buf, 0, prologue.length);
        System.arraycopy(h.getData(), 0, buf, prologue.length, Hash.HASH_LENGTH);
        int off = prologue.length + Hash.HASH_LENGTH;
        if (h2 != null) {
            System.arraycopy(h2.getData(), 0, buf, off, Hash.HASH_LENGTH);
            off += Hash.HASH_LENGTH;
        }
        System.arraycopy(data, 0, buf, off, data.length - siglen);
        byte[] bsig = new byte[siglen];
        System.arraycopy(data, data.length - siglen, bsig, 0, siglen);
        Signature sig = new Signature(type, bsig);
        return ctx.dsa().verifySignature(sig, buf, spk);
    }
}
