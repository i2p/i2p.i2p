/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Naming services create a subclass of this class.
 */
public abstract class NamingService {

    private final static Log _log = new Log(NamingService.class);
    protected I2PAppContext _context;
    private /* FIXME final FIXME */ HashMap _cache;

    /** what classname should be used as the naming service impl? */
    public static final String PROP_IMPL = "i2p.naming.impl";
    private static final String DEFAULT_IMPL = "net.i2p.client.naming.HostsTxtNamingService";

    protected static final int CACHE_MAX_SIZE = 16;

    
    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    protected NamingService(I2PAppContext context) {
        _context = context;
        _cache = new HashMap(CACHE_MAX_SIZE);
    }
    private NamingService() { // nop
    }
    
    /**
     * Look up a host name.
     * @return the Destination for this host name, or
     * <code>null</code> if name is unknown.
     */
    public abstract Destination lookup(String hostname);

    /**
     * Reverse look up a destination
     * @return a host name for this Destination, or <code>null</code>
     * if none is known. It is safe for subclasses to always return
     * <code>null</code> if no reverse lookup is possible.
     */
    public String reverseLookup(Destination dest) { return null; };

    /** @deprecated unused */
    public String reverseLookup(Hash h) { return null; };

    /**
     * Check if host name is valid Base64 encoded dest and return this
     * dest in that case. Useful as a "fallback" in custom naming
     * implementations.
     */
    protected Destination lookupBase64(String hostname) {
        try {
            Destination result = new Destination();
            result.fromBase64(hostname);
            return result;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Error translating [" + hostname + "]", dfe);
            return null;
        }
    }

    /**
     * Get a naming service instance. This method ensures that there
     * will be only one naming service instance (singleton) as well as
     * choose the implementation from the "i2p.naming.impl" system
     * property.
     */
    public static final synchronized NamingService createInstance(I2PAppContext context) {
        NamingService instance = null;
        String impl = context.getProperty(PROP_IMPL, DEFAULT_IMPL);
        try {
            Class cls = Class.forName(impl);
            Constructor con = cls.getConstructor(new Class[] { I2PAppContext.class });
            instance = (NamingService)con.newInstance(new Object[] { context });
        } catch (Exception ex) {
            _log.error("Cannot load naming service " + impl, ex);
            instance = new DummyNamingService(context); // fallback
        }
        return instance;
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
        Object oldestKey = null;
        long oldestExp = Long.MAX_VALUE;
        ArrayList deleteList = new ArrayList(CACHE_MAX_SIZE);
        for (Iterator iter = _cache.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry entry = (Map.Entry) iter.next();
            CacheEntry ce = (CacheEntry) entry.getValue();
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
        for (Iterator iter = deleteList.iterator(); iter.hasNext(); ) {
            _cache.remove(iter.next());
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
            CacheEntry ce = (CacheEntry) _cache.get(s);
            if (ce == null)
                return null;
            if (ce.isExpired()) {
                _cache.remove(s);
                return null;
            }
            return ce.dest;
        }
    }

    /** @since 0.8.1 */
    public void clearCache() {
        synchronized (_cache) {
            _cache.clear();
        }
    }
}
