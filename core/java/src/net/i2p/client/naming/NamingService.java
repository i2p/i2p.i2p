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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

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
    protected final I2PAppContext _context;
    protected final Set<NamingServiceListener> _listeners;
    protected final Set<NamingServiceUpdater> _updaters;

    /** what classname should be used as the naming service impl? */
    public static final String PROP_IMPL = "i2p.naming.impl";
    private static final String DEFAULT_IMPL = "net.i2p.client.naming.HostsTxtNamingService";
    
    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    protected NamingService(I2PAppContext context) {
        _context = context;
        _listeners = new CopyOnWriteArraySet();
        _updaters = new CopyOnWriteArraySet();
    }
    
    /**
     * Look up a host name.
     * @return the Destination for this host name, or
     * <code>null</code> if name is unknown.
     */
    public Destination lookup(String hostname) {
        return lookup(hostname, null, null);
    }

    /**
     * Reverse look up a destination
     * @return a host name for this Destination, or <code>null</code>
     * if none is known. It is safe for subclasses to always return
     * <code>null</code> if no reverse lookup is possible.
     */
    public String reverseLookup(Destination dest) {
        return reverseLookup(dest, null);
    }

    /** @deprecated unused */
    public String reverseLookup(Hash h) { return null; }

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

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    ///// New API Starts Here

    /**
     *  @return Class simple name by default
     *  @since 0.8.5
     */
    public String getName() {
        return getClass().getSimpleName();
    }

    /**
     *  @return NamingService-specific options or null
     *  @since 0.8.5
     */
    public Properties getConfiguration() {
        return null;
    }

    /**
     *  @return success
     *  @since 0.8.5
     */
    public boolean setConfiguration(Properties p) {
        return true;
    }

    // These are for daisy chaining (MetaNamingService)

    /**
     *  @return chained naming services or null
     *  @since 0.8.5
     */
    public List<NamingService> getNamingServices() {
        return null;
    }

    /**
     *  @return parent naming service or null if this is the root
     *  @since 0.8.5
     */
    public NamingService getParent() {
        return null;
    }

    /**
     * Only for chaining-capable NamingServices. Add to end of the list.
     * @return success
     */
    public boolean addNamingService(NamingService ns) {
        return addNamingService(ns, false);
    }


    /**
     * Only for chaining-capable NamingServices
     * @param head or tail
     * @return success
     */
    public boolean addNamingService(NamingService ns, boolean head) {
        return false;
    }

    /**
     *  Only for chaining-capable NamingServices
     *  @return success
     *  @since 0.8.5
     */
    public boolean removeNamingService(NamingService ns) {
        return false;
    }

    // options would be used to specify public / private / master ...
    // or should we just daisy chain 3 HostsTxtNamingServices ?
    // that might be better... then addressbook only talks to the 'router' HostsTxtNamingService

    /**
     *  @return number of entries or -1 if unknown
     *  @since 0.8.5
     */
    public int size() {
        return size(null);
    }

    /**
     *  @param options NamingService-specific, can be null
     *  @return number of entries (matching the options if non-null) or -1 if unknown
     *  @since 0.8.5
     */
    public int size(Properties options) {
        return -1;
    }

    /**
     *  @return all mappings
     *          or empty Map if none;
     *          Returned Map is not necessarily sorted, implementation dependent
     *  @since 0.8.5
     */
    public Map<String, Destination> getEntries() {
        return getEntries(null);
    }

    /**
     *  @param options NamingService-specific, can be null
     *  @return all mappings (matching the options if non-null)
     *          or empty Map if none;
     *          Returned Map is not necessarily sorted, implementation dependent
     *  @since 0.8.5
     */
    public Map<String, Destination> getEntries(Properties options) {
        return Collections.EMPTY_MAP;
    }

    /**
     *  This may be more or less efficient than getEntries()
     *  @param options NamingService-specific, can be null
     *  @return all mappings (matching the options if non-null)
     *          or empty Map if none;
     *          Returned Map is not necessarily sorted, implementation dependent
     *  @since 0.8.5
     */
    public Map<String, String> getBase64Entries(Properties options) {
        return Collections.EMPTY_MAP;
    }

    /**
     *  @return all known host names
     *          or empty Set if none;
     *          Returned Set is not necessarily sorted, implementation dependent
     *  @since 0.8.5
     */
    public Set<String> getNames() {
        return getNames(null);
    }

    /**
     *  @param options NamingService-specific, can be null
     *  @return all known host names (matching the options if non-null)
     *          or empty Set if none;
     *          Returned Set is not necessarily sorted, implementation dependent
     *  @since 0.8.5
     */
    public Set<String> getNames(Properties options) {
        return Collections.EMPTY_SET;
    }

    /**
     *  @return success
     *  @since 0.8.5
     */
    public boolean put(String hostname, Destination d) {
        return put(hostname, d, null);
    }

    /**
     *  @param options NamingService-specific, can be null
     *  @return success
     *  @since 0.8.5
     */
    public boolean put(String hostname, Destination d, Properties options) {
        return false;
    }

    /**
     *  Fails if entry previously exists
     *  @return success
     *  @since 0.8.5
     */
    public boolean putIfAbsent(String hostname, Destination d) {
        return putIfAbsent(hostname, d, null);
    }

    /**
     *  Fails if entry previously exists
     *  @param options NamingService-specific, can be null
     *  @return success
     *  @since 0.8.5
     */
    public boolean putIfAbsent(String hostname, Destination d, Properties options) {
        return false;
    }

    /**
     *  @param options NamingService-specific, can be null
     *  @return success
     *  @since 0.8.5
     */
    public boolean putAll(Map<String, Destination> entries, Properties options) {
        boolean rv = true;
        for (Map.Entry<String, Destination> entry : entries.entrySet()) {
            if (!put(entry.getKey(), entry.getValue(), options))
                rv = false;
        }
        return rv;
    }

    /**
     *  Fails if entry did not previously exist
     *  @param d may be null if only options are changing
     *  @param options NamingService-specific, can be null
     *  @return success
     *  @since 0.8.5
     */
    public boolean update(String hostname, Destination d, Properties options) {
        return false;
    }

    /**
     *  @return success
     *  @since 0.8.5
     */
    public boolean remove(String hostname) {
        return remove(hostname, null);
    }

    /**
     *  @param options NamingService-specific, can be null
     *  @return success
     *  @since 0.8.5
     */
    public boolean remove(String hostname, Properties options) {
        return false;
    }

    /**
     *  Ask any registered updaters to update now
     *  @param options NamingService- or updater-specific, may be null
     *  @since 0.8.5
     */
    public void requestUpdate(Properties options) {
        for (NamingServiceUpdater nsu : _updaters) {
            nsu.update(options);
        }
    }

    /**
     *  @since 0.8.5
     */
    public void registerListener(NamingServiceListener nsl) {
        _listeners.add(nsl);
    }

    /**
     *  @since 0.8.5
     */
    public void unregisterListener(NamingServiceListener nsl) {
        _listeners.remove(nsl);
    }

    /**
     *  @since 0.8.6
     */
    public void registerUpdater(NamingServiceUpdater nsu) {
        _updaters.add(nsu);
    }

    /**
     *  @since 0.8.6
     */
    public void unregisterUpdater(NamingServiceUpdater nsu) {
        _updaters.remove(nsu);
    }

    /**
     *  Same as lookup(hostname) but with in and out options
     *  Note that whether this (and lookup(hostname)) resolve B32 addresses is
     *  NamingService-specific.
     *  @param lookupOptions input parameter, NamingService-specific, can be null
     *  @param storedOptions output parameter, NamingService-specific, any stored properties will be added if non-null
     *  @return dest or null
     *  @since 0.8.5
     */
    public abstract Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions);

    /**
     *  Same as reverseLookup(dest) but with options
     *  @param options NamingService-specific, can be null
     *  @return host name or null
     *  @since 0.8.5
     */
    public String reverseLookup(Destination d, Properties options) {
        return null;
    }

    /**
     *  Lookup a Base 32 address. This may require the router to fetch the LeaseSet,
     *  which may take quite a while.
     *  @param hostname must be {52 chars}.b32.i2p
     *  @param timeout in seconds; <= 0 means use router default
     *  @return dest or null
     *  @since 0.8.5
     */
    public Destination lookupBase32(String hostname, int timeout) {
        return null;
    }

    /**
     *  Same as lookupB32 but with the SHA256 Hash precalculated
     *  @param timeout in seconds; <= 0 means use router default
     *  @return dest or null
     *  @since 0.8.5
     */
    public Destination lookup(Hash hash, int timeout) {
        return null;
    }

    /**
     *  Parent will call when added.
     *  If this is the root naming service, the core will start it.
     *  Should not be called by others.
     *  @since 0.8.5
     */
    public void start() {}

    /**
     *  Parent will call when removed.
     *  If this is the root naming service, the core will stop it.
     *  Should not be called by others.
     *  @since 0.8.5
     */
    public void stop() {}

    //// End New API

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

}
