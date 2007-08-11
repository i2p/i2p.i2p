package org.klomp.snark;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.util.EepGet;
import net.i2p.client.I2PSession;
import net.i2p.data.*;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

import java.io.*;
import java.util.*;

/**
 * I2P specific helpers for I2PSnark
 */
public class I2PSnarkUtil {
    private I2PAppContext _context;
    private Log _log;
    private static I2PSnarkUtil _instance = new I2PSnarkUtil();
    public static I2PSnarkUtil instance() { return _instance; }
    
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
    
    private I2PSnarkUtil() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(Snark.class);
        _opts = new HashMap();
        setProxy("127.0.0.1", 4444);
        setI2CPConfig("127.0.0.1", 7654, null);
        _shitlist = new HashSet(64);
        _configured = false;
        _maxUploaders = Snark.MAX_TOTAL_UPLOADERS;
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
        _i2cpHost = i2cpHost;
        _i2cpPort = i2cpPort;
        if (opts != null)
            _opts.putAll(opts);
        _configured = true;
    }
    
    public void setMaxUploaders(int limit) {
        _maxUploaders = limit;
        _configured = true;
    }
    
    public String getI2CPHost() { return _i2cpHost; }
    public int getI2CPPort() { return _i2cpPort; }
    public Map getI2CPOptions() { return _opts; }
    public String getEepProxyHost() { return _proxyHost; }
    public int getEepProxyPort() { return _proxyPort; }
    public boolean getEepProxySet() { return _shouldProxy; }
    public int getMaxUploaders() { return _maxUploaders; }
    
    /**
     * Connect to the router, if we aren't already
     */
    public boolean connect() {
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
                opts.setProperty("i2p.streaming.inactivityTimeout", "90000");
            if (opts.getProperty("i2p.streaming.inactivityAction") == null)
                opts.setProperty("i2p.streaming.inactivityAction", "2"); // 1 == disconnect, 2 == ping
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
            SimpleTimer.getInstance().addEvent(new Unshitlist(dest), 10*60*1000);
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
    public File get(String url) { return get(url, true); }
    public File get(String url, boolean rewrite) {
        _log.debug("Fetching [" + url + "] proxy=" + _proxyHost + ":" + _proxyPort + ": " + _shouldProxy);
        File out = null;
        try {
            out = File.createTempFile("i2psnark", "url", new File("."));
        } catch (IOException ioe) {
            ioe.printStackTrace();
            out.delete();
            return null;
        }
        String fetchURL = url;
        if (rewrite)
            fetchURL = rewriteAnnounce(url);
        //_log.debug("Rewritten url [" + fetchURL + "]");
        EepGet get = new EepGet(_context, _shouldProxy, _proxyHost, _proxyPort, 1, out.getAbsolutePath(), fetchURL);
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
        I2PSession sess = _manager.getSession();
        if (sess != null) {
            Destination dest = sess.getMyDestination();
            if (dest != null)
                return dest.toBase64();
        }
        return "unknown";
    }
    Destination getDestination(String ip) {
        if (ip == null) return null;
        if (ip.endsWith(".i2p")) {
            Destination dest = _context.namingService().lookup(ip);
            if (dest != null) {
                return dest;
            } else {
                try {
                    return new Destination(ip.substring(0, ip.length()-4)); // sans .i2p
                } catch (DataFormatException dfe) {
                    return null;
                }
            }
        } else {
            try {
                return new Destination(ip);
            } catch (DataFormatException dfe) {
                return null;
            }
        }
    }

    /**
     * Given http://blah.i2p/foo/announce turn it into http://i2p/blah/foo/announce
     */
    String rewriteAnnounce(String origAnnounce) {
        int destStart = "http://".length();
        int destEnd = origAnnounce.indexOf(".i2p");
        int pathStart = origAnnounce.indexOf('/', destEnd);
        String rv = "http://i2p/" + origAnnounce.substring(destStart, destEnd) + origAnnounce.substring(pathStart);
        //_log.debug("Rewriting [" + origAnnounce + "] as [" + rv + "]");
        return rv;
    }
    
    /** hook between snark's logger and an i2p log */
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
