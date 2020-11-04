package net.i2p.util;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

/**
 *  OutputStream to InputStream adapter.
 *  Zero-copy where possible. Unsynchronized.
 *  This is NOT a Pipe.
 *  Do NOT reset after writing.
 *
 *  @since 0.9.48
 */
public class ByteArrayStream extends ByteArrayOutputStream {

    public ByteArrayStream() {
        super();
    }

    /**
     *  @param size if accurate, toByteArray() will be zero-copy
     */
    public ByteArrayStream(int size) {
        super(size);
    }

    /**
     *  @throws IllegalStateException if previously written
     */
    @Override
    public void reset() {
        if (count > 0)
            throw new IllegalStateException();
    }

    /**
     *  Zero-copy only if the data fills the buffer.
     *  Use asInputStream() for guaranteed zero-copy.
     */
    @Override
    public byte[] toByteArray() {
        if (count == buf.length)
            return buf;
        return Arrays.copyOfRange(buf, 0, count);
    }

    /**
     *  All data previously written. Zero-copy. Not a Pipe.
     *  Data written after this call will not appear.
     */
    public ByteArrayInputStream asInputStream() {
        return new ByteArrayInputStream(buf, 0, count);
    }
}
