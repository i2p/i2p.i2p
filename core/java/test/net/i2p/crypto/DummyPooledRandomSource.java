package net.i2p.crypto;

import java.util.Random;

import net.i2p.I2PAppContext;
import net.i2p.util.PooledRandomSource;
import net.i2p.util.RandomSource;

/**
 *
 */
public class DummyPooledRandomSource extends PooledRandomSource {
    public DummyPooledRandomSource(I2PAppContext context) {
        super(context);
    }
    
    @Override
    protected void initializePool(I2PAppContext context) {
        _pool = new RandomSource[POOL_SIZE];
        for (int i = 0; i < POOL_SIZE; i++) {
            _pool[i] = new DummyRandomSource(context);
            _pool[i].nextBoolean();
        }
        _nextPool = 0;
    }
    
    private class DummyRandomSource extends RandomSource {
        private Random _prng;
        public DummyRandomSource(I2PAppContext context) {
            super(context);
            // when we replace to have hooks for fortuna (etc), replace with
            // a factory (or just a factory method)
            _prng = new Random();
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
            int val = _prng.nextInt(n);
            if (val < 0) val = 0 - val;
            if (val >= n) val = val % n;
            return val;
        }

        /**
         * Like the modified nextInt, nextLong(n) returns a random number from 0 through n,
         * including 0, excluding n.
         */
        @Override
        public long nextLong(long n) {
            long v = _prng.nextLong();
            if (v < 0) v = 0 - v;
            if (v >= n) v = v % n;
            return v;
        }

        /**
         * override as synchronized, for those JVMs that don't always pull via
         * nextBytes (cough ibm)
         */
        @Override
        public boolean nextBoolean() { return _prng.nextBoolean(); }
        /**
         * override as synchronized, for those JVMs that don't always pull via
         * nextBytes (cough ibm)
         */
        @Override
        public void nextBytes(byte buf[]) { _prng.nextBytes(buf); }
        /**
         * override as synchronized, for those JVMs that don't always pull via
         * nextBytes (cough ibm)
         */
        @Override
        public double nextDouble() { return _prng.nextDouble(); }
        /**
         * override as synchronized, for those JVMs that don't always pull via
         * nextBytes (cough ibm)
         */
        @Override
        public float nextFloat() { return _prng.nextFloat(); }
        /**
         * override as synchronized, for those JVMs that don't always pull via
         * nextBytes (cough ibm)
         */
        @Override
        public double nextGaussian() { return _prng.nextGaussian(); }
        /**
         * override as synchronized, for those JVMs that don't always pull via
         * nextBytes (cough ibm)
         */
        @Override
        public int nextInt() { return _prng.nextInt(); }
        /**
         * override as synchronized, for those JVMs that don't always pull via
         * nextBytes (cough ibm)
         */
        @Override
        public long nextLong() { return _prng.nextLong(); }
    }
}
