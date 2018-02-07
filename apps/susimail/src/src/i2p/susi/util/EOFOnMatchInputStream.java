package i2p.susi.util;

import java.io.PushbackInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import net.i2p.data.DataHelper;

/**
 *  A stream that returns EOF when the input matches
 *  the bytes provided. The reader will never see any bytes
 *  from a full match.
 *
 *  We extend PushbackInputStream for convenience,
 *  but we use its buffer as a fifo, not a stack.
 *  Do not call the unread() methods externally.
 *
 *  @since 0.9.34
 */
public class EOFOnMatchInputStream extends PushbackInputStream implements ReadCounter {
    private final byte[] match;
    private final int size;
    private final ReadCounter cis;

    /**
     *  Non-counter mode. getRead() will return 0.
     *  @param match will be copied
     */
    public EOFOnMatchInputStream(InputStream in, byte[] match) {
        this(in, null, match);
    }

    /**
     *  Counter mode. getRead() will the ReadCounter's value, not including the match bytes.
     *  @param match will be copied
     */
    public EOFOnMatchInputStream(InputStream in, ReadCounter ctr, byte[] match) {
        super(in, match.length);
        size = match.length;
        if (size <= 0)
            throw new IllegalArgumentException();
        // buf grows down, so flip for easy matching
        this.match = reverse(match);
        cis = ctr;
    }

    private static byte[] reverse(byte[] m) {
        int j = m.length;
        byte[] rv = new byte[j];
        for (int i = 0; i < m.length; i++) {
            rv[--j] = m[i];
        }
        return rv;
    }

    /**
     *  If constructed with a counter, returns the count
     *  (not necessarily starting at 0) minus the buffered/matched count.
     *  Otherwise returns 0.
     */
    public long getRead() {
        if (cis != null)
        	return cis.getRead() - (size - pos);
        return 0;
    }

    /**
     *  @return true if we returned EOF because we hit the match
     */
    public boolean wasFound() {
        return pos <= 0; 
    }

    /**
     *  Debug only. Return the number of bytes currently in the buffer.
     *
     *  @return number of bytes buffered
     */
/*
    public int getBuffered() {
        return size - pos;
    }
*/

    /**
     *  Debug only. Return the buffer.
     *
     *  @return the buffer
     */
/*
    public byte[] getBuffer() {
        int len = getBuffered();
        byte[] b = new byte[len];
        if (len <= 0)
            return b;
        System.arraycopy(buf, pos, b, 0, len);
        return reverse(b);
    }
*/

    @Override
    public int read() throws IOException {
        if (pos <= 0) 
            return -1;
        while(true) {
            // read, pushback, compare
            int c = in.read();
            if (c < 0) {
                if (pos < size)
                    return pop();
                return -1;
            }
            if (pos >= size) {
                // common case, buf is empty, no match
                if (c != (match[size - 1] & 0xff))
                    return c;
                // push first byte into buf, go around again
                unread(c);
                continue;
            }
            unread(c);
            if (!DataHelper.eq(buf, pos, match, pos, size - pos)) {
                return pop();
            }
            // partial or full match
            if (pos <= 0) 
                return -1;   // full match
            // partial match, go around again
        }
    }

    /**
     *  FIFO output. Pop the oldest (not the newest).
     *  Only call if pos < size.
     *  We never call super.read(), it returns the newest.
     */
    private int pop() {
        // return oldest, shift up
        int rv = buf[size - 1] & 0xff;
        for (int i = size - 1; i > pos; i--) {
            buf[i] = buf[i - 1];
        }
        pos++;
        return rv;
    }

    @Override
    public int read(byte buf[], int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            int c = read();
            if (c == -1) {
                if (i == 0)
                    return -1;
                return i;
            }
            buf[off++] = (byte)c;
        }
        return len;
    }

    @Override
    public long skip(long n) throws IOException {
        long rv = 0;
        int c;
        while (rv < n && (c = read()) >= 0) {
            rv++;
        }
        return rv;
    }

/****
    public static void main(String[] args) {
        String match = "xxa";
        String test = "xxbxaxoaaxxyyyyyyxxxazzzz"; 
        byte[] m = DataHelper.getASCII(match);
        byte[] in = DataHelper.getASCII(test); 
        try {
            InputStream eof = new EOFOnMatchInputStream(new java.io.ByteArrayInputStream(in), m);
            byte[] out = new byte[in.length + 10];
            int read = eof.read(out);
            if (read != test.indexOf(match))
                System.out.println("EOFOMIS test failed, read " + read);
            else if (!DataHelper.eq(in, 0, out, 0, read))
                System.out.println("EOFOMIS test failed, bad data");
            else
                System.out.println("EOFOMIS test passed");
        } catch (Exception e) {
            System.out.println("EOFOMIS test failed");
            e.printStackTrace();
        }
    }
****/
}    
