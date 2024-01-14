package net.i2p.i2ptunnel.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An OutputStream that limits how many bytes are written
 *
 * @since 0.9.62
 */
public class ByteLimitOutputStream extends LimitOutputStream {
    
    private final long _limit;
    private long _count;

    /**
     *  @param limit greater than zero
     */
    public ByteLimitOutputStream(OutputStream out, DoneCallback done, long limit) {
        super(out, done);
        if (limit <= 0)
            throw new IllegalArgumentException();
        _limit = limit;
    }
    
    @Override
    public void write(byte src[], int off, int len) throws IOException {
        if (len == 0)
            return;
        if (_isDone)
            throw new EOFException("done");
        long togo = _limit - _count;
        boolean last = len >= togo;
        if (last)
            len = (int) togo;
        super.write(src, off, len);
        _count += len;
        if (last)
            setDone();
    }

/*
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: ByteLimitOutputStream length < in > out");
            System.exit(1);
        }
        Test test = new Test();
        long limit = Long.parseLong(args[0]);
        test.test(limit);
    }

    static class Test implements DoneCallback {
        private boolean run = true;

        public void test(long limit) throws Exception {
            LimitOutputStream lout = new ByteLimitOutputStream(System.out, this, limit);
            final byte buf[] = new byte[4096];
            try {
                int read;
                while (run && (read = System.in.read(buf)) != -1) {
                    lout.write(buf, 0, read);
                }   
            } finally {   
                lout.close();
            }   
        }   

        public void streamDone() {
            System.err.println("Done");
            run = false;
        }
    }
*/

}
