package net.i2p.router.crypto.ratchet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.router.RouterContext;

/**
 *
 *  Ratchet payload generation and parsing
 *
 *  @since 0.9.44 adapted from NTCP2Payload
 */
class RatchetPayload {

    public static final int BLOCK_HEADER_SIZE = 3;

    private static final int BLOCK_DATETIME = 0;
    private static final int BLOCK_SESSIONID = 1;
    private static final int BLOCK_TERMINATION = 4;
    private static final int BLOCK_OPTIONS = 5;
    private static final int BLOCK_MSGNUM = 6;
    private static final int BLOCK_NEXTKEY = 7;
    private static final int BLOCK_ACK = 8;
    private static final int BLOCK_ACKREQ = 9;
    private static final int BLOCK_GARLIC = 11;
    private static final int BLOCK_PADDING = 254;

    /**
     *  For all callbacks, recommend throwing exceptions only from the handshake.
     *  Exceptions will get thrown out of processPayload() and prevent
     *  processing of succeeding blocks.
     */
    public interface PayloadCallback {
        public void gotDateTime(long time) throws DataFormatException;

        public void gotGarlic(GarlicClove clove);

        /**
         *  @param isHandshake true only for message 3 part 2
         */
        public void gotOptions(byte[] options, boolean isHandshake) throws DataFormatException;

        /**
         *  @param reason 0-255
         */
        public void gotTermination(int reason);

        /**
         *  @param pn 0-65535
         */
        public void gotPN(int pn);

        /**
         *  @param nextKey the next one
         */
        public void gotNextKey(NextSessionKey nextKey);

        /**
         *  @since 0.9.46
         */
        public void gotAck(int id, int n);

        /**
         *  @since 0.9.46
         */
        public void gotAckRequest();

        /**
         *  For stats.
         *  @param paddingLength the number of padding bytes, not including the 3-byte block header
         *  @param frameLength the total size of the frame, including all blocks and block headers
         */
        public void gotPadding(int paddingLength, int frameLength);

        public void gotUnknown(int type, int len);
    }

    /**
     *  Incoming payload. Calls the callback for each received block.
     *
     *  @return number of blocks processed
     *  @throws IOException on major errors
     *  @throws DataFormatException on parsing of individual blocks
     *  @throws I2NPMessageException on parsing of I2NP block
     */
    public static int processPayload(I2PAppContext ctx, PayloadCallback cb,
                                     byte[] payload, int off, int length, boolean isHandshake)
                                     throws IOException, DataFormatException, I2NPMessageException {
        int blocks = 0;
        boolean gotPadding = false;
        boolean gotTermination = false;
        int i = off;
        final int end = off + length;
        while (i < end) {
            int type = payload[i++] & 0xff;
            if (gotPadding)
                throw new IOException("Illegal block after padding: " + type);
            if (gotTermination && type != BLOCK_PADDING)
                throw new IOException("Illegal block after termination: " + type);
            int len = (int) DataHelper.fromLong(payload, i, 2);
            i += 2;
            if (i + len > end) {
                throw new IOException("Block " + blocks + " type " + type + " length " + len +
                                      " at offset " + (i - 3 - off) + " runs over frame of size " + length +
                                      '\n' + net.i2p.util.HexDump.dump(payload, off, length));
            }
            switch (type) {
                // don't modify i inside switch

                case BLOCK_DATETIME:
                    if (len != 4)
                        throw new IOException("Bad length for DATETIME: " + len);
                    long time = DataHelper.fromLong(payload, i, 4) * 1000;
                    cb.gotDateTime(time);
                    break;

                case BLOCK_OPTIONS:
                    byte[] options = new byte[len];
                    System.arraycopy(payload, i, options, 0, len);
                    cb.gotOptions(options, isHandshake);
                    break;

                case BLOCK_GARLIC:
                    GarlicClove clove = new GarlicClove(ctx);
                    clove.readBytesRatchet(payload, i, len);
                    cb.gotGarlic(clove);
                    break;

                case BLOCK_NEXTKEY:
                  {
                    if (len != 3 && len != 35)
                        throw new IOException("Bad length for NEXTKEY: " + len);
                    boolean hasKey = (payload[i] & 0x01) != 0;
                    boolean isReverse = (payload[i] & 0x02) != 0;
                    boolean isRequest = (payload[i] & 0x04) != 0;
                    int id = (int) DataHelper.fromLong(payload, i + 1, 2);
                    NextSessionKey nsk;
                    if (hasKey) {
                        byte[] data = new byte[32];
                        System.arraycopy(payload, i + 3, data, 0, 32);
                        nsk = new NextSessionKey(data, id, isReverse, isRequest);
                    } else {
                        nsk = new NextSessionKey(id, isReverse, isRequest);
                    }
                    cb.gotNextKey(nsk);
                  }
                    break;

                case BLOCK_ACK:
                  {
                    if (len < 4 || (len % 4) != 0)
                        throw new IOException("Bad length for ACK: " + len);
                    for (int j = i; j < i + len; j += 4) {
                        int id = (int) DataHelper.fromLong(payload, j, 2);
                        int n = (int) DataHelper.fromLong(payload, j + 2, 2);
                        cb.gotAck(id, n);
                    }
                  }
                    break;

                case BLOCK_ACKREQ:
                    if (len < 1)
                        throw new IOException("Bad length for ACKREQ: " + len);
                    cb.gotAckRequest();
                    break;

                case BLOCK_TERMINATION:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len < 1)
                        throw new IOException("Bad length for TERMINATION: " + len);
                    int rsn = payload[i] & 0xff;
                    cb.gotTermination(rsn);
                    gotTermination = true;
                    break;

                case BLOCK_MSGNUM:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len < 2)
                        throw new IOException("Bad length for PN: " + len);
                    int pn = (int) DataHelper.fromLong(payload, i, 2);
                    cb.gotPN(pn);
                    break;

                case BLOCK_PADDING:
                    gotPadding = true;
                    cb.gotPadding(len, length);
                    break;

                default:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    cb.gotUnknown(type, len);
                    break;

            }
            i += len;
            blocks++;
        }
        if (isHandshake && blocks == 0)
            throw new IOException("No blocks in handshake");
        return blocks;
    }

    /**
     *  @param payload writes to it starting at off
     *  @return the new offset
     */
    public static int writePayload(byte[] payload, int off, List<Block> blocks) {
        for (Block block : blocks) {
            off = block.write(payload, off);
        }
        return off;
    }

    /**
     *  Base class for blocks to be transmitted.
     *  Not used for receive; we use callbacks instead.
     */
    public static abstract class Block {
        private final int type;

        public Block(int ttype) {
            type = ttype;
        }

        /** @return new offset */
        public int write(byte[] tgt, int off) {
            tgt[off++] = (byte) type;
            // we do it this way so we don't call getDataLength(),
            // which may be inefficient
            // off is where the length goes
            int rv = writeData(tgt, off + 2);
            DataHelper.toLong(tgt, off, 2, rv - (off + 2));
            return rv;
        }

        /**
         *  @return the size of the block, including the 3 byte header (type and size)
         */
        public int getTotalLength() {
            return BLOCK_HEADER_SIZE + getDataLength();
        }

        /**
         *  @return the size of the block, NOT including the 3 byte header (type and size)
         */
        public abstract int getDataLength();

        /** @return new offset */
        public abstract int writeData(byte[] tgt, int off);

        @Override
        public String toString() {
            return "Payload block type " + type + " length " + getDataLength();
        }
    }

    public static class GarlicBlock extends Block {
        private final GarlicClove c;

        public GarlicBlock(GarlicClove clove) {
            super(BLOCK_GARLIC);
            c = clove;
        }

        public int getDataLength() {
            return c.getSizeRatchet();
        }

        public int writeData(byte[] tgt, int off) {
            return c.writeBytesRatchet(tgt, off);
        }
    }

    public static class PaddingBlock extends Block {
        private final int sz;
        private final I2PAppContext ctx;

        /** with zero-filled data */
        public PaddingBlock(int size) {
            this(null, size);
        }

        /** with random data */
        @Deprecated
        public PaddingBlock(I2PAppContext context, int size) {
            super(BLOCK_PADDING);
            sz = size;
            ctx = context;
        }

        public int getDataLength() {
            return sz;
        }

        public int writeData(byte[] tgt, int off) {
            if (ctx != null)
                ctx.random().nextBytes(tgt, off, sz);
            else
                Arrays.fill(tgt, off, off + sz, (byte) 0);
            return off + sz;
        }
    }

    public static class DateTimeBlock extends Block {
        private final long now;

        public DateTimeBlock(long time) {
            super(BLOCK_DATETIME);
            now = time;
        }

        public int getDataLength() {
            return 4;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, now / 1000);
            return off + 4;
        }
    }

    public static class OptionsBlock extends Block {
        private final byte[] opts;

        public OptionsBlock(byte[] options) {
            super(BLOCK_OPTIONS);
            opts = options;
        }

        public int getDataLength() {
            return opts.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(opts, 0, tgt, off, opts.length);
            return off + opts.length;
        }
    }

    public static class NextKeyBlock extends Block {
        private final NextSessionKey next;

        public NextKeyBlock(NextSessionKey nextKey) {
            super(BLOCK_NEXTKEY);
            next = nextKey;
        }

        public int getDataLength() {
            return next.getData() != null ? 35 : 3;
        }

        public int writeData(byte[] tgt, int off) {
            if (next.getData() != null)
                tgt[off] = 0x01;
            if (next.isReverse())
                tgt[off] |= 0x02;
            if (next.isRequest())
                tgt[off] |= 0x04;
            DataHelper.toLong(tgt, off + 1, 2, next.getID());
            if (next.getData() != null) {
                System.arraycopy(next.getData(), 0, tgt, off + 3, 32);
                return off + 35;
            }
            return off + 3;
        }
    }

    /**
     *  @since 0.9.46
     */
    public static class AckBlock extends Block {
        private final byte[] data;

        public AckBlock(int keyID, int n) {
            super(BLOCK_ACK);
            data = new byte[4];
            DataHelper.toLong(data, 0, 2, keyID);
            DataHelper.toLong(data, 2, 2, n);
        }

        /**
         *  @param acks each is id &lt;&lt; 16 | n
         */
        public AckBlock(List<Integer> acks) {
            super(BLOCK_ACK);
            data = new byte[4 * acks.size()];
            int i = 0;
            for (Integer a : acks) {
                toInt4(data, i, a.intValue());
                i += 4;
            }
        }

        public int getDataLength() {
            return data.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(data, 0, tgt, off, data.length);
            return off + data.length;
        }
    }

    /**
     *  @since 0.9.46
     */
    public static class AckRequestBlock extends Block {

        public AckRequestBlock() {
            super(BLOCK_ACKREQ);
            // flag is zero
        }

        public int getDataLength() {
            return 1;
        }

        public int writeData(byte[] tgt, int off) {
            tgt[off] = 0;
            return off + 1;
        }
    }

    public static class TerminationBlock extends Block {
        private final byte rsn;

        public TerminationBlock(int reason) {
            super(BLOCK_TERMINATION);
            rsn = (byte) reason;
        }

        public int getDataLength() {
            return 1;
        }

        public int writeData(byte[] tgt, int off) {
            tgt[off] = rsn;
            return off + 1;
        }
    }

    /**
     *  @since 0.9.46
     */
    public static class PNBlock extends Block {
        private final int pn;

        public PNBlock(int pn) {
            super(BLOCK_MSGNUM);
            this.pn = pn;
        }

        public int getDataLength() {
            return 2;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 2, pn);
            return off + 2;
        }
    }
    
    /**
     * Big endian.
     * Same as DataHelper.toLong(target, offset, 4, value) but allows negative value
     *
     * @throws ArrayIndexOutOfBoundsException
     * @since 0.9.46
     */
    private static void toInt4(byte target[], int offset, int value) {
        for (int i = offset + 3; i >= offset; i--) {
            target[i] = (byte) value;
            value >>= 8;
        }
    }
}
