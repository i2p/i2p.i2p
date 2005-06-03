package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.crypto.EntropyHarvester;

/**
 * Maintain a set of PRNGs to feed the apps
 */
public class PooledRandomSource extends RandomSource {
    private Log _log;
    private RandomSource _pool[];
    private volatile int _nextPool;

    private static final int POOL_SIZE = 16;
    
    public PooledRandomSource(I2PAppContext context) {
        super(context);
        _log = context.logManager().getLog(PooledRandomSource.class);
        _pool = new RandomSource[POOL_SIZE];
        for (int i = 0; i < POOL_SIZE; i++) {
            _pool[i] = new RandomSource(context);
            _pool[i].nextBoolean();
        }
        _nextPool = 0;
    }

    private final RandomSource pickPRNG() {
        int i = _nextPool;
        _nextPool = (++_nextPool) % POOL_SIZE;
        return _pool[i];
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
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextInt(n);
        }
    }

    /**
     * Like the modified nextInt, nextLong(n) returns a random number from 0 through n,
     * including 0, excluding n.
     */
    public long nextLong(long n) {
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextLong(n);
        }
    }

    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public boolean nextBoolean() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextBoolean();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public void nextBytes(byte buf[]) { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            prng.nextBytes(buf);
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public double nextDouble() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextDouble();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public float nextFloat() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextFloat();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public double nextGaussian() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextGaussian();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public int nextInt() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextInt();
        }
    }
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    public long nextLong() { 
        RandomSource prng = pickPRNG();
        synchronized (prng) {
            return prng.nextLong();
        }
    }
    
    public EntropyHarvester harvester() { 
        RandomSource prng = pickPRNG();
        return prng.harvester();
    }
}
