package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.DataHelper;

/**
 * Provide a cache of reusable GZIP streams, each handling up to 32KB without
 * expansion.
 *
 */
public class ReusableGZIPInputStream extends ResettableGZIPInputStream {
    // Apache Harmony 5.0M13 Deflater doesn't work after reset()
    // Neither does Android
    private static final boolean ENABLE_CACHING = !(System.getProperty("java.vendor").startsWith("Apache") ||
                                                    System.getProperty("java.vendor").contains("Android"));
    private static final LinkedBlockingQueue<ReusableGZIPInputStream> _available;
    static {
        if (ENABLE_CACHING)
            _available = new LinkedBlockingQueue(8);
        else
            _available = null;
    }

    /**
     * Pull a cached instance
     */
    public static ReusableGZIPInputStream acquire() {
        ReusableGZIPInputStream rv = null;
        // Apache Harmony 5.0M13 Deflater doesn't work after reset()
        if (ENABLE_CACHING)
            rv = _available.poll();
        if (rv == null) {
            rv = new ReusableGZIPInputStream();
        } 
        return rv;
    }
    /**
     * Release an instance back into the cache (this will reset the 
     * state)
     */
    public static void release(ReusableGZIPInputStream released) {
        if (ENABLE_CACHING)
            _available.offer(released);
    }
    
    private ReusableGZIPInputStream() { super(); }
    
/*******
    public static void main(String args[]) {
        for (int i = 0; i < 2; i++)
            test();
        for (int i = 0; i < 64*1024; i++) {
            if (!test(i)) break;
        }
    }
    private static void test() {
        byte b[] = "hi, how are you today?".getBytes();
        try { 
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
            GZIPOutputStream o = new GZIPOutputStream(baos);
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = baos.toByteArray();
            
            ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
            in.initialize(new ByteArrayInputStream(compressed));
            byte rv[] = new byte[128];
            int read = in.read(rv);
            if (!DataHelper.eq(rv, 0, b, 0, b.length))
                throw new RuntimeException("foo, read=" + read);
            else
                System.out.println("match, w00t");
            ReusableGZIPInputStream.release(in);
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    private static boolean test(int size) {
        byte b[] = new byte[size];
        new java.util.Random().nextBytes(b);
        try { 
            ByteArrayOutputStream baos = new ByteArrayOutputStream(size);
            GZIPOutputStream o = new GZIPOutputStream(baos);
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = baos.toByteArray();
            
            ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
            in.initialize(new ByteArrayInputStream(compressed));
            ByteArrayOutputStream baos2 = new ByteArrayOutputStream(size);
            byte rbuf[] = new byte[128];
            try {
                while (true) {
                    int read = in.read(rbuf);
                    if (read == -1)
                        break;
                    baos2.write(rbuf, 0, read);
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
                long crcVal = in.getCurrentCRCVal();
                //try { in.verifyFooter(); } catch (IOException ioee) {
                //    ioee.printStackTrace();
                //}
                throw ioe;
            } catch (RuntimeException re) {
                re.printStackTrace();
                throw re;
            }
            ReusableGZIPInputStream.release(in);
            byte rv[] = baos2.toByteArray();
            if (rv.length != b.length)
                throw new RuntimeException("read length: " + rv.length + " expected: " + b.length);
            
            if (!DataHelper.eq(rv, 0, b, 0, b.length)) {
                throw new RuntimeException("foo, read=" + rv.length);
            } else {
                System.out.println("match, w00t");
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

