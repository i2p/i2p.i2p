package org.rrd4j.core;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Backend to be used to store all RRD bytes in memory.
 *
 */
public class RrdMemoryBackend extends ByteBufferBackend {

    private ByteBuffer dbb = null;
    /**
     * <p>Constructor for RrdMemoryBackend.</p>
     *
     * @param path a {@link java.lang.String} object.
     */
    protected RrdMemoryBackend(String path) {
        super(path);
    }

    @Override
    protected void setLength(long length) throws IOException {
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal length: " + length);
        }
        dbb = ByteBuffer.allocate((int) length);
        setByteBuffer(dbb);
    }

    @Override
    public long getLength() throws IOException {
        return dbb.capacity();
    }

    /**
     * This method is required by the base class definition, but it does not
     * releases any memory resources at all.
     *
     * @throws java.io.IOException if any.
     */
    @Override
    protected void close() throws IOException {
        // Don't release ressources, as backend are cached by the factory and reused
    }

}
