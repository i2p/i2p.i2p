package net.i2p.util;

import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *  Count things.
 *
 *  @author zzz, welterde
 */
public class ObjectCounter<K> implements Serializable {
    /**
     * Serializable so it can be passed in an Android Bundle
     */
    private static final long serialVersionUID = 3160378641721937421L;

    private final ConcurrentHashMap<K, AtomicInteger> map;

    public ObjectCounter() {
        this.map = new ConcurrentHashMap<K, AtomicInteger>();
    }

    /**
     *  Add one.
     *  @return count after increment
     */
    public int increment(K h) {
        AtomicInteger i = this.map.putIfAbsent(h, new AtomicInteger(1));
        if (i != null)
            return i.incrementAndGet();
        return 1;
    }

    /**
     *  @return current count
     */
    public int count(K h) {
        AtomicInteger i = this.map.get(h);
        if (i != null)
            return i.get();
        return 0;
    }

    /**
     *  @return set of objects with counts &gt; 0
     */
    public Set<K> objects() {
        return this.map.keySet();
    }

    /**
     *  Start over. Reset the count for all keys to zero.
     *  @since 0.7.11
     */
    public void clear() {
        this.map.clear();
    }

    /**
     *  Reset the count for this key to zero
     *  @since 0.9.36
     */
    public void clear(K h) {
        this.map.remove(h);
    }
}

