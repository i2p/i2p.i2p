/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
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

    protected final Log _log;
    protected final I2PAppContext _context;
    protected final Set<NamingServiceListener> _listeners;
    protected final Set<NamingServiceUpdater> _updaters;

    /** what classname should be used as the naming service impl? */
    public static final String PROP_IMPL = "i2p.naming.impl";
    private static final String DEFAULT_IMPL = "net.i2p.router.naming.BlockfileNamingService";
    private static final String OLD_DEFAULT_IMPL = "net.i2p.client.naming.BlockfileNamingService";
    private static final String BACKUP_IMPL = "net.i2p.client.naming.HostsTxtNamingService";
    
    /** 
     * The naming service should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    protected NamingService(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(getClass());
        _listeners = new CopyOnWriteArraySet<NamingServiceListener>();
        _updaters = new CopyOnWriteArraySet<NamingServiceUpdater>();
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
     * Reverse lookup a destination
     * This implementation returns reverseLookup(dest, null).
     *
     * @param dest non-null
     * @return a host name for this Destination, or <code>null</code>
     * if none is known. It is safe for subclasses to always return
     * <code>null</code> if no reverse lookup is possible.
     */
    public String reverseLookup(Destination dest) {
        return reverseLookup(dest, null);
    }

    /**
     * Reverse lookup a hash.
     * This implementation returns null.
     * Subclasses implementing reverse lookups should override.
     *
     * @param h non-null
     * @return a host name for this hash, or <code>null</code>
     * if none is known. It is safe for subclasses to always return
     * <code>null</code> if no reverse lookup is possible.
     */
    public String reverseLookup(Hash h) { return null; }

    /**
     * If the host name is a valid Base64 encoded destination, return the
     * decoded Destination. Useful as a "fallback" in custom naming
     * implementations.
     * This is misnamed as it isn't a "lookup" at all, but
     * a simple conversion from a Base64 string to a Destination.
     *
     * @param hostname 516+ character Base 64
     * @return Destination or null on error
     */
    protected Destination lookupBase64(String hostname) {
        try {
            Destination result = new Destination();
            result.fromBase64(hostname);
            return result;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Bad B64 dest [" + hostname + "]", dfe);
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
     *  @since 0.8.7
     */
    public String getName() {
        return getClass().getSimpleName();
    }

    /**
     *  Warning - unimplemented in any subclass.
     *  Returns null always.
     *
     *  @return NamingService-specific options or null
     *  @since 0.8.7
     */
    public Properties getConfiguration() {
        return null;
    }

    /**
     *  Warning - unimplemented in any subclass.
     *  Returns true always.
     *
     *  @return success
     *  @since 0.8.7
     */
    public boolean setConfiguration(Properties p) {
        return true;
    }

    // These are for daisy chaining (MetaNamingService)

    /**
     *  This implementation returns null.
     *  Subclasses implementing chaining should override.
     *
     *  @return chained naming services or null
     *  @since 0.8.7
     */
    public List<NamingService> getNamingServices() {
        return null;
    }

    /**
     *  This implementation returns null.
     *  Subclasses implementing chaining should override.
     *
     *  @return parent naming service or null if this is the root
     *  @since 0.8.7
     */
    public NamingService getParent() {
        return null;
    }

    /**
     * Only for chaining-capable NamingServices. Add to end of the list.
     * @return success
     * @since 0.8.7
     */
    public boolean addNamingService(NamingService ns) {
        return addNamingService(ns, false);
    }


    /**
     * Only for chaining-capable NamingServices.
     * This implementation returns false.
     * Subclasses implementing chaining should override.
     *
     * @param head or tail
     * @return success
     * @since 0.8.7
     */
    public boolean addNamingService(NamingService ns, boolean head) {
        return false;
    }

    /**
     * Only for chaining-capable NamingServices.
     * This implementation returns false.
     * Subclasses implementing chaining should override.
     *
     *  @return success
     *  @since 0.8.7
     */
    public boolean removeNamingService(NamingService ns) {
        return false;
    }

    // options would be used to specify public / private / master ...
    // or should we just daisy chain 3 HostsTxtNamingServices ?
    // that might be better... then addressbook only talks to the 'router' HostsTxtNamingService

    /**
     *  @return number of entries or -1 if unknown
     *  @since 0.8.7
     */
    public int size() {
        return size(null);
    }

    /**
     *  This implementation returns -1.
     *  Most subclasses should override.
     *
     *  @param options NamingService-specific, can be null
     *  @return number of entries (matching the options if non-null) or -1 if unknown
     *  @since 0.8.7
     */
    public int size(Properties options) {
        return -1;
    }

    /**
     *  Warning - This obviously brings the whole database into memory,
     *  so use is discouraged.
     *
     *  @return all mappings
     *          or empty Map if none;
     *          Returned Map is not necessarily sorted, implementation dependent
     *  @since 0.8.7
     */
    public Map<String, Destination> getEntries() {
        return getEntries(null);
    }

    /**
     *  Warning - This will bring the whole database into memory
     *  if options is null, empty, or unsupported, use with caution.
     *
     *  @param options NamingService-specific, can be null
     *  @return all mappings (matching the options if non-null)
     *          or empty Map if none;
     *          Returned Map is not necessarily sorted, implementation dependent
     *  @since 0.8.7
     */
    public Map<String, Destination> getEntries(Properties options) {
        return Collections.emptyMap();
    }

    /**
     *  This may be more or less efficient than getEntries(),
     *  depending on the implementation.
     *  Warning - This will bring the whole database into memory
     *  if options is null, empty, or unsupported, use with caution.
     *
     *  This implementation calls getEntries(options) and returns a SortedMap.
     *  Subclasses should override if they store base64 natively.
     *
     *  @param options NamingService-specific, can be null
     *  @return all mappings (matching the options if non-null)
     *          or empty Map if none;
     *          Returned Map is not necessarily sorted, implementation dependent
     *  @since 0.8.7, implemented in 0.9.20
     */
    public Map<String, String> getBase64Entries(Properties options) {
        Map<String, Destination> entries = getEntries(options);
        if (entries.size() <= 0)
            return Collections.emptyMap();
        Map<String, String> rv = new TreeMap<String, String>();
        for (Map.Entry<String, Destination> e : entries.entrySet()) {
             rv.put(e.getKey(), e.getValue().toBase64());
        }
        return rv;
    }

    /**
     *  Export in a hosts.txt format.
     *  Output is not necessarily sorted, implementation dependent.
     *  Output may or may not contain comment lines, implementation dependent.
     *  Caller must close writer.
     *
     *  This implementation calls getBase64Entries().
     *  Subclasses should override if they store in a hosts.txt format natively.
     *
     *  @since 0.9.20
     */
    public void export(Writer out) throws IOException {
        export(out, null);
    }

    /**
     *  Export in a hosts.txt format.
     *  Output is not necessarily sorted, implementation dependent.
     *  Output may or may not contain comment lines, implementation dependent.
     *  Caller must close writer.
     *
     *  This implementation calls getBase64Entries(options).
     *  Subclasses should override if they store in a hosts.txt format natively.
     *
     *  @param options NamingService-specific, can be null
     *  @since 0.9.20
     */
    public void export(Writer out, Properties options) throws IOException {
        Map<String, String> entries = getBase64Entries(options);
        out.write("# Address book: ");
        out.write(getName());
        if (options != null) {
            String list = options.getProperty("list");
            if (list != null)
                out.write(" (" + list + ')');
        }
        final String nl = System.getProperty("line.separator", "\n");
        out.write(nl);
        int sz = entries.size();
        if (sz <= 0) {
            out.write("# No entries");
            out.write(nl);
            return;
        }
        out.write("# Exported: ");
        out.write((new Date()).toString());
        out.write(nl);
        if (sz > 1) {
            out.write("# " + sz + " entries");
            out.write(nl);
        }
        for (Map.Entry<String, String> e : entries.entrySet()) {
            out.write(e.getKey());
            out.write('=');
            out.write(e.getValue());
            out.write(nl);
        }
    }

    /**
     *  @return all known host names
     *          or empty Set if none;
     *          Returned Set is not necessarily sorted, implementation dependent
     *  @since 0.8.7
     */
    public Set<String> getNames() {
        return getNames(null);
    }

    /**
     *  @param options NamingService-specific, can be null
     *  @return all known host names (matching the options if non-null)
     *          or empty Set if none;
     *          Returned Set is not necessarily sorted, implementation dependent
     *  @since 0.8.7
     */
    public Set<String> getNames(Properties options) {
        return Collections.emptySet();
    }

    /**
     *  Add a hostname and Destination to the addressbook.
     *  Overwrites old entry if it exists.
     *  See also putIfAbsent() and update().
     *
     *  @return success
     *  @since 0.8.7
     */
    public boolean put(String hostname, Destination d) {
        return put(hostname, d, null);
    }

    /**
     *  Add a hostname and Destination to the addressbook.
     *  Overwrites old entry if it exists.
     *  See also putIfAbsent() and update().
     *
     *  @param options NamingService-specific, can be null
     *  @return success
     *  @since 0.8.7
     */
    public boolean put(String hostname, Destination d, Properties options) {
        return false;
    }

    /**
     *  Add a hostname and Destination to the addressbook.
     *  Fails if entry previously exists.
     *  See also put() and update().
     *
     *  @return success
     *  @since 0.8.7
     */
    public boolean putIfAbsent(String hostname, Destination d) {
        return putIfAbsent(hostname, d, null);
    }

    /**
     *  Add a hostname and Destination to the addressbook.
     *  Fails if entry previously exists.
     *  See also put() and update().
     *
     *  @param options NamingService-specific, can be null
     *  @return success
     *  @since 0.8.7
     */
    public boolean putIfAbsent(String hostname, Destination d, Properties options) {
        return false;
    }

    /**
     *  Put all the entries, each with the given options.
     *  This implementation calls put() for each entry.
     *  Subclasses may override if a more efficient implementation is available.
     *
     *  @param options NamingService-specific, can be null
     *  @return total success, or false if any put failed
     *  @since 0.8.7
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
     *  Fails if entry did not previously exist.
     *  Warning - unimplemented in any subclass.
     *  This implementation returns false.
     *
     *  @param d may be null if only options are changing
     *  @param options NamingService-specific, can be null
     *  @return success
     *  @since 0.8.7
     */
    public boolean update(String hostname, Destination d, Properties options) {
        return false;
    }

    /**
     *  Delete the entry.
     *  @return true if removed successfully, false on error or if it did not exist
     *  @since 0.8.7
     */
    public boolean remove(String hostname) {
        return remove(hostname, (Properties) null);
    }

    /**
     *  Delete the entry.
     *  @param options NamingService-specific, can be null
     *  @return true if removed successfully, false on error or if it did not exist
     *  @since 0.8.7
     */
    public boolean remove(String hostname, Properties options) {
        return false;
    }

    /**
     *  Ask any registered updaters to update now
     *  @param options NamingService- or updater-specific, may be null
     *  @since 0.8.7
     */
    public void requestUpdate(Properties options) {
        for (NamingServiceUpdater nsu : _updaters) {
            nsu.update(options);
        }
    }

    /**
     *  @since 0.8.7
     */
    public void registerListener(NamingServiceListener nsl) {
        _listeners.add(nsl);
    }

    /**
     *  @since 0.8.7
     */
    public void unregisterListener(NamingServiceListener nsl) {
        _listeners.remove(nsl);
    }

    /**
     *  @since 0.8.7
     */
    public void registerUpdater(NamingServiceUpdater nsu) {
        _updaters.add(nsu);
    }

    /**
     *  @since 0.8.7
     */
    public void unregisterUpdater(NamingServiceUpdater nsu) {
        _updaters.remove(nsu);
    }

    /**
     *  Same as lookup(hostname) but with in and out options
     *  Note that whether this (and lookup(hostname)) resolve Base 32 addresses
     *  in the form {52 chars}.b32.i2p is NamingService-specific.
     *
     *  @param lookupOptions input parameter, NamingService-specific, can be null
     *  @param storedOptions output parameter, NamingService-specific, any stored properties will be added if non-null
     *  @return dest or null
     *  @since 0.8.7
     */
    public abstract Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions);

    /**
     *  Same as reverseLookup(dest) but with options
     *  This implementation returns null.
     *  Subclasses implementing reverse lookups should override.
     *
     *  @param d non-null
     *  @param options NamingService-specific, can be null
     *  @return host name or null
     *  @since 0.8.7
     */
    public String reverseLookup(Destination d, Properties options) {
        return null;
    }

    /**
     *  Lookup a Base 32 address. This may require the router to fetch the LeaseSet,
     *  which may take quite a while.
     *  This implementation returns null.
     *  See also lookup(Hash, int).
     *
     *  @param hostname must be {52 chars}.b32.i2p
     *  @param timeout in seconds; &lt;= 0 means use router default
     *  @return dest or null
     *  @since 0.8.7
     */
    public Destination lookupBase32(String hostname, int timeout) {
        return null;
    }

    /**
     *  Same as lookupBase32() but with the SHA256 Hash precalculated
     *  This implementation returns null.
     *
     *  @param timeout in seconds; &lt;= 0 means use router default
     *  @return dest or null
     *  @since 0.8.7
     */
    public Destination lookup(Hash hash, int timeout) {
        return null;
    }

    /**
     *  Parent will call when added.
     *  If this is the root naming service, the core will start it.
     *  Should not be called by others.
     *  @since 0.8.7
     */
    public void start() {}

    /**
     *  Parent will call when removed.
     *  If this is the root naming service, the core will stop it.
     *  Should not be called by others.
     *  @since 0.8.7
     */
    public void shutdown() {}

    //// End New API

    //// Begin new API for multiple Destinations

    /**
     *  For NamingServices that support multiple Destinations for a single host name,
     *  return all of them.
     *
     *  It is recommended that the returned list is in order of priority, highest-first,
     *  but this is NamingService-specific.
     *
     *  Not recommended for resolving Base 32 addresses;
     *  whether this does resolve Base 32 addresses
     *  in the form {52 chars}.b32.i2p is NamingService-specific.
     *
     *  @return non-empty List of Destinations, or null if nothing found
     *  @since 0.9.26
     */
    public List<Destination> lookupAll(String hostname) {
        return lookupAll(hostname, null, null);
    }

    /**
     *  For NamingServices that support multiple Destinations and Properties for a single host name,
     *  return all of them.
     *
     *  It is recommended that the returned list is in order of priority, highest-first,
     *  but this is NamingService-specific.
     *
     *  If storedOptions is non-null, it must be a List that supports null entries.
     *  If the returned value (the List of Destinations) is non-null,
     *  the same number of Properties objects will be added to storedOptions.
     *  If no properties were found for a given Destination, the corresponding
     *  entry in the storedOptions list will be null.
     *
     *  Not recommended for resolving Base 32 addresses;
     *  whether this does resolve Base 32 addresses
     *  in the form {52 chars}.b32.i2p is NamingService-specific.
     *
     *  This implementation simply calls lookup().
     *  Subclasses implementing multiple destinations per hostname should override.
     *
     *  @param lookupOptions input parameter, NamingService-specific, may be null
     *  @param storedOptions output parameter, NamingService-specific, any stored properties will be added if non-null
     *  @return non-empty List of Destinations, or null if nothing found
     *  @since 0.9.26
     */
    public List<Destination> lookupAll(String hostname, Properties lookupOptions, List<Properties> storedOptions) {
        Properties props = storedOptions != null ? new Properties() : null;
        Destination d = lookup(hostname, lookupOptions, props);
        List<Destination> rv;
        if (d != null) {
            rv = Collections.singletonList(d);
            if (storedOptions != null)
                storedOptions.add(props.isEmpty() ? null : props);
        } else {
            rv = null;
        }
        return rv;
    }

    /**
     *  Add a Destination to an existing hostname's entry in the addressbook.
     *
     *  @return success
     *  @since 0.9.26
     */
    public boolean addDestination(String hostname, Destination d) {
        return addDestination(hostname, d, null);
    }

    /**
     *  Add a Destination to an existing hostname's entry in the addressbook.
     *  This implementation simply calls putIfAbsent().
     *  Subclasses implementing multiple destinations per hostname should override.
     *
     *  @param options NamingService-specific, may be null
     *  @return success
     *  @since 0.9.26
     */
    public boolean addDestination(String hostname, Destination d, Properties options) {
        return putIfAbsent(hostname, d, options);
    }

    /**
     *  Remove a hostname's entry only if it contains the Destination d.
     *  If the NamingService supports multiple Destinations per hostname,
     *  and this is the only Destination, removes the entire entry.
     *  If aditional Destinations remain, it only removes the
     *  specified Destination from the entry.
     *
     *  @return true if entry containing d was successfully removed.
     *  @since 0.9.26
     */
    public boolean remove(String hostname, Destination d) {
        return remove(hostname, d, null);
    }

    /**
     *  Remove a hostname's entry only if it contains the Destination d.
     *  If the NamingService supports multiple Destinations per hostname,
     *  and this is the only Destination, removes the entire entry.
     *  If aditional Destinations remain, it only removes the
     *  specified Destination from the entry.
     *
     *  This implementation simply calls lookup() and remove().
     *  Subclasses implementing multiple destinations per hostname,
     *  or with more efficient implementations, should override.
     *  Fails if entry previously exists.
     *
     *  @param options NamingService-specific, may be null
     *  @return true if entry containing d was successfully removed.
     *  @since 0.9.26
     */
    public boolean remove(String hostname, Destination d, Properties options) {
        Destination old = lookup(hostname, options, null);
        if (!d.equals(old))
            return false;
        return remove(hostname, options);
    }

    /**
     * Reverse lookup a hash.
     * This implementation returns the result from reverseLookup, or null.
     * Subclasses implementing reverse lookups should override.
     *
     * @param h non-null
     * @return a non-empty list of host names for this hash, or <code>null</code>
     * if none is known. It is safe for subclasses to always return
     * <code>null</code> if no reverse lookup is possible.
     * @since 0.9.26
     */
    public List<String> reverseLookupAll(Hash h) {
        String s = reverseLookup(h);
        return (s != null) ? Collections.singletonList(s) : null;
    }

    /**
     * Reverse lookup a destination
     * This implementation returns reverseLookupAll(dest, null).
     *
     * @param dest non-null
     * @return a non-empty list of host names for this Destination, or <code>null</code>
     * if none is known. It is safe for subclasses to always return
     * <code>null</code> if no reverse lookup is possible.
     * @since 0.9.26
     */
    public List<String> reverseLookupAll(Destination dest) {
        return reverseLookupAll(dest, null);
    }

    /**
     *  Same as reverseLookupAll(dest) but with options
     *  This implementation returns the result from reverseLookup, or null.
     *  Subclasses implementing reverse lookups should override.
     *
     *  @param d non-null
     *  @param options NamingService-specific, can be null
     *  @return a non-empty list of host names for this Destination, or <code>null</code>
     *  @since 0.9.26
     */
    public List<String> reverseLookupAll(Destination d, Properties options) {
        String s = reverseLookup(d, options);
        return (s != null) ? Collections.singletonList(s) : null;
    }

    //// End new API for multiple Destinations

    /**
     * WARNING - for use by I2PAppContext only - others must use
     * I2PAppContext.namingService()
     *
     * Get a naming service instance. This method ensures that there
     * will be only one naming service instance (singleton) as well as
     * choose the implementation from the "i2p.naming.impl" system
     * property.
     *
     * FIXME Actually, it doesn't ensure that. Only call this once!!!
     */
    public static final synchronized NamingService createInstance(I2PAppContext context) {
        NamingService instance = null;
        String dflt = context.isRouterContext() ? DEFAULT_IMPL : BACKUP_IMPL;
        String impl = context.getProperty(PROP_IMPL, DEFAULT_IMPL);
        if (impl.equals(OLD_DEFAULT_IMPL))
            impl = dflt;
        try {
            Class<?> cls = Class.forName(impl);
            Constructor<?> con = cls.getConstructor(I2PAppContext.class);
            instance = (NamingService)con.newInstance(context);
        } catch (Exception ex) {
            Log log = context.logManager().getLog(NamingService.class);
            // Blockfile may throw RuntimeException but HostsTxt won't
            if (!impl.equals(BACKUP_IMPL)) {
                log.error("Cannot load naming service " + impl + ", using HostsTxtNamingService", ex);
                instance = new HostsTxtNamingService(context);
            } else {
                log.error("Cannot load naming service " + impl + ", only .b32.i2p lookups will succeed", ex);
                instance = new DummyNamingService(context);
            }
        }
        return instance;
    }

}
