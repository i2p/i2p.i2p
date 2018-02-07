package i2p.susi.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Limit total reads and skips to a specified maximum, then return EOF
 *
 * @since 0.9.34
 */
public class LimitInputStream extends CountingInputStream {

    private final long maxx;
    
    /**
     *  @param max max number of bytes to read
     */
    public LimitInputStream(InputStream in, long max) {
        super(in);
        if (max < 0)
            throw new IllegalArgumentException("negative limit: " + max);
        maxx = max;
    }
    
    @Override
    public int available() throws IOException {
        return (int) Math.min(maxx - count, super.available());
    }
    
    @Override
    public long skip(long n) throws IOException {
        return super.skip(Math.min(maxx - count, n));
    }
    
    @Override
    public int read() throws IOException {
        if (count >= maxx)
            return -1;
        return super.read();
    }

    @Override
    public int read(byte buf[], int off, int len) throws IOException {
        if (count >= maxx)
            return -1;
        return super.read(buf, off, (int) Math.min(maxx - count, len));
    }
    
/****
    public static void main(String[] args) {
        try {
            LimitInputStream lim = new LimitInputStream(new java.io.ByteArrayInputStream(new byte[20]), 5);
            lim.read();
            lim.skip(2);
            byte[] out = new byte[10];
            int read = lim.read(out);
            if (read != 2)
                System.out.println("LIS test failed, read " + read);
            else if (lim.getRead() != 5)
                System.out.println("CIS test failed, read " + lim.getRead());
            else
                System.out.println("LIS/CIS test passed");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
****/
}
