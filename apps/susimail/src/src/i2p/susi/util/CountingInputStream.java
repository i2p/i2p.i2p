package i2p.susi.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream that implements ReadCounter.
 *
 * @since 0.9.34
 */
public class CountingInputStream extends FilterInputStream implements ReadCounter {

    protected long count;
    
    /**
     *
     */
    public CountingInputStream(InputStream in) {
        super(in);
    }
    
    @Override
    public long skip(long n) throws IOException {
	long rv = in.skip(n);
        count += rv;
        return rv;
    }
    
    public long getRead() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int rv = in.read();
        if (rv >= 0)
            count++;
        return rv;
    }

    @Override
    public int read(byte buf[], int off, int len) throws IOException {
        int rv = in.read(buf, off, len);
        if (rv > 0)
            count += rv;
        return rv;
    }
    
}
