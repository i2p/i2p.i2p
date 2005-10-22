package net.i2p.crypto;

import java.util.ArrayList;
import java.util.List;

/**
 * Cache the objects used in CryptixRijndael_Algorithm.makeKey to reduce
 * memory churn.  The KeyCacheEntry should be held onto as long as the 
 * data referenced in it is needed (which often is only one or two lines
 * of code)
 *
 */
public final class CryptixAESKeyCache {
    private List _availableKeys;
    
    private static final int KEYSIZE = 32; // 256bit AES
    private static final int BLOCKSIZE = 16;
    private static final int ROUNDS = CryptixRijndael_Algorithm.getRounds(KEYSIZE, BLOCKSIZE);
    private static final int BC = BLOCKSIZE / 4;
    private static final int KC = KEYSIZE / 4; 
    
    private static final int MAX_KEYS = 64;
    
    public CryptixAESKeyCache() {
        _availableKeys = new ArrayList(MAX_KEYS);
    }
    
    /**
     * Get the next available structure, either from the cache or a brand new one
     *
     */
    public final KeyCacheEntry acquireKey() {
        synchronized (_availableKeys) {
            if (_availableKeys.size() > 0)
                return (KeyCacheEntry)_availableKeys.remove(0);
        }
        return createNew();
    }
    
    /**
     * Put this structure back onto the available cache for reuse
     *
     */
    public final void releaseKey(KeyCacheEntry key) {
        synchronized (_availableKeys) {
            if (_availableKeys.size() < MAX_KEYS)
                _availableKeys.add(key);
        }
    }
    
    public static final KeyCacheEntry createNew() {
        KeyCacheEntry e = new KeyCacheEntry();
        e.Ke = new int[ROUNDS + 1][BC]; // encryption round keys
        e.Kd = new int[ROUNDS + 1][BC]; // decryption round keys
        e.tk = new int[KC];
        e.key = new Object[] { e.Ke, e.Kd };
        return e;
    }
    
    /**
     * all the data alloc'ed in a makeKey call
     */
    public static final class KeyCacheEntry {
        int[][] Ke;
        int[][] Kd;
        int[]   tk;
        
        Object[] key;
    }
}
