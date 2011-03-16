/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;

/**
 * A Dummy naming service that can only handle base64 and b32 destinations.
 */
class DummyNamingService extends NamingService {

    private static final int BASE32_HASH_LENGTH = 52;   // 1 + Hash.HASH_LENGTH * 8 / 5
    public final static String PROP_B32 = "i2p.naming.hostsTxt.useB32";
    protected static final int CACHE_MAX_SIZE = 32;
    public static final int DEST_SIZE = 516;                    // Std. Base64 length (no certificate)

    /**
     *  The LRU cache, with no expiration time.
     *  Classes should take care to call removeCache() for any entries that
     *  are invalidated.
     */
    private static final Map<String, Destination> _cache = new LHM(CACHE_MAX_SIZE);

    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    protected DummyNamingService(I2PAppContext context) {
        super(context);
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
     *  Provide basic static caching for all services
     */
    protected static void putCache(String s, Destination d) {
        if (d == null)
            return;
        synchronized (_cache) {
            _cache.put(s, d);
        }
    }

    /** @return cached dest or null */
    protected static Destination getCache(String s) {
        synchronized (_cache) {
            return _cache.get(s);
        }
    }

    /** @since 0.8.5 */
    protected static void removeCache(String s) {
        synchronized (_cache) {
            _cache.remove(s);
        }
    }

    /** @since 0.8.1 */
    protected static void clearCache() {
        synchronized (_cache) {
            _cache.clear();
        }
    }

    private static class LHM<K, V> extends LinkedHashMap<K, V> {
        private final int _max;

        public LHM(int max) {
            super(max, 0.75f, true);
            _max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > _max;
        }
    }
}
