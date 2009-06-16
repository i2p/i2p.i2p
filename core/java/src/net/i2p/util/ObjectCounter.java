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
    public ObjectCounter() {
        _map = new ConcurrentHashMap();
    }
    /**
     *  Add one.
     *  Not perfectly concurrent, new AtomicInteger(1) would be better,
     *  at the cost of some object churn.
     */
    public void increment(K h) {
        Integer i = _map.putIfAbsent(h, Integer.valueOf(1));
        if (i != null)
            _map.put(h, Integer.valueOf(i.intValue() + 1));
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
}

