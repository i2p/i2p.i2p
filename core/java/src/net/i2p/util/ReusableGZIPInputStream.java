package net.i2p.util;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Provide a cache of reusable GZIP unzipper streams.
 * This provides stream output only, and therefore can handle unlimited size.
 */
public class ReusableGZIPInputStream extends ResettableGZIPInputStream {
    // Apache Harmony 5.0M13 Deflater doesn't work after reset()
    // Neither does Android
    private static final boolean ENABLE_CACHING = !(SystemVersion.isApache() ||
                                                    SystemVersion.isAndroid());
    private static final LinkedBlockingQueue<ReusableGZIPInputStream> _available;
    static {
        if (ENABLE_CACHING)
            _available = new LinkedBlockingQueue<ReusableGZIPInputStream>(8);
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
    
    /**
     *  Clear the cache.
     *  @since 0.9.21
     */
    public static void clearCache() {
        if (_available != null)
            _available.clear();
    }

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
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(64);
            ResettableGZIPOutputStream o = new ResettableGZIPOutputStream(baos);
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = baos.toByteArray();
            
            ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
            in.initialize(new java.io.ByteArrayInputStream(compressed));
            byte rv[] = new byte[128];
            int read = in.read(rv);
            if (!net.i2p.data.DataHelper.eq(rv, 0, b, 0, b.length))
                throw new RuntimeException("foo, read=" + read);
            else
                System.out.println("match, w00t");
            ReusableGZIPInputStream.release(in);
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    private static boolean test(int size) {
        byte b[] = new byte[size];
        RandomSource.getInstance().nextBytes(b);
        try { 
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(size);
            ResettableGZIPOutputStream o = new ResettableGZIPOutputStream(baos);
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = baos.toByteArray();
            
            ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
            in.initialize(new java.io.ByteArrayInputStream(compressed));
            java.io.ByteArrayOutputStream baos2 = new java.io.ByteArrayOutputStream(size);
            byte rbuf[] = new byte[128];
            try {
                while (true) {
                    int read = in.read(rbuf);
                    if (read == -1)
                        break;
                    baos2.write(rbuf, 0, read);
                }
            } catch (java.io.IOException ioe) {
                ioe.printStackTrace();
                //long crcVal = in.getCurrentCRCVal();
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

