package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import net.i2p.data.DataHelper;

/**
 * Simple lookahead buffer to keep the last K bytes in reserve,
 * configured to easily be reused.  Currently only used by the
 * ResettableGZIPInputStream.
 */
public class LookaheadInputStream extends FilterInputStream {
    private boolean _eofReached;
    private final byte[] _footerLookahead;
    private final int size;
    // Next byte to read.
    private int index;
    private static final InputStream _fakeInputStream = new ByteArrayInputStream(new byte[0]);
    
    /**
     *  Configure a stream that hides a number of bytes from the reader.
     *  The last n bytes will never be available from read(),
     *  they can only be obtained from getFooter().
     *
     *  initialize() MUST be called before doing any read() calls.
     *
     *  @param lookaheadSize how many bytes to hide
     */
    public LookaheadInputStream(int lookaheadSize) {
        super(_fakeInputStream);
        _footerLookahead = new byte[lookaheadSize];
        size = lookaheadSize;
    }
    
    public boolean getEOFReached() { return _eofReached; }
        
    /**
     *  Start the LookaheadInputStream with the given input stream.
     *  Resets everything if the LookaheadInputStream was previously used.
     *  WARNING - blocking until lookaheadSize bytes are read!
     *
     *  @throws IOException if less than lookaheadSize bytes could be read.
     */
    public void initialize(InputStream src) throws IOException {
        in = src;
        _eofReached = false;
        index = 0;
        DataHelper.read(in, _footerLookahead);
    }
    
    @Override
    public int read() throws IOException {
        if (_eofReached) 
            return -1;
        int c = in.read();
        if (c == -1) {
            _eofReached = true;
            return -1;
        }
        int rv = _footerLookahead[index] & 0xff;
        _footerLookahead[index] = (byte)c;
        index++;
        if (index >= size)
            index = 0;
        return rv;
    }

    @Override
    public int read(byte buf[], int off, int len) throws IOException {
        if (_eofReached) 
            return -1;
        for (int i = 0; i < len; i++) {
            int c = read();
            if (c == -1) {
                if (i == 0)
                    return -1;
                else
                    return i;
            } else {
                buf[off+i] = (byte)c;
            }
        }
        return len;
    }
    
    /**
     * Grab the lookahead footer.
     * This will be of size lookaheadsize given in constructor.
     * The last byte received will be in the last byte of the array.
     */
    public byte[] getFooter() {
        if (index == 0)
            return _footerLookahead;
        byte[] rv = new byte[size];
        System.arraycopy(_footerLookahead, index, rv, 0, size - index);
        System.arraycopy(_footerLookahead, 0, rv, size - index, index);
        return rv;
    }
    
    /**
     *  @since 0.9.33
     */
    @Override
    public long skip(long n) throws IOException {
        long rv = 0;
        int c;
        while (rv < n && (c = read()) >= 0) {
            rv++;
        }
        return rv;
    }
    
/*******
    public static void main(String args[]) {
        byte buf[] = new byte[32];
        for (int i = 0; i < 32; i++)
            buf[i] = (byte)i;
        ByteArrayInputStream bais = new ByteArrayInputStream(buf);
        try {
            LookaheadInputStream lis = new LookaheadInputStream(8);
            lis.initialize(bais);
            byte rbuf[] = new byte[32];
            int read = lis.read(rbuf);
            if (read != 24) throw new RuntimeException("Should have stopped (read=" + read + ")");
            for (int i = 0; i < 24; i++)
                if (rbuf[i] != (byte)i)
                    throw new RuntimeException("Error at " + i + " [" + rbuf[i] + "]");
            for (int i = 0; i < 8; i++)
                if (lis.getFooter()[i] != (byte)(i+24))
                    throw new RuntimeException("Error at footer " + i + " [" + lis.getFooter()[i] + "]");
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        for (int i = 9; i < 32*1024; i++) {
            if (!test(i)) {
                System.out.println("Everything is NOT fine at size=" + i);
                break;
            }
        }
        System.out.println("Everything is fine in general");
    }
    
    private static boolean test(int size) {
        byte buf[] = new byte[size];
        new java.util.Random().nextBytes(buf);
        ByteArrayInputStream bais = new ByteArrayInputStream(buf);
        try {
            LookaheadInputStream lis = new LookaheadInputStream(8);
            lis.initialize(bais);
            byte rbuf[] = new byte[size];
            int read = lis.read(rbuf);
            if (read != (size-8)) throw new RuntimeException("Should have stopped (read=" + read + ")");
            for (int i = 0; i < (size-8); i++)
                if (rbuf[i] != buf[i])
                    throw new RuntimeException("Error at " + i + " [" + rbuf[i] + "]");
            for (int i = 0; i < 8; i++)
                if (lis.getFooter()[i] != buf[i+(size-8)])
                    throw new RuntimeException("Error at footer " + i + " [" + lis.getFooter()[i] + "]");
            //System.out.println("Everything is fine at size=" + size);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
******/
}
