package i2p.susi.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An OutputStream that counts how many bytes are written
 * and returns the count via getWritten().
 *
 * @since 0.9.34
 */
public class CountingOutputStream extends FilterOutputStream {
    
    private long count;

    public CountingOutputStream(OutputStream out) {
        super(out);
    }
    
    public long getWritten() {
        return count;
    }

    @Override
    public void write(int val) throws IOException {
        out.write(val);
        count++;
    }

    @Override
    public void write(byte src[], int off, int len) throws IOException {
        out.write(src, off, len);
        count += len;
    }
}
