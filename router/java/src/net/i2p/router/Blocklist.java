package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Use at your own risk.
 * zzz 2008-06
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.app.ClientAppManager;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.GeoIP;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.update.UpdateManager;
import net.i2p.update.UpdateType;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

/**
 * Manage blocking by IP address, in a manner similar to the Banlist,
 * which blocks by router hash.
 *
 * We also try to keep the two lists in sync: if a router at a given IP is
 * blocked, we will also banlist it "forever" (until the next reboot).
 *
 * While the reverse case (blocking the IP of a router banlisted forever)
 * is not automatic, the transports will call add() below to block the IP,
 * which allows the transports to terminate an inbound connection before
 * the router ident handshake.
 *
 * And the on-disk blocklist can also contain router hashes to be banlisted.
 *
 * So, this class maintains three separate lists:
 *<pre>
 *   1) The list of IP ranges, read in from a file at startup
 *   2) The list of hashes, read in from the same file
 *   3) A list of single IPs, initially empty, added to as needed
 *</pre>
 *
 * Read in the IP blocklist from a file, store it in-memory as efficiently
 * as we can, and perform tests against it as requested.
 *
 * When queried for a peer that is blocklisted but isn't banlisted,
 * banlist it forever, then go back to the file to get the original
 * entry so we can add the reason to the banlist text.
 *
 * On-disk blocklist supports IPv4 only.
 * In-memory supports both IPv4 and IPv6.
 */
public class Blocklist {
    private final Log _log;
    private final RouterContext _context;
    private long _blocklist[];
    private int _blocklistSize;
    private long _countryBlocklist[];
    private int _countryBlocklistSize;
    private final Object _lock = new Object();
    private Entry _wrapSave;
    private final Set<Hash> _inProcess = new HashSet<Hash>(4);
    private final File _blocklistFeedFile;
    private final boolean _haveIPv6;
    private boolean _started;
    // temp
    private final Map<Hash, String> _peerBlocklist = new HashMap<Hash, String>(4);
    
    private static final String PROP_BLOCKLIST_ENABLED = "router.blocklist.enable";
    private static final String PROP_BLOCKLIST_DETAIL = "router.blocklist.detail";
    private static final String PROP_BLOCKLIST_FILE = "router.blocklist.file";
    public static final String BLOCKLIST_FILE_DEFAULT = "blocklist.txt";
    private static final String BLOCKLIST_FEED_FILE = "docs/feed/blocklist/blocklist.txt";
    /** @since 0.9.48 */
    public static final String BLOCKLIST_COUNTRY_FILE = "blocklist-country.txt";

    /**
     *  Limits of transient (in-memory) blocklists.
     *  Note that it's impossible to prevent clogging up
     *  the tables by a determined attacker, esp. on IPv6
     */
    private static final int MAX_IPV4_SINGLES = SystemVersion.isSlow() ? 2048 : 8192;
    private static final int MAX_IPV6_SINGLES = SystemVersion.isSlow() ? 256 : 4096;

    private final Map<Integer, Object> _singleIPBlocklist = new LHMCache<Integer, Object>(MAX_IPV4_SINGLES);
    private final Map<BigInteger, Object> _singleIPv6Blocklist;

    private static final Object DUMMY = Integer.valueOf(0);    

    /**
     *  For Update Manager
     *  @since 0.9.48
     */
    public static final String ID_FEED = "feed";
    private static final String ID_SYSTEM = "system";
    private static final String ID_LOCAL = "local";
    private static final String ID_COUNTRY = "country";
    private static final String ID_USER = "user";
    public static final String ID_SYBIL = "sybil";


    /**
     *  Router MUST call startup()
     */
    public Blocklist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Blocklist.class);
        _blocklistFeedFile = new File(context.getConfigDir(), BLOCKLIST_FEED_FILE);
        _haveIPv6 = TransportUtil.getIPv6Config(_context, "SSU") != TransportUtil.IPv6Config.IPV6_DISABLED &&
                    Addresses.isConnectedIPv6();
        _singleIPv6Blocklist = _haveIPv6 ? new LHMCache<BigInteger, Object>(MAX_IPV6_SINGLES) : null;
    }
    
    /** only for testing with main() */
    private Blocklist() {
        _context = null;
        _log = new Log(Blocklist.class);
        _blocklistFeedFile = new File(BLOCKLIST_FEED_FILE);
        _haveIPv6 = TransportUtil.getIPv6Config(_context, "SSU") != TransportUtil.IPv6Config.IPV6_DISABLED &&
                    Addresses.isConnectedIPv6();
        _singleIPv6Blocklist = _haveIPv6 ? new LHMCache<BigInteger, Object>(MAX_IPV6_SINGLES) : null;
    }

    /**
     *  Loads the following files in-order:
     *  $I2P/blocklist.txt
     *  ~/.i2p/blocklist.txt
     *  ~/.i2p/docs/feed/blocklist/blocklist.txt
     *  ~/.i2p/blocklist-countries.txt
     *  File if specified with router.blocklist.file
     */
    public synchronized void startup() {
        if (_started)
            return;
        _started = true;
        if (! _context.getBooleanPropertyDefaultTrue(PROP_BLOCKLIST_ENABLED))
            return;
        List<BLFile> files = new ArrayList<BLFile>(5);

        // install dir
        File blFile = new File(_context.getBaseDir(), BLOCKLIST_FILE_DEFAULT);
        files.add(new BLFile(blFile, ID_SYSTEM));
        // config dir
        if (!_context.getConfigDir().equals(_context.getBaseDir())) {
            blFile = new File(_context.getConfigDir(), BLOCKLIST_FILE_DEFAULT);
            files.add(new BLFile(blFile, ID_LOCAL));
        }
        files.add(new BLFile(_blocklistFeedFile, ID_FEED));
        if (_context.router().isHidden() ||
            _context.getBooleanProperty(GeoIP.PROP_BLOCK_MY_COUNTRY)) {
            blFile = new File(_context.getConfigDir(), BLOCKLIST_COUNTRY_FILE);
            files.add(new BLFile(blFile, ID_COUNTRY));
        }
        // user specified
        String file = _context.getProperty(PROP_BLOCKLIST_FILE);
        if (file != null && !file.equals(BLOCKLIST_FILE_DEFAULT)) {
            blFile = new File(file);
            if (!blFile.isAbsolute())
                 blFile = new File(_context.getConfigDir(), file);
            files.add(new BLFile(blFile, ID_USER));
        }
        Job job = new ReadinJob(files);
        // Run immediately, so it's initialized before netdb.
        // As this is called by Router.runRouter() before job queue parallel operation,
        // this will block StartupJob, and will complete before netdb initialization.
        // If there is a huge blocklist, it will delay router startup,
        // but it's important to have this initialized before we read in the netdb.
        //job.getTiming().setStartAfter(_context.clock().now() + 30*1000);
        _context.jobQueue().addJob(job);
    }

    /**
     *  @since 0.9.48
     */
    private static class BLFile {
        public final File file;
        public final String id;
        public long version;
        public BLFile(File f, String s) { file = f; id = s; }
    }

    /**
     *  Delay telling update manager until it's there
     *  @since 0.9.48
     */
    private class VersionNotifier extends SimpleTimer2.TimedEvent {
        public final List<BLFile> blfs;

        public VersionNotifier(List<BLFile> bf) {
            super(_context.simpleTimer2(), 2*60*1000L);
            blfs = bf;
        }

        public void timeReached() {
            ClientAppManager cmgr = _context.clientAppManager();
            if (cmgr != null) {
                UpdateManager umgr = (UpdateManager) cmgr.getRegisteredApp(UpdateManager.APP_NAME);
                if (umgr != null) {
                    for (BLFile blf : blfs) {
                        if (blf.version > 0)
                               umgr.notifyInstalled(UpdateType.BLOCKLIST, blf.id, Long.toString(blf.version));
                    }
                } else {
                    _log.warn("No update manager");
                }
            }
        }
    }

    private class ReadinJob extends JobImpl {
        private final List<BLFile> _files;

        /**
         *  @param files not necessarily existing, but avoid dups
         */
        public ReadinJob (List<BLFile> files) {
            super(_context);
            _files = files;
        }

        public String getName() { return "Read Blocklist"; }

        public void runJob() {
            synchronized (_lock) {
                _blocklist = allocate(_files);
                if (_blocklist == null)
                    return;
                int ccount = process();
                if (_blocklist == null)
                    return;
                if (ccount <= 0) {
                    disable();
                    return;
                }
                _blocklistSize = merge(_blocklist, ccount);
                // we're done with _peerBlocklist, but leave it
                // in case we need it for a later readin
                //_peerBlocklist = null;
            }
            // schedules itself
            new VersionNotifier(_files);
        }

        private int process() {
            int count = 0;
                try {
                    for (BLFile blf : _files) {
                        count = readBlocklistFile(blf, _blocklist, count);
                    }
                } catch (OutOfMemoryError oom) {
                    _log.log(Log.CRIT, "OOM processing the blocklist");
                    disable();
                    return 0;
                }
            for (Hash peer : _peerBlocklist.keySet()) {
                String reason;
                String comment = _peerBlocklist.get(peer);
                if (comment != null)
                    reason = _x("Banned by router hash: {0}");
                else
                    reason = _x("Banned by router hash");
                _context.banlist().banlistRouterForever(peer, reason, comment);
            }
            _peerBlocklist.clear();
            return count;
        }
    }

    /**
     *  The blocklist-country.txt file was created or updated.
     *  Read it in. Not required normally, as the country file
     *  is read by startup().
     *  @since 0.9.48
     */
    public synchronized void addCountryFile() {
        File blFile = new File(_context.getConfigDir(), BLOCKLIST_COUNTRY_FILE);
        BLFile blf = new BLFile(blFile, ID_COUNTRY);
        List<BLFile> c = Collections.singletonList(blf);
        long[] cb = allocate(c);
        if (cb == null)
            return;
        int count = readBlocklistFile(blf, cb, 0);
        if (count <= 0)
            return;
        ClientAppManager cmgr = _context.clientAppManager();
        if (cmgr != null) {
            UpdateManager umgr = (UpdateManager) cmgr.getRegisteredApp(UpdateManager.APP_NAME);
            if (umgr != null)
                umgr.notifyInstalled(UpdateType.BLOCKLIST, ID_COUNTRY, Long.toString(blFile.lastModified()));
        }
        count = merge(cb, count);
        _countryBlocklistSize = count;
        _countryBlocklist = cb;
    }

    public void disable() {
        // hmm better block out any checks in process
        synchronized (_lock) {
            _blocklistSize = 0;
            _blocklist = null;
        }
    }

    /**
     *  @return array or null on failure
     *  @since 0.9.18 split out from readBlocklistFile()
     */
    private long[] allocate(List<BLFile> files) {
        int maxSize = 0;
        for (BLFile blf : files) {
            maxSize += getSize(blf.file);
        }
        try {
            return new long[maxSize + files.size()];  // extra for wrapsave
        } catch (OutOfMemoryError oom) {
            _log.log(Log.CRIT, "OOM creating the blocklist");
            return null;
        }
    }

   /**
    * Read in and parse the blocklist.
    * The blocklist need not be sorted, and may contain overlapping entries.
    *
    * Acceptable formats (IPV4 only):
    *   #comment (# must be in column 1)
    *   comment:IP-IP
    *   comment:morecomments:IP-IP
    *   IP-IP
    *   (comments also allowed before any of the following)
    *   IP/masklength
    *   IP
    *   hostname (DNS looked up at list readin time, not dynamically, so may not be much use)
    *   44-byte Base64 router hash
    *
    * Acceptable formats (IPV6 only):
    *   comment:IPv6 (must replace : with ; e.g. abcd;1234;0;12;;ff)
    *   IPv6 (must replace : with ; e.g. abcd;1234;0;12;;ff)
    *
    * No whitespace allowed after the last ':'.
    *
    * For further information and downloads:
    *   http://www.bluetack.co.uk/forums/index.php?autocom=faq&CODE=02&qid=17
    *   http://blocklist.googlepages.com/
    *   http://www.cymru.com/Documents/bogon-list.html
    *
    *
    * Must call allocate() before and merge() after.
    *
    *  @param blocklist out parameter, entries stored here
    *  @param count current number of entries
    *  @return new number of entries
    */
    private int readBlocklistFile(BLFile blf, long[] blocklist, int count) {
        File blFile = blf.file;
        if (blFile == null || (!blFile.exists()) || blFile.length() <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Blocklist file not found: " + blFile);
            return count;
        }

        long start = _context.clock().now();
        int oldcount = count;
        int badcount = 0;
        int peercount = 0;
        int feedcount = 0;
        long ipcount = 0;
        final boolean isFeedFile = blFile.equals(_blocklistFeedFile);
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(blFile), "UTF-8"));
            String source = blFile.toString();
            String buf = null;
            while ((buf = br.readLine()) != null) {
                Entry e = parse(buf, true);
                if (e == null) {
                    badcount++;
                    continue;
                }
                if (e.peer != null) {
                    _peerBlocklist.put(e.peer, e.comment);
                    peercount++;
                    continue;
                }
                byte[] ip1 = e.ip1;
                if (ip1.length == 4) {
                    //if (isFeedFile) {
                    //    // temporary
                    //    add(ip1, source);
                    //    feedcount++;
                    //} else {
                        byte[] ip2 = e.ip2;
                        store(ip1, ip2, blocklist, count++);
                        ipcount += 1 + toInt(ip2) - toInt(ip1); // includes dups, oh well
                    //}
                } else {
                    // IPv6
                    add(ip1, source);
                }
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the blocklist file", ioe);
            return count;
        } catch (OutOfMemoryError oom) {
            disable();
            _log.log(Log.CRIT, "OOM reading the blocklist");
            return 0;
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }

        if (_wrapSave != null) {
            // the extra record generated in parse() by a line that
            // wrapped around 128.0.0.0
            store(_wrapSave.ip1, _wrapSave.ip2, blocklist, count++);
            ipcount += 1 + toInt(_wrapSave.ip2) - toInt(_wrapSave.ip1);
            _wrapSave = null;
        }
        int read = isFeedFile ? feedcount : (count - oldcount);
        // save to tell the update manager
        if (read > 0)
            blf.version = blFile.lastModified();
        if (_log.shouldLog(Log.INFO)) {
            _log.info("Stats for " + blFile);
            _log.info("Removed " + badcount + " bad entries and comment lines");
            _log.info("Read " + read + " valid entries from the blocklist " + blFile);
            //_log.info("Blocking " + (isFeedFile ? feedcount : ipcount) + " IPs and " + peercount + " hashes");
            _log.info("Blocking " + ipcount + " IPs and " + peercount + " hashes");
            _log.info("Blocklist processing finished, time: " + (_context.clock().now() - start));
        }
        return count;
    }

    /**
     *  @param count valid entries in blocklist before merge
     *  @return count valid entries in blocklist after merge
     *  @since 0.9.18 split out from readBlocklistFile()
     */
    private int merge(long[] blocklist, int count) {
        long start = _context.clock().now();
        // This is a standard signed sort, so the entries will be ordered
        // 128.0.0.0 ... 255.255.255.255 0.0.0.0 .... 127.255.255.255
        // But that's ok.
        int removed = 0;
        try {
            Arrays.sort(blocklist, 0, count);
            removed = removeOverlap(blocklist, count);
            if (removed > 0) {
                // Sort again to remove the dups that were "zeroed" out as 127.255.255.255-255.255.255.255
                Arrays.sort(blocklist, 0, count);
                // sorry, no realloc to save memory, don't want to blow up now
            }
        } catch (OutOfMemoryError oom) {
            disable();
            _log.log(Log.CRIT, "OOM sorting the blocklist");
            return 0;
        }
        int blocklistSize = count - removed;
        if (_log.shouldLog(Log.INFO)) {
            _log.info("Merged Stats:\n" +
                      "Read " + count + " total entries from the blocklists\n" +
                      "Merged " + removed + " overlapping entries\n" +
                      "Result is " + blocklistSize + " entries\n" +
                      "Blocklist processing finished, time: " + (_context.clock().now() - start));
        }
        return blocklistSize;
    }

    /**
     *  The result of parsing one line
     */
    private static class Entry {
        final String comment;
        final byte ip1[];
        final byte ip2[];
        final Hash peer;

        public Entry(String c, Hash h, byte[] i1, byte[] i2) {
             comment = c;
             peer = h;
             ip1 = i1;
             ip2 = i2;
        }
    }

    /**
     *  Parse one line, returning a temp data structure with the result
     */
    private Entry parse(String buf, boolean shouldLog) {
        byte[] ip1;
        byte[] ip2;
        int start1 = 0;
        int end1 = buf.length();
        if (end1 <= 0)
            return null;  // blank
        //if (buf.charAt(end1 - 1) == '\r') {  // DataHelper.readLine leaves the \r on there
        //    buf.deleteCharAt(end1 - 1);
        //    end1--;
        //}
        //if (end1 <= 0)
        //    return null;  // blank
        int start2 = -1;
        int mask = -1;
        String comment = null;
        int index = buf.indexOf('#');
        if (index == 0)
            return null;  // comment
        index = buf.lastIndexOf(':');
        if (index >= 0) {
            comment = buf.substring(0, index);
            start1 = index + 1;
        }
        if (end1 - start1 == 44 && buf.substring(start1).indexOf('.') < 0) {
            byte b[] = Base64.decode(buf.substring(start1));
            if (b != null)
                return new Entry(comment, Hash.create(b), null, null);
        }
        index = buf.indexOf('-', start1);
        if (index >= 0) {
            end1 = index;
            start2 = index + 1;
        } else {
            index = buf.indexOf('/', start1);
            if (index >= 0) {
                end1 = index;
                mask = index + 1;
            }
        }
        if (end1 - start1 <= 0)
            return null;  // blank
        try {
            String sip = buf.substring(start1, end1);
            // IPv6
            sip = sip.replace(';', ':');
            InetAddress pi = InetAddress.getByName(sip);
            if (pi == null) return null;
            ip1 = pi.getAddress();
            //if (ip1.length != 4)
            //    throw new UnknownHostException();
            if (start2 >= 0) {
                pi = InetAddress.getByName(buf.substring(start2));
                if (pi == null) return null;
                ip2 = pi.getAddress();
                if (ip2.length != 4)
                    throw new UnknownHostException();
                if ((ip1[0] & 0xff) < 0x80 && (ip2[0] & 0xff) >= 0x80) {
                    if (_wrapSave == null) {
                        // don't cross the boundary 127.255.255.255 - 128.0.0.0
                        // because we are sorting using signed arithmetic
                        _wrapSave = new Entry(comment, null, new byte[] {(byte)0x80,0,0,0}, new byte[] {ip2[0], ip2[1], ip2[2], ip2[3]});
                        ip2 = new byte[] {127, (byte)0xff, (byte)0xff, (byte)0xff};
                    } else
                        // We only save one entry crossing the boundary, throw the rest out
                        throw new NumberFormatException();

                }
                for (int i = 0; i < 4; i++) {
                     if ((ip2[i] & 0xff) > (ip1[i] & 0xff))
                        break;
                     if ((ip2[i] & 0xff) < (ip1[i] & 0xff))
                        throw new NumberFormatException(); // backwards
                }
            } else if (mask >= 0) {
                int m = Integer.parseInt(buf.substring(mask));
                if (m < 3 || m > 32)
                    throw new NumberFormatException();
                ip2 = new byte[4];
                // ick
                for (int i = 0; i < 4; i++)
                    ip2[i] = ip1[i];
                for (int i = 0; i < 32-m; i++)
                    ip2[(31-i)/8] |= (0x01 << (i%8));
            } else {
                ip2 = ip1;
            }
        } catch (UnknownHostException uhe) {
            if (shouldLog)
                _log.logAlways(Log.WARN, "Format error in the blocklist file: " + buf);
            return null;
        } catch (NumberFormatException nfe) {
            if (shouldLog)
                _log.logAlways(Log.WARN, "Format error in the blocklist file: " + buf);
            return null;
        } catch (IndexOutOfBoundsException ioobe) {
            if (shouldLog)
                _log.logAlways(Log.WARN, "Format error in the blocklist file: " + buf);
            return null;
        }
        return new Entry(comment, null, ip1, ip2);
    }

    /**
     * Read the file once just to see how many entries are in it,
     * so we can size our array.
     * This is i/o inefficient, but memory-efficient, which is what we want.
     */
    private int getSize(File blFile) {
        if ( (!blFile.exists()) || (blFile.length() <= 0) ) return 0;
        int lines = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(blFile), "ISO-8859-1"));
            String s;
            while ((s = br.readLine()) != null) {
                if (s.length() > 0 && !s.startsWith("#"))
                    lines++;
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error reading the blocklist file", ioe);
            return 0;
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
        return lines;
    }
    
    /**
     * Merge and remove overlapping entries from a sorted list.
     * Returns number of removed entries.
     * Caller must re-sort if return code is > 0.
     */
    private int removeOverlap(long blist[], int count) {
        if (count <= 0) return 0;
        int lines = 0;
        for (int i = 0; i < count - 1; ) {
            int removed = 0;
            int to = getTo(blist[i]);
            for (int next = i + 1; next < count; next++) {
                if (to < getFrom(blist[next]))
                    break;
                if (_log.shouldInfo())
                    _log.info("Combining entries " + toStr(blist[i]) + " and " + toStr(blist[next]));
                int nextTo = getTo(blist[next]);
                if (nextTo > to) // else entry next is totally inside entry i
                    store(getFrom(blist[i]), nextTo, blist, i);
                blist[next] = Long.MAX_VALUE;  // to be removed with another sort
                lines++;
                removed++;
            }
            i += removed + 1;
        }
        return lines;
    }

    /**
     * Maintain a simple in-memory single-IP blocklist
     * This is used for new additions, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     */
    public void add(String ip) {
        if (!_haveIPv6 && ip.indexOf(':') >= 0)
            return;
        byte[] pib = Addresses.getIPOnly(ip);
        if (pib == null) return;
        add(pib, null);
    }

    /**
     * Maintain a simple in-memory single-IP blocklist
     * This is used for new additions, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     * @param source for logging only, may be null
     * @since 0.9.57
     */
    public void add(String ip, String source) {
        if (!_haveIPv6 && ip.indexOf(':') >= 0)
            return;
        byte[] pib = Addresses.getIPOnly(ip);
        if (pib == null) return;
        add(pib, source);
    }

    /**
     * Maintain a simple in-memory single-IP blocklist
     * This is used for new additions, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     */
    public void add(byte ip[]) {
        add(ip, null);
    }

    /**
     * Maintain a simple in-memory single-IP blocklist
     * This is used for new additions, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     * @param source for logging only, may be null
     * @since 0.9.57
     */
    public void add(byte ip[], String source) {
        boolean rv;
        if (ip.length == 4) {
            // don't ever block ourselves
            String us = _context.getProperty(UDPTransport.PROP_IP);
            if (us != null) {
                byte[] usb = Addresses.getIP(us);
                if (usb != null && DataHelper.eq(usb, ip)) {
                    if (_log.shouldWarn())
                        _log.warn("Not adding our own IP " + us, new Exception());
                    return;
                }
            }
            rv = add(toInt(ip));
            if (rv)
                _context.commSystem().removeExemption(Addresses.toString(ip));
        } else if (ip.length == 16) {
            if (!_haveIPv6)
                return;
            // don't ever block ourselves
            String us = _context.getProperty(UDPTransport.PROP_IPV6);
            if (us != null) {
                byte[] usb = Addresses.getIP(us);
                if (usb != null && DataHelper.eq(usb, ip)) {
                    if (_log.shouldWarn())
                        _log.warn("Not adding our own IP " + us, new Exception());
                    return;
                }
            }
            rv = add(new BigInteger(1, ip));
            if (rv)
                _context.commSystem().removeExemption(Addresses.toCanonicalString(ip));
        } else {
            return;
        }
        if (rv) {
            // lower log level at startup when initializing from blocklist files
            if (source == null && _log.shouldWarn())
                _log.warn("Added: " + Addresses.toString(ip), new Exception("source"));
            else if (_log.shouldDebug())
                _log.debug("Added: " + Addresses.toString(ip) + " source: " + source);
        }
    }

    /**
     * Remove from the in-memory single-IP blocklist.
     * This is only works to undo add()s, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     * @since 0.9.28
     */
    public void remove(byte ip[]) {
        if (ip.length == 4) {
            remove(toInt(ip));
        } else if (ip.length == 16) {
            if (!_haveIPv6)
                return;
            remove(new BigInteger(1, ip));
        }
    }

    /**
     * @return true if it was NOT previously on the list
     */
    private boolean add(int ip) {
        // save space, don't put in both
        if (isPermanentlyBlocklisted(ip))
            return false;
        Integer iip = Integer.valueOf(ip);
        synchronized(_singleIPBlocklist) {
            return _singleIPBlocklist.put(iip, DUMMY) == null;
        }
    }

    /**
     * @since 0.9.28
     */
    private void remove(int ip) {
        Integer iip = Integer.valueOf(ip);
        synchronized(_singleIPBlocklist) {
            _singleIPBlocklist.remove(iip);
        }
    }

    private boolean isOnSingleList(int ip) {
        Integer iip = Integer.valueOf(ip);
        synchronized(_singleIPBlocklist) {
            return _singleIPBlocklist.get(iip) != null;
        }
    }

    /**
     * @param ip IPv6 non-negative
     * @return true if it was NOT previously on the list
     * @since IPv6
     */
    private boolean add(BigInteger ip) {
        synchronized(_singleIPv6Blocklist) {
            return _singleIPv6Blocklist.put(ip, DUMMY) == null;
        }
    }

    /**
     * @param ip IPv6 non-negative
     * @since 0.9.28
     */
    private void remove(BigInteger ip) {
        synchronized(_singleIPv6Blocklist) {
            _singleIPv6Blocklist.remove(ip);
        }
    }

    /**
     * @param ip IPv6 non-negative
     * @since IPv6
     */
    private boolean isOnSingleList(BigInteger ip) {
        synchronized(_singleIPv6Blocklist) {
            return _singleIPv6Blocklist.get(ip) != null;
        }
    }

    /**
     * Will not contain duplicates.
     */
    private List<byte[]> getAddresses(Hash peer) {
        RouterInfo pinfo = _context.netDb().lookupRouterInfoLocally(peer);
        if (pinfo == null)
            return Collections.emptyList();
        return getAddresses(pinfo);
    }

    /**
     * Will not contain duplicates.
     * @since 0.9.29
     */
    private List<byte[]> getAddresses(RouterInfo pinfo) {
        List<byte[]> rv = new ArrayList<byte[]>(4);
        // for each peer address
        for (RouterAddress pa : pinfo.getAddresses()) {
            byte[] pib = pa.getIP();
            if (pib == null) continue;
            if (!_haveIPv6 && pib.length == 16)
                continue;
            // O(n**2)
            boolean dup = false;
            for (int i = 0; i < rv.size(); i++) {
                if (DataHelper.eq(rv.get(i), pib)) {
                    dup = true;
                    break;
                }
            }
            if (!dup)
                rv.add(pib);
         }
         return rv;
    }

    /**
     * Does the peer's IP address appear in the blocklist?
     * If so, and it isn't banlisted, banlist it forever...
     */
    public boolean isBlocklisted(Hash peer) {
        List<byte[]> ips = getAddresses(peer);
        if (ips.isEmpty())
            return false;
        for (byte[] ip : ips) {
            if (isBlocklisted(ip)) {
                if (! _context.banlist().isBanlisted(peer))
                    // nice knowing you...
                    banlist(peer, ip);
                return true;
            }
        }
        return false;
    }

    /**
     * Does the peer's IP address appear in the blocklist?
     * If so, and it isn't banlisted, banlist it forever...
     * @since 0.9.29
     */
    public boolean isBlocklisted(RouterInfo pinfo) {
        List<byte[]> ips = getAddresses(pinfo);
        if (ips.isEmpty())
            return false;
        for (byte[] ip : ips) {
            if (isBlocklisted(ip)) {
                Hash peer = pinfo.getHash();
                if (! _context.banlist().isBanlisted(peer))
                    // nice knowing you...
                    banlist(peer, ip);
                return true;
            }
        }
        return false;
    }

    /**
     * calling this externally won't banlist the peer, this is just an IP check
     *
     * @param ip IPv4 or IPv6
     */
    public boolean isBlocklisted(String ip) {
        if (!_haveIPv6 && ip.indexOf(':') >= 0)
            return false;
        byte[] pib = Addresses.getIPOnly(ip);
        if (pib == null) return false;
        return isBlocklisted(pib);
    }

    /**
     * calling this externally won't banlist the peer, this is just an IP check
     *
     * @param ip IPv4 or IPv6
     */
    public boolean isBlocklisted(byte ip[]) {
        if (ip.length == 4)
            return isBlocklisted(toInt(ip));
        if (ip.length == 16) {
            if (!_haveIPv6)
                return false;
            return isOnSingleList(new BigInteger(1, ip));
        }
        return false;
    }

    /**
     * First check the single-IP list.
     * Then do a
     * binary search through the in-memory range list which
     * is a sorted array of longs.
     * The array is sorted in signed order, but we don't care.
     * Each long is ((from << 32) | to)
     */ 
    private boolean isBlocklisted(int ip) {
        if (isOnSingleList(ip))
            return true;
        if (_countryBlocklist != null) {
            if (isPermanentlyBlocklisted(ip, _countryBlocklist, _countryBlocklistSize))
                return true;
        }
        return isPermanentlyBlocklisted(ip);
    }

    /**
     * Do a binary search through the in-memory range list which
     * is a sorted array of longs.
     * The array is sorted in signed order, but we don't care.
     * Each long is ((from &lt;&lt; 32) | to)
     *
     * Public for console only, not a public API
     *
     * @since 0.9.45 split out from above, public since 0.9.48 for console
     */ 
    public boolean isPermanentlyBlocklisted(int ip) {
        return isPermanentlyBlocklisted(ip, _blocklist, _blocklistSize);
    }

    /**
     * Do a binary search through the in-memory range list which
     * is a sorted array of longs.
     * The array is sorted in signed order, but we don't care.
     * Each long is ((from << 32) | to)
     *
     * @since 0.9.48 split out from above
     */ 
    private static boolean isPermanentlyBlocklisted(int ip, long[] blocklist, int blocklistSize) {
        int hi = blocklistSize - 1;
        if (hi <= 0)
            return false;
        int lo = 0;
        int cur = hi / 2;

        while  (!match(ip, blocklist[cur])) {
            if (isHigher(ip, blocklist[cur]))
                lo = cur;
            else
                hi = cur;
            // make sure we get the last one
            if (hi - lo <= 1) {
                if (lo == cur)
                    cur = hi;        
                else        
                    cur = lo;        
                break;
            } else {
                cur = lo + ((hi - lo) / 2);
            }
        }
        return match(ip, blocklist[cur]);
    }

/*
    // Is the IP included in the entry _blocklist[cur] ?
    private boolean match(int ip, int cur) {
        return match(ip, _blocklist[cur]);
    }
*/

    // Is the IP included in the compressed entry?
    private static boolean match(int ip, long entry) {
        if (getFrom(entry) > ip)
                return false;
        return (ip <= getTo(entry));
    }

    // Is the IP higher than the entry _blocklist[cur] ?
    private static boolean isHigher(int ip, long entry) {
        return ip > getFrom(entry);
    }

    // methods to get and store the from/to values in the array

    /**
     *  Public for console only, not a public API
     *  @since public since 0.9.48
     */
    public static int getFrom(long entry) {
        return (int) ((entry >> 32) & 0xffffffff);
    }

    /**
     *  Public for console only, not a public API
     *  @since public since 0.9.48
     */
    public static int getTo(long entry) {
        return (int) (entry & 0xffffffff);
    }

    /**
     * The in-memory blocklist is an array of longs, with the format
     * ((from IP) << 32) | (to IP)
     * The XOR is so the signed sort is in normal (unsigned) order.
     *
     * So the size is (cough) almost 2MB for the 240,000 line splist.txt.
     *
     */
    private static long toEntry(byte ip1[], byte ip2[]) {
        long entry = 0;
        for (int i = 0; i < 4; i++)
            entry |= ((long) (ip2[i] & 0xff)) << ((3-i)*8);
        for (int i = 0; i < 4; i++)
            entry |= ((long) (ip1[i] & 0xff)) << (32 + ((3-i)*8));
        return entry;
    }

    /**
     *  IPv4 only
     */
    private static void store(byte ip1[], byte ip2[], long[] blocklist, int idx) {
        blocklist[idx] = toEntry(ip1, ip2);
    }

    private static void store(int ip1, int ip2, long[] blocklist, int idx) {
        long entry = ((long) ip1) << 32;
        entry |= ((long)ip2) & 0xffffffff;
        blocklist[idx] = entry;
    }

    private static int toInt(byte ip[]) {
        int rv = 0;
        for (int i = 0; i < 4; i++)
            rv |= (ip[i] & 0xff) << ((3-i)*8);
        return rv;
    }

    private static String toStr(long entry) {
        StringBuilder buf = new StringBuilder(32);
        for (int i = 7; i >= 0; i--) {
            buf.append((entry >> (8*i)) & 0xff);
            if (i == 4)
                buf.append('-');
            else if (i > 0)
                buf.append('.');
        }
        return buf.toString();
    }

    /**
     *  Public for console only, not a public API
     *  @since public since 0.9.48
     */
    public static String toStr(int ip) {
        StringBuilder buf = new StringBuilder(16);
        for (int i = 3; i >= 0; i--) {
            buf.append((ip >> (8*i)) & 0xff);
            if (i > 0)
                buf.append('.');
        }
        return buf.toString();
    }

    /**
     * We don't keep the comment field in-memory,
     * so we have to go back out to the file to find it.
     *
     * Put this in a job because we're looking for the
     * actual line in the blocklist file, this could take a while.
     *
     */
    private void banlist(Hash peer, byte[] ip) {
        // Don't bother unless we have IPv6
        if (!_haveIPv6 && ip.length == 16)
            return;
        // Temporary reason, until the job finishes
        String reason = _x("IP banned by blocklist.txt entry {0}");
        String sip = Addresses.toString(ip);
        if ("127.0.0.1".equals(sip) ||
            "0:0:0:0:0:0:0:1".equals(sip) ||
            sip.startsWith("192.168.")) {
            // i2pd bug, possibly at startup, don't ban forever
            _context.banlist().banlistRouter(peer, reason, sip, null,
                                             _context.clock().now() + Banlist.BANLIST_DURATION_LOCALHOST);
            return;
        }
        _context.banlist().banlistRouterForever(peer, reason, sip);
        if (!  _context.getBooleanPropertyDefaultTrue(PROP_BLOCKLIST_DETAIL))
            return;
        boolean shouldRunJob;
        int number;
        synchronized (_inProcess) {
            number = _inProcess.size();
            shouldRunJob = _inProcess.add(peer);
        }
        if (!shouldRunJob)
            return;
        // get the IPs now because it won't be in the netdb by the time the job runs
        Job job = new BanlistJob(peer, getAddresses(peer));
        if (number > 0)
            job.getTiming().setStartAfter(_context.clock().now() + (30*1000l * number));
        _context.jobQueue().addJob(job);
    }

    private class BanlistJob extends JobImpl {
        private final Hash _peer;
        private final List<byte[]> _ips;
        public BanlistJob (Hash p, List<byte[]> ips) {
            super(_context);
            _peer = p;
            _ips = ips;
        }
        public String getName() { return "Ban Peer by IP"; }
        public void runJob() {
            banlistForever(_peer, _ips);
            synchronized (_inProcess) {
                _inProcess.remove(_peer);
            }
        }
    }

    /**
     * Look up the original record so we can record the reason in the banlist.
     * That's the only reason to do this.
     * Only synchronize to cut down on the I/O load.
     * Additional jobs can wait.
     * Although could this clog up the job queue runners? Yes.
     * So we also stagger these jobs.
     *
     */
    private synchronized void banlistForever(Hash peer, List<byte[]> ips) {
        // This only checks one file for now, pick the best one
        // user specified
        File blFile = null;
        String file = _context.getProperty(PROP_BLOCKLIST_FILE);
        if (file != null) {
            blFile = new File(file);
            if (!blFile.isAbsolute())
                 blFile = new File(_context.getConfigDir(), file);
            if (!blFile.exists())
                blFile = null;
        }
        // install dir
        if (blFile == null)
            blFile = new File(_context.getBaseDir(), BLOCKLIST_FILE_DEFAULT);

        if ((!blFile.exists()) || blFile.length() <= 0) {
            // just ban it and be done
            if (_log.shouldLog(Log.WARN))
                _log.warn("Banlisting " + peer);
            _context.banlist().banlistRouterForever(peer, "Banned");
            return;
        }

        // look through the file for each address to find which one was the cause
        for (Iterator<byte[]> iter = ips.iterator(); iter.hasNext(); ) {
            byte ip[] = iter.next();
            int ipint = toInt(ip);
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(blFile), "UTF-8"));
                String buf = null;
                // assume the file is unsorted, so go through the whole thing
                while ((buf = br.readLine()) != null) {
                    Entry e = parse(buf, false);
                    if (e == null || e.peer != null) {
                        continue;
                    }
                    if (match(ipint, toEntry(e.ip1, e.ip2))) {
                        try { br.close(); } catch (IOException ioe) {}
                        String reason = _x("IP banned by blocklist.txt entry {0}");
                        // only one translate parameter for now
                        //for (int i = 0; i < 4; i++) {
                        //    reason = reason + (ip[i] & 0xff);
                        //    if (i != 3)
                        //        reason = reason + '.';
                        //}
                        //reason = reason + " banned by " + BLOCKLIST_FILE_DEFAULT + " entry \"" + buf + "\"";
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Banlisting " + peer + " " + reason);
                        _context.banlist().banlistRouterForever(peer, reason, buf.toString());
                        return;
                    }
                }
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error reading the blocklist file", ioe);
            } finally {
                if (br != null) try { br.close(); } catch (IOException ioe) {}
            }
        }
        // We already banlisted in banlist(peer), that's good enough
    }

    /**
     *  Single IPs blocked until restart. Unsorted.
     *
     *  Public for console only, not a public API
     *  As of 0.9.57, will not contain IPs permanently banned,
     *  except for ones banned permanently after being added to the transient list.
     *
     *  @return a copy, unsorted
     *  @since 0.9.48
     */
    public List<Integer> getTransientIPv4Blocks() {
        synchronized(_singleIPBlocklist) {
            return new ArrayList<Integer>(_singleIPBlocklist.keySet());
        }
    }

    /**
     *  Single IPs blocked until restart. Unsorted.
     *
     *  Public for console only, not a public API
     *
     *  @return a copy, unsorted
     *  @since 0.9.48
     */
    public List<BigInteger> getTransientIPv6Blocks() {
        if (!_haveIPv6)
            return Collections.<BigInteger>emptyList();
        synchronized(_singleIPv6Blocklist) {
            return new ArrayList<BigInteger>(_singleIPv6Blocklist.keySet());
        }
    }

    /**
     *  IP ranges blocked until restart. Sorted,
     *  but as signed longs, so 128-255 are first
     *
     *  Public for console only, not a public API
     *
     *  @param max maximum entries to return
     *  @return a copy, sorted
     *  @since 0.9.48
     */
    public synchronized long[] getPermanentBlocks(int max) {
        long[] rv;
        if (_blocklistSize <= max) {
            rv = new long[_blocklistSize];
            System.arraycopy(_blocklist, 0, rv, 0, _blocklistSize);
        } else {
            // skip ahead to the positive entries
            int i = 0;
            for (; i < _blocklistSize; i++) {
                int from = Blocklist.getFrom(_blocklist[i]);
                if (from >= 0)
                    break;
            }
            int sz = Math.min(_blocklistSize - i, max);
            rv = new long[sz];
            System.arraycopy(_blocklist, i, rv, 0, sz);
        }
        return rv;
    }

    /**
     *  Size of permanent blocklist
     *
     *  Public for console only, not a public API
     *
     *  @since 0.9.48
     */
    public synchronized int getBlocklistSize() {
        return _blocklistSize;
    }

    /**
     *  Does nothing, moved to console ConfigPeerHelper
     *
     *  @deprecated
     */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {
        return s;
    }

/****
    public static void main(String args[]) throws Exception {
        Blocklist b = new Blocklist(new Router().getContext());
        if (args != null && args.length == 1) {
            File f = new File(args[0]);
            b.allocate(Collections.singletonList(f));
            int count = b.readBlocklistFile(f, 0);
            b.merge(count);
            Writer w = new java.io.OutputStreamWriter(System.out);
            b.renderStatusHTML(w);
        }
        System.out.println("Saved " + b._blocklistSize + " records");
        String tests[] = {"0.0.0.0", "0.0.0.1", "0.0.0.2", "0.0.0.255", "1.0.0.0",
                                        "3.3.3.3", "77.1.2.3", "127.0.0.0", "127.127.127.127", "128.0.0.0",
                                        "129.1.2.3", "255.255.255.254", "255.255.255.255"};
        for (int i = 0; i < tests.length; i++) {
            System.out.println("Testing " + tests[i] + " returns " + b.isBlocklisted(tests[i]));
        }
    }
****/
}
