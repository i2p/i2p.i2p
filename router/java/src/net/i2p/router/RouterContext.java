package net.i2p.router;

import net.i2p.data.Hash;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.transport.OutboundMessageRegistry;
import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.BandwidthLimiter;
import net.i2p.router.transport.TrivialBandwidthLimiter;
import net.i2p.router.tunnelmanager.PoolingTunnelManagerFacade;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.peermanager.PeerManagerFacadeImpl;
import net.i2p.router.peermanager.ProfileManagerImpl;
import net.i2p.router.peermanager.Calculator;
import net.i2p.router.peermanager.IsFailingCalculator;
import net.i2p.router.peermanager.ReliabilityCalculator;
import net.i2p.router.peermanager.SpeedCalculator;
import net.i2p.router.peermanager.IntegrationCalculator;
import net.i2p.I2PAppContext;

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
    private ClientManagerFacade _clientManagerFacade;
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
    private BandwidthLimiter _bandwidthLimiter;
    private TunnelManagerFacade _tunnelManager;
    private StatisticsManager _statPublisher;
    private Shitlist _shitlist;
    private MessageValidator _messageValidator;
    private Calculator _isFailingCalc;
    private Calculator _integrationCalc;
    private Calculator _speedCalc;
    private Calculator _reliabilityCalc;
    
    public RouterContext(Router router) { 
        super(); 
        _router = router;
        initAll();
    }
    private void initAll() {
        _clientManagerFacade = new ClientManagerFacadeImpl(this);
        _clientMessagePool = new ClientMessagePool(this);
        _jobQueue = new JobQueue(this);
        _inNetMessagePool = new InNetMessagePool(this);
        _outNetMessagePool = new OutNetMessagePool(this);
        _messageHistory = new MessageHistory(this);
        _messageRegistry = new OutboundMessageRegistry(this);
        _netDb = new KademliaNetworkDatabaseFacade(this);
        _keyManager = new KeyManager(this);
        _commSystem = new CommSystemFacadeImpl(this);
        _profileOrganizer = new ProfileOrganizer(this);
        _peerManagerFacade = new PeerManagerFacadeImpl(this);
        _profileManager = new ProfileManagerImpl(this);
        _bandwidthLimiter = new TrivialBandwidthLimiter(this);
        _tunnelManager = new PoolingTunnelManagerFacade(this);
        _statPublisher = new StatisticsManager(this);
        _shitlist = new Shitlist(this);
        _messageValidator = new MessageValidator(this);
        _isFailingCalc = new IsFailingCalculator(this);
        _integrationCalc = new IntegrationCalculator(this);
        _speedCalc = new SpeedCalculator(this);
        _reliabilityCalc = new ReliabilityCalculator(this);
    }
    
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
    public BandwidthLimiter bandwidthLimiter() { return _bandwidthLimiter; }
    /**
     * Coordinate this router's tunnels (its pools, participation, backup, etc).
     * Any configuration for the tunnels is rooted from the context's properties
     */
    public TunnelManagerFacade tunnelManager() { return _tunnelManager; }
    /**
     * If the router is configured to, gather up some particularly tasty morsels
     * regarding the stats managed and offer to publish them into the routerInfo.
     */
    public StatisticsManager statPublisher() { return _statPublisher; }
    /** 
     * who does this peer hate?
     */
    public Shitlist shitlist() { return _shitlist; }
    /**
     * The router keeps track of messages it receives to prevent duplicates, as
     * well as other criteria for "validity".
     */
    public MessageValidator messageValidator() { return _messageValidator; }
    
    /** how do we rank the failure of profiles? */
    public Calculator isFailingCalculator() { return _isFailingCalc; }
    /** how do we rank the integration of profiles? */
    public Calculator integrationCalculator() { return _integrationCalc; }
    /** how do we rank the speed of profiles? */
    public Calculator speedCalculator() { return _speedCalc; } 
    /** how do we rank the reliability of profiles? */
    public Calculator reliabilityCalculator() { return _reliabilityCalc; }
}
/*
public class RouterContext extends I2PAppContext {
    private Router _router;
    private ClientManagerFacade _clientManagerFacade;
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
    private BandwidthLimiter _bandwidthLimiter;
    private TunnelManagerFacade _tunnelManager;
    private StatisticsManager _statPublisher;
    private Shitlist _shitlist;
    private MessageValidator _messageValidator;
    private volatile boolean _clientManagerFacadeInitialized;
    private volatile boolean _clientMessagePoolInitialized;
    private volatile boolean _jobQueueInitialized;
    private volatile boolean _inNetMessagePoolInitialized;
    private volatile boolean _outNetMessagePoolInitialized;
    private volatile boolean _messageHistoryInitialized;
    private volatile boolean _messageRegistryInitialized;
    private volatile boolean _netDbInitialized;
    private volatile boolean _peerSelectorInitialized;
    private volatile boolean _keyManagerInitialized;
    private volatile boolean _commSystemInitialized;
    private volatile boolean _profileOrganizerInitialized;
    private volatile boolean _profileManagerInitialized;
    private volatile boolean _peerManagerFacadeInitialized;
    private volatile boolean _bandwidthLimiterInitialized;
    private volatile boolean _tunnelManagerInitialized;
    private volatile boolean _statPublisherInitialized;
    private volatile boolean _shitlistInitialized;
    private volatile boolean _messageValidatorInitialized;
    
    private Calculator _isFailingCalc = new IsFailingCalculator(this);
    private Calculator _integrationCalc = new IntegrationCalculator(this);
    private Calculator _speedCalc = new SpeedCalculator(this);
    private Calculator _reliabilityCalc = new ReliabilityCalculator(this);
    
    public Calculator isFailingCalculator() { return _isFailingCalc; }
    public Calculator integrationCalculator() { return _integrationCalc; }
    public Calculator speedCalculator() { return _speedCalc; } 
    public Calculator reliabilityCalculator() { return _reliabilityCalc; }
    
    
    public RouterContext(Router router) { 
        super(); 
        _router = router;
    }
    
    public Router router() { return _router; }
    public Hash routerHash() { return _router.getRouterInfo().getIdentity().getHash(); }
    
    public ClientManagerFacade clientManager() {
        if (!_clientManagerFacadeInitialized) initializeClientManagerFacade();
        return _clientManagerFacade;
    }
    private void initializeClientManagerFacade() {
        synchronized (this) {
            if (_clientManagerFacade == null) {
                _clientManagerFacade = new ClientManagerFacadeImpl(this);
            }
            _clientManagerFacadeInitialized = true;
        }
    }
    
    public ClientMessagePool clientMessagePool() {
        if (!_clientMessagePoolInitialized) initializeClientMessagePool();
        return _clientMessagePool;
    }
    private void initializeClientMessagePool() {
        synchronized (this) {
            if (_clientMessagePool == null) {
                _clientMessagePool = new ClientMessagePool(this);
            }
            _clientMessagePoolInitialized = true;
        }
    }
 
    public InNetMessagePool inNetMessagePool() {
        if (!_inNetMessagePoolInitialized) initializeInNetMessagePool();
        return _inNetMessagePool;
    }
    private void initializeInNetMessagePool() {
        synchronized (this) {
            if (_inNetMessagePool == null) {
                _inNetMessagePool = new InNetMessagePool(this);
            }
            _inNetMessagePoolInitialized = true;
        }
    }
    
    public OutNetMessagePool outNetMessagePool() {
        if (!_outNetMessagePoolInitialized) initializeOutNetMessagePool();
        return _outNetMessagePool;
    }
    private void initializeOutNetMessagePool() {
        synchronized (this) {
            if (_outNetMessagePool == null) {
                _outNetMessagePool = new OutNetMessagePool(this);
            }
            _outNetMessagePoolInitialized = true;
        }
    }
    
    public MessageHistory messageHistory() {
        if (!_messageHistoryInitialized) initializeMessageHistory();
        return _messageHistory;
    }
    private void initializeMessageHistory() {
        synchronized (this) {
            if (_messageHistory == null) {
                _messageHistory = new MessageHistory(this);
            }
            _messageHistoryInitialized = true;
        }
    }
    
    public OutboundMessageRegistry messageRegistry() {
        if (!_messageRegistryInitialized) initializeMessageRegistry();
        return _messageRegistry;
    }
    private void initializeMessageRegistry() {
        synchronized (this) {
            if (_messageRegistry == null)
                _messageRegistry = new OutboundMessageRegistry(this);
            _messageRegistryInitialized = true;
        }
    }
    
    public NetworkDatabaseFacade netDb() {
        if (!_netDbInitialized) initializeNetDb();
        return _netDb;
    }
    private void initializeNetDb() {
        synchronized (this) {
            if (_netDb == null)
                _netDb = new KademliaNetworkDatabaseFacade(this);
            _netDbInitialized = true;
        }
    }
    
    public JobQueue jobQueue() {
        if (!_jobQueueInitialized) initializeJobQueue();
        return _jobQueue;
    }
    private void initializeJobQueue() {
        synchronized (this) {
            if (_jobQueue == null) {
                _jobQueue= new JobQueue(this);
            }
            _jobQueueInitialized = true;
        }
    }
    
    public KeyManager keyManager() {
        if (!_keyManagerInitialized) initializeKeyManager();
        return _keyManager;
    }
    private void initializeKeyManager() {
        synchronized (this) {
            if (_keyManager == null)
                _keyManager = new KeyManager(this);
            _keyManagerInitialized = true;
        }
    }
    
    public CommSystemFacade commSystem() {
        if (!_commSystemInitialized) initializeCommSystem();
        return _commSystem;
    }
    private void initializeCommSystem() {
        synchronized (this) {
            if (_commSystem == null)
                _commSystem = new CommSystemFacadeImpl(this);
            _commSystemInitialized = true;
        }
    }
    
    public ProfileOrganizer profileOrganizer() {
        if (!_profileOrganizerInitialized) initializeProfileOrganizer();
        return _profileOrganizer;
    }
    private void initializeProfileOrganizer() {
        synchronized (this) {
            if (_profileOrganizer == null)
                _profileOrganizer = new ProfileOrganizer(this);
            _profileOrganizerInitialized = true;
        }
    }
    public PeerManagerFacade peerManager() {
        if (!_peerManagerFacadeInitialized) initializePeerManager();
        return _peerManagerFacade;
    }
    private void initializePeerManager() {
        synchronized (this) {
            if (_peerManagerFacade == null)
                _peerManagerFacade = new PeerManagerFacadeImpl(this);
            _peerManagerFacadeInitialized = true;
        }
    }
    
    public BandwidthLimiter bandwidthLimiter() {
        if (!_bandwidthLimiterInitialized) initializeBandwidthLimiter();
        return _bandwidthLimiter;
    }
    private void initializeBandwidthLimiter() {
        synchronized (this) {
            if (_bandwidthLimiter == null)
                _bandwidthLimiter = new TrivialBandwidthLimiter(this);
            _bandwidthLimiterInitialized = true;
        }
    }
    
    public TunnelManagerFacade tunnelManager() { 
        if (!_tunnelManagerInitialized) initializeTunnelManager();
        return _tunnelManager;
    }
    private void initializeTunnelManager() {
        synchronized (this) {
            if (_tunnelManager == null)
                _tunnelManager = new PoolingTunnelManagerFacade(this);
            _tunnelManagerInitialized = true;
        }
    }
    public ProfileManager profileManager() { 
        if (!_profileManagerInitialized) initializeProfileManager();
        return _profileManager;
    }
    private void initializeProfileManager() {
        synchronized (this) {
            if (_profileManager == null)
                _profileManager = new ProfileManagerImpl(this);
            _profileManagerInitialized = true;
        }
    }
    public StatisticsManager statPublisher() { 
        if (!_statPublisherInitialized) initializeStatPublisher();
        return _statPublisher;
    }
    private void initializeStatPublisher() {
        synchronized (this) {
            if (_statPublisher == null)
                _statPublisher = new StatisticsManager(this);
            _statPublisherInitialized = true;
        }
    }
    
    public Shitlist shitlist() { 
        if (!_shitlistInitialized) initializeShitlist();
        return _shitlist;
    }
    private void initializeShitlist() {
        synchronized (this) {
            if (_shitlist == null)
                _shitlist = new Shitlist(this);
            _shitlistInitialized = true;
        }
    }
    
    public MessageValidator messageValidator() {
        if (!_messageValidatorInitialized) initializeMessageValidator();
        return _messageValidator;
    }
    private void initializeMessageValidator() {
        synchronized (this) {
            if (_messageValidator == null)
                _messageValidator = new MessageValidator(this);
            _messageValidatorInitialized = true;
        }
    }
}
 */