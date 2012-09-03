package net.i2p.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 *  A LinkedHashMap with a maximum size, for use as
 *  an LRU cache. Unsynchronized.
 *
 *  @since 0.9.3
 */
public class LHMCache<K, V> extends LinkedHashMap<K, V> {
    private final int _max;

    public LHMCache(int max) {
        super(max, 0.75f, true);
        _max = max;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > _max;
    }
}
