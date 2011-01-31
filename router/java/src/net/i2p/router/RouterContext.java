package net.i2p.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.internal.InternalClientManager;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.peermanager.PeerManagerFacadeImpl;
import net.i2p.router.peermanager.ProfileManagerImpl;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.transport.OutboundMessageRegistry;
import net.i2p.router.transport.VMCommSystem;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.router.tunnel.pool.TunnelPoolManager;
import net.i2p.util.Clock;
import net.i2p.util.KeyRing;
import net.i2p.util.I2PProperties.I2PPropertyCallback;

/**
 * Build off the core I2P context to provide a root for a router instance to
 * coordinate its resources.  Router instances themselves should be sure to have
 * their own RouterContext, and rooting off of it will allow multiple routers to
 * operate in the same JVM without conflict (e.g. sessionTags wont get 
 * intermingled, nor will their netDbs, jobQueues, or bandwidth limiters).
 *
 */
public class RouterContext extends I2PAppContext {
    private Router _router;
    private ClientManagerFacadeImpl _clientManagerFacade;
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
    private Shitlist _shitlist;
    private Blocklist _blocklist;
    private MessageValidator _messageValidator;
    private MessageStateMonitor _messageStateMonitor;
    private RouterThrottle _throttle;

    private static List<RouterContext> _contexts = new ArrayList(1);
    
    public RouterContext(Router router) { this(router, null); }
    public RouterContext(Router router, Properties envProps) { 
        super(filterProps(envProps));
        _router = router;
        // Disabled here so that the router can get a context and get the
        // directory locations from it, to do an update, without having
        // to init everything. Caller MUST call initAll() afterwards.
        // Sorry, this breaks some main() unit tests out there.
        //initAll();
        _contexts.add(this);
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
            long maxMemory = Runtime.getRuntime().maxMemory();
            long buffs = Math.min(16, Math.max(2, maxMemory / (14 * 1024 * 1024)));
            envProps.setProperty("prng.buffers", "" + buffs);
        }
        return envProps;
    }
    
    /**
     * Modify the configuration attributes of this context, changing
     * one of the properties provided during the context construction.
     * @param propName The name of the property.
     * @param value The new value for the property.
     */
    public void setProperty(String propName, String value) {
    	if(_overrideProps != null) {
    		_overrideProps.setProperty(propName, value);
    	}
    }

    
    public void addPropertyCallback(I2PPropertyCallback callback) {
    	_overrideProps.addCallBack(callback);
    }


    public void initAll() {
        if (getBooleanProperty("i2p.dummyClientFacade"))
            System.err.println("i2p.dummpClientFacade currently unsupported");
        _clientManagerFacade = new ClientManagerFacadeImpl(this);
        // removed since it doesn't implement InternalClientManager for now
        //else
        //    _clientManagerFacade = new DummyClientManagerFacade(this);
        _clientMessagePool = new ClientMessagePool(this);
        _jobQueue = new JobQueue(this);
        _inNetMessagePool = new InNetMessagePool(this);
        _outNetMessagePool = new OutNetMessagePool(this);
        _messageHistory = new MessageHistory(this);
        _messageRegistry = new OutboundMessageRegistry(this);
        _messageStateMonitor = new MessageStateMonitor(this);
        if ("false".equals(getProperty("i2p.dummyNetDb", "false")))
            _netDb = new FloodfillNetworkDatabaseFacade(this); // new KademliaNetworkDatabaseFacade(this);
        else
            _netDb = new DummyNetworkDatabaseFacade(this);
        _keyManager = new KeyManager(this);
        if ("false".equals(getProperty("i2p.vmCommSystem", "false")))
            _commSystem = new CommSystemFacadeImpl(this);
        else
            _commSystem = new VMCommSystem(this);
        _profileOrganizer = new ProfileOrganizer(this);
        if ("false".equals(getProperty("i2p.dummyPeerManager", "false")))
            _peerManagerFacade = new PeerManagerFacadeImpl(this);
        else
            _peerManagerFacade = new DummyPeerManagerFacade();
        _profileManager = new ProfileManagerImpl(this);
        _bandwidthLimiter = new FIFOBandwidthLimiter(this);
        if ("false".equals(getProperty("i2p.dummyTunnelManager", "false")))
            _tunnelManager = new TunnelPoolManager(this);
        else
            _tunnelManager = new DummyTunnelManagerFacade();
        _tunnelDispatcher = new TunnelDispatcher(this);
        _statPublisher = new StatisticsManager(this);
        _shitlist = new Shitlist(this);
        _blocklist = new Blocklist(this);
        _messageValidator = new MessageValidator(this);
        _throttle = new RouterThrottleImpl(this);
        //_throttle = new RouterDoSThrottle(this);
    }
    
    /**
     * Retrieve the list of router contexts currently instantiated in this JVM.  
     * This will always contain only one item (except when a simulation per the
     * MultiRouter is going on), and the list should only be modified when a new
     * context is created or a router is shut down.
     *
     */
    public static List<RouterContext> listContexts() { return _contexts; }
    
    /** what router is this context working for? */
    public Router router() { return _router; }
    /** convenience method for querying the router's ident */
    public Hash routerHash() { return _router.getRouterInfo().getIdentity().getHash(); }

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
    public MessageStateMonitor messageStateMonitor() { return _messageStateMonitor; }
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
    public Shitlist shitlist() { return _shitlist; }
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
        buf.append(_shitlist).append('\n');
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
        synchronized (this) {
            if (_clock == null)
                _clock = new RouterClock(this);
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
        synchronized (this) {
            if (_keyRing == null)
                _keyRing = new PersistentKeyRing(this);
            _keyRingInitialized = true;
        }
    }
    
    /**
     *  Use this instead of context instanceof RouterContext
     *  @return true
     *  @since 0.7.9
     */
    public boolean isRouterContext() {
        return true;
    }

    /**
     *  Use this to connect to the router in the same JVM.
     *  @return the client manager
     *  @since 0.8.3
     */
    public InternalClientManager internalClientManager() {
        return _clientManagerFacade;
    }
}
