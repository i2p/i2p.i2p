package net.i2p.crypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;

/**
 * Cache the objects used in DSA's SHA1 calculateHash method to reduce
 * memory churn.  The CacheEntry should be held onto as long as the 
 * data referenced in it is needed (which often is only one or two lines
 * of code)
 *
 */
public class SHA1EntryCache extends SHA256EntryCache {
    protected CacheEntry createNew(int payload) {
        return new SHA1CacheEntry(payload);
    }
    
    /**
     * all the data alloc'ed in a calculateHash call
     */
    public static class SHA1CacheEntry extends SHAEntryCache.CacheEntry {
        public SHA1CacheEntry(int payload) {
            wordlength = DSAEngine.getWordlength(payload);
            bucket = payload;
            hashbytes = new byte[20];
            M0 = new int[wordlength];
            W = new int[80];
            H = new int[5];
            hash = new Hash();
            hash.setData(hashbytes);
        }
    }
}
