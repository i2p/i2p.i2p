package net.i2p.router;

import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CopyOnWriteArrayList;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.data.Hash;
import net.i2p.data.RoutingKeyGenerator;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.internal.InternalClientManager;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.crypto.TransientSessionKeyManager;
import net.i2p.router.dummy.*;
import net.i2p.router.message.GarlicMessageParser;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.peermanager.PeerManagerFacadeImpl;
import net.i2p.router.peermanager.ProfileManagerImpl;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.startup.RouterAppManager;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.transport.OutboundMessageRegistry;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.router.tunnel.pool.TunnelPoolManager;
import net.i2p.util.KeyRing;
import net.i2p.util.I2PProperties.I2PPropertyCallback;
import net.i2p.util.SystemVersion;

/**
 * Build off the core I2P context to provide a root for a router instance to
 * coordinate its resources.  Router instances themselves should be sure to have
 * their own RouterContext, and rooting off of it will allow multiple routers to
 * operate in the same JVM without conflict (e.g. sessionTags wont get 
 * intermingled, nor will their netDbs, jobQueues, or bandwidth limiters).
 *
 */
public class RouterContext extends I2PAppContext {
    private final Router _router;
    private ClientManagerFacade _clientManagerFacade;
    private InternalClientManager _internalClientManager;
    private ClientMessagePool _clientMessagePool;
    private JobQueue _jobQueue;
    private InNetMessagePool _inNetMessagePool;
    private OutNetMessagePool _outNetMessagePool;
    private MessageHistory _messageHistory;
    private OutboundMessageRegistry _messageRegistry;
    private NetworkDatabaseFacade _netDb;
    private KeyManager _keyManager;
    private CommSystemFacade _commSystem;
    private ProfileOrganizer _profileOrganizer;
    private PeerManagerFacade _peerManagerFacade;
    private ProfileManager _profileManager;
    private FIFOBandwidthLimiter _bandwidthLimiter;
    private TunnelManagerFacade _tunnelManager;
    private TunnelDispatcher _tunnelDispatcher;
    private StatisticsManager _statPublisher;
    private Banlist _banlist;
    private Blocklist _blocklist;
    private MessageValidator _messageValidator;
    //private MessageStateMonitor _messageStateMonitor;
    private RouterThrottle _throttle;
    private RouterAppManager _appManager;
    private RouterKeyGenerator _routingKeyGenerator;
    private GarlicMessageParser _garlicMessageParser;
    private final Set<Runnable> _finalShutdownTasks;
    // split up big lock on this to avoid deadlocks
    private volatile boolean _initialized;
    private final Object _lock1 = new Object(), _lock2 = new Object(), _lock3 = new Object();

    private static final List<RouterContext> _contexts = new CopyOnWriteArrayList<RouterContext>();
    
    /**
     *  Caller MUST call initAll() after instantiation.
     */
    public RouterContext(Router router) { this(router, null); }

    /**
     *  Caller MUST call initAll() after instantiation.
     */
    public RouterContext(Router router, Properties envProps) { 
        this(router, envProps, true);
    }

    /**
     *  Caller MUST call initAll() after instantiation.
     *  NOT a public API, for use by Router only, NOT for external use.
     *
     *  @param doInit should this context be used as the global one (if necessary)?
     *                Will only apply if there is no global context now.
     *                If false, caller should call setGlobalContext() afterwards.
     *  @since 0.9.33
     */
    RouterContext(Router router, Properties envProps, boolean doInit) { 
        super(doInit, filterProps(envProps));
        _router = router;
        // Disabled here so that the router can get a context and get the
        // directory locations from it, to do an update, without having
        // to init everything. Caller MUST call initAll() afterwards.
        // Sorry, this breaks some main() unit tests out there.
        //initAll();
        if (!_contexts.isEmpty())
            System.err.println("Warning - More than one router in this JVM");
        _finalShutdownTasks = new CopyOnWriteArraySet<Runnable>();
        if (doInit) {
            // Bad practice, adding this to static List in constructor.
            // doInit will be false when instantiated via Router.
            _contexts.add(this);
        }
    }
    
    /**
     * Sets the default context, unless there is one already.
     * NOT a public API, for use by Router only, NOT for external use.
     *
     * @param ctx context constructed with doInit = false
     * @return success (false if previously set)
     * @since 0.9.33
     */
    static boolean setGlobalContext(RouterContext ctx) {
        _contexts.add(ctx);
        return I2PAppContext.setGlobalContext(ctx);
    }

    /**
     * Set properties where the defaults must be different from those
     * in I2PAppContext.
     *
     * Unless we are explicitly disabling the timestamper, we want to use it.
     * We need this now as the new timestamper default is disabled (so we don't
     * have each I2PAppContext creating their own SNTP queries all the time)
     *
     * Set more PRNG buffers, as the default is now small for the I2PAppContext.
     *
     */
    private static final Properties filterProps(Properties envProps) {
        if (envProps == null)
            envProps = new Properties();
        if (envProps.getProperty("time.disabled") == null)
            envProps.setProperty("time.disabled", "false");
        if (envProps.getProperty("prng.buffers") == null) {
            // How many of these 256 KB buffers do we need?
            // One clue: prng.bufferFillTime is ~10ms on my system,
            // and prng.bufferFillTime event count is ~30 per minute,
            // or about 2 seconds per buffer - so about 200x faster
            // to fill than to drain - so we don't need too many
            long maxMemory = SystemVersion.getMaxMemory();
            long maxBuffs = (SystemVersion.isAndroid() || SystemVersion.isARM()) ? 4 : 8;
            long buffs = Math.min(maxBuffs, Math.max(2, maxMemory / (21 * 1024 * 1024)));
            envProps.setProperty("prng.buffers", "" + buffs);
        }
        return envProps;
    }
    
    /**
     * Modify the configuration attributes of this context, changing
     * one of the properties provided during the context construction.
     *
     * @param propName The name of the property.
     * @param value The new value for the property.
     * @since 0.8.4
     * @deprecated Use Router.saveConfig()
     */
    @Deprecated
    public void setProperty(String propName, String value) {
    		_overrideProps.setProperty(propName, value);
    }
    
    /**
     * Remove a property provided during the context construction.
     * Only for use by the router. Others use Router.saveConfig()
     *
     * @param propName The name of the property.
     * @since 0.9
     */
    void removeProperty(String propName) {
        _overrideProps.remove(propName);
    }

    
    public void addPropertyCallback(I2PPropertyCallback callback) {
    	_overrideProps.addCallBack(callback);
    }


    /**
     *  The following properties may be used to replace various parts
     *  of the context with dummy implementations for testing, by setting
     *  the property to "true":
     *<pre>
     *  i2p.dummyClientFacade
     *  i2p.dummyNetDb
     *  i2p.dummyPeerManager
     *  i2p.dummyTunnelManager
     *  i2p.vmCommSystem (transport)
     *</pre>
     */
    public synchronized void initAll() {
        if (_initialized)
            throw new IllegalStateException();
        if (!getBooleanProperty("i2p.dummyClientFacade")) {
            ClientManagerFacadeImpl cmfi = new ClientManagerFacadeImpl(this);
            _clientManagerFacade = cmfi;
            _internalClientManager = cmfi;
        } else {
            _clientManagerFacade = new DummyClientManagerFacade(this);
            // internal client manager is null
        }
        _garlicMessageParser = new GarlicMessageParser(this);
        _clientMessagePool = new ClientMessagePool(this);
        _jobQueue = new JobQueue(this);
        _jobQueue.startup();
        _inNetMessagePool = new InNetMessagePool(this);
        _outNetMessagePool = new OutNetMessagePool(this);
        _messageHistory = new MessageHistory(this);
        _messageRegistry = new OutboundMessageRegistry(this);
        //_messageStateMonitor = new MessageStateMonitor(this);
        _routingKeyGenerator = new RouterKeyGenerator(this);
        if (!getBooleanProperty("i2p.dummyNetDb"))
            _netDb = new FloodfillNetworkDatabaseFacade(this); // new KademliaNetworkDatabaseFacade(this);
        else
            _netDb = new DummyNetworkDatabaseFacade(this);
        _keyManager = new KeyManager(this);
        if (!getBooleanProperty("i2p.vmCommSystem"))
            _commSystem = new CommSystemFacadeImpl(this);
        else
            _commSystem = new VMCommSystem(this);
        _profileOrganizer = new ProfileOrganizer(this);
        if (!getBooleanProperty("i2p.dummyPeerManager"))
            _peerManagerFacade = new PeerManagerFacadeImpl(this);
        else
            _peerManagerFacade = new DummyPeerManagerFacade();
        _profileManager = new ProfileManagerImpl(this);
        _bandwidthLimiter = new FIFOBandwidthLimiter(this);
        if (!getBooleanProperty("i2p.dummyTunnelManager"))
            _tunnelManager = new TunnelPoolManager(this);
        else
            _tunnelManager = new DummyTunnelManagerFacade();
        _tunnelDispatcher = new TunnelDispatcher(this);
        _statPublisher = new StatisticsManager(this);
        _banlist = new Banlist(this);
        _blocklist = new Blocklist(this);
        _messageValidator = new MessageValidator(this);
        _throttle = new RouterThrottleImpl(this);
        //_throttle = new RouterDoSThrottle(this);
        _appManager = new RouterAppManager(this);
        _initialized = true;
    }
    
    /**
     * Retrieve the list of router contexts currently instantiated in this JVM.  
     * This will always contain only one item (except when a simulation per the
     * MultiRouter is going on).
     *
     * @return an unmodifiable list (as of 0.8.8). May be empty.
     */
    public static List<RouterContext> listContexts() {
        return Collections.unmodifiableList(_contexts);
    }
    
    /**
     * Same as listContexts() but package private and modifiable.
     * The list should only be modified when a new
     * context is created or a router is shut down.
     *
     * @since 0.8.8
     */
    static List<RouterContext> getContexts() {
        return _contexts;
    }
    
    /**
     * Kill the global I2PAppContext, so it isn't still around
     * when we restart in the same JVM (Android).
     * Only do this if there are no other routers in the JVM.
     *
     * @since 0.8.8
     */
    static void killGlobalContext() {
        synchronized (I2PAppContext.class) {
            _globalAppContext = null;
        }
    }
    
    /** what router is this context working for? */
    public Router router() { return _router; }

    /**
     *  Convenience method for getting the router hash.
     *  Equivalent to context.router().getRouterInfo().getIdentity().getHash()
     *
     *  Warning - risk of deadlock - do not call while holding locks
     *
     *  @return may be null if called very early
     */
    public Hash routerHash() {
        if (_router == null)
            return null;
        RouterInfo ri = _router.getRouterInfo();
        if (ri == null)
            return null;
        return ri.getIdentity().getHash();
    }

    /**
     * How are we coordinating clients for the router?
     */
    public ClientManagerFacade clientManager() { return _clientManagerFacade; }
    /**
     * Where do we toss messages for the clients (and where do we get client messages
     * to forward on from)?
     */
    public ClientMessagePool clientMessagePool() { return _clientMessagePool; }
    /**
     * Where do we get network messages from (aka where does the comm system dump what
     * it reads)?
     */
    public InNetMessagePool inNetMessagePool() { return _inNetMessagePool; }
    /**
     * Where do we put messages that the router wants to forwards onto the network?
     */
    public OutNetMessagePool outNetMessagePool() { return _outNetMessagePool; }
    /**
     * Tracker component for monitoring what messages are wrapped in what containers
     * and how they proceed through the network.  This is fully for debugging, as when
     * a large portion of the network tracks their messages through this messageHistory
     * and submits their logs, we can correlate them and watch as messages flow from 
     * hop to hop.
     */
    public MessageHistory messageHistory() { return _messageHistory; }
    /**
     * The registry is used by outbound messages to wait for replies.
     */
    public OutboundMessageRegistry messageRegistry() { return _messageRegistry; }

    /**
     * The monitor keeps track of inbound and outbound messages currently held in
     * memory / queued for processing.  We'll use this to throttle the router so
     * we don't overflow.
     *
     */
    //public MessageStateMonitor messageStateMonitor() { return _messageStateMonitor; }

    /**
     * Our db cache
     */
    public NetworkDatabaseFacade netDb() { return _netDb; }
    /**
     * The actual driver of the router, where all jobs are enqueued and processed.
     */
    public JobQueue jobQueue() { return _jobQueue; }
    /**
     * Coordinates the router's ElGamal and DSA keys, as well as any keys given
     * to it by clients as part of a LeaseSet.
     */
    public KeyManager keyManager() { return _keyManager; }
    /**
     * How do we pass messages from our outNetMessagePool to another router
     */
    public CommSystemFacade commSystem() { return _commSystem; }
    /**
     * Organize the peers we know about into various tiers, profiling their
     * performance and sorting them accordingly.
     */
    public ProfileOrganizer profileOrganizer() { return _profileOrganizer; }
    /**
     * Minimal interface for selecting peers for various tasks based on given
     * criteria.  This is kept seperate from the profile organizer since this 
     * logic is independent of how the peers are organized (or profiled even).
     */
    public PeerManagerFacade peerManager() { return _peerManagerFacade; }
    /**
     * Expose a simple API for various router components to take note of 
     * particular events that a peer enacts (sends us a message, agrees to 
     * participate in a tunnel, etc).
     */
    public ProfileManager profileManager() { return _profileManager; }
    /**
     * Coordinate this router's bandwidth limits
     */
    public FIFOBandwidthLimiter bandwidthLimiter() { return _bandwidthLimiter; }
    /**
     * Coordinate this router's tunnels (its pools, participation, backup, etc).
     * Any configuration for the tunnels is rooted from the context's properties
     */
    public TunnelManagerFacade tunnelManager() { return _tunnelManager; }
    /**
     * Handle tunnel messages, as well as coordinate the gateways
     */
    public TunnelDispatcher tunnelDispatcher() { return _tunnelDispatcher; }
    /**
     * If the router is configured to, gather up some particularly tasty morsels
     * regarding the stats managed and offer to publish them into the routerInfo.
     */
    public StatisticsManager statPublisher() { return _statPublisher; }
    /** 
     * who does this peer hate?
     */
    public Banlist banlist() { return _banlist; }
    public Blocklist blocklist() { return _blocklist; }
    /**
     * The router keeps track of messages it receives to prevent duplicates, as
     * well as other criteria for "validity".
     */
    public MessageValidator messageValidator() { return _messageValidator; }
    /**
     * Component to coordinate our accepting/rejecting of requests under load
     *
     */
    public RouterThrottle throttle() { return _throttle; }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(512);
        buf.append("RouterContext: ").append(super.toString()).append('\n');
        buf.append(_router).append('\n');
        buf.append(_clientManagerFacade).append('\n');
        buf.append(_clientMessagePool).append('\n');
        buf.append(_jobQueue).append('\n');
        buf.append(_inNetMessagePool).append('\n');
        buf.append(_outNetMessagePool).append('\n');
        buf.append(_messageHistory).append('\n');
        buf.append(_messageRegistry).append('\n');
        buf.append(_netDb).append('\n');
        buf.append(_keyManager).append('\n');
        buf.append(_commSystem).append('\n');
        buf.append(_profileOrganizer).append('\n');
        buf.append(_peerManagerFacade).append('\n');
        buf.append(_profileManager).append('\n');
        buf.append(_bandwidthLimiter).append('\n');
        buf.append(_tunnelManager).append('\n');
        buf.append(_statPublisher).append('\n');
        buf.append(_banlist).append('\n');
        buf.append(_messageValidator).append('\n');
        return buf.toString();
    }
    
    /**
     * Tie in the router's config as properties, as well as whatever the 
     * I2PAppContext says.
     *
     */
    @Override
    public String getProperty(String propName) {
        if (_router != null) {
            String val = _router.getConfigSetting(propName);
            if (val != null) return val;
        }
        return super.getProperty(propName);
    }
    /**
     * Tie in the router's config as properties, as well as whatever the 
     * I2PAppContext says.
     *
     */
    @Override
    public String getProperty(String propName, String defaultVal) {
        if (_router != null) {
            String val = _router.getConfigSetting(propName);
            if (val != null) return val;
        }
        return super.getProperty(propName, defaultVal);
    }

    /**
     * Return an int with an int default
     */
    @Override
    public int getProperty(String propName, int defaultVal) {
        if (_router != null) {
            String val = _router.getConfigSetting(propName);
            if (val != null) {
                int ival = defaultVal;
                try {
                    ival = Integer.parseInt(val);
                } catch (NumberFormatException nfe) {}
                return ival;
            }
        }
        return super.getProperty(propName, defaultVal);
    }

    /**
     * Return a long with a long default
     * @since 0.9.4
     */
    @Override
    public long getProperty(String propName, long defaultVal) {
        if (_router != null) {
            String val = _router.getConfigSetting(propName);
            if (val != null) {
                long rv = defaultVal;
                try {
                    rv = Long.parseLong(val);
                } catch (NumberFormatException nfe) {}
                return rv;
            }
        }
        return super.getProperty(propName, defaultVal);
    }

    /**
     * @return new Properties with system and context properties
     * @since 0.8.4
     */
    @Override
    public Properties getProperties() { 
        Properties rv = super.getProperties();
        if (_router != null)
            rv.putAll(_router.getConfigMap());
        return rv;
    }
    
    @Override
    protected void initializeClock() {
        synchronized (_lock1) {
            if (_clock == null) {
                RouterClock rc = new RouterClock(this);
                rc.start();
                _clock = rc;
            }
            _clockInitialized = true;
        }
    }

    /** override to support storage in router.config */
    @Override
    public KeyRing keyRing() {
        if (!_keyRingInitialized)
            initializeKeyRing();
        return _keyRing;
    }

    @Override
    protected void initializeKeyRing() {
        synchronized (_lock2) {
            if (_keyRing == null)
                _keyRing = new PersistentKeyRing(this);
            _keyRingInitialized = true;
        }
    }
    
    /**
     *  @since 0.8.8
     */
    void removeShutdownTasks() {
        _shutdownTasks.clear();
    }
    
    /**
     *  The last thing to be called before router shutdown.
     *  No context resources, including logging, will be available.
     *  Only for external threads in the same JVM needing to know when
     *  the shutdown is complete, like Android.
     *  @since 0.8.8
     */
    public void addFinalShutdownTask(Runnable task) {
        _finalShutdownTasks.add(task);
    }
    
    /**
     *  @return the Set
     *  @since 0.8.8
     */
    Set<Runnable> getFinalShutdownTasks() {
        return _finalShutdownTasks;
    }
    
    /**
     *  Use this instead of context instanceof RouterContext
     *  @return true
     *  @since 0.7.9
     */
    @Override
    public boolean isRouterContext() {
        return true;
    }

    /**
     *  Use this to connect to the router in the same JVM.
     *  @return the client manager
     *  @since 0.8.3
     */
    @Override
    public InternalClientManager internalClientManager() {
        return _internalClientManager;
    }

    /**
     *  The RouterAppManager.
     *  @return the manager
     *  @since 0.9.4
     */
    @Override
    public ClientAppManager clientAppManager() {
        return _appManager;
    }

    /**
     *  The RouterAppManager.
     *  For convenience, same as clientAppManager(), no cast required
     *  @return the manager
     *  @since 0.9.11
     */
    public RouterAppManager routerAppManager() {
        return _appManager;
    }

    /**
     *  As of 0.9.15, this returns a dummy SessionKeyManager in I2PAppContext.
     *  Overridden in RouterContext to return the full TransientSessionKeyManager.
     *
     *  @since 0.9.15
     */
    @Override
    protected void initializeSessionKeyManager() {
        synchronized (_lock3) {
            if (_sessionKeyManager == null) 
                //_sessionKeyManager = new PersistentSessionKeyManager(this);
                _sessionKeyManager = new TransientSessionKeyManager(this);
            _sessionKeyManagerInitialized = true;
        }
    }
    
    /**
     * Determine how much do we want to mess with the keys to turn them 
     * into something we can route.  This is context specific because we 
     * may want to test out how things react when peers don't agree on 
     * how to skew.
     *
     * Returns same thing as routerKeyGenerator()
     *
     * @return non-null
     * @since 0.9.16 Overrides I2PAppContext. Returns non-null in RouterContext and null in I2PAppcontext.
     */
    @Override
    public RoutingKeyGenerator routingKeyGenerator() {
        return _routingKeyGenerator;
    }

    /**
     * Determine how much do we want to mess with the keys to turn them 
     * into something we can route.  This is context specific because we 
     * may want to test out how things react when peers don't agree on 
     * how to skew.
     *
     * Returns same thing as routingKeyGenerator()
     *
     * @return non-null
     * @since 0.9.16
     */
    public RouterKeyGenerator routerKeyGenerator() {
        return _routingKeyGenerator;
    }

    /**
     * Since we only need one.
     *
     * @return non-null after initAll()
     * @since 0.9.20
     */
    public GarlicMessageParser garlicMessageParser() {
        return _garlicMessageParser;
    }
}
