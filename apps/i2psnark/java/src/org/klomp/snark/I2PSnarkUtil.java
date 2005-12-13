package org.klomp.snark;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.util.EepGet;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.util.Log;

import java.io.*;
import java.util.Properties;

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
    private Properties _opts;
    private I2PSocketManager _manager;
    
    private I2PSnarkUtil() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(Snark.class);
        setProxy("127.0.0.1", 4444);
        setI2CPConfig("127.0.0.1", 7654, null);
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
    }
    
    public void setI2CPConfig(String i2cpHost, int i2cpPort, Properties opts) {
        _i2cpHost = i2cpHost;
        _i2cpPort = i2cpPort;
        if (opts != null)
            _opts = opts;
    }
    
    /**
     * Connect to the router, if we aren't already
     */
    boolean connect() {
        if (_manager == null) {
            _manager = I2PSocketManagerFactory.createManager(_i2cpHost, _i2cpPort, _opts);
        }
        return (_manager != null);
    }
    
    /** connect to the given destination */
    I2PSocket connect(PeerID peer) throws IOException {
        try {
            return _manager.connect(peer.getAddress());
        } catch (I2PException ie) {
            throw new IOException("Unable to reach the peer " + peer + ": " + ie.getMessage());
        }
    }
    
    /**
     * fetch the given URL, returning the file it is stored in, or null on error
     */
    File get(String url) {
        _log.debug("Fetching [" + url + "] proxy=" + _proxyHost + ":" + _proxyPort + ": " + _shouldProxy);
        File out = null;
        try {
            out = File.createTempFile("i2psnark", "url");
        } catch (IOException ioe) {
            ioe.printStackTrace();
            out.delete();
            return null;
        }
        String fetchURL = rewriteAnnounce(url);
        _log.debug("Rewritten url [" + fetchURL + "]");
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
    
    I2PServerSocket getServerSocket() { 
        return _manager.getServerSocket();
    }
    
    String getOurIPString() {
        return _manager.getSession().getMyDestination().toBase64();
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
        _log.debug("Rewriting [" + origAnnounce + "] as [" + rv + "]");
        return rv;
    }
    
    /** hook between snark's logger and an i2p log */
    void debug(String msg, int snarkDebugLevel, Throwable t) {
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
