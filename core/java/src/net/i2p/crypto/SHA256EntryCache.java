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
public class SHA256EntryCache extends SHAEntryCache {
    public SHA256EntryCache() {
        super();
    }
    
    protected CacheEntry createNew(int payload) {
        return new SHA256CacheEntry(payload);
    }
    
    /**
     * all the data alloc'ed in a calculateHash call
     */
    public static class SHA256CacheEntry extends SHAEntryCache.CacheEntry {
        public SHA256CacheEntry(int payload) {
            wordlength = SHA256Generator.getWordlength(payload);
            bucket = payload;
            hashbytes = new byte[32];
            M0 = new int[wordlength];
            W = new int[64];
            H = new int[8];
            hash = new Hash();
            hash.setData(hashbytes);
        }
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
