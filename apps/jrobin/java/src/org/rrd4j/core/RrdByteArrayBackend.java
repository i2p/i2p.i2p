package org.rrd4j.core;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Abstract byte array based backend.
 *
 */
public abstract class RrdByteArrayBackend extends ByteBufferBackend {

    private byte[] buffer;

    /**
     * <p>Constructor for RrdByteArrayBackend.</p>
     *
     * @param path a {@link java.lang.String} object.
     */
    protected RrdByteArrayBackend(String path) {
        super(path);
    }

    protected void setBuffer(byte[] buffer) {
        this.buffer = buffer;
        setByteBuffer(ByteBuffer.wrap(buffer));
    }

    protected byte[] getBuffer() {
        return buffer;
    }

    /**
     * <p>read.</p>
     *
     * @param offset a long.
     * @param bytes an array of byte.
     * @throws java.io.IOException if any.
     * @throws java.lang.IllegalArgumentException if offset is bigger that the possible length.
     */
    @Override
    protected synchronized void read(long offset, byte[] bytes) throws IOException {
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal offset: " + offset);
        }

        if (offset + bytes.length <= buffer.length) {
            System.arraycopy(buffer, (int) offset, bytes, 0, bytes.length);
        }
        else {
            throw new RrdBackendException("Not enough bytes available in RRD buffer; RRD " + getPath());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return Number of RRD bytes held in memory.
     */
    public long getLength() {
        return buffer.length;
    }

    /**
     * {@inheritDoc}
     *
     * <p>It will reserves a memory section as a RRD storage.</p>
     *
     */
    protected void setLength(long length) {
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal length: " + length);
        }

        buffer = new byte[(int) length];
        setByteBuffer(ByteBuffer.wrap(buffer));
    }

}
