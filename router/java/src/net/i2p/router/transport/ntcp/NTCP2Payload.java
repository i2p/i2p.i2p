package net.i2p.router.transport.ntcp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.data.router.RouterInfo;

/**
 *
 *  NTCP2 Payload generation and parsing
 *
 *  @since 0.9.35
 */
class NTCP2Payload {

    private static final int BLOCK_DATETIME = 0;
    private static final int BLOCK_OPTIONS = 1;
    private static final int BLOCK_ROUTERINFO = 2;
    private static final int BLOCK_I2NP = 3;
    private static final int BLOCK_TERMINATION = 4;
    private static final int BLOCK_PADDING = 254;

    public interface PayloadCallback {
        public void gotDateTime(long time);
        public void gotI2NP(I2NPMessage msg);
        public void gotOptions(byte[] options, boolean isHandshake);
        public void gotRI(RouterInfo ri, boolean isHandshake);
        public void gotTermination(int reason);
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
                                     byte[] payload, int length, boolean isHandshake)
                                    throws IOException, DataFormatException, I2NPMessageException {
        int blocks = 0;
        boolean gotRI = false;
        boolean gotPadding = false;
        int i = 0;
        while (i < length) {
            int type = payload[i++] & 0xff;
            if (gotPadding)
                throw new IOException("Illegal block after padding: " + type);
            int len = (int) DataHelper.fromLong(payload, i, 2);
            i += 2;
            if (i + len > length)
                throw new IOException("Block runs over frame");
            switch (type) {
                case BLOCK_DATETIME:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len != 4)
                        throw new IOException("Bad length for DATETIME: " + len);
                    long time = DataHelper.fromLong(payload, i, 4) * 1000;
                    cb.gotDateTime(time);
                    break;

                case BLOCK_OPTIONS:
                    byte[] options = null;
                    cb.gotOptions(options, isHandshake);
                    break;

                case BLOCK_ROUTERINFO:
                    int flag = payload[i] & 0xff;
                    RouterInfo alice = new RouterInfo();
                    // TODO limit
                    ByteArrayInputStream bais = new ByteArrayInputStream(payload, i + 1, len - 1);
                    alice.readBytes(bais, true);
                    // TODO validate Alice static key, pass back somehow
                    cb.gotRI(alice, isHandshake);
                    gotRI = true;
                    break;

                case BLOCK_I2NP:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    I2NPMessage msg = I2NPMessageImpl.fromRawByteArrayNTCP2(ctx, payload, i, len, null);
                    cb.gotI2NP(msg);
                    break;

                case BLOCK_TERMINATION:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len != 9)
                        throw new IOException("Bad length for TERMINATION: " + len);
                    int rsn = payload[i] & 0xff;
                    cb.gotTermination(rsn);
                    break;

                case BLOCK_PADDING:
                    gotPadding = true;
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
        if (isHandshake && !gotRI)
            throw new IOException("No RI block in handshake");
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

    public static abstract class Block {
        private final int type;

        public Block(int ttype) {
            type = ttype;
        }

        public int write(byte[] tgt, int off) {
            tgt[off++] = (byte) type;
            DataHelper.toLong(tgt, off, 2, getDataLength());
            return writeData(tgt, off + 2);
        }

        public abstract int getDataLength();

        public abstract int writeData(byte[] tgt, int off);
    }

    public static class RIBlock extends Block {
        private final byte[] data;

        public RIBlock(RouterInfo ri) {
            super(BLOCK_ROUTERINFO);
            data = ri.toByteArray();
        }

        public int getDataLength() {
            return 1 + data.length;
        }

        public int writeData(byte[] tgt, int off) {
            tgt[off++] = 0;    // flag
            System.arraycopy(data, 0, tgt, off, data.length);
            return off + data.length;
        }
    }

    public static class I2NPBlock extends Block {
        private final byte[] data;

        public I2NPBlock(I2NPMessage msg) {
            super(BLOCK_I2NP);
            data = msg.toByteArray();
        }

        public int getDataLength() {
            return data.length - 7;
        }

        public int writeData(byte[] tgt, int off) {
            // type, ID, first 4 bytes of exp
            System.arraycopy(data, 0, tgt, off, 9);
            // skip last 4 bytes of exp, sz, checksum
            System.arraycopy(data, 16, tgt, off + 9, data.length - 16);
            return off + data.length - 7;
        }
    }

    public static class PaddingBlock extends Block {
        private final byte[] data;

        public PaddingBlock(I2PAppContext ctx, int size) {
            super(BLOCK_PADDING);
            data = new byte[size];
            if (size > 0)
                ctx.random().nextBytes(data);
        }

        public int getDataLength() {
            return data.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(tgt, off, data, 0, data.length);
            return off + data.length;
        }
    }

    public static class DateTimeBlock extends Block {
        private final long now;

        public DateTimeBlock(I2PAppContext ctx) {
            super(BLOCK_DATETIME);
            now = ctx.clock().now();
        }

        public int getDataLength() {
            return 4;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, now / 1000);
            return off + 4;
        }
    }
}
