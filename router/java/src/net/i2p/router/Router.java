package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.CoreVersion;
import net.i2p.crypto.DHSessionKeyBuilder;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.router.message.GarlicMessageHandler;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.startup.StartupJob;
import net.i2p.router.startup.WorkingDir;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.ByteCache;
import net.i2p.util.FileUtil;
import net.i2p.util.FortunaRandomSource;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Main driver for the router.
 *
 */
public class Router {
    private Log _log;
    private RouterContext _context;
    private final Map<String, String> _config;
    /** full path */
    private String _configFilename;
    private RouterInfo _routerInfo;
    public final Object routerInfoFileLock = new Object();
    private long _started;
    private boolean _higherVersionSeen;
    //private SessionKeyPersistenceHelper _sessionKeyPersistenceHelper;
    private boolean _killVMOnEnd;
    private boolean _isAlive;
    private int _gracefulExitCode;
    private I2PThread.OOMEventListener _oomListener;
    private ShutdownHook _shutdownHook;
    private final I2PThread _gracefulShutdownDetector;
    private final RouterWatchdog _watchdog;
    private final Thread _watchdogThread;
    
    public final static String PROP_CONFIG_FILE = "router.configLocation";
    
    /** let clocks be off by 1 minute */
    public final static long CLOCK_FUDGE_FACTOR = 1*60*1000; 

    /** used to differentiate routerInfo files on different networks */
    public static final int NETWORK_ID = 2;
    
    /** coalesce stats this often - should be a little less than one minute, so the graphs get updated */
    private static final int COALESCE_TIME = 50*1000;

    /** this puts an 'H' in your routerInfo **/
    public final static String PROP_HIDDEN = "router.hiddenMode";
    /** this does not put an 'H' in your routerInfo **/
    public final static String PROP_HIDDEN_HIDDEN = "router.isHidden";
    public final static String PROP_DYNAMIC_KEYS = "router.dynamicKeys";
    public final static String PROP_INFO_FILENAME = "router.info.location";
    public final static String PROP_INFO_FILENAME_DEFAULT = "router.info";
    public final static String PROP_KEYS_FILENAME = "router.keys.location";
    public final static String PROP_KEYS_FILENAME_DEFAULT = "router.keys";
    public final static String PROP_SHUTDOWN_IN_PROGRESS = "__shutdownInProgress";
    public final static String DNS_CACHE_TIME = "" + (5*60);
        
    private static final String originalTimeZoneID;
    static {
        // grumble about sun's java caching DNS entries *forever* by default
        // so lets just keep 'em for a short time
        System.setProperty("sun.net.inetaddr.ttl", DNS_CACHE_TIME);
        System.setProperty("sun.net.inetaddr.negative.ttl", DNS_CACHE_TIME);
        System.setProperty("networkaddress.cache.ttl", DNS_CACHE_TIME);
        System.setProperty("networkaddress.cache.negative.ttl", DNS_CACHE_TIME);
        System.setProperty("http.agent", "I2P");
        // (no need for keepalive)
        System.setProperty("http.keepAlive", "false");
        // Save it for LogManager
        originalTimeZoneID = TimeZone.getDefault().getID();
        System.setProperty("user.timezone", "GMT");
        // just in case, lets make it explicit...
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
        // https://www.kb.cert.org/vuls/id/402580
        // http://docs.codehaus.org/display/JETTY/SystemProperties
        // Fixed in Jetty 5.1.15 but we are running 5.1.12
        // The default is true, unfortunately it was previously
        // set to false in wrapper.config thru 0.7.10 so we must set it back here.
        System.setProperty("Dorg.mortbay.util.FileResource.checkAliases", "true");
    }
    
    public Router() { this(null, null); }
    public Router(Properties envProps) { this(null, envProps); }
    public Router(String configFilename) { this(configFilename, null); }
    public Router(String configFilename, Properties envProps) {
        _gracefulExitCode = -1;
        _config = new ConcurrentHashMap();

        if (configFilename == null) {
            if (envProps != null) {
                _configFilename = envProps.getProperty(PROP_CONFIG_FILE);
            }
            if (_configFilename == null)
                _configFilename = System.getProperty(PROP_CONFIG_FILE, "router.config");
        } else {
            _configFilename = configFilename;
        }
                    
        // we need the user directory figured out by now, so figure it out here rather than
        // in the RouterContext() constructor.
        //
        // We have not read the config file yet. Therefore the base and config locations
        // are determined solely by properties (first envProps then System), for the purposes
        // of initializing the user's config directory if it did not exist.
        // If the base dir and/or config dir are set in the config file,
        // they wil be used after the initialization of the (possibly different) dirs
        // determined by WorkingDir.
        // So for now, it doesn't make much sense to set the base or config dirs in the config file -
        // use properties instead. If for some reason, distros need this, we can revisit it.
        //
        // Then add it to envProps (but not _config, we don't want it in the router.config file)
        // where it will then be available to all via _context.dir()
        //
        // This call also migrates all files to the new working directory,
        // including router.config
        //

        // Do we copy all the data files to the new directory? default false
        String migrate = System.getProperty("i2p.dir.migrate");
        boolean migrateFiles = Boolean.valueOf(migrate).booleanValue();
        String userDir = WorkingDir.getWorkingDir(envProps, migrateFiles);

        // Use the router.config file specified in the router.configLocation property
        // (default "router.config"),
        // if it is an abolute path, otherwise look in the userDir returned by getWorkingDir
        // replace relative path with absolute
        File cf = new File(_configFilename);
        if (!cf.isAbsolute()) {
            cf = new File(userDir, _configFilename);
            _configFilename = cf.getAbsolutePath();
        }

        readConfig();

        if (envProps == null)
            envProps = new Properties();
        envProps.putAll(_config);

        // This doesn't work, guess it has to be in the static block above?
        // if (Boolean.valueOf(envProps.getProperty("router.disableIPv6")).booleanValue())
        //    System.setProperty("java.net.preferIPv4Stack", "true");

        if (envProps.getProperty("i2p.dir.config") == null)
            envProps.setProperty("i2p.dir.config", userDir);
        // Save this in the context for the logger and apps that need it
        envProps.setProperty("i2p.systemTimeZone", originalTimeZoneID);

        // Make darn sure we don't have a leftover I2PAppContext in the same JVM
        // e.g. on Android - see finalShutdown() also
        List<RouterContext> contexts = RouterContext.getContexts();
        if (contexts.isEmpty()) {
            RouterContext.killGlobalContext();
        } else if (System.getProperty("java.vendor").contains("Android")) {
            System.err.println("Warning: Killing " + contexts.size() + " other routers in this JVM");
            contexts.clear();
            RouterContext.killGlobalContext();
        } else {
            System.err.println("Warning: " + contexts.size() + " other routers in this JVM");
        }

        // The important thing that happens here is the directory paths are set and created
        // i2p.dir.router defaults to i2p.dir.config
        // i2p.dir.app defaults to i2p.dir.router
        // i2p.dir.log defaults to i2p.dir.router
        // i2p.dir.pid defaults to i2p.dir.router
        // i2p.dir.base defaults to user.dir == $CWD
        _context = new RouterContext(this, envProps);

        // This is here so that we can get the directory location from the context
        // for the ping file
        // Check for other router but do not start a thread yet so the update doesn't cause
        // a NCDFE
        if (!isOnlyRouterRunning()) {
            System.err.println("ERROR: There appears to be another router already running!");
            System.err.println("       Please make sure to shut down old instances before starting up");
            System.err.println("       a new one.  If you are positive that no other instance is running,");
            System.err.println("       please delete the file " + getPingFile().getAbsolutePath());
            System.exit(-1);
        }

        if (_config.get("router.firstVersion") == null) {
            // These may be useful someday. First added in 0.8.2
            _config.put("router.firstVersion", RouterVersion.VERSION);
            String now = Long.toString(System.currentTimeMillis());
            _config.put("router.firstInstalled", now);
            _config.put("router.updateLastInstalled", now);
            saveConfig();
        }

        // This is here so that we can get the directory location from the context
        // for the zip file and the base location to unzip to.
        // If it does an update, it never returns.
        // I guess it's better to have the other-router check above this, we don't want to
        // overwrite an existing running router's jar files. Other than ours.
        installUpdates();

        // *********  Start no threads before here ********* //
        //
        // NOW we can start the ping file thread.
        beginMarkingLiveliness();

        // Apps may use this as an easy way to determine if they are in the router JVM
        // But context.isRouterContext() is even easier...
        // Both of these as of 0.7.9
        System.setProperty("router.version", RouterVersion.VERSION);

        // NOW we start all the activity
        _context.initAll();

        // Set wrapper.log permissions.
        // Just hope this is the right location, we don't know for sure,
        // but this is the same method used in LogsHelper and we have no complaints.
        // (we could look for the wrapper.config file and parse it I guess...)
        // If we don't have a wrapper, RouterLaunch does this for us.
        if (_context.hasWrapper()) {
            File f = new File(System.getProperty("java.io.tmpdir"), "wrapper.log");
            if (!f.exists())
                f = new File(_context.getBaseDir(), "wrapper.log");
            if (f.exists())
                SecureFileOutputStream.setPerms(f);
        }

        _routerInfo = null;
        _higherVersionSeen = false;
        _log = _context.logManager().getLog(Router.class);
        _log.info("New router created with config file " + _configFilename);
        //_sessionKeyPersistenceHelper = new SessionKeyPersistenceHelper(_context);
        _killVMOnEnd = true;
        _oomListener = new I2PThread.OOMEventListener() { 
            public void outOfMemory(OutOfMemoryError oom) { 
                clearCaches();
                _log.log(Log.CRIT, "Thread ran out of memory", oom);
                for (int i = 0; i < 5; i++) { // try this 5 times, in case it OOMs
                    try { 
                        _log.log(Log.CRIT, "free mem: " + Runtime.getRuntime().freeMemory() + 
                                           " total mem: " + Runtime.getRuntime().totalMemory());
                        break; // w00t
                    } catch (OutOfMemoryError oome) {
                        // gobble
                    }
                }
                shutdown(EXIT_OOM); 
            }
        };
        _shutdownHook = new ShutdownHook(_context);
        _gracefulShutdownDetector = new I2PAppThread(new GracefulShutdown(), "Graceful shutdown hook", true);
        _gracefulShutdownDetector.start();
        
        _watchdog = new RouterWatchdog(_context);
        _watchdogThread = new I2PAppThread(_watchdog, "RouterWatchdog", true);
        _watchdogThread.start();
        
    }
    
    /** @since 0.8.8 */
    private static final void clearCaches() {
        ByteCache.clearAll();
        SimpleByteCache.clearAll();
    }

    /**
     * Configure the router to kill the JVM when the router shuts down, as well
     * as whether to explicitly halt the JVM during the hard fail process.
     *
     */
    public void setKillVMOnEnd(boolean shouldDie) { _killVMOnEnd = shouldDie; }

    /** @deprecated unused */
    public boolean getKillVMOnEnd() { return _killVMOnEnd; }
    
    public String getConfigFilename() { return _configFilename; }
    public void setConfigFilename(String filename) { _configFilename = filename; }
    
    public String getConfigSetting(String name) { 
            return _config.get(name); 
    }
    public void setConfigSetting(String name, String value) { 
            _config.put(name, value); 
    }
    public void removeConfigSetting(String name) { 
            _config.remove(name); 
    }
    public Set getConfigSettings() { 
            return new HashSet(_config.keySet()); 
    }
    public Properties getConfigMap() { 
        Properties rv = new Properties();
        rv.putAll(_config); 
        return rv;
    }
    
    public RouterInfo getRouterInfo() { return _routerInfo; }

    /**
     *  Caller must ensure info is valid - no validation done here
     */
    public void setRouterInfo(RouterInfo info) { 
        _routerInfo = info; 
        if (_log.shouldLog(Log.INFO))
            _log.info("setRouterInfo() : " + info, new Exception("I did it"));
        if (info != null)
            _context.jobQueue().addJob(new PersistRouterInfoJob(_context));
    }

    /**
     * True if the router has tried to communicate with another router who is running a higher
     * incompatible protocol version.  
     *
     */
    public boolean getHigherVersionSeen() { return _higherVersionSeen; }
    public void setHigherVersionSeen(boolean seen) { _higherVersionSeen = seen; }
    
    public long getWhenStarted() { return _started; }

    /** wall clock uptime */
    public long getUptime() { 
        if ( (_context == null) || (_context.clock() == null) ) return 1; // racing on startup
        return _context.clock().now() - _context.clock().getOffset() - _started;
    }
    
    public RouterContext getContext() { return _context; }
    
    void runRouter() {
        _isAlive = true;
        _started = _context.clock().now();
        Runtime.getRuntime().addShutdownHook(_shutdownHook);
        I2PThread.addOOMEventListener(_oomListener);
        
        _context.keyManager().startup();
        
        // why are we reading this again, it's read in the constructor
        readConfig();
        
        setupHandlers();
        //if (ALLOW_DYNAMIC_KEYS) {
        //    if ("true".equalsIgnoreCase(_context.getProperty(Router.PROP_HIDDEN, "false")))
        //        killKeys();
        //}

        _context.messageValidator().startup();
        _context.tunnelDispatcher().startup();
        _context.inNetMessagePool().startup();
        startupQueue();
        //_context.jobQueue().addJob(new CoalesceStatsJob(_context));
        SimpleScheduler.getInstance().addPeriodicEvent(new CoalesceStatsEvent(_context), COALESCE_TIME);
        _context.jobQueue().addJob(new UpdateRoutingKeyModifierJob(_context));
        warmupCrypto();
        //_sessionKeyPersistenceHelper.startup();
        //_context.adminManager().startup();
        _context.blocklist().startup();
        
        // let the timestamper get us sync'ed
        // this will block for quite a while on a disconnected machine
        long before = System.currentTimeMillis();
        _context.clock().getTimestamper().waitForInitialization();
        long waited = System.currentTimeMillis() - before;
        if (_log.shouldLog(Log.INFO))
            _log.info("Waited " + waited + "ms to initialize");

        _context.jobQueue().addJob(new StartupJob(_context));
    }
    
    /**
     * This updates the config with all settings found in the file.
     * It does not clear the config first, so settings not found in
     * the file will remain in the config.
     *
     * This is synchronized with saveConfig()
     */
    public synchronized void readConfig() {
        String f = getConfigFilename();
        Properties config = getConfig(_context, f);
        // to avoid compiler errror
        Map foo = _config;
        foo.putAll(config);
    }
    
    /** this does not use ctx.getConfigDir(), must provide a full path in filename */
    private static Properties getConfig(RouterContext ctx, String filename) {
        Log log = null;
        if (ctx != null) {
            log = ctx.logManager().getLog(Router.class);
            if (log.shouldLog(Log.DEBUG))
                log.debug("Config file: " + filename, new Exception("location"));
        }
        Properties props = new Properties();
        try {
            File f = new File(filename);
            if (f.canRead()) {
                DataHelper.loadProps(props, f);
                // dont be a wanker
                props.remove(PROP_SHUTDOWN_IN_PROGRESS);
            } else {
                if (log != null)
                    log.warn("Configuration file " + filename + " does not exist");
            }
        } catch (Exception ioe) {
            if (log != null)
                log.error("Error loading the router configuration from " + filename, ioe);
        }
        return props;
    }
    
    public boolean isAlive() { return _isAlive; }
    
    /**
     * Rebuild and republish our routerInfo since something significant 
     * has changed.
     */
    public void rebuildRouterInfo() { rebuildRouterInfo(false); }
    public void rebuildRouterInfo(boolean blockingRebuild) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Rebuilding new routerInfo");
        
        RouterInfo ri = null;
        if (_routerInfo != null)
            ri = new RouterInfo(_routerInfo);
        else
            ri = new RouterInfo();
        
        try {
            ri.setPublished(_context.clock().now());
            Properties stats = _context.statPublisher().publishStatistics();
            stats.setProperty(RouterInfo.PROP_NETWORK_ID, NETWORK_ID+"");
            
            ri.setOptions(stats);
            ri.setAddresses(_context.commSystem().createAddresses());

            addCapabilities(ri);
            SigningPrivateKey key = _context.keyManager().getSigningPrivateKey();
            if (key == null) {
                _log.log(Log.CRIT, "Internal error - signing private key not known?  wtf");
                return;
            }
            ri.sign(key);
            setRouterInfo(ri);
            if (!ri.isValid())
                throw new DataFormatException("Our RouterInfo has a bad signature");
            Republish r = new Republish();
            if (blockingRebuild)
                r.timeReached();
            else
                SimpleScheduler.getInstance().addEvent(r, 0);
        } catch (DataFormatException dfe) {
            _log.log(Log.CRIT, "Internal error - unable to sign our own address?!", dfe);
        }
    }
    
    private class Republish implements SimpleTimer.TimedEvent {
        public void timeReached() {
            try {
                _context.netDb().publish(getRouterInfo());
            } catch (IllegalArgumentException iae) {
                _log.log(Log.CRIT, "Local router info is invalid?  rebuilding a new identity", iae);
                rebuildNewIdentity();
            }
        }
    }
    
    // publicize our ballpark capacity
    public static final char CAPABILITY_BW12 = 'K';
    public static final char CAPABILITY_BW32 = 'L';
    public static final char CAPABILITY_BW64 = 'M';
    public static final char CAPABILITY_BW128 = 'N';
    public static final char CAPABILITY_BW256 = 'O';
    public static final String PROP_FORCE_BWCLASS = "router.forceBandwidthClass";
    
    public static final char CAPABILITY_REACHABLE = 'R';
    public static final char CAPABILITY_UNREACHABLE = 'U';
    public static final String PROP_FORCE_UNREACHABLE = "router.forceUnreachable";

    /** @deprecated unused */
    public static final char CAPABILITY_NEW_TUNNEL = 'T';
    
    public void addCapabilities(RouterInfo ri) {
        int bwLim = Math.min(_context.bandwidthLimiter().getInboundKBytesPerSecond(),
                             _context.bandwidthLimiter().getOutboundKBytesPerSecond());
        bwLim = (int)(((float)bwLim) * getSharePercentage());
        if (_log.shouldLog(Log.INFO))
            _log.info("Adding capabilities w/ bw limit @ " + bwLim, new Exception("caps"));
        
        String force = _context.getProperty(PROP_FORCE_BWCLASS);
        if (force != null && force.length() > 0) {
            ri.addCapability(force.charAt(0));
        } else if (bwLim < 12) {
            ri.addCapability(CAPABILITY_BW12);
        } else if (bwLim <= 32) {
            ri.addCapability(CAPABILITY_BW32);
        } else if (bwLim <= 64) {
            ri.addCapability(CAPABILITY_BW64);
        } else if (bwLim <= 128) {
            ri.addCapability(CAPABILITY_BW128);
        } else { // ok, more than 128KBps... aka "lots"
            ri.addCapability(CAPABILITY_BW256);
        }
        
        // if prop set to true, don't tell people we are ff even if we are
        if (FloodfillNetworkDatabaseFacade.floodfillEnabled(_context) &&
            !Boolean.valueOf(_context.getProperty("router.hideFloodfillParticipant")).booleanValue())
            ri.addCapability(FloodfillNetworkDatabaseFacade.CAPACITY_FLOODFILL);
        
        if(Boolean.valueOf(_context.getProperty(PROP_HIDDEN)).booleanValue())
            ri.addCapability(RouterInfo.CAPABILITY_HIDDEN);
        
        if (Boolean.valueOf(_context.getProperty(PROP_FORCE_UNREACHABLE)).booleanValue()) {
            ri.addCapability(CAPABILITY_UNREACHABLE);
            return;
        }
        switch (_context.commSystem().getReachabilityStatus()) {
            case CommSystemFacade.STATUS_OK:
                ri.addCapability(CAPABILITY_REACHABLE);
                break;
            case CommSystemFacade.STATUS_DIFFERENT:
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
                ri.addCapability(CAPABILITY_UNREACHABLE);
                break;
            case CommSystemFacade.STATUS_UNKNOWN:
                // no explicit capability
                break;
        }
    }
    
    public boolean isHidden() {
        RouterInfo ri = _routerInfo;
        if ( (ri != null) && (ri.isHidden()) )
            return true;
        return Boolean.valueOf(_context.getProperty(PROP_HIDDEN_HIDDEN)).booleanValue();
    }

    /**
     *  @return the certificate for a new RouterInfo - probably a null cert.
     */
    public Certificate createCertificate() {
        if (isHidden())
            return new Certificate(Certificate.CERTIFICATE_TYPE_HIDDEN, null);
        return Certificate.NULL_CERT;
    }
    
    /**
     * Ugly list of files that we need to kill if we are building a new identity
     *
     */
    private static final String _rebuildFiles[] = new String[] { "router.info", 
                                                                 "router.keys",
                                                                 "netDb/my.info",      // no longer used
                                                                 "connectionTag.keys", // never used?
                                                                 "keyBackup/privateEncryption.key",
                                                                 "keyBackup/privateSigning.key",
                                                                 "keyBackup/publicEncryption.key",
                                                                 "keyBackup/publicSigning.key",
                                                                 "sessionKeys.dat"     // no longer used
                                                               };

    static final String IDENTLOG = "identlog.txt";
    public void killKeys() {
        //new Exception("Clearing identity files").printStackTrace();
        int remCount = 0;
        for (int i = 0; i < _rebuildFiles.length; i++) {
            File f = new File(_context.getRouterDir(),_rebuildFiles[i]);
            if (f.exists()) {
                boolean removed = f.delete();
                if (removed) {
                    System.out.println("INFO:  Removing old identity file: " + _rebuildFiles[i]);
                    remCount++;
                } else {
                    System.out.println("ERROR: Could not remove old identity file: " + _rebuildFiles[i]);
                }
            }
        }

        // now that we have random ports, keeping the same port would be bad
        removeConfigSetting(UDPTransport.PROP_INTERNAL_PORT);
        removeConfigSetting(UDPTransport.PROP_EXTERNAL_PORT);
        saveConfig();

        if (remCount > 0) {
            FileOutputStream log = null;
            try {
                log = new FileOutputStream(new File(_context.getRouterDir(), IDENTLOG), true);
                log.write((new Date() + ": Old router identity keys cleared\n").getBytes());
            } catch (IOException ioe) {
                // ignore
            } finally {
                if (log != null)
                    try { log.close(); } catch (IOException ioe) {}
            }
        }
    }
    /**
     * Rebuild a new identity the hard way - delete all of our old identity 
     * files, then reboot the router.
     *
     */
    public void rebuildNewIdentity() {
        killKeys();
        for (Runnable task : _context.getShutdownTasks()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running shutdown task " + task.getClass());
            try {
                task.run();
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error running shutdown task", t);
            }
        }
        _context.removeShutdownTasks();
        // hard and ugly
        if (_context.hasWrapper())
            _log.log(Log.CRIT, "Restarting with new router identity");
        else
            _log.log(Log.CRIT, "Shutting down because old router identity was invalid - restart I2P");
        finalShutdown(EXIT_HARD_RESTART);
    }
    
    private void warmupCrypto() {
        _context.random().nextBoolean();
        // Use restart() to refire the static refiller threads, in case
        // we are restarting the router in the same JVM (Android)
        DHSessionKeyBuilder.restart();
        _context.elGamalEngine().restart();
    }
    
    private void startupQueue() {
        _context.jobQueue().runQueue(1);
    }
    
    private void setupHandlers() {
        _context.inNetMessagePool().registerHandlerJobBuilder(GarlicMessage.MESSAGE_TYPE, new GarlicMessageHandler(_context));
        //_context.inNetMessagePool().registerHandlerJobBuilder(TunnelMessage.MESSAGE_TYPE, new TunnelMessageHandler(_context));
    }
    
    /**
     *  this is for oldconsole.jsp, pretty much unused except as a way to get memory info,
     *  so let's comment out the rest, it is available elsewhere, and we don't really
     *  want to spend a minute rendering a multi-megabyte page in memory.
     */
    public void renderStatusHTML(Writer out) throws IOException {
/****************
        out.write("<h1>Router console</h1>\n" +
                   "<i><a href=\"/oldconsole.jsp\">console</a> | <a href=\"/oldstats.jsp\">stats</a></i><br>\n" +
                   "<form action=\"/oldconsole.jsp\">" +
                   "<select name=\"go\" onChange='location.href=this.value'>" +
                   "<option value=\"/oldconsole.jsp#bandwidth\">Bandwidth</option>\n" +
                   "<option value=\"/oldconsole.jsp#clients\">Clients</option>\n" +
                   "<option value=\"/oldconsole.jsp#transports\">Transports</option>\n" +
                   "<option value=\"/oldconsole.jsp#profiles\">Peer Profiles</option>\n" +
                   "<option value=\"/oldconsole.jsp#tunnels\">Tunnels</option>\n" +
                   "<option value=\"/oldconsole.jsp#jobs\">Jobs</option>\n" +
                   "<option value=\"/oldconsole.jsp#shitlist\">Shitlist</option>\n" +
                   "<option value=\"/oldconsole.jsp#pending\">Pending messages</option>\n" +
                   "<option value=\"/oldconsole.jsp#netdb\">Network Database</option>\n" +
                   "<option value=\"/oldconsole.jsp#logs\">Log messages</option>\n" +
                   "</select> <input type=\"submit\" value=\"GO\" /> </form>" +
                   "<hr>\n");
**************/

        StringBuilder buf = new StringBuilder(4*1024);
        
        // Please don't change the text or formatting, tino matches it in his scripts
        if ( (_routerInfo != null) && (_routerInfo.getIdentity() != null) )
            buf.append("<b>Router: </b> ").append(_routerInfo.getIdentity().getHash().toBase64()).append("<br>\n");
        buf.append("<b>As of: </b> ").append(new Date(_context.clock().now())).append("<br>\n");
        buf.append("<b>RouterUptime: </b> " ).append(DataHelper.formatDuration(getUptime())).append(" <br>\n");
        buf.append("<b>Started on: </b> ").append(new Date(getWhenStarted())).append("<br>\n");
        buf.append("<b>Clock offset: </b> ").append(_context.clock().getOffset()).append("ms (OS time: ").append(new Date(_context.clock().now() - _context.clock().getOffset())).append(")<br>\n");
        buf.append("<b>RouterVersion:</b> ").append(RouterVersion.FULL_VERSION).append(" / SDK: ").append(CoreVersion.VERSION).append("<br>\n"); 
        long tot = Runtime.getRuntime().totalMemory()/1024;
        long free = Runtime.getRuntime().freeMemory()/1024;
        buf.append("<b>Memory:</b> In use: ").append((tot-free)).append("KB Free: ").append(free).append("KB <br>\n"); 
        if (_higherVersionSeen) 
            buf.append("<b><font color=\"red\">HIGHER VERSION SEEN</font><b> - please <a href=\"http://www.i2p.net/\">check</a> to see if there is a new release out<br>\n");

/*********
        buf.append("<hr><a name=\"bandwidth\"> </a><h2>Bandwidth</h2>\n");
        long sent = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        long received = _context.bandwidthLimiter().getTotalAllocatedInboundBytes();
        buf.append("<ul>");

        buf.append("<li> ").append(sent).append(" bytes sent, ");
        buf.append(received).append(" bytes received</li>");

        long notSent = _context.bandwidthLimiter().getTotalWastedOutboundBytes();
        long notReceived = _context.bandwidthLimiter().getTotalWastedInboundBytes();

        buf.append("<li> ").append(notSent).append(" bytes outbound bytes unused, ");
        buf.append(notReceived).append(" bytes inbound bytes unused</li>");

        DecimalFormat fmt = new DecimalFormat("##0.00");

        // we use the unadjusted time, since thats what getWhenStarted is based off
        long lifetime = _context.clock().now()-_context.clock().getOffset() - getWhenStarted();
        lifetime /= 1000;
        if ( (sent > 0) && (received > 0) ) {
            double sendKBps = sent / (lifetime*1024.0);
            double receivedKBps = received / (lifetime*1024.0);
            buf.append("<li>Lifetime rate: ");
            buf.append(fmt.format(sendKBps)).append("KBps sent ");
            buf.append(fmt.format(receivedKBps)).append("KBps received");
            buf.append("</li>");
        }
        if ( (notSent > 0) && (notReceived > 0) ) {
            double notSendKBps = notSent / (lifetime*1024.0);
            double notReceivedKBps = notReceived / (lifetime*1024.0);
            buf.append("<li>Lifetime unused rate: ");
            buf.append(fmt.format(notSendKBps)).append("KBps outbound unused  ");
            buf.append(fmt.format(notReceivedKBps)).append("KBps inbound unused");
            buf.append("</li>");
        } 
        
        RateStat sendRate = _context.statManager().getRate("transport.sendMessageSize");
        for (int i = 0; i < sendRate.getPeriods().length; i++) {
            Rate rate = sendRate.getRate(sendRate.getPeriods()[i]);
            double bytes = rate.getLastTotalValue();
            long ms = rate.getLastTotalEventTime() + rate.getLastTotalEventTime();
            if (ms <= 0) {
                bytes = 0;
                ms = 1;
            }
            buf.append("<li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" instantaneous send avg: ");
            double bps = bytes*1000.0d/ms;
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps");
            } else {
                buf.append(fmt.format(bps)).append(" Bps");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li><li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" period send avg: ");
            bps = bytes*1000.0d/(rate.getPeriod()); 
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps");
            } else {
                buf.append(fmt.format(bps)).append(" Bps");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li>");
        }

        RateStat receiveRate = _context.statManager().getRate("transport.receiveMessageSize");
        for (int i = 0; i < receiveRate.getPeriods().length; i++) {
            Rate rate = receiveRate.getRate(receiveRate.getPeriods()[i]);
            double bytes = rate.getLastTotalValue();
            long ms = rate.getLastTotalEventTime();
            if (ms <= 0) {
                bytes = 0;
                ms = 1;
            }
            buf.append("<li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" instantaneous receive avg: ");
            double bps = bytes*1000.0d/ms;
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps ");
            } else {
                buf.append(fmt.format(bps)).append(" Bps ");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li><li>");
            buf.append(DataHelper.formatDuration(rate.getPeriod())).append(" period receive avg: ");
            bps = bytes*1000.0d/(rate.getPeriod());
            if (bps > 2048) {
                bps /= 1024.0d;
                buf.append(fmt.format(bps)).append(" KBps");
            } else {
                buf.append(fmt.format(bps)).append(" Bps");
            }
            buf.append(" over ").append((long)bytes).append(" bytes");
            buf.append("</li>");
        }

        buf.append("</ul>\n");
        buf.append("<i>Instantaneous averages count how fast the transfers go when we're trying to transfer data, ");
        buf.append("while period averages count how fast the transfers go across the entire period, even when we're not ");
        buf.append("trying to transfer data.  Lifetime averages count how many elephants there are on the moon [like anyone reads this text]</i>");
        buf.append("\n");
        
        out.write(buf.toString());
        
        _context.bandwidthLimiter().renderStatusHTML(out);

        out.write("<hr><a name=\"clients\"> </a>\n");
        
        _context.clientManager().renderStatusHTML(out);
        
        out.write("\n<hr><a name=\"transports\"> </a>\n");
        
        _context.commSystem().renderStatusHTML(out);
        
        out.write("\n<hr><a name=\"profiles\"> </a>\n");
        
        _context.peerManager().renderStatusHTML(out);
        
        out.write("\n<hr><a name=\"tunnels\"> </a>\n");
        
        _context.tunnelManager().renderStatusHTML(out);
        
        out.write("\n<hr><a name=\"jobs\"> </a>\n");
        
        _context.jobQueue().renderStatusHTML(out);
        
        out.write("\n<hr><a name=\"shitlist\"> </a>\n");
        
        _context.shitlist().renderStatusHTML(out);
        
        out.write("\n<hr><a name=\"pending\"> </a>\n");
        
        _context.messageRegistry().renderStatusHTML(out);
        
        out.write("\n<hr><a name=\"netdb\"> </a>\n");
        
        _context.netDb().renderLeaseSetHTML(out);
        _context.netDb().renderStatusHTML(out);
        
        buf.setLength(0);
        buf.append("\n<hr><a name=\"logs\"> </a>\n");	
        List msgs = _context.logManager().getBuffer().getMostRecentMessages();
        buf.append("\n<h2>Most recent console messages:</h2><table>\n");
        for (Iterator iter = msgs.iterator(); iter.hasNext(); ) {
            String msg = (String)iter.next();
            buf.append("<tr><td align=\"left\"><pre>");
            appendLogMessage(buf, msg);
            buf.append("</pre></td></tr>\n");
        }
        buf.append("</table>\n");
***********/
        out.write(buf.toString());
        out.flush();
    }
    
    //private static int MAX_MSG_LENGTH = 120;
    private static final void appendLogMessage(StringBuilder buf, String msg) {
        // disable this code for the moment because i think it
        // looks ugly (on the router console)
        //if (true) {
            buf.append(msg);
            return;
        //}
/******
        if (msg.length() < MAX_MSG_LENGTH) {
            buf.append(msg);
            return;
        }
        int newline = msg.indexOf('\n');
        int len = msg.length();
        while ( (msg != null) && (len > 0) ) {
            if (newline < 0) {
                // last line, trim if necessary
                if (len > MAX_MSG_LENGTH)
                    msg = msg.substring(len-MAX_MSG_LENGTH);
                buf.append(msg);
                return;
            } else if (newline >= MAX_MSG_LENGTH) {
                // not the last line, but too long.  
                // trim the first few chars 
                String cur = msg.substring(newline-MAX_MSG_LENGTH, newline).trim();
                msg = msg.substring(newline+1);
                if (cur.length() > 0)
                    buf.append(cur).append('\n');
            } else {
                // newline <= max_msg_length, so its not the last,
                // and not too long
                String cur = msg.substring(0, newline).trim();
                msg = msg.substring(newline+1);
                if (cur.length() > 0)
                    buf.append(cur).append('\n');
            }
            newline = msg.indexOf('\n');
            len = msg.length();
        }
******/
    }
    
    /** main-ish method for testing appendLogMessage */
/******
    private static final void testAppendLog() {
        StringBuilder buf = new StringBuilder(1024);
        Router.appendLogMessage(buf, "hi\nhow are you\nh0h0h0");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "\nfine thanks\nh0h0h0");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "liar\nblah blah\n");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "\n");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, ".........10........20........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, ".........10........\n20........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, ".........10........20\n........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, ".........10.......\n.20........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
        Router.appendLogMessage(buf, "\n.........10........20........30........40........50........6");
        System.out.println("line: [" + buf.toString() + "]");
        buf.setLength(0);
    }
******/
    
    public static final int EXIT_GRACEFUL = 2;
    public static final int EXIT_HARD = 3;
    public static final int EXIT_OOM = 10;
    public static final int EXIT_HARD_RESTART = 4;
    public static final int EXIT_GRACEFUL_RESTART = 5;
    
    public void shutdown(int exitCode) {
        _isAlive = false;
        _context.random().saveSeed();
        I2PThread.removeOOMEventListener(_oomListener);
        // Run the shutdown hooks first in case they want to send some goodbye messages
        // Maybe we need a delay after this too?
        for (Runnable task : _context.getShutdownTasks()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running shutdown task " + task.getClass());
            try {
                task.run();
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error running shutdown task", t);
            }
        }
        _context.removeShutdownTasks();
        try { _context.clientManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the client manager", t); }
        try { _context.namingService().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the naming service", t); }
        try { _context.jobQueue().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the job queue", t); }
        //try { _context.adminManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the admin manager", t); }        
        try { _context.statPublisher().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the stats publisher", t); }
        try { _context.statManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the stats manager", t); }
        try { _context.tunnelManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the tunnel manager", t); }
        try { _context.tunnelDispatcher().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the tunnel dispatcher", t); }
        try { _context.netDb().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the networkDb", t); }
        try { _context.commSystem().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the comm system", t); }
        try { _context.bandwidthLimiter().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the comm system", t); }
        try { _context.peerManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the peer manager", t); }
        try { _context.messageRegistry().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the message registry", t); }
        try { _context.messageValidator().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the message validator", t); }
        try { _context.inNetMessagePool().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the inbound net pool", t); }
        try { _context.clientMessagePool().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the client msg pool", t); }
        try { _context.sessionKeyManager().shutdown(); } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting down the session key manager", t); }
        _context.deleteTempDir();
        List<RouterContext> contexts = RouterContext.getContexts();
        contexts.remove(_context);

        // shut down I2PAppContext tasks here

        // If there are multiple routers in the JVM, we don't want to do this
        // to the DH or YK tasks, as they are singletons.
        if (contexts.isEmpty()) {
            try {
                DHSessionKeyBuilder.shutdown();
            } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting DH", t); }
            try {
                _context.elGamalEngine().shutdown();
            } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting elGamal", t); }
        } else {
            _log.logAlways(Log.WARN, "Warning - " + contexts.size() + " routers remaining in this JVM, not releasing all resources");
        }
        try {
            ((FortunaRandomSource)_context.random()).shutdown();
        } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting random()", t); }

        // logManager shut down in finalShutdown()
        _watchdog.shutdown();
        _watchdogThread.interrupt();
        finalShutdown(exitCode);
    }

    /**
     * disable dynamic key functionality for the moment, as it may be harmful and doesn't
     * add meaningful anonymity
     */
    private static final boolean ALLOW_DYNAMIC_KEYS = false;

    private void finalShutdown(int exitCode) {
        clearCaches();
        _log.log(Log.CRIT, "Shutdown(" + exitCode + ") complete"  /* , new Exception("Shutdown") */ );
        try { _context.logManager().shutdown(); } catch (Throwable t) { }
        if (ALLOW_DYNAMIC_KEYS) {
            if (Boolean.valueOf(_context.getProperty(PROP_DYNAMIC_KEYS)).booleanValue())
                killKeys();
        }

        File f = getPingFile();
        f.delete();
        if (RouterContext.getContexts().isEmpty())
            RouterContext.killGlobalContext();

        // Since 0.8.8, mainly for Android
        for (Runnable task : _context.getFinalShutdownTasks()) {
            System.err.println("Running final shutdown task " + task.getClass());
            try {
                task.run();
            } catch (Throwable t) {
                System.err.println("Running final shutdown task " + t);
            }
        }
        _context.getFinalShutdownTasks().clear();

        if (_killVMOnEnd) {
            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            Runtime.getRuntime().halt(exitCode);
        } else {
            Runtime.getRuntime().gc();
        }
    }
    
    /**
     * Call this if we want the router to kill itself as soon as we aren't 
     * participating in any more tunnels (etc).  This will not block and doesn't
     * guarantee any particular time frame for shutting down.  To shut the 
     * router down immediately, use {@link #shutdown}.  If you want to cancel
     * the graceful shutdown (prior to actual shutdown ;), call 
     * {@link #cancelGracefulShutdown}.
     *
     */
    public void shutdownGracefully() {
        shutdownGracefully(EXIT_GRACEFUL);
    }
    /**
     * Call this with EXIT_HARD or EXIT_HARD_RESTART for a non-blocking,
     * hard, non-graceful shutdown with a brief delay to allow a UI response
     */
    public void shutdownGracefully(int exitCode) {
        _gracefulExitCode = exitCode;
        _config.put(PROP_SHUTDOWN_IN_PROGRESS, "true");
        synchronized (_gracefulShutdownDetector) {
            _gracefulShutdownDetector.notifyAll();
        }
    }
    
    /**
     * Cancel any prior request to shut the router down gracefully.
     *
     */
    public void cancelGracefulShutdown() {
        _gracefulExitCode = -1;
        _config.remove(PROP_SHUTDOWN_IN_PROGRESS);
        synchronized (_gracefulShutdownDetector) {
            _gracefulShutdownDetector.notifyAll();
        }        
    }
    /**
     * What exit code do we plan on using when we shut down (or -1, if there isn't a graceful shutdown planned)
     */
    public int scheduledGracefulExitCode() { return _gracefulExitCode; }
    public boolean gracefulShutdownInProgress() {
        return (null != _config.get(PROP_SHUTDOWN_IN_PROGRESS));
    }
    /** How long until the graceful shutdown will kill us?  */
    public long getShutdownTimeRemaining() {
        if (_gracefulExitCode <= 0) return -1; // maybe Long.MAX_VALUE would be better?
        if (_gracefulExitCode == EXIT_HARD || _gracefulExitCode == EXIT_HARD_RESTART)
            return 0;
        long exp = _context.tunnelManager().getLastParticipatingExpiration();
        if (exp < 0)
            return -1;
        else
            return exp + 2*CLOCK_FUDGE_FACTOR - _context.clock().now();
    }
    
    /**
     * Simple thread that sits and waits forever, managing the
     * graceful shutdown "process" (describing it would take more text
     * than just reading the code...)
     *
     */
    private class GracefulShutdown implements Runnable {
        public void run() {
            while (true) {
                boolean shutdown = (null != _config.get(PROP_SHUTDOWN_IN_PROGRESS));
                if (shutdown) {
                    if (_gracefulExitCode == EXIT_HARD || _gracefulExitCode == EXIT_HARD_RESTART ||
                        _context.tunnelManager().getParticipatingCount() <= 0) {
                        if (_gracefulExitCode == EXIT_HARD)
                            _log.log(Log.CRIT, "Shutting down after a brief delay");
                        else if (_gracefulExitCode == EXIT_HARD_RESTART)
                            _log.log(Log.CRIT, "Restarting after a brief delay");
                        else
                            _log.log(Log.CRIT, "Graceful shutdown progress - no more tunnels, safe to die");
                        // Allow time for a UI reponse
                        try {
                            synchronized (Thread.currentThread()) {
                                Thread.currentThread().wait(2*1000);
                            }
                        } catch (InterruptedException ie) {}
                        shutdown(_gracefulExitCode);
                        return;
                    } else {
                        try {
                            synchronized (Thread.currentThread()) {
                                Thread.currentThread().wait(10*1000);
                            }
                        } catch (InterruptedException ie) {}
                    }
                } else {
                    try {
                        synchronized (Thread.currentThread()) {
                            Thread.currentThread().wait();
                        }
                    } catch (InterruptedException ie) {}
                }
            }
        }
    }
    
    /**
     * Save the current config options (returning true if save was 
     * successful, false otherwise)
     *
     * Note that unlike DataHelper.storeProps(),
     * this does escape the \r or \n that are unescaped in DataHelper.loadProps().
     * Note that the escaping of \r or \n was probably a mistake and should be taken out.
     *
     * Synchronized with file read in getConfig()
     */
    public synchronized boolean saveConfig() {
        FileOutputStream fos = null;
        try {
            fos = new SecureFileOutputStream(_configFilename);
            StringBuilder buf = new StringBuilder(8*1024);
            buf.append("# NOTE: This I2P config file must use UTF-8 encoding\n");
            TreeSet ordered = new TreeSet(_config.keySet());
            for (Iterator iter = ordered.iterator() ; iter.hasNext(); ) {
                String key = (String)iter.next();
                String val = _config.get(key);
                    // Escape line breaks before saving.
                    // Remember: "\" needs escaping both for regex and string.
                    // NOOO - see comments in DataHelper
                    //val = val.replaceAll("\\r","\\\\r");
                    //val = val.replaceAll("\\n","\\\\n");
                buf.append(key).append('=').append(val).append('\n');
            }
            fos.write(buf.toString().getBytes("UTF-8"));
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error saving the config to " + _configFilename, ioe);
            return false;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
        
        return true;
    }
    
    /**
     *  A "soft" restart, primarily of the comm system, after
     *  a port change or large step-change in system time.
     *  Does not stop the whole JVM, so it is safe even in the absence
     *  of the wrapper.
     *  This is not a graceful restart - all peer connections are dropped.
     */
    public void restart() {
        _isAlive = false;
        
        try { _context.commSystem().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the comm system", t); }
        try { _context.clientManager().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the client manager", t); }
        try { _context.tunnelManager().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the tunnel manager", t); }
        try { _context.peerManager().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the peer manager", t); }
        try { _context.netDb().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the networkDb", t); }
        
        //try { _context.jobQueue().restart(); } catch (Throwable t) { _log.log(Log.CRIT, "Error restarting the job queue", t); }
        
        _log.log(Log.CRIT, "Restart teardown complete... ");
        try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
        
        _log.log(Log.CRIT, "Restarting...");
        
        _isAlive = true;
        _started = _context.clock().now();
        
        _log.log(Log.CRIT, "Restart complete");
    }
    
    public static void main(String args[]) {
        System.out.println("Starting I2P " + RouterVersion.FULL_VERSION);
        // installUpdates() moved to constructor so we can get file locations from the context
        // installUpdates();
        //verifyWrapperConfig();
        Router r = new Router();
        if ( (args != null) && (args.length == 1) && ("rebuild".equals(args[0])) ) {
            r.rebuildNewIdentity();
        } else {
            r.runRouter();
        }
    }
    
    public static final String UPDATE_FILE = "i2pupdate.zip";
    
    /**
     * Unzip update file found in the router dir OR base dir, to the base dir
     *
     * If we can't write to the base dir, complain.
     * Note: _log not available here.
     */
    private void installUpdates() {
        File updateFile = new File(_context.getRouterDir(), UPDATE_FILE);
        boolean exists = updateFile.exists();
        if (!exists) {
            updateFile = new File(_context.getBaseDir(), UPDATE_FILE);
            exists = updateFile.exists();
        }
        if (exists) {
            // do a simple permissions test, if it fails leave the file in place and don't restart
            File test = new File(_context.getBaseDir(), "history.txt");
            if ((test.exists() && !test.canWrite()) || (!_context.getBaseDir().canWrite())) {
                System.out.println("ERROR: No write permissions on " + _context.getBaseDir() +
                                   " to extract software update file");
                // carry on
                return;
            }
            System.out.println("INFO: Update file exists [" + UPDATE_FILE + "] - installing");
            // verify the whole thing first
            // we could remember this fails, and not bother restarting, but who cares...
            boolean ok = FileUtil.verifyZip(updateFile);
            if (ok) {
                // This may be useful someday. First added in 0.8.2
                // Moved above the extract so we don't NCDFE
                _config.put("router.updateLastInstalled", "" + System.currentTimeMillis());
                saveConfig();
                ok = FileUtil.extractZip(updateFile, _context.getBaseDir());
            }

            // Very important - we have now trashed our jars.
            // After this point, do not use any new I2P classes, or they will fail to load
            // and we will die with NCDFE.
            // Ideally, do not use I2P classes at all, new or not.
            try {
                if (ok)
                    System.out.println("INFO: Update installed");
                else
                    System.out.println("ERROR: Update failed!");
                if (!ok) {
                    // we can't leave the file in place or we'll continually restart, so rename it
                    File bad = new File(_context.getRouterDir(), "BAD-" + UPDATE_FILE);
                    boolean renamed = updateFile.renameTo(bad);
                    if (renamed) {
                        System.out.println("Moved update file to " + bad.getAbsolutePath());
                    } else {
                        System.out.println("Deleting file " + updateFile.getAbsolutePath());
                        ok = true;  // so it will be deleted
                    }
                }
                if (ok) {
                    boolean deleted = updateFile.delete();
                    if (!deleted) {
                        System.out.println("ERROR: Unable to delete the update file!");
                        updateFile.deleteOnExit();
                    }
                }
                // exit whether ok or not
                if (_context.hasWrapper())
                    System.out.println("INFO: Restarting after update");
                else
                    System.out.println("WARNING: Exiting after update, restart I2P");
            } catch (Throwable t) {
                // hide the NCDFE
                // hopefully the update file got deleted or we will loop
            }
            System.exit(EXIT_HARD_RESTART);
        } else {
            // Remove extracted libjbigi.so and libjcpuid.so files if we have a newer jbigi.jar,
            // so the new ones will be extracted.
            // We do this after the restart, not after the extract, because it's safer, and
            // because people may upgrade their jbigi.jar file manually.

            // Copied from NativeBigInteger, which we can't access here or the
            // libs will get loaded.
            String osArch = System.getProperty("os.arch");
            boolean isX86 = osArch.contains("86") || osArch.equals("amd64");
            String osName = System.getProperty("os.name").toLowerCase();
            boolean isWin = osName.startsWith("win");
            boolean isMac = osName.startsWith("mac");
            // only do this on these OSes
            boolean goodOS = isWin || isMac ||
                             osName.contains("linux") || osName.contains("freebsd");

            // only do this on these x86
            File jbigiJar = new File(_context.getBaseDir(), "lib/jbigi.jar");
            if (isX86 && goodOS && jbigiJar.exists()) {
                String libPrefix = (isWin ? "" : "lib");
                String libSuffix = (isWin ? ".dll" : isMac ? ".jnilib" : ".so");

                File jcpuidLib = new File(_context.getBaseDir(), libPrefix + "jcpuid" + libSuffix);
                if (jcpuidLib.canWrite() && jbigiJar.lastModified() > jcpuidLib.lastModified()) {
                    String path = jcpuidLib.getAbsolutePath();
                    boolean success = FileUtil.copy(path, path + ".bak", true, true);
                    if (success) {
                        boolean success2 = jcpuidLib.delete();
                        if (success2) {
                            System.out.println("New jbigi.jar detected, moved jcpuid library to " +
                                               path + ".bak");
                            System.out.println("Check logs for successful installation of new library");
                        }
                    }
                }

                File jbigiLib = new File(_context.getBaseDir(), libPrefix + "jbigi" + libSuffix);
                if (jbigiLib.canWrite() && jbigiJar.lastModified() > jbigiLib.lastModified()) {
                    String path = jbigiLib.getAbsolutePath();
                    boolean success = FileUtil.copy(path, path + ".bak", true, true);
                    if (success) {
                        boolean success2 = jbigiLib.delete();
                        if (success2) {
                            System.out.println("New jbigi.jar detected, moved jbigi library to " +
                                               path + ".bak");
                            System.out.println("Check logs for successful installation of new library");
                        }
                    }
                }
            }
        }
    }
    
/*******
    private static void verifyWrapperConfig() {
        File cfgUpdated = new File("wrapper.config.updated");
        if (cfgUpdated.exists()) {
            cfgUpdated.delete();
            System.out.println("INFO: Wrapper config updated, but the service wrapper requires you to manually restart");
            System.out.println("INFO: Shutting down the router - please rerun it!");
            System.exit(EXIT_HARD);
        }
    }
*******/
    
/*
    private static String getPingFile(Properties envProps) {
        if (envProps != null) 
            return envProps.getProperty("router.pingFile", "router.ping");
        else
            return "router.ping";
    }
*/
    private File getPingFile() {
        String s = _context.getProperty("router.pingFile", "router.ping");
        File f = new File(s);
        if (!f.isAbsolute())
            f = new File(_context.getPIDDir(), s);
        return f;
    }
    
    static final long LIVELINESS_DELAY = 60*1000;
    
    /** 
     * Check the file "router.ping", but if 
     * that file already exists and was recently written to, return false as there is
     * another instance running.
     * 
     * @return true if the router is the only one running 
     * @since 0.8.2
     */
    private boolean isOnlyRouterRunning() {
        File f = getPingFile();
        if (f.exists()) {
            long lastWritten = f.lastModified();
            if (System.currentTimeMillis()-lastWritten > LIVELINESS_DELAY) {
                System.err.println("WARN: Old router was not shut down gracefully, deleting router.ping");
                f.delete();
            } else {
                return false;
            }
        }
        return true;
    }

    /** 
     * Start a thread that will periodically update the file "router.ping".
     * isOnlyRouterRunning() MUST have been called previously.
     */
    private void beginMarkingLiveliness() {
        File f = getPingFile();
        SimpleScheduler.getInstance().addPeriodicEvent(new MarkLiveliness(this, f), 0, LIVELINESS_DELAY);
    }
    
    public static final String PROP_BANDWIDTH_SHARE_PERCENTAGE = "router.sharePercentage";
    public static final int DEFAULT_SHARE_PERCENTAGE = 80;
    
    /** 
     * What fraction of the bandwidth specified in our bandwidth limits should
     * we allow to be consumed by participating tunnels?
     * @return a number less than one, not a percentage!
     *
     */
    public double getSharePercentage() {
        RouterContext ctx = _context;
        if (ctx == null) return 0;
        String pct = ctx.getProperty(PROP_BANDWIDTH_SHARE_PERCENTAGE);
        if (pct != null) {
            try {
                double d = Double.parseDouble(pct);
                if (d > 1)
                    return d/100d; // *cough* sometimes its 80 instead of .8 (!stab jrandom)
                else
                    return d;
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Unable to get the share percentage");
            }
        }
        return DEFAULT_SHARE_PERCENTAGE / 100.0d;
    }

    public int get1sRate() { return get1sRate(false); }
    public int get1sRate(boolean outboundOnly) {
        RouterContext ctx = _context;
        if (ctx != null) {
            FIFOBandwidthLimiter bw = ctx.bandwidthLimiter();
            if (bw != null) {
                int out = (int)bw.getSendBps();
                if (outboundOnly)
                    return out;
                return (int)Math.max(out, bw.getReceiveBps());
            }
        }
        return 0;
    }
    public int get1sRateIn() {
        RouterContext ctx = _context;
        if (ctx != null) {
            FIFOBandwidthLimiter bw = ctx.bandwidthLimiter();
            if (bw != null)
                return (int) bw.getReceiveBps();
        }
        return 0;
    }

    public int get15sRate() { return get15sRate(false); }
    public int get15sRate(boolean outboundOnly) {
        RouterContext ctx = _context;
        if (ctx != null) {
            FIFOBandwidthLimiter bw = ctx.bandwidthLimiter();
            if (bw != null) {
                int out = (int)bw.getSendBps15s();
                if (outboundOnly)
                    return out;
                return (int)Math.max(out, bw.getReceiveBps15s());
            }
        }
        return 0;
    }
    public int get15sRateIn() {
        RouterContext ctx = _context;
        if (ctx != null) {
            FIFOBandwidthLimiter bw = ctx.bandwidthLimiter();
            if (bw != null)
                return (int) bw.getReceiveBps15s();
        }
        return 0;
    }

    public int get1mRate() { return get1mRate(false); }
    public int get1mRate(boolean outboundOnly) {
        int send = 0;
        RouterContext ctx = _context;
        if (ctx == null)
            return 0;
        StatManager mgr = ctx.statManager();
        if (mgr == null)
            return 0;
        RateStat rs = mgr.getRate("bw.sendRate");
        if (rs != null)
            send = (int)rs.getRate(1*60*1000).getAverageValue();
        if (outboundOnly)
            return send;
        int recv = 0;
        rs = mgr.getRate("bw.recvRate");
        if (rs != null)
            recv = (int)rs.getRate(1*60*1000).getAverageValue();
        return Math.max(send, recv);
    }
    public int get1mRateIn() {
        RouterContext ctx = _context;
        if (ctx == null)
            return 0;
        StatManager mgr = ctx.statManager();
        if (mgr == null)
            return 0;
        RateStat rs = mgr.getRate("bw.recvRate");
        int recv = 0;
        if (rs != null)
            recv = (int)rs.getRate(1*60*1000).getAverageValue();
        return recv;
    }

    public int get5mRate() { return get5mRate(false); }
    public int get5mRate(boolean outboundOnly) {
        int send = 0;
        RateStat rs = _context.statManager().getRate("bw.sendRate");
        if (rs != null)
            send = (int)rs.getRate(5*60*1000).getAverageValue();
        if (outboundOnly)
            return send;
        int recv = 0;
        rs = _context.statManager().getRate("bw.recvRate");
        if (rs != null)
            recv = (int)rs.getRate(5*60*1000).getAverageValue();
        return Math.max(send, recv);
    }
    
    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     *  @since 0.8.7
     */
    private static final String _x(String s) {
        return s;
    }

/* following classes are now private static inner classes, didn't bother to reindent */

private static final long LOW_MEMORY_THRESHOLD = 5 * 1024 * 1024;

/**
 * coalesce the stats framework every minute
 *
 */
private static class CoalesceStatsEvent implements SimpleTimer.TimedEvent {
    private RouterContext _ctx;
    private long _maxMemory;

    public CoalesceStatsEvent(RouterContext ctx) { 
        _ctx = ctx; 
        // NOTE TO TRANSLATORS - each of these phrases is a description for a statistic
        // to be displayed on /stats.jsp and in the graphs on /graphs.jsp.
        // Please keep relatively short so it will fit on the graphs.
        ctx.statManager().createRequiredRateStat("bw.receiveBps", _x("Message receive rate (bytes/sec)"), "Bandwidth", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        ctx.statManager().createRequiredRateStat("bw.sendBps", _x("Message send rate (bytes/sec)"), "Bandwidth", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        ctx.statManager().createRequiredRateStat("bw.sendRate", _x("Low-level send rate (bytes/sec)"), "Bandwidth", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        ctx.statManager().createRequiredRateStat("bw.recvRate", _x("Low-level receive rate (bytes/sec)"), "Bandwidth", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l });
        ctx.statManager().createRequiredRateStat("router.activePeers", _x("How many peers we are actively talking with"), "Throttle", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        ctx.statManager().createRateStat("router.activeSendPeers", "How many peers we've sent to this minute", "Throttle", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        ctx.statManager().createRateStat("router.highCapacityPeers", "How many high capacity peers we know", "Throttle", new long[] { 5*60*1000, 60*60*1000 });
        ctx.statManager().createRequiredRateStat("router.fastPeers", _x("Known fast peers"), "Throttle", new long[] { 5*60*1000, 60*60*1000 });
        _maxMemory = Runtime.getRuntime().maxMemory();
        String legend = "(Bytes)";
        if (_maxMemory < Long.MAX_VALUE)
            legend += " Max is " + DataHelper.formatSize(_maxMemory) + 'B';
        // router.memoryUsed currently has the max size in the description so it can't be tagged
        ctx.statManager().createRequiredRateStat("router.memoryUsed", legend, "Router", new long[] { 60*1000 });
    }
    private RouterContext getContext() { return _ctx; }
    public void timeReached() {
        int active = getContext().commSystem().countActivePeers();
        getContext().statManager().addRateData("router.activePeers", active, 60*1000);

        int activeSend = getContext().commSystem().countActiveSendPeers();
        getContext().statManager().addRateData("router.activeSendPeers", activeSend, 60*1000);

        int fast = getContext().profileOrganizer().countFastPeers();
        getContext().statManager().addRateData("router.fastPeers", fast, 60*1000);

        int highCap = getContext().profileOrganizer().countHighCapacityPeers();
        getContext().statManager().addRateData("router.highCapacityPeers", highCap, 60*1000);

        getContext().statManager().addRateData("bw.sendRate", (long)getContext().bandwidthLimiter().getSendBps(), 0);
        getContext().statManager().addRateData("bw.recvRate", (long)getContext().bandwidthLimiter().getReceiveBps(), 0);
        
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        getContext().statManager().addRateData("router.memoryUsed", used, 0);
        if (_maxMemory - used < LOW_MEMORY_THRESHOLD)
            clearCaches();

        getContext().tunnelDispatcher().updateParticipatingStats(COALESCE_TIME);

        getContext().statManager().coalesceStats();

        RateStat receiveRate = getContext().statManager().getRate("transport.receiveMessageSize");
        if (receiveRate != null) {
            Rate rate = receiveRate.getRate(60*1000);
            if (rate != null) { 
                double bytes = rate.getLastTotalValue();
                double bps = (bytes*1000.0d)/rate.getPeriod(); 
                getContext().statManager().addRateData("bw.receiveBps", (long)bps, 60*1000);
            }
        }

        RateStat sendRate = getContext().statManager().getRate("transport.sendMessageSize");
        if (sendRate != null) {
            Rate rate = sendRate.getRate(60*1000);
            if (rate != null) {
                double bytes = rate.getLastTotalValue();
                double bps = (bytes*1000.0d)/rate.getPeriod(); 
                getContext().statManager().addRateData("bw.sendBps", (long)bps, 60*1000);
            }
        }
    }
}

/**
 * Update the routing Key modifier every day at midnight (plus on startup).
 * This is done here because we want to make sure the key is updated before anyone
 * uses it.
 */
private static class UpdateRoutingKeyModifierJob extends JobImpl {
    private Log _log;
    private Calendar _cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
    public UpdateRoutingKeyModifierJob(RouterContext ctx) { 
        super(ctx);
    }
    public String getName() { return "Update Routing Key Modifier"; }
    public void runJob() {
        _log = getContext().logManager().getLog(getClass());
        getContext().routingKeyGenerator().generateDateBasedModData();
        requeue(getTimeTillMidnight());
    }
    private long getTimeTillMidnight() {
        long now = getContext().clock().now();
        _cal.setTime(new Date(now));
        _cal.set(Calendar.YEAR, _cal.get(Calendar.YEAR));               // gcj <= 4.0 workaround
        _cal.set(Calendar.DAY_OF_YEAR, _cal.get(Calendar.DAY_OF_YEAR)); // gcj <= 4.0 workaround
        _cal.add(Calendar.DATE, 1);
        _cal.set(Calendar.HOUR_OF_DAY, 0);
        _cal.set(Calendar.MINUTE, 0);
        _cal.set(Calendar.SECOND, 0);
        _cal.set(Calendar.MILLISECOND, 0);
        long then = _cal.getTime().getTime();
        long howLong = then - now;
        if (howLong < 0) // hi kaffe
            howLong = 24*60*60*1000l + howLong;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Time till midnight: " + howLong + "ms");
        return howLong;
    }
}

/**
 *  Write a timestamp to the ping file where the wrapper can see it
 */
private static class MarkLiveliness implements SimpleTimer.TimedEvent {
    private Router _router;
    private File _pingFile;

    public MarkLiveliness(Router router, File pingFile) {
        _router = router;
        _pingFile = pingFile;
        _pingFile.deleteOnExit();
    }

    public void timeReached() {
        if (_router.isAlive())
            ping();
        else
            _pingFile.delete();
    }

    private void ping() {
        FileOutputStream fos = null;
        try { 
            fos = new FileOutputStream(_pingFile);
            fos.write(("" + System.currentTimeMillis()).getBytes());
        } catch (IOException ioe) {
            System.err.println("Error writing to ping file");
            ioe.printStackTrace();
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
}

private static class ShutdownHook extends Thread {
    private RouterContext _context;
    private static int __id = 0;
    private int _id;
    public ShutdownHook(RouterContext ctx) {
        _context = ctx;
        _id = ++__id;
    }
        @Override
    public void run() {
        setName("Router " + _id + " shutdown");
        Log l = _context.logManager().getLog(Router.class);
        l.log(Log.CRIT, "Shutting down the router...");
        _context.router().shutdown(Router.EXIT_HARD);
    }
}

/** update the router.info file whenever its, er, updated */
private static class PersistRouterInfoJob extends JobImpl {
    public PersistRouterInfoJob(RouterContext ctx) { 
        super(ctx); 
    }

    public String getName() { return "Persist Updated Router Information"; }

    public void runJob() {
        Log _log = getContext().logManager().getLog(PersistRouterInfoJob.class);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Persisting updated router info");

        String infoFilename = getContext().getProperty(PROP_INFO_FILENAME, PROP_INFO_FILENAME_DEFAULT);
        File infoFile = new File(getContext().getRouterDir(), infoFilename);

        RouterInfo info = getContext().router().getRouterInfo();

        FileOutputStream fos = null;
        synchronized (getContext().router().routerInfoFileLock) {
            try {
                fos = new SecureFileOutputStream(infoFile);
                info.writeBytes(fos);
            } catch (DataFormatException dfe) {
                _log.error("Error rebuilding the router information", dfe);
            } catch (IOException ioe) {
                _log.error("Error writing out the rebuilt router information", ioe);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
    }
}

}
