package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.security.SecureRandom;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EntropyHarvester;

/**
 * Singleton for whatever PRNG i2p uses.  
 *
 * @author jrandom
 */
public class RandomSource extends SecureRandom {
    private Log _log;
    private EntropyHarvester _entropyHarvester;

    public RandomSource(I2PAppContext context) {
        super();
        _log = context.logManager().getLog(RandomSource.class);
        // when we replace to have hooks for fortuna (etc), replace with
        // a factory (or just a factory method)
        _entropyHarvester = new DummyEntropyHarvester();
    }
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

    /** synchronized for older versions of kaffe */
    public void nextBytes(byte bytes[]) {
        synchronized (this) {
            super.nextBytes(bytes);
        }
    }
    
    public EntropyHarvester harvester() { return _entropyHarvester; }
 
    // noop
    private static class DummyEntropyHarvester implements EntropyHarvester {
        public void feedEntropy(String source, long data, int bitoffset, int bits) {}
        public void feedEntropy(String source, byte[] data, int offset, int len) {}
    }
}