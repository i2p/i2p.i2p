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
public abstract class SHAEntryCache {
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
    
    public SHAEntryCache() {
        for (int i = 0; i < _available.length; i++) {
            _available[i] = new ArrayList(MAX_CACHED);
            //for (int j = 0; j < MAX_CACHED; j++)
            //    _available[i].add(new CacheEntry(_sizes[i]));
        }
    }
    
    /**
     * Overridden by the impl to provide a brand new cache entry, capable
     * of sustaining the data necessary to digest the specified payload
     *
     */
    protected abstract CacheEntry createNew(int payload);
        
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
                return createNew(payload);
        }
        return createNew(payload);
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
    public static abstract class CacheEntry {
        byte hashbytes[];
        int W[];
        int M0[];
        int H[];
        Hash hash;
        int wordlength;
        int bucket;
        
        protected CacheEntry() {}
        
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
}
