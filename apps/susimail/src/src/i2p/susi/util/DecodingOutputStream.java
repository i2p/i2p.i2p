package i2p.susi.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;


/**
 *  Buffering decoder, with output to a Writer.
 *  Adapted from SAM UTF8Reader.
 *
 *  @since 0.9.34
 */
public class DecodingOutputStream extends OutputStream {

    private final Writer _out;
    private final ByteBuffer _bb;
    private final CharBuffer _cb;
    private final CharsetDecoder _dc;

    // Charset.forName("UTF-8").newDecoder().replacement().charAt(0) & 0xffff
    private static final int REPLACEMENT = 0xfffd;

    /**
     *  @param out UTF-8
     *  @throws UnsupportedEncodingException (an IOException) on unknown charset
     */
    public DecodingOutputStream(Writer out, String charset) throws UnsupportedEncodingException {
        super();
        _out = out;
        try {
            _dc = Charset.forName(charset).newDecoder();
        } catch (IllegalArgumentException iae) {
            UnsupportedEncodingException uee = new UnsupportedEncodingException("Unsupported charset \"" + charset + '"');
            uee.initCause(iae);
            throw uee;
        }
        _bb = ByteBuffer.allocate(1024);
        _cb = CharBuffer.allocate(1024);
    }						

    @Override
    public void write(int b) throws IOException {
        if (!_bb.hasRemaining())
            flush();
        _bb.put((byte) b);
    }

    @Override
    public void write(byte buf[], int off, int len) throws IOException {
	while (len > 0) {
            if (_bb.hasRemaining()) {
	        int toWrite = Math.min(len, _bb.remaining());
    	        _bb.put(buf, off, toWrite);
                len -= toWrite;
            }
            flush();
        }
    }

    private void decodeAndWrite(boolean endOfInput) throws IOException {
        _bb.flip();
	if (!_bb.hasRemaining())
            return;
        CoderResult result;
        try {
            result = _dc.decode(_bb, _cb, endOfInput);
        } catch (IllegalStateException ise) {
            System.out.println("Decoder error with endOfInput=" + endOfInput);
            ise.printStackTrace();
            result = null;
        }
        _bb.compact();
        // Overflow and underflow are not errors.
        // It seems to return underflow every time.
        // So just check if we got a character back in the buffer.
        if (result == null || (result.isError() && !_cb.hasRemaining())) {
            _out.write(REPLACEMENT);
        } else {
            _cb.flip();
            _out.append(_cb);
            _cb.clear();
        }
    }

    @Override
    public void flush() throws IOException {
        decodeAndWrite(false);
    }

    /** Only flushes. Does NOT close the writer */
    @Override
    public void close() throws IOException {
        decodeAndWrite(true);
    }

/****
    public static void main(String[] args) {
        try {
            String s = "Consider the encoding of the Euro sign, €." +
                       " The Unicode code point for \"€\" is U+20AC.";
            byte[] test = s.getBytes("UTF-8");
            InputStream bais = new java.io.ByteArrayInputStream(test);
            DecodingOutputStream r = new DecodingOutputStream(bais);
            int b;
            StringBuilder buf = new StringBuilder(128);
            while ((b = r.write()) >= 0) {
                buf.append((char) b);
            }
            System.out.println("Received: " + buf);
            System.out.println("Test passed? " + buf.toString().equals(s));
            buf.setLength(0);
            bais = new java.io.ByteArrayInputStream(new byte[] { 'x', (byte) 0xcc, 'x' } );
            r = new DecodingOutputStream(bais);
            while ((b = r.write()) >= 0) {
                buf.append((char) b);
            }
            System.out.println("Received: " + buf);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
****/
}
