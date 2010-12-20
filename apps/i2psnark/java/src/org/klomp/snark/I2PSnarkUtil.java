package org.klomp.snark;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.i2p.client.streaming.I2PSocketEepGet;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFile;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;
import net.i2p.util.Translate;

import org.klomp.snark.dht.KRPC;

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
    private Map<String, String> _opts;
    private I2PSocketManager _manager;
    private boolean _configured;
    private final Set<Hash> _shitlist;
    private int _maxUploaders;
    private int _maxUpBW;
    private int _maxConnections;
    private File _tmpDir;
    private int _startupDelay;
    private KRPC _krpc;

    public static final int DEFAULT_STARTUP_DELAY = 3;
    public static final String PROP_USE_OPENTRACKERS = "i2psnark.useOpentrackers";
    public static final boolean DEFAULT_USE_OPENTRACKERS = true;
    public static final String PROP_OPENTRACKERS = "i2psnark.opentrackers";
    public static final String DEFAULT_OPENTRACKERS = "http://tracker.welterde.i2p/a";
    public static final int DEFAULT_MAX_UP_BW = 8;  //KBps
    public static final int MAX_CONNECTIONS = 16; // per torrent
    private static final boolean ENABLE_DHT = true;

    public I2PSnarkUtil(I2PAppContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(Snark.class);
        _opts = new HashMap();
        //setProxy("127.0.0.1", 4444);
        setI2CPConfig("127.0.0.1", 7654, null);
        _shitlist = new ConcurrentHashSet();
        _configured = false;
        _maxUploaders = Snark.MAX_TOTAL_UPLOADERS;
        _maxUpBW = DEFAULT_MAX_UP_BW;
        _maxConnections = MAX_CONNECTIONS;
        _startupDelay = DEFAULT_STARTUP_DELAY;
        // This is used for both announce replies and .torrent file downloads,
        // so it must be available even if not connected to I2CP.
        // so much for multiple instances
        _tmpDir = new SecureDirectory(ctx.getTempDir(), "i2psnark");
        FileUtil.rmdir(_tmpDir, false);
        _tmpDir.mkdirs();
    }
    
    /**
     * Specify what HTTP proxy tracker requests should go through (specify a null
     * host for no proxying)
     *
     */
/*****
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
******/
    
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

    public void setStartupDelay(int minutes) {
	_startupDelay = minutes;
	_configured = true;
    }
    
    public String getI2CPHost() { return _i2cpHost; }
    public int getI2CPPort() { return _i2cpPort; }
    public Map<String, String> getI2CPOptions() { return _opts; }
    public String getEepProxyHost() { return _proxyHost; }
    public int getEepProxyPort() { return _proxyPort; }
    public boolean getEepProxySet() { return _shouldProxy; }
    public int getMaxUploaders() { return _maxUploaders; }
    public int getMaxUpBW() { return _maxUpBW; }
    public int getMaxConnections() { return _maxConnections; }
    public int getStartupDelay() { return _startupDelay; }  
  
    /**
     * Connect to the router, if we aren't already
     */
    synchronized public boolean connect() {
        if (_manager == null) {
            // try to find why reconnecting after stop
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Connecting to I2P", new Exception("I did it"));
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
            // Dont do this for now, it is set in I2PSocketEepGet for announces,
            // we don't need fast handshake for peer connections.
            //if (opts.getProperty("i2p.streaming.connectDelay") == null)
            //    opts.setProperty("i2p.streaming.connectDelay", "500");
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
        // FIXME this only instantiates krpc once, left stuck with old manager
        if (ENABLE_DHT && _manager != null && _krpc == null)
            _krpc = new KRPC(_context, _manager.getSession());
        return (_manager != null);
    }
    
    /**
     * @return null if disabled or not started
     * @since 0.8.4
     */
    public KRPC getDHT() { return _krpc; }

    public boolean connected() { return _manager != null; }

    /**
     * Destroy the destination itself
     */
    public void disconnect() {
        I2PSocketManager mgr = _manager;
        // FIXME this can cause race NPEs elsewhere
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
        I2PSocketManager mgr = _manager;
        if (mgr == null)
            throw new IOException("No socket manager");
        Destination addr = peer.getAddress();
        if (addr == null)
            throw new IOException("Null address");
        if (addr.equals(getMyDestination()))
            throw new IOException("Attempt to connect to myself");
        Hash dest = addr.calculateHash();
        if (_shitlist.contains(dest))
            throw new IOException("Not trying to contact " + dest.toBase64() + ", as they are shitlisted");
        try {
            I2PSocket rv = _manager.connect(addr);
            if (rv != null)
                _shitlist.remove(dest);
            return rv;
        } catch (I2PException ie) {
            _shitlist.add(dest);
            SimpleScheduler.getInstance().addEvent(new Unshitlist(dest), 10*60*1000);
            throw new IOException("Unable to reach the peer " + peer + ": " + ie.getMessage());
        }
    }
    
    private class Unshitlist implements SimpleTimer.TimedEvent {
        private Hash _dest;
        public Unshitlist(Hash dest) { _dest = dest; }
        public void timeReached() { _shitlist.remove(_dest); }
    }
    
    /**
     * fetch the given URL, returning the file it is stored in, or null on error
     */
    public File get(String url) { return get(url, true, 0); }
    public File get(String url, boolean rewrite) { return get(url, rewrite, 0); }
    public File get(String url, int retries) { return get(url, true, retries); }
    public File get(String url, boolean rewrite, int retries) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fetching [" + url + "] proxy=" + _proxyHost + ":" + _proxyPort + ": " + _shouldProxy);
        File out = null;
        try {
            // we could use the system tmp dir but deleteOnExit() doesn't seem to work on all platforms...
            out = SecureFile.createTempFile("i2psnark", null, _tmpDir);
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
        //EepGet get = new EepGet(_context, _shouldProxy, _proxyHost, _proxyPort, retries, out.getAbsolutePath(), fetchURL);
        // Use our tunnel for announces and .torrent fetches too! Make sure we're connected first...
        if (!connected()) {
            if (!connect())
                return null;
        }
        EepGet get = new I2PSocketEepGet(_context, _manager, retries, out.getAbsolutePath(), fetchURL);
        if (get.fetch()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Fetch successful [" + url + "]: size=" + out.length());
            return out;
        } else {
            if (_log.shouldLog(Log.WARN))
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
        Destination dest = getMyDestination();
        if (dest != null)
            return dest.toBase64();
        return "unknown";
    }

    /**
     *  @return dest or null
     *  @since 0.8.4
     */
    Destination getMyDestination() {
        if (_manager == null)
            return null;
        I2PSession sess = _manager.getSession();
        if (sess != null)
            return sess.getMyDestination();
        return null;
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
    
    /** @param ot non-null */
    public void setOpenTrackerString(String ot) { 
        _opts.put(PROP_OPENTRACKERS, ot);
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
        
        if (rv.isEmpty())
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

    private static final String BUNDLE_NAME = "org.klomp.snark.web.messages";

    /** lang in routerconsole.lang property, else current locale */
    public String getString(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than getString(s, ctx), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To tranlslate parameter also, use _("foo {0} bar", _("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    public String getString(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }

    /** {0} and {1} */
    public String getString(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, _context, BUNDLE_NAME);
    }

    /** ngettext @since 0.7.14 */
    public String getString(int n, String s, String p) {
        return Translate.getString(n, s, p, _context, BUNDLE_NAME);
    }
}
