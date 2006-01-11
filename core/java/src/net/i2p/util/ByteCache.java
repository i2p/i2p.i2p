package net.i2p.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

/**
 * Cache the objects frequently used to reduce memory churn.  The ByteArray 
 * should be held onto as long as the  data referenced in it is needed.
 *
 */
public final class ByteCache {
    private static Map _caches = new HashMap(16);
    /**
     * Get a cache responsible for objects of the given size
     *
     * @param cacheSize how large we want the cache to grow before using on 
     *                  demand allocation
     * @param size how large should the objects cached be?
     */
    public static ByteCache getInstance(int cacheSize, int size) {
        Integer sz = new Integer(size);
        ByteCache cache = null;
        synchronized (_caches) {
            if (!_caches.containsKey(sz))
                _caches.put(sz, new ByteCache(cacheSize, size));
            cache = (ByteCache)_caches.get(sz);
        }
        cache.resize(cacheSize);
        return cache;
    }
    private Log _log;
    /** list of available and available entries */
    private List _available;
    private int _maxCached;
    private int _entrySize;
    private long _lastOverflow;
    
    /** do we actually want to cache? */
    private static final boolean _cache = true;
    
    /** how often do we cleanup the cache */
    private static final int CLEANUP_FREQUENCY = 30*1000;
    /** if we haven't exceeded the cache size in 2 minutes, cut our cache in half */
    private static final long EXPIRE_PERIOD = 2*60*1000;
    
    private ByteCache(int maxCachedEntries, int entrySize) {
        if (_cache)
            _available = new ArrayList(maxCachedEntries);
        _maxCached = maxCachedEntries;
        _entrySize = entrySize;
        _lastOverflow = -1;
        SimpleTimer.getInstance().addEvent(new Cleanup(), CLEANUP_FREQUENCY);
        _log = I2PAppContext.getGlobalContext().logManager().getLog(ByteCache.class);
    }
    
    private void resize(int maxCachedEntries) {
        if (_maxCached >= maxCachedEntries) return;
        _maxCached = maxCachedEntries;
    }
    
    /**
     * Get the next available structure, either from the cache or a brand new one
     *
     */
    public final ByteArray acquire() {
        if (_cache) {
            synchronized (_available) {
                if (_available.size() > 0)
                    return (ByteArray)_available.remove(0);
            }
        }
        _lastOverflow = System.currentTimeMillis();
        byte data[] = new byte[_entrySize];
        ByteArray rv = new ByteArray(data);
        rv.setValid(0);
        rv.setOffset(0);
        return rv;
    }
    
    /**
     * Put this structure back onto the available cache for reuse
     *
     */
    public final void release(ByteArray entry) {
        release(entry, true);
    }
    public final void release(ByteArray entry, boolean shouldZero) {
        if (_cache) {
            if ( (entry == null) || (entry.getData() == null) )
                return;
            
            entry.setValid(0);
            entry.setOffset(0);
            
            if (shouldZero)
                Arrays.fill(entry.getData(), (byte)0x0);
            synchronized (_available) {
                if (_available.size() < _maxCached)
                    _available.add(entry);
            }
        }
    }
    
    private class Cleanup implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (System.currentTimeMillis() - _lastOverflow > EXPIRE_PERIOD) {
                // we haven't exceeded the cache size in a few minutes, so lets
                // shrink the cache 
                synchronized (_available) {
                    int toRemove = _available.size() / 2;
                    for (int i = 0; i < toRemove; i++)
                        _available.remove(0);
                    if ( (toRemove > 0) && (_log.shouldLog(Log.DEBUG)) )
                        _log.debug("Removing " + toRemove + " cached entries of size " + _entrySize);
                }
            }
            SimpleTimer.getInstance().addEvent(Cleanup.this, CLEANUP_FREQUENCY);
        }
    }
}
