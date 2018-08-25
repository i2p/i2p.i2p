package net.i2p.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Like ByteCache but works directly with byte arrays, not ByteArrays.
 * These are designed to be small caches, so there's no cleaner task
 * like there is in ByteCache. And we don't zero out the arrays here.
 * Only the static methods are public here.
 *
 * @since 0.8.3
 */
public final class SimpleByteCache {

    private static final ConcurrentHashMap<Integer, SimpleByteCache> _caches = new ConcurrentHashMap<Integer, SimpleByteCache>(8);

    private static final int DEFAULT_SIZE = 64;

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
            SimpleByteCache old = _caches.putIfAbsent(sz, cache);
            if (old != null)
                cache = old;
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

    private final TryCache<byte[]> _available;
    private final int _entrySize;
    
    /** @since 0.9.36 */
    private static class ByteArrayFactory implements TryCache.ObjectFactory<byte[]> {
        private final int sz;

        ByteArrayFactory(int entrySize) {
            sz = entrySize;
        }

        public byte[] newInstance() {
            return new byte[sz];
        }
    }

    private SimpleByteCache(int maxCachedEntries, int entrySize) {
        _available = new TryCache<byte[]>(new ByteArrayFactory(entrySize), maxCachedEntries);
        _entrySize = entrySize;
    }
    
    private void resize(int maxCachedEntries) {
        // _available is now final, and getInstance() is not used anywhere,
        // all call sites just use static acquire()
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
        return _available.acquire();
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
        _available.release(entry);
    }
    
    /**
     *  Clear everything (memory pressure)
     */
    private void clear() {
        _available.clear();
    }
}
