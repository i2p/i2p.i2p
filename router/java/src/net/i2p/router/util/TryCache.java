package net.i2p.router.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An object cache which is safe to use by multiple threads without blocking.
 * 
 * @author zab
 *
 * @param <T>
 */
public class TryCache<T> {

    /**
     * Something that creates objects of the type cached by this cache
     *
     * @param <T>
     */
    public static interface ObjectFactory<T> {
        T newInstance();
    }
    
    private final ObjectFactory<T> factory;
    private final int capacity;
    private final List<T> items;
    private final Lock lock = new ReentrantLock();
    
    /**
     * @param factory to be used for creating new instances
     * @param capacity cache up to this many items
     */
    public TryCache(ObjectFactory<T> factory, int capacity) {
        this.factory = factory;
        this.capacity = capacity;
        this.items = new ArrayList<>(capacity);
    }
    
    /**
     * @return a cached or newly created item from this cache
     */
    public T tryAcquire() {
        T rv = null;
        if (lock.tryLock()) {
            try {
                if (!items.isEmpty()) {
                    rv = items.remove(items.size() - 1);
                }
            } finally {
                lock.unlock();
            }
        }
        
        if (rv == null) {
            rv = factory.newInstance();
        }
        return rv;
    }
    
    /**
     * Tries to return this item to the cache but it may fail if
     * the cache has reached capacity or it's lock is held by
     * another thread.
     */
    public void tryRelease(T item) {
        if (lock.tryLock()) {
            try {
                if (items.size() < capacity) {
                    items.add(item);
                }
            } finally {
                lock.unlock();
            }
        }
    }
    
    /**
     * Clears all cached items.  This is the only method
     * that blocks until it acquires the lock.
     */
    public void clear() {
        lock.lock();
        try {
            items.clear();
        } finally {
            lock.unlock();
        }
    }
}
