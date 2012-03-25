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

/**
 * Allocate data out of a large buffer of data, rather than the PRNG's 
 * (likely) small buffer to reduce the frequency of prng recalcs (though
 * the recalcs are now more time consuming).
 *
 * @deprecated Unused! See FortunaRandomSource
 *
 */
public class BufferedRandomSource extends RandomSource {
    private byte _buffer[];
    private int _nextByte;
    private int _nextBit;
    private static volatile long _reseeds;
    
    private static final int DEFAULT_BUFFER_SIZE = 256*1024;
    
    public BufferedRandomSource(I2PAppContext context) {
        this(context, DEFAULT_BUFFER_SIZE);
    }
    public BufferedRandomSource(I2PAppContext context, int bufferSize) {
        super(context);
        context.statManager().createRateStat("prng.reseedCount", "How many times the prng has been reseeded", "Encryption", new long[] { 60*1000, 10*60*1000, 60*60*1000 } );
        _buffer = new byte[bufferSize];
        refillBuffer();
        // stagger reseeding
        _nextByte = ((int)_reseeds-1) * 16 * 1024;
    }
    
    private final void refillBuffer() {
        long before = System.currentTimeMillis();
        doRefillBuffer();
        long duration = System.currentTimeMillis() - before;
        if ( (_reseeds % 1) == 0)
            _context.statManager().addRateData("prng.reseedCount", _reseeds, duration);
    }
    
    private synchronized final void doRefillBuffer() {
        super.nextBytes(_buffer);
        _nextByte = 0;
        _nextBit = 0;
        _reseeds++;
    }
    
    private static final byte GOBBLE_MASK[] = { 0x0, // 0 bits
                                                0x1, // 1 bit
                                                0x3, // 2 bits
                                                0x7, // 3 bits
                                                0xF, // 4 bits
                                                0x1F, // 5 bits
                                                0x3F, // 6 bits
                                                0x7F, // 7 bits
                                                (byte)0xFF // 8 bits
    };
    
    private synchronized final long nextBits(int numBits) {
        if (false) {
            long rv = 0;
            for (int curBit = 0; curBit < numBits; curBit++) {
                if (_nextBit >= 8) {
                    _nextBit = 0;
                    _nextByte++;
                }
                if (_nextByte >= _buffer.length)
                    refillBuffer();
                rv += (_buffer[_nextByte] << curBit);
                _nextBit++;
                /*
                int avail = 8 - _nextBit;
                // this is not correct! (or is it?)
                rv += (_buffer[_nextByte] << 8 - avail);
                _nextBit += avail;
                numBits -= avail;
                if (_nextBit >= 8) {
                    _nextBit = 0;
                    _nextByte++;
                }
                 */
            }
            return rv;
        } else {
            long rv = 0;
            int curBit = 0;
            while (curBit < numBits) {
                if (_nextBit >= 8) {
                    _nextBit = 0;
                    _nextByte++;
                }
                if (_nextByte >= _buffer.length)
                    refillBuffer();
                int gobbleBits = 8 - _nextBit;
                int want = numBits - curBit;
                if (gobbleBits > want)
                    gobbleBits = want;
                curBit += gobbleBits;
                int shift = 8 - _nextBit - gobbleBits;
                int c = (_buffer[_nextByte] & (GOBBLE_MASK[gobbleBits] << shift));
                rv += ((c >>> shift) << (curBit-gobbleBits));
                _nextBit += gobbleBits;
            }
            return rv;
        }
    }
    
    @Override
    public synchronized final void nextBytes(byte buf[]) { 
        int outOffset = 0;
        while (outOffset < buf.length) {
            int availableBytes = _buffer.length - _nextByte - (_nextBit != 0 ? 1 : 0);
            if (availableBytes <= 0)
                refillBuffer();
            int start = _buffer.length - availableBytes;
            int writeSize = Math.min(buf.length - outOffset, availableBytes);
            System.arraycopy(_buffer, start, buf, outOffset, writeSize);
            outOffset += writeSize;
            _nextByte += writeSize;
            _nextBit = 0;
        }
    }
    
    @Override
    public final int nextInt(int n) {
        if (n <= 0) return 0;
        int val = ((int)nextBits(countBits(n))) % n;
        if (val < 0) 
            return 0 - val;
        else
            return val;
    }
    
    @Override
    public final int nextInt() { return nextInt(Integer.MAX_VALUE); }
        
    /**
     * Like the modified nextInt, nextLong(n) returns a random number from 0 through n,
     * including 0, excluding n.
     */
    @Override
    public final long nextLong(long n) {
        if (n <= 0) return 0;
        long val = nextBits(countBits(n)) % n;
        if (val < 0)
            return 0 - val;
        else
            return val;
    }
    
    @Override
    public final long nextLong() { return nextLong(Long.MAX_VALUE); }
    
    static final int countBits(long val) {
        int rv = 0;
        while (val > Integer.MAX_VALUE) {
            rv += 31;
            val >>>= 31;
        }
        
        while (val > 0) {
            rv++;
            val >>= 1;
        }
        return rv;
    }
    
    /**
     * override as synchronized, for those JVMs that don't always pull via
     * nextBytes (cough ibm)
     */
    @Override
    public final boolean nextBoolean() { 
        return nextBits(1) != 0;
    }
    
    private static final double DOUBLE_DENOMENATOR = (1L << 53);
    /** defined per javadoc ( ((nextBits(26)<<27) + nextBits(27)) / (1 << 53)) */
    @Override
    public final double nextDouble() { 
        long top = ((nextBits(26) << 27) + nextBits(27));
        return top / DOUBLE_DENOMENATOR;
    }
    private static final float FLOAT_DENOMENATOR = (1 << 24);
    /** defined per javadoc (nextBits(24) / ((float)(1 << 24)) ) */
    @Override
    public float nextFloat() { 
        long top = nextBits(24);
        return top / FLOAT_DENOMENATOR;
    }
    @Override
    public double nextGaussian() { 
        // bah, unbuffered
        return super.nextGaussian(); 
    }
    
/*****
    public static void main(String args[]) {
        for (int i = 0; i < 16; i++)
            test();
    }
    private static void test() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        byte data[] = new byte[16*1024];
        for (int i = 0; i < data.length; i += 4) {
            long l = ctx.random().nextLong();
            if (l < 0) l = 0 - l;
            DataHelper.toLong(data, i, 4, l);
        }
        byte compressed[] = DataHelper.compress(data);
        System.out.println("Data: " + data.length + "/" + compressed.length + ": " + toString(data));
    }
    private static final String toString(byte data[]) {
        StringBuilder buf = new StringBuilder(data.length * 9);
        for (int i = 0; i < data.length; i++) {
            for (int j = 0; j < 8; j++) {
                if ((data[i] & (1 << j)) != 0)
                    buf.append('1');
                else
                    buf.append('0');
            }
            buf.append(' ');
        }
        return buf.toString();
    }
*****/
}
