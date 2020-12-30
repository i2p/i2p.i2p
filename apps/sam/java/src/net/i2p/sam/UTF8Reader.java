package net.i2p.sam;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;


/**
 *  An unbuffered version of InputStreamReader.
 *
 *  Does not read any extra characters, as long as input is well-formed.
 *  This permits the partial reading of an InputStream as UTF-8
 *  and then passing the remainder of the input stream elsewhere.
 *  This isn't the most robust for malformed input, so it
 *  may not be appropriate for e.g. HTTP headers.
 *
 *  Not thread-safe, obviously.
 *
 *  May be moved to net.i2p.util if anybody else needs it.
 *
 *  @since 0.9.24 somewhat adapted from net.i2p.util.TranslateReader
 */
public class UTF8Reader extends Reader {

    private final InputStream _in;
    // following three are lazily initialized when needed
    private ByteBuffer _bb;
    private CharBuffer _cb;
    private CharsetDecoder _dc;

    // Charset.forName("UTF-8").newDecoder().replacement().charAt(0) & 0xffff
    private static final int REPLACEMENT = 0xfffd;

    /**
     *  @param in UTF-8
     */
    public UTF8Reader(InputStream in) {
        super();
        _in = in;
    }

    /**
     *  @return replacement character on decoding error
     */
    @Override
    public int read() throws IOException {
        int b = _in.read();
        if (b < 0)
            return b;
        // https://en.wikipedia.org/wiki/Utf-8
        if ((b & 0x80) == 0)
            return b;
        if (_bb == null) {
            _bb = ByteBuffer.allocate(6);
            _cb = CharBuffer.allocate(1);
            _dc = Charset.forName("UTF-8").newDecoder();
        } else {
            ((Buffer)_bb).clear();
            ((Buffer)_cb).clear();
        }
        _bb.put((byte) b);
        int end;  // how many more
        if ((b & 0xe0) == 0xc0)
            end = 1;
        else if ((b & 0xf0) == 0xe0)
            end = 2;
        else if ((b & 0xf8) == 0xf0)
            end = 3;
        else if ((b & 0xfc) == 0xf8)
            end = 4;
        else if ((b & 0xfe) == 0xfc)
            end = 5;
        else  //  error, 10xxxxxx
            return REPLACEMENT;
        for (int i = 0; i < end; i++) {
            b = _in.read();
            if (b < 0)
                return REPLACEMENT;  // next read will return EOF
            // we aren't going to check for all errors,
            // but let's fail fast on this one
            if ((b & 0x80) == 0)
                return REPLACEMENT;
            _bb.put((byte) b);
        }
        _dc.reset();
        // not ByteBuffer to avoid Java 8/9 issues with flip()
        ((Buffer)_bb).flip();
        CoderResult result = _dc.decode(_bb, _cb, true);
        // Overflow and underflow are not errors.
        // It seems to return underflow every time.
        // So just check if we got a character back in the buffer.
        ((Buffer)_cb).flip();
        if (result.isError() || !_cb.hasRemaining())
            return REPLACEMENT;
        // let underflow and overflow go, return first
        return _cb.get() & 0xffff;
    }

    @Override
    public int read(char cbuf[]) throws IOException {
        return read(cbuf, 0, cbuf.length);
    }

    public int read(char cbuf[], int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            int c = read();
            if (c < 0) {
                if (i == 0)
                    return -1;
                return i;
            }
            cbuf[off + i] = (char) c;
        }
        return len;
    }

    public void close() throws IOException {
        _in.close();
    }

/****
    public static void main(String[] args) {
        try {
            String s = "Consider the encoding of the Euro sign, €." +
                       " The Unicode code point for \"€\" is U+20AC.";
            byte[] test = s.getBytes("UTF-8");
            InputStream bais = new java.io.ByteArrayInputStream(test);
            UTF8Reader r = new UTF8Reader(bais);
            int b;
            StringBuilder buf = new StringBuilder(128);
            while ((b = r.read()) >= 0) {
                buf.append((char) b);
            }
            System.out.println("Received: " + buf);
            System.out.println("Test passed? " + buf.toString().equals(s));
            buf.setLength(0);
            bais = new java.io.ByteArrayInputStream(new byte[] { 'x', (byte) 0xcc, 'x' } );
            r = new UTF8Reader(bais);
            while ((b = r.read()) >= 0) {
                buf.append((char) b);
            }
            System.out.println("Received: " + buf);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
****/
}
