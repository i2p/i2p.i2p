package i2p.susi.util;

/**
 * Count the bytes that have been read or skipped
 *
 * @since 0.9.34
 */
public interface ReadCounter {

    /**
     *  The total number of bytes that have been read or skipped
     */
    public long getRead();
}
