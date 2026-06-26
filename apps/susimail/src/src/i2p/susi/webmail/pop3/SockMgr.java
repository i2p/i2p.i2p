package i2p.susi.webmail.pop3;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManager.DisconnectListener;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Base32;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Standalone only, bridge to I2PSocketManager.
 *
 * @since 0.9.70 adapted from I2PSnarkUtil
 */
public class SockMgr implements DisconnectListener {
    private final I2PAppContext _context;
    private final Log _log;
    
    private final Map<String, String> _opts;
    private volatile I2PSocketManager _manager;
    private volatile boolean _connecting;

    /**
     *
     */
    public SockMgr(I2PAppContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(SockMgr.class);
        _opts = new HashMap<String, String>();
    }
    
    /**
     * Connect to the router, if we aren't already
     */
    public synchronized boolean connect() {
        if (_manager == null) {
            _connecting = true;
            Properties opts = _context.getProperties();
            synchronized(_opts) {
                opts.putAll(_opts);
            }
            String sin = opts.getProperty("inbound.quantity");
            if (sin == null)
                opts.setProperty("inbound.quantity", "2");
            String sout = opts.getProperty("outbound.quantity");
            if (sout == null)
                opts.setProperty("outbound.quantity", "2");
            if (opts.containsKey("inbound.backupQuantity"))
                opts.setProperty("inbound.backupQuantity", "0");
            if (opts.containsKey("outbound.backupQuantity"))
                opts.setProperty("outbound.backupQuantity", "0");

            if (opts.getProperty("inbound.nickname") == null)
                opts.setProperty("inbound.nickname", "SusiMail");
            if (opts.getProperty("outbound.nickname") == null)
                opts.setProperty("outbound.nickname", "SusiMail");
            if (opts.getProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT) == null)
                opts.setProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT, "75000");
            if (opts.getProperty("i2p.streaming.inactivityTimeout") == null)
                opts.setProperty("i2p.streaming.inactivityTimeout", "240000");
            if (opts.getProperty("i2p.streaming.inactivityAction") == null)
                opts.setProperty("i2p.streaming.inactivityAction", "1"); // 1 == disconnect, 2 == ping
            if (opts.getProperty("i2p.streaming.initialWindowSize") == null)
                opts.setProperty("i2p.streaming.initialWindowSize", "1");
            if (opts.getProperty("i2p.streaming.slowStartGrowthRateFactor") == null)
                opts.setProperty("i2p.streaming.slowStartGrowthRateFactor", "1");
            if (opts.getProperty(I2PClient.PROP_SIGTYPE) == null)
                opts.setProperty(I2PClient.PROP_SIGTYPE, "EdDSA_SHA512_Ed25519");
            if (opts.getProperty("i2cp.leaseSetEncType") == null)
                opts.setProperty("i2cp.leaseSetEncType", "6,4");
            if (opts.getProperty("i2cp.closeOnIdle") == null)
                opts.setProperty("i2cp.closeOnIdle", "true");
            if (opts.getProperty("i2cp.closeIdleTime") == null)
                opts.setProperty("i2cp.closeIdleTime", "1500000");
            if (opts.getProperty("i2cp.reduceOnIdle") == null)
                opts.setProperty("i2cp.reduceOnIdle", "true");
            if (opts.getProperty("i2cp.reduceIdleTime") == null)
                opts.setProperty("i2cp.reduceIdleTime", "900000");
            if (opts.getProperty("i2cp.reduceQuantity") == null)
                opts.setProperty("i2cp.reduceQuantity", "1");
            opts.setProperty("i2cp.dontPublishLeaseSet", "true");


            String i2cpHost =  opts.getProperty("i2cp.tcp.host");
            if (i2cpHost == null)
                i2cpHost = "127.0.0.1";
            int i2cpPort = 7654;
            String port = opts.getProperty("i2cp.tcp.port");
            if (port != null) {
                try {
                    i2cpPort = Integer.parseInt(port);
                } catch (NumberFormatException nfe) {}
            }
            _manager = I2PSocketManagerFactory.createManager(i2cpHost, i2cpPort, opts);
            if (_manager != null)
                _manager.addDisconnectListener(this);
            _connecting = false;
        }
        return (_manager != null);
    }
    
    /**
     * DisconnectListener interface
     */
    public synchronized void sessionDisconnected() {
        _manager = null;
        _connecting = false;
    }

    public boolean connected() { return _manager != null; }

    public boolean isConnecting() { return _manager == null && _connecting; }

    /**
     *  @return null if not connected
     */
    public I2PSocketManager getSocketManager() {
        return _manager;
    }

    /**
     * Destroy the destination itself
     */
    public synchronized void disconnect() {
        I2PSocketManager mgr = _manager;
        _manager = null;
        if (mgr != null)
            mgr.destroySocketManager();
    }
    
    /** connect to the given destination */
    public Socket connect(Destination peer, int port) throws IOException {
        I2PSocketManager mgr = _manager;
        if (mgr == null)
            throw new IOException("No socket manager");
        // no method for connectToSocket with options or port
        //I2PSocketOptions opts = _manager.buildOptions();
        //opts.setPort(port);
        Socket rv = mgr.connectToSocket(peer);
        return rv;
    }
    
    private static final int BASE32_HASH_LENGTH = 52;   // 1 + Hash.HASH_LENGTH * 8 / 5

    /** Base64 Hash or Hash.i2p or name.i2p using naming service */
    public Destination getDestination(String ip) {
        if (ip == null) return null;
        if (ip.endsWith(".i2p")) {
            if (ip.length() < 520) {   // key + ".i2p"
                if (_manager != null && ip.length() == BASE32_HASH_LENGTH + 8 && ip.endsWith(".b32.i2p")) {
                    // Use existing I2PSession for b32 lookups if we have it
                    // This is much more efficient than using the naming service
                    I2PSession sess = _manager.getSession();
                    if (sess != null) {
                        byte[] b = Base32.decode(ip.substring(0, BASE32_HASH_LENGTH));
                        if (b != null) {
                            //Hash h = new Hash(b);
                            Hash h = Hash.create(b);
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Using existing session for lookup of " + ip);
                            try {
                                return sess.lookupDest(h, 15*1000);
                            } catch (I2PSessionException ise) {}
                        }
                    }
                } else if (_manager != null) {
                    I2PSession sess = _manager.getSession();
                    if (sess != null) {
                        try {
                            return sess.lookupDest(ip);
                        } catch (I2PSessionException ise) {}
                    }
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info("Using naming service for lookup of " + ip);
                return _context.namingService().lookup(ip);
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("Creating Destination for " + ip);
            try {
                return new Destination(ip.substring(0, ip.length()-4)); // sans .i2p
            } catch (DataFormatException dfe) {
                return null;
            }
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Creating Destination for " + ip);
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
}
