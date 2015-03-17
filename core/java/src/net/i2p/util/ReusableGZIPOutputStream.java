package net.i2p.util;

//import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.DataHelper;

/**
 * Provide a cache of reusable GZIP streams, each handling up to 40 KB output without
 * expansion.
 *
 * This compresses to memory only. Retrieve the compressed data with getData().
 * There is no facility to compress to an output stream.
 *
 * Do NOT use this for compression of unlimited-size data, as it will
 * expand, but never release, the BAOS memory buffer.
 */
public class ReusableGZIPOutputStream extends ResettableGZIPOutputStream {
    // Apache Harmony 5.0M13 Deflater doesn't work after reset()
    // Neither does Android
    private static final boolean ENABLE_CACHING = !(System.getProperty("java.vendor").startsWith("Apache") ||
                                                    System.getProperty("java.vendor").contains("Android"));
    private static final LinkedBlockingQueue<ReusableGZIPOutputStream> _available;
    static {
        if (ENABLE_CACHING)
            _available = new LinkedBlockingQueue(16);
        else
            _available = null;
    }

    /**
     * Pull a cached instance
     */
    public static ReusableGZIPOutputStream acquire() {
        ReusableGZIPOutputStream rv = null;
        if (ENABLE_CACHING)
            rv = _available.poll();
        if (rv == null) {
            rv = new ReusableGZIPOutputStream();
        } 
        return rv;
    }
    
    /**
     * Release an instance back into the cache (this will discard any
     * state)
     */
    public static void release(ReusableGZIPOutputStream out) {
        out.reset();
        if (ENABLE_CACHING)
            _available.offer(out);
    }
    
    private final ByteArrayOutputStream _buffer;

    private ReusableGZIPOutputStream() {
        super(new ByteArrayOutputStream(DataHelper.MAX_UNCOMPRESSED));
        _buffer = (ByteArrayOutputStream)out;
    }

    /** clear the data so we can start again afresh */
    @Override
    public void reset() { 
        super.reset();
        _buffer.reset();
        def.setLevel(Deflater.BEST_COMPRESSION);
    }

    public void setLevel(int level) { 
        def.setLevel(level);
    }

    /** pull the contents of the stream written */
    public byte[] getData() { return _buffer.toByteArray(); }
    
/******
    public static void main(String args[]) {
        try {
            for (int i = 0; i < 2; i++)
                test();
            for (int i = 500; i < 64*1024; i++) {
                if (!test(i)) break;
            }
        } catch (Exception e) { e.printStackTrace(); }
        try { Thread.sleep(10*1000); } catch (InterruptedException ie){}
        System.out.println("After all tests are complete...");
    }
    private static void test() {
        byte b[] = "hi, how are you today?".getBytes();
        try { 
            ReusableGZIPOutputStream o = ReusableGZIPOutputStream.acquire();
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = o.getData();
            ReusableGZIPOutputStream.release(o);
            
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
            byte rv[] = new byte[128];
            int read = in.read(rv);
            if (!DataHelper.eq(rv, 0, b, 0, b.length))
                throw new RuntimeException("foo, read=" + read);
            else
                System.out.println("match, w00t");
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    private static boolean test(int size) {
        byte b[] = new byte[size];
        new java.util.Random().nextBytes(b);
        try {
            ReusableGZIPOutputStream o = ReusableGZIPOutputStream.acquire();
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = o.getData();
            ReusableGZIPOutputStream.release(o);
            
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
            ByteArrayOutputStream baos2 = new ByteArrayOutputStream(256*1024);
            byte rbuf[] = new byte[128];
            while (true) {
                int read = in.read(rbuf);
                if (read == -1)
                    break;
                baos2.write(rbuf, 0, read);
            }
            byte rv[] = baos2.toByteArray();
            if (!DataHelper.eq(rv, 0, b, 0, b.length)) {
                throw new RuntimeException("foo, read=" + rv.length);
            } else {
                System.out.println("match, w00t @ " + size);
                return true;
            }
        } catch (Exception e) { 
            System.out.println("Error on size=" + size + ": " + e.getMessage());
            e.printStackTrace(); 
            return false;
        }
    }
*****/
}

