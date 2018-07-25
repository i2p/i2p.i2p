package net.i2p.util;

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
 * @since 0.9.36
 */
public class TryCache<T> {

    private static final boolean DEBUG_DUP = false;

    /**
     * Something that creates objects of the type cached by this cache
     *
     * @param <T>
     */
    public static interface ObjectFactory<T> {
        T newInstance();
    }
    
    private final ObjectFactory<T> factory;
    protected final int capacity;
    protected final List<T> items;
    protected final Lock lock = new ReentrantLock();
    protected long _lastUnderflow;
    
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
    public T acquire() {
        T rv = null;
        if (lock.tryLock()) {
            try {
                if (!items.isEmpty()) {
                    rv = items.remove(items.size() - 1);
                } else {
                    _lastUnderflow = System.currentTimeMillis();
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
    public void release(T item) {
        if (lock.tryLock()) {
            try {
                if (DEBUG_DUP) {
                    for (int i = 0; i < items.size(); i++) {
                        // use == not equals() because ByteArray.equals()
                        if (items.get(i) == item) {
                            net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(TryCache.class).log(Log.CRIT,
                                "dup release of " + item.getClass(), new Exception("I did it"));
                            return;
                        }
                    }
                }
                if (items.size() < capacity) {
                    if (DEBUG_DUP)
                        items.add(0, item);
                    else
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
