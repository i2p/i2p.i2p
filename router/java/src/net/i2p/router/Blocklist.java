package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Use at your own risk.
 * zzz 2008-06
 */

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 * Manage blocking by IP address, in a manner similar to the Shitlist,
 * which blocks by router hash.
 *
 * We also try to keep the two lists in sync: if a router at a given IP is
 * blocked, we will also shitlist it "forever" (until the next reboot).
 *
 * While the reverse case (blocking the IP of a router shitlisted forever)
 * is not automatic, the transports will call add() below to block the IP,
 * which allows the transports to terminate an inbound connection before
 * the router ident handshake.
 *
 * And the on-disk blocklist can also contain router hashes to be shitlisted.
 *
 * So, this class maintains three separate lists:
 *   1) The list of IP ranges, read in from a file at startup
 *   2) The list of hashes, read in from the same file
 *   3) A list of single IPs, initially empty, added to as needed
 *
 * Read in the IP blocklist from a file, store it in-memory as efficiently
 * as we can, and perform tests against it as requested.
 *
 * When queried for a peer that is blocklisted but isn't shitlisted,
 * shitlist it forever, then go back to the file to get the original
 * entry so we can add the reason to the shitlist text.
 *
 */
public class Blocklist {
    private Log _log;
    private RouterContext _context;
    private long _blocklist[];
    private int _blocklistSize;
    private final Object _lock = new Object();
    private Entry _wrapSave;
    private final Set<Hash> _inProcess = new HashSet(4);
    private Map<Hash, String> _peerBlocklist = new HashMap(4);
    private final Set<Integer> _singleIPBlocklist = new ConcurrentHashSet(4);
    
    public Blocklist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Blocklist.class);
        _blocklist = null;
        _blocklistSize = 0;
        _wrapSave = null;
    }
    
    public Blocklist() {
        _log = new Log(Blocklist.class);
        _blocklist = null;
        _blocklistSize = 0;
    }
    
    static final String PROP_BLOCKLIST_ENABLED = "router.blocklist.enable";
    static final String PROP_BLOCKLIST_DETAIL = "router.blocklist.detail";
    static final String PROP_BLOCKLIST_FILE = "router.blocklist.file";
    static final String BLOCKLIST_FILE_DEFAULT = "blocklist.txt";

    public void startup() {
        if (! Boolean.valueOf(_context.getProperty(PROP_BLOCKLIST_ENABLED, "true")).booleanValue())
            return;
        String file = _context.getProperty(PROP_BLOCKLIST_FILE, BLOCKLIST_FILE_DEFAULT);
        // Maybe someday we'll read in multiple files and merge them
        // StringTokenizer tok = new StringTokenizer(file, " ,\r\n");
        // while (tok.hasMoreTokens())
        //    readBlocklistFile(tok.nextToken());
        Job job = new ReadinJob(file);
        job.getTiming().setStartAfter(_context.clock().now() + 2*60*1000);
        _context.jobQueue().addJob(job);
    }

    private class ReadinJob extends JobImpl {
        private String _file;
        public ReadinJob (String f) {
            super(_context);
            _file = f;
        }
        public String getName() { return "Read Blocklist"; }
        public void runJob() {
            synchronized (_lock) {
                try {
                    readBlocklistFile(_file);
                } catch (OutOfMemoryError oom) {
                    _log.log(Log.CRIT, "OOM processing the blocklist");
                    disable();
                    return;
                }
            }
            for (Iterator<Hash> iter = _peerBlocklist.keySet().iterator(); iter.hasNext(); ) {
                Hash peer = iter.next();
                String reason;
                String comment = (String) _peerBlocklist.get(peer);
                if (comment != null)
                    reason = _x("Banned by router hash: {0}");
                else
                    reason = _x("Banned by router hash");
                _context.shitlist().shitlistRouterForever(peer, reason, comment);
            }
            _peerBlocklist = null;

            if (_blocklistSize <= 0)
                return;
            FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
            int count = 0;
            for (Iterator<RouterInfo> iter = fndf.getKnownRouterData().iterator(); iter.hasNext(); ) {
                RouterInfo ri = iter.next();
                Hash peer = ri.getIdentity().getHash();
                if (isBlocklisted(peer))
                    count++;
            }
            if (count > 0 && _log.shouldLog(Log.WARN))
                _log.warn("Blocklisted " + count + " routers in the netDb.");
        }
    }

    public void disable() {
        // hmm better block out any checks in process
        synchronized (_lock) {
            _blocklistSize = 0;
            _blocklist = null;
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
    * No whitespace allowed after the last ':'.
    *
    * For further information and downloads:
    *   http://www.bluetack.co.uk/forums/index.php?autocom=faq&CODE=02&qid=17
    *   http://blocklist.googlepages.com/
    *   http://www.cymru.com/Documents/bogon-list.html
    */
    private void readBlocklistFile(String file) {
        File BLFile = new File(file);
        if (!BLFile.isAbsolute())
            BLFile = new File(_context.getConfigDir(), file);
        if (BLFile == null || (!BLFile.exists()) || BLFile.length() <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Blocklist file not found: " + file);
            return;
        }
        long start = _context.clock().now();
        int maxSize = getSize(BLFile);
        try {
            _blocklist = new long[maxSize + 1];  // extra for wrapsave
        } catch (OutOfMemoryError oom) {
            _log.log(Log.CRIT, "OOM creating the blocklist");
            return;
        }
        int count = 0;
        int badcount = 0;
        int peercount = 0;
        long ipcount = 0;
        FileInputStream in = null;
        try {
            in = new FileInputStream(BLFile);
            StringBuilder buf = new StringBuilder(128);
            while (DataHelper.readLine(in, buf) && count < maxSize) {
                Entry e = parse(buf, true);
                buf.setLength(0);
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
                byte[] ip2 = e.ip2;

                store(ip1, ip2, count++);
                ipcount += 1 + toInt(ip2) - toInt(ip1); // includes dups, oh well
            }
        } catch (IOException ioe) {
            _blocklist = null;
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error reading the BLFile", ioe);
            return;
        } catch (OutOfMemoryError oom) {
            _blocklist = null;
            _log.log(Log.CRIT, "OOM reading the blocklist");
            return;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }

        if (_wrapSave != null) {
            store(_wrapSave.ip1, _wrapSave.ip2, count++);
            ipcount += 1 + toInt(_wrapSave.ip2) - toInt(_wrapSave.ip1);
        }

        // This is a standard signed sort, so the entries will be ordered
        // 128.0.0.0 ... 255.255.255.255 0.0.0.0 .... 127.255.255.255
        // But that's ok.
        int removed = 0;
        try {
            Arrays.sort(_blocklist, 0, count);
            removed = removeOverlap(_blocklist, count);
            if (removed > 0) {
                // Sort again to remove the dups that were "zeroed" out as 127.255.255.255-255.255.255.255
                Arrays.sort(_blocklist, 0, count);
                // sorry, no realloc to save memory, don't want to blow up now
            }
        } catch (OutOfMemoryError oom) {
            _blocklist = null;
            _log.log(Log.CRIT, "OOM sorting the blocklist");
            return;
        }
        _blocklistSize = count - removed;
        if (_log.shouldLog(Log.WARN)) {
            _log.warn("Removed " + badcount + " bad entries and comment lines");
            _log.warn("Read " + count + " valid entries from the blocklist " + BLFile);
            _log.warn("Merged " + removed + " overlapping entries");
            _log.warn("Result is " + _blocklistSize + " entries");
            _log.warn("Blocking " + ipcount + " IPs and " + peercount + " hashes");
            _log.warn("Blocklist processing finished, time: " + (_context.clock().now() - start));
        }
    }

    private static class Entry {
        String comment;
        byte ip1[];
        byte ip2[];
        Hash peer;

        public Entry(String c, Hash h, byte[] i1, byte[] i2) {
             comment = c;
             peer = h;
             ip1 = i1;
             ip2 = i2;
        }
    }

    private Entry parse(StringBuilder buf, boolean bitch) {
        byte[] ip1;
        byte[] ip2;
        int start1 = 0;
        int end1 = buf.length();
        if (end1 <= 0)
            return null;  // blank
        if (buf.charAt(end1 - 1) == '\r') {  // DataHelper.readLine leaves the \r on there
            buf.deleteCharAt(end1 - 1);
            end1--;
        }
        if (end1 <= 0)
            return null;  // blank
        int start2 = -1;
        int mask = -1;
        String comment = null;
        int index = buf.indexOf("#");
        if (index == 0)
            return null;  // comment
        index = buf.lastIndexOf(":");
        if (index >= 0) {
            comment = buf.substring(0, index);
            start1 = index + 1;
        }
        if (end1 - start1 == 44 && buf.substring(start1).indexOf(".") < 0) {
            byte b[] = Base64.decode(buf.substring(start1));
            if (b != null)
                return new Entry(comment, Hash.create(b), null, null);
        }
        index = buf.indexOf("-", start1);
        if (index >= 0) {
            end1 = index;
            start2 = index + 1;
        } else {
            index = buf.indexOf("/", start1);
            if (index >= 0) {
                end1 = index;
                mask = index + 1;
            }
        }
        if (end1 - start1 <= 0)
            return null;  // blank
        try {
            InetAddress pi = InetAddress.getByName(buf.substring(start1, end1));
            if (pi == null) return null;
            ip1 = pi.getAddress();
            if (ip1.length != 4)
                throw new UnknownHostException();
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
            if (bitch && _log.shouldLog(Log.ERROR))
                _log.error("Format error in the blocklist file: " + buf);
            return null;
        } catch (NumberFormatException nfe) {
            if (bitch && _log.shouldLog(Log.ERROR))
                _log.error("Format error in the blocklist file: " + buf);
            return null;
        } catch (IndexOutOfBoundsException ioobe) {
            if (bitch && _log.shouldLog(Log.ERROR))
                _log.error("Format error in the blocklist file: " + buf);
            return null;
        }
        return new Entry(comment, null, ip1, ip2);
    }

    /**
     * Read the file once just to see how many entries are in it,
     * so we can size our array.
     * This is i/o inefficient, but memory-efficient, which is what we want.
     */
    private int getSize(File BLFile) {
        if ( (!BLFile.exists()) || (BLFile.length() <= 0) ) return 0;
        int lines = 0;
        FileInputStream in = null;
        try {
            in = new FileInputStream(BLFile);
            StringBuilder buf = new StringBuilder(128);
            while (DataHelper.readLine(in, buf)) {
                lines++;
                buf.setLength(0);
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error reading the BLFile", ioe);
            return 0;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
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
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Combining entries " + toStr(blist[i]) + " and " + toStr(blist[next]));
                int nextTo = getTo(blist[next]);
                if (nextTo > to) // else entry next is totally inside entry i
                    store(getFrom(blist[i]), nextTo, i);
                blist[next] = Long.MAX_VALUE;  // to be removed with another sort
                lines++;
                removed++;
            }
            i += removed + 1;
        }
        return lines;
    }

    // Maintain a simple in-memory single-IP blocklist
    // This is used for new additions, NOT for the main list
    // of IP ranges read in from the file.
    public void add(String ip) {
        InetAddress pi;
        try {
            pi = InetAddress.getByName(ip);
        } catch (UnknownHostException uhe) {
            return;
        }
        if (pi == null) return;
        byte[] pib = pi.getAddress();
        add(pib);
    }

    public void add(byte ip[]) {
        if (ip.length != 4)
            return;
        if (add(toInt(ip)))
            if (_log.shouldLog(Log.WARN))
                _log.warn("Adding IP to blocklist: " + (ip[0]&0xff) + '.' + (ip[1]&0xff) + '.' + (ip[2]&0xff) + '.' + (ip[3]&0xff));
    }

    private boolean add(int ip) {
        return _singleIPBlocklist.add(Integer.valueOf(ip));
    }

    private boolean isOnSingleList(int ip) {
        return _singleIPBlocklist.contains(Integer.valueOf(ip));
    }

    /**
     * this tries to not return duplicates
     * but I suppose it could.
     */
    public List<byte[]> getAddresses(Hash peer) {
        List<byte[]> rv = new ArrayList(1);
        RouterInfo pinfo = _context.netDb().lookupRouterInfoLocally(peer);
        if (pinfo == null) return rv;
        Set<RouterAddress> paddr = pinfo.getAddresses();
        if (paddr == null || paddr.isEmpty())
            return rv;
        String oldphost = null;
        List<RouterAddress> pladdr = new ArrayList(paddr);
        // for each peer address
        for (int j = 0; j < paddr.size(); j++) {
            RouterAddress pa = (RouterAddress) pladdr.get(j);
            if (pa == null) continue;
            Properties pprops = pa.getOptions();
            if (pprops == null) continue;
            String phost = pprops.getProperty("host");
            if (phost == null) continue;
            if (oldphost != null && oldphost.equals(phost)) continue;
            oldphost = phost;
            InetAddress pi;
            try {
                pi = InetAddress.getByName(phost);
            } catch (UnknownHostException uhe) {
                continue;
            }
            if (pi == null) continue;
            byte[] pib = pi.getAddress();
            rv.add(pib);
         }
         return rv;
    }

    /**
     * Does the peer's IP address appear in the blocklist?
     * If so, and it isn't shitlisted, shitlist it forever...
     */
    public boolean isBlocklisted(Hash peer) {
        List<byte[]> ips = getAddresses(peer);
        for (Iterator<byte[]> iter = ips.iterator(); iter.hasNext(); ) {
            byte ip[] = iter.next();
            if (isBlocklisted(ip)) {
                if (! _context.shitlist().isShitlisted(peer))
                    // nice knowing you...
                    shitlist(peer);
                return true;
            }
        }
        return false;
    }

    // calling this externally won't shitlist the peer, this is just an IP check
    public boolean isBlocklisted(String ip) {
        InetAddress pi;
        try {
            pi = InetAddress.getByName(ip);
        } catch (UnknownHostException uhe) {
            return false;
        }
        if (pi == null) return false;
        byte[] pib = pi.getAddress();
        return isBlocklisted(pib);
    }

    // calling this externally won't shitlist the peer, this is just an IP check
    public boolean isBlocklisted(byte ip[]) {
        if (ip.length != 4)
            return false;
        return isBlocklisted(toInt(ip));
    }

    /**
     * First check the single-IP list.
     * Then do a
     * binary search through the in-memory range list which
     * is a sorted array of longs.
     * The array is sorted in signed order, but we don't care.
     * Each long is ((from << 32) | to)
     **/ 
    private boolean isBlocklisted(int ip) {
        if (isOnSingleList(ip))
            return true;
        int hi = _blocklistSize - 1;
        if (hi <= 0)
            return false;
        int lo = 0;
        int cur = hi / 2;

        while  (!match(ip, cur)) {
            if (isHigher(ip, cur))
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
        return match(ip, cur);
    }

    // Is the IP included in the entry _blocklist[cur] ?
    private boolean match(int ip, int cur) {
        return match(ip, _blocklist[cur]);
    }

    // Is the IP included in the compressed entry?
    private boolean match(int ip, long entry) {
        if (getFrom(entry) > ip)
                return false;
        return (ip <= getTo(entry));
    }

    // Is the IP higher than the entry _blocklist[cur] ?
    private boolean isHigher(int ip, int cur) {
        return ip > getFrom(_blocklist[cur]);
    }

    // methods to get and store the from/to values in the array

    private static int getFrom(long entry) {
        return (int) ((entry >> 32) & 0xffffffff);
    }

    private static int getTo(long entry) {
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

    private void store(byte ip1[], byte ip2[], int idx) {
        _blocklist[idx] = toEntry(ip1, ip2);
    }

    private void store(int ip1, int ip2, int idx) {
        long entry = ((long) ip1) << 32;
        entry |= ip2;
        _blocklist[idx] = entry;
    }

    private static int toInt(byte ip[]) {
        int rv = 0;
        for (int i = 0; i < 4; i++)
            rv |= (ip[i] & 0xff) << ((3-i)*8);
        return rv;
    }

    public static String toStr(byte[] ip) {
        return toStr(toInt(ip));
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

    private static String toStr(int ip) {
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
    public void shitlist(Hash peer) {
        // Temporary reason, until the job finishes
        _context.shitlist().shitlistRouterForever(peer, _x("IP banned"));
        if (! "true".equals( _context.getProperty(PROP_BLOCKLIST_DETAIL, "true")))
            return;
        boolean shouldRunJob;
        int number;
        synchronized (_inProcess) {
            number = _inProcess.size();
            shouldRunJob = _inProcess.add(peer);
        }
        if (!shouldRunJob)
            return;
        Job job = new ShitlistJob(peer);
        if (number > 0)
            job.getTiming().setStartAfter(_context.clock().now() + (number * 30*1000));
        _context.jobQueue().addJob(job);
    }

    private class ShitlistJob extends JobImpl {
        private Hash _peer;
        public ShitlistJob (Hash p) {
            super(_context);
            _peer = p;
        }
        public String getName() { return "Ban Peer by IP"; }
        public void runJob() {
            shitlistForever(_peer);
            synchronized (_inProcess) {
                _inProcess.remove(_peer);
            }
        }
    }

    /**
     * Look up the original record so we can record the reason in the shitlist.
     * That's the only reason to do this.
     * Only synchronize to cut down on the I/O load.
     * Additional jobs can wait.
     * Although could this clog up the job queue runners? Yes.
     * So we also stagger these jobs.
     *
     */
    private synchronized void shitlistForever(Hash peer) {
        String file = _context.getProperty(PROP_BLOCKLIST_FILE, BLOCKLIST_FILE_DEFAULT);
        File BLFile = new File(file);
        if (!BLFile.isAbsolute())
            BLFile = new File(_context.getConfigDir(), file);
        if (BLFile == null || (!BLFile.exists()) || BLFile.length() <= 0) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Blocklist file not found: " + file);
            return;
        }

        // look through the file for each address to find which one was the cause
        List ips = getAddresses(peer);
        for (Iterator<byte[]> iter = ips.iterator(); iter.hasNext(); ) {
            byte ip[] = iter.next();
            int ipint = toInt(ip);
            FileInputStream in = null;
            try {
                in = new FileInputStream(BLFile);
                StringBuilder buf = new StringBuilder(128);
                // assume the file is unsorted, so go through the whole thing
                while (DataHelper.readLine(in, buf)) {
                    Entry e = parse(buf, false);
                    if (e == null || e.peer != null) {
                        buf.setLength(0);
                        continue;
                    }
                    if (match(ipint, toEntry(e.ip1, e.ip2))) {
                        try { in.close(); } catch (IOException ioe) {}
                        String reason = _x("IP banned by blocklist.txt entry {0}");
                        // only one translate parameter for now
                        //for (int i = 0; i < 4; i++) {
                        //    reason = reason + (ip[i] & 0xff);
                        //    if (i != 3)
                        //        reason = reason + '.';
                        //}
                        //reason = reason + " banned by " + BLOCKLIST_FILE_DEFAULT + " entry \"" + buf + "\"";
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Shitlisting " + peer + " " + reason);
                        _context.shitlist().shitlistRouterForever(peer, reason, buf.toString());
                        return;
                    }
                    buf.setLength(0);
                }
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error reading the BLFile", ioe);
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
            }
        }
        // We already shitlisted in shitlist(peer), that's good enough
    }

    private static final int MAX_DISPLAY = 1000;

    /** write directly to the stream so we don't OOM on a huge list */
    public void renderStatusHTML(Writer out) throws IOException {
        // move to the jsp
        //out.write("<h2>Banned IPs</h2>");
        Set<Integer> singles = new TreeSet();
        singles.addAll(_singleIPBlocklist);
        if (!singles.isEmpty()) {
            out.write("<table><tr><td><b>Transient IPs</b></td></tr>");
            for (Iterator<Integer> iter = singles.iterator(); iter.hasNext(); ) {
                 int ip = iter.next().intValue();
                 out.write("<tr><td align=right>"); out.write(toStr(ip)); out.write("</td></tr>\n");
            }
            out.write("</table>");
        }
        if (_blocklistSize > 0) {
            out.write("<table><tr><th align=center colspan=2><b>IPs from Blocklist File</b></th></tr><tr><td align=center width=50%><b>From:</b></td><td align=center width=50%><b>To:</b></td></tr>");
            int max = Math.min(_blocklistSize, MAX_DISPLAY);
            for (int i = 0; i < max; i++) {
                 int from = getFrom(_blocklist[i]);
                 out.write("<tr><td align=center width=50%>"); out.write(toStr(from)); out.write("</td><td align=center width=50%>");
                 int to = getTo(_blocklist[i]);
                 if (to != from) {
                     out.write(toStr(to)); out.write("</td></tr>\n");
                 } else
                     out.write("&nbsp;</td></tr>\n");
            }
            if (_blocklistSize > MAX_DISPLAY)
                out.write("<tr><th colspan=2>First " + MAX_DISPLAY + " displayed, see the " +
                          BLOCKLIST_FILE_DEFAULT + " file for the full list</th></tr>");
            out.write("</table>");
        } else {
            out.write("<br><i>No blocklist file entries.</i>");
        }
        out.flush();
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

    public static void main(String args[]) {
        Blocklist b = new Blocklist();
        if ( (args != null) && (args.length == 1) )
            b.readBlocklistFile(args[0]);
        System.out.println("Saved " + b._blocklistSize + " records");
        String tests[] = {"0.0.0.0", "0.0.0.1", "0.0.0.2", "0.0.0.255", "1.0.0.0",
                                        "3.3.3.3", "77.1.2.3", "127.0.0.0", "127.127.127.127", "128.0.0.0",
                                        "129.1.2.3", "255.255.255.254", "255.255.255.255"};
        for (int i = 0; i < tests.length; i++) {
            System.out.println("Testing " + tests[i] + " returns " + b.isBlocklisted(tests[i]));
        }
    }
}
