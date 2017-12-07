package net.i2p.util;

import java.io.PipedInputStream;

/**
 *  We are now Java 6 minimum. Just use PipedInputStream.
 *
 *  Java 1.5 PipedInputStream buffers are only 1024 bytes; our I2CP messages are typically 1730 bytes,
 *  thus causing thread blockage before the whole message is transferred.
 *  We can specify buffer size in 1.6 but not in 1.5.
 *
 *  Until we switch to Java 1.6 -
 *  http://javatechniques.com/blog/low-memory-deep-copy-technique-for-java-objects/
 *
 *  Moved from InternalServerSocket.
 *  @since 0.8.9
 *  @deprecated scheduled for removal in 0.9.34
 */
@Deprecated
public class BigPipedInputStream extends PipedInputStream {

    private static final int PIPE_SIZE = 64*1024;

    /** default size 64K */
    public static PipedInputStream getInstance() {
        return getInstance(PIPE_SIZE);
    }

    public static PipedInputStream getInstance(int size) {
        return new PipedInputStream(size);
    }
}
