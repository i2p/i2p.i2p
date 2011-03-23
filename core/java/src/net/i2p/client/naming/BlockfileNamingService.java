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

import net.metanotion.io.Serializer;
import net.metanotion.io.block.BlockFile;
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
 *             "version": "1"
 *             "created": Java long time (ms)
 *             "lists":   Comma-separated list of host databases, to be
 *                        searched in-order for lookups
 *
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
 */
public class BlockfileNamingService extends DummyNamingService {

    private final BlockFile _bf;
    private final RandomAccessFile _raf;
    private final List<String> _lists;
    private volatile boolean _isClosed;

    private static final Serializer _infoSerializer = new PropertiesSerializer();
    private static final Serializer _stringSerializer = new StringSerializer();
    private static final Serializer _destSerializer = new DestEntrySerializer();

    private static final String HOSTS_DB = "hostsdb.blockfile";
    private static final String FALLBACK_LIST = "hosts.txt";

    private static final String INFO_SKIPLIST = "%%__INFO__%%";
    private static final String PROP_INFO = "info";
    private static final String PROP_VERSION = "version";
    private static final String PROP_LISTS = "lists";
    private static final String PROP_CREATED = "created";
    private static final String PROP_MODIFIED = "modified";
    private static final String VERSION = "1";

    private static final String PROP_ADDED = "a";
    private static final String PROP_SOURCE = "s";
    
    /**
     *  @throws RuntimeException on fatal error
     */
    public BlockfileNamingService(I2PAppContext context) {
        super(context);
        _lists = new ArrayList();
        BlockFile bf = null;
        RandomAccessFile raf = null;
        File f = new File(_context.getRouterDir(), HOSTS_DB);
        if (f.exists()) {
            try {
                // closing a BlockFile does not close the underlying file,
                // so we must create and retain a RAF so we may close it later
                raf = new RandomAccessFile(f, "rw");
                bf = initExisting(raf);
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
                raf = new RandomAccessFile(f, "rw");
                SecureFileOutputStream.setPerms(f);
                bf = init(raf);
            } catch (IOException ioe) {
                if (raf != null) {
                    try { raf.close(); } catch (IOException e) {}
                }
                _log.log(Log.CRIT, "Failed to initialize database", ioe);
                throw new RuntimeException(ioe);
            }
        }
        _bf = bf;
        _raf = raf;
        _context.addShutdownTask(new Shutdown());
    }

    /**
     *  Create a new database and initialize it from the local files
     *  privatehosts.txt, userhosts.txt, and hosts.txt,
     *  creating a skiplist in the database for each.
     */
    private BlockFile init(RandomAccessFile f) throws IOException {
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

            // TODO all in one skiplist or separate?
            int total = 0;
            for (String hostsfile : getFilenames(list)) {
                File file = new File(_context.getRouterDir(), hostsfile);
                if ((!file.exists()) || !(file.canRead()))
                    continue;
                int count = 0;
                BufferedReader in = null;
                try {
                    in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"), 16*1024);
                    String line = null;
                    while ( (line = in.readLine()) != null) {
                        if (line.startsWith("#"))
                            continue;
                        int split = line.indexOf('=');
                        if (split <= 0)
                            continue;
                        String key = line.substring(0, split).toLowerCase();
                        if (line.indexOf('#') > 0)  { // trim off any end of line comment
                            line = line.substring(0, line.indexOf('#')).trim();
                            if (line.length() < split + 1)
                                continue;
                        }
                        String b64 = line.substring(split+1);   //.trim() ??????????????
                        Destination d = lookupBase64(b64);
                        if (d != null) {
                            addEntry(rv, hostsfile, key, d, hostsfile);
                            count++;
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
    private BlockFile initExisting(RandomAccessFile raf) throws IOException {
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
            String version = info.getProperty(PROP_VERSION);
            if (!VERSION.equals(version))
                throw new IOException("Bad db version: " + version);

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
            if (_log.shouldLog(Log.INFO))
                _log.info("Found database version " + version + " created " + (new Date(createdOn)).toString() +
                          " containing lists: " + list);

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
    private void addEntry(SkipList sl, String key, Destination dest, String source) {
        Properties props = new Properties();
        props.setProperty(PROP_ADDED, Long.toString(_context.clock().now()));
        if (source != null)
            props.setProperty(PROP_SOURCE, source);
        addEntry(sl, key, dest, props);
    }

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

    ////////// Start NamingService API

    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        Destination d = super.lookup(hostname, null, null);
        if (d != null)
            return d;

        String key = hostname.toLowerCase();
        synchronized(_bf) {
            if (_isClosed)
                return null;
            for (String list : _lists) { 
                try {
                    DestEntry de = getEntry(list, key);
                    if (de != null) {
                        d = de.dest;
                        if (storedOptions != null)
                            storedOptions.putAll(de.props);
                        break;
                    }
                } catch (IOException ioe) {
                    break;
                }
            }
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
     *                Use the key "s" for the source
     */
    @Override
    public boolean putIfAbsent(String hostname, Destination d, Properties options) {
        return put(hostname, d, options, true);
    }

    private boolean put(String hostname, Destination d, Properties options, boolean checkExisting) {
        String key = hostname.toLowerCase();
        String listname = FALLBACK_LIST;
        Properties props = new Properties();
        if (options != null) {
            props.putAll(options);
            String list = options.getProperty("list");
            if (list != null) {
                listname = list;
                props.remove("list");
            }
        }
        props.setProperty(PROP_ADDED, Long.toString(_context.clock().now()));
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
                if (changed)
                    removeCache(hostname);
                for (NamingServiceListener nsl : _listeners) { 
                    if (changed)
                        nsl.entryChanged(this, hostname, d, options);
                    else
                        nsl.entryAdded(this, hostname, d, options);
                }
                return true;
            } catch (IOException re) {
                return false;
            } catch (RuntimeException re) {
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
        String key = hostname.toLowerCase();
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
                boolean rv = removeEntry(sl, key) != null;
                if (rv) {
                    removeCache(hostname);
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
            }
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
                         _log.error("DB " + list + " key " + key + " val " + de);
                         i++;
                    }
                    _log.error(i + " entries found for " + list);
                } catch (IOException ioe) {
                    _log.error("Fail", ioe);
                    break;
                }
            }
        }
    }

    private void close() {
        synchronized(_bf) {
            try {
                _bf.close();
            } catch (IOException ioe) {
            } catch (RuntimeException e) {
            }
            try {
                _raf.close();
            } catch (IOException ioe) {
            }
            _isClosed = true;
        }
    }

    private class Shutdown implements Runnable {
        public void run() {
            close();
        }
    }

    /**
     *  UTF-8 Serializer (the one in the lib is US-ASCII).
     *  Used for all keys.
     */
    private static class StringSerializer implements Serializer {
        public byte[] getBytes(Object o) {
            try {
                return ((String) o).getBytes("UTF-8");
            } catch (UnsupportedEncodingException uee) {
                throw new RuntimeException("No UTF-8", uee);
            }
        }

        public Object construct(byte[] b) {
            try {
                return new String(b, "UTF-8");
            } catch (UnsupportedEncodingException uee) {
                throw new RuntimeException("No UTF-8", uee);
            }
        }
    }

    /**
     *  Used for the values in the header skiplist
     */
    private static class PropertiesSerializer implements Serializer {
        public byte[] getBytes(Object o) {
            Properties p = (Properties) o;
            try {
                return DataHelper.toProperties(p);
            } catch (DataFormatException dfe) {
                return null;
            }
        }

        public Object construct(byte[] b) {
            Properties rv = new Properties();
            try {
                DataHelper.fromProperties(b, 0, rv);
            } catch (IOException ioe) {
                return null;
            } catch (DataFormatException dfe) {
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
     */
    private static class DestEntrySerializer implements Serializer {
        public byte[] getBytes(Object o) {
            DestEntry de = (DestEntry) o;
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            try {
                DataHelper.writeProperties(baos, de.props);
                de.dest.writeBytes(baos);
            } catch (IOException ioe) {
                return null;
            } catch (DataFormatException dfe) {
                return null;
            }
            return baos.toByteArray();
        }

        public Object construct(byte[] b) {
            DestEntry rv = new DestEntry();
            Destination dest = new Destination();
            rv.dest = dest;
            ByteArrayInputStream bais = new ByteArrayInputStream(b);
            try {
                rv.props = DataHelper.readProperties(bais);
                dest.readBytes(bais);
            } catch (IOException ioe) {
                return null;
            } catch (DataFormatException dfe) {
                return null;
            }
            return rv;
        }
    }

    public static void main(String[] args) {
        BlockfileNamingService bns = new BlockfileNamingService(I2PAppContext.getGlobalContext());
        List<String> names = null;
        try {
            Properties props = new Properties();
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
        long start = System.currentTimeMillis();
        for (String name : names) {
             Destination dest = bns.lookup(name);
             if (dest != null)
                 found++;
             else
                 notfound++;
        }
        System.out.println("BFNS took " + DataHelper.formatDuration(System.currentTimeMillis() - start));
        System.out.println("found " + found + " notfound " + notfound);
        bns.dumpDB();
        bns.close();

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
