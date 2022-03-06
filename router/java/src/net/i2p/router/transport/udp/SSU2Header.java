package net.i2p.router.transport.udp;

import java.net.DatagramPacket;

import net.i2p.crypto.ChaCha20;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import static net.i2p.router.transport.udp.SSU2Util.*;

/**
 *  Encrypt/decrypt headers
 *
 *  @since 0.9.54
 */
final class SSU2Header {
    
    /** 8 bytes of zeros */
    public static final byte[] HEADER_PROT_DATA = new byte[HEADER_PROT_DATA_LEN];
    /** 12 bytes of zeros */
    public static final byte[] CHACHA_IV_0 = new byte[CHACHA_IV_LEN];

    private SSU2Header() {}
    
    /**
     *  Session Request and Session Created only. 64 bytes.
     *  Packet is unmodified.
     *
     *  @param packet must be 88 bytes min
     *  @return 64 byte header, null if data too short
     */
    public static Header trialDecryptHandshakeHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        if (pkt.getLength() < MIN_HANDSHAKE_DATA_LEN)
            return null;
        Header header = new Header(SESSION_HEADER_SIZE);
        decryptHandshakeHeader(pkt, key1, key2, header);
        return header;
    }

    /**
     *  Retry, Token Request, Peer Test only. 32 bytes.
     *  Packet is unmodified.
     *
     *  @param packet must be 56 bytes min
     *  @return 32 byte header, null if data too short
     */
    public static Header trialDecryptLongHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        if (pkt.getLength() < MIN_LONG_DATA_LEN)
            return null;
        Header header = new Header(LONG_HEADER_SIZE);
        decryptLongHeader(pkt, key1, key2, header);
        return header;
    }

    /**
     *  Session Confirmed and data phase. 16 bytes.
     *  Packet is unmodified.
     *
     *  @param packet must be 40 bytes min
     *  @return 16 byte header, null if data too short, must be 40 bytes min
     */
    public static Header trialDecryptShortHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        if (pkt.getLength() < MIN_DATA_LEN)
            return null;
        Header header = new Header(SHORT_HEADER_SIZE);
        decryptShortHeader(pkt, key1, key2, header);
        return header;
    }

    /**
     *  Decrypt bytes 0-7 in header.
     *  Packet is unmodified.
     *
     *  @param pkt must be 8 bytes min
     *  @return the destination connection ID
     *  @throws IndexOutOfBoundsException if too short
     */
    public static long decryptDestConnID(DatagramPacket pkt, byte[] key1) {
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte[] xor = new byte[HEADER_PROT_DATA_LEN];

        ChaCha20.decrypt(key1, data, off + len - HEADER_PROT_SAMPLE_1_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            xor[i] ^= data[i + off + HEADER_PROT_1_OFFSET];
        }
        return DataHelper.fromLong8(xor, 0);
    }

    /**
     *  Copy the header back to the packet. Cannot be undone.
     */
    public static void acceptTrialDecrypt(UDPPacket packet, Header header) {
        DatagramPacket pkt = packet.getPacket();
        int off = pkt.getOffset();
        byte data[] = pkt.getData();
        System.arraycopy(header.data, 0, data, off, header.data.length);
    }


    /**
     *  Decrypt bytes 0-63 from pkt to header
     *  First 64 bytes
     *  Packet is unmodified.
     */
    private static void decryptHandshakeHeader(DatagramPacket pkt, byte[] key1, byte[] key2, Header header) {
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        decryptShortHeader(pkt, key1, key2, header);
        ChaCha20.decrypt(key2, CHACHA_IV_0, data, off + SHORT_HEADER_SIZE, header.data, SHORT_HEADER_SIZE, KEY_LEN + LONG_HEADER_SIZE - SHORT_HEADER_SIZE);
    }

    /**
     *  Decrypt bytes 0-31 from pkt to header.
     *  First 32 bytes
     *  Packet is unmodified.
     */
    private static void decryptLongHeader(DatagramPacket pkt, byte[] key1, byte[] key2, Header header) {
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        decryptShortHeader(pkt, key1, key2, header);
        ChaCha20.decrypt(key2, CHACHA_IV_0, data, off + SHORT_HEADER_SIZE, header.data, SHORT_HEADER_SIZE, LONG_HEADER_SIZE - SHORT_HEADER_SIZE);
    }

    /**
     *  Decrypt bytes 0-15 to header.
     *  Packet is unmodified.
     *
     *  First 8 bytes uses key1 and the next-to-last 12 bytes as the IV.
     *  Next 8 bytes uses key2 and the last 12 bytes as the IV.
     */
    private static void decryptShortHeader(DatagramPacket pkt, byte[] key1, byte[] key2, Header header) {
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte[] xor = new byte[HEADER_PROT_DATA_LEN];

        ChaCha20.decrypt(key1, data, off + len - HEADER_PROT_SAMPLE_1_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            header.data[i + HEADER_PROT_1_OFFSET] = (byte) (data[i + off + HEADER_PROT_1_OFFSET] ^ xor[i]);
        }

        ChaCha20.decrypt(key2, data, off + len - HEADER_PROT_SAMPLE_2_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            header.data[i + HEADER_PROT_2_OFFSET] = (byte) (data[i + off + HEADER_PROT_2_OFFSET] ^ xor[i]);
        }
    }

    /**
     * A temporary structure returned from trial decrypt,
     * with methods to access the fields.
     */
    public static class Header {
        public final byte[] data;

        public Header(int len) { data = new byte[len]; }

        /** all headers */
        public long getDestConnID() { return DataHelper.fromLong8(data, 0); }
        /** all headers */
        public long getPacketNumber() { return DataHelper.fromLong(data, PKT_NUM_OFFSET, PKT_NUM_LEN); }
        /** all headers */
        public int getType() { return data[TYPE_OFFSET] & 0xff; }

        /** short headers only */
        public int getShortHeaderFlags() { return (int) DataHelper.fromLong(data, SHORT_HEADER_FLAGS_OFFSET, SHORT_HEADER_FLAGS_LEN); }

        /** long headers only */
        public int getVersion() { return data[VERSION_OFFSET] & 0xff; }
        /** long headers only */
        public int getNetID() { return data[NETID_OFFSET] & 0xff; }
        /** long headers only */
        public int getHandshakeHeaderFlags() { return data[LONG_HEADER_FLAGS_OFFSET] & 0xff; }
        /** long headers only */
        public long getSrcConnID() { return DataHelper.fromLong8(data, SRC_CONN_ID_OFFSET); }
        /** long headers only */
        public long getToken() { return DataHelper.fromLong8(data, TOKEN_OFFSET); }

        /** handshake headers only */
        public byte[] getEphemeralKey() {
            byte[] rv = new byte[KEY_LEN];
            System.arraycopy(data, LONG_HEADER_SIZE, rv, 0, KEY_LEN);
            return rv;
        }

        @Override
        public String toString() {
            if (data.length >= SESSION_HEADER_SIZE) {
                return "Handshake header destID " + getDestConnID() + " pkt num " + getPacketNumber() + " type " + getType() +
                       " version " + getVersion() + " netID " + getNetID() +
                       " srcID " + getSrcConnID() + " token " + getToken() + " key " + Base64.encode(getEphemeralKey());
            }
            if (data.length >= LONG_HEADER_SIZE) {
                return "Long header destID " + getDestConnID() + " pkt num " + getPacketNumber() + " type " + getType() +
                       " version " + getVersion() + " netID " + getNetID() +
                       " srcID " + getSrcConnID() + " token " + getToken();
            }
            return "Short header destID " + getDestConnID() + " pkt num " + getPacketNumber() + " type " + getType();
        }
    }

    ////////// Encryption ///////////

    /**
     *  First 64 bytes
     */
    public static void encryptHandshakeHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        encryptShortHeader(packet, key1, key2);
        ChaCha20.encrypt(key2, CHACHA_IV_0, data, off + SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE, KEY_LEN + LONG_HEADER_SIZE - SHORT_HEADER_SIZE);
    }

    /**
     *  First 32 bytes
     */
    public static void encryptLongHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        encryptShortHeader(packet, key1, key2);
        ChaCha20.encrypt(key2, CHACHA_IV_0, data, off + SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE, LONG_HEADER_SIZE - SHORT_HEADER_SIZE);
    }

    /**
     *  First 16 bytes.
     *
     *  First 8 bytes uses key1 and the next-to-last 12 bytes as the IV.
     *  Next 8 bytes uses key2 and the last 12 bytes as the IV.
     */
    public static void encryptShortHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte[] xor = new byte[HEADER_PROT_DATA_LEN];

        ChaCha20.encrypt(key1, data, off + len - HEADER_PROT_SAMPLE_1_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            data[i + off + HEADER_PROT_1_OFFSET] ^= xor[i];
        }

        ChaCha20.encrypt(key2, data, off + len - HEADER_PROT_SAMPLE_2_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            data[i + off + HEADER_PROT_2_OFFSET] ^= xor[i];
        }
    }



}
