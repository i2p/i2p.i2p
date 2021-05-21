package org.rrd4j.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backend to be used to store all RRD bytes in memory.
 *
 */
public class RrdMemoryBackend extends ByteBufferBackend {

    private final AtomicReference<ByteBuffer> refbb;
    /**
     * <p>Constructor for RrdMemoryBackend.</p>
     *
     * @param path a {@link java.lang.String} object.
     * @param refbb 
     */
    protected RrdMemoryBackend(String path, AtomicReference<ByteBuffer> refbb) {
        super(path);
        this.refbb = refbb;
        Optional.ofNullable(refbb).map(r -> r.get()).ifPresent(this::setByteBuffer);
    }

    @Override
    protected void setLength(long length) throws IOException {
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal length: " + length);
        }
        refbb.set(ByteBuffer.allocate((int) length));
        setByteBuffer(refbb.get());
    }

    @Override
    public long getLength() throws IOException {
        return Optional.ofNullable(refbb.get()).map(ByteBuffer::capacity).orElse(0);
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
