package net.i2p.crypto;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Cache the objects used in CryptixRijndael_Algorithm.makeKey to reduce
 * memory churn.  The KeyCacheEntry should be held onto as long as the 
 * data referenced in it is needed (which often is only one or two lines
 * of code)
 *
 * Unused as a class, as the keys are cached in the SessionKey objects,
 * but the static methods are used in FortunaStandalone.
 */
public final class CryptixAESKeyCache {
    private final LinkedBlockingQueue<KeyCacheEntry> _availableKeys;
    
    private static final int KEYSIZE = 32; // 256bit AES
    private static final int BLOCKSIZE = 16;
    private static final int ROUNDS = CryptixRijndael_Algorithm.getRounds(KEYSIZE, BLOCKSIZE);
    private static final int BC = BLOCKSIZE / 4;
    private static final int KC = KEYSIZE / 4; 
    
    private static final int MAX_KEYS = 64;
    
    /*
     * @deprecated unused, keys are now cached in the SessionKey objects
     */
    public CryptixAESKeyCache() {
        _availableKeys = new LinkedBlockingQueue(MAX_KEYS);
    }
    
    /**
     * Get the next available structure, either from the cache or a brand new one
     *
     * @deprecated unused, keys are now cached in the SessionKey objects
     */
    public final KeyCacheEntry acquireKey() {
        KeyCacheEntry rv = _availableKeys.poll();
        if (rv != null)
            return rv;
        return createNew();
    }
    
    /**
     * Put this structure back onto the available cache for reuse
     *
     * @deprecated unused, keys are now cached in the SessionKey objects
     */
    public final void releaseKey(KeyCacheEntry key) {
        _availableKeys.offer(key);
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
