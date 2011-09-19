package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.data.DataHelper;

/**
 * GZIP implementation per 
 * <a href="http://www.faqs.org/rfcs/rfc1952.html">RFC 1952</a>, reusing 
 * java's standard CRC32 and Deflater implementations.  The main difference
 * is that this implementation allows its state to be reset to initial 
 * values, and hence reused, while the standard GZIPOutputStream writes the
 * GZIP header to the stream on instantiation, rather than on first write. 
 *
 */
public class ResettableGZIPOutputStream extends DeflaterOutputStream {
    /** has the header been written out yet? */
    private boolean _headerWritten;
    /** how much data is in the uncompressed stream? */
    private long _writtenSize;
    private final CRC32 _crc32;
    private static final boolean DEBUG = false;
    
    public ResettableGZIPOutputStream(OutputStream o) {
        super(o, new Deflater(9, true));
        _crc32 = new CRC32();
    }

    /**
     * Reinitialze everything so we can write a brand new gzip output stream
     * again.
     */
    public void reset() { 
        if (DEBUG)
            System.out.println("Resetting (writtenSize=" + _writtenSize + ")");
        def.reset();
        _crc32.reset();
        _writtenSize = 0;
        _headerWritten = false;
    }
    
    private static final byte[] HEADER = new byte[] {
        (byte)0x1F, (byte)0x8b, // magic bytes 
        0x08,                   // compression format == DEFLATE
        0x00,                   // flags (NOT using CRC16, filename, etc)
        0x00, 0x00, 0x00, 0x00, // no modification time available (don't leak this!)
        0x02,                   // maximum compression
        (byte)0xFF              // unknown creator OS (!!!)
    };
    
    /**
     * obviously not threadsafe, but its a stream, thats standard
     */
    private void ensureHeaderIsWritten() throws IOException {
        if (_headerWritten) return;
        if (DEBUG) System.out.println("Writing header");
        out.write(HEADER);
        _headerWritten = true;
    }
    
    private void writeFooter() throws IOException {
        // damn RFC writing their bytes backwards...
        long crcVal = _crc32.getValue();
        out.write((int)(crcVal & 0xFF));
        out.write((int)((crcVal >>> 8) & 0xFF));
        out.write((int)((crcVal >>> 16) & 0xFF));
        out.write((int)((crcVal >>> 24) & 0xFF));
        
        long sizeVal = _writtenSize; // % (1 << 31) // *redundant*
        out.write((int)(sizeVal & 0xFF));
        out.write((int)((sizeVal >>> 8) & 0xFF));
        out.write((int)((sizeVal >>> 16) & 0xFF));
        out.write((int)((sizeVal >>> 24) & 0xFF));
        out.flush();
        if (DEBUG) {
            System.out.println("Footer written: crcVal=" + crcVal + " sizeVal=" + sizeVal + " written=" + _writtenSize);
            System.out.println("size hex: " + Long.toHexString(sizeVal));
            System.out.print(  "size2 hex:" + Long.toHexString((int)(sizeVal & 0xFF)));
            System.out.print(  Long.toHexString((int)((sizeVal >>> 8) & 0xFF)));
            System.out.print(  Long.toHexString((int)((sizeVal >>> 16) & 0xFF)));
            System.out.print(  Long.toHexString((int)((sizeVal >>> 24) & 0xFF)));
            System.out.println();
        }
    }
    
    @Override
    public void close() throws IOException {
        finish();
        super.close();
    }
    @Override
    public void finish() throws IOException {
        ensureHeaderIsWritten();
        super.finish();
        writeFooter();
    }
    
    @Override
    public void write(int b) throws IOException {
        ensureHeaderIsWritten();
        _crc32.update(b);
        _writtenSize++;
        super.write(b);
    }
    @Override
    public void write(byte buf[]) throws IOException {
        write(buf, 0, buf.length);
    }
    @Override
    public void write(byte buf[], int off, int len) throws IOException {
        ensureHeaderIsWritten();
        _crc32.update(buf, off, len);
        _writtenSize += len;
        super.write(buf, off, len);
    }
    
/******
    public static void main(String args[]) {
        for (int i = 0; i < 2; i++)
            test();
    }
    private static void test() {
        byte b[] = "hi, how are you today?".getBytes();
        try { 
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
            ResettableGZIPOutputStream o = new ResettableGZIPOutputStream(baos);
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = baos.toByteArray();
            
            ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
            SnoopGZIPOutputStream gzo = new SnoopGZIPOutputStream(baos2);
            gzo.write(b);
            gzo.finish();
            gzo.flush();
            long value = gzo.getCRC().getValue();
            byte compressed2[] = baos2.toByteArray();
            System.out.println("CRC32 values: Resettable = " + o._crc32.getValue() 
                               + " GZIP = " + value);
            
            System.out.print("Resettable compressed data: ");
            for (int i = 0; i < compressed.length; i++)
                System.out.print(Integer.toHexString(compressed[i] & 0xFF) + " ");
            System.out.println();
            System.out.print("      GZIP compressed data: ");
            for (int i = 0; i < compressed2.length; i++)
                System.out.print(Integer.toHexString(compressed2[i] & 0xFF) + " ");
            System.out.println();
            
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
            byte rv[] = new byte[128];
            int read = in.read(rv);
            if (!DataHelper.eq(rv, 0, b, 0, b.length))
                throw new RuntimeException("foo, read=" + read);
            else
                System.out.println("match, w00t");
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    // just for testing/verification, expose the CRC32 values
    private static final class SnoopGZIPOutputStream extends GZIPOutputStream {
        public SnoopGZIPOutputStream(OutputStream o) throws IOException {
            super(o);
        }
        public CRC32 getCRC() { return crc; }
    }
******/
}

