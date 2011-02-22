/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;

/**
 * A Dummy naming service that can only handle base64 and b32 destinations.
 */
class DummyNamingService extends NamingService {
    private final Map<String, CacheEntry> _cache;

    private static final int BASE32_HASH_LENGTH = 52;   // 1 + Hash.HASH_LENGTH * 8 / 5
    public final static String PROP_B32 = "i2p.naming.hostsTxt.useB32";
    protected static final int CACHE_MAX_SIZE = 16;
    public static final int DEST_SIZE = 516;                    // Std. Base64 length (no certificate)

    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    protected DummyNamingService(I2PAppContext context) {
        super(context);
        _cache = new HashMap(CACHE_MAX_SIZE);
    }
    
    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        Destination d = getCache(hostname);
        if (d != null)
            return d;

        // If it's long, assume it's a key.
        if (hostname.length() >= 516) {
            d = lookupBase64(hostname);
            // What the heck, cache these too
            putCache(hostname, d);
            return d;
        }

        // Try Base32 decoding
        if (hostname.length() == BASE32_HASH_LENGTH + 8 && hostname.endsWith(".b32.i2p") &&
            _context.getBooleanPropertyDefaultTrue(PROP_B32)) {
            d = LookupDest.lookupBase32Hash(_context, hostname.substring(0, BASE32_HASH_LENGTH));
            if (d != null) {
                putCache(hostname, d);
                return d;
            }
        }

        return null;
    }

    /**
     *  Provide basic caching for the service
     *  The service may override the age and/or size limit
     */
    /** Don't know why a dest would ever change but keep this short anyway */
    protected static final long CACHE_MAX_AGE = 7*60*1000;

    private class CacheEntry {
        public Destination dest;
        public long exp;
        public CacheEntry(Destination d) {
            dest = d;
            exp = _context.clock().now() + CACHE_MAX_AGE;
        }
        public boolean isExpired() {
            return exp < _context.clock().now();
        }
    }

    /**
     * Clean up when full.
     * Don't bother removing old entries unless full.
     * Caller must synchronize on _cache.
     */
    private void cacheClean() {
        if (_cache.size() < CACHE_MAX_SIZE)
            return;
        boolean full = true;
        String oldestKey = null;
        long oldestExp = Long.MAX_VALUE;
        List<String> deleteList = new ArrayList(CACHE_MAX_SIZE);
        for (Map.Entry<String, CacheEntry> entry : _cache.entrySet()) {
            CacheEntry ce = entry.getValue();
            if (ce.isExpired()) {
                deleteList.add(entry.getKey());
                full = false;
                continue;
            }
            if (oldestKey == null || ce.exp < oldestExp) {
                oldestKey = entry.getKey();
                oldestExp = ce.exp;
            }
        }
        if (full && oldestKey != null)
            deleteList.add(oldestKey);
        for (String s : deleteList) {
            _cache.remove(s);
        }
    }

    protected void putCache(String s, Destination d) {
        if (d == null)
            return;
        synchronized (_cache) {
            _cache.put(s, new CacheEntry(d));
            cacheClean();
        }
    }

    protected Destination getCache(String s) {
        synchronized (_cache) {
            CacheEntry ce = _cache.get(s);
            if (ce == null)
                return null;
            if (ce.isExpired()) {
                _cache.remove(s);
                return null;
            }
            return ce.dest;
        }
    }

    /** @since 0.8.5 */
    protected void removeCache(String s) {
        synchronized (_cache) {
            _cache.remove(s);
        }
    }

    /** @since 0.8.1 */
    public void clearCache() {
        synchronized (_cache) {
            _cache.clear();
        }
    }
}
