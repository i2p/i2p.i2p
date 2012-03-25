package net.i2p.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import net.i2p.data.DataHelper;

/**
 * GZIP implementation per 
 * <a href="http://www.faqs.org/rfcs/rfc1952.html">RFC 1952</a>, reusing 
 * java's standard CRC32 and Inflater and InflaterInputStream implementations.
 * The main difference is that this implementation allows its state to be 
 * reset to initial values, and hence reused, while the standard 
 * GZIPInputStream reads the GZIP header from the stream on instantiation.
 *
 */
public class ResettableGZIPInputStream extends InflaterInputStream {
    private static final int FOOTER_SIZE = 8; // CRC32 + ISIZE
    private static final boolean DEBUG = false;
    /** See below for why this is necessary */
    private final ExtraByteInputStream _extraByteInputStream;
    /** keep a typesafe copy of this */
    private final LookaheadInputStream _lookaheadStream;
    private final InputStream _sequenceStream = null;
    private final CRC32 _crc32;
    private final byte _buf1[] = new byte[1];
    private boolean _complete;
    
    /**
     * Build a new GZIP stream without a bound compressed stream.  You need
     * to initialize this with initialize(compressedStream) when you want to
     * decompress a stream.
     */
    public ResettableGZIPInputStream() {
        // compressedStream -> 
        //   LookaheadInputStream that removes last 8 bytes ->
        //     ExtraByteInputStream that adds 1 byte ->
        //       InflaterInputStream
        // See below for why this is necessary
        super(new ExtraByteInputStream(new LookaheadInputStream(FOOTER_SIZE)),
              new Inflater(true));
        _extraByteInputStream = (ExtraByteInputStream)in;
        _lookaheadStream = (LookaheadInputStream)_extraByteInputStream.getInputStream();
        _crc32 = new CRC32();
    }

    /**
     * Warning - blocking!
     */
    public ResettableGZIPInputStream(InputStream compressedStream) throws IOException {
        this();
        initialize(compressedStream);
    }
    
    /**
     * Blocking call to initialize this stream with the data from the given
     * compressed stream.
     *
     */
    public void initialize(InputStream compressedStream) throws IOException {
        len = 0;
        inf.reset();
        _complete = false;
        _crc32.reset();
        _buf1[0] = 0x0;
        _extraByteInputStream.reset();
        // blocking call to read the footer/lookahead, and use the compressed
        // stream as the source for further lookahead bytes
        _lookaheadStream.initialize(compressedStream);
        // now blocking call to read and verify the GZIP header from the
        // lookahead stream
        verifyHeader();
    }
    
    @Override
    public int read() throws IOException {
        int read = read(_buf1, 0, 1);
        if (read == -1)
            return -1;
        return _buf1[0] & 0xff;
    }
    
    @Override
    public int read(byte buf[]) throws IOException {
        return read(buf, 0, buf.length);
    }

    /**
     *
     */
    @Override
    public int read(byte buf[], int off, int len) throws IOException {
        if (_complete) {
            // shortcircuit so the inflater doesn't try to refill 
            // with the footer's data (which would fail, causing ZLIB err)
            return -1;
        }
        int read = super.read(buf, off, len);
        if (read == -1) {
            verifyFooter();
            return -1;
        } else {
            _crc32.update(buf, off, read);
            // NO, we can't do use getEOFReached here
            // 1) Just because the lookahead stream has hit EOF doesn't mean
            //    that the inflater has given us all the data yet,
            //    this would cause data loss at the end
            //if (_lookaheadStream.getEOFReached()) {
            if (inf.finished()) {
                verifyFooter();
                inf.reset(); // so it doesn't bitch about missing data...
                _complete = true;
            }
            return read;
        }
    }
    
    /**
     *  Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream
     *  @since 0.8.9
     */
    public long getTotalRead() {
        try {
            return inf.getBytesRead(); 
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     *  Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream
     *  @since 0.8.9
     */
    public long getTotalExpanded() { 
        try {
            return inf.getBytesWritten(); 
        } catch (Exception e) {
            // possible NPE in some implementations
            return 0;
        }
    }

    /**
     *  Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream
     *  @since 0.8.9
     */
    public long getRemaining() { 
        try {
            return inf.getRemaining(); 
        } catch (Exception e) {
            // possible NPE in some implementations
            return 0;
        }
    }

    /**
     *  Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream
     *  @since 0.8.9
     */
    public boolean getFinished() { 
        try {
            return inf.finished(); 
        } catch (Exception e) {
            // possible NPE in some implementations
            return true;
        }
    }

    /**
     *  Moved from i2ptunnel HTTPResponseOutputStream.InternalGZIPInputStream
     *  @since 0.8.9
     */
    @Override
    public String toString() { 
        return "Read: " + getTotalRead() + " expanded: " + getTotalExpanded() + " remaining: " + getRemaining() + " finished: " + getFinished();
    }

    private long getCurrentCRCVal() { return _crc32.getValue(); }
    
    private void verifyFooter() throws IOException {
        byte footer[] = _lookaheadStream.getFooter();
        
        long actualSize = inf.getTotalOut();
        long expectedSize = DataHelper.fromLongLE(footer, 4, 4);
        if (expectedSize != actualSize)
            throw new IOException("gunzip expected " + expectedSize + " bytes, got " + actualSize);
        
        long actualCRC = _crc32.getValue();
        long expectedCRC = DataHelper.fromLongLE(footer, 0, 4);
        if (expectedCRC != actualCRC)
            throw new IOException("gunzip CRC fail expected 0x" + Long.toHexString(expectedCRC) +
                                  " bytes, got 0x" + Long.toHexString(actualCRC));
    }
    
    /**
     * Make sure the header is valid, throwing an IOException if its b0rked.
     */
    private void verifyHeader() throws IOException {
        int c = in.read();
        if (c != 0x1F) throw new IOException("First magic byte was wrong [" + c + "]");
        c = in.read();
        if (c != 0x8B) throw new IOException("Second magic byte was wrong [" + c + "]");
        c = in.read();
        if (c != 0x08) throw new IOException("Compression format is invalid [" + c + "]");
        
        int flags = in.read();
        
        // snag (and ignore) the MTIME
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME0 [" + c + "]");
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME1 [" + c + "]");
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME2 [" + c + "]");
        c = in.read();
        if (c == -1) throw new IOException("EOF on MTIME3 [" + c + "]");
        
        c = in.read();
        if ( (c != 0x00) && (c != 0x02) && (c != 0x04) ) 
            throw new IOException("Invalid extended flags [" + c + "]");
        
        c = in.read(); // ignore creator OS
        
        // handle flags...
        if (0 != (flags & (1<<5))) {
            // extra header, read and ignore
            int _len = 0;
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the extra header");
            _len = c;
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the extra header");
            _len += (c << 8);
            
            // now skip that data
            for (int i = 0; i < _len; i++) {
                c = in.read();
                if (c == -1) throw new IOException("EOF reading the extra header's body");
            }
        }
        
        if (0 != (flags & (1 << 4))) {
            // ignore the name
            c = in.read();
            while (c != 0) {
                if (c == -1) throw new IOException("EOF reading the name");
                c = in.read();
            }
        }
        
        if (0 != (flags & (1 << 3))) {
            // ignore the comment
            c = in.read();
            while (c != 0) {
                if (c == -1) throw new IOException("EOF reading the comment");
                c = in.read();
            }
        }
        
        if (0 != (flags & (1 << 6))) {
            // ignore the header CRC16 (we still check the body CRC32)
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the CRC16");
            c = in.read();
            if (c == -1) throw new IOException("EOF reading the CRC16");
        }
    }
    
    /**
     *  Essentially a SequenceInputStream(in, new ByteArrayInputStream(new byte[1])),
     *  except that this is resettable.
     *
     *  Unsupported:
     *    - available() doesn't include the extra byte
     *    - skip() doesn't skip the extra byte
     *
     *  Why? otherwise the inflater finished() is wrong when the compressed payload
     *  (in between the 10 byte header and the 8 byte footer) is a multiple of 512 bytes,
     *  which caused read(buf, off, len) above to fail.
     *  Happened every time with 1042 byte compressed router infos, for example.
     *
     *  Details:
     *
     *  Warning with Inflater nowrap = true:
     *
     *     "Note: When using the 'nowrap' option it is also necessary to provide an extra "dummy" byte as input.
     *      This is required by the ZLIB native library in order to support certain optimizations."
     *
     *     http://docs.oracle.com/javase/1.5.0/docs/api/java/util/zip/Inflater.html
     *
     *  This is for sure:
     *
     *     "This is not nearly specific enough to be useful.  Where in the compressed byte array is the
     *      extra 'dummy' byte" expected?  What is it to contain? When calling setInput() is the 'len'
     *      argument incremented to include the dummy byte or not?"
     *
     *     http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4795299
     *
     *  This is useless:
     *
     *     http://www.java-forums.org/new-java/38604-decompress-un-gzip-byte.html
     *
     *  This seems to be the definitive answer:
     *
     *     "The fix simply involves copying the byte array and tacking a single null byte on to the end."
     *
     *     http://code.google.com/p/google-apps-sso-sample/issues/detail?id=8
     *
     *  @since 0.8.12
     */
    private static class ExtraByteInputStream extends FilterInputStream {
        private static final byte DUMMY = 0;
        private boolean _extraSent;

        public ExtraByteInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            if (_extraSent)
                return -1;
            int rv = in.read();
            if (rv >= 0)
                return rv;
            _extraSent = true;
            return DUMMY;
        }

        @Override
        public int read(byte buf[], int off, int len) throws IOException {
            if (len == 0)
                return 0;
            if (_extraSent)
                return -1;
            int rv = in.read(buf, off, len);
            if (rv >= 0)
                return rv;
            _extraSent = true;
            buf[off] = DUMMY;
            return 1;
        }

        @Override
        public void close() throws IOException {
            _extraSent = false;
            in.close();
        }

        /** does NOT call in.reset() */
        @Override
        public void reset() {
            _extraSent = false;
        }

        public InputStream getInputStream() {
            return in;
        }
    }
    
/******
    public static void main(String args[]) {
        for (int i = 129; i < 64*1024; i++) {
            if (!test(i)) return;
        }
        
        byte orig[] = "ho ho ho, merry christmas".getBytes();
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(64);
            java.util.zip.GZIPOutputStream o = new java.util.zip.GZIPOutputStream(baos);
            o.write(orig);
            o.finish();
            o.flush();
            o.close();
            byte compressed[] = baos.toByteArray();
            
            ResettableGZIPInputStream i = new ResettableGZIPInputStream();
            i.initialize(new ByteArrayInputStream(compressed));
            byte readBuf[] = new byte[128];
            int read = i.read(readBuf);
            if (read != orig.length)
                throw new RuntimeException("read=" + read);
            for (int j = 0; j < read; j++)
                if (readBuf[j] != orig[j])
                    throw new RuntimeException("wtf, j=" + j + " readBuf=" + readBuf[j] + " orig=" + orig[j]);
            boolean ok = (-1 == i.read());
            if (!ok) throw new RuntimeException("wtf, not EOF after the data?");
            System.out.println("Match ok");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static boolean test(int size) {
        byte b[] = new byte[size];
        new java.util.Random().nextBytes(b);
        try { 
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(size);
            java.util.zip.GZIPOutputStream o = new java.util.zip.GZIPOutputStream(baos);
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = baos.toByteArray();
            
            ResettableGZIPInputStream in = new ResettableGZIPInputStream(new ByteArrayInputStream(compressed));
            java.io.ByteArrayOutputStream baos2 = new java.io.ByteArrayOutputStream(size);
            byte rbuf[] = new byte[512];
            while (true) {
                int read = in.read(rbuf);
                if (read == -1)
                    break;
                baos2.write(rbuf, 0, read);
            }
            byte rv[] = baos2.toByteArray();
            if (rv.length != b.length)
                throw new RuntimeException("read length: " + rv.length + " expected: " + b.length);
            
            if (!net.i2p.data.DataHelper.eq(rv, 0, b, 0, b.length)) {
                throw new RuntimeException("foo, read=" + rv.length);
            } else {
                System.out.println("match, w00t @ " + size);
                return true;
            }
        } catch (Exception e) { 
            System.out.println("Error dealing with size=" + size + ": " + e.getMessage());
            e.printStackTrace(); 
            return false;
        }
    }
******/
}
