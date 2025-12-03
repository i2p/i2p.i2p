package net.i2p.i2ptunnel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.app.ClientAppManager;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.KeyCertificate;
import net.i2p.data.PrivateKey;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.i2ptunnel.socks.I2PSOCKSTunnel;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import net.i2p.util.RandomSource;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

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
    private File _configFile;
    private final I2PTunnel _tunnel;
    private final List<String> _messages;
    private List<I2PSession> _sessions;
    private volatile TunnelState _state;
    private volatile SimpleTimer2.TimedEvent _pkfc;

    /** @since 0.9.19 */
    private enum TunnelState {
        START_ON_LOAD,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        DESTROYING,
        DESTROYED,
    }

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
    public static final String PROP_FILTER = "filterDefinition";
    /** @since 0.9.42 */
    public static final String PROP_CONFIG_FILE = "configFile";
    /** @since 0.9.46 */
    public static final String PROP_TUN_GZIP = "i2ptunnel.gzip";

    /**
     * all of these are @since 0.9.33 (moved from TunnelConfig)
     */
    public static final String PROP_MAX_CONNS_MIN = "i2p.streaming.maxConnsPerMinute";
    public static final String PROP_MAX_CONNS_HOUR = "i2p.streaming.maxConnsPerHour";
    public static final String PROP_MAX_CONNS_DAY = "i2p.streaming.maxConnsPerDay";
    public static final String PROP_MAX_TOTAL_CONNS_MIN = "i2p.streaming.maxTotalConnsPerMinute";
    public static final String PROP_MAX_TOTAL_CONNS_HOUR = "i2p.streaming.maxTotalConnsPerHour";
    public static final String PROP_MAX_TOTAL_CONNS_DAY = "i2p.streaming.maxTotalConnsPerDay";
    public static final String PROP_MAX_STREAMS = "i2p.streaming.maxConcurrentStreams";
    public static final String PROP_LIMITS_SET = "i2p.streaming.limitsManuallySet";
    public static final int DEFAULT_MAX_CONNS_MIN = 30;
    public static final int DEFAULT_MAX_CONNS_HOUR = 80;
    public static final int DEFAULT_MAX_CONNS_DAY = 200;
    public static final int DEFAULT_MAX_TOTAL_CONNS_MIN = 50;
    public static final int DEFAULT_MAX_TOTAL_CONNS_HOUR = 0;
    public static final int DEFAULT_MAX_TOTAL_CONNS_DAY = 0;
    public static final int DEFAULT_MAX_STREAMS = 30;

    /** @since 0.9.34 */
    public static final String PROP_LIMIT_ACTION = "i2p.streaming.limitAction";

    /** @since 0.9.14 */
    public static final String PFX_OPTION = "option.";

    private static final String OPT_PERSISTENT = PFX_OPTION + "persistentClientKey";
    public static final String OPT_BUNDLE_REPLY = PFX_OPTION + "shouldBundleReplyInfo";
    private static final String OPT_TAGS_SEND = PFX_OPTION + "crypto.tagsToSend";
    private static final String OPT_LOW_TAGS = PFX_OPTION + "crypto.lowTagThreshold";
    private static final String OPT_SIG_TYPE = PFX_OPTION + I2PClient.PROP_SIGTYPE;
    /** @since 0.9.30 */
    private static final String OPT_ALT_PKF = PFX_OPTION + I2PTunnelServer.PROP_ALT_PKF;

    /**
     * all of these are @since 0.9.33
     */
    private static final String OPT_MAX_CONNS_MIN = PFX_OPTION + PROP_MAX_CONNS_MIN;
    private static final String OPT_MAX_CONNS_HOUR = PFX_OPTION + PROP_MAX_CONNS_HOUR;
    private static final String OPT_MAX_CONNS_DAY = PFX_OPTION + PROP_MAX_CONNS_DAY;
    private static final String OPT_MAX_TOTAL_CONNS_MIN = PFX_OPTION + PROP_MAX_TOTAL_CONNS_MIN;
    private static final String OPT_MAX_TOTAL_CONNS_HOUR = PFX_OPTION + PROP_MAX_TOTAL_CONNS_HOUR;
    private static final String OPT_MAX_TOTAL_CONNS_DAY = PFX_OPTION + PROP_MAX_TOTAL_CONNS_DAY;
    private static final String OPT_MAX_STREAMS = PFX_OPTION + PROP_MAX_STREAMS;
    private static final String OPT_LIMITS_SET = PFX_OPTION + PROP_LIMITS_SET;
    public static final String OPT_POST_MAX = PFX_OPTION + I2PTunnelHTTPServer.OPT_POST_MAX;
    public static final String OPT_POST_TOTAL_MAX = PFX_OPTION + I2PTunnelHTTPServer.OPT_POST_TOTAL_MAX;

    /** @since 0.9.34 */
    private static final String OPT_LIMIT_ACTION = PFX_OPTION + PROP_LIMIT_ACTION;
    private static final String OPT_I2CP_GZIP = PFX_OPTION + I2PClient.PROP_GZIP;

    private static final String OPT_ENCTYPE = PFX_OPTION + "i2cp.leaseSetEncType";

    /** @since 0.9.53 */
    private static final String OPT_PRIORITY = PFX_OPTION + "outbound.priority";

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
    /** Client in the UI and I2P side but a server on the localhost side */
    public static final String TYPE_STREAMR_CLIENT = "streamrclient";
    /** Server in the UI and I2P side but a client on the localhost side */
    public static final String TYPE_STREAMR_SERVER = "streamrserver";

    /**
     *  This is guaranteed to be available.
     *  @since 0.9.17
     */
    public static final SigType PREFERRED_SIGTYPE;
    static {
        if (SystemVersion.isGNU() || SystemVersion.isAndroid()) {
            if (SigType.ECDSA_SHA256_P256.isAvailable())
                PREFERRED_SIGTYPE = SigType.ECDSA_SHA256_P256;
            else
                PREFERRED_SIGTYPE = SigType.DSA_SHA1;
        } else {
            PREFERRED_SIGTYPE = SigType.EdDSA_SHA512_Ed25519;
        }
    }

    /**
     * Create a new controller for a tunnel out of the specific config options.
     * The config may contain a large number of options - only ones that begin in
     * the prefix should be used (and, in turn, that prefix should be stripped off
     * before being interpreted by this controller)
     *
     * If config contains the "configFile" property, it will be set as the config path
     * and may be retrieved with getConfigFile().
     *
     * Defaults in config properties are not recommended, they may or may not be honored.
     *
     * @param config original key=value mapping non-null
     * @param prefix beginning of key values that are relevant to this tunnel
     */
    public TunnelController(Properties config, String prefix) {
        this(config, prefix, true);
    }

    /**
     * Create a new controller for a tunnel out of the specific config options.
     * The config may contain a large number of options - only ones that begin in
     * the prefix should be used (and, in turn, that prefix should be stripped off
     * before being interpreted by this controller)
     *
     * If config contains the "configFile" property, it will be set as the config path
     * and may be retrieved with getConfigFile().
     *
     * Defaults in config properties are not recommended, they may or may not be honored.
     *
     * @param config original key=value mapping non-null
     * @param prefix beginning of key values that are relevant to this tunnel
     * @param createKey for servers, whether we want to create a brand new destination
     *                  with private keys at the location specified or not (does not
     *                  overwrite existing ones)
     */
    public TunnelController(Properties config, String prefix, boolean createKey) {
        _tunnel = new I2PTunnel(this);
        _log = I2PAppContext.getGlobalContext().logManager().getLog(TunnelController.class);
        setConfig(config, prefix);
        _messages = new ArrayList<String>(4);
        boolean keyOK = true;
        if (createKey && (!isClient() || getPersistentClientKey())) {
            keyOK = createPrivateKey();
            if (keyOK && !isClient() && !getType().equals(TYPE_STREAMR_SERVER)) {
                // check rv?
                createAltPrivateKey();
            }
        }
        _state = keyOK && getStartOnLoad() ? TunnelState.START_ON_LOAD : TunnelState.STOPPED;
    }

    /**
     *  The I2PTunnel
     *
     *  @since 0.9.53 for advanced plugin usage
     */
    public I2PTunnel getTunnel() {
        return _tunnel;
    }

    /**
     * @return success
     */
    private boolean createPrivateKey() {
        I2PClient client = I2PClientFactory.createClient();
        File keyFile = getPrivateKeyFile();
        if (keyFile == null) {
            log("No filename specified for the private key");
            return false;
        }

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
            SigType stype = PREFERRED_SIGTYPE;
            String st = _config.getProperty(OPT_SIG_TYPE);
            if (st != null) {
                SigType type = SigType.parseSigType(st);
                if (type != null && type.isAvailable())
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
                if (FileUtil.copy(keyFile, backup, false, true)) {
                    SecureFileOutputStream.setPerms(backup);
                    log("Private key backup saved to " + backup.getAbsolutePath());
                }
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

    /**
     * Creates alternate Destination with the same encryption keys as the primary Destination,
     * but a different signing key.
     *
     * Must have already called createPrivateKey() successfully.
     * Does nothing unless option OPT_ALT_PKF is set with the privkey file name.
     * Does nothing if the file already exists.
     *
     * @return success
     * @since 0.9.30
     */
    private boolean createAltPrivateKey() {
        if (PREFERRED_SIGTYPE == SigType.DSA_SHA1)
            return false;
        File keyFile = getPrivateKeyFile();
        if (keyFile == null)
            return false;
        if (!keyFile.exists())
            return false;
        File altFile = getAlternatePrivateKeyFile();
        if (altFile == null)
            return false;
        if (altFile.equals(keyFile))
            return false;
        if (altFile.exists())
            return true;
        PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
        FileOutputStream out = null;
        try {
            Destination dest = pkf.getDestination();
            if (dest == null)
                return false;
            if (dest.getSigType() != SigType.DSA_SHA1)
                return false;
            PublicKey pub = dest.getPublicKey();
            PrivateKey priv = pkf.getPrivKey();
            SimpleDataStructure[] signingKeys = KeyGenerator.getInstance().generateSigningKeys(PREFERRED_SIGTYPE);
            SigningPublicKey signingPubKey = (SigningPublicKey) signingKeys[0];
            SigningPrivateKey signingPrivKey = (SigningPrivateKey) signingKeys[1];
            KeyCertificate cert = new KeyCertificate(signingPubKey);
            Destination d = new Destination();
            d.setPublicKey(pub);
            d.setSigningPublicKey(signingPubKey);
            d.setCertificate(cert);
            int len = signingPubKey.length();
            if (len < 128) {
                byte[] pad = new byte[128 - len];
                RandomSource.getInstance().nextBytes(pad);
                d.setPadding(pad);
            } else if (len > 128) {
                // copy of excess data handled in KeyCertificate constructor
            }

            out = new SecureFileOutputStream(altFile);
            d.writeBytes(out);
            priv.writeBytes(out);
            signingPrivKey.writeBytes(out);
            try { out.close(); } catch (IOException ioe) {}

            String destStr = d.toBase64();
            log("Alternate private key created and saved in " + altFile.getAbsolutePath());
            log("You should backup this file in a secure place.");
            log("New alternate destination: " + destStr);
            String b32 = d.toBase32();
            log("Base32: " + b32);
            File backupDir = new SecureFile(I2PAppContext.getGlobalContext().getConfigDir(), KEY_BACKUP_DIR);
            if (backupDir.isDirectory() || backupDir.mkdir()) {
                String name = b32 + '-' + I2PAppContext.getGlobalContext().clock().now() + ".dat";
                File backup = new File(backupDir, name);
                if (FileUtil.copy(altFile, backup, false, true)) {
                    SecureFileOutputStream.setPerms(backup);
                    log("Alternate private key backup saved to " + backup.getAbsolutePath());
                }
            }
            return true;
        } catch (GeneralSecurityException e) {
            log("Error creating keys " + e);
            return false;
        } catch (I2PSessionException e) {
            log("Error creating keys " + e);
            return false;
        } catch (I2PException e) {
            log("Error creating keys " + e);
            return false;
        } catch (IOException e) {
            log("Error creating keys " + e);
            return false;
        } catch (RuntimeException e) {
            log("Error creating keys " + e);
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
    }

    public void startTunnelBackground() {
        synchronized (this) {
            if (_state != TunnelState.STOPPED && _state != TunnelState.START_ON_LOAD)
                return;
        }
        new I2PAppThread(new Runnable() { public void run() { startTunnel(); } }, "Tunnel Starter " + getName()).start();
    }

    /**
     * Start up the tunnel (if it isn't already running)
     *
     */
    public void startTunnel() {
        synchronized (this) {
            if (_state != TunnelState.STOPPED && _state != TunnelState.START_ON_LOAD) {
                if (_state == TunnelState.RUNNING) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Already running");
                    log("Tunnel " + getName() + " is already running");
                }
                return;
            }
            changeState(TunnelState.STARTING);
        }
        try {
            doStartTunnel();
        } catch (RuntimeException e) {
            String msg = "Error starting the tunnel " + getName();
            _log.error(msg, e);
            addBubble(msg);
            log(msg + ": " + e.getMessage());
            // if we don't acquire() then the release() in stopTunnel() won't work
            acquire();
            stopTunnel();
        }
    }

    /**
     *  @throws IllegalArgumentException via methods in I2PTunnel
     */
    private void doStartTunnel() {
        synchronized (this) {
            if (_state != TunnelState.STARTING)
                return;
        }

        String type = getType();
        if ( (type == null) || (type.length() <= 0) ) {
            changeState(TunnelState.STOPPED);
            if (_log.shouldLog(Log.ERROR))
                _log.error("Cannot start the tunnel - no type specified");
            return;
        }
        // Config options may have changed since instantiation, so do this again.
        // Or should we take it out of the constructor completely?
        if (!isClient() || getPersistentClientKey()) {
            boolean ok = createPrivateKey();
            if (!ok) {
                changeState(TunnelState.STOPPED);
                log("Failed to start tunnel " + getName() + " as the private key file could not be created");
                return;
            }
            if (!isClient() && !getType().equals(TYPE_STREAMR_SERVER)) {
                // check rv?
                createAltPrivateKey();
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
            changeState(TunnelState.STOPPED);
            if (_log.shouldLog(Log.ERROR))
                _log.error("Cannot start tunnel - unknown type [" + type + "]");
            return;
        }
        acquire();
        changeState(TunnelState.RUNNING);
        if ((!isClient() || getPersistentClientKey()) && getIsOfflineKeysAnySession()) {
            File f = getPrivateKeyFile();
            File f2 = getAlternatePrivateKeyFile();
            _pkfc = new PKFChecker(f, f2);
            _pkfc.schedule(5*60*1000L);
        }
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
        Set<I2PSession> sessions = new HashSet<I2PSession>(_tunnel.getSessions());
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
     *  Defaults in config properties are not honored.
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
                _tunnel.port = Integer.toString(I2PClient.DEFAULT_LISTEN_PORT);
            }
        } else {
            _tunnel.port = Integer.toString(I2PClient.DEFAULT_LISTEN_PORT);
        }
    }

    /**
     *  May be restarted with restartTunnel() or startTunnel() later.
     *  This may not release all resources. In particular, the I2PSocketManager remains
     *  and it may have timer threads that continue running.
     */
    public void stopTunnel() {
        synchronized (this) {
            if (_state != TunnelState.STARTING && _state != TunnelState.RUNNING)
                return;
            changeState(TunnelState.STOPPING);
        }
        if (_pkfc != null) {
            _pkfc.cancel();
            _pkfc = null;
        }
        // I2PTunnel removes the session in close(),
        // so save the sessions to pass to release() and TCG
        Collection<I2PSession> sessions = getAllSessions();
        _tunnel.runClose(new String[] { "forced", "all" }, this);
        release(sessions);
        changeState(TunnelState.STOPPED);
    }

    /**
     *  May NOT be restarted with restartTunnel() or startTunnel() later.
     *  This should release all resources.
     *
     *  @since 0.9.17
     */
    public void destroyTunnel() {
        synchronized (this) {
            if (_state != TunnelState.RUNNING)
                return;
            changeState(TunnelState.DESTROYING);
        }
        if (_pkfc != null) {
            _pkfc.cancel();
            _pkfc = null;
        }
        // I2PTunnel removes the session in close(),
        // so save the sessions to pass to release() and TCG
        Collection<I2PSession> sessions = getAllSessions();
        _tunnel.runClose(new String[] { "destroy", "all" }, this);
        release(sessions);
        changeState(TunnelState.DESTROYED);
    }

    public void restartTunnel() {
        TunnelState oldState;
        synchronized (this) {
            oldState = _state;
            if (oldState != TunnelState.STOPPED)
                stopTunnel();
        }
        if (oldState != TunnelState.STOPPED) {
            long ms = _tunnel.getContext().isRouterContext() ? 100 : 500;
            try { Thread.sleep(ms); } catch (InterruptedException ie) {}
        }
        startTunnel();
    }

    /**
     *  As of 0.9.1, updates the options on an existing session
     */
    public void setConfig(Properties config, String prefix) {
        Properties props;
        if (prefix.length() > 0) {
            props = new Properties();
            for (Map.Entry<Object, Object> e : config.entrySet()) {
                String key = (String) e.getKey();
                if (key.startsWith(prefix)) {
                    key = key.substring(prefix.length());
                    String val = (String) e.getValue();
                    props.setProperty(key, val);
                }
            }
        } else {
            props = config;
        }
        Properties oldConfig = _config;
        _config = props;

        // save the config file this was loaded from, if passed in
        String cname = _config.getProperty(PROP_CONFIG_FILE);
        if (cname != null && _configFile == null)
            _configFile = new File(cname);

        // Set up some per-type defaults
        // This really isn't the best spot to do this but for servers in particular,
        // it's hard to override settings in the subclass since the session connect
        // is done in the I2PTunnelServer constructor.
        String type = getType();
        if (type != null) {
            String et = _config.getProperty(OPT_ENCTYPE);
            if (type.equals(TYPE_HTTP_SERVER)) {
                if (!_config.containsKey(OPT_LIMIT_ACTION))
                    _config.setProperty(OPT_LIMIT_ACTION, "http");
            }
            if (type.equals(TYPE_HTTP_SERVER) || type.equals(TYPE_STREAMR_SERVER)) {
                String tgzip = _config.getProperty(PROP_TUN_GZIP);
                if (tgzip == null || Boolean.parseBoolean(tgzip)) {
                    // Web server will gzip
                    // If web server doesn't gzip, I2PTunnelHTTPServer will.
                    // Streaming will force gzip on first packet for header compression,
                    // regardless of this setting
                    if (!_config.containsKey(OPT_I2CP_GZIP))
                        _config.setProperty(OPT_I2CP_GZIP, "false");
                }
                if (!_config.containsKey(OPT_BUNDLE_REPLY))
                    _config.setProperty(OPT_BUNDLE_REPLY, "false");
                if (et == null || et.equals("4,0") || et.equals("4") || et.equals("0"))
                    _config.setProperty(OPT_ENCTYPE, "6,4");
            } else if (!isClient(type)) {
                // override UI that sets it to false
                _config.setProperty(OPT_BUNDLE_REPLY, "true");
            }
            if (type.contains("irc") || type.equals(TYPE_STREAMR_CLIENT)) {
                // maybe a bad idea for ircclient if DCC is enabled
                if (!_config.containsKey(OPT_TAGS_SEND))
                    _config.setProperty(OPT_TAGS_SEND, "20");
                if (!_config.containsKey(OPT_LOW_TAGS))
                    _config.setProperty(OPT_LOW_TAGS, "14");
                if (et == null || et.equals("4,0") || et.equals("4") || et.equals("0"))
                    _config.setProperty(OPT_ENCTYPE, "6,4");
            }
            // same default logic as in EditBean.getSigType() and GeneralHelper.getSigType()
            if (!isClient(type) ||
                type.equals(TYPE_IRC_CLIENT) || type.equals(TYPE_STD_CLIENT) ||
                type.equals(TYPE_SOCKS) || type.equals(TYPE_CONNECT) ||
                type.equals(TYPE_SOCKS_IRC) || type.equals(TYPE_STREAMR_CLIENT) ||
                type.equals(TYPE_HTTP_CLIENT)) {
                if (!_config.containsKey(OPT_SIG_TYPE))
                    _config.setProperty(OPT_SIG_TYPE, PREFERRED_SIGTYPE.name());
            }
            if (type.equals(TYPE_IRC_CLIENT) || type.equals(TYPE_STD_CLIENT) ||
                type.equals(TYPE_IRC_SERVER) || type.equals(TYPE_STD_SERVER) ||
                type.equals(TYPE_SOCKS_IRC)) {
                if (!_config.containsKey(OPT_PRIORITY))
                    _config.setProperty(OPT_PRIORITY, "10");
            }
            if (!isClient(type)) {
                _tunnel.filterDefinition = _config.getProperty(PROP_FILTER);

                String p1 = _config.getProperty(OPT_MAX_CONNS_MIN, "0");
                String p2 = _config.getProperty(OPT_MAX_CONNS_HOUR, "0");
                String p3 = _config.getProperty(OPT_MAX_CONNS_DAY, "0");
                String p4 = _config.getProperty(OPT_MAX_TOTAL_CONNS_MIN, "0");
                String p5 = _config.getProperty(OPT_MAX_TOTAL_CONNS_HOUR, "0");
                String p6 = _config.getProperty(OPT_MAX_TOTAL_CONNS_DAY, "0");
                String p7 = _config.getProperty(OPT_MAX_STREAMS, "0");
                String p8 = _config.getProperty(OPT_LIMITS_SET, "false");
                if (p1.equals("0") && p2.equals("0") && p3.equals("0") &&
                    p4.equals("0") && p5.equals("0") && p6.equals("0") &&
                    p7.equals("0") && !p8.equals("true")) {
                    // No limits set, let's set some defaults
                    _config.setProperty(OPT_MAX_CONNS_MIN, Integer.toString(DEFAULT_MAX_CONNS_MIN));
                    _config.setProperty(OPT_MAX_CONNS_HOUR, Integer.toString(DEFAULT_MAX_CONNS_HOUR));
                    _config.setProperty(OPT_MAX_CONNS_DAY, Integer.toString(DEFAULT_MAX_CONNS_DAY));
                    _config.setProperty(OPT_MAX_TOTAL_CONNS_MIN, Integer.toString(DEFAULT_MAX_TOTAL_CONNS_MIN));
                    _config.setProperty(OPT_MAX_STREAMS, Integer.toString(DEFAULT_MAX_STREAMS));
                }
                if (type.equals(TYPE_HTTP_SERVER) && !p8.equals("true")) {
                    String p9 = _config.getProperty(OPT_POST_MAX, "0");
                    String p10 = _config.getProperty(OPT_POST_TOTAL_MAX, "0");
                    if (p9.equals("0") && p10.equals("0")) {
                        _config.setProperty(OPT_POST_MAX, Integer.toString(I2PTunnelHTTPServer.DEFAULT_POST_MAX));
                        _config.setProperty(OPT_POST_TOTAL_MAX, Integer.toString(I2PTunnelHTTPServer.DEFAULT_POST_TOTAL_MAX));
                    }
                }
                if (!_config.containsKey(I2PSocketOptions.PROP_PROFILE))
                    _config.setProperty(I2PSocketOptions.PROP_PROFILE, Integer.toString(I2PSocketOptions.PROFILE_BULK));
            }
            if (isClient(type) &&
                (type.equals(TYPE_HTTP_CLIENT) || Boolean.parseBoolean(_config.getProperty(PROP_SHARED)))) {
                // migration: HTTP proxy and shared clients default to both
                if (et == null || et.equals("4,0") || et.equals("4") || et.equals("0"))
                    _config.setProperty(OPT_ENCTYPE, "6,4");
            }
        }

        // tell i2ptunnel, who will tell the TunnelTask, who will tell the SocketManager
        setSessionOptions();

        synchronized (this) {
            if (_state != TunnelState.RUNNING) {
                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug("Not running, not updating sessions");
                }
                return;
            }
        }

        if (oldConfig != null) {
            if (configChanged(_config, oldConfig, PROP_FILE) ||
                configChanged(_config, oldConfig, OPT_ALT_PKF) ||
                configChanged(_config, oldConfig, OPT_SIG_TYPE)) {
                log("Tunnel must be stopped and restarted for private key file changes to take effect");
            }
        }

        // Running, so check sessions
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
    }

    /**
     *  Is property p different in p1 and p2?
     *  @since 0.9.30
     */
    private static boolean configChanged(Properties p1, Properties p2, String p) {
        String s1 = p1.getProperty(p);
        String s2 = p2.getProperty(p);
        return (s1 != null && !s1.equals(s2)) ||
               (s1 == null && s2 != null);
    }

    /**
     *  @return a copy
     */
    public Properties getConfig(String prefix) {
        Properties rv = new Properties();
        if (prefix.length() > 0) {
            for (Map.Entry<Object, Object> e : _config.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                rv.setProperty(prefix + key, val);
            }
        } else {
            rv.putAll(_config);
        }
        return rv;
    }

    /**
     *  @return the config file as passed into constructor via "configFile" property,
     *          or as set later, or null
     *  @since 0.9.42
     */
    public File getConfigFile() { return _configFile; }

    /**
     *  Set the config file. Only do this if previously null.
     *  @since 0.9.42
     */
    public void setConfigFile(File file) { _configFile = file; }

    public String getType() { return _config.getProperty(PROP_TYPE); }
    public String getName() { return _config.getProperty(PROP_NAME); }
    public String getDescription() { return _config.getProperty(PROP_DESCR); }
    public String getI2CPHost() { return _config.getProperty(PROP_I2CP_HOST); }
    public String getI2CPPort() { return _config.getProperty(PROP_I2CP_PORT); }

    /**
     *  Absolute path to filter definition file
     *  @since 0.9.40
     */
    public String getFilter() { return _config.getProperty(PROP_FILTER); }

    /**
     *  Is it a client or server in the UI and I2P side?
     *  Note that a streamr client is a UI and I2P client but a server on the localhost side.
     *  Note that a streamr server is a UI and I2P server but a client on the localhost side.
     *
     *  @since 0.9.17
     */
    public boolean isClient() {
        return isClient(getType());
    }

    /**
     *  Is it a client or server in the UI and I2P side?
     *  Note that a streamr client is a UI and I2P client but a server on the localhost side.
     *  Note that a streamr server is a UI and I2P server but a client on the localhost side.
     *
     *  @since 0.9.17 moved from IndexBean
     *  @return false if type == null
     */
    public static boolean isClient(String type) {
        return TYPE_STD_CLIENT.equals(type) ||
               TYPE_HTTP_CLIENT.equals(type) ||
               TYPE_SOCKS.equals(type) ||
               TYPE_SOCKS_IRC.equals(type) ||
               TYPE_CONNECT.equals(type) ||
               TYPE_STREAMR_CLIENT.equals(type) ||
               TYPE_IRC_CLIENT.equals(type);
    }

    /**
     *  These are the ones with a prefix of "option."
     *
     *  @return one big string of "key=val key=val ..."
     *  @deprecated why would you want this? Use getClientOptionProps() instead
     */
    @Deprecated
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

    /**
     *  Probably not absolute. May be null. getPrivateKeyFile() recommended.
     */
    public String getPrivKeyFile() { return _config.getProperty(PROP_FILE); }

    public String getListenPort() { return _config.getProperty(PROP_LISTEN_PORT); }
    public String getTargetDestination() { return _config.getProperty(PROP_DEST); }
    public String getProxyList() { return _config.getProperty(PROP_PROXIES); }

    /** default true for clients, always false for servers */
    public String getSharedClient() {
         if (!isClient())
             return "false";
         return _config.getProperty(PROP_SHARED, "true");
    }

    /** default true */
    public boolean getStartOnLoad() { return Boolean.parseBoolean(_config.getProperty(PROP_START, "true")); }
    public boolean getPersistentClientKey() { return Boolean.parseBoolean(_config.getProperty(OPT_PERSISTENT)); }

    /**
     *  Does not necessarily exist.
     *  @return absolute path or null if unset
     *  @since 0.9.17
     */
    public File getPrivateKeyFile() {
        return filenameToFile(getPrivKeyFile());
    }

    /**
     *  Does not necessarily exist.
     *  @return absolute path or null if unset
     *  @since 0.9.30
     */
    public File getAlternatePrivateKeyFile() {
        return filenameToFile(_config.getProperty(OPT_ALT_PKF));
    }

    /**
     *  Does not necessarily exist.
     *  @param f relative or absolute path, may be null
     *  @return absolute path or null
     *  @since 0.9.30
     */
    static File filenameToFile(String f) {
        if (f == null)
            return null;
        f = f.trim();
        if (f.length() == 0)
            return null;
        File rv = new File(f);
        if (!rv.isAbsolute())
            rv = new File(I2PAppContext.getGlobalContext().getConfigDir(), f);
        return rv;
    }

    /**
     *  Returns null if not running.
     *  @return Base64 or null
     */
    public String getMyDestination() {
        Destination dest = getDestination();
        if (dest != null)
            return dest.toBase64();
        return null;
    }

    /**
     *  Returns null if not running.
     *  @return "{52 chars}.b32.i2p" or null
     */
    public String getMyDestHashBase32() {
        Destination dest = getDestination();
        if (dest != null)
            return dest.toBase32();
        return null;
    }

    /**
     *  Returns null if not running.
     *  @return Destination or null
     *  @since 0.9.17
     */
    public Destination getDestination() {
        List<I2PSession> sessions = _tunnel.getSessions();
        for (int i = 0; i < sessions.size(); i++) {
            I2PSession session = sessions.get(i);
            Destination dest = session.getMyDestination();
            if (dest != null)
                return dest;
        }
        return null;
    }

    /**
     *  Returns false if not running.
     *  @return true if the primary session has offline keys
     *  @since 0.9.40
     */
    public boolean getIsOfflineKeys() {
        List<I2PSession> sessions = _tunnel.getSessions();
        if (!sessions.isEmpty())
            return sessions.get(0).isOffline();
        return false;
    }

    /**
     *  Returns false if not running.
     *  @return true if ANY session or subsession has offline keys
     *  @since 0.9.48
     */
    private boolean getIsOfflineKeysAnySession() {
        List<I2PSession> sessions = _tunnel.getSessions();
        for (I2PSession sess : sessions) {
            if (sess.isOffline())
                return true;
            for (I2PSession sub : sess.getSubsessions()) {
                if (sub.isOffline())
                    return true;
            }
        }
        return false;
    }

    // TODO synch
    public boolean getIsRunning() { return _state == TunnelState.RUNNING; }
    public boolean getIsStarting() { return _state == TunnelState.START_ON_LOAD || _state == TunnelState.STARTING; }

    /** if running but no open sessions, we are in standby */
    public boolean getIsStandby() {
        synchronized (this) {
            if (_state != TunnelState.RUNNING)
                return false;
        }

        for (I2PSession sess : _tunnel.getSessions()) {
            if (!sess.isClosed())
                return false;
        }
        return true;
    }

    /** @since 0.9.19 */
    private synchronized void changeState(TunnelState state) {
        _state = state;
    }

    /**
     *  A text description of the tunnel.
     *  @deprecated unused
     */
    @Deprecated
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
        synchronized (_messages) {
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
        List<String> rv;
        synchronized (_messages) {
            rv = new ArrayList<String>(_messages);
            _messages.clear();
        }
        return rv;
    }

    /**
     * @param msg may be null
     * @since 0.9.66
     */
    private void addBubble(String msg) {
        addBubble(_tunnel.getContext(), msg);
    }

    /**
     * @param msg may be null
     * @since 0.9.66
     */
    static void addBubble(I2PAppContext ctx, String msg) {
        ClientAppManager cmgr = ctx.clientAppManager();
        if (cmgr != null) {
            cmgr.addBubble(PortMapper.SVC_I2PTUNNEL, msg);
        }
    }

    /**
     * @since 0.9.15
     */
    @Override
    public String toString() {
        return "TC " + getType() + ' ' + getName() + " for " + _tunnel + ' ' + _state;
    }

    /**
     * Periodically check for an updated offline-signed private key file.
     * Log if about to expire.
     *
     * @since 0.9.48
     */
    private class PKFChecker extends SimpleTimer2.TimedEvent {
        private final List<File> files;
        private final List<Long> stamps;
        private boolean wasRun;

        /**
         *  caller must schedule
         *  @param f2 may be null
         */
        public PKFChecker(File f, File f2) {
            super(SimpleTimer2.getInstance());
            files = new ArrayList<File>(2);
            stamps = new ArrayList<Long>(2);
            files.add(f);
            stamps.add(Long.valueOf(f.lastModified()));
            if (f2 != null) {
                files.add(f2);
                stamps.add(Long.valueOf(f2.lastModified()));
            }
        }

        public void timeReached() {
            if (!getIsRunning() && !getIsStarting())
                return;
            List<I2PSession> sessions = _tunnel.getSessions();
            if (getIsOfflineKeysAnySession()) {
                I2PAppContext ctx = _tunnel.getContext();
                long now = ctx.clock().now();
                long delay = 2*24*60*60*1000L;
                for (int i = 0; i < files.size(); i++) {
                    File f = files.get(i);
                    long stamp = stamps.get(i).longValue();
                    if (_log.shouldDebug())
                        _log.debug("PKFC checking: " + f + " stamp: " + DataHelper.formatTime(stamp));
                    if (stamp <= 0)
                        continue;
                    if (f.lastModified() > stamp) {
                        String msg = "Private key file " + f + " with offline signature updated, restarting tunnel";
                        _log.logAlways(Log.WARN, msg);
                        _tunnel.log(msg);
                        restartTunnel();
                        return;
                    }
                    if (sessions.isEmpty())
                        continue;
                    I2PSession sess = sessions.get(0);
                    if (i > 0) {
                        List<I2PSession> subs = sess.getSubsessions();
                        if (subs.isEmpty())
                            continue;
                        sess = subs.get(0);
                    }
                    if (!sess.isOffline())
                        continue;
                    long exp = sess.getOfflineExpiration();
                    long remaining = exp - now;
                    if (remaining <= 11*60*1000) {
                        // can't sign another LS
                        String msg;
                        if (remaining > 0)
                            msg = "Offline signature in private key file " + f + " for tunnel expires " + DataHelper.formatTime(exp) + ", stopping the tunnel!";
                        else
                            msg = "Offline signature in private key file " + f + " for tunnel expired " + DataHelper.formatTime(exp) + ", stopping the tunnel!";
                        _log.log(Log.CRIT, msg);
                        _tunnel.log(msg);
                        addBubble(msg);
                        stopTunnel();
                        return;
                    }
                    long d;
                    if (remaining < 24*60*60*1000L) {
                        d = Math.min(60*60*1000L, remaining - (11*60*1000));
                    } else if (remaining < 7*24*60*60*1000L) {
                        d = 6*60*60*1000L;
                        if (!wasRun) {
                            d += ctx.random().nextLong(4 * delay);
                            wasRun = true;
                        }
                    } else {
                        d = 24*60*60*1000L;
                        if (!wasRun) {
                            delay += ctx.random().nextLong(delay);
                            wasRun = true;
                        }
                    }
                    if (remaining < 30*24*60*60*1000L) {
                        String msg = "Offline signature in private key file " + f + " for tunnel expires in " + DataHelper.formatDuration(remaining);
                        _log.logAlways(Log.WARN, msg);
                        _tunnel.log("WARNING: " + msg);
                    }
                    if (d < delay)
                        delay = d;
                }
                if (_log.shouldDebug())
                    _log.debug("PKFC sleeping " + DataHelper.formatDuration(delay));
                schedule(delay);
            }
        }
    }
}
