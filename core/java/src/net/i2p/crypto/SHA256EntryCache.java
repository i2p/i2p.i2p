package net.i2p.crypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;

/**
 * Cache the objects used in SHA256Generator's calculate method to reduce
 * memory churn.  The CacheEntry should be held onto as long as the 
 * data referenced in it is needed (which often is only one or two lines
 * of code)
 *
 */
public final class SHA256EntryCache {
    private static final int ONE_KB = 0;
    private static final int FOUR_KB = 1;
    private static final int EIGHT_KB = 2;
    private static final int SIXTEEN_KB = 3;
    private static final int THIRTYTWO_KB = 4;
    private static final int FOURTYEIGHT_KB = 5;
    private static final int LARGER = 6;
    /** 
     * Array of Lists of free CacheEntry objects, indexed
     * by the payload size they are capable of handling
     */
    private List _available[] = new List[6];
    /** count up how often we use the cache for each size */
    private long _used[] = new long[7];
    private int _sizes[] = new int[] { 1024,4*1024,8*1024,16*1024,32*1024,48*1024 };
    
    /** no more than 32 at each size level */
    private static final int MAX_CACHED = 64;
    
    public SHA256EntryCache() {
        for (int i = 0; i < _available.length; i++) {
            _available[i] = new ArrayList(MAX_CACHED);
            //for (int j = 0; j < MAX_CACHED; j++)
            //    _available[i].add(new CacheEntry(_sizes[i]));
        }
    }
    
    /**
     * Get the next available structure, either from the cache or a brand new one
     *
     */
    public final CacheEntry acquire(int payload) {
        int entrySize = getBucket(payload);
        switch (entrySize) {
            case 1024:
                _used[ONE_KB]++;
                synchronized (_available[ONE_KB]) {
                    if (_available[ONE_KB].size() > 0) {
                        return (CacheEntry)_available[ONE_KB].remove(0);
                    }
                }
                break;
            case 4*1024:
                _used[FOUR_KB]++;
                synchronized (_available[FOUR_KB]) {
                    if (_available[FOUR_KB].size() > 0) {
                        return (CacheEntry)_available[FOUR_KB].remove(0);
                    }
                }
                break;
            case 8*1024:
                _used[EIGHT_KB]++;
                synchronized (_available[EIGHT_KB]) {
                    if (_available[EIGHT_KB].size() > 0) {
                        return (CacheEntry)_available[EIGHT_KB].remove(0);
                    }
                }
                break;
            case 16*1024:
                _used[SIXTEEN_KB]++;
                synchronized (_available[SIXTEEN_KB]) {
                    if (_available[SIXTEEN_KB].size() > 0) {
                        return (CacheEntry)_available[SIXTEEN_KB].remove(0);
                    }
                }
                break;
            case 32*1024:
                _used[THIRTYTWO_KB]++;
                synchronized (_available[THIRTYTWO_KB]) {
                    if (_available[THIRTYTWO_KB].size() > 0) {
                        return (CacheEntry)_available[THIRTYTWO_KB].remove(0);
                    }
                }
                break;
            case 48*1024:
                _used[FOURTYEIGHT_KB]++;
                synchronized (_available[FOURTYEIGHT_KB]) {
                    if (_available[FOURTYEIGHT_KB].size() > 0) {
                        return (CacheEntry)_available[FOURTYEIGHT_KB].remove(0);
                    }
                }
                break;
            default:
                _used[LARGER]++;
                // not for the bucket, so make it exact
                return new CacheEntry(payload);
        }
        return new CacheEntry(entrySize);
    }
    
    /**
     * Put this structure back onto the available cache for reuse
     *
     */
    public final void release(CacheEntry entry) {
        entry.reset();
        if (false) return;
        switch (entry.bucket) {
            case 1024:
                synchronized (_available[ONE_KB]) {
                    if (_available[ONE_KB].size() < MAX_CACHED) {
                        _available[ONE_KB].add(entry);
                    }
                }
                return;
            case 4*1024:
                synchronized (_available[FOUR_KB]) {
                    if (_available[FOUR_KB].size() < MAX_CACHED) {
                        _available[FOUR_KB].add(entry);
                    }
                }
                return;
            case 8*1024:
                synchronized (_available[EIGHT_KB]) {
                    if (_available[EIGHT_KB].size() < MAX_CACHED) {
                        _available[EIGHT_KB].add(entry);
                    }
                }
                return;
            case 16*1024:
                synchronized (_available[SIXTEEN_KB]) {
                    if (_available[SIXTEEN_KB].size() < MAX_CACHED) {
                        _available[SIXTEEN_KB].add(entry);
                    }
                }
                return;
            case 32*1024:
                synchronized (_available[THIRTYTWO_KB]) {
                    if (_available[THIRTYTWO_KB].size() < MAX_CACHED) {
                        _available[THIRTYTWO_KB].add(entry);
                    }
                }
                return;
            case 48*1024:
                synchronized (_available[FOURTYEIGHT_KB]) {
                    if (_available[FOURTYEIGHT_KB].size() < MAX_CACHED) {
                        _available[FOURTYEIGHT_KB].add(entry);
                    }
                }
                return;
        }
    }
    
    /**
     * all the data alloc'ed in a calculateHash call
     */
    public static final class CacheEntry {
        byte hashbytes[];
        int W[];
        int M0[];
        int H[];
        Hash hash;
        int wordlength;
        int bucket;
        
        public CacheEntry(int payload) {
            wordlength = SHA256Generator.getWordlength(payload);
            bucket = payload;
            hashbytes = new byte[32];
            M0 = new int[wordlength];
            W = new int[64];
            H = new int[8];
            hash = new Hash();
            hash.setData(hashbytes);
        }
        
        public final void reset() {
            Arrays.fill(hashbytes, (byte)0x0);
            Arrays.fill(M0, (byte)0x0);
            Arrays.fill(W, (byte)0x0);
            Arrays.fill(H, (byte)0x0);
        }
    }
    
    private static final int getBucket(int payload) {
        if (payload <= 1024)
            return 1024;
        else if (payload <= 4*1024)
            return 4*1024;
        else if (payload <= 8*1024)
            return 8*1024;
        else if (payload <= 16*1024)
            return 16*1024;
        else if (payload <= 32*1024)
            return 32*1024;
        else if (payload <= 48*1024)
            return 48*1024;
        else
            return payload;
    }
    
    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        for (int i = 1; i < 20000; i+=2) {
            test(ctx, i);
        }
    }
    private static void test(I2PAppContext ctx, int size) {
        System.out.print("Size = " + size);
        for (int i = 0; i < 2; i++) {
            byte orig[] = new byte[size];
            ctx.random().nextBytes(orig);
            CacheEntry cache = ctx.sha().cache().acquire(orig.length);
            try {
            Hash h = ctx.sha().calculateHash(orig, cache);
            Hash h2 = ctx.sha().calculateHash(orig);
            boolean eq = h.equals(h2);
            ctx.sha().cache().release(cache);
            if (eq) {
                System.out.print(".");
            } else {
                System.out.print("ERROR " + i);
                break;
            }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        System.out.println();
    }
}
