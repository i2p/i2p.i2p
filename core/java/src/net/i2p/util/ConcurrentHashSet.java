package net.i2p.util;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 *  Implement on top of a ConcurrentHashMap with a dummy value.
 *
 *  @author zzz
 */
public class ConcurrentHashSet<E> extends AbstractSet<E> implements Set<E> {
    private static final Object DUMMY = new Object();
    private final Map<E, Object> _map;

    public ConcurrentHashSet() {
        _map = new ConcurrentHashMap();
    }
    public ConcurrentHashSet(int capacity) {
        _map = new ConcurrentHashMap(capacity);
    }

    @Override
    public boolean add(E o) {
        return _map.put(o, DUMMY) == null;
    }

    @Override
    public void clear() {
        _map.clear();
    }

    @Override
    public boolean contains(Object o) {
        return _map.containsKey(o);
    }

    @Override
    public boolean isEmpty() {
        return _map.isEmpty();
    }

    @Override
    public boolean remove(Object o) {
        return _map.remove(o) != null;
    }

    public int size() {
        return _map.size();
    }

    public Iterator<E> iterator() {
        return _map.keySet().iterator();
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean rv = false;
        for (E e : c)
            rv |= _map.put(e, DUMMY) == null;
        return rv;
    }
}
