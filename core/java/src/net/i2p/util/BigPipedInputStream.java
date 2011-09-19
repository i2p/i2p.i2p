package net.i2p.util;

import java.io.PipedInputStream;

/**
 *  Java 1.5 PipedInputStream buffers are only 1024 bytes; our I2CP messages are typically 1730 bytes,
 *  thus causing thread blockage before the whole message is transferred.
 *  We can specify buffer size in 1.6 but not in 1.5.
 *
 *  Until we switch to Java 1.6 -
 *  http://javatechniques.com/blog/low-memory-deep-copy-technique-for-java-objects/
 *
 *  Moved from InternalServerSocket.
 *  @since 0.8.9
 */
public class BigPipedInputStream extends PipedInputStream {

    private static final boolean oneDotSix =
        (new VersionComparator()).compare(System.getProperty("java.version"), "1.6") >= 0;

    private static final int PIPE_SIZE = 64*1024;

    private BigPipedInputStream(int size) {
         super();
         buffer = new byte[size];
    }

    /** default size 64K */
    public static PipedInputStream getInstance() {
        return getInstance(PIPE_SIZE);
    }

    public static PipedInputStream getInstance(int size) {
        if (oneDotSix) {
            try {
                return new PipedInputStream(size);
            } catch (Throwable t) {
                // NoSuchMethodException or NoSuchMethodError if we somehow got the
                // version detection wrong or the JVM doesn't support it
            }
        }
        return new BigPipedInputStream(size);
    }
}
