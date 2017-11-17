/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.router.naming;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.DummyNamingService;
import net.i2p.client.naming.HostsTxtNamingService;
import net.i2p.client.naming.NamingService;
import net.i2p.client.naming.NamingServiceListener;
import net.i2p.client.naming.SingleFileNamingService;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

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
 *             "version": "4"
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
 *      value: a DestEntry, which is:
 *             a one-byte count of the Properties/Destination pairs to follow
 *               (as of database version 4, otherwise one)
 *             that many pairs of:
 *               Properties (serialized with DataHelper)
 *               Destination (serialized as usual).
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
 * @since 0.8.7, moved from core to addressbook in 0.9.31
 */
public class BlockfileNamingService extends DummyNamingService {

    private final BlockFile _bf;
    private final RAIFile _raf;
    private final List<String> _lists;
    private final List<InvalidEntry> _invalid;
    private final Map<String, String> _negativeCache;
    private volatile boolean _isClosed;
    private final boolean _readOnly;
    private String _version = "0";
    private volatile boolean _isVersion4;
    private boolean _needsUpgrade;

    private static final Serializer<Properties> _infoSerializer = new PropertiesSerializer();
    private static final Serializer<String> _stringSerializer = new UTF8StringBytes();
    private static final Serializer<DestEntry> _destSerializerV1 = new DestEntrySerializer();
    private static final Serializer<DestEntry> _destSerializerV4 = new DestEntrySerializerV4();
    // upgrade(), initExisting(), and initNew() will change this to _destSerializerV4
    private volatile Serializer<DestEntry> _destSerializer = _destSerializerV1;
    private static final Serializer<Integer> _hashIndexSerializer = new IntBytes();

    private static final String HOSTS_DB = "hostsdb.blockfile";
    private static final String FALLBACK_LIST = "hosts.txt";
    private static final String PROP_FORCE = "i2p.naming.blockfile.writeInAppContext";

    private static final String INFO_SKIPLIST = "%%__INFO__%%";
    private static final String REVERSE_SKIPLIST = "%%__REVERSE__%%";
    private static final String PROP_INFO = "info";
    private static final String PROP_VERSION = "version";
    private static final String PROP_LISTVERSION = "listversion";
    private static final String PROP_LISTS = "lists";
    private static final String PROP_CREATED = "created";
    private static final String PROP_UPGRADED = "upgraded";
    private static final String VERSION = "4";

    private static final String PROP_ADDED = "a";
    private static final String PROP_MODDED = "m";
    private static final String PROP_SOURCE = "s";
    private static final String PROP_VALIDATED = "v";
    
    private static final String DUMMY = "";
    private static final int NEGATIVE_CACHE_SIZE = 32;
    private static final int MAX_VALUE_LENGTH = 4096;
    private static final int MAX_DESTS_PER_HOST = 8;

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
        _lists = new ArrayList<String>();
        _invalid = new ArrayList<InvalidEntry>();
        _negativeCache = new LHMCache<String, String>(NEGATIVE_CACHE_SIZE);
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
                File corrupt = new File(_context.getRouterDir(), HOSTS_DB + '.' + System.currentTimeMillis() + ".corrupt");
                _log.log(Log.CRIT, "Corrupt, unsupported version, or unreadable database " +
                                   f + ", moving to " + corrupt +
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
                bf = initNew(raf);
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
    private BlockFile initNew(RAIFile f) throws IOException {
        long start = _context.clock().now();
        _version = VERSION;
        _destSerializer = _destSerializerV4;
        _isVersion4 = true;
        try {
            BlockFile rv = new BlockFile(f, true);
            SkipList<String, Properties> hdr = rv.makeIndex(INFO_SKIPLIST, _stringSerializer, _infoSerializer);
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
                _lists.add(hostsfile);
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
                        String b64 = line.substring(split+1).trim();
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
            SkipList<String, Properties> hdr = bf.getIndex(INFO_SKIPLIST, _stringSerializer, _infoSerializer);
            if (hdr == null)
                throw new IOException("No db header");
            Properties info = hdr.get(PROP_INFO);
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
            if (version == null)
                throw new IOException("No version");
            if (VersionComparator.comp(version, VERSION) > 0)
                throw new IOException("Database version is " + version +
                                      " but this implementation only supports versions 1-" + VERSION +
                                      " Did you downgrade I2P??");
            _version = version;
            if (VersionComparator.comp(version, "4") >= 0) {
                _destSerializer = _destSerializerV4;
                _isVersion4 = true;
            }
            _needsUpgrade = needsUpgrade(bf);
            if (_needsUpgrade) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Upgrading database from version " + _version + " to " + VERSION +
                              ", created " + (new Date(createdOn)).toString() +
                              " containing lists: " + list);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Found database version " + _version +
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
    private boolean needsUpgrade(BlockFile bf) throws IOException {
        if (VersionComparator.comp(_version, VERSION) >= 0)
            return false;
        if (!bf.file.canWrite()) {
            _log.logAlways(Log.WARN, "Not upgrading read-only database version " + _version);
            return false;
        }
        return true;
    }

    /**
     *  Blockfile must be writable of course.
     *
     *  Version 1->2: Add reverse skiplist and populate
     *  Version 2->3: Re-populate reverse skiplist as version 2 didn't keep it updated
     *                after the upgrade. No change to format.
     *  Version 3->4: Change format to support multiple destinations per hostname
     *
     *  @return true if upgraded successfully
     *  @since 0.8.9
     */
    private boolean upgrade() {
        try {
            // version 1 -> version 2
            // Add reverse skiplist
            if (VersionComparator.comp(_version, "2") < 0) {
                SkipList<Integer, Properties> rev = _bf.getIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
                if (rev == null) {
                    rev = _bf.makeIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Created reverse index");
                }
                setVersion("2");
            }

            // version 2 -> version 3
            // no change in format, just regenerate skiplist
            if (VersionComparator.comp(_version, "3") < 0) {
                Map<String, Destination> entries = getEntries();
                int i = 0;
                for (Map.Entry<String, Destination> entry : entries.entrySet()) {
                     addReverseEntry(entry.getKey(), entry.getValue());
                     i++;
                }
                // i may be greater than skiplist keys if there are dups
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Updated reverse index with " + i + " entries");
                setVersion("3");
            }

            // version 3 -> version 4
            // support multiple destinations per hostname
            if (VersionComparator.comp(_version, "4") < 0) {
                // Upgrade of 4K entry DB on RPi 2 is over 2 1/2 minutes, probably worse on Android, disable for now
                if (SystemVersion.isAndroid()) {
                    if (_log.shouldWarn())
                        _log.warn("Deferring upgrade to version 4 on Android");
                    return true;
                }
                SkipList<String, Properties> hdr = _bf.getIndex(INFO_SKIPLIST, _stringSerializer, _infoSerializer);
                if (hdr == null)
                    throw new IOException("No db header");
                Properties info = hdr.get(PROP_INFO);
                if (info == null)
                    throw new IOException("No header info");
                for (String list : _lists) { 
                    try {
                        // so that we can handle an aborted upgrade,
                        // we keep track of the version of each list
                        String vprop = PROP_LISTVERSION + '_' + list;
                        String listVersion = info.getProperty(vprop);
                        if (listVersion == null || VersionComparator.comp(listVersion, "4") < 0) {
                            if (_log.shouldWarn())
                                _log.warn("Upgrading " + list + " from database version 3 to 4");
                            _bf.reformatIndex(list, _stringSerializer, _destSerializerV1,
                                              _stringSerializer, _destSerializerV4);
                            info.setProperty(vprop, "4");
                            hdr.put(PROP_INFO, info);
                        } else {
                            if (_log.shouldWarn())
                                _log.warn("Partial upgrade, " + list + " already at version " + listVersion);
                        }
                    } catch (IOException ioe) {
                        _log.error("Failed upgrade of list " + list + " to version 4", ioe);
                    }
                }
                _destSerializer = _destSerializerV4;
                _isVersion4 = true;
                setVersion("4");
            }

            return true;
        } catch (IOException ioe) {
            _log.error("Error upgrading DB", ioe);
        } catch (RuntimeException e) {
            _log.error("Error upgrading DB", e);
        }
        return false;
    }

    /**
     *  Save new version number in blockfile after upgrade.
     *  Blockfile must be writable, of course.
     *  Side effect: sets _version field
     *
     *  Caller must synchronize
     *  @since 0.9.26 pulled out of upgrade()
     */
    private void setVersion(String version) throws IOException {
        SkipList<String, Properties> hdr = _bf.getIndex(INFO_SKIPLIST, _stringSerializer, _infoSerializer);
        if (hdr == null)
            throw new IOException("No db header");
        Properties info = hdr.get(PROP_INFO);
        if (info == null)
            throw new IOException("No header info");
        info.setProperty(PROP_VERSION, version);
        info.setProperty(PROP_UPGRADED, Long.toString(_context.clock().now()));
        hdr.put(PROP_INFO, info);
        if (_log.shouldLog(Log.WARN))
            _log.warn("Upgraded database from version " + _version + " to version " + version);
        _version = version;
    }

    /**
     *  For either v1 or v4.
     *  Caller must synchronize
     *  @return entry or null, or throws ioe
     */
    private DestEntry getEntry(String listname, String key) throws IOException {
        try {
            SkipList<String, DestEntry> sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
            if (sl == null)
                return null;
            DestEntry rv = sl.get(key);
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
            SkipList<String, DestEntry> sl = bf.getIndex(listname, _stringSerializer, _destSerializer);
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
     *  Single dest version.
     *  Caller must synchronize
     *
     *  @param props may be null
     *  @throws RuntimeException
     */
    private static void addEntry(SkipList<String, DestEntry> sl, String key, Destination dest, Properties props) {
        DestEntry de = new DestEntry();
        de.dest = dest;
        de.props = props;
        sl.put(key, de);
    }

    /**
     *  Multiple dests version.
     *  DB MUST be version 4.
     *  Caller must synchronize
     *
     *  @param propsList may be null, or entries may be null
     *  @throws RuntimeException
     *  @since 0.9.26
     */
    private static void addEntry(SkipList<String, DestEntry> sl, String key, List<Destination> dests, List<Properties> propsList) {
        DestEntry de = new DestEntry();
        de.destList = dests;
        de.dest = dests.get(0);
        de.propsList = propsList;
        if (propsList != null)
            de.props = propsList.get(0);
        sl.put(key, de);
    }

    private static List<String> getFilenames(String list) {
        StringTokenizer tok = new StringTokenizer(list, ",");
        List<String> rv = new ArrayList<String>(tok.countTokens());
        while (tok.hasMoreTokens())
            rv.add(tok.nextToken());
        return rv;
    }
    
    /**
     *  Caller must synchronize
     *  @return removed object or null
     *  @throws RuntimeException
     */
    private static <V> V removeEntry(SkipList<String, V> sl, String key) {
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
     *  @return all found if more than one
     *  @since 0.9.26 from getReverseEntry() 0.8.9
     */
    private List<String> getReverseEntries(Hash hash) {
        try {
            SkipList<Integer, Properties> rev = _bf.getIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
            if (rev == null)
                return null;
            Integer idx = getReverseKey(hash);
            //_log.info("Get reverse " + idx + ' ' + hash);
            Properties props = rev.get(idx);
            if (props == null)
                return null;
            List<String> rv = new ArrayList<String>(props.size());
            for (String key : props.stringPropertyNames()) {
                // now do the forward lookup to verify (using the cache)
                List<Destination> ld = lookupAll(key);
                if (ld != null) {
                    for (Destination d : ld) {
                        if (d.calculateHash().equals(hash)) {
                            rv.add(key);
                            break;
                        }
                    }
                }
            }
            if (!rv.isEmpty())
                return rv;
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
            SkipList<Integer, Properties> rev = bf.getIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
            if (rev == null)
                return;
            Integer idx = getReverseKey(dest);
            Properties props = rev.get(idx);
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
            SkipList<Integer, Properties> rev = _bf.getIndex(REVERSE_SKIPLIST, _hashIndexSerializer, _infoSerializer);
            if (rev == null)
                return;
            Integer idx = getReverseKey(dest);
            Properties props = rev.get(idx);
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
     *
     * Will strip a "www." prefix and retry if lookup fails
     *
     * @param hostname upper/lower case ok
     * @param options If non-null and contains the key "list", lookup in
     *                that list only, otherwise all lists
     */
    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        Destination rv = lookup2(hostname, lookupOptions, storedOptions);
        if (rv == null) {
            // if hostname starts with "www.", strip and try again
            // but not for www.i2p
            hostname = hostname.toLowerCase(Locale.US);
            if (hostname.startsWith("www.") && hostname.length() > 7) {
                hostname = hostname.substring(4);
                rv = lookup2(hostname, lookupOptions, storedOptions);
            }
        }
        return rv;
    }

    /*
     * Single dest version.
     *
     * @param lookupOptions If non-null and contains the key "list", lookup in
     *                that list only, otherwise all lists
     */
    private Destination lookup2(String hostname, Properties lookupOptions, Properties storedOptions) {
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
        synchronized(_negativeCache) {
            if (_negativeCache.get(key) != null)
                return null;
        }
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
        if (d != null) {
            putCache(hostname, d);
        } else {
            synchronized(_negativeCache) {
                _negativeCache.put(key, DUMMY);
            }
        }
        return d;
    }

    /*
     * Multiple dests version.
     * DB MUST be version 4.
     *
     * @param lookupOptions If non-null and contains the key "list", lookup in
     *                that list only, otherwise all lists
     * @since 0.9.26
     */
    private List<Destination> lookupAll2(String hostname, Properties lookupOptions, List<Properties> storedOptions) {
        // only use cache for b32
        if (hostname.length() == BASE32_HASH_LENGTH + 8 && hostname.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
            Destination d = super.lookup(hostname, null, null);
            if (d != null) {
                if (storedOptions != null)
                    storedOptions.add(null);
                return Collections.singletonList(d);
            }
            // Base32 failed?
            return null;
        }
        String key = hostname.toLowerCase(Locale.US);
        synchronized(_negativeCache) {
            if (_negativeCache.get(key) != null)
                return null;
        }
        String listname = null;
        if (lookupOptions != null)
            listname = lookupOptions.getProperty("list");

        List<Destination> rv = null;
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
                        if (de.destList != null) {
                            rv = de.destList;
                            if (storedOptions != null)
                                storedOptions.addAll(de.propsList);
                        } else {
                            rv = Collections.singletonList(de.dest);
                            if (storedOptions != null)
                                storedOptions.add(de.props);
                        }
                        break;
                    }
                } catch (IOException ioe) {
                    break;
                }
            }
            deleteInvalid();
        }
        if (rv != null) {
            putCache(hostname, rv.get(0));
        } else {
            synchronized(_negativeCache) {
                _negativeCache.put(key, DUMMY);
            }
        }
        return rv;
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

    /**
     * Single dest version
     * This does not prevent adding b32. Caller must check.
     *
     * @param checkExisting if true, fail if entry already exists
     */
    private boolean put(String hostname, Destination d, Properties options, boolean checkExisting) {
        if (_readOnly) {
            _log.error("Add entry failed, read-only hosts database");
            return false;
        }
        String key = hostname.toLowerCase(Locale.US);
        synchronized(_negativeCache) {
            _negativeCache.remove(key);
        }
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
                SkipList<String, DestEntry> sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null)
                    sl = _bf.makeIndex(listname, _stringSerializer, _destSerializer);
                boolean changed =  (checkExisting || !_listeners.isEmpty()) && sl.get(key) != null;
                if (changed && checkExisting)
                        return false;
                addEntry(sl, key, d, props);
                if (changed) {
                    removeCache(hostname);
                    // removeReverseEntry(key, oldDest) ???
                }
                addReverseEntry(key, d);
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
     * Multiple dests version.
     * DB MUST be version 4.
     * This does not prevent adding b32. Caller must check.
     *
     * @param propsList may be null, or entries may be null
     * @param checkExisting if true, fail if entry already exists
     * @since 0.9.26
     */
    private boolean put(String hostname, List<Destination> dests, List<Properties> propsList, boolean checkExisting) {
        int sz = dests.size();
        if (sz <= 0)
            throw new IllegalArgumentException();
        if (sz == 1)
            return put(hostname, dests.get(0), propsList != null ? propsList.get(0) : null, checkExisting);
        if (_readOnly) {
            _log.error("Add entry failed, read-only hosts database");
            return false;
        }
        String key = hostname.toLowerCase(Locale.US);
        synchronized(_negativeCache) {
            _negativeCache.remove(key);
        }
        String listname = FALLBACK_LIST;
        String date = Long.toString(_context.clock().now());
        List<Properties> outProps = new ArrayList<Properties>(propsList.size());
        for (Properties options : propsList) {
            Properties props = new Properties();
            props.setProperty(PROP_ADDED, date);
            if (options != null) {
                props.putAll(options);
                String list = options.getProperty("list");
                if (list != null) {
                    listname = list;
                    props.remove("list");
                }
            }
            outProps.add(props);
        }
        synchronized(_bf) {
            if (_isClosed)
                return false;
            try {
                SkipList<String, DestEntry> sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null)
                    sl = _bf.makeIndex(listname, _stringSerializer, _destSerializer);
                boolean changed =  (checkExisting || !_listeners.isEmpty()) && sl.get(key) != null;
                if (changed && checkExisting)
                        return false;
                addEntry(sl, key, dests, outProps);
                if (changed) {
                    removeCache(hostname);
                    // removeReverseEntry(key, oldDest) ???
                }
                for (int i = 0; i < dests.size(); i++) {
                    Destination d = dests.get(i);
                    Properties options = propsList.get(i);
                    addReverseEntry(key, d);
                    for (NamingServiceListener nsl : _listeners) { 
                        if (changed)
                            nsl.entryChanged(this, hostname, d, options);
                        else
                            nsl.entryAdded(this, hostname, d, options);
                    }
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
                SkipList<String, DestEntry> sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null)
                    return false;
                DestEntry removed = removeEntry(sl, key);
                boolean rv = removed != null;
                if (rv) {
                    removeCache(hostname);
                    try {
                        removeReverseEntry(key, removed.dest);
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
                return Collections.emptyMap();
            try {
                SkipList<String, DestEntry> sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No skiplist found for lookup in " + listname);
                    return Collections.emptyMap();
                }
                SkipIterator<String, DestEntry> iter;
                if (beginWith != null)
                    iter = sl.find(beginWith);
                else
                    iter = sl.iterator();
                Map<String, Destination> rv = new TreeMap<String, Destination>();
                for (int i = 0; i < skip && iter.hasNext(); i++) {
                    // don't bother validating here
                    iter.next();
                }
                for (int i = 0; i < limit && iter.hasNext(); ) {
                    String key = iter.nextKey();
                    if (startsWith != null) {
                        if (startsWith.equals("[0-9]")) {
                            if (key.charAt(0) > '9')
                                break;
                        } else if (!key.startsWith(startsWith)) {
                            break;
                        }
                    }
                    DestEntry de = iter.next();
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
                return Collections.emptyMap();
            } catch (RuntimeException re) {
                _log.error("DB lookup error", re);
                return Collections.emptyMap();
            } finally {
                deleteInvalid();
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
     *  @since 0.9.20
     */
    @Override
    public Map<String, String> getBase64Entries(Properties options) {
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
        synchronized(_bf) {
            if (_isClosed)
                return Collections.emptyMap();
            try {
                SkipList<String, DestEntry> sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No skiplist found for lookup in " + listname);
                    return Collections.emptyMap();
                }
                SkipIterator<String, DestEntry> iter;
                if (beginWith != null)
                    iter = sl.find(beginWith);
                else
                    iter = sl.iterator();
                Map<String, String> rv = new TreeMap<String, String>();
                for (int i = 0; i < skip && iter.hasNext(); i++) {
                    // don't bother validating here
                    iter.next();
                }
                for (int i = 0; i < limit && iter.hasNext(); ) {
                    String key = iter.nextKey();
                    if (startsWith != null) {
                        if (startsWith.equals("[0-9]")) {
                            if (key.charAt(0) > '9')
                                break;
                        } else if (!key.startsWith(startsWith)) {
                            break;
                        }
                    }
                    DestEntry de = iter.next();
                    if (!validate(key, de, listname))
                        continue;
                    if (search != null && key.indexOf(search) < 0)
                        continue;
                    rv.put(key, de.dest.toBase64());
                    i++;
                }
                return rv;
            } catch (IOException ioe) {
                _log.error("DB lookup error", ioe);
                return Collections.emptyMap();
            } catch (RuntimeException re) {
                _log.error("DB lookup error", re);
                return Collections.emptyMap();
            } finally {
                deleteInvalid();
            }
        }
    }

    /**
     *  Export in a hosts.txt format.
     *  Output is sorted.
     *  Caller must close writer.
     *
     *  @param options If non-null and contains the key "list", get
     *                from that list (default "hosts.txt", NOT all lists)
     *                Key "search": return only those matching substring
     *                Key "startsWith": return only those starting with
     *                                  ("[0-9]" allowed)
     *                Key "beginWith": start here in the iteration
     *  @since 0.9.30 override NamingService to add stored authentication strings
     */
    @Override
    public void export(Writer out, Properties options) throws IOException {
        String listname = FALLBACK_LIST;
        String search = null;
        String startsWith = null;
        String beginWith = null;
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
        }
        out.write("# Address book: ");
        out.write(getName());
        out.write(" (" + listname + ')');
        final String nl = System.getProperty("line.separator", "\n");
        out.write(nl);
        out.write("# Exported: ");
        out.write((new Date()).toString());
        out.write(nl);
        synchronized(_bf) {
            if (_isClosed)
                return;
            try {
                SkipList<String, DestEntry> sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No skiplist found for lookup in " + listname);
                    return;
                }
                if (beginWith == null && search == null) {
                    int sz = sl.size();
                    if (sz <= 0) {
                        out.write("# No entries");
                        out.write(nl);
                        return;
                    }
                    if (sz > 1) {
                        // actually not right due to multidest
                        out.write("# " + sz + " entries");
                        out.write(nl);
                    }
                }
                SkipIterator<String, DestEntry> iter;
                if (beginWith != null)
                    iter = sl.find(beginWith);
                else
                    iter = sl.iterator();
                int cnt = 0;
                while (iter.hasNext()) {
                    String key = iter.nextKey();
                    if (startsWith != null) {
                        if (startsWith.equals("[0-9]")) {
                            if (key.charAt(0) > '9')
                                break;
                        } else if (!key.startsWith(startsWith)) {
                            break;
                        }
                    }
                    DestEntry de = iter.next();
                    if (!validate(key, de, listname))
                        continue;
                    if (search != null && key.indexOf(search) < 0)
                        continue;
                    int dsz = de.destList != null ? de.destList.size() : 1;
                    // new non-DSA dest is put first, so put in reverse
                    // order so importers will see the older dest first
                    for (int i = dsz - 1; i >= 0; i--) {
                        Properties p;
                        Destination d;
                        if (i == 0) {
                            p = de.props;
                            d = de.dest;
                        } else {
                            p = de.propsList.get(i);
                            d = de.destList.get(i);
                        }
                        out.write(key);
                        out.write('=');
                        out.write(d.toBase64());
                        if (p != null)
                            SingleFileNamingService.writeOptions(p, out);
                        out.write(nl);
                        cnt++;
                    }
                }
                if (beginWith != null || search != null) {
                    if (cnt <= 0) {
                        out.write("# No entries");
                        out.write(nl);
                        return;
                    }
                    if (cnt > 1) {
                        out.write("# " + cnt + " entries");
                        out.write(nl);
                    }
                }
            } catch (RuntimeException re) {
                throw new IOException("DB lookup error", re);
            } finally {
                deleteInvalid();
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
     *  @since 0.9.20
     */
    @Override
    public Set<String> getNames(Properties options) {
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
        synchronized(_bf) {
            if (_isClosed)
                return Collections.emptySet();
            try {
                SkipList<String, DestEntry> sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
                if (sl == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No skiplist found for lookup in " + listname);
                    return Collections.emptySet();
                }
                SkipIterator<String, DestEntry> iter;
                if (beginWith != null)
                    iter = sl.find(beginWith);
                else
                    iter = sl.iterator();
                Set<String> rv = new HashSet<String>();
                for (int i = 0; i < skip && iter.hasNext(); i++) {
                    iter.next();
                }
                for (int i = 0; i < limit && iter.hasNext(); ) {
                    String key = iter.nextKey();
                    if (startsWith != null) {
                        if (startsWith.equals("[0-9]")) {
                            if (key.charAt(0) > '9')
                                break;
                        } else if (!key.startsWith(startsWith)) {
                            break;
                        }
                    }
                    if (search != null && key.indexOf(search) < 0)
                        continue;
                    rv.add(key);
                    i++;
                }
                return rv;
            } catch (IOException ioe) {
                _log.error("DB lookup error", ioe);
                return Collections.emptySet();
            } catch (RuntimeException re) {
                _log.error("DB lookup error", re);
                return Collections.emptySet();
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
        List<String> ls;
        synchronized(_bf) {
            if (_isClosed)
                return null;
            ls = getReverseEntries(h);
        }
        return (ls != null) ? ls.get(0) : null;
    }

    /**
     * @param options ignored
     * @since 0.9.26
     */
    @Override
    public List<String> reverseLookupAll(Destination d, Properties options) {
        return reverseLookupAll(d.calculateHash());
    }

    /**
     * @since 0.9.26
     */
    @Override
    public List<String> reverseLookupAll(Hash h) {
        synchronized(_bf) {
            if (_isClosed)
                return null;
            return getReverseEntries(h);
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
                SkipList<String, DestEntry> sl = _bf.getIndex(listname, _stringSerializer, _destSerializer);
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

    //// Begin new API for multiple Destinations

    /**
     *  Return all of the entries found in the first list found, or in the list
     *  specified in lookupOptions. Does not aggregate all destinations found
     *  in all lists.
     *
     *  If storedOptions is non-null, it must be a List that supports null entries.
     *  If the returned value (the List of Destinations) is non-null,
     *  the same number of Properties objects will be added to storedOptions.
     *  If no properties were found for a given Destination, the corresponding
     *  entry in the storedOptions list will be null.
     *
     *  @param lookupOptions input parameter, NamingService-specific, may be null
     *  @param storedOptions output parameter, NamingService-specific, any stored properties will be added if non-null
     *  @return non-empty List of Destinations, or null if nothing found
     *  @since 0.9.26
     */
    @Override
    public List<Destination> lookupAll(String hostname, Properties lookupOptions, List<Properties> storedOptions) {
        if (!_isVersion4)
            return super.lookupAll(hostname, lookupOptions, storedOptions);
        List<Destination> rv = lookupAll2(hostname, lookupOptions, storedOptions);
        if (rv == null) {
            // if hostname starts with "www.", strip and try again
            // but not for www.i2p
            hostname = hostname.toLowerCase(Locale.US);
            if (hostname.startsWith("www.") && hostname.length() > 7) {
                hostname = hostname.substring(4);
                rv = lookupAll2(hostname, lookupOptions, storedOptions);
            }
        }
        // we sort the destinations in addDestination(),
        // which is a lot easier than sorting them here
        return rv;
    }

    /**
     *  Add a Destination to an existing hostname's entry in the addressbook.
     *
     *  This does not prevent adding b32. Caller must check.
     *
     *  @param options NamingService-specific, may be null
     *  @return success
     *  @since 0.9.26
     */
    @Override
    public boolean addDestination(String hostname, Destination d, Properties options) {
        if (!_isVersion4)
            return putIfAbsent(hostname, d, options);
        List<Properties> storedOptions = new ArrayList<Properties>(4);
        synchronized(_bf) {
            // We use lookupAll2(), not lookupAll(), because if hostname starts with www.,
            // we do not want to read in from the
            // non-www hostname and then copy it to a new www hostname.
            List<Destination> dests = lookupAll2(hostname, options, storedOptions);
            if (dests == null)
                return put(hostname, d, options, false);
            if (dests.contains(d))
                return false;
            if (dests.size() >= MAX_DESTS_PER_HOST)
                return false;
            List<Destination> newDests = new ArrayList<Destination>(dests.size() + 1);
            newDests.addAll(dests);
            // TODO better sort by sigtype preference.
            // For now, non-DSA at the front, DSA at the end
            SigType type = d.getSigningPublicKey().getType();
            if (type != SigType.DSA_SHA1 && type.isAvailable()) {
                newDests.add(0, d);
                storedOptions.add(0, options);
            } else {
                newDests.add(d);
                storedOptions.add(options);
            }
            return put(hostname, newDests, storedOptions, false);
        }
    }

    /**
     *  Remove a hostname's entry only if it contains the Destination d.
     *  If the NamingService supports multiple Destinations per hostname,
     *  and this is the only Destination, removes the entire entry.
     *  If aditional Destinations remain, it only removes the
     *  specified Destination from the entry.
     *
     *  @param options NamingService-specific, may be null
     *  @return true if entry containing d was successfully removed.
     *  @since 0.9.26
     */
    @Override
    public boolean remove(String hostname, Destination d, Properties options) {
        if (!_isVersion4) {
            // super does a get-test-remove, so lock around that
            synchronized(_bf) {
                return super.remove(hostname, d, options);
            }
        }
        List<Properties> storedOptions = new ArrayList<Properties>(4);
        synchronized(_bf) {
            // We use lookupAll2(), not lookupAll(), because if hostname starts with www.,
            // we do not want to read in from the
            // non-www hostname and then copy it to a new www hostname.
            List<Destination> dests = lookupAll2(hostname, options, storedOptions);
            if (dests == null)
                return false;
            for (int i = 0; i < dests.size(); i++) {
                Destination dd = dests.get(i);
                if (dd.equals(d)) {
                    // Found it. Remove and return.
                    if (dests.size() == 1)
                        return remove(hostname, options);
                    List<Destination> newDests = new ArrayList<Destination>(dests.size() - 1);
                    for (int j = 0; j < dests.size(); j++) {
                        if (j != i)
                            newDests.add(dests.get(j));
                    }
                    storedOptions.remove(i);
                    removeReverseEntry(hostname, d);
                    if (options != null) {
                        String list = options.getProperty("list");
                        if (list != null)
                            storedOptions.get(0).setProperty("list", list);
                    }
                    return put(hostname, newDests, storedOptions, false);
                }
            }
        }
        return false;
    }

    //// End new API for multiple Destinations

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
        if (_isVersion4 && rv && de.destList != null) {
            // additional checks for multi-dest
            rv = de.propsList != null &&
                 de.destList.size() == de.propsList.size() &&
                 !de.destList.contains(null);
        }
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
                SkipList<String, DestEntry> sl = _bf.getIndex(list, _stringSerializer, _destSerializer);
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

  /****
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
  ****/

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
        synchronized(_negativeCache) {
            _negativeCache.clear();
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
    private static class PropertiesSerializer implements Serializer<Properties> {
        /**
         *  A format error on the properties is non-fatal (returns an empty properties)
         */
        public byte[] getBytes(Properties p) {
            try {
                return DataHelper.toProperties(p);
            } catch (DataFormatException dfe) {
                logError("DB Write Fail - properties too big?", dfe);
                // null properties is a two-byte length of 0.
                return new byte[2];
            }
        }

        /** returns null on error */
        public Properties construct(byte[] b) {
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
        /** May be null.
         *  If more than one dest, contains the first props.
         */
        public Properties props;

        /** May not be null.
         *  If more than one dest, contains the first dest.
         */
        public Destination dest;

        /** May be null - v4 only - same size as destList - may contain null entries
         *  Only non-null if more than one dest.
         *  First entry always equal to props.
         */
        public List<Properties> propsList;

        /** May be null - v4 only - same size as propsList
         *  Only non-null if more than one dest.
         *  First entry always equal to dest.
         */
        public List<Destination> destList;

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
    private static class DestEntrySerializer implements Serializer<DestEntry> {

        /**
         *  A format error on the properties is non-fatal (only the properties are lost)
         *  A format error on the destination is fatal
         */
        public byte[] getBytes(DestEntry de) {
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
        public DestEntry construct(byte[] b) {
            DestEntry rv = new DestEntry();
            ByteArrayInputStream bais = new ByteArrayInputStream(b);
            try {
                rv.props = DataHelper.readProperties(bais);
                //dest.readBytes(bais);
                // Will this flush the dest cache too much?
                rv.dest = Destination.create(bais);
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
     *  For multiple destinations per hostname
     *  @since 0.9.26
     */
    private static class DestEntrySerializerV4 implements Serializer<DestEntry> {

        public byte[] getBytes(DestEntry de) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            int sz = de.destList != null ? de.destList.size() : 1;
            try {
                baos.write((byte) sz);
                for (int i = 0; i < sz; i++) {
                    Properties p;
                    Destination d;
                    if (i == 0) {
                        p = de.props;
                        d = de.dest;
                    } else {
                        p = de.propsList.get(i);
                        d = de.destList.get(i);
                    }
                    try {
                        writeProperties(baos, p);
                    } catch (DataFormatException dfe) {
                        logError("DB Write Fail - properties too big?", dfe);
                        baos.write(new byte[2]);
                    }
                    d.writeBytes(baos);
                }
            } catch (IOException ioe) {
                logError("DB Write Fail", ioe);
            } catch (DataFormatException dfe) {
                logError("DB Write Fail", dfe);
            }
            return baos.toByteArray();
        }

        /** returns null on error */
        public DestEntry construct(byte[] b) {
            DestEntry rv = new DestEntry();
            ByteArrayInputStream bais = new ByteArrayInputStream(b);
            try {
                int sz = bais.read() & 0xff;
                if (sz <= 0)
                    throw new DataFormatException("bad dest count " + sz);
                rv.props = readProperties(bais);
                rv.dest = Destination.create(bais);
                if (sz > 1) {
                    rv.propsList = new ArrayList<Properties>(sz);
                    rv.destList = new ArrayList<Destination>(sz);
                    rv.propsList.add(rv.props);
                    rv.destList.add(rv.dest);
                    for (int i = 1; i < sz; i++) {
                        rv.propsList.add(readProperties(bais));
                        rv.destList.add(Destination.create(bais));
                    }
                }
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
     * Same as DataHelper.writeProperties, UTF-8, unsorted,
     * except that values may up to 4K bytes.
     *
     * @param props source may be null
     * @throws DataFormatException if any key string is over 255 bytes long,
     *                             if any value string is over 4096 bytes long, or if the total length
     *                             (not including the two length bytes) is greater than 65535 bytes.
     * @since 0.9.26
     */
    private static void writeProperties(ByteArrayOutputStream rawStream, Properties p) 
            throws DataFormatException, IOException {
        if (p != null && !p.isEmpty()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(p.size() * 32);
            for (Map.Entry<Object, Object> entry : p.entrySet()) {
                String key = (String) entry.getKey();
                String val = (String) entry.getValue();
                DataHelper.writeStringUTF8(baos, key);
                baos.write('=');
                writeLongStringUTF8(baos, val);
                baos.write(';');
            }
            if (baos.size() > 65535)
                throw new DataFormatException("Properties too big (65535 max): " + baos.size());
            byte propBytes[] = baos.toByteArray();
            DataHelper.writeLong(rawStream, 2, propBytes.length);
            rawStream.write(propBytes);
        } else {
            DataHelper.writeLong(rawStream, 2, 0);
        }
    }

    /**
     * Same as DataHelper.readProperties, UTF-8, unsorted,
     * except that values may up to 4K bytes.
     *
     * Throws DataFormatException on duplicate key
     *
     * @param in stream to read the mapping from
     * @throws DataFormatException if the format is invalid
     * @throws IOException if there is a problem reading the data
     * @return a Properties
     * @since 0.9.26
     */
    public static Properties readProperties(ByteArrayInputStream in) 
        throws DataFormatException, IOException {
        Properties props = new Properties();
        int size = (int) DataHelper.readLong(in, 2);
        // this doesn't prevent reading past the end on corruption
        int ignore = in.available() - size;
        while (in.available() > ignore) {
            String key = DataHelper.readString(in);
            int b = in.read();
            if (b != '=')
                throw new DataFormatException("Bad key " + b);
            String val = readLongString(in);
            b = in.read();
            if (b != ';')
                throw new DataFormatException("Bad value");
            Object old = props.put(key, val);
            if (old != null)
                throw new DataFormatException("Duplicate key " + key);
        }
        return props;
    }

    /**
     * Same as DataHelper.writeStringUTF8, except that
     * strings up to 4K bytes are allowed.
     * Format is: one-byte length + data, or 0xff + two-byte length + data
     *
     * @param out stream to write string
     * @param string to write out: null strings are valid, but strings of excess length will
     *               cause a DataFormatException to be thrown
     * @throws DataFormatException if the string is not valid
     * @throws IOException if there is an IO error writing the string
     */
    private static void writeLongStringUTF8(ByteArrayOutputStream out, String string) 
        throws DataFormatException, IOException {
        if (string == null) {
            out.write(0);
        } else {
            byte[] raw = string.getBytes("UTF-8");
            int len = raw.length;
            if (len >= 255) {
                if (len > MAX_VALUE_LENGTH)
                    throw new DataFormatException(MAX_VALUE_LENGTH + " max, but this is "
                                              + len + " [" + string + "]");
                out.write(0xff);
                DataHelper.writeLong(out, 2, len);
            } else {
                out.write(len);
            }
            out.write(raw);
        }
    }

    /**
     * Same as DataHelper.readString, except that
     * strings up to 4K bytes are allowed.
     * Format is: one-byte length + data, or 0xff + two-byte length + data
     *
     * @param in stream to read from
     * @throws DataFormatException if the stream doesn't contain a validly formatted string
     * @throws EOFException if there aren't enough bytes to read the string
     * @throws IOException if there is an IO error reading the string
     * @return UTF-8 string
     */
    private static String readLongString(ByteArrayInputStream in) throws DataFormatException, IOException {
        int size = in.read();
        if (size < 0)
            throw new EOFException("EOF reading string");
        if (size == 0xff) {
            size = (int) DataHelper.readLong(in, 2);
            if (size > MAX_VALUE_LENGTH)
                throw new DataFormatException(MAX_VALUE_LENGTH + " max, but this is " + size);
        }
        if (size == 0)
            return "";
        byte raw[] = new byte[size];
        int read = DataHelper.read(in, raw);
        if (read != size)
            throw new EOFException("EOF reading string");
        return new String(raw, "UTF-8");
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
        Properties sprops = new Properties();
        String lname = "privatehosts.txt";
        sprops.setProperty("list", lname);
        System.out.println("List " + lname + " contains " + bns.size(sprops));
        lname = "userhosts.txt";
        sprops.setProperty("list", lname);
        System.out.println("List " + lname + " contains " + bns.size(sprops));
        lname = "hosts.txt";
        sprops.setProperty("list", lname);
        System.out.println("List " + lname + " contains " + bns.size(sprops));

/****
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
        System.out.println("size() reports " + bns.size());


        //bns.dumpDB();
****/
        bns.close();
        ctx.logManager().flush();
        System.out.flush();
/****
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
****/
    }
}
