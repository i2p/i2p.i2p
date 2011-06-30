package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import gnu.crypto.prng.AsyncFortunaStandalone;

import java.security.SecureRandom;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EntropyHarvester;

/**
 * Wrapper around GNU-Crypto's Fortuna PRNG.  This seeds from /dev/urandom and
 * ./prngseed.rnd on startup (if they exist), writing a new seed to ./prngseed.rnd
 * on an explicit call to saveSeed().
 *
 */
public class FortunaRandomSource extends RandomSource implements EntropyHarvester {
    private final AsyncFortunaStandalone _fortuna;
    private double _nextGaussian;
    private boolean _haveNextGaussian;

    public FortunaRandomSource(I2PAppContext context) {
        super(context);
        _fortuna = new AsyncFortunaStandalone(context);
        byte seed[] = new byte[1024];
        if (initSeed(seed)) {
            _fortuna.seed(seed);
        } else {
            SecureRandom sr = new SecureRandom();
            sr.nextBytes(seed);
            _fortuna.seed(seed);
        }
        _fortuna.startup();
        // kickstart it
        _fortuna.nextBytes(seed);
        _haveNextGaussian = false;
    }
    
    /**
     *  Note - methods may hang or NPE or throw IllegalStateExceptions after this
     *  @since 0.8.8
     */
    public void shutdown() {
        _fortuna.shutdown();
    }

    @Override
    public synchronized void setSeed(byte buf[]) {
      _fortuna.addRandomBytes(buf);
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
        int rv = signedNextInt(n);
        if (rv < 0) 
            rv = 0 - rv;
        rv %= n;
        return rv;
    }
    
    @Override
    public int nextInt() { return signedNextInt(Integer.MAX_VALUE); }

    /**
     * Implementation from Sun's java.util.Random javadocs
     */
    private int signedNextInt(int n) {
        if (n<=0)
          throw new IllegalArgumentException("n must be positive");

        ////
        // this shortcut from sun's docs neither works nor is necessary.
        //
        //if ((n & -n) == n)  {
        //    // i.e., n is a power of 2
        //    return (int)((n * (long)nextBits(31)) >> 31);
        //}

        int numBits = 0;
        int remaining = n;
        int rv = 0;
        while (remaining > 0) {
            remaining >>= 1;
            rv += nextBits(8) << numBits*8;
            numBits++;
        }
        if (rv < 0)
            rv += n;
        return rv % n;
        
        //int bits, val;
        //do {
        //    bits = nextBits(31);
        //    val = bits % n;
        //} while(bits - val + (n-1) < 0);
        //
        //return val;
    }

    /**
     * Like the modified nextInt, nextLong(n) returns a random number from 0 through n,
     * including 0, excluding n.
     */
    @Override
    public long nextLong(long n) {
        if (n == 0) return 0;
        long rv = signedNextLong(n);
        if (rv < 0) 
            rv = 0 - rv;
        rv %= n;
        return rv;
    }
    
    @Override
    public long nextLong() { return signedNextLong(Long.MAX_VALUE); }

    /**
     * Implementation from Sun's java.util.Random javadocs
     */
    private long signedNextLong(long n) {
        return ((long)nextBits(32) << 32) + nextBits(32);
    }

    @Override
    public synchronized boolean nextBoolean() { 
        // wasteful, might be worth caching the boolean byte later
        byte val = _fortuna.nextByte();
        return ((val & 0x01) == 1);
    }

    @Override
    public synchronized void nextBytes(byte buf[]) { 
        _fortuna.nextBytes(buf);
    }

    /**
     * Implementation from sun's java.util.Random javadocs 
     */
    @Override
    public double nextDouble() { 
        return (((long)nextBits(26) << 27) + nextBits(27)) / (double)(1L << 53);
    }
    /**
     * Implementation from sun's java.util.Random javadocs 
     */
    @Override
    public float nextFloat() { 
        return nextBits(24) / ((float)(1 << 24));
    }
    /**
     * Implementation from sun's java.util.Random javadocs 
     */
    @Override
    public synchronized double nextGaussian() { 
        if (_haveNextGaussian) {
            _haveNextGaussian = false;
            return _nextGaussian;
        } else {
            double v1, v2, s;
            do { 
                v1 = 2 * nextDouble() - 1;   // between -1.0 and 1.0
                v2 = 2 * nextDouble() - 1;   // between -1.0 and 1.0
                s = v1 * v1 + v2 * v2;
            } while (s >= 1 || s == 0);
            double multiplier = Math.sqrt(-2 * Math.log(s)/s);
            _nextGaussian = v2 * multiplier;
            _haveNextGaussian = true;
            return v1 * multiplier;
        }
    }

    /**
     * Pull the next numBits of random data off the fortuna instance (returning -2^numBits-1
     * through 2^numBits-1
     */
    protected synchronized int nextBits(int numBits) {
        long rv = 0;
        int bytes = (numBits + 7) / 8;
        for (int i = 0; i < bytes; i++)
            rv += ((_fortuna.nextByte() & 0xFF) << i*8);
        //rv >>>= (64-numBits);
        if (rv < 0)
            rv = 0 - rv;
        int off = 8*bytes - numBits;
        rv >>>= off;
        return (int)rv;
    }
    
    @Override
    public EntropyHarvester harvester() { return this; }
 
    /** reseed the fortuna */
    @Override
    public synchronized void feedEntropy(String source, long data, int bitoffset, int bits) {
        _fortuna.addRandomByte((byte)(data & 0xFF));
    }
 
    /** reseed the fortuna */
    @Override
    public synchronized void feedEntropy(String source, byte[] data, int offset, int len) {
        _fortuna.addRandomBytes(data, offset, len);
    }
    
/*****
    public static void main(String args[]) {
        try {
            RandomSource rand = I2PAppContext.getGlobalContext().random();
            if (true) {
                for (int i = 0; i < 1000; i++)
                    if (rand.nextFloat() < 0)
                        throw new RuntimeException("negative!");
                System.out.println("All positive");
                return;
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(baos);
            for (int i = 0; i < 1024*1024; i++) {
                int c = rand.nextInt(256);
                gos.write((byte)c);
            }
            gos.finish();
            byte compressed[] = baos.toByteArray();
            System.out.println("Compressed size of 1MB: " + compressed.length);
        } catch (Exception e) { e.printStackTrace(); }
    }
*****/
}
