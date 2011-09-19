package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Simple lookahead buffer to keep the last K bytes in reserve,
 * configured to easily be reused.  Currently only used by the
 * ResettableGZIPInputStream
 *
 */
public class LookaheadInputStream extends FilterInputStream {
    private boolean _eofReached;
    private final byte[] _footerLookahead;
    private static final InputStream _fakeInputStream = new ByteArrayInputStream(new byte[0]);
    
    public LookaheadInputStream(int lookaheadSize) {
        super(_fakeInputStream);
        _footerLookahead = new byte[lookaheadSize];
    }
    
    public boolean getEOFReached() { return _eofReached; }
        
    /** blocking! */
    public void initialize(InputStream src) throws IOException {
        in = src;
        _eofReached = false;
        Arrays.fill(_footerLookahead, (byte)0x00);
        int footerRead = 0;
        while (footerRead < _footerLookahead.length) {
            int read = in.read(_footerLookahead);
            if (read == -1) throw new IOException("EOF reading the footer lookahead");
            footerRead += read;
        }
    }
    
    @Override
    public int read() throws IOException {
        if (_eofReached) 
            return -1; //throw new IOException("Already past the EOF");
        int c = in.read();
        if (c == -1) {
            _eofReached = true;
            return -1;
        }
        int rv = _footerLookahead[0];
        System.arraycopy(_footerLookahead, 1, _footerLookahead, 0, _footerLookahead.length-1);
        _footerLookahead[_footerLookahead.length-1] = (byte)c;
        if (rv < 0) rv += 256;
        return rv;
    }

    @Override
    public int read(byte buf[]) throws IOException {
        return read(buf, 0, buf.length);
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
    
    /** grab the lookahead footer */
    public byte[] getFooter() { return _footerLookahead; }
    
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
            System.out.println("Everything is fine in general");
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        for (int i = 9; i < 32*1024; i++) {
            if (!test(i)) break;
        }
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
            System.out.println("Everything is fine at size=" + size);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
******/
}
