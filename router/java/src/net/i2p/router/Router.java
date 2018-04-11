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
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import gnu.getopt.Getopt;

import net.i2p.client.impl.I2PSessionImpl;
import net.i2p.crypto.SigUtil;
import net.i2p.data.Base64;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.message.GarlicMessageHandler;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.startup.CreateRouterInfoJob;
import net.i2p.router.startup.PortableWorkingDir;
import net.i2p.router.startup.StartupJob;
import net.i2p.router.startup.WorkingDir;
import net.i2p.router.tasks.*;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.util.EventLog;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.ByteCache;
import net.i2p.util.FortunaRandomSource;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.OrderedProperties;
import net.i2p.util.ReusableGZIPInputStream;
import net.i2p.util.ReusableGZIPOutputStream;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

/**
 * Main driver for the router.
 *
 * For embedded use, instantiate, call setKillVMOnEnd(false), and then call runRouter().
 *
 */
public class Router implements RouterClock.ClockShiftListener {
    private Log _log;
    private final RouterContext _context;
    private final Map<String, String> _config;
    /** full path */
    private String _configFilename;
    private RouterInfo _routerInfo;
    private final ReentrantReadWriteLock _routerInfoLock = new ReentrantReadWriteLock(false);
    /** not for external use */
    public final Object routerInfoFileLock = new Object();
    private final Object _configFileLock = new Object();
    private long _started;
    private boolean _killVMOnEnd;
    private int _gracefulExitCode;
    private I2PThread.OOMEventListener _oomListener;
    private ShutdownHook _shutdownHook;
    private I2PThread _gracefulShutdownDetector;
    private RouterWatchdog _watchdog;
    private Thread _watchdogThread;
    private final EventLog _eventLog;
    private final Object _stateLock = new Object();
    private State _state = State.UNINITIALIZED;
    private FamilyKeyCrypto _familyKeyCrypto;
    private boolean _familyKeyCryptoFail;
    public final Object _familyKeyLock = new Object();
    
    public final static String PROP_CONFIG_FILE = "router.configLocation";
    
    /** let clocks be off by 1 minute */
    public final static long CLOCK_FUDGE_FACTOR = 1*60*1000; 

    /** used to differentiate routerInfo files on different networks */
    private static final int DEFAULT_NETWORK_ID = 2;
    private static final String PROP_NETWORK_ID = "router.networkID";
    private final int _networkID;
    
    /** coalesce stats this often - should be a little less than one minute, so the graphs get updated */
    public static final int COALESCE_TIME = 50*1000;

    /** this puts an 'H' in your routerInfo **/
    public final static String PROP_HIDDEN = "router.hiddenMode";
    /** this does not put an 'H' in your routerInfo **/
    public final static String PROP_HIDDEN_HIDDEN = "router.isHidden";
    /** New router keys at every restart. Disabled. */
    public final static String PROP_DYNAMIC_KEYS = "router.dynamicKeys";
    /**
     *  New router keys once only.
     *  @since 0.9.34
     */
    public final static String PROP_REBUILD_KEYS = "router.rebuildKeys";
    /** deprecated, use gracefulShutdownInProgress() */
    private final static String PROP_SHUTDOWN_IN_PROGRESS = "__shutdownInProgress";
    public static final String PROP_IB_RANDOM_KEY = TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + TunnelPoolSettings.PROP_RANDOM_KEY;
    public static final String PROP_OB_RANDOM_KEY = TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + TunnelPoolSettings.PROP_RANDOM_KEY;
    private static final String EVENTLOG = "eventlog.txt";
    private static final String PROP_JBIGI = "jbigi.loadedResource";
    public static final String UPDATE_FILE = "i2pupdate.zip";
        
    private static final int SHUTDOWN_WAIT_SECS = 60;

    private static final String originalTimeZoneID;
    static {
        //
        // If embedding I2P you may wish to disable one or more of the following
        // via the associated System property. Since 0.9.19.
        //
        if (System.getProperty("I2P_DISABLE_DNS_CACHE_OVERRIDE") == null) {
            // grumble about sun's java caching DNS entries *forever* by default
            // so lets just keep 'em for a short time
            String DNS_CACHE_TIME = Integer.toString(2*60*60);
            String DNS_NEG_CACHE_TIME = Integer.toString(30*60);
            System.setProperty("sun.net.inetaddr.ttl", DNS_CACHE_TIME);
            System.setProperty("sun.net.inetaddr.negative.ttl", DNS_NEG_CACHE_TIME);
            System.setProperty("networkaddress.cache.ttl", DNS_CACHE_TIME);
            System.setProperty("networkaddress.cache.negative.ttl", DNS_NEG_CACHE_TIME);
        }
        if (System.getProperty("I2P_DISABLE_HTTP_AGENT_OVERRIDE") == null) {
            System.setProperty("http.agent", "I2P");
        }
        if (System.getProperty("I2P_DISABLE_HTTP_KEEPALIVE_OVERRIDE") == null) {
            // (no need for keepalive)
            System.setProperty("http.keepAlive", "false");
        }
        // Save it for LogManager
        originalTimeZoneID = TimeZone.getDefault().getID();
        if (System.getProperty("I2P_DISABLE_TIMEZONE_OVERRIDE") == null) {
            System.setProperty("user.timezone", "GMT");
            // just in case, lets make it explicit...
            TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
        }
        // Note: I2P_DISABLE_OUTPUT_OVERRIDE implemented in startup/WorkingDir.java

        // https://www.kb.cert.org/vuls/id/402580
        // http://docs.codehaus.org/display/JETTY/SystemProperties
        // Fixed in Jetty 5.1.15 but we are running 5.1.12
        // The default is true, unfortunately it was previously
        // set to false in wrapper.config thru 0.7.10 so we must set it back here.
        // Not in Jetty 7
        //System.setProperty("org.mortbay.util.FileResource.checkAliases", "true");
    }
    
    /**
     *  Instantiation only. Starts no threads. Does not install updates.
     *  RouterContext is created but not initialized.
     *  You must call runRouter() after any constructor to start things up.
     *
     *  Config file name is "router.config" unless router.configLocation set in system properties.
     *  @throws IllegalStateException since 0.9.19 if another router with this config is running
     */
    public Router() { this(null, null); }

    /**
     *  Instantiation only. Starts no threads. Does not install updates.
     *  RouterContext is created but not initialized.
     *  You must call runRouter() after any constructor to start things up.
     *
     *  Config file name is "router.config" unless router.configLocation set in envProps or system properties.
     *
     *  @param envProps may be null
     *  @throws IllegalStateException since 0.9.19 if another router with this config is running
     */
    public Router(Properties envProps) { this(null, envProps); }

    /**
     *  Instantiation only. Starts no threads. Does not install updates.
     *  RouterContext is created but not initialized.
     *  You must call runRouter() after any constructor to start things up.
     *
     *  @param configFilename may be null
     *  @throws IllegalStateException since 0.9.19 if another router with this config is running
     */
    public Router(String configFilename) { this(configFilename, null); }

    /**
     *  Instantiation only. Starts no threads. Does not install updates.
     *  RouterContext is created but not initialized.
     *  You must call runRouter() after any constructor to start things up.
     *
     *  If configFilename is non-null, configuration is read in from there.
     *  Else if envProps is non-null, configuration is read in from the
     *  location given in the router.configLocation property.
     *  Else it's read in from the System property router.configLocation.
     *  Else from the file "router.config".
     *
     *  The most important properties are i2p.dir.base (the install directory, may be read-only)
     *  and i2p.dir.config (the user's configuration/data directory).
     *
     *  i2p.dir.base defaults to user.dir (CWD) but should almost always be set.
     *
     *  i2p.dir.config default depends on OS, user name (to detect if running as a service or not),
     *  and auto-detection of whether there appears to be previous data files in the base dir.
     *  See WorkingDir for details.
     *  If the config dir does not exist, it will be created, and files migrated from the base dir,
     *  in this constructor.
     *  If files in an existing config dir indicate that another router is already running
     *  with this directory, the constructor will delay for several seconds to be sure,
     *  and then call System.exit(-1).
     *
     *  @param configFilename may be null
     *  @param envProps may be null
     *  @throws IllegalStateException since 0.9.19 if another router with this config is running
     */
    public Router(String configFilename, Properties envProps) {
        _killVMOnEnd = true;
        _gracefulExitCode = -1;
        _config = new ConcurrentHashMap<String, String>();

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
        boolean migrateFiles = Boolean.parseBoolean(migrate);


        String isPortableStr = System.getProperty("i2p.dir.portableMode");
        boolean isPortable = Boolean.parseBoolean(isPortableStr);
        String userDir = (!isPortable) ? WorkingDir.getWorkingDir(envProps, migrateFiles) : PortableWorkingDir.getWorkingDir(envProps);

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
        // if (Boolean.parseBoolean(envProps.getProperty("router.disableIPv6")))
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
        } else if (SystemVersion.isAndroid()) {
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
        _context = new RouterContext(this, envProps, false);
        RouterContext.setGlobalContext(_context);
        _eventLog = new EventLog(_context, new File(_context.getRouterDir(), EVENTLOG));

        // This is here so that we can get the directory location from the context
        // for the ping file
        // Check for other router but do not start a thread yet so the update doesn't cause
        // a NCDFE
        for (int i = 0; i < 14; i++) {
            // Wrapper can start us up too quickly after a crash, the ping file
            // may still be less than LIVELINESS_DELAY (60s) old.
            // So wait at least 60s to be sure.
            if (isOnlyRouterRunning()) {
                if (i > 0)
                    System.err.println("INFO: No, there wasn't another router already running. Proceeding with startup.");
                break;
            }
            if (i < 13) {
                if (i == 0)
                    System.err.println("WARN: There may be another router already running. Waiting a while to be sure...");
                // yes this is ugly to sleep in the constructor.
                try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            } else {
                _eventLog.addEvent(EventLog.ABORTED, "Another router running");
                System.err.println("ERROR: There appears to be another router already running!");
                System.err.println("       Please make sure to shut down old instances before starting up");
                System.err.println("       a new one.  If you are positive that no other instance is running,");
                System.err.println("       please delete the file " + getPingFile().getAbsolutePath());
                //System.exit(-1);
                // throw exception instead, for embedded
                throw new IllegalStateException(
                                   "ERROR: There appears to be another router already running!" +
                                   " Please make sure to shut down old instances before starting up" +
                                   " a new one.  If you are positive that no other instance is running," +
                                   " please delete the file " + getPingFile().getAbsolutePath());
            }
        }

        if (_config.get("router.firstVersion") == null) {
            // These may be useful someday. First added in 0.8.2
            _config.put("router.firstVersion", RouterVersion.VERSION);
            String now = Long.toString(System.currentTimeMillis());
            _config.put("router.firstInstalled", now);
            _config.put("router.updateLastInstalled", now);
            // First added in 0.8.13
            _config.put("router.previousVersion", RouterVersion.VERSION);
            saveConfig();
        }
        int id = DEFAULT_NETWORK_ID;
        String sid = _config.get(PROP_NETWORK_ID);
        if (sid != null) {
            try {
                id = Integer.parseInt(sid);
            } catch (NumberFormatException nfe) {}
        }
        _networkID = id;
        changeState(State.INITIALIZED);
        // *********  Start no threads before here ********* //
    }

    /**
     *  Initializes the RouterContext.
     *  Starts some threads. Does not install updates.
     *  All this was in the constructor.
     *
     *  Could block for 10 seconds or forever if waiting for entropy
     *
     *  @since 0.8.12
     */
    private void startupStuff() {
        // *********  Start no threads before here ********* //
        _log = _context.logManager().getLog(Router.class);

        //
        // NOW we can start the ping file thread.
        if (!SystemVersion.isAndroid())
            beginMarkingLiveliness();

        // Apps may use this as an easy way to determine if they are in the router JVM
        // But context.isRouterContext() is even easier...
        // Both of these as of 0.7.9
        // As of 0.9.34, this is FULL_VERSION, not VERSION, which was the same as CoreVersion.VERSION
        // and thus not particularly useful.
        System.setProperty("router.version", RouterVersion.FULL_VERSION);

        // crypto init may block for 10 seconds waiting for entropy
        // we want to do this before context.initAll()
        // which will fire up several things that could block on the PRNG init
        warmupCrypto();

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
        CryptoChecker.warnUnavailableCrypto(_context);

        _routerInfo = null;
        if (_log.shouldLog(Log.INFO))
            _log.info("New router created with config file " + _configFilename);
        _oomListener = new OOMListener(_context);

        _shutdownHook = new ShutdownHook(_context);
        _gracefulShutdownDetector = new I2PAppThread(new GracefulShutdown(_context), "Graceful ShutdownHook", true);
        _gracefulShutdownDetector.setPriority(Thread.NORM_PRIORITY + 1);
        _gracefulShutdownDetector.start();
        
        _watchdog = new RouterWatchdog(_context);
        _watchdogThread = new I2PAppThread(_watchdog, "RouterWatchdog", true);
        _watchdogThread.setPriority(Thread.NORM_PRIORITY + 1);
        _watchdogThread.start();
        
    }
    
    /**
     *  Not for external use.
     *  @since 0.8.8
     */
    public static final void clearCaches() {
        ByteCache.clearAll();
        SimpleByteCache.clearAll();
        Destination.clearCache();
        Translate.clearCache();
        Hash.clearCache();
        PublicKey.clearCache();
        SigningPublicKey.clearCache();
        SigUtil.clearCaches();
        I2PSessionImpl.clearCache();
        ReusableGZIPInputStream.clearCache();
        ReusableGZIPOutputStream.clearCache();
    }

    /**
     * Configure the router to kill the JVM when the router shuts down, as well
     * as whether to explicitly halt the JVM during the hard fail process.
     *
     * Defaults to true. Set to false for embedded before calling runRouter()
     */
    public void setKillVMOnEnd(boolean shouldDie) { _killVMOnEnd = shouldDie; }

    /** @deprecated unused */
    @Deprecated
    public boolean getKillVMOnEnd() { return _killVMOnEnd; }
    
    /** @return absolute path */
    public String getConfigFilename() { return _configFilename; }

    /** @deprecated unused */
    @Deprecated
    public void setConfigFilename(String filename) { _configFilename = filename; }
    
    public String getConfigSetting(String name) { 
            return _config.get(name); 
    }

    /**
     *  Warning, race between here and saveConfig(),
     *  saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove) is recommended.
     *
     *  @since 0.8.13
     *  @deprecated use saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove)
     */
    @Deprecated
    public void setConfigSetting(String name, String value) { 
            _config.put(name, value); 
    }

    /**
     *  Warning, race between here and saveConfig(),
     *  saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove) is recommended.
     *
     *  @since 0.8.13
     *  @deprecated use saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove)
     */
    @Deprecated
    public void removeConfigSetting(String name) { 
            _config.remove(name); 
            // remove the backing default also
            _context.removeProperty(name);
    }

    /**
     *  @return unmodifiable Set, unsorted
     */
    public Set<String> getConfigSettings() { 
        return Collections.unmodifiableSet(_config.keySet()); 
    }

    /**
     *  @return unmodifiable Map, unsorted
     */
    public Map<String, String> getConfigMap() { 
        return Collections.unmodifiableMap(_config); 
    }
    
    /**
     *  Our current router info.
     *  Warning, may be null if called very early.
     *
     *  Warning - risk of deadlock - do not call while holding locks
     *
     *  Note: Due to lock contention, especially during a
     *  rebuild of the router info, this may take a long time.
     *  For determining the current status of the router, use
     *  RouterContext.commSystem().getStatus().
     */
    public RouterInfo getRouterInfo() {
        _routerInfoLock.readLock().lock();
        try {
            return _routerInfo;
        } finally {
            _routerInfoLock.readLock().unlock();
        }
    }

    /**
     *  Caller must ensure info is valid - no validation done here.
     *  Not for external use.
     *
     *  Warning - risk of deadlock - do not call while holding locks
     *
     */
    public void setRouterInfo(RouterInfo info) { 
        _routerInfoLock.writeLock().lock();
        try {
            _routerInfo = info; 
        } finally {
            _routerInfoLock.writeLock().unlock();
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("setRouterInfo() : " + info, new Exception("I did it"));
        if (info != null)
            _context.jobQueue().addJob(new PersistRouterInfoJob(_context));
    }

    /**
     *  Used only by routerconsole.. to be deprecated?
     *  @return System time, NOT context time
     */
    public long getWhenStarted() { return _started; }

    /**
     * Wall clock uptime.
     * This uses System time, NOT context time, so context clock shifts will
     * not affect it. This is important if NTP fails and the
     * clock then shifts from a SSU peer source just after startup.
     */
    public long getUptime() { 
        if (_started <= 0) return 1000; // racing on startup
        return Math.max(1000, System.currentTimeMillis() - _started);
    }

    /**
     *  The network ID. Default 2.
     *  May be changed with the config property router.networkID (restart required).
     *  Change only if running a test network to prevent cross-network contamination.
     *  @since 0.9.25
     */
    public int getNetworkID() { return _networkID; }
    
    /**
     *  Non-null, but take care when accessing context items before runRouter() is called
     *  as the context will not be initialized.
     *
     *  @return non-null
     */
    public RouterContext getContext() { return _context; }
    
    /**
     *  This must be called after instantiation.
     *  Starts the threads. Does not install updates.
     *  This is for embedded use.
     *  Standard standalone installation uses main() instead, which
     *  checks for updates and then calls this.
     *
     *  This may take quite a while, especially if NTP fails
     *  or the system lacks entropy
     *
     *  @since public as of 0.9 for Android and other embedded uses
     *  @throws IllegalStateException if called more than once
     */
    public synchronized void runRouter() {
        synchronized(_stateLock) {
            if (_state != State.INITIALIZED)
                throw new IllegalStateException();
            changeState(State.STARTING_1);
        }
        String last = _config.get("router.previousFullVersion");
        if (last != null) {
            _eventLog.addEvent(EventLog.UPDATED, "from " + last + " to " + RouterVersion.FULL_VERSION);
            saveConfig("router.previousFullVersion", null);
        }
        _eventLog.addEvent(EventLog.STARTED, RouterVersion.FULL_VERSION);
        startupStuff();
        changeState(State.STARTING_2);
        _started = System.currentTimeMillis();
        try {
            Runtime.getRuntime().addShutdownHook(_shutdownHook);
        } catch (IllegalStateException ise) {}
        if (!SystemVersion.isAndroid())
            I2PThread.addOOMEventListener(_oomListener);
        
        // message handlers
        _context.inNetMessagePool().registerHandlerJobBuilder(GarlicMessage.MESSAGE_TYPE, new GarlicMessageHandler(_context));

        //if (ALLOW_DYNAMIC_KEYS) {
        //    if ("true".equalsIgnoreCase(_context.getProperty(Router.PROP_HIDDEN, "false")))
        //        killKeys();
        //}
        if (_context.getBooleanProperty(PROP_REBUILD_KEYS))
            killKeys();

        _context.messageValidator().startup();
        _context.tunnelDispatcher().startup();
        _context.inNetMessagePool().startup();
        _context.jobQueue().runQueue(1);
        //_context.jobQueue().addJob(new CoalesceStatsJob(_context));
        _context.simpleTimer2().addPeriodicEvent(new CoalesceStatsEvent(_context), COALESCE_TIME);
        _context.jobQueue().addJob(new UpdateRoutingKeyModifierJob(_context));
        //_context.adminManager().startup();
        _context.blocklist().startup();

        synchronized(_configFileLock) {
            // persistent key for peer ordering since 0.9.17
            // These will be replaced in CreateRouterInfoJob if we rekey
            if (!_config.containsKey(PROP_IB_RANDOM_KEY)) {
                byte rk[] = new byte[32];
                _context.random().nextBytes(rk);
                _config.put(PROP_IB_RANDOM_KEY, Base64.encode(rk));
                _context.random().nextBytes(rk);
                _config.put(PROP_OB_RANDOM_KEY, Base64.encode(rk));
                saveConfig();
            }
        }
        
        // let the timestamper get us sync'ed
        // this will block for quite a while on a disconnected machine
        long before = System.currentTimeMillis();
        _context.clock().getTimestamper().waitForInitialization();
        long waited = System.currentTimeMillis() - before;
        if (_log.shouldLog(Log.INFO))
            _log.info("Waited " + waited + "ms to initialize");

        changeState(State.STARTING_3);
        _context.jobQueue().addJob(new StartupJob(_context));
    }
    
    /**
     * This updates the config with all settings found in the file.
     * It does not clear the config first, so settings not found in
     * the file will remain in the config.
     *
     * This is synchronized with saveConfig().
     * Not for external use.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void readConfig() {
        synchronized(_configFileLock) {
            String f = getConfigFilename();
            Properties config = getConfig(_context, f);
            // to avoid compiler errror
            Map foo = _config;
            foo.putAll(config);
        }
    }
    
    /**
     *  this does not use ctx.getConfigDir(), must provide a full path in filename
     *  Caller must synchronize
     *
     *  @param ctx will be null at startup when called from constructor
     */
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
                props.remove(PROP_SHUTDOWN_IN_PROGRESS);
            } else {
                if (log != null)
                    log.warn("Configuration file " + filename + " does not exist");
                // normal not to exist at first install
                //else
                //    System.err.println("WARNING: Configuration file " + filename + " does not exist");
            }
        } catch (IOException ioe) {
            if (log != null)
                log.error("Error loading the router configuration from " + filename, ioe);
            else
                System.err.println("Error loading the router configuration from " + filename + ": " + ioe);
        }
        return props;
    }

    ////////// begin state management
    
    /**
     *  Startup / shutdown states
     *
     *  @since 0.9.18
     */
    private enum State {
        UNINITIALIZED,
        /** constructor complete */
        INITIALIZED,
        /** runRouter() called */
        STARTING_1,
        /** startupStuff() complete, most of the time here is NTP */
        STARTING_2,
        /** NTP done, Job queue started, StartupJob queued, runRouter() returned */
        STARTING_3,
        /** RIs loaded. From STARTING_3 */
        NETDB_READY,
        /** Non-zero-hop expl. tunnels built. From STARTING_3 */
        EXPL_TUNNELS_READY,
        /** from NETDB_READY or EXPL_TUNNELS_READY */
        RUNNING,
        /**
         *  A "soft" restart, primarily of the comm system, after
         *  a port change or large step-change in system time.
         *  Does not stop the whole JVM, so it is safe even in the absence
         *  of the wrapper.
         *  This is not a graceful restart - all peer connections are dropped immediately.
         */
        RESTARTING,
        /** cancellable shutdown has begun */
        GRACEFUL_SHUTDOWN,
        /** In shutdown(). Non-cancellable shutdown has begun */
        FINAL_SHUTDOWN_1,
        /** In shutdown2(). Killing everything */
        FINAL_SHUTDOWN_2,
        /** In finalShutdown(). Final cleanup */
        FINAL_SHUTDOWN_3,
        /** all done */
        STOPPED
    }

    /**
     *  For efficiency. EnumSets are bitmasks.
     *  @since 0.9.34
     */
    private static final Set<State> STATES_ALIVE =
        EnumSet.of(State.RUNNING, State.GRACEFUL_SHUTDOWN, State.STARTING_1, State.STARTING_2,
                   State.STARTING_3, State.NETDB_READY, State.EXPL_TUNNELS_READY);

    private static final Set<State> STATES_GRACEFUL =
        EnumSet.of(State.GRACEFUL_SHUTDOWN, State.FINAL_SHUTDOWN_1, State.FINAL_SHUTDOWN_2,
                   State.FINAL_SHUTDOWN_3, State.STOPPED);

    private static final Set<State> STATES_FINAL =
        EnumSet.of(State.FINAL_SHUTDOWN_1, State.FINAL_SHUTDOWN_2, State.FINAL_SHUTDOWN_3, State.STOPPED);
    
    /**
     *  @since 0.9.18
     */
    private void changeState(State state) {
        State oldState;
        synchronized(_stateLock) {
            oldState = _state;
            _state = state;
        }
        if (_log != null && oldState != state && state != State.STOPPED && _log.shouldLog(Log.WARN)) {
            _log.warn("Router state change from " + oldState + " to " + state /* , new Exception() */ );
            //for debugging
            _context.logManager().flush();
        }
    }

    /**
     *  True during the initial start, but false during a soft restart.
     */
    public boolean isAlive() {
        synchronized(_stateLock) {
            return STATES_ALIVE.contains(_state);
        }
    }

    /**
     *  Only for Restarter, after soft restart is complete.
     *  Not for external use.
     *  @since 0.8.12
     */
    public void setIsAlive() {
        changeState(State.RUNNING);
    }

    /**
     *  Only for NetDB, after RIs are loaded.
     *  Not for external use.
     *  @since 0.9.18
     */
    public void setNetDbReady() {
        synchronized(_stateLock) {
            if (_state == State.STARTING_3)
                changeState(State.NETDB_READY);
            else if (_state == State.EXPL_TUNNELS_READY)
                changeState(State.RUNNING);
        }
    }

    /**
     *  Only for Tunnel Building, after we have non-zero-hop expl. tunnels.
     *  Not for external use.
     *  @since 0.9.18
     */
    public void setExplTunnelsReady() {
        synchronized(_stateLock) {
            if (_state == State.STARTING_3)
                changeState(State.EXPL_TUNNELS_READY);
            else if (_state == State.NETDB_READY)
                changeState(State.RUNNING);
        }
    }

    /**
     * Is a graceful shutdown in progress? This may be cancelled.
     * Note that this also returns true if an uncancellable final shutdown is in progress.
     */
    public boolean gracefulShutdownInProgress() {
        synchronized(_stateLock) {
            return STATES_GRACEFUL.contains(_state);
        }
    }

    /**
     * Is a final shutdown in progress? This may not be cancelled.
     * @since 0.8.12
     */
    public boolean isFinalShutdownInProgress() {
        synchronized(_stateLock) {
            return STATES_FINAL.contains(_state);
        }
    }

    ////////// end state management

    /**
     * Rebuild and republish our routerInfo since something significant 
     * has changed.
     * This is a non-blocking rebuild.
     *
     * Not for external use.
     *
     * Warning - risk of deadlock - do not call while holding locks
     *
     */
    public void rebuildRouterInfo() { rebuildRouterInfo(false); }

    /**
     * Rebuild and republish our routerInfo since something significant 
     * has changed.
     * Not for external use.
     *
     *  Warning - risk of deadlock - do not call while holding locks
     *
     * @param blockingRebuild If true, netdb publish will happen in-line.
     *                        This may take a long time.
     */
    public void rebuildRouterInfo(boolean blockingRebuild) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Rebuilding new routerInfo");
        _routerInfoLock.writeLock().lock();
        try {
            locked_rebuildRouterInfo(blockingRebuild);
        } finally {
            _routerInfoLock.writeLock().unlock();
        }
    }
        

    /**
     * Rebuild and republish our routerInfo since something significant 
     * has changed.
     */
    private void locked_rebuildRouterInfo(boolean blockingRebuild) {
        RouterInfo ri;
        if (_routerInfo != null)
            ri = new RouterInfo(_routerInfo);
        else
            ri = new RouterInfo();
        
        try {
            ri.setPublished(_context.clock().now());
            Properties stats = _context.statPublisher().publishStatistics();
            
            ri.setOptions(stats);
            // deadlock thru createAddresses() thru SSU REA... move outside lock?
            ri.setAddresses(_context.commSystem().createAddresses());

            SigningPrivateKey key = _context.keyManager().getSigningPrivateKey();
            if (key == null) {
                _log.log(Log.CRIT, "Internal error - signing private key not known? Impossible?");
                return;
            }
            ri.sign(key);
            setRouterInfo(ri);
            if (!ri.isValid())
                throw new DataFormatException("Our RouterInfo has a bad signature");
            Republish r = new Republish(_context);
            if (blockingRebuild)
                r.timeReached();
            else
                _context.simpleTimer2().addEvent(r, 0);
        } catch (DataFormatException dfe) {
            _log.log(Log.CRIT, "Internal error - unable to sign our own address?!", dfe);
        }
    }

    /**
     *  Family Key Crypto Signer / Verifier.
     *  Not for external use.
     *  If family key is set, first call Will take a while to generate keys.
     *  Warning - risk of deadlock - do not call while holding locks
     *  (other than routerInfoLock)
     *
     *  @return null on initialization failure
     *  @since 0.9.24
     */
    public FamilyKeyCrypto getFamilyKeyCrypto() {
        synchronized (_familyKeyLock) {
            if (_familyKeyCrypto == null) {
                if (!_familyKeyCryptoFail) {
                    try {
                        _familyKeyCrypto = new FamilyKeyCrypto(_context);
                    } catch (GeneralSecurityException gse) {
                        _log.error("Failed to initialize family key crypto", gse);
                        _familyKeyCryptoFail = true;
                    }
                }
            }
        }
        return _familyKeyCrypto;
    }

    // publicize our ballpark capacity
    public static final char CAPABILITY_BW12 = 'K';
    public static final char CAPABILITY_BW32 = 'L';
    public static final char CAPABILITY_BW64 = 'M';
    public static final char CAPABILITY_BW128 = 'N';
    public static final char CAPABILITY_BW256 = 'O';
    /** @since 0.9.18 */
    public static final char CAPABILITY_BW512 = 'P';
    /** @since 0.9.18 */
    public static final char CAPABILITY_BW_UNLIMITED = 'X';
    /** for testing */
    public static final String PROP_FORCE_BWCLASS = "router.forceBandwidthClass";
    
    public static final char CAPABILITY_REACHABLE = 'R';
    public static final char CAPABILITY_UNREACHABLE = 'U';
    /** for testing */
    public static final String PROP_FORCE_UNREACHABLE = "router.forceUnreachable";

    /** @deprecated unused */
    @Deprecated
    public static final char CAPABILITY_NEW_TUNNEL = 'T';
    
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_K = 0;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_L = 12;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_M = 48;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_N = 64;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_O = 128;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_P = 256;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_X = 2000;

    /**
     *  The current bandwidth class.
     *  For building our RI. Not for external use.
     *
     *  @return a character to be added to the RI, one of "KLMNOPX"
     *  @since 0.9.31
     */
    public char getBandwidthClass() {
        int bwLim = Math.min(_context.bandwidthLimiter().getInboundKBytesPerSecond(),
                             _context.bandwidthLimiter().getOutboundKBytesPerSecond());
        bwLim = (int)(bwLim * getSharePercentage());
        
        String force = _context.getProperty(PROP_FORCE_BWCLASS);
        if (force != null && force.length() > 0) {
            return force.charAt(0);
        } else if (bwLim < MIN_BW_L) {
            return CAPABILITY_BW12;
        } else if (bwLim <= MIN_BW_M) {
            return CAPABILITY_BW32;
        } else if (bwLim <= MIN_BW_N) {
            return CAPABILITY_BW64;
        } else if (bwLim <= MIN_BW_O) {
            return CAPABILITY_BW128;
        } else if (bwLim <= MIN_BW_P) {
            return CAPABILITY_BW256;
        } else if (bwLim <= MIN_BW_X) {    // TODO adjust threshold
            // 512 supported as of 0.9.18;
            return CAPABILITY_BW512;
        } else {
            // Unlimited supported as of 0.9.18;
            return CAPABILITY_BW_UNLIMITED;
        }
    }
        
    /**
     *  For building our RI. Not for external use.
     *
     *  @return a capabilities string to be added to the RI
     */
    public String getCapabilities() {
        StringBuilder rv = new StringBuilder(4);
        char bw = getBandwidthClass();
        rv.append(bw);
        // 512 and unlimited supported as of 0.9.18;
        // Add 256 as well for compatibility
        if (bw == CAPABILITY_BW512 || bw == CAPABILITY_BW_UNLIMITED)
            rv.append(CAPABILITY_BW256);

        // if prop set to true, don't tell people we are ff even if we are
        if (_context.netDb().floodfillEnabled() &&
            !_context.getBooleanProperty("router.hideFloodfillParticipant"))
            rv.append(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        
        if(_context.getBooleanProperty(PROP_HIDDEN))
            rv.append(RouterInfo.CAPABILITY_HIDDEN);
        
        if (_context.getBooleanProperty(PROP_FORCE_UNREACHABLE)) {
            rv.append(CAPABILITY_UNREACHABLE);
            return rv.toString();
        }
        switch (_context.commSystem().getStatus()) {
            case OK:
            case IPV4_OK_IPV6_UNKNOWN:
            case IPV4_OK_IPV6_FIREWALLED:
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_DISABLED_IPV6_OK:
            case IPV4_UNKNOWN_IPV6_OK:
            case IPV4_SNAT_IPV6_OK:
                rv.append(CAPABILITY_REACHABLE);
                break;

            case DIFFERENT:
            case REJECT_UNSOLICITED:
            case IPV4_DISABLED_IPV6_FIREWALLED:
                rv.append(CAPABILITY_UNREACHABLE);
                break;

            case DISCONNECTED:
            case HOSED:
            case UNKNOWN:
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
            case IPV4_DISABLED_IPV6_UNKNOWN:
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
            case IPV4_SNAT_IPV6_UNKNOWN:
            default:
                // no explicit capability
                break;
        }
        return rv.toString();
    }
    
    /*
     *  This checks the config only. We don't check the current RI
     *  due to deadlocks.
     *
     */
    public boolean isHidden() {
        //RouterInfo ri;
        //synchronized (_routerInfoLock) {
        //    ri = _routerInfo;
        //}
        //if ( (ri != null) && (ri.isHidden()) )
        //    return true;
        if (_context.getBooleanProperty(PROP_HIDDEN))
            return true;
        String h = _context.getProperty(PROP_HIDDEN_HIDDEN);
        if (h != null)
            return Boolean.parseBoolean(h);
        return _context.commSystem().isInBadCountry();
    }
    
    /**
     *  @since 0.9.3
     */
    public EventLog eventLog() {
        return _eventLog;
    }
    
    /**
     * Ugly list of files that we need to kill if we are building a new identity
     *
     */
    private static final String _rebuildFiles[] = new String[] {
        CreateRouterInfoJob.INFO_FILENAME,
        CreateRouterInfoJob.KEYS_FILENAME,
        CreateRouterInfoJob.KEYS2_FILENAME,
        "netDb/my.info",      // no longer used
        "connectionTag.keys", // never used?
        KeyManager.DEFAULT_KEYDIR + '/' + KeyManager.KEYFILE_PRIVATE_ENC,
        KeyManager.DEFAULT_KEYDIR + '/' + KeyManager.KEYFILE_PUBLIC_ENC,
        KeyManager.DEFAULT_KEYDIR + '/' + KeyManager.KEYFILE_PRIVATE_SIGNING,
        KeyManager.DEFAULT_KEYDIR + '/' + KeyManager.KEYFILE_PUBLIC_SIGNING,
        "sessionKeys.dat"     // no longer used
    };

    /**
     *  Not for external use.
     */
    public void killKeys() {
        //new Exception("Clearing identity files").printStackTrace();
        for (int i = 0; i < _rebuildFiles.length; i++) {
            File f = new File(_context.getRouterDir(),_rebuildFiles[i]);
            if (f.exists()) {
                boolean removed = f.delete();
                if (removed) {
                    System.out.println("INFO:  Removing old identity file: " + _rebuildFiles[i]);
                } else {
                    System.out.println("ERROR: Could not remove old identity file: " + _rebuildFiles[i]);
                }
            }
        }

        // now that we have random ports, keeping the same port would be bad
        synchronized(_configFileLock) {
            removeConfigSetting(UDPTransport.PROP_INTERNAL_PORT);
            removeConfigSetting(UDPTransport.PROP_EXTERNAL_PORT);
            removeConfigSetting(PROP_IB_RANDOM_KEY);
            removeConfigSetting(PROP_OB_RANDOM_KEY);
            removeConfigSetting(PROP_REBUILD_KEYS);
            saveConfig();
        }
    }

    /**
     * Rebuild a new identity the hard way - delete all of our old identity 
     * files, then reboot the router.
     *
     *  Calls exit(), never returns.
     *
     *  Not for external use.
     */
    public synchronized void rebuildNewIdentity() {
        if (_shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(_shutdownHook);
            } catch (IllegalStateException ise) {}
        }
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
    
    /**
     *  Could block for 10 seconds or forever
     */
    private void warmupCrypto() {
        _context.random().nextBoolean();
        // Instantiate to fire up the YK refiller thread
        _context.elGamalEngine();
        String loaded = NativeBigInteger.getLoadedResourceName();
        if (loaded != null)
            saveConfig(PROP_JBIGI, loaded);
    }
    
    /** shut down after all tunnels are gone */
    public static final int EXIT_GRACEFUL = 2;
    /** shut down immediately */
    public static final int EXIT_HARD = 3;
    /** shut down immediately */
    public static final int EXIT_OOM = 10;
    /** shut down immediately, and tell the wrapper to restart */
    public static final int EXIT_HARD_RESTART = 4;
    /** shut down after all tunnels are gone, and tell the wrapper to restart */
    public static final int EXIT_GRACEFUL_RESTART = 5;
    
    /**
     *  Shutdown with no chance of cancellation.
     *  Blocking, will call exit() and not return unless setKillVMOnExit(false) was previously called,
     *  or a final shutdown is already in progress.
     *  May take several seconds as it runs all the shutdown hooks.
     *
     *  @param exitCode one of the EXIT_* values, non-negative
     *  @throws IllegalArgumentException if exitCode negative
     */
    public synchronized void shutdown(int exitCode) {
        if (exitCode < 0)
            throw new IllegalArgumentException();
        synchronized(_stateLock) {
            if (_state == State.FINAL_SHUTDOWN_1 ||
                _state == State.FINAL_SHUTDOWN_2 ||
                _state == State.FINAL_SHUTDOWN_3 ||
                _state == State.STOPPED)
                return;
            changeState(State.FINAL_SHUTDOWN_1);
        }
        _context.throttle().setShutdownStatus();
        if (_shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(_shutdownHook);
            } catch (IllegalStateException ise) {}
        }
        shutdown2(exitCode);
    }

    /**
     *  Cancel the JVM runtime hook before calling this.
     *  Called by the ShutdownHook.
     *  NOT to be called by others, use shutdown().
     *
     *  @param exitCode one of the EXIT_* values, non-negative
     *  @throws IllegalArgumentException if exitCode negative
     */
    public synchronized void shutdown2(int exitCode) {
        if (exitCode < 0)
            throw new IllegalArgumentException();
        changeState(State.FINAL_SHUTDOWN_2);
        // help us shut down esp. after OOM
        int priority = (exitCode == EXIT_OOM) ? Thread.MAX_PRIORITY - 1 : Thread.NORM_PRIORITY + 2;
        Thread.currentThread().setPriority(priority);
        _log.log(Log.CRIT, "Starting final shutdown(" + exitCode + ')');
        // So we can get all the way to the end
        // No, you can't do Thread.currentThread.setDaemon(false)
        if (_killVMOnEnd) {
            try {
                (new Spinner()).start();
            } catch (Throwable t) {}
        }
        ((RouterClock) _context.clock()).removeShiftListener(this);
        _context.random().saveSeed();
        I2PThread.removeOOMEventListener(_oomListener);
        // Run the shutdown hooks first in case they want to send some goodbye messages
        // Maybe we need a delay after this too?
        LinkedList<Thread> tasks = new LinkedList<Thread>();
        for (Runnable task : _context.getShutdownTasks()) {
            //System.err.println("Running shutdown task " + task.getClass());
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running shutdown task " + task.getClass());
            try {
                //task.run();
                Thread t = new I2PAppThread(task, "Shutdown task " + task.getClass().getName());
                t.setDaemon(true);
                t.start();
                tasks.add(t);
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Error running shutdown task", t);
            }
        }
        long waitSecs = SHUTDOWN_WAIT_SECS;
        if (SystemVersion.isARM())
            waitSecs *= 2;
        final long maxWait = System.currentTimeMillis() + (waitSecs *1000);
        Thread th;
        while ((th = tasks.poll()) != null) {
            long toWait = maxWait - System.currentTimeMillis();
            if (toWait <= 0) {
                _log.logAlways(Log.WARN, "Shutdown tasks took more than " + waitSecs + " seconds to run");
                tasks.clear();
                break;
            }
            try {
                th.join(toWait);
            } catch (InterruptedException ie) {}
            if (th.isAlive()) {
                _log.logAlways(Log.WARN, "Shutdown task took more than " + waitSecs + " seconds to run: " + th.getName());
                tasks.clear();
                break;
            } else if (_log.shouldInfo()) {
                _log.info("Shutdown task complete: " + th.getName());
            }
        }

        // Set the last version to the current version, since 0.8.13
        if (!RouterVersion.VERSION.equals(_config.get("router.previousVersion"))) {
            saveConfig("router.previousVersion", RouterVersion.VERSION);
        }

        _context.removeShutdownTasks();
        try { _context.clientManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the client manager", t); }
        try { _context.namingService().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the naming service", t); }
        try { _context.jobQueue().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the job queue", t); }
        try { _context.tunnelManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the tunnel manager", t); }
        try { _context.tunnelDispatcher().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the tunnel dispatcher", t); }
        try { _context.netDb().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the networkDb", t); }
        try { _context.commSystem().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the comm system", t); }
        try { _context.bandwidthLimiter().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the comm system", t); }
        try { _context.peerManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the peer manager", t); }
        try { _context.messageRegistry().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the message registry", t); }
        try { _context.messageValidator().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the message validator", t); }
        try { _context.inNetMessagePool().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the inbound net pool", t); }
        try { _context.clientMessagePool().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the client msg pool", t); }
        try { _context.sessionKeyManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the session key manager", t); }
        try { _context.messageHistory().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the message history logger", t); }
        // do stat manager last to reduce chance of NPEs in other threads
        try { _context.statManager().shutdown(); } catch (Throwable t) { _log.error("Error shutting down the stats manager", t); }
        _context.deleteTempDir();
        List<RouterContext> contexts = RouterContext.getContexts();
        contexts.remove(_context);

        // shut down I2PAppContext tasks here

        try {
            _context.elGamalEngine().shutdown();
        } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting elGamal", t); }

        if (contexts.isEmpty()) {
            // any thing else to shut down?
        } else {
            _log.logAlways(Log.WARN, "Warning - " + contexts.size() + " routers remaining in this JVM, not releasing all resources");
        }
        try {
            ((FortunaRandomSource)_context.random()).shutdown();
        } catch (Throwable t) { _log.log(Log.CRIT, "Error shutting random()", t); }

        // logManager shut down in finalShutdown()
        _watchdog.shutdown();
        _watchdogThread.interrupt();
        _eventLog.addEvent(EventLog.STOPPED, Integer.toString(exitCode));
        finalShutdown(exitCode);
    }

    /**
     * disable dynamic key functionality for the moment, as it may be harmful and doesn't
     * add meaningful anonymity
     */
    private static final boolean ALLOW_DYNAMIC_KEYS = false;

    /**
     *  Cancel the JVM runtime hook before calling this.
     *
     *  @param exitCode one of the EXIT_* values, non-negative
     */
    private synchronized void finalShutdown(int exitCode) {
        changeState(State.FINAL_SHUTDOWN_3);
        clearCaches();
        _log.log(Log.CRIT, "Shutdown(" + exitCode + ") complete"  /* , new Exception("Shutdown") */ );
        try { _context.logManager().shutdown(); } catch (Throwable t) { }
        if (ALLOW_DYNAMIC_KEYS) {
            if (_context.getBooleanProperty(PROP_DYNAMIC_KEYS))
                killKeys();
        }

        File f = getPingFile();
        f.delete();
        if (RouterContext.getContexts().isEmpty())
            RouterContext.killGlobalContext();

        // Since 0.8.8, for Android and the wrapper
        for (Runnable task : _context.getFinalShutdownTasks()) {
            //System.err.println("Running final shutdown task " + task.getClass());
            try {
                task.run();
            } catch (Throwable t) {
                System.err.println("Running final shutdown task " + t);
            }
        }
        _context.getFinalShutdownTasks().clear();

        if (_killVMOnEnd) {
            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            //Runtime.getRuntime().halt(exitCode);
            // allow the Runtime shutdown hooks to execute
            Runtime.getRuntime().exit(exitCode);
        } else if (SystemVersion.isAndroid()) {
            Runtime.getRuntime().gc();
        }
        changeState(State.STOPPED);
    }
    
    /**
     * Non-blocking shutdown.
     *
     * Call this if we want the router to kill itself as soon as we aren't 
     * participating in any more tunnels (etc).  This will not block and doesn't
     * guarantee any particular time frame for shutting down.  To shut the 
     * router down immediately, use {@link #shutdown}.  If you want to cancel
     * the graceful shutdown (prior to actual shutdown ;), call 
     * {@link #cancelGracefulShutdown}.
     *
     * Exit code will be EXIT_GRACEFUL.
     *
     * Shutdown delay will be from zero to 11 minutes.
     */
    public void shutdownGracefully() {
        shutdownGracefully(EXIT_GRACEFUL);
    }

    /**
     * Non-blocking shutdown.
     *
     * Call this with EXIT_HARD or EXIT_HARD_RESTART for a non-blocking,
     * hard, non-graceful shutdown with a brief delay to allow a UI response
     *
     * Returns silently if a final shutdown is already in progress.
     *
     * @param exitCode one of the EXIT_* values, non-negative
     * @throws IllegalArgumentException if exitCode negative
     */
    public void shutdownGracefully(int exitCode) {
        if (exitCode < 0)
            throw new IllegalArgumentException();
        synchronized(_stateLock) {
            if (isFinalShutdownInProgress())
                return; // too late
            changeState(State.GRACEFUL_SHUTDOWN);
            _gracefulExitCode = exitCode;
        }
        //_config.put(PROP_SHUTDOWN_IN_PROGRESS, "true");
        _context.throttle().setShutdownStatus();
        synchronized (_gracefulShutdownDetector) {
            _gracefulShutdownDetector.notifyAll();
        }
    }
    
    /**
     * Cancel any prior request to shut the router down gracefully.
     *
     * Returns silently if a final shutdown is already in progress.
     */
    public void cancelGracefulShutdown() {
        synchronized(_stateLock) {
            if (isFinalShutdownInProgress())
                return; // too late
            changeState(State.RUNNING);
            _gracefulExitCode = -1;
        }
        //_config.remove(PROP_SHUTDOWN_IN_PROGRESS);
        _context.throttle().cancelShutdownStatus();
        synchronized (_gracefulShutdownDetector) {
            _gracefulShutdownDetector.notifyAll();
        }        
    }

    /**
     * What exit code do we plan on using when we shut down (or -1, if there isn't a graceful shutdown planned)
     *
     * @return one of the EXIT_* values or -1
     */
    public int scheduledGracefulExitCode() {
        synchronized(_stateLock) {
            return _gracefulExitCode;
        }
    }

    /**
     *  How long until the graceful shutdown will kill us?
     *  @return -1 if no shutdown in progress.
     */
    public long getShutdownTimeRemaining() {
        synchronized(_stateLock) {
            if (_gracefulExitCode <= 0) return -1; // maybe Long.MAX_VALUE would be better?
            if (_gracefulExitCode == EXIT_HARD || _gracefulExitCode == EXIT_HARD_RESTART)
                return 0;
        }
        long exp = _context.tunnelManager().getLastParticipatingExpiration();
        if (exp < 0)
            return 0;
        else
            return Math.max(0, exp + 2*CLOCK_FUDGE_FACTOR - _context.clock().now());
    }
    
    /**
     * Save the current config options (returning true if save was 
     * successful, false otherwise)
     *
     * Synchronized with file read in getConfig()
     */
    public boolean saveConfig() {
        try {
            Properties ordered = new OrderedProperties();
            synchronized(_configFileLock) {
                ordered.putAll(_config);
                DataHelper.storeProps(ordered, new File(_configFilename));
            }
        } catch (IOException ioe) {
                // warning, _log will be null when called from constructor
                if (_log != null)
                    _log.error("Error saving the config to " + _configFilename, ioe);
                else
                    System.err.println("Error saving the config to " + _configFilename + ": " + ioe);
                return false;
        }
        return true;
    }
    
    /**
     * Updates the current config with the given key/value and then saves it.
     * Prevents a race in the interval between setConfigSetting() / removeConfigSetting() and saveConfig(),
     * Synchronized with getConfig() / saveConfig()
     *
     * @param name setting to add/change/remove before saving
     * @param value if non-null, updated value; if null, setting will be removed
     * @return success
     * @since 0.8.13
     */
    public boolean saveConfig(String name, String value) {
        synchronized(_configFileLock) {
            if (value != null)
                _config.put(name, value);
            else
                removeConfigSetting(name);
            return saveConfig();
        }
    }

    /**
     * Updates the current config and then saves it.
     * Prevents a race in the interval between setConfigSetting() / removeConfigSetting() and saveConfig(),
     * Synchronized with getConfig() / saveConfig()
     *
     * @param toAdd settings to add/change before saving, may be null or empty
     * @param toRemove settings to remove before saving, may be null or empty
     * @return success
     * @since 0.8.13
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public boolean saveConfig(Map toAdd, Collection<String> toRemove) {
        synchronized(_configFileLock) {
            if (toAdd != null)
                _config.putAll(toAdd);
            if (toRemove != null) {
                for (String s : toRemove) {
                    removeConfigSetting(s);
                }
            }
            return saveConfig();
        }
    }

    /**
     *  The clock shift listener.
     *  Restart the router if we should.
     *
     *  @since 0.8.8
     */
    public void clockShift(long delta) {
        if (delta > -60*1000 && delta < 60*1000)
            return;
        synchronized(_stateLock) {
            if (gracefulShutdownInProgress() || !isAlive())
                return;
        }
        _eventLog.addEvent(EventLog.CLOCK_SHIFT, Long.toString(delta));
        // update the routing key modifier
        _context.routerKeyGenerator().generateDateBasedModData();
        // Commented because this check makes no sense (#1014)
        // .. and 'active' is relative to our broken time.
        //if (_context.commSystem().countActivePeers() <= 0)
        //    return;
        if (delta > 0)
            _log.error("Restarting after large clock shift forward by " + DataHelper.formatDuration(delta));
        else
            _log.error("Restarting after large clock shift backward by " + DataHelper.formatDuration(0 - delta));
        restart();
    }

    /**
     *  A "soft" restart, primarily of the comm system, after
     *  a port change or large step-change in system time.
     *  Does not stop the whole JVM, so it is safe even in the absence
     *  of the wrapper.
     *  This is not a graceful restart - all peer connections are dropped immediately.
     *
     *  As of 0.8.8, this returns immediately and does the actual restart in a separate thread.
     *  Poll isAlive() if you need to know when the restart is complete.
     *
     *  Not recommended for external use.
     */
    public synchronized void restart() {
        synchronized(_stateLock) {
            if (gracefulShutdownInProgress() || !isAlive())
                return;
            changeState(State.RESTARTING);
        }
        ((RouterClock) _context.clock()).removeShiftListener(this);
        // Let's not stop accepting tunnels, etc
        //_started = _context.clock().now();
        Thread t = new I2PThread(new Restarter(_context), "Router Restart");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        t.start();
    }    

    /**
     *  Usage: Router [rebuild]
     *  No other options allowed, for now
     *  Instantiates Router(), and either installs updates and exits,
     *  or calls runRouter().
     *
     *  Not recommended for embedded use.
     *  Applications bundling I2P should instantiate a Router and call runRouter().
     *
     *  @param args null ok
     *  @throws IllegalArgumentException
     */
    public static void main(String args[]) {
        boolean rebuild = false;
        if (args != null) {
            boolean error = false;
            Getopt g = new Getopt("router", args, "");
            int c;
            while ((c = g.getopt()) != -1) {
                switch (c) {
                    default:
                        error = true;
                }
            }
            int remaining = args.length - g.getOptind();
            if (remaining > 1) {
                error = true;
            } else if (remaining == 1) {
                rebuild = args[g.getOptind()].equals("rebuild");;
                if (!rebuild)
                    error = true;
            }
            if (error)
                throw new IllegalArgumentException();
        }

        System.out.println("Starting I2P " + RouterVersion.FULL_VERSION);
        //verifyWrapperConfig();
        Router r;
        try {
            r = new Router();
        } catch (IllegalStateException ise) {
            System.exit(-1);
            return;
        }
        if (rebuild) {
            r.rebuildNewIdentity();
        } else {
            // This is here so that we can get the directory location from the context
            // for the zip file and the base location to unzip to.
            // If it does an update, it never returns.
            // I guess it's better to have the other-router check above this, we don't want to
            // overwrite an existing running router's jar files. Other than ours.
            InstallUpdate.installUpdates(r);
            // *********  Start no threads before here ********* //
            r.runRouter();
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
    
    private static final long LIVELINESS_DELAY = 60*1000;
    
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
                System.err.println("WARN: Old router was not shut down gracefully, deleting " + f);
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
        _context.simpleTimer2().addPeriodicEvent(new MarkLiveliness(this, f), 0, LIVELINESS_DELAY - (5*1000));
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
        String pct = _context.getProperty(PROP_BANDWIDTH_SHARE_PERCENTAGE);
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

    /**
     *  Max of inbound and outbound rate in bytes per second
     */
    public int get1sRate() { return get1sRate(false); }

    /**
     *  When outboundOnly is false, outbound rate in bytes per second.
     *  When true, max of inbound and outbound rate in bytes per second.
     */
    public int get1sRate(boolean outboundOnly) {
            FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
                int out = (int)bw.getSendBps();
                if (outboundOnly)
                    return out;
                return (int)Math.max(out, bw.getReceiveBps());
    }

    /**
     *  Inbound rate in bytes per second
     */
    public int get1sRateIn() {
            FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
                return (int) bw.getReceiveBps();
    }

    /**
     *  Max of inbound and outbound rate in bytes per second
     */
    public int get15sRate() { return get15sRate(false); }

    /**
     *  When outboundOnly is false, outbound rate in bytes per second.
     *  When true, max of inbound and outbound rate in bytes per second.
     */
    public int get15sRate(boolean outboundOnly) {
            FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
                int out = (int)bw.getSendBps15s();
                if (outboundOnly)
                    return out;
                return (int)Math.max(out, bw.getReceiveBps15s());
    }

    /**
     *  Inbound rate in bytes per second
     */
    public int get15sRateIn() {
            FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
                return (int) bw.getReceiveBps15s();
    }

    /**
     *  Max of inbound and outbound rate in bytes per second
     */
    public int get1mRate() { return get1mRate(false); }

    /**
     *  When outboundOnly is false, outbound rate in bytes per second.
     *  When true, max of inbound and outbound rate in bytes per second.
     */
    public int get1mRate(boolean outboundOnly) {
        int send = 0;
        StatManager mgr = _context.statManager();
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

    /**
     *  Inbound rate in bytes per second
     */
    public int get1mRateIn() {
        StatManager mgr = _context.statManager();
        RateStat rs = mgr.getRate("bw.recvRate");
        int recv = 0;
        if (rs != null)
            recv = (int)rs.getRate(1*60*1000).getAverageValue();
        return recv;
    }

    /**
     *  Max of inbound and outbound rate in bytes per second
     */
    public int get5mRate() { return get5mRate(false); }

    /**
     *  When outboundOnly is false, outbound rate in bytes per second.
     *  When true, max of inbound and outbound rate in bytes per second.
     */
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
}
