package net.i2p.util;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;
import net.i2p.data.DataHelper;

/**
 * Provide a cache of reusable GZIP streams, each handling up to 32KB without
 * expansion.
 *
 */
public class ReusableGZIPOutputStream extends ResettableGZIPOutputStream {
    private static ArrayList _available = new ArrayList(16);
    /**
     * Pull a cached instance
     */
    public static ReusableGZIPOutputStream acquire() {
        ReusableGZIPOutputStream rv = null;
        synchronized (_available) {
            if (_available.size() > 0)
                rv = (ReusableGZIPOutputStream)_available.remove(0);
        }
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
        synchronized (_available) {
            if (_available.size() < 16)
                _available.add(out);
        }
    }
    
    private ByteArrayOutputStream _buffer = null;
    private ReusableGZIPOutputStream() {
        super(new ByteArrayOutputStream(40*1024));
        _buffer = (ByteArrayOutputStream)out;
    }
    /** clear the data so we can start again afresh */
    public void reset() { 
        super.reset();
        _buffer.reset();
    }
    /** pull the contents of the stream written */
    public byte[] getData() { return _buffer.toByteArray(); }
    
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
}

