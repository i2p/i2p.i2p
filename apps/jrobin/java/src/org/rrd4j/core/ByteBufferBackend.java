package org.rrd4j.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Arrays;

/**
 * A backend that store and provides access to data using a {@link java.nio.ByteBuffer}, using java internal methods for
 * long, integer and others types.
 * 
 * @author Fabrice Bacchella
 * @since 3.4
 *
 */
public abstract class ByteBufferBackend extends RrdBackend {

    private volatile boolean dirty = false;

    private ByteBuffer byteBuffer;

    protected ByteBufferBackend(String path) {
        super(path);
    }

    protected void setByteBuffer(ByteBuffer byteBuffer) {
        this.byteBuffer = byteBuffer;
        byteBuffer.order(BYTEORDER);
    }

    /**
     * Writes bytes to the underlying RRD file on the disk
     *
     * @param offset Starting file offset
     * @param b      Bytes to be written.
     * @throws java.io.IOException if any.
     * @throws java.lang.IllegalArgumentException if offset is bigger that the possible mapping position (2GiB).
     */
    protected synchronized void write(long offset, byte[] b) throws IOException {
        checkOffsetAndByteBuffer(offset);
        byteBuffer.put(b, (int) offset, b.length);
        dirty = true;
    }

    @Override
    protected void writeShort(long offset, short value) throws IOException {
        checkOffsetAndByteBuffer(offset);
        byteBuffer.putShort((int)offset, value);
        dirty = true;
    }

    @Override
    protected void writeInt(long offset, int value) throws IOException {
        checkOffsetAndByteBuffer(offset);
        byteBuffer.putInt((int)offset, value);
        dirty = true;
    }

    @Override
    protected void writeLong(long offset, long value) throws IOException {
        checkOffsetAndByteBuffer(offset);
        byteBuffer.putLong((int)offset, value);
        dirty = true;
    }

    @Override
    protected void writeDouble(long offset, double value) throws IOException {
        checkOffsetAndByteBuffer(offset);
        byteBuffer.putDouble((int)offset, value);
        dirty = true;
    }

    @Override
    protected void writeDouble(long offset, double value, int count)
            throws IOException {
        checkOffsetAndByteBuffer(offset);
        double[] values = new double[count];
        Arrays.fill(values, value);
        // position must be set in the original ByteByffer, as DoubleBuffer uses a "double" offset
        byteBuffer.position((int)offset);
        byteBuffer.asDoubleBuffer().put(values, 0, count);
        dirty = true;
    }

    @Override
    protected void writeDouble(long offset, double[] values) throws IOException {
        checkOffsetAndByteBuffer(offset);
        // position must be set in the original ByteByffer, as DoubleBuffer uses a "double" offset
        byteBuffer.position((int)offset);
        byteBuffer.asDoubleBuffer().put(values, 0, values.length);
        dirty = true;
    }

    @Override
    protected void writeString(long offset, String value, int length) throws IOException {
        checkOffsetAndByteBuffer(offset);
        byteBuffer.position((int)offset);
        CharBuffer cbuff = byteBuffer.asCharBuffer();
        cbuff.limit(length);
        cbuff.put(value);
        while (cbuff.position() < cbuff.limit()) {
            cbuff.put(' ');
        }
        dirty = true;
    }

    /**
     * Reads a number of bytes from the RRD file on the disk
     *
     * @param offset Starting file offset
     * @param b      Buffer which receives bytes read from the file.
     * @throws java.io.IOException Thrown in case of I/O error.
     * @throws java.lang.IllegalArgumentException if offset is bigger that the possible mapping position (2GiB).
     */
    protected synchronized void read(long offset, byte[] b) throws IOException {
        checkOffsetAndByteBuffer(offset);
        byteBuffer.position((int)offset);
        byteBuffer.get(b);
    }

    @Override
    protected short readShort(long offset) throws IOException {
        checkOffsetAndByteBuffer(offset);
        return byteBuffer.getShort((int)offset);
    }

    @Override
    protected int readInt(long offset) throws IOException {
        checkOffsetAndByteBuffer(offset);
        return byteBuffer.getInt((int)offset);
    }

    @Override
    protected long readLong(long offset) throws IOException {
        checkOffsetAndByteBuffer(offset);
        return byteBuffer.getLong((int)offset);
    }

    @Override
    public double readDouble(long offset) throws IOException {
        checkOffsetAndByteBuffer(offset);
        return byteBuffer.getDouble((int)offset);
    }

    @Override
    public double[] readDouble(long offset, int count) throws IOException {
        checkOffsetAndByteBuffer(offset);
        double[] values = new double[count];
        // position must be set in the original ByteByffer, as DoubleBuffer is a "double" offset
        byteBuffer.position((int)offset);
        byteBuffer.asDoubleBuffer().get(values, 0, count);
        return values;
    }

    @Override
    protected CharBuffer getCharBuffer(long offset, int size) throws RrdException {
        checkOffsetAndByteBuffer(offset);
        byteBuffer.position((int)offset);
        CharBuffer cbuffer = byteBuffer.asCharBuffer();
        cbuffer.limit(size);
        return cbuffer;
    }

    protected void close() throws IOException {
        byteBuffer = null;
    }

    /**
     * Ensure that the conversion from long offset to integer offset will not overflow
     * @param offset
     * @throws RrdException 
     */
    private void checkOffsetAndByteBuffer(long offset) throws RrdException {
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal offset: " + offset);
        }
        if (byteBuffer == null) {
            throw new RrdException("Empty rrd");
        }
    }

    protected boolean isDirty() {
        return dirty;
    }

    @Override
    protected void rrdClose() throws IOException {
        super.rrdClose();
        dirty = false;
    }

}
