package net.i2p.router.transport.udp;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;

/**
 *  SSU2 Utils and constants
 *
 *  @since 0.9.54
 */
final class SSU2Util {
    public static final int PROTOCOL_VERSION = 2;

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

    public static final int PADDING_MAX = 64;

    /** 40 */
    public static final int MIN_DATA_LEN = SHORT_HEADER_SIZE + TOTAL_PROT_SAMPLE_LEN;
    /** 56 */
    public static final int MIN_LONG_DATA_LEN = LONG_HEADER_SIZE + TOTAL_PROT_SAMPLE_LEN;
    /** 88 */
    public static final int MIN_HANDSHAKE_DATA_LEN = SESSION_HEADER_SIZE + TOTAL_PROT_SAMPLE_LEN;


    /** 3 byte block header + 9 byte I2NP header = 12 */
    public static final int FULL_I2NP_HEADER_SIZE = SSU2Payload.BLOCK_HEADER_SIZE + 9;

    /** 3 byte block header + 9 byte I2NP header = 12 */
    public static final int FIRST_FRAGMENT_HEADER_SIZE = SSU2Payload.BLOCK_HEADER_SIZE + 9;

    /** 3 byte block header + 4 byte msg ID + 1 byte fragment info = 8 */
    public static final int FOLLOWON_FRAGMENT_HEADER_SIZE = SSU2Payload.BLOCK_HEADER_SIZE + 5;

    /** 16 byte block header + 2 + 12 = 30 */
    public static final int DATA_HEADER_SIZE = SHORT_HEADER_SIZE + 2 + FULL_I2NP_HEADER_SIZE;

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

    public static final String INFO_CREATED =   "SessCreateHeader";
    public static final String INFO_CONFIRMED = "SessionConfirmed";
    public static final String INFO_DATA =      "HKDFSSU2DataKeys";

    public static final byte[] ZEROLEN = new byte[0];
    public static final byte[] ZEROKEY = new byte[KEY_LEN];

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
}
