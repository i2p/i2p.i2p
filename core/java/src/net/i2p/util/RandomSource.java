package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EntropyHarvester;
import net.i2p.data.Base64;

/**
 * Singleton for whatever PRNG i2p uses.  
 *
 * @author jrandom
 */
public class RandomSource extends SecureRandom implements EntropyHarvester {
    private final EntropyHarvester _entropyHarvester;
    protected final I2PAppContext _context;

    public RandomSource(I2PAppContext context) {
        super();
        _context = context;
        // when we replace to have hooks for fortuna (etc), replace with
        // a factory (or just a factory method)
        _entropyHarvester = this;
    }

    /**
     * Singleton for whatever PRNG i2p uses.  
     * Same as I2PAppContext.getGlobalContext().random();
     * use context.random() if you have a context already.
     * @return I2PAppContext.getGlobalContext().random()
     */
    public static RandomSource getInstance() {
        return I2PAppContext.getGlobalContext().random();
    }

    /**
     * According to the java docs (http://java.sun.com/j2se/1.4.1/docs/api/java/util/Random.html#nextInt(int))
     * nextInt(n) should return a number between 0 and n (including 0 and excluding n).  However, their pseudocode,
     * as well as sun's, kaffe's, and classpath's implementation INCLUDES NEGATIVE VALUES.
     * WTF.  Ok, so we're going to have it return between 0 and n (including 0, excluding n), since 
     * thats what it has been used for.
     *
     */
    @Override
    public int nextInt(int n) {
        if (n == 0) return 0;
        int val = super.nextInt(n);
        if (val < 0) val = 0 - val;
        if (val >= n) val = val % n;
        return val;
    }

    /**
     * Like the modified nextInt, nextLong(n) returns a random number from 0 through n,
     * including 0, excluding n.
     */
    public long nextLong(long n) {
        long v = super.nextLong();
        if (v < 0) v = 0 - v;
        if (v >= n) v = v % n;
        return v;
    }

    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)

    @Override
    public boolean nextBoolean() { return super.nextBoolean(); }

    @Override
    public void nextBytes(byte buf[]) { super.nextBytes(buf); }

    @Override
    public double nextDouble() { return super.nextDouble(); }

    @Override
    public float nextFloat() { return super.nextFloat(); }

    @Override
    public double nextGaussian() { return super.nextGaussian(); }

    @Override
    public int nextInt() { return super.nextInt(); }

    @Override
    public long nextLong() { return super.nextLong(); }
*****/
   
    /** */
    public EntropyHarvester harvester() { return _entropyHarvester; }
 
    public void feedEntropy(String source, long data, int bitoffset, int bits) {
        if (bitoffset == 0)
            setSeed(data);
    }
    
    public void feedEntropy(String source, byte[] data, int offset, int len) {
        if ( (offset == 0) && (len == data.length) ) {
            setSeed(data);
        } else {
            setSeed(_context.sha().calculateHash(data, offset, len).getData());
        }
    }

    public void loadSeed() {
        byte buf[] = new byte[1024];
        if (initSeed(buf))
            setSeed(buf);
    }

    public void saveSeed() {
        byte buf[] = new byte[1024];
        nextBytes(buf);
        writeSeed(buf);
    }
    
    private static final String SEEDFILE = "prngseed.rnd";
    
    public static final void writeSeed(byte buf[]) {
        File f = new File(I2PAppContext.getGlobalContext().getConfigDir(), SEEDFILE);
        FileOutputStream fos = null;
        try {
            fos = new SecureFileOutputStream(f);
            fos.write(buf);
        } catch (IOException ioe) {
            // ignore
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
 
    public final boolean initSeed(byte buf[]) {
        // why urandom?  because /dev/random blocks, and there are arguments
        // suggesting such blockages are largely meaningless
        boolean ok = seedFromFile("/dev/urandom", buf);
        // we merge (XOR) in the data from /dev/urandom with our own seedfile
        ok = seedFromFile("prngseed.rnd", buf) || ok;
        return ok;
    }
    
    private static final boolean seedFromFile(String filename, byte buf[]) {
        File f = new File(I2PAppContext.getGlobalContext().getConfigDir(), filename);
        if (f.exists()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(f);
                int read = 0;
                byte tbuf[] = new byte[buf.length];
                while (read < buf.length) {
                    int curRead = fis.read(tbuf, read, tbuf.length - read);
                    if (curRead < 0)
                        break;
                    read += curRead;
                }
                for (int i = 0; i < read; i++)
                    buf[i] ^= tbuf[i];
                return true;
            } catch (IOException ioe) {
                // ignore
            } finally {
                if (fis != null) try { fis.close(); } catch (IOException ioe) {}
            }
        }
        return false;
    }

    public static void main(String args[]) {
        for (int j = 0; j < 2; j++) {
        RandomSource rs = new RandomSource(I2PAppContext.getGlobalContext());
        byte buf[] = new byte[1024];
        boolean seeded = rs.initSeed(buf);
        System.out.println("PRNG class hierarchy: ");
        Class c = rs.getClass();
        while (c != null) {
            System.out.println("\t" + c.getName());
            c = c.getSuperclass();
        }
        System.out.println("Provider: \n" + rs.getProvider());
        if (seeded) {
            System.out.println("Initialized seed: " + Base64.encode(buf));
            rs.setSeed(buf);
        }
        for (int i = 0; i < 64; i++) rs.nextBytes(buf);
        rs.saveSeed();
        }
    }
    
    // noop
    private static class DummyEntropyHarvester implements EntropyHarvester {
        public void feedEntropy(String source, long data, int bitoffset, int bits) {}
        public void feedEntropy(String source, byte[] data, int offset, int len) {}
    }
}
