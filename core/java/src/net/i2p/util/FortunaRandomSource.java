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

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Random;

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

    /**
     *  May block up to 10 seconds or forever
     */
    public FortunaRandomSource(I2PAppContext context) {
        super(context);
        _fortuna = new AsyncFortunaStandalone(context);
        byte seed[] = new byte[1024];
        // may block for 10 seconds
        if (initSeed(seed)) {
            _fortuna.seed(seed);
        } else {
            // may block forever
            //SecureRandom sr = new SecureRandom();
            // SecureRandom already failed in initSeed(), so try Random
            Random sr = new Random();
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
        synchronized(_fortuna) {
            _fortuna.shutdown();
        }
    }

    @Override
    public void setSeed(byte buf[]) {
        synchronized(_fortuna) {
            _fortuna.addRandomBytes(buf);
        }
    }

    /**
     * According to the java docs (http://java.sun.com/j2se/1.4.1/docs/api/java/util/Random.html#nextInt(int))
     * nextInt(n) should return a number between 0 and n (including 0 and excluding n).  However, their pseudocode,
     * as well as sun's, kaffe's, and classpath's implementation INCLUDES NEGATIVE VALUES.
     * Ok, so we're going to have it return between 0 and n (including 0, excluding n), since 
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

        // get at least 4 extra bits if possible for better
        // distribution after the %
        // No extra needed if power of two.
        int numBits;
        if (n > 0x100000)
            numBits = 31;
        else if (n > 0x1000)
            numBits = 24;
        else if (n > 0x10)
            numBits = 16;
        else
            numBits = 8;
        int rv;
        synchronized(_fortuna) {
            rv = nextBits(numBits);
        }
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
        long rv = signedNextLong();
        if (rv < 0) 
            rv = 0 - rv;
        rv %= n;
        return rv;
    }
    
    @Override
    public long nextLong() { return signedNextLong(); }

    /**
     * Implementation from Sun's java.util.Random javadocs
     */
    private long signedNextLong() {
        synchronized(_fortuna) {
            return ((long)nextBits(32) << 32) + nextBits(32);
        }
    }

    @Override
    public boolean nextBoolean() { 
        byte val;
        synchronized(_fortuna) {
            val = _fortuna.nextByte();
        }
        return ((val & 0x01) != 0);
    }

    @Override
    public void nextBytes(byte buf[]) { 
        synchronized(_fortuna) {
            _fortuna.nextBytes(buf);
        }
    }

    /**
     * Not part of java.util.SecureRandom, but added for efficiency, since Fortuna supports it.
     *
     * @since 0.8.12
     */
    @Override
    public void nextBytes(byte buf[], int offset, int length) {
        synchronized(_fortuna) {
            _fortuna.nextBytes(buf, offset, length);
        }
    }

    /**
     * Not part of java.util.SecureRandom, but added for efficiency, since Fortuna supports it.
     *
     * @since 0.9.24
     */
    public byte nextByte() { 
        synchronized(_fortuna) {
            return _fortuna.nextByte();
        }
    }

    /**
     * Implementation from sun's java.util.Random javadocs 
     */
    @Override
    public double nextDouble() { 
        long d;
        synchronized(_fortuna) {
            d = ((long)nextBits(26) << 27) + nextBits(27);
        }
        return d / (double)(1L << 53);
    }

    /**
     * Implementation from sun's java.util.Random javadocs 
     */
    @Override
    public float nextFloat() { 
        int d;
        synchronized(_fortuna) {
            d = nextBits(24);
        }
        return d / ((float)(1 << 24));
    }

    /**
     * Implementation from sun's java.util.Random javadocs 
     */
    @Override
    public double nextGaussian() { 
        synchronized (this) {
            if (_haveNextGaussian) {
                _haveNextGaussian = false;
                return _nextGaussian;
            }
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
     * Pull the next numBits of random data off the fortuna instance (returning 0
     * through 2^numBits-1
     *
     * Caller must synchronize!
     */
    protected int nextBits(int numBits) {
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
    public void feedEntropy(String source, long data, int bitoffset, int bits) {
        synchronized(_fortuna) {
            _fortuna.addRandomByte((byte)(data & 0xFF));
        }
    }
 
    /** reseed the fortuna */
    @Override
    public void feedEntropy(String source, byte[] data, int offset, int len) {
        try {
            synchronized(_fortuna) {
                _fortuna.addRandomBytes(data, offset, len);
            }
        } catch (RuntimeException e) {
            // AIOOBE seen, root cause unknown, ticket #1576
            Log log = _context.logManager().getLog(FortunaRandomSource.class);
            log.warn("feedEntropy()", e);
        }
    }
    
    /**
     *  Outputs to stdout for dieharder:
     *  <code>
     *  java -cp build/i2p.jar net.i2p.util.FortunaRandomSource | dieharder -a -g 200
     *  </code>
     */
    public static void main(String args[]) {
        try {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("prng.buffers", "12");
            I2PAppContext ctx = new I2PAppContext(props);
            RandomSource rand = ctx.random();
            byte[] buf = new byte[65536];
            while (true) {
                rand.nextBytes(buf);
                System.out.write(buf);
            }
        } catch (IOException e) { e.printStackTrace(); }
    }
}
