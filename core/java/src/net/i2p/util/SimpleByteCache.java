package net.i2p.util;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;

/**
 * Like ByteCache but works directly with byte arrays, not ByteArrays.
 * These are designed to be small caches, so there's no cleaner task
 * like there is in ByteCache. And we don't zero out the arrays here.
 * Only the static methods are public here.
 *
 * @since 0.8.3
 */
public final class SimpleByteCache {

    private static final Map<Integer, SimpleByteCache> _caches = new ConcurrentHashMap(8);

    private static final int DEFAULT_SIZE = 16;

    /**
     * Get a cache responsible for arrays of the given size
     *
     * @param size how large should the objects cached be?
     */
    public static SimpleByteCache getInstance(int size) {
        return getInstance(DEFAULT_SIZE, size);
    }

    /**
     * Get a cache responsible for objects of the given size
     *
     * @param cacheSize how large we want the cache to grow 
     *                  (number of objects, NOT memory size)
     *                  before discarding released objects.
     * @param size how large should the objects cached be?
     */
    public static SimpleByteCache getInstance(int cacheSize, int size) {
        Integer sz = Integer.valueOf(size);
        SimpleByteCache cache = _caches.get(sz);
        if (cache == null) {
            cache = new SimpleByteCache(cacheSize, size);
            _caches.put(sz, cache);
        }
        cache.resize(cacheSize);
        return cache;
    }

    /**
     *  Clear everything (memory pressure)
     */
    public static void clearAll() {
        for (SimpleByteCache bc : _caches.values())
            bc.clear();
    }

    /** list of available and available entries */
    private Queue<byte[]> _available;
    private int _maxCached;
    private int _entrySize;
    
    private SimpleByteCache(int maxCachedEntries, int entrySize) {
        _available = new LinkedBlockingQueue(maxCachedEntries);
        _maxCached = maxCachedEntries;
        _entrySize = entrySize;
    }
    
    private void resize(int maxCachedEntries) {
        if (_maxCached >= maxCachedEntries) return;
        _maxCached = maxCachedEntries;
        // make a bigger one, move the cached items over
        Queue<byte[]> newLBQ = new LinkedBlockingQueue(maxCachedEntries);
        byte[] ba;
        while ((ba = _available.poll()) != null)
            newLBQ.offer(ba);
        _available = newLBQ;
    }
    
    /**
     * Get the next available array, either from the cache or a brand new one
     */
    public static byte[] acquire(int size) {
        return getInstance(size).acquire();
    }

    /**
     * Get the next available array, either from the cache or a brand new one
     */
    private byte[] acquire() {
        byte[] rv = _available.poll();
        if (rv == null)
            rv = new byte[_entrySize];
        return rv;
    }
    
    /**
     * Put this array back onto the available cache for reuse
     */
    public static void release(byte[] entry) {
        SimpleByteCache cache = _caches.get(entry.length);
        if (cache != null)
            cache.releaseIt(entry);
    }

    /**
     * Put this array back onto the available cache for reuse
     */
    private void releaseIt(byte[] entry) {
        if (entry == null || entry.length != _entrySize)
            return;
        // should be safe without this
        //Arrays.fill(entry, (byte) 0);
        _available.offer(entry);
    }
    
    /**
     *  Clear everything (memory pressure)
     */
    private void clear() {
        _available.clear();
    }
}
