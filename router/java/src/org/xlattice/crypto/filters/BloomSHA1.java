package org.xlattice.crypto.filters;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A Bloom filter for sets of SHA1 digests.  A Bloom filter uses a set
 * of k hash functions to determine set membership.  Each hash function
 * produces a value in the range 0..M-1.  The filter is of size M.  To
 * add a member to the set, apply each function to the new member and 
 * set the corresponding bit in the filter.  For M very large relative
 * to k, this will normally set k bits in the filter.  To check whether
 * x is a member of the set, apply each of the k hash functions to x
 * and check whether the corresponding bits are set in the filter.  If
 * any are not set, x is definitely not a member.  If all are set, x 
 * may be a member.  The probability of error (the false positive rate)
 * is f = (1 - e^(-kN/M))^k, where N is the number of set members.
 *
 * This class takes advantage of the fact that SHA1 digests are good-
 * quality pseudo-random numbers.  The k hash functions are the values
 * of distinct sets of bits taken from the 20-byte SHA1 hash.  The
 * number of bits in the filter, M, is constrained to be a power of 
 * 2; M == 2^m.  The number of bits in each hash function may not 
 * exceed floor(m/k).
 *
 * This class is designed to be thread-safe, but this has not been
 * exhaustively tested.
 *
 * @author < A HREF="mailto:jddixon@users.sourceforge.net">Jim Dixon</A>
 * 
 * BloomSHA1.java and KeySelector.java are BSD licensed from the xlattice
 * app - http://xlattice.sourceforge.net/
 * 
 * minor tweaks by jrandom, exposing unsynchronized access and 
 * allowing larger M and K.  changes released into the public domain.
 * 
 * Note that this is used only by DecayingBloomFilter, which uses only
 * the unsynchronized locked_foo() methods.
 * Deprecated for use outside of the router; to be moved to router.jar.
 * 
 * As of 0.8.11, the locked_foo() methods are thread-safe, in that they work,
 * but there is a minor risk of false-negatives if two threads are
 * accessing the same bloom filter integer.
 */

public class BloomSHA1 {
    protected final int m;
    protected final int k;
    protected int count;
   
    protected final int[] filter;
    protected final KeySelector ks;
    
    // convenience variables
    protected final int filterBits;
    protected final int filterWords;
    
    private final BlockingQueue<int[]> buf;

/* (24,11) too big - see KeySelector

    public static void main(String args[]) {
        BloomSHA1 b = new BloomSHA1(24, 11);
        for (int i = 0; i < 100; i++) {
            byte v[] = new byte[32];
            v[0] = (byte)i;
            b.insert(v);
        }
    }
*/
    
    
    /**
     * Creates a filter with 2^m bits and k 'hash functions', where
     * each hash function is portion of the 160-bit SHA1 hash.   

     * @param m determines number of bits in filter
     * @param k number of hash functionsx
     *
     * See KeySelector for important restriction on max m and k
     */
    public BloomSHA1( int m, int k) {
        // XXX need to devise more reasonable set of checks
        //if ( m < 2 || m > 20) {
        //    throw new IllegalArgumentException("m out of range");
        //}
        //if ( k < 1 || ( k * m > 160 )) {
        //    throw new IllegalArgumentException( 
        //        "too many hash functions for filter size");
        //}
        this.m = m;
        this.k = k;
        filterBits = 1 << m;
        filterWords = (filterBits + 31)/32;     // round up 
        filter = new int[filterWords];
        ks = new KeySelector(m, k);
        buf = new LinkedBlockingQueue(16);

        // DEBUG
        //System.out.println("Bloom constructor: m = " + m + ", k = " + k
        //    + "\n    filterBits = " + filterBits
        //    + ", filterWords = " + filterWords);
        // END
    }

    /**
     * Creates a filter of 2^m bits, with the number of 'hash functions"
     * k defaulting to 8.
     * @param m determines size of filter
     */
    public BloomSHA1 (int m) {
        this(m, 8);
    }

    /**
     * Creates a filter of 2^20 bits with k defaulting to 8.
     */
    public BloomSHA1 () {
        this (20, 8);
    }
    /** Clear the filter, unsynchronized */
    protected void doClear() {
        Arrays.fill(filter, 0);
        count = 0;
    }
    /** Synchronized version */
    public void clear() {
        synchronized (this) {
            doClear();
        }
    }
    /**
     * Returns the number of keys which have been inserted.  This 
     * class (BloomSHA1) does not guarantee uniqueness in any sense; if the 
     * same key is added N times, the number of set members reported
     * will increase by N.
     * 
     * @return number of set members 
     */
    public final int size() {
        synchronized (this) {
            return count;
        }
    }
    /**
     * @return number of bits in filter
     */
    public final int capacity () {
        return filterBits;
    }

    /**
     * Add a key to the set represented by the filter.   
     *
     * XXX This version does not maintain 4-bit counters, it is not
     * a counting Bloom filter.
     * 
     * @param b byte array representing a key (SHA1 digest)
     */
    public void insert (byte[]b) { insert(b, 0, b.length); }

    public void insert (byte[]b, int offset, int len) {
        synchronized(this) {
            locked_insert(b, offset, len);
        }
    }

    public final void locked_insert(byte[]b) { locked_insert(b, 0, b.length); }

    public final void locked_insert(byte[]b, int offset, int len) { 
        int[] bitOffset = acquire();
        int[] wordOffset = acquire();
        ks.getOffsets(b, offset, len, bitOffset, wordOffset);
        for (int i = 0; i < k; i++) {
            filter[wordOffset[i]] |=  1 << bitOffset[i];
        }
        count++;
        buf.offer(bitOffset);
        buf.offer(wordOffset);
    }
    
    /**
     * Is a key in the filter.  Sets up the bit and word offset arrays.
     * 
     * @param b byte array representing a key (SHA1 digest)
     * @return true if b is in the filter 
     */
    protected final boolean isMember(byte[] b) { return isMember(b, 0, b.length); }

    protected final boolean isMember(byte[] b, int offset, int len) {
        int[] bitOffset = acquire();
        int[] wordOffset = acquire();
        ks.getOffsets(b, offset, len, bitOffset, wordOffset);
        for (int i = 0; i < k; i++) {
            if (! ((filter[wordOffset[i]] & (1 << bitOffset[i])) != 0) ) {
                buf.offer(bitOffset);
                buf.offer(wordOffset);
                return false;
            }
        }
        buf.offer(bitOffset);
        buf.offer(wordOffset);
        return true;
    }
    
    public final boolean locked_member(byte[]b) { return isMember(b); }
    public final boolean locked_member(byte[]b, int offset, int len) { return isMember(b, offset, len); }
    
    /**
     * Is a key in the filter.  External interface, internally synchronized.
     * 
     * @param b byte array representing a key (SHA1 digest)
     * @return true if b is in the filter 
     */
    public final boolean member(byte[]b) { return member(b, 0, b.length); }
    public final boolean member(byte[]b, int offset, int len) {
        synchronized (this) {
            return isMember(b, offset, len);
        }
    }

    /**
     * Get the bloom filter offsets for reuse.
     * Caller should call release(rv) when done with it.
     * @since 0.8.11
     */
    public FilterKey getFilterKey(byte[] b, int offset, int len) {
        int[] bitOffset = acquire();
        int[] wordOffset = acquire();
        ks.getOffsets(b, offset, len, bitOffset, wordOffset);
        return new FilterKey(bitOffset, wordOffset);
    }

    /**
     * Add the key to the filter.
     * @since 0.8.11
     */
    public void locked_insert(FilterKey fk) {
        for (int i = 0; i < k; i++) {
            filter[fk.wordOffset[i]] |=  1 << fk.bitOffset[i];
        }
        count++;
    }


    /**
     * Is the key in the filter.
     * @since 0.8.11
     */
    public boolean locked_member(FilterKey fk) {
        for (int i = 0; i < k; i++) {
            if (! ((filter[fk.wordOffset[i]] & (1 << fk.bitOffset[i])) != 0) )
                return false;
        }
        return true;
    }

    /**
     * @since 0.8.11
     */
    private int[] acquire() {
        int[] rv = buf.poll();
        if (rv != null)
            return rv;
        return new int[k];
    }

    /**
     * @since 0.8.11
     */
    public void release(FilterKey fk) {
        buf.offer(fk.bitOffset);
        buf.offer(fk.wordOffset);
    }

    /**
     * Store the (opaque) bloom filter offsets for reuse.
     * @since 0.8.11
     */
    public static class FilterKey {

        private final int[] bitOffset;
        private final int[] wordOffset;

        private FilterKey(int[] bitOffset, int[] wordOffset) {
            this.bitOffset = bitOffset;
            this.wordOffset = wordOffset;
        }
    }

    /** 
     * @param n number of set members
     * @return approximate false positive rate
     */
    public final double falsePositives(int n) {
        // (1 - e(-kN/M))^k
        return java.lang.Math.pow ( 
                (1l - java.lang.Math.exp(0d- ((double)k) * (long)n / filterBits)), k);
    }

    public final double falsePositives() {
        return falsePositives(count);
    }

/*****
    // DEBUG METHODS
    public static String keyToString(byte[] key) {
        StringBuilder sb = new StringBuilder().append(key[0]);
        for (int i = 1; i < key.length; i++) {
            sb.append(".").append(Integer.toString(key[i], 16));
        }
        return sb.toString();
    }
*****/

    /** convert 64-bit integer to hex String */
/*****
    public static String ltoh (long i) {
        StringBuilder sb = new StringBuilder().append("#")
                                .append(Long.toString(i, 16));
        return sb.toString();
    }
*****/

    /** convert 32-bit integer to String */
/*****
    public static String itoh (int i) {
        StringBuilder sb = new StringBuilder().append("#")
                                .append(Integer.toString(i, 16));
        return sb.toString();
    }
*****/

    /** convert single byte to String */
/*****
    public static String btoh (byte b) {
        int i = 0xff & b;
        return itoh(i);
    }
*****/
}

