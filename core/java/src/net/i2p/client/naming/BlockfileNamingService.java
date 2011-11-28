/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;

import net.metanotion.io.RAIFile;
import net.metanotion.io.Serializer;
import net.metanotion.io.block.BlockFile;
import net.metanotion.io.data.IntBytes;
import net.metanotion.io.data.UTF8StringBytes;
import net.metanotion.util.skiplist.SkipIterator;
import net.metanotion.util.skiplist.SkipList;


/**
 * A naming service using the net.metanotion BlockFile database.
 *
 * This database contains the following skiplists:
 * <pre>
 *
 * "%%__INFO__%%" is the master database skiplist, containing one entry:
 *     "info": a Properties, serialized with DataHelper functions:
 *             "version": "2"
 *             "created": Java long time (ms)
 *             "upgraded": Java long time (ms) (as of database version 2)
 *             "lists":   Comma-separated list of host databases, to be
 *                        searched in-order for lookups
 *
 * "%%__REVERSE__%%" is the reverse lookup skiplist
 *     (as of database version 2):
 *     The skiplist keys are Integers, the first 4 bytes of the hash of the dest.
 *     The skiplist values are Properties.
 *         There may be multiple entries in the properties, each one is a reverse mapping,
 *            as there may be more than one hostname for a given destination,
 *            or there could be collisions with the same first 4 bytes of the hash.
 *         Each property key is a hostname.
 *         Each property value is the empty string.
 *
 * For each host database, there is a skiplist containing
 * the hosts for that database.
 * The keys/values in these skiplists are as follows:
 *      key: a UTF-8 String
 *      value: a DestEntry, which is a Properties (serialized with DataHelper)
 *             followed by a Destination (serialized as usual).
 *
 *
 * The DestEntry Properties typically contains:
 *             "a":   The time added (Java long time in ms)
 *             "s":   The original source of the entry (typically a file name or subscription URL)
 *             others TBD
 *
 * </pre>
 *
 * All host names are converted to lower case.
 *
 * @since 0.8.7
 */
public class BlockfileNamingService extends DummyNamingService {

    private final BlockFile _bf;
    private final RAIFile _raf;
    private final List<String> _lists;
    private final List<InvalidEntry> _invalid;
    private volatile boolean _isClosed;
    private final boolean _readOnly;
    private boolean _needsUpgrade;

    private static final Serializer _infoSerializer = new PropertiesSerializer();
    private static final Serializer _stringSerializer = new UTF8StringBytes();
    private static final Serializer _destSerializer = new DestEntrySerializer();
    private static final Serializer _hashIndexSerializer = new IntBytes();

    private static final String HOSTS_DB = "hostsdb.blockfile";
    private static final String FALLBACK_LIST = "hosts.txt";
    private static final String PROP_FORCE = "i2p.naming.blockfile.writeInAppContext";

    private static final String INFO_SKIPLIST = "%%__INFO__%%";
    private static final String REVERSE_SKIPLIST = "%%__REVERSE__%%";
    private static final String PROP_INFO = "info";
    private static final String PROP_VERSION = "version";
    private static final String PROP_LISTS = "lists";
    private static final String PROP_CREATED = "created";
    private static final String PROP_UPGRADED = "upgraded";
    private static final String VERSION = "2";
    private static final String OLD_VERSION = "1";

    private static final String PROP_ADDED = "a";
    private static final String PROP_SOURCE = "s";
    
    /**
     *  Opens the database at hostsdb.blockfile or creates a new
     *  one and imports entries from hosts.txt, userhosts.txt, and privatehosts.txt.
     *
     *  If not in router context, the database will be opened read-only
     *  unless the property i2p.naming.blockfile.writeInAppContext is true.
     *  Not designed for multiple instantiations or simultaneous use by multple JVMs.
     *
     *  @throws RuntimeException on fatal error
     */
    public BlockfileNamingService(I2PAppContext context) {
        super(context);
        _lists = new ArrayList();
        _invalid = new ArrayList();
        BlockFile bf = null;
        RAIFile raf = null;
        boolean readOnly = false;
        File f = new File(_context.getRouterDir(), HOSTS_DB);
        if (f.exists()) {
            try {
                // closing a BlockFile does not close the underlying file,
                // so we must create and retain a RAF so we may close it later

                // *** Open readonly if not in router context (unless forced)
                readOnly = (!f.canWrite()) ||
                           ((!context.isRouterContext()) && (!context.getBooleanProperty(PROP_FORCE)));
                raf = new RAIFile(f, true, !readOnly);
                bf = initExisting(raf);
                if (readOnly && context.isRouterContext())
                    _log.logAlways(Log.WARN, "Read-only hosts database in router context");
                if (bf.wasMounted()) {
                    if (context.isRouterContext())
                        _log.logAlways(Log.WARN, "The hosts database was not closed cleanly or is still open by another process");
                    else
                        _log.logAlways(Log.WARN, "The hosts database is possibly in use by another process, perhaps the router? " +
                                       "The database is not designed for simultaneous access by multiple processes.\n" +
                                       "If you are using clients outside the router JVM, consider using the hosts.txt " +
                                       "naming service with " +
                                       "i2p.naming.impl=net.i2p.client.naming.HostsTxtNamingService");
                }
            } catch (IOException ioe) {
                if (raf != null) {
                    try { raf.close(); } catch (IOException e) {}
                }
                File corrupt = new File(_context.getRouterDir(), HOSTS_DB + ".corrupt");
                _log.log(Log.CRIT, "Corrupt or unreadable database " + f + ", moving to " + corrupt +
                                   " and creating new database", ioe);
                boolean success = f.renameTo(corrupt);
                if (!success)
                    _log.log(Log.CRIT, "Failed to move corrupt database " + f + " to " + corrupt);
            }
        }
        if (bf == null) {
            try {
                // closing a BlockFile does not close the underlying file,
                // so we must create and retain a RAF so we may close it later
                raf = new RAIFile(f, true, true);
                SecureFileOutputStream.setPerms(f);
                bf = init(raf);
            } catch (IOException ioe) {
                if (raf != null) {
                    try { raf.close(); } catch (IOException e) {}
                }
                _log.log(Log.CRIT, "Failed to initialize database", ioe);
                throw new RuntimeException(ioe);
            }
            readOnly = false;
        }
        _bf = bf;
        _raf = raf;
        _readOnly = readOnly;
        if (_needsUpgrade)
            upgrade();
        _context.addShutdownTask(new Shutdown());
    }

    /**
     *  Create a new database and initialize it from the local files
     *  privatehosts.txt, userhosts.txt, and hosts.txt,
     *  creating a skiplist in the database for each.
     */
    private BlockFile init(RAIFile f) throws IOException {
        long start = _context.clock().now();
        try {
            BlockFile rv = new BlockFile(f, true);
            SkipList hdr = rv.makeIndex(INFO_SKIPLIST, _stringSerializer, _infoSerializer);
            Properties info = new Properties();
            info.setProperty(PROP_VERSION, VERSION);
            info.setProperty(PROP_CREATED, Long.toString(_context.clock().now()));
            String list = _context.getProperty(HostsTxtNamingService.PROP_HOSTS_FILE,
                                               HostsTxtNamingService.DEFAULT_HOSTS_FILE);
            info.setProperty(PROP_LISTS, list);
            hdr.put(PROP_INFO, info);
            rv.makeIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);

            int total = 0;
            for (String hostsfile : getFilenames(list)) {
                File file = new File(_context.getRouterDir(), hostsfile);
                if ((!file.exists()) || !(file.canRead()))
                    continue;
                int count = 0;
                BufferedReader in = null;
                String sourceMsg = "Imported from " + hostsfile + " file";
                try {
                    in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"), 16*1024);
                    String line = null;
                    while ( (line = in.readLine()) != null) {
                        if (line.startsWith("#"))
                            continue;
                        int split = line.indexOf('=');
                        if (split <= 0)
                            continue;
                        String key = line.substring(0, split).toLowerCase(Locale.US);
                        if (line.indexOf('#') > 0)  { // trim off any end of line comment
                            line = line.substring(0, line.indexOf('#')).trim();
                            if (line.length() < split + 1)
                                continue;
                        }
                        String b64 = line.substring(split+1);   //.trim() ??????????????
                        Destination d = lookupBase64(b64);
                        if (d != null) {
                            addEntry(rv, hostsfile, key, d, sourceMsg);
                            addReverseEntry(rv, key, d, _log);
                            count++;
                        } else {
                            _log.logAlways(Log.WARN, "Unable to import entry for " + key +
                                                     " from file " + file + " - bad Base 64: " + b64);
                        }
                    }
                } catch (IOException ioe) {
                    _log.error("Failed to read hosts from " + file, ioe);
                } finally {
                    if (in != null) try { in.close(); } catch (IOException ioe) {}
                }
                total += count;
                _log.logAlways(Log.INFO, "Migrating " + count + " hosts from " + file + " to new hosts database");
                _lists.add(hostsfile);
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("DB init took " + DataHelper.formatDuration(_context.clock().now() - start));
            if (total <= 0)
                _log.logAlways(Log.WARN, "No hosts.txt files found, Initialized hosts database with zero entries");
            return rv;
        } catch (RuntimeException e) {
            _log.error("Failed to initialize database", e);
            throw new IOException(e.toString());
        }
    }

    /**
     *  Read the info block of an existing database.
     */
    private BlockFile initExisting(RAIFile raf) throws IOException {
        long start = _context.clock().now();
        try {
            BlockFile bf = new BlockFile(raf, false);
            // TODO all in one skiplist or separate?
            SkipList hdr = bf.getIndex(INFO_SKIPLIST, _stringSerializer, _infoSerializer);
            if (hdr == null)
                throw new IOException("No db header");
            Properties info = (Properties) hdr.get(PROP_INFO);
            if (info == null)
                throw new IOException("No header info");

            String list = info.getProperty(PROP_LISTS);
            if (list == null)
                throw new IOException("No lists");
            long createdOn = 0;
            String created = info.getProperty(PROP_CREATED);
            if (created != null) {
                try {
                    createdOn = Long.parseLong(created);
                } catch (NumberFormatException nfe) {}
            }

            String version = info.getProperty(PROP_VERSION);
            _needsUpgrade = needsUpgrade(bf, version);
            if (_needsUpgrade) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Upgrading from database version " + version + " to " + VERSION +
                              " created " + (new Date(createdOn)).toString() +
                              " containing lists: " + list);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Found database version " + version +
                              " created " + (new Date(createdOn)).toString() +
                              " containing lists: " + list);
            }

            List<String> skiplists = getFilenames(list);
            if (skiplists.isEmpty())
                skiplists.add(FALLBACK_LIST);
            _lists.addAll(skiplists);

            if (_log.shouldLog(Log.INFO))
                _log.info("DB init took " + DataHelper.formatDuration(_context.clock().now() - start));
            return bf;
        } catch (RuntimeException e) {
            _log.error("Failed to initialize database", e);
            throw new IOException(e.toString());
        }
    }

    /**
     *  @return true if needs an upgrade
     *  @throws IOE on bad version
     *  @since 0.8.9
     */
    private boolean needsUpgrade(BlockFile bf, String version) throws IOException {
        if (VERSION.equals(version))
            return false;
        if (!OLD_VERSION.equals(version))
            throw new IOException("Bad db version: " + version);
        if (!bf.file.canWrite()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not upgrading read-only database version " + version);
            return false;
        }
        return true;
    }

    /**
     *  Blockfile must be writable of course.
     *  @return true if upgraded successfully
     *  @since 0.8.9
     */
    private boolean upgrade() {
        try {
            // shouldn't ever be there...
            SkipList rev = _bf.getIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
            if (rev == null)
                rev = _bf.makeIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
            Map<String, Destination> entries = getEntries();
            long start = System.currentTimeMillis();
            int i = 0;
            for (Map.Entry<String, Destination> entry : entries.entrySet()) {
                 addReverseEntry(entry.getKey(), entry.getValue());
                 i++;
            }
            if (_log.shouldLog(Log.WARN))
                _log.warn("Created reverse index with " + i + " entries");
            SkipList hdr = _bf.getIndex(INFO_SKIPLIST, _stringSerializer, _infoSerializer);
            if (hdr == null)
                throw new IOException("No db header");
            Properties info = (Properties) hdr.get(PROP_INFO);
            if (info == null)
                throw new IOException("No header info");
            info.setProperty(PROP_VERSION, VERSION);
            info.setProperty(PROP_UPGRADED, Long.toString(_context.clock().now()));
            hdr.put(PROP_INFO, info);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Upgraded to version " + VERSION + " in " +
                          DataHelper.formatDuration(System.currentTimeMillis() - start));
            return true;
        } catch (IOException ioe) {
            _log.error("Error upgrading DB", ioe);
        } catch (RuntimeException e) {
            _log.error("Error upgrading DB", e);
        }
        return false;
    }

    /**
     *  Caller must synchronize
     *  @return entry or null, or throws ioe
     */
    private DestEntry getEntry(String listname, String key) throws IOException {
        try {
            SkipList sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
            if (sl == null)
                return null;
            DestEntry rv = (DestEntry) sl.get(key);
            return rv;
        } catch (IOException ioe) {
            _log.error("DB Lookup error", ioe);
            // delete index??
            throw ioe;
        } catch (RuntimeException e) {
            _log.error("DB Lookup error", e);
            throw new IOException(e.toString());
        }
    }

    /**
     *  Caller must synchronize
     *  @param source may be null
     */
    private void addEntry(BlockFile bf, String listname, String key, Destination dest, String source) throws IOException {
        try {
            // catch IOE and delete index??
            SkipList sl = bf.getIndex(listname, _stringSerializer, _destSerializer);
            if (sl == null) {
                //_log.info("Making new skiplist " + listname);
                sl = bf.makeIndex(listname, _stringSerializer, _destSerializer);
            }
            Properties props = new Properties();
            props.setProperty(PROP_ADDED, Long.toString(_context.clock().now()));
            if (source != null)
                props.setProperty(PROP_SOURCE, source);
            addEntry(sl, key, dest, props);
        } catch (IOException ioe) {
            _log.error("DB add error", ioe);
            // delete index??
            throw ioe;
        } catch (RuntimeException e) {
            _log.error("DB add error", e);
            throw new IOException(e.toString());
        }
    }

    /**
     *  Caller must synchronize
     *  @param source may be null
     *  @throws RuntimeException
     */
/****
    private void addEntry(SkipList sl, String key, Destination dest, String source) {
        Properties props = new Properties();
        props.setProperty(PROP_ADDED, Long.toString(_context.clock().now()));
        if (source != null)
            props.setProperty(PROP_SOURCE, source);
        addEntry(sl, key, dest, props);
    }
****/

    /**
     *  Caller must synchronize
     *  @param props may be null
     *  @throws RuntimeException
     */
    private static void addEntry(SkipList sl, String key, Destination dest, Properties props) {
        DestEntry de = new DestEntry();
        de.dest = dest;
        de.props = props;
        sl.put(key, de);
    }

    private static List<String> getFilenames(String list) {
        StringTokenizer tok = new StringTokenizer(list, ",");
        List<String> rv = new ArrayList(tok.countTokens());
        while (tok.hasMoreTokens())
            rv.add(tok.nextToken());
        return rv;
    }
    
    /**
     *  Caller must synchronize
     *  @return removed object or null
     *  @throws RuntimeException
     */
    private static Object removeEntry(SkipList sl, String key) {
        return sl.remove(key);
    }

    ///// Reverse index methods

    /**
     *  Caller must synchronize.
     *  @return null without exception on error (logs only)
     *  @since 0.8.9
     */
/****
    private String getReverseEntry(Destination dest) {
        return getReverseEntry(dest.calculateHash());
    }
****/

    /**
     *  Caller must synchronize.
     *  Returns null without exception on error (logs only).
     *  Returns without logging if no reverse skiplist (version 1).
     *
     *  @return the first one found if more than one
     *  @since 0.8.9
     */
    private String getReverseEntry(Hash hash) {
        try {
            SkipList rev = _bf.getIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
            if (rev == null)
                return null;
            Integer idx = getReverseKey(hash);
            //_log.info("Get reverse " + idx + ' ' + hash);
            Properties props = (Properties) rev.get(idx);
            if (props == null)
                return null;
            for (Object okey : props.keySet()) {
                String key = (String) okey;
                // now do the forward lookup to verify (using the cache)
                Destination d = lookup(key);
                if (d != null && d.calculateHash().equals(hash))
                    return key;
            }
        } catch (IOException ioe) {
            _log.error("DB get reverse error", ioe);
        } catch (RuntimeException e) {
            _log.error("DB get reverse error", e);
        }
        return null;
    }

    /**
     *  Caller must synchronize.
     *  Fails without exception on error (logs only)
     *  @since 0.8.9
     */
    private void addReverseEntry(String key, Destination dest) {
        addReverseEntry(_bf, key, dest, _log);
    }

    /**
     *  Caller must synchronize.
     *  Fails without exception on error (logs only).
     *  Returns without logging if no reverse skiplist (version 1).
     *
     *  We store one or more hostnames for a given hash.
     *  The skiplist key is a signed Integer, the first 4 bytes of the dest hash.
     *  For convenience (since we have a serializer already) we use
     *  a Properties as the value, with a null string as the value for each hostname property.
     *  We could in the future use the property value for something.
     *  @since 0.8.9
     */
    private static void addReverseEntry(BlockFile bf, String key, Destination dest, Log log) {
        //log.info("Add reverse " + key);
        try {
            SkipList rev = bf.getIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
            if (rev == null)
                return;
            Integer idx = getReverseKey(dest);
            Properties props = (Properties) rev.get(idx);
            if (props != null) {
                if (props.getProperty(key) != null)
                    return;
            } else {
                props = new Properties();
            }
            props.put(key, "");
            rev.put(idx, props);
        } catch (IOException ioe) {
            log.error("DB add reverse error", ioe);
        } catch (RuntimeException e) {
            log.error("DB add reverse error", e);
        }
    }

    /**
     *  Caller must synchronize.
     *  Fails without exception on error (logs only)
     *  @since 0.8.9
     */
    private void removeReverseEntry(String key, Destination dest) {
        //_log.info("Remove reverse " + key);
        try {
            SkipList rev = _bf.getIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
            if (rev == null)
                return;
            Integer idx = getReverseKey(dest);
            Properties props = (Properties) rev.get(idx);
            if (props == null || props.remove(key) == null)
                return;
            if (props.isEmpty())
                rev.remove(idx);
            else
                rev.put(idx, props);
        } catch (IOException ioe) {
            _log.error("DB remove reverse error", ioe);
        } catch (RuntimeException e) {
            _log.error("DB remove reverse error", e);
        }
    }

    /**
     *  @since 0.8.9
     */
    private static Integer getReverseKey(Destination dest) {
        return getReverseKey(dest.calculateHash());        
    }

    /**
     *  @since 0.8.9
     */
    private static Integer getReverseKey(Hash hash) {
        byte[] hashBytes = hash.getData();        
        int i = (int) DataHelper.fromLong(hashBytes, 0, 4);
        return Integer.valueOf(i);
    }

    ////////// Start NamingService API

    /*
     * @param options If non-null and contains the key "list", lookup in
     *                that list only, otherwise all lists
     */
    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        String listname = null;
        if (lookupOptions != null)
            listname = lookupOptions.getProperty("list");

        Destination d = null;
        // only use cache if we aren't retreiving options or specifying the list
        if (listname == null && storedOptions == null) {
            d = super.lookup(hostname, null, null);
            if (d != null)
                return d;
            // Base32 failed?
            if (hostname.length() == BASE32_HASH_LENGTH + 8 && hostname.toLowerCase(Locale.US).endsWith(".b32.i2p"))
                return null;
        }

        String key = hostname.toLowerCase(Locale.US);
        synchronized(_bf) {
            if (_isClosed)
                return null;
            for (String list : _lists) { 
                if (listname != null && !list.equals(listname))
                    continue;
                try {
                    DestEntry de = getEntry(list, key);
                    if (de != null) {
                        if (!validate(key, de, listname))
                            continue;
                        d = de.dest;
                        if (storedOptions != null && de.props != null)
                            storedOptions.putAll(de.props);
                        break;
                    }
                } catch (IOException ioe) {
                    break;
                }
            }
            deleteInvalid();
        }
        if (d != null)
            putCache(hostname, d);
        return d;
    }

    /**
     * @param options If non-null and contains the key "list", add to that list
     *                (default "hosts.txt")
     *                Use the key "s" for the source
     */
    @Override
    public boolean put(String hostname, Destination d, Properties options) {
        return put(hostname, d, options, false);
    }

    /**
     * @param options If non-null and contains the key "list", add to that list
     *                (default "hosts.txt")
     *                Use the key "s" for the source.
     *                Key "a" will be added with the current time, unless
     *                "a" is present in options.
     */
    @Override
    public boolean putIfAbsent(String hostname, Destination d, Properties options) {
        return put(hostname, d, options, true);
    }

    private boolean put(String hostname, Destination d, Properties options, boolean checkExisting) {
        if (_readOnly) {
            _log.error("Add entry failed, read-only hosts database");
            return false;
        }
        String key = hostname.toLowerCase(Locale.US);
        String listname = FALLBACK_LIST;
        Properties props = new Properties();
        props.setProperty(PROP_ADDED, Long.toString(_context.clock().now()));
        if (options != null) {
            props.putAll(options);
            String list = options.getProperty("list");
            if (list != null) {
                listname = list;
                props.remove("list");
            }
        }
        synchronized(_bf) {
            if (_isClosed)
                return false;
            try {
                SkipList sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null)
                    sl = _bf.makeIndex(listname, _stringSerializer, _destSerializer);
                boolean changed =  (checkExisting || !_listeners.isEmpty()) && sl.get(key) != null;
                if (changed && checkExisting)
                        return false;
                addEntry(sl, key, d, props);
                if (changed) {
                    removeCache(hostname);
                    addReverseEntry(key, d);
                }
                for (NamingServiceListener nsl : _listeners) { 
                    if (changed)
                        nsl.entryChanged(this, hostname, d, options);
                    else
                        nsl.entryAdded(this, hostname, d, options);
                }
                return true;
            } catch (IOException ioe) {
                _log.error("DB add error", ioe);
                return false;
            } catch (RuntimeException re) {
                _log.error("DB add error", re);
                return false;
            }
        }
    }

    /**
     * @param options If non-null and contains the key "list", remove
     *                from that list (default "hosts.txt", NOT all lists)
     */
    @Override
    public boolean remove(String hostname, Properties options) {
        if (_readOnly) {
            _log.error("Remove entry failed, read-only hosts database");
            return false;
        }
        String key = hostname.toLowerCase(Locale.US);
        String listname = FALLBACK_LIST;
        if (options != null) {
            String list = options.getProperty("list");
            if (list != null) {
                listname = list;
            }
        }
        synchronized(_bf) {
            if (_isClosed)
                return false;
            try {
                SkipList sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null)
                    return false;
                Object removed = removeEntry(sl, key);
                boolean rv = removed != null;
                if (rv) {
                    removeCache(hostname);
                    try {
                        removeReverseEntry(key, ((DestEntry)removed).dest);
                    } catch (ClassCastException cce) {
                        _log.error("DB reverse remove error", cce);
                    }
                    for (NamingServiceListener nsl : _listeners) { 
                        nsl.entryRemoved(this, key);
                    }
                }
                return rv;
            } catch (IOException ioe) {
                _log.error("DB remove error", ioe);
                return false;
            } catch (RuntimeException re) {
                _log.error("DB remove error", re);
                return false;
            }
        }
    }

    /**
     * @param options If non-null and contains the key "list", get
     *                from that list (default "hosts.txt", NOT all lists)
     *                Key "skip": skip that many entries
     *                Key "limit": max number to return
     *                Key "search": return only those matching substring
     *                Key "startsWith": return only those starting with
     *                                  ("[0-9]" allowed)
     *                Key "beginWith": start here in the iteration
     *                Don't use both startsWith and beginWith.
     *                Search, startsWith, and beginWith values must be lower case.
     */
    @Override
    public Map<String, Destination> getEntries(Properties options) {
        String listname = FALLBACK_LIST;
        String search = null;
        String startsWith = null;
        String beginWith = null;
        int limit = Integer.MAX_VALUE;
        int skip = 0;
        if (options != null) {
            String ln = options.getProperty("list");
            if (ln != null)
                listname = ln;
            search = options.getProperty("search");
            startsWith = options.getProperty("startsWith");
            beginWith = options.getProperty("beginWith");
            if (beginWith == null && startsWith != null) {
                if (startsWith.equals("[0-9]"))
                    beginWith = "0";
                else
                    beginWith = startsWith;
            }
            String lim = options.getProperty("limit");
            try {
                limit = Integer.parseInt(lim);
            } catch (NumberFormatException nfe) {}
            String sk = options.getProperty("skip");
            try {
                skip = Integer.parseInt(sk);
            } catch (NumberFormatException nfe) {}
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Searching " + listname + " beginning with " + beginWith +
                       " starting with " + startsWith + " search string " + search +
                       " limit=" + limit + " skip=" + skip);
        synchronized(_bf) {
            if (_isClosed)
                return Collections.EMPTY_MAP;
            try {
                SkipList sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No skiplist found for lookup in " + listname);
                    return Collections.EMPTY_MAP;
                }
                SkipIterator iter;
                if (beginWith != null)
                    iter = sl.find(beginWith);
                else
                    iter = sl.iterator();
                Map<String, Destination> rv = new HashMap();
                for (int i = 0; i < skip && iter.hasNext(); i++) {
                    // don't bother validating here
                    iter.next();
                }
                for (int i = 0; i < limit && iter.hasNext(); ) {
                    String key = (String) iter.nextKey();
                    if (startsWith != null) {
                        if (startsWith.equals("[0-9]")) {
                            if (key.charAt(0) > '9')
                                break;
                        } else if (!key.startsWith(startsWith)) {
                            break;
                        }
                    }
                    DestEntry de = (DestEntry) iter.next();
                    if (!validate(key, de, listname))
                        continue;
                    if (search != null && key.indexOf(search) < 0)
                        continue;
                    rv.put(key, de.dest);
                    i++;
                }
                return rv;
            } catch (IOException ioe) {
                _log.error("DB lookup error", ioe);
                return Collections.EMPTY_MAP;
            } catch (RuntimeException re) {
                _log.error("DB lookup error", re);
                return Collections.EMPTY_MAP;
            } finally {
                deleteInvalid();
            }
        }
    }

    /**
     * @param options ignored
     * @since 0.8.9
     */
    @Override
    public String reverseLookup(Destination d, Properties options) {
        return reverseLookup(d.calculateHash());
    }

    /**
     * @since 0.8.9
     */
    @Override
    public String reverseLookup(Hash h) {
        synchronized(_bf) {
            if (_isClosed)
                return null;
            return getReverseEntry(h);
        }
    }

    /**
     * @param options If non-null and contains the key "list", return the
     *                size of that list (default "hosts.txt", NOT all lists)
     */
    @Override
    public int size(Properties options) {
        String listname = FALLBACK_LIST;
        if (options != null) {
            String list = options.getProperty("list");
            if (list != null) {
                listname = list;
            }
        }
        synchronized(_bf) {
            if (_isClosed)
                return 0;
            try {
                SkipList sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null)
                    return 0;
                return sl.size();
            } catch (IOException ioe) {
                _log.error("DB size error", ioe);
                return 0;
            } catch (RuntimeException re) {
                _log.error("DB size error", re);
                return 0;
            }
        }
    }

    public void shutdown() {
        close();
    }

    ////////// End NamingService API

    /**
     *  Continuously validate anything we read in.
     *  Queue anything invalid to be removed at the end of the operation.
     *  Caller must sync!
     *  @return valid
     */
    private boolean validate(String key, DestEntry de, String listname) {
        if (key == null)
            return false;
        // de.props may be null
        // publickey check is a quick proxy to detect dest deserialization failure
        boolean rv = key.length() > 0 &&
                     de != null &&
                     de.dest != null &&
                     de.dest.getPublicKey() != null;
        if ((!rv) && (!_readOnly))
            _invalid.add(new InvalidEntry(key, listname));
        return rv;
    }

    /**
     *  Remove and log all invalid entries queued by validate()
     *  while scanning in lookup() or getEntries().
     *  We delete in the order detected, as an error may be corrupting later entries in the skiplist.
     *  Caller must sync!
     */
    private void deleteInvalid() {
        if (_invalid.isEmpty())
            return;
        _log.error("Removing " + _invalid.size() + " corrupt entries from database");
        for (InvalidEntry ie : _invalid) {
            String key = ie.key;
            String list = ie.list;
            try {
                SkipList sl = _bf.getIndex(list, _stringSerializer, _destSerializer);
                if (sl == null) {
                    _log.error("No list found to remove corrupt \"" + key + "\" from database " + list);
                    continue;
                }
                // this will often return null since it was corrupt
                boolean success = removeEntry(sl, key) != null;
                if (success)
                    _log.error("Removed corrupt \"" + key + "\" from database " + list);
                else
                    _log.error("May have Failed to remove corrupt \"" + key + "\" from database " + list);
            } catch (RuntimeException re) {
                _log.error("Error while removing corrupt \"" + key + "\" from database " + list, re);
            } catch (IOException ioe) {
                _log.error("Error while removing corrput \"" + key + "\" from database " + list, ioe);
            }
        }
        _invalid.clear();
    }

    private void dumpDB() {
        synchronized(_bf) {
            if (_isClosed)
                _log.error("Database is closed");
            for (String list : _lists) { 
                try {
                    SkipList sl = _bf.getIndex(list, _stringSerializer, _destSerializer);
                    if (sl == null) {
                        _log.error("No list found for " + list);
                        continue;
                    }
                    int i = 0;
                    for (SkipIterator iter = sl.iterator(); iter.hasNext(); ) {
                         String key = (String) iter.nextKey();
                         DestEntry de = (DestEntry) iter.next();
                         if (!validate(key, de, list))
                             continue;
                         _log.error("DB " + list + " key " + key + " val " + de);
                         i++;
                    }
                    _log.error(i + " entries found for " + list);
                } catch (IOException ioe) {
                    _log.error("Fail", ioe);
                    break;
                }
            }
            deleteInvalid();
        }
    }

    private void close() {
        synchronized(_bf) {
            try {
                _bf.close();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error closing", ioe);
            } catch (RuntimeException e) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error closing", e);
            }
            try {
                _raf.close();
            } catch (IOException ioe) {
            }
            _isClosed = true;
        }
        clearCache();
    }

    /** for logging errors in the static serializers below */
    private static void logError(String msg, Throwable t) {
        I2PAppContext.getGlobalContext().logManager().getLog(BlockfileNamingService.class).error(msg, t);
    }

    private class Shutdown implements Runnable {
        public void run() {
            close();
        }
    }

    /**
     *  Used for the values in the header skiplist
     *  Take care not to throw on any error.
     *  This means that some things will fail with no indication other than the log,
     *  but if we threw a RuntimeException we would prevent access to entries later in
     *  the SkipSpan.
     */
    private static class PropertiesSerializer implements Serializer {
        /**
         *  A format error on the properties is non-fatal (returns an empty properties)
         */
        public byte[] getBytes(Object o) {
            Properties p = (Properties) o;
            try {
                return DataHelper.toProperties(p);
            } catch (DataFormatException dfe) {
                logError("DB Write Fail - properties too big?", dfe);
                // null properties is a two-byte length of 0.
                return new byte[2];
            }
        }

        /** returns null on error */
        public Object construct(byte[] b) {
            Properties rv = new Properties();
            try {
                DataHelper.fromProperties(b, 0, rv);
            } catch (DataFormatException dfe) {
                logError("DB Read Fail", dfe);
                return null;
            }
            return rv;
        }
    }

    /**
     *  A DestEntry contains Properties and a Destination,
     *  and is serialized in that order.
     */
    private static class DestEntry {
        /** may be null */
        public Properties props;
        /** may not be null */
        public Destination dest;

        @Override
        public String toString() {
            return "DestEntry (" + DataHelper.toString(props) +
                   ") " + dest.toString();
        }
    }

    /**
     *  Used for the values in the addressbook skiplists
     *  Take care not to throw on any error.
     *  This means that some things will fail with no indication other than the log,
     *  but if we threw a RuntimeException we would prevent access to entries later in
     *  the SkipSpan.
     */
    private static class DestEntrySerializer implements Serializer {

        /**
         *  A format error on the properties is non-fatal (only the properties are lost)
         *  A format error on the destination is fatal
         */
        public byte[] getBytes(Object o) {
            DestEntry de = (DestEntry) o;
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            try {
                try {
                    DataHelper.writeProperties(baos, de.props, true, false);  // UTF-8, unsorted
                } catch (DataFormatException dfe) {
                    logError("DB Write Fail - properties too big?", dfe);
                    // null properties is a two-byte length of 0.
                    baos.write(new byte[2]);
		}
                de.dest.writeBytes(baos);
            } catch (IOException ioe) {
                logError("DB Write Fail", ioe);
            } catch (DataFormatException dfe) {
                logError("DB Write Fail", dfe);
            }
            return baos.toByteArray();
        }

        /** returns null on error */
        public Object construct(byte[] b) {
            DestEntry rv = new DestEntry();
            Destination dest = new Destination();
            rv.dest = dest;
            ByteArrayInputStream bais = new ByteArrayInputStream(b);
            try {
                rv.props = DataHelper.readProperties(bais);
                dest.readBytes(bais);
            } catch (IOException ioe) {
                logError("DB Read Fail", ioe);
                return null;
            } catch (DataFormatException dfe) {
                logError("DB Read Fail", dfe);
                return null;
            }
            return rv;
        }
    }

    /**
     *  Used to store entries that need deleting
     */
    private static class InvalidEntry {
        public final String key;
        public final String list;

        public InvalidEntry(String k, String l) {
            key = k;
            list = l;
        }
    }

    /**
     *  BlockfileNamingService [force]
     *  force = force writable
     */
    public static void main(String[] args) {
        Properties ctxProps = new Properties();
        if (args.length > 0 && args[0].equals("force"))
            ctxProps.setProperty(PROP_FORCE, "true");
        I2PAppContext ctx = new I2PAppContext(ctxProps);
        BlockfileNamingService bns = new BlockfileNamingService(ctx);
        List<String> names = null;
        Properties props = new Properties();
        try {
            DataHelper.loadProps(props, new File("hosts.txt"), true);
            names = new ArrayList(props.keySet());
            Collections.shuffle(names);
        } catch (IOException ioe) {
            System.out.println("No hosts.txt to test with");
            bns.close();
            return;
        }

        System.out.println("size() reports " + bns.size());
        System.out.println("getEntries() returns " + bns.getEntries().size());

        System.out.println("Testing with " + names.size() + " hostnames");
        int found = 0;
        int notfound = 0;
        int rfound = 0;
        int rnotfound = 0;
        long start = System.currentTimeMillis();
        for (String name : names) {
             Destination dest = bns.lookup(name);
             if (dest != null) {
                 found++;
                 String reverse = bns.reverseLookup(dest);
                 if (reverse != null)
                     rfound++;
                 else
                     rnotfound++;
             } else {
                 notfound++;
             }
        }
        System.out.println("BFNS took " + DataHelper.formatDuration(System.currentTimeMillis() - start));
        System.out.println("found " + found + " notfound " + notfound);
        System.out.println("reverse found " + rfound + " notfound " + rnotfound);

        //if (true) return;

        System.out.println("Removing all " + names.size() + " hostnames");
        found = 0;
        notfound = 0;
        Collections.shuffle(names);
        start = System.currentTimeMillis();
        for (String name : names) {
             if (bns.remove(name))
                 found++;
             else
                 notfound++;
        }
        System.out.println("BFNS took " + DataHelper.formatDuration(System.currentTimeMillis() - start));
        System.out.println("removed " + found + " not removed " + notfound);

        System.out.println("Adding back " + names.size() + " hostnames");
        found = 0;
        notfound = 0;
        Collections.shuffle(names);
        start = System.currentTimeMillis();
        for (String name : names) {
            try {
                 if (bns.put(name, new Destination(props.getProperty(name))))
                     found++;
                 else
                     notfound++;
            } catch (DataFormatException dfe) {}
        }
        System.out.println("BFNS took " + DataHelper.formatDuration(System.currentTimeMillis() - start));
        System.out.println("Added " + found + " not added " + notfound);



        //bns.dumpDB();
        bns.close();
        if (true) return;

        HostsTxtNamingService htns = new HostsTxtNamingService(I2PAppContext.getGlobalContext());
        found = 0;
        notfound = 0;
        start = System.currentTimeMillis();
        for (String name : names) {
             Destination dest = htns.lookup(name);
             if (dest != null)
                 found++;
             else
                 notfound++;
        }
        System.out.println("HTNS took " + DataHelper.formatDuration(System.currentTimeMillis() - start));
        System.out.println("found " + found + " notfound " + notfound);
    }
}
