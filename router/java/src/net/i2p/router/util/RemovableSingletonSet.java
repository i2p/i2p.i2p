package net.i2p.router.util;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 *  Like Collections.singleton() but item is removable,
 *  clear() is supported, and the iterator supports remove().
 *  Item may not be null. add() and addAll() unsupported.
 *  Unsynchronized.
 *
 *  @since 0.9.7
 */
public class RemovableSingletonSet<E> extends AbstractSet<E> {
    private E _elem;

    public RemovableSingletonSet(E element) {
        if (element == null)
            throw new NullPointerException();
        _elem = element;
    }

    @Override
    public void clear() {
        _elem = null;
    }

    @Override
    public boolean contains(Object o) {
        return o != null && o.equals(_elem);
    }

    @Override
    public boolean isEmpty() {
        return _elem == null;
    }

    @Override
    public boolean remove(Object o) {
        boolean rv = o.equals(_elem);
        if (rv)
            _elem = null;
        return rv;
    }

    public int size() {
        return _elem != null ? 1 : 0;
    }

    public Iterator<E> iterator() {
        return new RSSIterator();
    }

    private class RSSIterator implements Iterator<E> {
        boolean done;

        public boolean hasNext() {
            return _elem != null && !done;
        }

        public E next() {
            if (!hasNext())
                throw new NoSuchElementException();
            done = true;
            return _elem;
        }

        public void remove() {
            if (_elem == null || !done)
                throw new IllegalStateException();
            _elem = null;
        }
    }
}
    
