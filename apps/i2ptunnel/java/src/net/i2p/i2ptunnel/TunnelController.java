package net.i2p.i2ptunnel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.crypto.SigType;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.socks.I2PSOCKSTunnel;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;

/**
 * Coordinate the runtime operation and configuration of a single I2PTunnel.
 * An I2PTunnel tracks one or more I2PTunnelTasks and one or more I2PSessions.
 * Usually one of each.
 *
 * These objects are bundled together under a TunnelControllerGroup where the
 * entire group is stored / loaded from a single config file.
 *
 * This is the class used by several plugins to create tunnels, so
 * take care to maintain the public methods as a stable API.
 */
public class TunnelController implements Logging {
    private final Log _log;
    private Properties _config;
    private final I2PTunnel _tunnel;
    private final List<String> _messages;
    private List<I2PSession> _sessions;
    private boolean _running;
    private boolean _starting;
    
    public static final String KEY_BACKUP_DIR = "i2ptunnel-keyBackup";

    /** all of these @since 0.9.14 */
    public static final String PROP_DESCR = "description";
    public static final String PROP_DEST = "targetDestination";
    public static final String PROP_I2CP_HOST = "i2cpHost";
    public static final String PROP_I2CP_PORT = "i2cpPort";
    public static final String PROP_INTFC = "interface";
    public static final String PROP_FILE = "privKeyFile";
    public static final String PROP_LISTEN_PORT = "listenPort";
    public static final String PROP_NAME = "name";
    public static final String PROP_PROXIES = "proxyList";
    public static final String PROP_SHARED = "sharedClient";
    public static final String PROP_SPOOFED_HOST = "spoofedHost";
    public static final String PROP_START = "startOnLoad";
    public static final String PROP_TARGET_HOST = "targetHost";
    public static final String PROP_TARGET_PORT = "targetPort";
    public static final String PROP_TYPE = "type";

    /** @since 0.9.14 */
    public static final String PFX_OPTION = "option.";

    private static final String OPT_PERSISTENT = PFX_OPTION + "persistentClientKey";
    private static final String OPT_BUNDLE_REPLY = PFX_OPTION + "shouldBundleReplyInfo";
    private static final String OPT_TAGS_SEND = PFX_OPTION + "crypto.tagsToSend";
    private static final String OPT_LOW_TAGS = PFX_OPTION + "crypto.lowTagThreshold";
    private static final String OPT_SIG_TYPE = PFX_OPTION + I2PClient.PROP_SIGTYPE;

    /** all of these @since 0.9.14 */
    public static final String TYPE_CONNECT = "connectclient";
    public static final String TYPE_HTTP_BIDIR_SERVER = "httpbidirserver";
    public static final String TYPE_HTTP_CLIENT = "httpclient";
    public static final String TYPE_HTTP_SERVER = "httpserver";
    public static final String TYPE_IRC_CLIENT = "ircclient";
    public static final String TYPE_IRC_SERVER = "ircserver";
    public static final String TYPE_SOCKS = "sockstunnel";
    public static final String TYPE_SOCKS_IRC = "socksirctunnel";
    public static final String TYPE_STD_CLIENT = "client";
    public static final String TYPE_STD_SERVER = "server";
    public static final String TYPE_STREAMR_CLIENT = "streamrclient";
    public static final String TYPE_STREAMR_SERVER = "streamrserver";

    /**
     * Create a new controller for a tunnel out of the specific config options.
     * The config may contain a large number of options - only ones that begin in
     * the prefix should be used (and, in turn, that prefix should be stripped off
     * before being interpreted by this controller)
     * 
     * @param config original key=value mapping non-null
     * @param prefix beginning of key values that are relevant to this tunnel
     */
    public TunnelController(Properties config, String prefix) {
        this(config, prefix, true);
    }

    /**
     * 
     * @param config original key=value mapping non-null
     * @param prefix beginning of key values that are relevant to this tunnel
     * @param createKey for servers, whether we want to create a brand new destination
     *                  with private keys at the location specified or not (does not
     *                  overwrite existing ones)
     */
    public TunnelController(Properties config, String prefix, boolean createKey) {
        _tunnel = new I2PTunnel();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(TunnelController.class);
        setConfig(config, prefix);
        _messages = new ArrayList<String>(4);
        _running = false;
        boolean keyOK = true;
        if (createKey && (getType().endsWith("server") || getPersistentClientKey()))
            keyOK = createPrivateKey();
        _starting = keyOK && getStartOnLoad();
    }
    
    /**
     * @return success
     */
    private boolean createPrivateKey() {
        I2PClient client = I2PClientFactory.createClient();
        String filename = getPrivKeyFile();
        if ( (filename == null) || (filename.trim().length() <= 0) ) {
            log("No filename specified for the private key");
            return false;
        }
        
        File keyFile = new File(getPrivKeyFile());
        if (!keyFile.isAbsolute())
            keyFile = new File(I2PAppContext.getGlobalContext().getConfigDir(), getPrivKeyFile());
        if (keyFile.exists()) {
            //log("Not overwriting existing private keys in " + keyFile.getAbsolutePath());
            return true;
        } else {
            File parent = keyFile.getParentFile();
            if ( (parent != null) && (!parent.exists()) )
                parent.mkdirs();
        }
        FileOutputStream fos = null;
        try {
            fos = new SecureFileOutputStream(keyFile);
            SigType stype = I2PClient.DEFAULT_SIGTYPE;
            String st = _config.getProperty(OPT_SIG_TYPE);
            if (st != null) {
                SigType type = SigType.parseSigType(st);
                if (type != null)
                    stype = type;
                else
                    log("Unsupported sig type " + st + ", reverting to " + stype);
            }
            Destination dest = client.createDestination(fos, stype);
            String destStr = dest.toBase64();
            log("Private key created and saved in " + keyFile.getAbsolutePath());
            log("You should backup this file in a secure place.");
            log("New destination: " + destStr);
            String b32 = dest.toBase32();
            log("Base32: " + b32);
            File backupDir = new SecureFile(I2PAppContext.getGlobalContext().getConfigDir(), KEY_BACKUP_DIR);
            if (backupDir.isDirectory() || backupDir.mkdir()) {
                String name = b32 + '-' + I2PAppContext.getGlobalContext().clock().now() + ".dat";
                File backup = new File(backupDir, name);
                if (FileUtil.copy(keyFile, backup, false, true))
                    log("Private key backup saved to " + backup.getAbsolutePath());
            }
        } catch (I2PException ie) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error creating new destination", ie);
            log("Error creating new destination: " + ie.getMessage());
            return false;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error creating writing the destination to " + keyFile.getAbsolutePath(), ioe);
            log("Error writing the keys to " + keyFile.getAbsolutePath());
            return false;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
        return true;
    }
    
    public void startTunnelBackground() {
        if (_running) return;
        _starting = true;
        new I2PAppThread(new Runnable() { public void run() { startTunnel(); } }).start();
    }
    
    /**
     * Start up the tunnel (if it isn't already running)
     *
     */
    public void startTunnel() {
        _starting = true;
        try {
            doStartTunnel();
        } catch (Exception e) {
            _log.error("Error starting the tunnel " + getName(), e);
            log("Error starting the tunnel " + getName() + ": " + e.getMessage());
            // if we don't acquire() then the release() in stopTunnel() won't work
            acquire();
            stopTunnel();
        }
        _starting = false;
    }

    /**
     *  @throws IllegalArgumentException via methods in I2PTunnel
     */
    private void doStartTunnel() {
        if (_running) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Already running");
            log("Tunnel " + getName() + " is already running");
            return;
        }
        String type = getType(); 
        if ( (type == null) || (type.length() <= 0) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Cannot start the tunnel - no type specified");
            return;
        }
        // Config options may have changed since instantiation, so do this again.
        // Or should we take it out of the constructor completely?
        if (type.endsWith("server") || getPersistentClientKey()) {
            boolean ok = createPrivateKey();
            if (!ok) {
                log("Failed to start tunnel " + getName() + " as the private key file could not be created");
                return;
            }
        }
        setI2CPOptions();
        setSessionOptions();
        if (TYPE_HTTP_CLIENT.equals(type)) {
            startHttpClient();
        } else if(TYPE_IRC_CLIENT.equals(type)) {
            startIrcClient();
        } else if(TYPE_SOCKS.equals(type)) {
            startSocksClient();
        } else if(TYPE_SOCKS_IRC.equals(type)) {
            startSocksIRCClient();
        } else if(TYPE_CONNECT.equals(type)) {
            startConnectClient();
        } else if (TYPE_STD_CLIENT.equals(type)) {
            startClient();
        } else if (TYPE_STREAMR_CLIENT.equals(type)) {
            startStreamrClient();
        } else if (TYPE_STD_SERVER.equals(type)) {
            startServer();
        } else if (TYPE_HTTP_SERVER.equals(type)) {
            startHttpServer();
        } else if (TYPE_HTTP_BIDIR_SERVER.equals(type)) {
            startHttpBidirServer();
        } else if (TYPE_IRC_SERVER.equals(type)) {
            startIrcServer();
        } else if (TYPE_STREAMR_SERVER.equals(type)) {
            startStreamrServer();
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Cannot start tunnel - unknown type [" + type + "]");
            return;
        }
        acquire();
        _running = true;
    }
    
    private void startHttpClient() {
        setListenOn();
        String listenPort = getListenPort();
        String proxyList = getProxyList();
        String sharedClient = getSharedClient();
        if (proxyList == null)
            _tunnel.runHttpClient(new String[] { listenPort, sharedClient }, this);
        else
            _tunnel.runHttpClient(new String[] { listenPort, sharedClient, proxyList }, this);
    }
    
    private void startConnectClient() {
        setListenOn();
        String listenPort = getListenPort();
        String proxyList = getProxyList();
        String sharedClient = getSharedClient();
        if (proxyList == null)
            _tunnel.runConnectClient(new String[] { listenPort, sharedClient }, this);
        else
            _tunnel.runConnectClient(new String[] { listenPort, sharedClient, proxyList }, this);
    }
    
    private void startIrcClient() {
        setListenOn();
        String listenPort = getListenPort();
        String dest = getTargetDestination();
        String sharedClient = getSharedClient();
        if (getPersistentClientKey()) {
            String privKeyFile = getPrivKeyFile(); 
            _tunnel.runIrcClient(new String[] { listenPort, dest, sharedClient, privKeyFile }, this);
        } else {
            _tunnel.runIrcClient(new String[] { listenPort, dest, sharedClient }, this);
        }
    }
    
    private void startSocksClient() {
        setListenOn();
        String listenPort = getListenPort();
        String sharedClient = getSharedClient();
        String proxyList = getProxyList();
        if (proxyList != null) {
            // set the outproxy property the socks tunnel wants
            Properties props = _tunnel.getClientOptions();
            if (!props.containsKey(I2PSOCKSTunnel.PROP_PROXY_DEFAULT))
                props.setProperty(I2PSOCKSTunnel.PROP_PROXY_DEFAULT, proxyList);
        }
        if (getPersistentClientKey()) {
            String privKeyFile = getPrivKeyFile(); 
            _tunnel.runSOCKSTunnel(new String[] { listenPort, "false", privKeyFile }, this);
        } else {
            _tunnel.runSOCKSTunnel(new String[] { listenPort, sharedClient }, this);
        }
    }
    
    /** @since 0.7.12 */
    private void startSocksIRCClient() {
        setListenOn();
        String listenPort = getListenPort();
        String sharedClient = getSharedClient();
        String proxyList = getProxyList();
        if (proxyList != null) {
            // set the outproxy property the socks tunnel wants
            Properties props = _tunnel.getClientOptions();
            if (!props.containsKey(I2PSOCKSTunnel.PROP_PROXY_DEFAULT))
                props.setProperty(I2PSOCKSTunnel.PROP_PROXY_DEFAULT, proxyList);
        }
        if (getPersistentClientKey()) {
            String privKeyFile = getPrivKeyFile(); 
            _tunnel.runSOCKSIRCTunnel(new String[] { listenPort, "false", privKeyFile }, this);
        } else {
            _tunnel.runSOCKSIRCTunnel(new String[] { listenPort, sharedClient }, this);
        }
    }
    
    /*
     *  Streamr client is a UDP server, use the listenPort field for targetPort
     */
    private void startStreamrClient() {
        String targetHost = getTargetHost();
        String targetPort = getListenPort();
        String dest = getTargetDestination();
        _tunnel.runStreamrClient(new String[] { targetHost, targetPort, dest }, this);
    }
    
    /**
     *  Streamr server is a UDP client, use the targetPort field for listenPort
     */
    private void startStreamrServer() {
        String listenOn = getListenOnInterface();
        if ( (listenOn != null) && (listenOn.length() > 0) ) {
            _tunnel.runListenOn(new String[] { listenOn }, this);
        }
        String listenPort = getTargetPort();
        String privKeyFile = getPrivKeyFile(); 
        _tunnel.runStreamrServer(new String[] { listenPort, privKeyFile }, this);
    }
    
    /** 
     * Note the fact that we are using some sessions, so that they dont get
     * closed by some other tunnels
     */
    private void acquire() {
        List<I2PSession> sessions = _tunnel.getSessions();
        if (!sessions.isEmpty()) {
            for (int i = 0; i < sessions.size(); i++) {
                I2PSession session = sessions.get(i);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Acquiring session " + session);
                TunnelControllerGroup group = TunnelControllerGroup.getInstance();
                if (group != null)
                    group.acquire(this, session);
            }
            _sessions = sessions;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No sessions to acquire? for " + getName());
        }
    }
    
    /** 
     * Note the fact that we are no longer using some sessions, and if
     * no other tunnels are using them, close them.
     */
    private void release(Collection<I2PSession> sessions) {
        if (!sessions.isEmpty()) {
            for (I2PSession s : sessions) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Releasing session " + s);
                TunnelControllerGroup group = TunnelControllerGroup.getInstance();
                if (group != null)
                    group.release(this, s);
            }
            // _sessions.clear() ????
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No sessions to release? for " + getName());
        }
    }

    /** 
     *  Get all the sessions we may be using.
     *
     *  @return a copy, non-null
     *  @since 0.9.15
     */
    private Collection<I2PSession> getAllSessions() {
        // We use _sessions AND the tunnel sessions as
        // _sessions will be null for delay-open tunnels - see acquire().
        // We want the current sessions.
        Set<I2PSession> sessions = new HashSet(_tunnel.getSessions());
        if (_sessions != null)
            sessions.addAll(_sessions);
        return sessions;
    }
    
    private void startClient() {
        setListenOn();
        String listenPort = getListenPort(); 
        String dest = getTargetDestination();
        String sharedClient = getSharedClient();
        if (getPersistentClientKey()) {
            String privKeyFile = getPrivKeyFile(); 
            _tunnel.runClient(new String[] { listenPort, dest, sharedClient, privKeyFile }, this);
        } else {
            _tunnel.runClient(new String[] { listenPort, dest, sharedClient }, this);
        }
    }

    private void startServer() {
        String targetHost = getTargetHost(); 
        String targetPort = getTargetPort(); 
        String privKeyFile = getPrivKeyFile(); 
        _tunnel.runServer(new String[] { targetHost, targetPort, privKeyFile }, this);
    }
    
    private void startHttpServer() {
        String targetHost = getTargetHost(); 
        String targetPort = getTargetPort(); 
        String spoofedHost = getSpoofedHost(); 
        String privKeyFile = getPrivKeyFile(); 
        _tunnel.runHttpServer(new String[] { targetHost, targetPort, spoofedHost, privKeyFile }, this);
    }
    
    private void startHttpBidirServer() {
        setListenOn();
        String targetHost = getTargetHost();
        String targetPort = getTargetPort();
        String listenPort = getListenPort();
        String spoofedHost = getSpoofedHost();
        String privKeyFile = getPrivKeyFile();
        _tunnel.runHttpBidirServer(new String[] { targetHost, targetPort, listenPort, spoofedHost, privKeyFile }, this);
    }

    private void startIrcServer() {
        String targetHost = getTargetHost(); 
        String targetPort = getTargetPort(); 
        String privKeyFile = getPrivKeyFile(); 
        _tunnel.runIrcServer(new String[] { targetHost, targetPort, privKeyFile }, this);
    }
    
    private void setListenOn() {
        String listenOn = getListenOnInterface();
        if ( (listenOn != null) && (listenOn.length() > 0) ) {
            _tunnel.runListenOn(new String[] { listenOn }, this);
        }
    }
    
    /**
     *  These are the ones stored with a prefix of "option."
     *
     *  @return keys with the "option." prefix stripped, non-null
     *  @since 0.9.1 Much better than getClientOptions()
     */
    public Properties getClientOptionProps() {
        Properties opts = new Properties();
        for (Map.Entry<Object, Object> e : _config.entrySet()) {
            String key = (String) e.getKey();
            if (key.startsWith(PFX_OPTION)) {
                key = key.substring(PFX_OPTION.length());
                String val = (String) e.getValue();
                opts.setProperty(key, val);
            }
        }
        return opts;
    }

    private void setSessionOptions() {
        Properties opts = getClientOptionProps();
        // targetDestination does NOT start with "option.", but we still want
        // to allow a change on the fly, so we pass it through this way,
        // as a "spoofed" option. Since 0.9.9.
        String target = getTargetDestination();
        if (target != null)
            opts.setProperty(PROP_DEST, target);
        // Ditto outproxy list. Since 0.9.12.
        String proxies = getProxyList();
        if (proxies != null)
            opts.setProperty(PROP_PROXIES, proxies);
        // Ditto spoof host. Since 0.9.15.
        String spoofhost = getSpoofedHost();
        if (spoofhost != null)
            opts.setProperty(PROP_SPOOFED_HOST, spoofhost);
        // Ditto target host/port. Since 0.9.15.
        String targethost = getTargetHost();
        if (targethost != null)
            opts.setProperty(PROP_TARGET_HOST, targethost);
        String targetport = getTargetPort();
        if (targetport != null)
            opts.setProperty(PROP_TARGET_PORT, targetport);
        _tunnel.setClientOptions(opts);
    }
    
    private void setI2CPOptions() {
        String host = getI2CPHost();
        if ( (host != null) && (host.length() > 0) ) 
            _tunnel.host = host;
        // woohah, special casing for people with ipv6/etc
        if ("localhost".equals(_tunnel.host)) 
            _tunnel.host = "127.0.0.1";
        String port = getI2CPPort();
        if ( (port != null) && (port.length() > 0) ) {
            try {
                int portNum = Integer.parseInt(port);
                _tunnel.port = String.valueOf(portNum);
            } catch (NumberFormatException nfe) {
                _tunnel.port = "7654";
            }
        } else {
            _tunnel.port = "7654";
        }
    }

    public void stopTunnel() {
        // I2PTunnel removes the session in close(),
        // so save the sessions to pass to release() and TCG
        Collection<I2PSession> sessions = getAllSessions();
        _tunnel.runClose(new String[] { "forced", "all" }, this);
        release(sessions);
        _running = false;
    }
    
    public void restartTunnel() {
        stopTunnel();
        startTunnel();
    }
    
    /**
     *  As of 0.9.1, updates the options on an existing session
     */
    public void setConfig(Properties config, String prefix) {
        Properties props = new Properties();
        for (Map.Entry<Object, Object> e : config.entrySet()) {
            String key = (String) e.getKey();
            if (key.startsWith(prefix)) {
                key = key.substring(prefix.length());
                String val = (String) e.getValue();
                props.setProperty(key, val);
            }
        }
        _config = props;

        // Set up some per-type defaults
        // This really isn't the best spot to do this but for servers in particular,
        // it's hard to override settings in the subclass since the session connect
        // is done in the I2PTunnelServer constructor.
        String type = getType();
        if (type != null) {
            if (type.equals(TYPE_HTTP_SERVER) || type.equals(TYPE_STREAMR_SERVER)) {
                if (!_config.containsKey(OPT_BUNDLE_REPLY))
                    _config.setProperty(OPT_BUNDLE_REPLY, "false");
            } else if (type.contains("irc") || type.equals(TYPE_STREAMR_CLIENT)) {
                // maybe a bad idea for ircclient if DCC is enabled
                if (!_config.containsKey(OPT_TAGS_SEND))
                    _config.setProperty(OPT_TAGS_SEND, "20");
                if (!_config.containsKey(OPT_LOW_TAGS))
                    _config.setProperty(OPT_LOW_TAGS, "14");
            }
            // same default logic as in EditBean.getSigType()
            if ((type.equals(TYPE_IRC_CLIENT) || type.equals(TYPE_STD_CLIENT) || type.equals(TYPE_SOCKS_IRC))
                && !Boolean.valueOf(getSharedClient())) {
                if (!_config.containsKey(OPT_SIG_TYPE) &&
                    SigType.ECDSA_SHA256_P256.isAvailable())
                    _config.setProperty(OPT_SIG_TYPE, "ECDSA_SHA256_P256");
            }
        }

        // tell i2ptunnel, who will tell the TunnelTask, who will tell the SocketManager
        setSessionOptions();

        if (_running) {
            Collection<I2PSession> sessions = getAllSessions();
            if (sessions.isEmpty()) {
                 if (_log.shouldLog(Log.DEBUG))
                     _log.debug("Running but no sessions to update");
            }
            for (I2PSession s : sessions) {
                // tell the router via the session
                if (!s.isClosed()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Session is open, updating: " + s);
                    s.updateOptions(_tunnel.getClientOptions());
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Session is closed, not updating: " + s);
                }
            }
        } else {
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Not running, not updating sessions");
            }
        }
    }

    /**
     *  @return a copy
     */
    public Properties getConfig(String prefix) { 
        Properties rv = new Properties();
        for (Map.Entry<Object, Object> e : _config.entrySet()) {
            String key = (String) e.getKey();
            String val = (String) e.getValue();
            rv.setProperty(prefix + key, val);
        }
        return rv;
    }

    public String getType() { return _config.getProperty(PROP_TYPE); }
    public String getName() { return _config.getProperty(PROP_NAME); }
    public String getDescription() { return _config.getProperty(PROP_DESCR); }
    public String getI2CPHost() { return _config.getProperty(PROP_I2CP_HOST); }
    public String getI2CPPort() { return _config.getProperty(PROP_I2CP_PORT); }

    /**
     *  These are the ones with a prefix of "option."
     *
     *  @return one big string of "key=val key=val ..."
     *  @deprecated why would you want this? Use getClientOptionProps() instead
     */
    public String getClientOptions() {
        StringBuilder opts = new StringBuilder(64);
        for (Map.Entry<Object, Object> e : _config.entrySet()) {
            String key = (String) e.getKey();
            if (key.startsWith(PFX_OPTION)) {
                key = key.substring(PFX_OPTION.length());
                String val = (String) e.getValue();
                if (opts.length() > 0) opts.append(' ');
                opts.append(key).append('=').append(val);
            }
        }
        return opts.toString();
    }

    public String getListenOnInterface() { return _config.getProperty(PROP_INTFC); }
    public String getTargetHost() { return _config.getProperty(PROP_TARGET_HOST); }
    public String getTargetPort() { return _config.getProperty(PROP_TARGET_PORT); }
    public String getSpoofedHost() { return _config.getProperty(PROP_SPOOFED_HOST); }
    public String getPrivKeyFile() { return _config.getProperty(PROP_FILE); }
    public String getListenPort() { return _config.getProperty(PROP_LISTEN_PORT); }
    public String getTargetDestination() { return _config.getProperty(PROP_DEST); }
    public String getProxyList() { return _config.getProperty(PROP_PROXIES); }
    /** default true */
    public String getSharedClient() { return _config.getProperty(PROP_SHARED, "true"); }
    /** default true */
    public boolean getStartOnLoad() { return Boolean.parseBoolean(_config.getProperty(PROP_START, "true")); }
    public boolean getPersistentClientKey() { return Boolean.parseBoolean(_config.getProperty(OPT_PERSISTENT)); }

    public String getMyDestination() {
        if (_tunnel != null) {
            List<I2PSession> sessions = _tunnel.getSessions();
            for (int i = 0; i < sessions.size(); i++) {
                I2PSession session = sessions.get(i);
                Destination dest = session.getMyDestination();
                if (dest != null)
                    return dest.toBase64();
            }
        }
        return null;
    }
    
    /**
     *  @return "{52 chars}.b32.i2p" or null
     */
    public String getMyDestHashBase32() {
        if (_tunnel != null) {
            List<I2PSession> sessions = _tunnel.getSessions();
            for (int i = 0; i < sessions.size(); i++) {
                I2PSession session = sessions.get(i);
                Destination dest = session.getMyDestination();
                if (dest != null)
                    return dest.toBase32();
            }
        }
        return null;
    }
    
    public boolean getIsRunning() { return _running; }
    public boolean getIsStarting() { return _starting; }

    /** if running but no open sessions, we are in standby */
    public boolean getIsStandby() {
        if (!_running)
            return false;
        for (I2PSession sess : _tunnel.getSessions()) {
            if (!sess.isClosed())
                return false;
        }
        return true;
    }
    
    /**
     *  A text description of the tunnel.
     *  @deprecated unused
     */
    public void getSummary(StringBuilder buf) {
        String type = getType();
        buf.append(type);
      /****
        if ("httpclient".equals(type))
            getHttpClientSummary(buf);
        else if ("client".equals(type))
            getClientSummary(buf);
        else if ("server".equals(type))
            getServerSummary(buf);
        else if ("httpserver".equals(type))
            getHttpServerSummary(buf);
        else
            buf.append("Unknown type ").append(type);
       ****/
    }
    
  /****
    private void getHttpClientSummary(StringBuilder buf) {
        String description = getDescription();
        if ( (description != null) && (description.trim().length() > 0) )
            buf.append("<i>").append(description).append("</i><br />\n");
        buf.append("HTTP proxy listening on port ").append(getListenPort());
        String listenOn = getListenOnInterface();
        if ("0.0.0.0".equals(listenOn)) 
            buf.append(" (reachable by any machine)");
        else if ("127.0.0.1".equals(listenOn))
            buf.append(" (reachable locally only)");
        else
            buf.append(" (reachable at the ").append(listenOn).append(" interface)");
        buf.append("<br />\n");
        String proxies = getProxyList();
        if ( (proxies == null) || (proxies.trim().length() <= 0) )
            buf.append("Outproxy: default [squid.i2p]<br />\n");
        else
            buf.append("Outproxy: ").append(proxies).append("<br />\n");
        getOptionSummary(buf);
    }
    
    private void getClientSummary(StringBuilder buf) {
        String description = getDescription();
        if ( (description != null) && (description.trim().length() > 0) )
            buf.append("<i>").append(description).append("</i><br />\n");
        buf.append("Client tunnel listening on port ").append(getListenPort());
        buf.append(" pointing at ").append(getTargetDestination());
        String listenOn = getListenOnInterface();
        if ("0.0.0.0".equals(listenOn)) 
            buf.append(" (reachable by any machine)");
        else if ("127.0.0.1".equals(listenOn))
            buf.append(" (reachable locally only)");
        else
            buf.append(" (reachable at the ").append(listenOn).append(" interface)");
        buf.append("<br />\n");
        getOptionSummary(buf);
    }
    
    private void getServerSummary(StringBuilder buf) {
        String description = getDescription();
        if ( (description != null) && (description.trim().length() > 0) )
            buf.append("<i>").append(description).append("</i><br />\n");
        buf.append("Server tunnel pointing at port ").append(getTargetPort());
        buf.append(" on ").append(getTargetHost());
        buf.append("<br />\n");
        buf.append("Private destination loaded from ").append(getPrivKeyFile()).append("<br />\n");
        getOptionSummary(buf);
    }
    
    private void getHttpServerSummary(StringBuilder buf) {
        String description = getDescription();
        if ( (description != null) && (description.trim().length() > 0) )
            buf.append("<i>").append(description).append("</i><br />\n");
        buf.append("Server tunnel pointing at port ").append(getTargetPort());
        buf.append(" on ").append(getTargetHost());
        buf.append(" for the site ").append(getSpoofedHost());
        buf.append("<br />\n");
        buf.append("Private destination loaded from ").append(getPrivKeyFile()).append("<br />\n");
        getOptionSummary(buf);
    }
    
    private void getOptionSummary(StringBuilder buf) {
        String opts = getClientOptions();
        if ( (opts != null) && (opts.length() > 0) )
            buf.append("Network options: ").append(opts).append("<br />\n");
        if (_running) {
            List<I2PSession> sessions = _tunnel.getSessions();
            for (int i = 0; i < sessions.size(); i++) {
                I2PSession session = sessions.get(i);
                Destination dest = session.getMyDestination();
                if (dest != null) {
                    buf.append("Destination hash: ").append(dest.calculateHash().toBase64()).append("<br />\n");
                    if ( ("server".equals(getType())) || ("httpserver".equals(getType())) ) {
                        buf.append("Full destination: ");
                        buf.append("<input type=\"text\" size=\"10\" onclick=\"this.select();\" ");
                        buf.append("value=\"").append(dest.toBase64()).append("\" />\n");
                        long val = new Random().nextLong();
                        if (val < 0) val = 0 - val;
                        buf.append("<br />You can <a href=\"http://temp").append(val);
                        buf.append(".i2p/?i2paddresshelper=").append(dest.toBase64()).append("\">view</a>");
                        buf.append(" it in a browser (only when you're using the eepProxy)\n");
                        buf.append("<br />If you are going to share this on IRC, you need to split it up:<br />\n");
                        String str = dest.toBase64();
                        buf.append(str.substring(0, str.length()/2)).append("<br />\n");
                        buf.append(str.substring(str.length()/2)).append("<br />\n");
                        buf.append("You can also post it to <a href=\"http://forum.i2p/viewforum.php?f=16\">Eepsite announcement forum</a><br />");
                    }
                }
            }
        }
    }
  ****/
    
    /**
     *
     */
    public void log(String s) {
        synchronized (this) {
            _messages.add(s);
            while (_messages.size() > 10)
                _messages.remove(0);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(s);
    }
    
    /**
     * Pull off any messages that the I2PTunnel has produced 
     *
     * @return list of messages pulled off (each is a String, earliest first)
     */
    public List<String> clearMessages() { 
        List<String> rv = null;
        synchronized (this) {
            rv = new ArrayList<String>(_messages);
            _messages.clear();
        }
        return rv;
    }

    /**
     * @since 0.9.15
     */
    @Override
    public String toString() { 
        return "TC " + getType() + ' ' + getName() + " for " + _tunnel;
    }
}
