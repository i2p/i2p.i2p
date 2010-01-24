package net.i2p.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 *  Count things.
 *
 *  @author zzz
 */
public class ObjectCounter<K> {
    private ConcurrentHashMap<K, Integer> _map;
    private static final Integer ONE = Integer.valueOf(1);

    public ObjectCounter() {
        _map = new ConcurrentHashMap();
    }

    /**
     *  Add one.
     *  Not perfectly concurrent, new AtomicInteger(1) would be better,
     *  at the cost of some object churn.
     *  @return count after increment
     */
    public int increment(K h) {
        Integer i = _map.putIfAbsent(h, ONE);
        if (i != null) {
            int rv = i.intValue() + 1;
            _map.put(h, Integer.valueOf(rv));
            return rv;
        }
        return 1;
    }

    /**
     *  @return current count
     */
    public int count(K h) {
        Integer i = _map.get(h);
        if (i != null)
            return i.intValue();
        return 0;
    }

    /**
     *  @return set of objects with counts > 0
     */
    public Set<K> objects() {
        return _map.keySet();
    }

    /**
     *  start over
     *  @since 0.7.11
     */
    public void clear() {
        _map.clear();
    }
}

