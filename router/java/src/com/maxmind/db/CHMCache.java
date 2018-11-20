package com.maxmind.db;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simplistic cache using a {@link ConcurrentHashMap}. There's no eviction
 * policy, it just fills up until reaching the specified capacity <small>(or
 * close enough at least, bounds check is not atomic :)</small>
 */
public class CHMCache implements NodeCache {

    private static final int DEFAULT_CAPACITY = 4096;

    private final int capacity;
    private final ConcurrentHashMap<Integer, Object> cache;
    private boolean cacheFull = false;

    public CHMCache() {
        this(DEFAULT_CAPACITY);
    }

    public CHMCache(int capacity) {
        this.capacity = capacity;
        this.cache = new ConcurrentHashMap<Integer, Object>(capacity);
    }

    @Override
    public Object get(int key, Loader loader) throws IOException {
        Integer k = key;
        Object value = cache.get(k);
        if (value == null) {
            value = loader.load(key);
            if (!cacheFull) {
                if (cache.size() < capacity) {
                    cache.put(k, value);
                } else {
                    cacheFull = true;
                }
            }
        }
        return value;
    }

}
