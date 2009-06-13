package org.klomp.snark;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * I2P specific helpers for I2PSnark
 * We use this class as a sort of context for i2psnark
 * so we can run multiple instances of single Snarks
 * (but not multiple SnarkManagers, it is still static)
 */
public class I2PSnarkUtil {
    private I2PAppContext _context;
    private Log _log;
    
    private boolean _shouldProxy;
    private String _proxyHost;
    private int _proxyPort;
    private String _i2cpHost;
    private int _i2cpPort;
    private Map _opts;
    private I2PSocketManager _manager;
    private boolean _configured;
    private Set _shitlist;
    private int _maxUploaders;
    private int _maxUpBW;
    private int _maxConnections;
    private File _tmpDir;
    
    public static final String PROP_USE_OPENTRACKERS = "i2psnark.useOpentrackers";
    public static final boolean DEFAULT_USE_OPENTRACKERS = true;
    public static final String PROP_OPENTRACKERS = "i2psnark.opentrackers";
    public static final String DEFAULT_OPENTRACKERS = "http://tracker.welterde.i2p/a";
    public static final int DEFAULT_MAX_UP_BW = 8;  //KBps
    public static final int MAX_CONNECTIONS = 16; // per torrent

    public I2PSnarkUtil(I2PAppContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(Snark.class);
        _opts = new HashMap();
        setProxy("127.0.0.1", 4444);
        setI2CPConfig("127.0.0.1", 7654, null);
        _shitlist = new HashSet(64);
        _configured = false;
        _maxUploaders = Snark.MAX_TOTAL_UPLOADERS;
        _maxUpBW = DEFAULT_MAX_UP_BW;
        _maxConnections = MAX_CONNECTIONS;
        // This is used for both announce replies and .torrent file downloads,
        // so it must be available even if not connected to I2CP.
        // so much for multiple instances
        _tmpDir = new File(ctx.getTempDir(), "i2psnark");
        FileUtil.rmdir(_tmpDir, false);
        _tmpDir.mkdirs();
    }
    
    /**
     * Specify what HTTP proxy tracker requests should go through (specify a null
     * host for no proxying)
     *
     */
    public void setProxy(String host, int port) {
        if ( (host != null) && (port > 0) ) {
            _shouldProxy = true;
            _proxyHost = host;
            _proxyPort = port;
        } else {
            _shouldProxy = false;
            _proxyHost = null;
            _proxyPort = -1;
        }
        _configured = true;
    }
    
    public boolean configured() { return _configured; }
    
    public void setI2CPConfig(String i2cpHost, int i2cpPort, Map opts) {
        if (i2cpHost != null)
            _i2cpHost = i2cpHost;
        if (i2cpPort > 0)
            _i2cpPort = i2cpPort;
        // can't remove any options this way...
        if (opts != null)
            _opts.putAll(opts);
        _configured = true;
    }
    
    public void setMaxUploaders(int limit) {
        _maxUploaders = limit;
        _configured = true;
    }
    
    public void setMaxUpBW(int limit) {
        _maxUpBW = limit;
        _configured = true;
    }
    
    public void setMaxConnections(int limit) {
        _maxConnections = limit;
        _configured = true;
    }
    
    public String getI2CPHost() { return _i2cpHost; }
    public int getI2CPPort() { return _i2cpPort; }
    public Map getI2CPOptions() { return _opts; }
    public String getEepProxyHost() { return _proxyHost; }
    public int getEepProxyPort() { return _proxyPort; }
    public boolean getEepProxySet() { return _shouldProxy; }
    public int getMaxUploaders() { return _maxUploaders; }
    public int getMaxUpBW() { return _maxUpBW; }
    public int getMaxConnections() { return _maxConnections; }
    
    /**
     * Connect to the router, if we aren't already
     */
    synchronized public boolean connect() {
        if (_manager == null) {
            Properties opts = new Properties();
            if (_opts != null) {
                for (Iterator iter = _opts.keySet().iterator(); iter.hasNext(); ) {
                    String key = (String)iter.next();
                    opts.setProperty(key, _opts.get(key).toString());
                }
            }
            if (opts.getProperty("inbound.nickname") == null)
                opts.setProperty("inbound.nickname", "I2PSnark");
            if (opts.getProperty("outbound.nickname") == null)
                opts.setProperty("outbound.nickname", "I2PSnark");
            if (opts.getProperty("i2p.streaming.inactivityTimeout") == null)
                opts.setProperty("i2p.streaming.inactivityTimeout", "240000");
            if (opts.getProperty("i2p.streaming.inactivityAction") == null)
                opts.setProperty("i2p.streaming.inactivityAction", "1"); // 1 == disconnect, 2 == ping
            if (opts.getProperty("i2p.streaming.initialWindowSize") == null)
                opts.setProperty("i2p.streaming.initialWindowSize", "1");
            if (opts.getProperty("i2p.streaming.slowStartGrowthRateFactor") == null)
                opts.setProperty("i2p.streaming.slowStartGrowthRateFactor", "1");
            //if (opts.getProperty("i2p.streaming.writeTimeout") == null)
            //    opts.setProperty("i2p.streaming.writeTimeout", "90000");
            //if (opts.getProperty("i2p.streaming.readTimeout") == null)
            //    opts.setProperty("i2p.streaming.readTimeout", "120000");
            _manager = I2PSocketManagerFactory.createManager(_i2cpHost, _i2cpPort, opts);
        }
        return (_manager != null);
    }
    
    public boolean connected() { return _manager != null; }
    /**
     * Destroy the destination itself
     */
    public void disconnect() {
        I2PSocketManager mgr = _manager;
        _manager = null;
        _shitlist.clear();
        mgr.destroySocketManager();
        // this will delete a .torrent file d/l in progress so don't do that...
        FileUtil.rmdir(_tmpDir, false);
        // in case the user will d/l a .torrent file next...
        _tmpDir.mkdirs();
    }
    
    /** connect to the given destination */
    I2PSocket connect(PeerID peer) throws IOException {
        Hash dest = peer.getAddress().calculateHash();
        synchronized (_shitlist) {
            if (_shitlist.contains(dest))
                throw new IOException("Not trying to contact " + dest.toBase64() + ", as they are shitlisted");
        }
        try {
            I2PSocket rv = _manager.connect(peer.getAddress());
            if (rv != null) synchronized (_shitlist) { _shitlist.remove(dest); }
            return rv;
        } catch (I2PException ie) {
            synchronized (_shitlist) {
                _shitlist.add(dest);
            }
            SimpleScheduler.getInstance().addEvent(new Unshitlist(dest), 10*60*1000);
            throw new IOException("Unable to reach the peer " + peer + ": " + ie.getMessage());
        }
    }
    
    private class Unshitlist implements SimpleTimer.TimedEvent {
        private Hash _dest;
        public Unshitlist(Hash dest) { _dest = dest; }
        public void timeReached() { synchronized (_shitlist) { _shitlist.remove(_dest); } }
    }
    
    /**
     * fetch the given URL, returning the file it is stored in, or null on error
     */
    public File get(String url) { return get(url, true, 0); }
    public File get(String url, boolean rewrite) { return get(url, rewrite, 0); }
    public File get(String url, int retries) { return get(url, true, retries); }
    public File get(String url, boolean rewrite, int retries) {
        _log.debug("Fetching [" + url + "] proxy=" + _proxyHost + ":" + _proxyPort + ": " + _shouldProxy);
        File out = null;
        try {
            // we could use the system tmp dir but deleteOnExit() doesn't seem to work on all platforms...
            out = File.createTempFile("i2psnark", null, _tmpDir);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            if (out != null)
                out.delete();
            return null;
        }
        out.deleteOnExit();
        String fetchURL = url;
        if (rewrite)
            fetchURL = rewriteAnnounce(url);
        //_log.debug("Rewritten url [" + fetchURL + "]");
        EepGet get = new EepGet(_context, _shouldProxy, _proxyHost, _proxyPort, retries, out.getAbsolutePath(), fetchURL);
        if (get.fetch()) {
            _log.debug("Fetch successful [" + url + "]: size=" + out.length());
            return out;
        } else {
            _log.warn("Fetch failed [" + url + "]");
            out.delete();
            return null;
        }
    }
    
    public I2PServerSocket getServerSocket() { 
        I2PSocketManager mgr = _manager;
        if (mgr != null)
            return mgr.getServerSocket();
        else
            return null;
    }
    
    String getOurIPString() {
        if (_manager == null)
            return "unknown";
        I2PSession sess = _manager.getSession();
        if (sess != null) {
            Destination dest = sess.getMyDestination();
            if (dest != null)
                return dest.toBase64();
        }
        return "unknown";
    }

    /** Base64 only - static (no naming service) */
    static Destination getDestinationFromBase64(String ip) {
        if (ip == null) return null;
        if (ip.endsWith(".i2p")) {
            if (ip.length() < 520)
                    return null;
            try {
                return new Destination(ip.substring(0, ip.length()-4)); // sans .i2p
            } catch (DataFormatException dfe) {
                return null;
            }
        } else {
            try {
                return new Destination(ip);
            } catch (DataFormatException dfe) {
                return null;
            }
        }
    }

    /** Base64 Hash or Hash.i2p or name.i2p using naming service */
    Destination getDestination(String ip) {
        if (ip == null) return null;
        if (ip.endsWith(".i2p")) {
            if (ip.length() < 520) {   // key + ".i2p"
                Destination dest = _context.namingService().lookup(ip);
                if (dest != null)
                    return dest;
            }
            try {
                return new Destination(ip.substring(0, ip.length()-4)); // sans .i2p
            } catch (DataFormatException dfe) {
                return null;
            }
        } else {
            try {
                return new Destination(ip);
            } catch (DataFormatException dfe) {
                return null;
            }
        }
    }

    public String lookup(String name) {
        Destination dest = getDestination(name);
	if (dest == null)
            return null;
        return dest.toBase64();
    }

    /**
     * Given http://KEY.i2p/foo/announce turn it into http://i2p/KEY/foo/announce
     * Given http://tracker.blah.i2p/foo/announce leave it alone
     */
    String rewriteAnnounce(String origAnnounce) {
        int destStart = "http://".length();
        int destEnd = origAnnounce.indexOf(".i2p");
        if (destEnd < destStart + 516)
            return origAnnounce;
        int pathStart = origAnnounce.indexOf('/', destEnd);
        String rv = "http://i2p/" + origAnnounce.substring(destStart, destEnd) + origAnnounce.substring(pathStart);
        //_log.debug("Rewriting [" + origAnnounce + "] as [" + rv + "]");
        return rv;
    }
    
    public String getOpenTrackerString() { 
        String rv = (String) _opts.get(PROP_OPENTRACKERS);
        if (rv == null)
            return DEFAULT_OPENTRACKERS;
        return rv;
    }

    /** comma delimited list open trackers to use as backups */
    /** sorted map of name to announceURL=baseURL */
    public List getOpenTrackers() { 
        if (!shouldUseOpenTrackers())
            return null;
        List rv = new ArrayList(1);
        String trackers = getOpenTrackerString();
        StringTokenizer tok = new StringTokenizer(trackers, ", ");
        while (tok.hasMoreTokens())
            rv.add(tok.nextToken());
        
        if (rv.size() <= 0)
            return null;
        return rv;
    }
    
    public boolean shouldUseOpenTrackers() {
        String rv = (String) _opts.get(PROP_USE_OPENTRACKERS);
        if (rv == null)
            return DEFAULT_USE_OPENTRACKERS;
        return Boolean.valueOf(rv).booleanValue();
    }

    /** hook between snark's logger and an i2p log */
    void debug(String msg, int snarkDebugLevel) {
        debug(msg, snarkDebugLevel, null);
    }
    void debug(String msg, int snarkDebugLevel, Throwable t) {
        if (t instanceof OutOfMemoryError) {
            try { Thread.sleep(100); } catch (InterruptedException ie) {}
            try {
                t.printStackTrace();
            } catch (Throwable tt) {}
            try {
                System.out.println("OOM thread: " + Thread.currentThread().getName());
            } catch (Throwable tt) {}
        }
        switch (snarkDebugLevel) {
            case 0:
            case 1:
                _log.error(msg, t);
                break;
            case 2:
                _log.warn(msg, t);
                break;
            case 3:
            case 4:
                _log.info(msg, t);
                break;
            case 5:
            case 6:
            default:
                _log.debug(msg, t);
                break;
        }
    }
}
