package net.i2p.router.transport.udp;

import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.i2p.crypto.SigType;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.Transport;
import static net.i2p.router.transport.Transport.AddressSource.*;
import net.i2p.router.transport.TransportBid;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportUtil;
import static net.i2p.router.transport.TransportUtil.IPv6Config.*;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import static net.i2p.router.transport.udp.PeerTestState.Role.*;
import net.i2p.router.util.EventLog;
import net.i2p.router.util.RandomIterator;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.VersionComparator;

/**
 *  The SSU transport
 */
public class UDPTransport extends TransportImpl implements TimedWeightedPriorityMessageQueue.FailedListener {
    private final Log _log;
    private final List<UDPEndpoint> _endpoints;
    private final Object _addDropLock = new Object();
    /** Peer (Hash) to PeerState */
    private final Map<Hash, PeerState> _peersByIdent;
    /** RemoteHostId to PeerState */
    private final Map<RemoteHostId, PeerState> _peersByRemoteHost;
    private PacketHandler _handler;
    private EstablishmentManager _establisher;
    private final MessageQueue _outboundMessages;
    private final OutboundMessageFragments _fragments;
    private final OutboundMessageFragments.ActiveThrottle _activeThrottle;
    private OutboundRefiller _refiller;
    private volatile PacketPusher _pusher;
    private final InboundMessageFragments _inboundFragments;
    //private UDPFlooder _flooder;
    private final PeerTestManager _testManager;
    private final IntroductionManager _introManager;
    private final ExpirePeerEvent _expireEvent;
    private final PeerTestEvent _testEvent;
    private final PacketBuilder _destroyBuilder;
    private Status _reachabilityStatus;
    private Status _reachabilityStatusPending;
    // only for logging, to be removed
    private long _reachabilityStatusLastUpdated;
    private int _reachabilityStatusUnchanged;
    private long _introducersSelectedOn;
    private long _lastInboundReceivedOn;
    private final DHSessionKeyBuilder.Factory _dhFactory;
    private int _mtu;
    private int _mtu_ipv6;
    private boolean _mismatchLogged;
    private final int _networkID;

    /**
     *  Do we have a public IPv6 address?
     *  TODO periodically update via CSFI.NetMonitor?
     */
    private volatile boolean _haveIPv6Address;
    private long _lastInboundIPv6;
    private final int _min_peers;
    private final int _min_v6_peers;
    
    /** do we need to rebuild our external router address asap? */
    private boolean _needsRebuild;
    private final Object _rebuildLock = new Object();
    
    /** introduction key */
    private SessionKey _introKey;
    
    /**
     *  List of RemoteHostId for peers whose packets we want to drop outright
     *  This is only for old network IDs (pre-0.6.1.10), so it isn't really used now.
     */
    private final Set<RemoteHostId> _dropList;
    
    private volatile long _expireTimeout;

    /** last report from a peer of our IP */
    private Hash _lastFrom;
    private byte[] _lastOurIP;
    private int _lastOurPort;
    /** since we don't publish our IP/port if introduced anymore, we need
        to store it somewhere. */
    private RouterAddress _currentOurV4Address;
    private RouterAddress _currentOurV6Address;

    private static final int DROPLIST_PERIOD = 10*60*1000;
    public static final String STYLE = "SSU";
    public static final String PROP_INTERNAL_PORT = "i2np.udp.internalPort";

    /** now unused, we pick a random port
     *  @deprecated unused
     */
    @Deprecated
    public static final int DEFAULT_INTERNAL_PORT = 8887;

    /** define this to explicitly set an external IP address */
    public static final String PROP_EXTERNAL_HOST = "i2np.udp.host";
    /** define this to explicitly set an external port */
    public static final String PROP_EXTERNAL_PORT = "i2np.udp.port";
    /** 
     * If i2np.udp.preferred is set to "always", the UDP bids will always be under 
     * the bid from the TCP transport - even if a TCP connection already 
     * exists.  If it is set to "true",
     * it will prefer UDP unless no UDP session exists and a TCP connection 
     * already exists.
     * If it is set to "false" (the default),
     * it will prefer TCP unless no TCP session exists and a UDP connection 
     * already exists.
     */
    public static final String PROP_PREFER_UDP = "i2np.udp.preferred";
    private static final String DEFAULT_PREFER_UDP = "false";
    
    /** Override whether we will change our advertised port no matter what our peers tell us
     *  See getIsPortFixed() for default behaviour.
     */
    private static final String PROP_FIXED_PORT = "i2np.udp.fixedPort";

    /** allowed sources of address updates */
    public static final String PROP_SOURCES = "i2np.udp.addressSources";
    public static final String DEFAULT_SOURCES = SOURCE_INTERFACE.toConfigString() + ',' +
                                                 SOURCE_UPNP.toConfigString() + ',' +
                                                 SOURCE_SSU.toConfigString();
    /** remember IP changes */
    public static final String PROP_IP= "i2np.lastIP";
    public static final String PROP_IP_CHANGE = "i2np.lastIPChange";
    public static final String PROP_LAPTOP_MODE = "i2np.laptopMode";

    /** do we require introducers, regardless of our status? */
    public static final String PROP_FORCE_INTRODUCERS = "i2np.udp.forceIntroducers";
    /** do we allow direct SSU connections, sans introducers?  */
    public static final String PROP_ALLOW_DIRECT = "i2np.udp.allowDirect";
    /** this is rarely if ever used, default is to bind to wildcard address */
    public static final String PROP_BIND_INTERFACE = "i2np.udp.bindInterface";
    /** override the "large" (max) MTU, default is PeerState.LARGE_MTU */
    private static final String PROP_DEFAULT_MTU = "i2np.udp.mtu";
    private static final String PROP_ADVANCED = "routerconsole.advanced";
        
    private static final String CAP_TESTING = "" + UDPAddress.CAPACITY_TESTING;
    private static final String CAP_TESTING_INTRO = "" + UDPAddress.CAPACITY_TESTING + UDPAddress.CAPACITY_INTRODUCER;

    /** how many relays offered to us will we use at a time? */
    public static final int PUBLIC_RELAY_COUNT = 3;
    
    private static final boolean USE_PRIORITY = false;

    /** configure the priority queue with the given split points */
    private static final int PRIORITY_LIMITS[] = new int[] { 100, 200, 300, 400, 500, 1000 };
    /** configure the priority queue with the given weighting per priority group */
    private static final int PRIORITY_WEIGHT[] = new int[] { 1, 1, 1, 1, 1, 2 };

    /** should we flood all UDP peers with the configured rate? This is for testing only! */
    //private static final boolean SHOULD_FLOOD_PEERS = false;
    
    private static final int MAX_CONSECUTIVE_FAILED = 5;
    
    public static final int DEFAULT_COST = 5;
    static final long[] RATES = { 10*60*1000 };
    /** minimum active peers to maintain IP detection, etc. */
    private static final int MIN_PEERS = 5;
    private static final int MIN_PEERS_IF_HAVE_V6 = 30;
    /** minimum peers volunteering to be introducers if we need that */
    private static final int MIN_INTRODUCER_POOL = 5;
    static final long INTRODUCER_EXPIRATION_MARGIN = 20*60*1000L;
    
    private static final int[] BID_VALUES = { 15, 20, 50, 65, 80, 95, 100, 115, TransportBid.TRANSIENT_FAIL };
    private static final int FAST_PREFERRED_BID = 0;
    private static final int SLOW_PREFERRED_BID = 1;
    private static final int FAST_BID = 2;
    private static final int SLOW_BID = 3;
    private static final int SLOWEST_BID = 4;
    private static final int SLOWEST_COST_BID = 5;
    private static final int NEAR_CAPACITY_BID = 6;
    private static final int NEAR_CAPACITY_COST_BID = 7;
    private static final int TRANSIENT_FAIL_BID = 8;
    private final TransportBid[] _cachedBid;

    // Opera doesn't have the char, TODO check UA
    //private static final String THINSP = "&thinsp;/&thinsp;";
    private static final String THINSP = " / ";

    /**
     *  RI sigtypes supported in 0.9.16, but due to a bug in InboundEstablishState
     *  fixed in 0.9.17, we cannot connect out to routers before that version.
     */
    private static final String MIN_SIGTYPE_VERSION = "0.9.17";

    /**
     *  IPv6 Peer Testing supported
     */
    private static final String MIN_V6_PEER_TEST_VERSION = "0.9.27";


    public UDPTransport(RouterContext ctx, DHSessionKeyBuilder.Factory dh) {
        super(ctx);
        _networkID = ctx.router().getNetworkID();
        _dhFactory = dh;
        _log = ctx.logManager().getLog(UDPTransport.class);
        _peersByIdent = new ConcurrentHashMap<Hash, PeerState>(128);
        _peersByRemoteHost = new ConcurrentHashMap<RemoteHostId, PeerState>(128);
        _dropList = new ConcurrentHashSet<RemoteHostId>(2);
        _endpoints = new CopyOnWriteArrayList<UDPEndpoint>();
        
        // See comments in DummyThrottle.java
        if (USE_PRIORITY) {
            TimedWeightedPriorityMessageQueue mq = new TimedWeightedPriorityMessageQueue(ctx, PRIORITY_LIMITS, PRIORITY_WEIGHT, this);
            _outboundMessages = mq;
            _activeThrottle = mq;
        } else {
            DummyThrottle mq = new DummyThrottle();
            _outboundMessages = null;
            _activeThrottle = mq;
        }

        _cachedBid = new SharedBid[BID_VALUES.length];
        for (int i = 0; i < BID_VALUES.length; i++) {
            _cachedBid[i] = new SharedBid(BID_VALUES[i]);
        }

        _destroyBuilder = new PacketBuilder(_context, this);
        _fragments = new OutboundMessageFragments(_context, this, _activeThrottle);
        _inboundFragments = new InboundMessageFragments(_context, _fragments, this);
        //if (SHOULD_FLOOD_PEERS)
        //    _flooder = new UDPFlooder(_context, this);
        _expireTimeout = EXPIRE_TIMEOUT;
        _expireEvent = new ExpirePeerEvent();
        _testManager = new PeerTestManager(_context, this);
        _testEvent = new PeerTestEvent(_context, this, _testManager);
        _reachabilityStatus = Status.UNKNOWN;
        _reachabilityStatusPending = Status.OK;
        _introManager = new IntroductionManager(_context, this);
        _introducersSelectedOn = -1;
        _lastInboundReceivedOn = -1;
        _mtu = PeerState.LARGE_MTU;
        _mtu_ipv6 = PeerState.MIN_IPV6_MTU;
        setupPort();
        _needsRebuild = true;
        _min_peers = _context.getProperty("i2np.udp.minpeers", MIN_PEERS);
        _min_v6_peers = _context.getProperty("i2np.udp.minv6peers", MIN_PEERS_IF_HAVE_V6);
        
        _context.statManager().createRateStat("udp.alreadyConnected", "What is the lifetime of a reestablished session", "udp", RATES);
        _context.statManager().createRateStat("udp.droppedPeer", "How long ago did we receive from a dropped peer (duration == session lifetime", "udp", RATES);
        _context.statManager().createRateStat("udp.droppedPeerInactive", "How long ago did we receive from a dropped peer (duration == session lifetime)", "udp", RATES);
        //_context.statManager().createRateStat("udp.statusOK", "How many times the peer test returned OK", "udp", RATES);
        //_context.statManager().createRateStat("udp.statusDifferent", "How many times the peer test returned different IP/ports", "udp", RATES);
        //_context.statManager().createRateStat("udp.statusReject", "How many times the peer test returned reject unsolicited", "udp", RATES);
        //_context.statManager().createRateStat("udp.statusUnknown", "How many times the peer test returned an unknown result", "udp", RATES);
        _context.statManager().createRateStat("udp.addressTestInsteadOfUpdate", "How many times we fire off a peer test of ourselves instead of adjusting our own reachable address?", "udp", RATES);
        _context.statManager().createRateStat("udp.addressUpdated", "How many times we adjust our own reachable IP address", "udp", RATES);
        _context.statManager().createRateStat("udp.proactiveReestablish", "How long a session was idle for when we proactively reestablished it", "udp", RATES);
        _context.statManager().createRateStat("udp.dropPeerDroplist", "How many peers currently have their packets dropped outright when a new peer is added to the list?", "udp", RATES);
        _context.statManager().createRateStat("udp.dropPeerConsecutiveFailures", "How many consecutive failed sends to a peer did we attempt before giving up and reestablishing a new session (lifetime is inactivity perood)", "udp", RATES);
        _context.statManager().createRateStat("udp.inboundIPv4Conn", "Inbound IPv4 UDP Connection", "udp", RATES);
        _context.statManager().createRateStat("udp.inboundIPv6Conn", "Inbound IPv4 UDP Connection", "udp", RATES);
        // following are for PacketBuider
        //_context.statManager().createRateStat("udp.packetAuthTime", "How long it takes to encrypt and MAC a packet for sending", "udp", RATES);
        //_context.statManager().createRateStat("udp.packetAuthTimeSlow", "How long it takes to encrypt and MAC a packet for sending (when its slow)", "udp", RATES);

        _context.simpleTimer2().addPeriodicEvent(new PingIntroducers(), MIN_EXPIRE_TIMEOUT * 3 / 4);
    }
    
    /**
     *  Pick a port if not previously configured, so that TransportManager may
     *  call getRequestedPort() before we've started to get a best-guess of what our
     *  port is going to be, and pass that to NTCP
     *
     *  @since IPv6
     */
    private void setupPort() {
        int port = getRequestedPort();
        if (port < 0) {
            port = UDPEndpoint.selectRandomPort(_context);
            Map<String, String> changes = new HashMap<String, String>();
            changes.put(PROP_INTERNAL_PORT, Integer.toString(port));
            changes.put(PROP_EXTERNAL_PORT, Integer.toString(port));
            _context.router().saveConfig(changes, null);
            _log.logAlways(Log.INFO, "UDP selected random port " + port);
        }
    }

    private synchronized void startup() {
        _fragments.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        if (_handler != null) 
            _handler.shutdown();
        for (UDPEndpoint endpoint : _endpoints) {
            endpoint.shutdown();
            // should we remove?
            _endpoints.remove(endpoint);
        }
        if (_establisher != null)
            _establisher.shutdown();
        if (_refiller != null)
            _refiller.shutdown();
        _inboundFragments.shutdown();
        //if (_flooder != null)
        //    _flooder.shutdown();
        _introManager.reset();
        UDPPacket.clearCache();
        
        if (_log.shouldLog(Log.WARN)) _log.warn("Starting SSU transport listening");
        _introKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(_context.routerHash().getData(), 0, _introKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        
        // bind host
        // This is not exposed in the UI and in practice is always null.
        // We use PROP_EXTERNAL_HOST instead. See below.
        String bindTo = _context.getProperty(PROP_BIND_INTERFACE);

        if (bindTo == null) {
            // If we are configured with a fixed IP address,
            // AND it's one of our local interfaces,
            // bind only to that.
            String fixedHost = _context.getProperty(PROP_EXTERNAL_HOST);
            if (fixedHost != null && fixedHost.length() > 0) {
                // Generate a comma-separated list of valid IP addresses
                // that we can bind to,
                // from the comma-separated PROP_EXTERNAL_HOST config.
                // The config may contain IPs or hostnames; expand each
                // hostname to one or more (v4 or v6) IPs.
                TransportUtil.IPv6Config cfg = getIPv6Config();
                Set<String> myAddrs;
                if (cfg == IPV6_DISABLED)
                    myAddrs = Addresses.getAddresses(false, false);
                else
                    myAddrs = Addresses.getAddresses(false, true);
                StringBuilder buf = new StringBuilder();
                String[] bta = DataHelper.split(fixedHost, "[,; \r\n\t]");
                for (int i = 0; i < bta.length; i++) {
                    String bt = bta[i];
                    if (bt.length() <= 0)
                        continue;
                    try {
                        InetAddress[] all = InetAddress.getAllByName(bt);
                        for (int j = 0; j < all.length; j++) {
                            InetAddress ia = all[j];
                            if (cfg == IPV6_ONLY && (ia instanceof Inet4Address)) {
                                if (_log.shouldWarn())
                                    _log.warn("Configured for IPv6 only, not binding to configured IPv4 host " + bt);
                                continue;
                            }
                            String testAddr = ia.getHostAddress();
                            if (myAddrs.contains(testAddr)) {
                                if (buf.length() > 0)
                                    buf.append(',');
                                buf.append(testAddr);
                            } else {
                                if (_log.shouldWarn())
                                    _log.warn("Not a local address, not binding to configured IP " + testAddr);
                            }
                        }
                    } catch (UnknownHostException uhe) {
                        if (_log.shouldWarn())
                            _log.warn("Not binding to configured host " + bt + " - " + uhe);
                    }
                }
                if (buf.length() > 0) {
                    bindTo = buf.toString();
                    if (_log.shouldWarn() && !fixedHost.equals(bindTo))
                        _log.warn("Expanded external host config \"" + fixedHost + "\" to \"" + bindTo + '"');
                }
            }
        }

        // construct a set of addresses
        Set<InetAddress> bindToAddrs = new HashSet<InetAddress>(4);
        if (bindTo != null) {
            // Generate a set IP addresses
            // that we can bind to,
            // from the comma-separated PROP_BIND_INTERFACE config,
            // or as generated from the PROP_EXTERNAL_HOST config.
            // In theory, the config may contain IPs and/or hostnames.
            // However, in practice, it's only IPs, because any hostnames
            // in PROP_EXTERNAL_HOST were expanded above to one or more (v4 or v6) IPs.
            // PROP_BIND_INTERFACE is not exposed in the UI and is never set.
            String[] bta = DataHelper.split(bindTo, "[,; \r\n\t]");
            for (int i = 0; i < bta.length; i++) {
                String bt = bta[i];
                if (bt.length() <= 0)
                    continue;
                try {
                    bindToAddrs.add(InetAddress.getByName(bt));
                } catch (UnknownHostException uhe) {
                    _log.error("Invalid SSU bind interface specified [" + bt + "]", uhe);
                    //setReachabilityStatus(CommSystemFacade.STATUS_HOSED);
                    //return;
                    // fall thru...
                }
            }
        }
        
        // Requested bind port
        // This may be -1 or may not be honored if busy,
        // Priority: Configured internal, then already used, then configured external
        // we will check below after starting up the endpoint.
        int port;
        int oldIPort = _context.getProperty(PROP_INTERNAL_PORT, -1);
        int oldBindPort = getListenPort(false);
        int oldEPort = _context.getProperty(PROP_EXTERNAL_PORT, -1);
        if (oldIPort > 0)
            port = oldIPort;
        else if (oldBindPort > 0)
            port = oldBindPort;
        else
            port = oldEPort;
        if (!bindToAddrs.isEmpty() && _log.shouldLog(Log.WARN))
            _log.warn("Binding only to " + bindToAddrs);
        if (_log.shouldLog(Log.INFO))
            _log.info("Binding to the port: " + port);
        if (_endpoints.isEmpty()) {
            // _endpoints will always be empty since we removed them above
            if (bindToAddrs.isEmpty()) {
                UDPEndpoint endpoint = new UDPEndpoint(_context, this, port, null);
                _endpoints.add(endpoint);
                setMTU(null);
            } else {
                for (InetAddress bindToAddr : bindToAddrs) {
                    UDPEndpoint endpoint = new UDPEndpoint(_context, this, port, bindToAddr);
                    _endpoints.add(endpoint);
                    setMTU(bindToAddr);
                }
            }
        } else {
            // unused for now
            for (UDPEndpoint endpoint : _endpoints) {
                if (endpoint.isIPv4()) {
                    // hack, first IPv4 endpoint, FIXME
                    // todo, set bind address too
                    endpoint.setListenPort(port);
                    break;
                }
            }
        }

        if (_establisher == null)
            _establisher = new EstablishmentManager(_context, this);
        
        if (_handler == null)
            _handler = new PacketHandler(_context, this, _establisher, _inboundFragments, _testManager, _introManager);
        
        // See comments in DummyThrottle.java
        if (USE_PRIORITY && _refiller == null)
            _refiller = new OutboundRefiller(_context, _fragments, _outboundMessages);
        
        //if (SHOULD_FLOOD_PEERS && _flooder == null)
        //    _flooder = new UDPFlooder(_context, this);
        
        // Startup the endpoint with the requested port, check the actual port, and
        // take action if it failed or was different than requested or it needs to be saved
        int newPort = -1;
        for (UDPEndpoint endpoint : _endpoints) {
            try {
                endpoint.startup();
                // hack, first IPv4 endpoint, FIXME
                if (newPort < 0 && endpoint.isIPv4()) {
                    newPort = endpoint.getListenPort();
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Started " + endpoint);
            } catch (SocketException se) {
                _endpoints.remove(endpoint);
                _log.error("Failed to start " + endpoint, se);
            }
        }
        if (_endpoints.isEmpty()) {
            _log.log(Log.CRIT, "Unable to open UDP port");
            setReachabilityStatus(Status.HOSED);
            return;
        }
        if (newPort > 0 &&
            (newPort != port || newPort != oldIPort || newPort != oldEPort)) {
            // attempt to use it as our external port - this will be overridden by
            // externalAddressReceived(...)
            Map<String, String> changes = new HashMap<String, String>();
            changes.put(PROP_INTERNAL_PORT, Integer.toString(newPort));
            changes.put(PROP_EXTERNAL_PORT, Integer.toString(newPort));
            _context.router().saveConfig(changes, null);
        }

        _handler.startup();
        _fragments.startup();
        _inboundFragments.startup();
        _pusher = new PacketPusher(_context, _fragments, _endpoints);
        _pusher.startup();
        // must be after pusher
        _establisher.startup();
        if (USE_PRIORITY)
            _refiller.startup();
        //if (SHOULD_FLOOD_PEERS)
        //    _flooder.startup();
        _expireEvent.setIsAlive(true);
        _reachabilityStatus = Status.UNKNOWN;
        _testEvent.setIsAlive(true); // this queues it for 3-6 minutes in the future...
        _testEvent.reschedule(10*1000); // lets requeue it for Real Soon

        // set up external addresses
        // REA param is false;
        // TransportManager.startListening() calls router.rebuildRouterInfo()
        if (newPort > 0 && bindToAddrs.isEmpty()) {
            for (InetAddress ia : getSavedLocalAddresses()) {
                // Discovered or configured addresses are presumed good at the start.
                // when externalAddressReceived() was called with SOURCE_INTERFACE,
                // isAlive() was false, so setReachabilityStatus() was not called
                // TODO should we set both to unknown and wait for an inbound v6 conn,
                // since there's no v6 testing?
                if (ia.getAddress().length == 16) {
                    // FIXME we need to check and time out after an hour of no inbound ipv6,
                    // change to firewalled maybe? but we don't have any test to restore
                    // a v6 address after it's removed.
                    _lastInboundIPv6 = _context.clock().now();
                    if (!isIPv6Firewalled())
                        setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_OK, true);
                } else {
                    if (!isIPv4Firewalled())
                        setReachabilityStatus(Status.IPV4_OK_IPV6_UNKNOWN);
                }
                rebuildExternalAddress(ia.getHostAddress(), newPort, false);
            }
        } else if (newPort > 0 && !bindToAddrs.isEmpty()) {
            for (InetAddress ia : bindToAddrs) {
                if (ia.getAddress().length == 16) {
                    _lastInboundIPv6 = _context.clock().now();
                    if (!isIPv6Firewalled())
                        setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_OK, true);
                } else {
                    if (!isIPv4Firewalled())
                        setReachabilityStatus(Status.IPV4_OK_IPV6_UNKNOWN);
                }
                rebuildExternalAddress(ia.getHostAddress(), newPort, false);
            }
            // TODO
            // If we are bound only to v4 addresses,
            // force _haveIPv6Address to false, or else we get 'no endpoint' errors
            // If we are bound only to v6 addresses,
            // override getIPv6Config() ?
        }
        if (isIPv4Firewalled()) {
            if (_lastInboundIPv6 > 0)
                setReachabilityStatus(Status.IPV4_FIREWALLED_IPV6_UNKNOWN);
            else
                setReachabilityStatus(Status.REJECT_UNSOLICITED);
        }
        rebuildExternalAddress(false);
    }
    
    public synchronized void shutdown() {
        destroyAll();
        for (UDPEndpoint endpoint : _endpoints) {
            endpoint.shutdown();
            // should we remove?
            _endpoints.remove(endpoint);
        }
        //if (_flooder != null)
        //    _flooder.shutdown();
        if (_refiller != null)
            _refiller.shutdown();
        if (_handler != null)
            _handler.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        _fragments.shutdown();
        if (_establisher != null)
            _establisher.shutdown();
        _inboundFragments.shutdown();
        _expireEvent.setIsAlive(false);
        _testEvent.setIsAlive(false);
        _peersByRemoteHost.clear();
        _peersByIdent.clear();
        _dropList.clear();
        _introManager.reset();
        UDPPacket.clearCache();
        UDPAddress.clearCache();
        _lastInboundIPv6 = 0;
    }

    /**
     *  The endpoint has failed. Remove it.
     *
     *  @since 0.9.16
     */
    public void fail(UDPEndpoint endpoint) {
        if (_endpoints.remove(endpoint)) {
            _log.log(Log.CRIT, "UDP port failure: " + endpoint);
            if (_endpoints.isEmpty()) {
                _log.log(Log.CRIT, "No more UDP sockets open");
                setReachabilityStatus(Status.HOSED);
                // TODO restart?
            }
            rebuildExternalAddress();
        }
    }

    /** @since IPv6 */
    private boolean isAlive() {
        return _inboundFragments.isAlive();
    }
    
    /**
     * Introduction key that people should use to contact us
     *
     */
    SessionKey getIntroKey() { return _introKey; }

    int getExternalPort(boolean ipv6) {
        RouterAddress addr = getCurrentAddress(ipv6);
        if (addr != null) {
            int rv = addr.getPort();
            if (rv > 0)
                return rv;
        }
        return getRequestedPort(ipv6);
    }

    /**
     *  IPv4 only
     *  @return IP or null
     *  @since 0.9.2
     */
    byte[] getExternalIP() {
        RouterAddress addr = getCurrentAddress(false);
        if (addr != null)
            return addr.getIP();
        return null;
    }

    /**
     *  For PeerTestManager
     *  @since 0.9.30
     */
    boolean hasIPv6Address() {
        return _haveIPv6Address;
    }

    /**
     *  Is this IP too close to ours to trust it for
     *  things like relaying?
     *  @param ip IPv4 or IPv6
     *  @since IPv6
     */
    boolean isTooClose(byte[] ip) {
        if (allowLocal())
            return false;
        for (RouterAddress addr : getCurrentAddresses()) {
            byte[] myip = addr.getIP();
            if (myip == null || ip.length != myip.length)
                continue;
            if (ip.length == 4) {
                if (DataHelper.eq(ip, 0, myip, 0, 2))
                    return true;
            } else if (ip.length == 16) {
                if (DataHelper.eq(ip, 0, myip, 0, 8))
                    return true;
            }
        }
        return false;
    }

    /**
     *  The current port of the first matching endpoint.
     *  To be enhanced to handle multiple endpoints of the same type.
     *  @return port or -1
     *  @since IPv6
     */
    private int getListenPort(boolean ipv6) {
        for (UDPEndpoint endpoint : _endpoints) {
            if (((!ipv6) && endpoint.isIPv4()) ||
                (ipv6 && endpoint.isIPv6()))
                return endpoint.getListenPort();
       }
       return -1;
    }

    /**
     *  The current or configured internal IPv4 port.
     *  UDPEndpoint should always be instantiated (and a random port picked if not configured)
     *  before this is called, so the returned value should be &gt; 0
     *  unless the endpoint failed to bind.
     */
    @Override
    public int getRequestedPort() {
        return getRequestedPort(false);
    }

    /**
     *  The current or configured internal port.
     *  UDPEndpoint should always be instantiated (and a random port picked if not configured)
     *  before this is called, so the returned value should be &gt; 0
     *  unless the endpoint failed to bind.
     */
    private int getRequestedPort(boolean ipv6) {
        int rv = getListenPort(ipv6);
        if (rv > 0)
            return rv;
        // fallbacks
        rv = _context.getProperty(PROP_INTERNAL_PORT, -1);
        if (rv > 0)
            return rv;
        return _context.getProperty(PROP_EXTERNAL_PORT, -1);
    }

    /**
     *  Set the MTU for the socket interface at addr.
     *  @param addr null ok
     *  @return the mtu
     *  @since 0.9.2
     */
    private int setMTU(InetAddress addr) {
        String p = _context.getProperty(PROP_DEFAULT_MTU);
        if (p != null) {
            try {
                int pmtu = Integer.parseInt(p);
                _mtu = MTU.rectify(false, pmtu);
                _mtu_ipv6 = MTU.rectify(true, pmtu);
                return _mtu;
            } catch (NumberFormatException nfe) {}
        }
        int mtu = MTU.getMTU(addr);
        if (addr != null && addr.getAddress().length == 16) {
            if (mtu <= 0)
                mtu = PeerState.MIN_IPV6_MTU;
            _mtu_ipv6 = mtu;
        } else {
            if (mtu <= 0)
                mtu = PeerState.LARGE_MTU;
            _mtu = mtu;
        }
        return mtu;
    }

    /**
     * The MTU for the socket interface.
     * To be used as the "large" MTU.
     * @return limited to range PeerState.MIN_MTU to PeerState.LARGE_MTU.
     * @since 0.9.2, public since 0.9.31
     */
    public int getMTU(boolean ipv6) {
        // TODO multiple interfaces of each type
        return ipv6 ? _mtu_ipv6 : _mtu;
    }

    /**
     * If we have received an inbound connection in the last 2 minutes, don't allow 
     * our IP to change.
     */
    private static final int ALLOW_IP_CHANGE_INTERVAL = 2*60*1000;
    
    void inboundConnectionReceived(boolean isIPv6) {
        if (isIPv6) {
            _lastInboundIPv6 = _context.clock().now();
            _context.statManager().addRateData("udp.inboundIPv6Conn", 1);
            // former workaround for lack of IPv6 peer testing
            //if (_currentOurV6Address != null)
            //    setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_OK, true);
        } else {
            // Introduced connections are still inbound, this is not evidence
            // that we are not firewalled.
            // use OS clock since its an ordering thing, not a time thing
            _lastInboundReceivedOn = System.currentTimeMillis(); 
            _context.statManager().addRateData("udp.inboundIPv4Conn", 1);
        }
    }
    
    // temp prevent multiples
    private boolean gotIPv4Addr = false;
    private boolean gotIPv6Addr = false;

    /**
     * From config, UPnP, local i/f, ...
     * Not for info received from peers - see externalAddressReceived(Hash, ip, port)
     *
     * @param source as defined in Transport.SOURCE_xxx
     * @param ip publicly routable IPv4 or IPv6, null ok
     * @param port 0 if unknown
     */
    @Override
    public void externalAddressReceived(Transport.AddressSource source, byte[] ip, int port) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Received address: " + Addresses.toString(ip, port) + " from: " + source);
        if (ip == null)
            return;
        // this is essentially isValid(ip), but we can't use that because
        // _haveIPv6Address is not set yet
        if (!(isPubliclyRoutable(ip) || allowLocal())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid address: " + Addresses.toString(ip, port) + " from: " + source);
            return;
        }
        if (source == SOURCE_INTERFACE && ip.length == 16) {
            // NOW we can set it, it's a valid v6 address
            // (we don't want to set this for Teredo, 6to4, ...)
            _haveIPv6Address = true;
        }
        if (explicitAddressSpecified())
            return;
        String sources = _context.getProperty(PROP_SOURCES, DEFAULT_SOURCES);
        if (!sources.contains(source.toConfigString()))
            return;
        if (!isAlive()) {
            if (source == SOURCE_INTERFACE || source == SOURCE_UPNP) {
                try {
                    InetAddress ia = InetAddress.getByAddress(ip);
                    saveLocalAddress(ia);
                } catch (UnknownHostException uhe) {}
            }
            return;
        }
        if (source == SOURCE_INTERFACE) {
            // temp prevent multiples
            if (ip.length == 4) {
                if (gotIPv4Addr)
                    return;
                else
                    gotIPv4Addr = true;
            } else if (ip.length == 16) {
                if (gotIPv6Addr)
                    return;
                else
                    gotIPv6Addr = true;
            }
        }
        boolean changed = changeAddress(ip, port);
        // Assume if we have an interface with a public IP that we aren't firewalled.
        // If this is wrong, the peer test will figure it out and change the status.
        if (changed && source == SOURCE_INTERFACE) {
            if (ip.length == 4) {
                if (!isIPv4Firewalled())
                    setReachabilityStatus(Status.IPV4_OK_IPV6_UNKNOWN);
            } else if (ip.length == 16) {
                // TODO should we set both to unknown and wait for an inbound v6 conn,
                // since there's no v6 testing?
                if (!isIPv6Firewalled())
                    setReachabilityStatus(Status.IPV4_UNKNOWN_IPV6_OK, true);
            }
        }
    }

    /**
     *  Callback from UPnP.
     *  If we we have an IP address and UPnP claims success, believe it.
     *  If this is wrong, the peer test will figure it out and change the status.
     *  Don't do anything if UPnP claims failure.
     */
    @Override
    public void forwardPortStatus(byte[] ip, int port, int externalPort, boolean success, String reason) {
        if (_log.shouldLog(Log.WARN)) {
            if (success)
                _log.warn("UPnP has opened the SSU port: " + port + " via " + Addresses.toString(ip, externalPort));
            else
                _log.warn("UPnP has failed to open the SSU port: " + port + " reason: " + reason);
        }
        if (success && ip != null && getExternalIP() != null) {
            if (!isIPv4Firewalled())
                setReachabilityStatus(Status.IPV4_OK_IPV6_UNKNOWN);
        }
    }

    /**
     * Someone we tried to contact gave us what they think our IP address is.
     * Right now, we just blindly trust them, changing our IP and port on a
     * whim.  this is not good ;)
     *
     * Slight enhancement - require two different peers in a row to agree
     *
     * Todo:
     *   - Much better tracking of troublemakers
     *   - Disable if we have good local address or UPnP
     *   - This gets harder if and when we publish multiple addresses, or IPv6
     * 
     * @param from Hash of inbound destination
     * @param ourIP publicly routable IPv4 only
     * @param ourPort &gt;= 1024
     */
    void externalAddressReceived(Hash from, byte ourIP[], int ourPort) {
        boolean isValid = isValid(ourIP) &&
                          TransportUtil.isValidPort(ourPort);
        boolean explicitSpecified = explicitAddressSpecified();
        boolean inboundRecent = _lastInboundReceivedOn + ALLOW_IP_CHANGE_INTERVAL > System.currentTimeMillis();
        if (_log.shouldLog(Log.INFO))
            _log.info("External address received: " + Addresses.toString(ourIP, ourPort) + " from " 
                      + from + ", isValid? " + isValid + ", explicitSpecified? " + explicitSpecified 
                      + ", receivedInboundRecent? " + inboundRecent + " status " + _reachabilityStatus);
        if (ourIP.length != 4) {
            return;
        }
        
        if (explicitSpecified) 
            return;
        String sources = _context.getProperty(PROP_SOURCES, DEFAULT_SOURCES);
        if (!sources.contains("ssu"))
            return;
        
        if (!isValid) {
            // ignore them 
            if (_log.shouldLog(Log.ERROR))
                _log.error("The router " + from + " told us we have an invalid IP - " 
                           + Addresses.toString(ourIP, ourPort) + ".  Lets throw tomatoes at them");
            markUnreachable(from);
            //_context.banlist().banlistRouter(from, "They said we had an invalid IP", STYLE);
            return;
        }

        RouterAddress addr = getCurrentExternalAddress(false);
        if (inboundRecent && addr != null && addr.getPort() > 0 && addr.getHost() != null) {
            // use OS clock since its an ordering thing, not a time thing
            // Note that this fails us if we switch from one IP to a second, then back to the first,
            // as some routers still have the first IP and will successfully connect,
            // leaving us thinking the second IP is still good.
            if (_log.shouldLog(Log.INFO))
                _log.info("Ignoring IP address suggestion, since we have received an inbound con recently");
        } else {
            // New IP
            boolean changeIt = false;
            synchronized(this) {
                if (from.equals(_lastFrom) || !eq(_lastOurIP, _lastOurPort, ourIP, ourPort)) {
                    _lastFrom = from;
                    _lastOurIP = ourIP;
                    _lastOurPort = ourPort;
                    if (_log.shouldLog(Log.INFO))
                        _log.info("The router " + from + " told us we have a new IP - " 
                                  + Addresses.toString(ourIP, ourPort) + ".  Wait until somebody else tells us the same thing.");
                } else {
                    _lastFrom = from;
                    _lastOurIP = ourIP;
                    _lastOurPort = ourPort;
                    changeIt = true;
                }
            }
            if (changeIt) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(from + " and " + _lastFrom + " agree we have a new IP - " 
                              + Addresses.toString(ourIP, ourPort) + ".  Changing address.");
                changeAddress(ourIP, ourPort);
            }
        }
    }
    
    /**
     * Possibly change our external address to the IP/port.
     * IP/port are already validated, but not yet compared to current IP/port.
     * We compare here.
     *
     * @param ourIP MUST have been previously validated with isValid()
     *              IPv4 or IPv6 OK
     * @param ourPort &gt;= 1024 or 0 for no change
     */
    private boolean changeAddress(byte ourIP[], int ourPort) {
        // this defaults to true when we are firewalled and false otherwise.
        boolean fixedPort = getIsPortFixed();
        boolean updated = false;
        boolean fireTest = false;

        boolean isIPv6 = ourIP.length == 16;
        RouterAddress current = getCurrentExternalAddress(isIPv6);
        byte[] externalListenHost = current != null ? current.getIP() : null;
        int externalListenPort = current != null ? current.getPort() : getRequestedPort(isIPv6);

        if (_log.shouldLog(Log.INFO))
            _log.info("Change address? status = " + _reachabilityStatus +
                      " diff = " + (_context.clock().now() - _reachabilityStatusLastUpdated) +
                      " old = " + Addresses.toString(externalListenHost, externalListenPort) +
                      " new = " + Addresses.toString(ourIP, ourPort));

        if ((fixedPort && externalListenPort > 0) || ourPort <= 0)
            ourPort = externalListenPort;

            synchronized (this) {
                if (ourPort > 0 &&
                    !eq(externalListenHost, externalListenPort, ourIP, ourPort)) {
                    // This prevents us from changing our IP when we are not firewalled
                    //if ( (_reachabilityStatus != CommSystemFacade.STATUS_OK) ||
                    //     (_externalListenHost == null) || (_externalListenPort <= 0) ||
                    //     (_context.clock().now() - _reachabilityStatusLastUpdated > 2*TEST_FREQUENCY) ) {
                        // they told us something different and our tests are either old or failing
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Trying to change our external address to " +
                                          Addresses.toString(ourIP, ourPort));
                            RouterAddress newAddr = rebuildExternalAddress(ourIP, ourPort, true);
                            updated = newAddr != null;
                    //} else {
                    //    // they told us something different, but our tests are recent and positive,
                    //    // so lets test again
                    //    fireTest = true;
                    //    if (_log.shouldLog(Log.WARN))
                    //        _log.warn("Different address, but we're fine.. (" + _reachabilityStatus + ")");
                    //}
                } else {
                    // matched what we expect
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Same address as the current one");
                }
            }

        if (fireTest) {
            // always false, commented out above
            _context.statManager().addRateData("udp.addressTestInsteadOfUpdate", 1);
            _testEvent.forceRunImmediately(isIPv6);
        } else if (updated) {
            _context.statManager().addRateData("udp.addressUpdated", 1);
            Map<String, String> changes = new HashMap<String, String>();
            if (ourIP.length == 4 && !fixedPort)
                changes.put(PROP_EXTERNAL_PORT, Integer.toString(ourPort));
            // queue a country code lookup of the new IP
            if (ourIP.length == 4)
                _context.commSystem().queueLookup(ourIP);
            // store these for laptop-mode (change ident on restart... or every time... when IP changes)
            // IPV4 ONLY
            String oldIP = _context.getProperty(PROP_IP);
            String newIP = Addresses.toString(ourIP);
            if (ourIP.length == 4 && !newIP.equals(oldIP)) {
                long lastChanged = 0;
                long now = _context.clock().now();
                String lcs = _context.getProperty(PROP_IP_CHANGE);
                if (lcs != null) {
                    try {
                        lastChanged = Long.parseLong(lcs);
                    } catch (NumberFormatException nfe) {}
                }

                changes.put(PROP_IP, newIP);
                changes.put(PROP_IP_CHANGE, Long.toString(now));
                _context.router().saveConfig(changes, null);

                if (oldIP != null) {
                    _context.router().eventLog().addEvent(EventLog.CHANGE_IP, newIP);
                }

                // laptop mode
                // For now, only do this at startup
                if (oldIP != null &&
                    System.getProperty("wrapper.version") != null &&
                    _context.getBooleanProperty(PROP_LAPTOP_MODE) &&
                    now - lastChanged > 10*60*1000 &&
                    _context.router().getUptime() < 10*60*1000) {
                    System.out.println("WARN: IP changed, restarting with a new identity and port");
                    _log.logAlways(Log.WARN, "IP changed, restarting with a new identity and port");
                    // this removes the UDP port config
                    _context.router().killKeys();
                    // do we need WrapperManager.signalStopped() like in ConfigServiceHandler ???
                    // without it, the wrapper complains "shutdown unexpectedly"
                    // but we can't have that dependency in the router
                    _context.router().shutdown(Router.EXIT_HARD_RESTART);
                    // doesn't return
                }
            } else if (ourIP.length == 4 && !fixedPort) {
                // save PROP_EXTERNAL_PORT
                _context.router().saveConfig(changes, null);
            }
            // deadlock thru here ticket #1699
            _context.router().rebuildRouterInfo();
            _testEvent.forceRunImmediately(isIPv6);
        }
        return updated;
    }

    /**
     *  @param laddr and raddr may be null
     */
    private static final boolean eq(byte laddr[], int lport, byte raddr[], int rport) {
        return (rport == lport) && DataHelper.eq(laddr, raddr);
    }
    
    /**
     * An IPv6 address is only valid if we are configured to support IPv6
     * AND we have a public IPv6 address.
     *
     * @param addr may be null, returns false
     */
    public final boolean isValid(byte addr[]) {
        if (addr == null) return false;
        if (isPubliclyRoutable(addr) &&
            (addr.length != 16 || _haveIPv6Address))
            return true;
        return allowLocal();
    }

    /**
     *  Was true before 0.9.2
     *  Now false if we need introducers (as perhaps that's why we need them,
     *  our firewall is changing our port), unless overridden by the property.
     *  We must have an accurate external port when firewalled, or else
     *  our signature of the SessionCreated packet will be invalid.
     */
    private boolean getIsPortFixed() {
        String prop = _context.getProperty(PROP_FIXED_PORT);
        if (prop != null)
            return Boolean.parseBoolean(prop);
        Status status = getReachabilityStatus();
        return status != Status.REJECT_UNSOLICITED &&
               status != Status.IPV4_FIREWALLED_IPV6_OK &&
               status != Status.IPV4_FIREWALLED_IPV6_UNKNOWN;
    }

    /** 
     * get the state for the peer at the given remote host/port, or null 
     * if no state exists
     */
    PeerState getPeerState(RemoteHostId hostInfo) {
            return _peersByRemoteHost.get(hostInfo);
    }

    /** 
     *  Get the states for all peers at the given remote host, ignoring port.
     *  Used for a last-chance search for a peer that changed port, by PacketHandler.
     *  Always returns empty list for IPv6 hostInfo.
     *  @since 0.9.3
     */
    List<PeerState> getPeerStatesByIP(RemoteHostId hostInfo) {
        List<PeerState> rv = new ArrayList<PeerState>(4);
        byte[] ip = hostInfo.getIP();
        if (ip != null && ip.length == 4) {
            for (PeerState ps : _peersByIdent.values()) {
                if (DataHelper.eq(ip, ps.getRemoteIP()))
                    rv.add(ps);
            }
        }
        return rv;
    }
    
    /** 
     * get the state for the peer with the given ident, or null 
     * if no state exists
     */
    PeerState getPeerState(Hash remotePeer) { 
            return _peersByIdent.get(remotePeer);
    }
    
    /** 
     * For /peers UI only. Not a public API, not for external use.
     *
     * @return not a copy, do not modify
     * @since 0.9.31
     */
    public Collection<PeerState> getPeers() {
        return _peersByIdent.values();
    }
    
    /** 
     * Connected peers.
     *
     * @return not a copy, do not modify
     * @since 0.9.34
     */
    public Set<Hash> getEstablished() {
        return _peersByIdent.keySet();
    }

    /** 
     *  Remove and add to peersByRemoteHost map
     *  @since 0.9.3
     */
    void changePeerPort(PeerState peer, int newPort) {
        // this happens a lot
        int oldPort;
        synchronized (_addDropLock) {
            oldPort = peer.getRemotePort();
            if (oldPort != newPort) {
                _peersByRemoteHost.remove(peer.getRemoteHostId());
                peer.changePort(newPort);
                _peersByRemoteHost.put(peer.getRemoteHostId(), peer);
            }
        }
        if (_log.shouldInfo() && oldPort != newPort)
            _log.info("Changed port from " + oldPort + " to " + newPort + " for " + peer);
    }

    /**
     *  For IntroductionManager
     *  @return may be null if not started
     *  @since 0.9.2
     */
    EstablishmentManager getEstablisher() {
        return _establisher;
    }

    /**
     * Intercept RouterInfo entries received directly from a peer to inject them into
     * the PeersByCapacity listing.
     *
     */
    /*
    public void messageReceived(I2NPMessage inMsg, RouterIdentity remoteIdent, Hash remoteIdentHash, long msToReceive, int bytesReceived) {
        
        if (inMsg instanceof DatabaseStoreMessage) {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)inMsg;
            if (dsm.getValueType() == DatabaseStoreMessage.KEY_TYPE_ROUTERINFO) {
                Hash from = remoteIdentHash;
                if (from == null)
                    from = remoteIdent.getHash();

                if (from.equals(dsm.getKey())) {
                    // db info received directly from the peer - inject it into the peersByCapacity
                    RouterInfo info = dsm.getRouterInfo();
                    Set addresses = info.getAddresses();
                    for (Iterator iter = addresses.iterator(); iter.hasNext(); ) {
                        RouterAddress addr = (RouterAddress)iter.next();
                        if (!STYLE.equals(addr.getTransportStyle()))
                            continue;
                        Properties opts = addr.getOptions();
                        if ( (opts != null) && (info.isValid()) ) {
                            String capacities = opts.getProperty(UDPAddress.PROP_CAPACITY);
                            if (capacities != null) {
                                if (_log.shouldLog(Log.INFO))
                                    _log.info("Intercepting and storing the capacities for " + from.toBase64() + ": " + capacities);
                                PeerState peer = getPeerState(from);
                                for (int i = 0; i < capacities.length(); i++) {
                                    char capacity = capacities.charAt(i);
                                    int cap = capacity - 'A';
                                    if ( (cap < 0) || (cap >= _peersByCapacity.length) ) 
                                        continue;
                                    List peers = _peersByCapacity[cap];
                                    synchronized (peers) {
                                        if ( (peers.size() < MAX_PEERS_PER_CAPACITY) && (!peers.contains(peer)) )
                                            peers.add(peer);
                                    }
                                }
                            }
                        }
                        // this was an SSU address so we're done now
                        break;
                    }
                }
            }
        }
        super.messageReceived(inMsg, remoteIdent, remoteIdentHash, msToReceive, bytesReceived);
    }
    */
    
    /** 
     * add the peer info, returning true if it went in properly, false if
     * it was rejected (causes include peer ident already connected, or no
     * remote host info known
     *
     */
    boolean addRemotePeerState(PeerState peer) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Add remote peer state: " + peer);
        synchronized(_addDropLock) {
            return locked_addRemotePeerState(peer);
        }
    }

    private boolean locked_addRemotePeerState(PeerState peer) {
        Hash remotePeer = peer.getRemotePeer();
        long oldEstablishedOn = -1;
        PeerState oldPeer = null;
        if (remotePeer != null) {
            oldPeer = _peersByIdent.put(remotePeer, peer);
            if ( (oldPeer != null) && (oldPeer != peer) ) {
                // this happens a lot
                if (_log.shouldInfo())
                    _log.info("Peer already connected (PBID): old=" + oldPeer + " new=" + peer);
                // transfer over the old state/inbound message fragments/etc
                peer.loadFrom(oldPeer);
                oldEstablishedOn = oldPeer.getKeyEstablishedTime();
            }
        }
        
        RemoteHostId remoteId = peer.getRemoteHostId();
        if (oldPeer != null) {
            oldPeer.dropOutbound();
            _introManager.remove(oldPeer);
            _expireEvent.remove(oldPeer);
            RemoteHostId oldID = oldPeer.getRemoteHostId();
            if (!remoteId.equals(oldID)) {
                // leak fix, remove old address
                if (_log.shouldInfo())
                    _log.info(remotePeer + " changed address FROM " + oldID + " TO " + remoteId);
                PeerState oldPeer2 = _peersByRemoteHost.remove(oldID);
                // different ones in the two maps? shouldn't happen
                if (oldPeer2 != oldPeer && oldPeer2 != null) {
                    oldPeer2.dropOutbound();
                     _introManager.remove(oldPeer2);
                    _expireEvent.remove(oldPeer2);
                }
            }
        }

        // Should always be direct... except maybe for hidden mode?
        // or do we always know the IP by now?
        if (remoteId.getIP() == null && _log.shouldLog(Log.WARN))
            _log.warn("Add indirect: " + peer);

        // don't do this twice
        PeerState oldPeer2 = _peersByRemoteHost.put(remoteId, peer);
        if (oldPeer2 != null && oldPeer2 != peer && oldPeer2 != oldPeer) {
            // this shouldn't happen, should have been removed above
            if (_log.shouldLog(Log.WARN))
                _log.warn("Peer already connected (PBRH): old=" + oldPeer2 + " new=" + peer);
            // transfer over the old state/inbound message fragments/etc
            peer.loadFrom(oldPeer2);
            oldEstablishedOn = oldPeer2.getKeyEstablishedTime();
            oldPeer2.dropOutbound();
            _introManager.remove(oldPeer2);
            _expireEvent.remove(oldPeer2);
        }

        if (_log.shouldLog(Log.WARN) && !_mismatchLogged && _peersByIdent.size() != _peersByRemoteHost.size()) {
            _mismatchLogged = true;
            _log.warn("Size Mismatch after add: " + peer
                       + " byIDsz = " + _peersByIdent.size()
                       + " byHostsz = " + _peersByRemoteHost.size());
        }
        
        _activeThrottle.unchoke(peer.getRemotePeer());
        markReachable(peer.getRemotePeer(), peer.isInbound());
        //_context.banlist().unbanlistRouter(peer.getRemotePeer(), STYLE);

        //if (SHOULD_FLOOD_PEERS)
        //    _flooder.addPeer(peer);
        
        _expireEvent.add(peer);
        
        _introManager.add(peer);
        
        if (oldEstablishedOn > 0)
            _context.statManager().addRateData("udp.alreadyConnected", oldEstablishedOn);
        
        synchronized(_rebuildLock) {
            rebuildIfNecessary();
            Status status = getReachabilityStatus();
            if (status != Status.OK &&
                status != Status.IPV4_OK_IPV6_UNKNOWN &&
                status != Status.IPV4_OK_IPV6_FIREWALLED &&
                status != Status.IPV4_DISABLED_IPV6_OK &&
                status != Status.IPV4_DISABLED_IPV6_UNKNOWN &&
                status != Status.IPV4_DISABLED_IPV6_FIREWALLED &&
                status != Status.DISCONNECTED &&
                _reachabilityStatusUnchanged < 7) {
                _testEvent.forceRunSoon(peer.isIPv6());
            }
        }
        return true;
    }
    
/*** infinite loop
    public RouterAddress getCurrentAddress() {
        if (needsRebuild())
            rebuildExternalAddress(false);
        return super.getCurrentAddress();
    }
***/
    
    @Override
    public void messageReceived(I2NPMessage inMsg, RouterIdentity remoteIdent, Hash remoteIdentHash, long msToReceive, int bytesReceived) {
        if (inMsg.getType() == DatabaseStoreMessage.MESSAGE_TYPE) {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)inMsg;
            DatabaseEntry entry = dsm.getEntry();
            if (entry == null)
                return;
            if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO &&
                ((RouterInfo) entry).getNetworkId() != _networkID) {
                // this is pre-0.6.1.10, so it isn't going to happen any more

                /*
                if (remoteIdentHash != null) {
                    _context.banlist().banlistRouter(remoteIdentHash, "Sent us a peer from the wrong network");
                    dropPeer(remoteIdentHash);
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Dropping the peer " + remoteIdentHash
                                   + " because they are in the wrong net");
                } else if (remoteIdent != null) {
                    _context.banlist().banlistRouter(remoteIdent.calculateHash(), "Sent us a peer from the wrong network");
                    dropPeer(remoteIdent.calculateHash());
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Dropping the peer " + remoteIdent.calculateHash()
                                   + " because they are in the wrong net");
                }
                 */
                Hash peerHash = entry.getHash();
                PeerState peer = getPeerState(peerHash);
                if (peer != null) {
                    RemoteHostId remote = peer.getRemoteHostId();
                    _dropList.add(remote);
                    _context.statManager().addRateData("udp.dropPeerDroplist", 1);
                    _context.simpleTimer2().addEvent(new RemoveDropList(remote), DROPLIST_PERIOD);
                }
                markUnreachable(peerHash);
                _context.banlist().banlistRouter(peerHash, "Part of the wrong network, version = " + ((RouterInfo) entry).getVersion());
                //_context.banlist().banlistRouter(peerHash, "Part of the wrong network", STYLE);
                if (peer != null)
                    sendDestroy(peer);
                dropPeer(peerHash, false, "wrong network");
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping the peer " + peerHash + " because they are in the wrong net: " + entry);
                return;
            } else {
                if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Received an RI from the same net");
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Received a leaseSet: " + dsm);
                }
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received another message: " + inMsg.getClass().getName());
        }
        PeerState peer = getPeerState(remoteIdentHash);
        super.messageReceived(inMsg, remoteIdent, remoteIdentHash, msToReceive, bytesReceived);
        if (peer != null)
            peer.expireInboundMessages();
    }

    private class RemoveDropList implements SimpleTimer.TimedEvent {
        private final RemoteHostId _peer;
        public RemoveDropList(RemoteHostId peer) { _peer = peer; }
        public void timeReached() { 
            _dropList.remove(_peer);
        }
    }
    
    boolean isInDropList(RemoteHostId peer) { return _dropList.contains(peer); }
    
    /**
     *  This does not send a session destroy, caller must do that if desired.
     *
     *  @param shouldBanlist doesn't really, only sets unreachable
     */
    void dropPeer(Hash peer, boolean shouldBanlist, String why) {
        PeerState state = getPeerState(peer);
        if (state != null)
            dropPeer(state, shouldBanlist, why);
    }

    /**
     *  This does not send a session destroy, caller must do that if desired.
     *
     *  @param shouldBanlist doesn't really, only sets unreachable
     */
    void dropPeer(PeerState peer, boolean shouldBanlist, String why) {
        if (_log.shouldLog(Log.INFO)) {
            long now = _context.clock().now();
            StringBuilder buf = new StringBuilder(4096);
            long timeSinceSend = now - peer.getLastSendTime();
            long timeSinceRecv = now - peer.getLastReceiveTime();
            long timeSinceAck  = now - peer.getLastACKSend();
            long timeSinceSendOK = now - peer.getLastSendFullyTime();
            int consec = peer.getConsecutiveFailedSends();
            buf.append("Dropping remote peer: ").append(peer.toString()).append(" banlist? ").append(shouldBanlist);
            buf.append(" lifetime: ").append(now - peer.getKeyEstablishedTime());
            buf.append(" time since send/fully/recv/ack: ").append(timeSinceSend).append(" / ");
            buf.append(timeSinceSendOK).append(" / ");
            buf.append(timeSinceRecv).append(" / ").append(timeSinceAck);
            buf.append(" consec failures: ").append(consec);
            if (why != null)
                buf.append(" cause: ").append(why);
            /*
            buf.append("Existing peers: \n");
            synchronized (_peersByIdent) {
                for (Iterator iter = _peersByIdent.keySet().iterator(); iter.hasNext(); ) {
                    Hash c = (Hash)iter.next();
                    PeerState p = (PeerState)_peersByIdent.get(c);
                    if (c.equals(peer.getRemotePeer())) {
                        if (p != peer) {
                            buf.append(" SAME PEER, DIFFERENT STATE ");
                        } else {
                            buf.append(" same peer, same state ");
                        }
                    } else {
                        buf.append("Peer ").append(p.toString()).append(" ");
                    }
                    
                    buf.append(" lifetime: ").append(now - p.getKeyEstablishedTime());
                    
                    timeSinceSend = now - p.getLastSendTime();
                    timeSinceRecv = now - p.getLastReceiveTime();
                    timeSinceAck  = now - p.getLastACKSend();
                    
                    buf.append(" time since send/recv/ack: ").append(timeSinceSend).append(" / ");
                    buf.append(timeSinceRecv).append(" / ").append(timeSinceAck);
                    buf.append("\n");
                }
            }
             */
            _log.info(buf.toString(), new Exception("Dropped by"));
        }
        synchronized(_addDropLock) {
            locked_dropPeer(peer, shouldBanlist, why);
        }
        rebuildIfNecessary();
    }

    /**
     *  This does not send a session destroy, caller must do that if desired.
     *
     *  @param shouldBanlist doesn't really, only sets unreachable
     */
    private void locked_dropPeer(PeerState peer, boolean shouldBanlist, String why) {
        peer.dropOutbound();
        peer.expireInboundMessages();
        _introManager.remove(peer);
        _fragments.dropPeer(peer);
        
        PeerState altByIdent = null;
        if (peer.getRemotePeer() != null) {
            dropPeerCapacities(peer);
            
            if (shouldBanlist) {
                markUnreachable(peer.getRemotePeer());
                //_context.banlist().banlistRouter(peer.getRemotePeer(), "dropped after too many retries", STYLE);
            }
            long now = _context.clock().now();
            _context.statManager().addRateData("udp.droppedPeer", now - peer.getLastReceiveTime(), now - peer.getKeyEstablishedTime());
            altByIdent = _peersByIdent.remove(peer.getRemotePeer());
        }
        
        RemoteHostId remoteId = peer.getRemoteHostId();
        PeerState altByHost = _peersByRemoteHost.remove(remoteId);

        if (altByIdent != altByHost && _log.shouldLog(Log.WARN)) 
            _log.warn("Mismatch on remove, RHID = " + remoteId
                      + " byID = " + altByIdent
                      + " byHost = " + altByHost
                      + " byIDsz = " + _peersByIdent.size()
                      + " byHostsz = " + _peersByRemoteHost.size());
        
        // unchoke 'em, but just because we'll never talk again...
        _activeThrottle.unchoke(peer.getRemotePeer());
        
        //if (SHOULD_FLOOD_PEERS)
        //    _flooder.removePeer(peer);
        _expireEvent.remove(peer);
        
        // deal with races to make sure we drop the peers fully
        if ( (altByIdent != null) && (peer != altByIdent) ) locked_dropPeer(altByIdent, shouldBanlist, "recurse");
        if ( (altByHost != null) && (peer != altByHost) ) locked_dropPeer(altByHost, shouldBanlist, "recurse");
    }
    
    /**
     *  Rebuild the IPv4 external address if required
     */
    private void rebuildIfNecessary() {
        synchronized (_rebuildLock) {
            if (locked_needsRebuild())
                rebuildExternalAddress();
        }
    }

    private boolean locked_needsRebuild() {
        if (_needsRebuild) return true; // simple enough
        if (_context.router().isHidden()) return false;
        boolean v6Only = getIPv6Config() == IPV6_ONLY;
        RouterAddress addr = getCurrentAddress(v6Only);
        if (!v6Only && introducersRequired()) {
            UDPAddress ua = new UDPAddress(addr);
            long now = _context.clock().now();
            int valid = 0;
            for (int i = 0; i < ua.getIntroducerCount(); i++) {
                // warning: this is only valid as long as we use the ident hash as their key.
                byte[] key = ua.getIntroducerKey(i);
                if (key.length != Hash.HASH_LENGTH)
                    continue;
                long exp = ua.getIntroducerExpiration(i);
                if (exp > 0 && exp < now + INTRODUCER_EXPIRATION_MARGIN)
                    continue;
                PeerState peer = getPeerState(new Hash(key));
                if (peer != null)
                    valid++;
            }
            long sinceSelected = now - _introducersSelectedOn;
            if (valid >= PUBLIC_RELAY_COUNT) {
                // try to shift 'em around every 10 minutes or so
                if (sinceSelected > 17*60*1000) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Our introducers are valid, but haven't changed in " + DataHelper.formatDuration(sinceSelected) + ", so lets rechoose");
                    return true;
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Our introducers are valid and were selected " + DataHelper.formatDuration(sinceSelected) + " ago");
                    return false;
                }
            } else if (sinceSelected > 2*60*1000) {
                // Rate limit to prevent rapid churn after transition to firewalled or at startup
                if (_log.shouldLog(Log.INFO))
                    _log.info("Need more introducers (have " +valid + " need " + PUBLIC_RELAY_COUNT + ')');
                return true;
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Need more introducers (have " +valid + " need " + PUBLIC_RELAY_COUNT + ')' +
                              " but we just chose them " + DataHelper.formatDuration(sinceSelected) + " ago so wait");
                // TODO also check to see if we actually have more available
                return false;
            }
        } else {
            byte[] externalListenHost = addr != null ? addr.getIP() : null;
            int externalListenPort = addr != null ? addr.getPort() : -1;
            boolean rv = (externalListenHost == null) || (externalListenPort <= 0);
            if (!rv) {
                // shortcut to determine if introducers are present
                if (addr.getOption("ihost0") != null)
                    rv = true;  // status == ok and we don't actually need introducers, so rebuild
            }
            if (rv) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Need to initialize our direct SSU info (" + Addresses.toString(externalListenHost, externalListenPort) + ')');
            } else if (addr.getPort() <= 0 || addr.getHost() == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Our direct SSU info is initialized, but not used in our address yet");
                rv = true;
            } else {
                //_log.info("Our direct SSU info is initialized");
            }
            return rv;
        }
    }
    
    /**
     * Make sure we don't think this dropped peer is capable of doing anything anymore...
     *
     */
    private void dropPeerCapacities(PeerState peer) {
        /*
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer.getRemotePeer());
        if (info != null) {
            String capacities = info.getOptions().getProperty(UDPAddress.PROP_CAPACITY);
            if (capacities != null) {
                for (int i = 0; i < capacities.length(); i++) {
                    char capacity = capacities.charAt(i);
                    int cap = capacity - 'A';
                    if ( (cap < 0) || (cap >= _peersByCapacity.length) ) 
                        continue;
                    List peers = _peersByCapacity[cap];
                    synchronized (peers) {
                        peers.remove(peer);
                    }
                }
            }
        }
         */
    }
    
    /**
     *  This sends it directly out, bypassing OutboundMessageFragments.
     *  The only queueing is for the bandwidth limiter.
     *  BLOCKING if OB queue is full.
     */
    void send(UDPPacket packet) { 
        if (_pusher != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending packet " + packet);
            _pusher.send(packet); 
        } else {
            _log.error("No pusher", new Exception());
        }
    }
    
    /**
     *  Send a session destroy message, bypassing OMF and PacketPusher.
     *  BLOCKING if OB queue is full.
     *
     *  @since 0.8.9
     */
    void sendDestroy(PeerState peer) {
        // peer must be fully established
        if (peer.getCurrentCipherKey() == null)
            return;
        UDPPacket pkt = _destroyBuilder.buildSessionDestroyPacket(peer);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending destroy to : " + peer);
        send(pkt);
    }

    /**
     *  Send a session destroy message to everybody.
     *  BLOCKING for at least 1 sec per 1K peers, more if BW is very low or if OB queue is full.
     *
     *  @since 0.8.9
     */
    private void destroyAll() {
        for (UDPEndpoint endpoint : _endpoints) {
            endpoint.clearOutbound();
        }
        int howMany = _peersByIdent.size();
        // use no more than 1/4 of configured bandwidth
        final int burst = 8;
        int pps = Math.max(48, (_context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1000 / 4) /  48);
        int burstps = pps / burst;
        // max of 1000 pps
        int toSleep = Math.max(8, (1000 / burstps));
        int count = 0;
        if (_log.shouldInfo())
            _log.info("Sending destroy to : " + howMany + " peers");
        for (PeerState peer : _peersByIdent.values()) {
            sendDestroy(peer);
            // 1000 per second * 48 bytes = 400 KBps
            if ((++count) % burst == 0) { 
                try {
                    Thread.sleep(toSleep);
                } catch (InterruptedException ie) {}
            }
        }
        toSleep = Math.min(howMany / 3, 750);
        if (toSleep > 0) {
            try {
                Thread.sleep(toSleep);
            } catch (InterruptedException ie) {}
        }
    }

    public TransportBid bid(RouterInfo toAddress, long dataSize) {
        if (dataSize > OutboundMessageState.MAX_MSG_SIZE) {
            // NTCP max is lower, so msg will get dropped
            return null;
        }
        Hash to = toAddress.getIdentity().calculateHash();
        PeerState peer = getPeerState(to);
        if (peer != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("bidding on a message to an established peer: " + peer);
            if (preferUDP())
                return _cachedBid[FAST_PREFERRED_BID];
            else
                return _cachedBid[FAST_BID];
        } else {
            // If we don't have a port, all is lost
            if ( _reachabilityStatus == Status.HOSED) {
                markUnreachable(to);
                return null;
            }

            // Validate his SSU address
            RouterAddress addr = getTargetAddress(toAddress);
            if (addr == null) {
                markUnreachable(to);
                return null;
            }

            // Check for supported sig type
            SigType type = toAddress.getIdentity().getSigType();
            if (type == null || !type.isAvailable()) {
                markUnreachable(to);
                return null;
            }

            // Can we connect to them if we are not DSA?
            RouterInfo us = _context.router().getRouterInfo();
            if (us != null) {
                RouterIdentity id = us.getIdentity();
                if (id.getSigType() != SigType.DSA_SHA1) {
                    String v = toAddress.getVersion();
                    if (VersionComparator.comp(v, MIN_SIGTYPE_VERSION) < 0) {
                        markUnreachable(to);
                        return null;
                    }
                }
            }

            if (!allowConnection())
                return _cachedBid[TRANSIENT_FAIL_BID];

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("bidding on a message to an unestablished peer: " + to);

            // Try to maintain at least 5 peers (30 for v6) so we can determine our IP address and
            // we have a selection to run peer tests with.
            // If we are firewalled, and we don't have enough peers that volunteered to
            // also introduce us, also bid aggressively so we are preferred over NTCP.
            // (Otherwise we only talk UDP to those that are firewalled, and we will
            // never get any introducers)
            int count = _peersByIdent.size();
            if (alwaysPreferUDP()) {
                return _cachedBid[SLOW_PREFERRED_BID];
            } else if (count < _min_peers ||
                       (_haveIPv6Address && count < _min_v6_peers) ||
                       (introducersRequired() && _introManager.introducerCount() < MIN_INTRODUCER_POOL)) {
                 // Even if we haven't hit our minimums, give NTCP a chance some of the time.
                 // This may make things work a little faster at startup
                 // (especially when we have an IPv6 address and the increased minimums),
                 // and if UDP is completely blocked we'll still have some connectivity.
                 // TODO After some time, decide that UDP is blocked/broken and return TRANSIENT_FAIL_BID?
                if (_context.random().nextInt(4) == 0)
                    return _cachedBid[SLOWEST_BID];
                else
                    return _cachedBid[SLOW_PREFERRED_BID];
            } else if (preferUDP()) {
                return _cachedBid[SLOW_BID];
            } else if (haveCapacity()) {
                if (addr.getCost() > DEFAULT_COST)
                    return _cachedBid[SLOWEST_COST_BID];
                else
                    return _cachedBid[SLOWEST_BID];
            } else {
                if (addr.getCost() > DEFAULT_COST)
                    return _cachedBid[NEAR_CAPACITY_COST_BID];
                else
                    return _cachedBid[NEAR_CAPACITY_BID];
            }
        }
    }

    /**
     *  Get first available address we can use.
     *  @return address or null
     *  @since 0.9.6
     */
    RouterAddress getTargetAddress(RouterInfo target) {
        List<RouterAddress> addrs = getTargetAddresses(target);
        for (int i = 0; i < addrs.size(); i++) {
            RouterAddress addr = addrs.get(i);
            if (addr.getOption("ihost0") == null) {
                byte[] ip = addr.getIP();
                int port = addr.getPort();
                if (ip == null || !TransportUtil.isValidPort(port) ||
                    (!isValid(ip)) ||
                    (Arrays.equals(ip, getExternalIP()) && !allowLocal())) {
                    continue;
                }
            } else {
                // introducers
                if (getIPv6Config() == IPV6_ONLY)
                    continue;
            }
            return addr;
        }
        return null;
    }

    private boolean preferUDP() {
        String pref = _context.getProperty(PROP_PREFER_UDP, DEFAULT_PREFER_UDP);
        return (pref != null) && ! "false".equals(pref);
    }
    
    private boolean alwaysPreferUDP() {
        String pref = _context.getProperty(PROP_PREFER_UDP, DEFAULT_PREFER_UDP);
        return (pref != null) && "always".equals(pref);
    }
    
    // We used to have MAX_IDLE_TIME = 5m, but this causes us to drop peers
    // and lose the old introducer tags, causing introduction fails,
    // so we keep the max time long to give the introducer keepalive code
    // in the IntroductionManager a chance to work.
    public static final int EXPIRE_TIMEOUT = 20*60*1000;
    private static final int MAX_IDLE_TIME = EXPIRE_TIMEOUT;
    public static final int MIN_EXPIRE_TIMEOUT = 165*1000;
    
    public String getStyle() { return STYLE; }

    @Override
    public void send(OutNetMessage msg) { 
        if (msg == null) return;
        if (msg.getTarget() == null) return;
        if (msg.getTarget().getIdentity() == null) return;
        if (_establisher == null) {
            failed(msg, "UDP not up yet");
            return;    
        }

        msg.timestamp("sending on UDP transport");
        Hash to = msg.getTarget().getIdentity().calculateHash();
        PeerState peer = getPeerState(to);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending to " + (to != null ? to.toString() : ""));
        if (peer != null) {
            long lastSend = peer.getLastSendFullyTime();
            long lastRecv = peer.getLastReceiveTime();
            long now = _context.clock().now();
            int inboundActive = peer.expireInboundMessages();
            if ( (lastSend > 0) && (lastRecv > 0) ) {
                if ( (now - lastSend > MAX_IDLE_TIME) && 
                     (now - lastRecv > MAX_IDLE_TIME) && 
                     (peer.getConsecutiveFailedSends() > 0) &&
                     (inboundActive <= 0)) {
                    // peer is waaaay idle, drop the con and queue it up as a new con
                    dropPeer(peer, false, "proactive reconnection");
                    msg.timestamp("peer is really idle, dropping con and reestablishing");
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Proactive reestablish to " + to);
                    _establisher.establish(msg);
                    _context.statManager().addRateData("udp.proactiveReestablish", now-lastSend, now-peer.getKeyEstablishedTime());
                    return;
                }
            }
            msg.timestamp("enqueueing for an already established peer");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Add to fragments for " + to);

            // See comments in DummyThrottle.java
            if (USE_PRIORITY)
                _outboundMessages.add(msg);
            else  // skip the priority queue and go straight to the active pool
                _fragments.add(msg);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Establish new connection to " + to);
            msg.timestamp("establishing a new connection");
            _establisher.establish(msg);
        }
    }

    /**
     *  Send only if established, otherwise fail immediately.
     *  Never queue with the establisher.
     *  @since 0.9.2
     */
    void sendIfEstablished(OutNetMessage msg) { 
        _fragments.add(msg);
    }

    /**
     *  "injected" message from the EstablishmentManager.
     *  If you have multiple messages, use the list variant,
     *  so the messages may be bundled efficiently.
     *
     *  @param peer all messages MUST be going to this peer
     */
    void send(I2NPMessage msg, PeerState peer) {
        try {
            OutboundMessageState state = new OutboundMessageState(_context, msg, peer);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Injecting a data message to a new peer: " + peer);
            _fragments.add(state, peer);
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Shouldnt happen", new Exception("I did it"));
        }
    }

    /**
     *  "injected" message from the EstablishmentManager,
     *  plus pending messages to send,
     *  so the messages may be bundled efficiently.
     *  Called at end of outbound establishment.
     *
     *  @param msg may be null if nothing to inject
     *  @param msgs non-null, may be empty
     *  @param peer all messages MUST be going to this peer
     *  @since 0.9.24
     */
    void send(I2NPMessage msg, List<OutNetMessage> msgs, PeerState peer) {
        try {
            int sz = msgs.size();
            List<OutboundMessageState> states = new ArrayList<OutboundMessageState>(sz + 1);
            if (msg != null) {
                OutboundMessageState state = new OutboundMessageState(_context, msg, peer);
                states.add(state);
            }
            for (int i = 0; i < sz; i++) {
                OutboundMessageState state = new OutboundMessageState(_context, msgs.get(i), peer);
                states.add(state);
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Injecting " + states.size() + " data messages to a new peer: " + peer);
            _fragments.add(states, peer);
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Shouldnt happen", new Exception("I did it"));
        }
    }

    /**
     *  "injected" messages from the EstablishmentManager.
     *  Called at end of inbound establishment.
     *
     *  @param peer all messages MUST be going to this peer
     *  @since 0.9.24
     */
    void send(List<I2NPMessage> msgs, PeerState peer) {
        try {
            int sz = msgs.size();
            List<OutboundMessageState> states = new ArrayList<OutboundMessageState>(sz);
            for (int i = 0; i < sz; i++) {
                OutboundMessageState state = new OutboundMessageState(_context, msgs.get(i), peer);
                states.add(state);
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Injecting " + sz + " data messages to a new peer: " + peer);
            _fragments.add(states, peer);
        } catch (IllegalArgumentException iae) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Shouldnt happen", new Exception("I did it"));
        }
    }
    
    // we don't need the following, since we have our own queueing
    protected void outboundMessageReady() { throw new UnsupportedOperationException("Not used for UDP"); }
    
    public void startListening() {
        startup();
    }
    
    public void stopListening() {
        shutdown();
        replaceAddress(null);
    }
    
    private boolean explicitAddressSpecified() {
        String h = _context.getProperty(PROP_EXTERNAL_HOST);
        // Bug in config.jsp prior to 0.7.14, sets an empty host config
        return h != null && h.length() > 0;
    }
    
    /**
     * Rebuild to get updated cost and introducers.
     * Do not tell the router (he is the one calling this)
     * @since 0.7.12
     */
    @Override
    public List<RouterAddress> updateAddress() {
        rebuildExternalAddress(false);
        return getCurrentAddresses();
    }

    /**
     *  Update our IPv4 addresses AND tell the router to rebuild and republish the router info.
     *
     *  @return the new address if changed, else null
     */
    private RouterAddress rebuildExternalAddress() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("REA1");
        return rebuildExternalAddress(true);
    }

    /**
     *  Update our IPv4 address and optionally tell the router to rebuild and republish the router info.
     *
     *  If PROP_EXTERNAL_HOST is set, use those addresses (comma/space separated).
     *  If a hostname is configured in that property, use it.
     *  As of 0.9.32, a hostname is resolved here into one or more addresses
     *  and the IPs are published, to implement proposal 141.
     *
     *  A max of one v4 and one v6 address will be set. Significant changes both
     *  here and in NTCP would be required to publish multiple v4 or v6 addresses.
     *
     *  @param allowRebuildRouterInfo whether to tell the router
     *  @return the new address if changed, else null
     */
    private RouterAddress rebuildExternalAddress(boolean allowRebuildRouterInfo) {
        if (_log.shouldDebug())
            _log.debug("REA2 " + allowRebuildRouterInfo);
        // if the external port is specified, we want to use that to bind to even
        // if we don't know the external host.
        int port = _context.getProperty(PROP_EXTERNAL_PORT, -1);
        
        String host = null;
        if (explicitAddressSpecified()) {
            host = _context.getProperty(PROP_EXTERNAL_HOST);
            if (host != null) {
                String[] hosts = DataHelper.split(host, "[,; \r\n\t]");
                RouterAddress rv = null;
                // we only take one each of v4 and v6
                boolean v4 = false;
                boolean v6 = false;
                // prevent adding a type if disabled
                TransportUtil.IPv6Config cfg = getIPv6Config();
                if (cfg == IPV6_DISABLED)
                    v6 = true;
                else if (cfg == IPV6_ONLY)
                    v4 = true;
                for (int i = 0; i < hosts.length; i++) {
                    String h = hosts[i];
                    if (h.length() <= 0)
                        continue;
                    if (Addresses.isIPv4Address(h)) {
                        if (v4)
                            continue;
                        v4 = true;
                    } else if (Addresses.isIPv6Address(h)) {
                        if (v6)
                            continue;
                        v6 = true;
                    } else {
                        int valid = 0;
                        List<byte[]> ips = Addresses.getIPs(h);
                        if (ips != null) {
                            for (byte[] ip : ips) {
                                if (!isValid(ip)) {
                                    if (_log.shouldWarn())
                                        _log.warn("REA2: skipping invalid " + Addresses.toString(ip) + " for " + h);
                                    continue;
                                }
                                if ((v4 && ip.length == 4) || (v6 && ip.length == 16)) {
                                    if (_log.shouldWarn())
                                        _log.warn("REA2: skipping additional " + Addresses.toString(ip) + " for " + h);
                                    continue;
                                }
                                if (ip.length == 4)
                                    v4 = true;
                                else if (ip.length == 16)
                                    v6 = true;
                                valid++;
                                if (_log.shouldDebug())
                                    _log.debug("REA2: adding " + Addresses.toString(ip) + " for " + h);
                                RouterAddress trv = rebuildExternalAddress(ip, port, allowRebuildRouterInfo);
                                if (trv != null)
                                    rv = trv;
                            }
                        }
                        if (valid == 0)
                            _log.error("No valid IPs for configured hostname " + h);
                        continue;
                    }
                    RouterAddress trv = rebuildExternalAddress(h, port, allowRebuildRouterInfo);
                    if (trv != null)
                        rv = trv;
                }
                return rv;
            }
        } else {
            if (!introducersRequired()) {
                boolean v6Only = getIPv6Config() == IPV6_ONLY;
                RouterAddress cur = getCurrentExternalAddress(v6Only);
                if (cur != null)
                    host = cur.getHost();
            }
        }
        return rebuildExternalAddress(host, port, allowRebuildRouterInfo);
    }
            
    /**
     *  Update our IPv4 or IPv6 address and optionally tell the router to rebuild and republish the router info.
     *
     *  @param ip new ip valid IPv4 or IPv6 or null
     *  @param port new valid port or -1
     *  @param allowRebuildRouterInfo whether to tell the router
     *  @return the new address if changed, else null
     *  @since IPv6
     */
    private RouterAddress rebuildExternalAddress(byte[] ip, int port, boolean allowRebuildRouterInfo) {
        if (_log.shouldDebug())
            _log.debug("REA3 " + Addresses.toString(ip, port));
        if (ip == null)
            return rebuildExternalAddress((String) null, port, allowRebuildRouterInfo);
        if (isValid(ip))
            return rebuildExternalAddress(Addresses.toString(ip), port, allowRebuildRouterInfo);
        return null;
    }

    /**
     *  Update our IPv4 or IPv6 address and optionally tell the router to rebuild and republish the router info.
     *  FIXME no way to remove an IPv6 address
     *
     *  @param host new validated IPv4 or IPv6 or DNS hostname or null
     *  @param port new validated port or 0/-1
     *  @param allowRebuildRouterInfo whether to tell the router
     *  @return the new address if changed, else null
     *  @since IPv6
     */
    private RouterAddress rebuildExternalAddress(String host, int port, boolean allowRebuildRouterInfo) {
        synchronized (_rebuildLock) {
            return locked_rebuildExternalAddress(host, port, allowRebuildRouterInfo);
        }
    }

    private RouterAddress locked_rebuildExternalAddress(String host, int port, boolean allowRebuildRouterInfo) {
        if (_log.shouldDebug())
            _log.debug("REA4 " + host + ':' + port);
        if (_context.router().isHidden())
            return null;
        
        OrderedProperties options = new OrderedProperties(); 
        boolean directIncluded;
        // DNS name assumed IPv4
        boolean isIPv6 = host != null && host.contains(":");
        boolean introducersRequired = (!isIPv6) && introducersRequired();
        if (!introducersRequired && allowDirectUDP() && port > 0 && host != null) {
            options.setProperty(UDPAddress.PROP_PORT, String.valueOf(port));
            options.setProperty(UDPAddress.PROP_HOST, host);
            directIncluded = true;
        } else {
            directIncluded = false;
        }

        boolean introducersIncluded = false;
        if (introducersRequired) {
            // intro manager now sorts introducers, so
            // deepEquals() below will not fail even with same introducers.
            // Was only a problem when we had very very few peers to pick from.
            RouterAddress current = getCurrentAddress(isIPv6);
            int found = _introManager.pickInbound(current, options, PUBLIC_RELAY_COUNT);
            if (found > 0) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Direct? " + directIncluded + " reqd? " + introducersRequired +
                              " picked introducers: " + found);
                _introducersSelectedOn = _context.clock().now();
                introducersIncluded = true;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Direct? " + directIncluded + " reqd? " + introducersRequired +
                              " no introducers");
            }
        }
        
        // if we have explicit external addresses, they had better be reachable
        if (introducersRequired)
            options.setProperty(UDPAddress.PROP_CAPACITY, CAP_TESTING);
        else
            options.setProperty(UDPAddress.PROP_CAPACITY, CAP_TESTING_INTRO);

        // MTU since 0.9.2
        int mtu;
        if (host == null) {
            mtu = _mtu;
        } else {
            try {
                InetAddress ia = InetAddress.getByName(host);
                mtu = setMTU(ia);
            } catch (UnknownHostException uhe) {
                mtu = _mtu;
            }
        }
        if (mtu != PeerState.LARGE_MTU)
            options.setProperty(UDPAddress.PROP_MTU, Integer.toString(mtu));

        if (directIncluded || introducersIncluded) {
            // This is called via TransportManager.configTransports() before startup(), prevent NPE
            // Note that peers won't connect to us without this - see EstablishmentManager
            if (_introKey != null)
                options.setProperty(UDPAddress.PROP_INTRO_KEY, _introKey.toBase64());

            // SSU seems to regulate at about 85%, so make it a little higher.
            // If this is too low, both NTCP and SSU always have incremented cost and
            // the whole mechanism is not helpful.
            int cost = DEFAULT_COST;
            if (ADJUST_COST && !haveCapacity(91))
                cost += CONGESTION_COST_ADJUSTMENT;
            if (introducersIncluded)
                cost += 2;
            if (isIPv6) {
                TransportUtil.IPv6Config config = getIPv6Config();
                if (config == IPV6_PREFERRED)
                    cost--;
                else if (config == IPV6_NOT_PREFERRED)
                    cost++;
            }
            RouterAddress addr = new RouterAddress(STYLE, options, cost);

            RouterAddress current = getCurrentAddress(isIPv6);
            boolean wantsRebuild = !addr.deepEquals(current);

            // save the external address, even if we didn't publish it
            if (port > 0 && host != null) {
                RouterAddress local;
                if (directIncluded) {
                    local = addr;
                } else {
                    OrderedProperties localOpts = new OrderedProperties(); 
                    localOpts.setProperty(UDPAddress.PROP_PORT, String.valueOf(port));
                    localOpts.setProperty(UDPAddress.PROP_HOST, host);
                    local = new RouterAddress(STYLE, localOpts, cost);
                }
                replaceCurrentExternalAddress(local, isIPv6);
            }

            if (wantsRebuild) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Address rebuilt: " + addr, new Exception());
                replaceAddress(addr);
                // warning, this calls back into us with allowRebuildRouterInfo = false,
                // via CSFI.createAddresses->TM.getAddresses()->updateAddress()->REA
                if (allowRebuildRouterInfo)
                    _context.router().rebuildRouterInfo();
            } else {
                addr = null;
            }
        
            if (!isIPv6)
                _needsRebuild = false;
            return addr;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Wanted to rebuild my SSU address, but couldn't specify either the direct or indirect info (needs introducers? " 
                           + introducersRequired + ")", new Exception("source"));
            _needsRebuild = true;
            // save the external address, even if we didn't publish it
            if (port > 0 && host != null) {
                OrderedProperties localOpts = new OrderedProperties(); 
                localOpts.setProperty(UDPAddress.PROP_PORT, String.valueOf(port));
                localOpts.setProperty(UDPAddress.PROP_HOST, host);
                RouterAddress local = new RouterAddress(STYLE, localOpts, DEFAULT_COST);
                replaceCurrentExternalAddress(local, isIPv6);
            }
            if (hasCurrentAddress()) {
                // We must remove current address, otherwise the user will see
                // "firewalled with inbound NTCP enabled" warning in console.
                // Remove the IPv4 address only
                removeAddress(false);
                // warning, this calls back into us with allowRebuildRouterInfo = false,
                // via CSFI.createAddresses->TM.getAddresses()->updateAddress()->REA
                if (allowRebuildRouterInfo)
                    _context.router().rebuildRouterInfo();
            }
            return null;
        }
    }

    /**
     *  Simple storage of IP and port, since
     *  we don't put them in the real, published RouterAddress anymore
     *  if we are firewalled.
     *
     *  Caller must sync on _rebuildLock
     *
     *  @since 0.9.18
     */
    private void replaceCurrentExternalAddress(RouterAddress ra, boolean isIPv6) {
        if (isIPv6)
            _currentOurV6Address = ra;
        else
            _currentOurV4Address = ra;
    }

    /**
     *  Simple fetch of stored IP and port, since
     *  we don't put them in the real, published RouterAddress anymore
     *  if we are firewalled.
     *
     *  @since 0.9.18
     */
    private RouterAddress getCurrentExternalAddress(boolean isIPv6) {
        // deadlock thru here ticket #1699
        synchronized (_rebuildLock) {
            return isIPv6 ? _currentOurV6Address : _currentOurV4Address;
        }
    }

    /**
     *  Replace then tell NTCP that we changed.
     *
     *  @param address the new address or null to remove all
     */
    @Override
    protected void replaceAddress(RouterAddress address) {
        super.replaceAddress(address);
        _context.commSystem().notifyReplaceAddress(address);
    }

    /**
     *  Remove then tell NTCP that we changed.
     *
     *  @since 0.9.20
     */
    @Override
    protected void removeAddress(RouterAddress address) {
        super.removeAddress(address);
        _context.commSystem().notifyRemoveAddress(address);
    }

    /**
     *  Remove then tell NTCP that we changed.
     *
     *  @since 0.9.20
     */
    @Override
    protected void removeAddress(boolean ipv6) {
        super.removeAddress(ipv6);
        if (ipv6)
            _lastInboundIPv6 = 0;
        _context.commSystem().notifyRemoveAddress(ipv6);
    }

    /**
     *  Calls replaceAddress(address), then shuts down the router if
     *  dynamic keys is enabled, which it never is, so all this is unused.
     *
     *  @param address the new address or null to remove all
     */
/****
    protected void replaceAddress(RouterAddress address, RouterAddress oldAddress) {
        replaceAddress(address);
        if (oldAddress != null) {
            UDPAddress old = new UDPAddress(oldAddress);
            InetAddress oldHost = old.getHostAddress();
            UDPAddress newAddr = new UDPAddress(address);
            InetAddress newHost = newAddr.getHostAddress();
            if ( (old.getPort() > 0) && (oldHost != null) && (isValid(oldHost.getAddress())) &&
                 (newAddr.getPort() > 0) && (newHost != null) && (isValid(newHost.getAddress())) ) {
                if ( (old.getPort() != newAddr.getPort()) || (!oldHost.equals(newHost)) ) {
                    // substantial data has changed, so if we are in 'dynamic keys' mode, restart the 
                    // router hard and regenerate a new identity
                    if (_context.getBooleanProperty(Router.PROP_DYNAMIC_KEYS)) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("SSU address updated. new address: " 
                                       + newAddr.getHostAddress() + ":" + newAddr.getPort() + ", old address: " 
                                       + old.getHostAddress() + ":" + old.getPort());
                        // shutdown itself checks the DYNAMIC_KEYS flag, and if its set to true, deletes
                        // the keys
                        _context.router().shutdown(Router.EXIT_HARD_RESTART);              
                    }
                }
            }
        }
    }
****/
    
    /**
     *  Do we require introducers?
     */
    public boolean introducersRequired() {
        /******************
         *  Don't do this anymore, as we are removing the checkbox from the UI,
         *  and we rarely if ever see the problem of false negatives for firewall detection -
         *  it's usually false positives.
         ******************
        String forceIntroducers = _context.getProperty(PROP_FORCE_INTRODUCERS);
        if ( (forceIntroducers != null) && (Boolean.parseBoolean(forceIntroducers)) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Force introducers specified");
            return true;
        }
        *******************/
        Status status = getReachabilityStatus();
        switch (status) {
            case REJECT_UNSOLICITED:
            case DIFFERENT:
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Require introducers, because our status is " + status);
                return true;

            default:
                if (!allowDirectUDP()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Require introducers, because we do not allow direct UDP connections");
                    return true;
                }
                return false;
        }
    }
    
    /**
     *  MIGHT we require introducers?
     *  This is like introducersRequired, but if we aren't sure, this returns true.
     *  Used only by EstablishmentManager.
     *
     *  @since 0.9.24
     */
    boolean introducersMaybeRequired() {
        Status status = getReachabilityStatus();
        switch (status) {
            case REJECT_UNSOLICITED:
            case DIFFERENT:
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
            case IPV4_UNKNOWN_IPV6_OK:
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
            case UNKNOWN:
                return true;

            default:
                return !allowDirectUDP();
        }
    }
    
    /**
     *  For EstablishmentManager
     *  @since 0.9.3
     */
    boolean canIntroduce() {
        // we don't expect inbound connections when hidden, but it could happen
        // Don't offer if we are approaching max connections. While Relay Intros do not
        // count as connections, we have to keep the connection to this peer up longer if
        // we are offering introductions.
        return
            (!_context.router().isHidden()) &&
            (!introducersRequired()) &&
            haveCapacity() &&
            (!_context.netDb().floodfillEnabled()) &&
            _introManager.introducedCount() < IntroductionManager.MAX_OUTBOUND &&
            _introManager.introducedCount() < getMaxConnections() / 4;
    }

    /** default true */
    private boolean allowDirectUDP() {
        return _context.getBooleanPropertyDefaultTrue(PROP_ALLOW_DIRECT);
    }

    String getPacketHandlerStatus() {
        PacketHandler handler = _handler;
        if (handler != null)
            return handler.getHandlerStatus();
        else
            return "";
    }

    /** @since IPv6 */
    PacketHandler getPacketHandler() {
        return _handler;
    }

    public void failed(OutboundMessageState msg) { failed(msg, true); }

    void failed(OutboundMessageState msg, boolean allowPeerFailure) {
        if (msg == null) return;
        OutNetMessage m = msg.getMessage();
        if ( allowPeerFailure && (msg.getPeer() != null) && 
             ( (msg.getMaxSends() >= OutboundMessageFragments.MAX_VOLLEYS) ||
               (msg.isExpired())) ) {
            //long recvDelay = _context.clock().now() - msg.getPeer().getLastReceiveTime();
            //long sendDelay = _context.clock().now() - msg.getPeer().getLastSendFullyTime();
            //if (m != null)
            //    m.timestamp("message failure - volleys = " + msg.getMaxSends() 
            //                + " lastReceived: " + recvDelay
            //                + " lastSentFully: " + sendDelay
            //                + " expired? " + msg.isExpired());
            int consecutive = msg.getPeer().incrementConsecutiveFailedSends();
            if (_log.shouldLog(Log.INFO))
                _log.info("Consecutive failure #" + consecutive 
                          + " on " + msg.toString()
                          + " to " + msg.getPeer());
            if ( (_context.clock().now() - msg.getPeer().getLastSendFullyTime() <= 60*1000) || (consecutive < MAX_CONSECUTIVE_FAILED) ) {
                // ok, a few conseutive failures, but we /are/ getting through to them
            } else {
                _context.statManager().addRateData("udp.dropPeerConsecutiveFailures", consecutive, msg.getPeer().getInactivityTime());
                sendDestroy(msg.getPeer());
                dropPeer(msg.getPeer(), false, "too many failures");
            }
            //if ( (consecutive > MAX_CONSECUTIVE_FAILED) && (msg.getPeer().getInactivityTime() > DROP_INACTIVITY_TIME))
            //    dropPeer(msg.getPeer(), false);
            //else if (consecutive > 2 * MAX_CONSECUTIVE_FAILED) // they're sending us data, but we cant reply?
            //    dropPeer(msg.getPeer(), false);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Failed sending " + msg + " to " + msg.getPeer());
        }
        noteSend(msg, false);
        if (m != null)
            super.afterSend(m, false);
    }
    
    private void noteSend(OutboundMessageState msg, boolean successful) {
        // bail before we do all the work
        if (!_context.messageHistory().getDoLog())
            return;
        int pushCount = msg.getPushCount();
        int sends = msg.getMaxSends();
        boolean expired = msg.isExpired();
     
        OutNetMessage m = msg.getMessage();
        PeerState p = msg.getPeer();
        StringBuilder buf = new StringBuilder(64);
        buf.append(" lifetime: ").append(msg.getLifetime());
        buf.append(" sends: ").append(sends);
        buf.append(" pushes: ").append(pushCount);
        buf.append(" expired? ").append(expired);
        buf.append(" unacked: ").append(msg.getUnackedSize());
        if ( (p != null) && (!successful) ) {
            buf.append(" consec_failed: ").append(p.getConsecutiveFailedSends());
            long timeSinceSend = _context.clock().now() - p.getLastSendFullyTime();
            buf.append(" lastFullSend: ").append(timeSinceSend);
            long timeSinceRecv = _context.clock().now() - p.getLastReceiveTime();
            buf.append(" lastRecv: ").append(timeSinceRecv);
            buf.append(" xfer: ").append(p.getSendBps()).append("/").append(p.getReceiveBps());
            buf.append(" mtu: ").append(p.getMTU());
            buf.append(" rto: ").append(p.getRTO());
            buf.append(" sent: ").append(p.getMessagesSent()).append("/").append(p.getPacketsTransmitted());
            buf.append(" recv: ").append(p.getMessagesReceived()).append("/").append(p.getPacketsReceived());
            buf.append(" uptime: ").append(_context.clock().now()-p.getKeyEstablishedTime());
        }
        if ( (m != null) && (p != null) ) {
            _context.messageHistory().sendMessage(m.getMessageType(), msg.getMessageId(), m.getExpiration(), 
                                                  p.getRemotePeer(), successful, buf.toString());
        } else {
            _context.messageHistory().sendMessage("establish", msg.getMessageId(), -1, 
                                                  (p != null ? p.getRemotePeer() : null), successful, buf.toString());
        }
    }
    
    public void failed(OutNetMessage msg, String reason) {
        if (msg == null) return;
        if (_log.shouldLog(Log.INFO))
            _log.info("Send failed: " + reason + " msg: " + msg, new Exception("failed from"));
        
        if (_context.messageHistory().getDoLog())
            _context.messageHistory().sendMessage(msg.getMessageType(), msg.getMessageId(), msg.getExpiration(), 
                                              msg.getTarget().getIdentity().calculateHash(), false, reason);
        super.afterSend(msg, false);
    }

    public void succeeded(OutboundMessageState msg) {
        if (msg == null) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending message succeeded: " + msg);
        noteSend(msg, true);
        OutNetMessage m = msg.getMessage();
        if (m != null)
            super.afterSend(m, true);
    }

    public int countPeers() {
            return _peersByIdent.size();
    }

    public int countActivePeers() {
        long old = _context.clock().now() - 5*60*1000;
        int active = 0;
        for (PeerState peer : _peersByIdent.values()) {
            // PeerState initializes times at construction,
            // so check message count also
            if ((peer.getMessagesReceived() > 0 && peer.getLastReceiveTime() >= old) ||
                (peer.getMessagesSent() > 0 && peer.getLastSendTime() >= old)) {
                active++;
            }
        }
        return active;
    }
    
    public int countActiveSendPeers() {
        long old = _context.clock().now() - 60*1000;
        int active = 0;
        for (PeerState peer : _peersByIdent.values()) {
                if (peer.getLastSendFullyTime() >= old)
                    active++;
            }
        return active;
    }
    
    @Override
    public boolean isEstablished(Hash dest) {
        return _peersByIdent.containsKey(dest);
    }

    /**
     *  @since 0.9.3
     */
    @Override
    public boolean isBacklogged(Hash dest) {
        PeerState peer =  _peersByIdent.get(dest);
        return peer != null && peer.isBacklogged();
    }

    /**
     * Tell the transport that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    @Override
    public void mayDisconnect(final Hash peer) {
        final PeerState ps =  _peersByIdent.get(peer);
        if (ps != null &&
            ps.getWeRelayToThemAs() <= 0 &&
            (ps.getTheyRelayToUsAs() <= 0 || ps.getIntroducerTime() < _context.clock().now() - 2*60*60*1000) &&
            ps.getMessagesReceived() <= 2 && ps.getMessagesSent() <= 2) {
            ps.setMayDisconnect();
        }
    }

    public boolean allowConnection() {
            return _peersByIdent.size() < getMaxConnections();
    }

    /**
     * Return our peer clock skews on this transport.
     * Vector composed of Long, each element representing a peer skew in seconds.
     * A positive number means our clock is ahead of theirs.
     */
    @Override
    public Vector<Long> getClockSkews() {
        Vector<Long> skews = new Vector<Long>();

        // If our clock is way off, we may not have many (or any) successful connections,
        // so try hard in that case to return good data
        boolean includeEverybody = _context.router().getUptime() < 10*60*1000 || _peersByIdent.size() < 10;
        long now = _context.clock().now();
        for (PeerState peer : _peersByIdent.values()) {
            if ((!includeEverybody) && now - peer.getLastReceiveTime() > 5*60*1000)
                continue; // skip old peers
            if (peer.getRTT() > PeerState.INIT_RTT - 250)
                continue; // Big RTT makes for a poor calculation
            skews.addElement(Long.valueOf(peer.getClockSkew() / 1000));
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("UDP transport returning " + skews.size() + " peer clock skews.");
        return skews;
    }
    
    /**
     *  @return a new DHSessionKeyBuilder
     *  @since 0.9
     */
    DHSessionKeyBuilder getDHBuilder() {
        return _dhFactory.getBuilder();
    }
    
    /**
     *  @return the factory
     *  @since 0.9.2
     */
    DHSessionKeyBuilder.Factory getDHFactory() {
        return _dhFactory;
    }
    
    /**
     * Does nothing
     * @deprecated as of 0.9.31
     */
    @Override
    @Deprecated
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException {
    }

    /*
     * Cache the bid to reduce object churn
     */
    private class SharedBid extends TransportBid {
        public SharedBid(int ms) { super(); setLatencyMs(ms); }
        @Override
        public Transport getTransport() { return UDPTransport.this; }
        @Override
        public String toString() { return "UDP bid @ " + getLatencyMs(); }
    }
    
    private class ExpirePeerEvent extends SimpleTimer2.TimedEvent {
        // TODO why have separate Set, just use _peersByIdent.values()
        private final Set<PeerState> _expirePeers;
        private final List<PeerState> _expireBuffer;
        private volatile boolean _alive;
        private int _runCount;
        private boolean _lastLoopShort;
        // we've seen firewalls change ports after 40 seconds
        private static final long PING_FIREWALL_TIME = 30*1000;
        private static final long PING_FIREWALL_CUTOFF = PING_FIREWALL_TIME / 2;
        // ping 1/4 of the peers every loop
        private static final int SLICES = 4;
        private static final long SHORT_LOOP_TIME = PING_FIREWALL_CUTOFF / (SLICES + 1);
        private static final long LONG_LOOP_TIME = 25*1000;
        private static final long EXPIRE_INCREMENT = 15*1000;
        private static final long EXPIRE_DECREMENT = 45*1000;
        private static final long MAY_DISCON_TIMEOUT = 10*1000;

        public ExpirePeerEvent() {
            super(_context.simpleTimer2());
            _expirePeers = new ConcurrentHashSet<PeerState>(128);
            _expireBuffer = new ArrayList<PeerState>();
        }

        public void timeReached() {
            // Increase allowed idle time if we are well under allowed connections, otherwise decrease
            boolean haveCap = haveCapacity(33);
            if (haveCap) {
                long inc;
                // don't adjust too quickly if we are looping fast
                if (_lastLoopShort)
                    inc = EXPIRE_INCREMENT * SHORT_LOOP_TIME / LONG_LOOP_TIME;
                else
                    inc = EXPIRE_INCREMENT;
                _expireTimeout = Math.min(_expireTimeout + inc, EXPIRE_TIMEOUT);
            } else {
                long dec;
                if (_lastLoopShort)
                    dec = EXPIRE_DECREMENT * SHORT_LOOP_TIME / LONG_LOOP_TIME;
                else
                    dec = EXPIRE_DECREMENT;
                _expireTimeout = Math.max(_expireTimeout - dec, MIN_EXPIRE_TIMEOUT);
            }
            long now = _context.clock().now();
            long shortInactivityCutoff = now - _expireTimeout;
            long longInactivityCutoff = now - EXPIRE_TIMEOUT;
            final long mayDisconCutoff = now - MAY_DISCON_TIMEOUT;
            long pingCutoff = now - (2 * 60*60*1000);
            long pingFirewallCutoff = now - PING_FIREWALL_CUTOFF;
            boolean shouldPingFirewall = _reachabilityStatus != Status.OK;
            int currentListenPort = getListenPort(false);
            boolean pingOneOnly = shouldPingFirewall && getExternalPort(false) == currentListenPort;
            boolean shortLoop = shouldPingFirewall || !haveCap || _context.netDb().floodfillEnabled();
            _lastLoopShort = shortLoop;
            _expireBuffer.clear();
            _runCount++;

                for (Iterator<PeerState> iter = _expirePeers.iterator(); iter.hasNext(); ) {
                    PeerState peer = iter.next();
                    long inactivityCutoff;
                    // if we offered to introduce them, or we used them as introducer in last 2 hours
                    if (peer.getWeRelayToThemAs() > 0 || peer.getIntroducerTime() > pingCutoff) {
                        inactivityCutoff = longInactivityCutoff;
                    } else if ((!haveCap || !peer.isInbound()) &&
                               peer.getMayDisconnect() &&
                               peer.getMessagesReceived() <= 2 && peer.getMessagesSent() <= 2) {
                        if (_log.shouldInfo())
                            _log.info("Possible early disconnect for: " + peer);
                        inactivityCutoff = mayDisconCutoff;
                    } else {
                        inactivityCutoff = shortInactivityCutoff;
                    }
                    if ( (peer.getLastReceiveTime() < inactivityCutoff) && (peer.getLastSendTime() < inactivityCutoff) ) {
                        _expireBuffer.add(peer);
                        iter.remove();
                    } else if (shouldPingFirewall &&
                               ((_runCount ^ peer.hashCode()) & (SLICES - 1)) == 0 &&
                               peer.getLastSendOrPingTime() < pingFirewallCutoff &&
                               peer.getLastReceiveTime() < pingFirewallCutoff) {
                        // ping if firewall is mapping the port to keep port the same...
                        // if the port changes we are screwed
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Pinging for firewall: " + peer);
                        // don't update or idle time won't be right and peer won't get dropped
                        // TODO if both sides are firewalled should only one ping
                        // or else session will stay open forever?
                        //peer.setLastSendTime(now);
                        send(_destroyBuilder.buildPing(peer));
                        peer.setLastPingTime(now);
                        // If external port is different, it may be changing the port for every
                        // session, so ping all of them. Otherwise only one.
                        if (pingOneOnly)
                            shouldPingFirewall = false;
		    }
                }

            if (!_expireBuffer.isEmpty()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Expiring " + _expireBuffer.size() + " peers");
                for (PeerState peer : _expireBuffer) {
                    sendDestroy(peer);
                    dropPeer(peer, false, "idle too long");
                    // TODO sleep to limit burst like in destroyAll() ??
                    // but we are on the timer thread...
                    // hopefully this isn't too many at once
                    // ... or only send a max of x, then requeue
                }
                _expireBuffer.clear();
            }

            if (_alive)
                schedule(shortLoop ? SHORT_LOOP_TIME : LONG_LOOP_TIME);
        }

        public void add(PeerState peer) {
                _expirePeers.add(peer);
        }

        public void remove(PeerState peer) {
                _expirePeers.remove(peer);
        }

        public void setIsAlive(boolean isAlive) {
            _alive = isAlive;
            if (isAlive) {
                reschedule(LONG_LOOP_TIME);
            } else {
                cancel();
                _expirePeers.clear();
            }
        }
    }
    
    /**
     *  IPv4 only
     */
    private void setReachabilityStatus(Status status) { 
        setReachabilityStatus(status, false);
    }
    
    /**
     *  @since 0.9.27
     *  @param isIPv6 Is the change an IPv6 change?
     */
    void setReachabilityStatus(Status status, boolean isIPv6) { 
        synchronized (_rebuildLock) {
            locked_setReachabilityStatus(status, isIPv6);
        }
    }

    /**
     *  @param isIPv6 Is the change an IPv6 change?
     */
    private void locked_setReachabilityStatus(Status newStatus, boolean isIPv6) { 
        Status old = _reachabilityStatus;
        // merge new status into old
        Status status = Status.merge(old, newStatus);
        _testEvent.setLastTested(isIPv6);
        // now modify if we are IPv6 only
        TransportUtil.IPv6Config config = getIPv6Config();
        if (config == IPV6_ONLY) {
            if (status == Status.IPV4_UNKNOWN_IPV6_OK)
                status = Status.IPV4_DISABLED_IPV6_OK;
            else if (status == Status.IPV4_UNKNOWN_IPV6_FIREWALLED)
                status = Status.IPV4_DISABLED_IPV6_FIREWALLED;
            else if (status == Status.UNKNOWN)
                status = Status.IPV4_DISABLED_IPV6_UNKNOWN;
        }
        if (status != Status.UNKNOWN) {
            // now modify if we have no IPv6 address
            if (_currentOurV6Address == null && !_haveIPv6Address) {
                if (status == Status.IPV4_OK_IPV6_UNKNOWN)
                    status = Status.OK;
                else if (status == Status.IPV4_FIREWALLED_IPV6_UNKNOWN)
                    status = Status.REJECT_UNSOLICITED;
                else if (status == Status.IPV4_SNAT_IPV6_UNKNOWN)
                    status = Status.DIFFERENT;
                // prevent firewalled -> OK -> firewalled+OK
                else if (status == Status.IPV4_FIREWALLED_IPV6_OK)
                    status = Status.REJECT_UNSOLICITED;
                else if (status == Status.IPV4_SNAT_IPV6_OK)
                    status = Status.DIFFERENT;
            }

            if (status != old) {
                // for the following transitions ONLY, require two in a row
                // to prevent thrashing
                if ((old == Status.OK && (status == Status.DIFFERENT ||
                                          status == Status.REJECT_UNSOLICITED ||
                                          status == Status.IPV4_FIREWALLED_IPV6_OK ||
                                          status == Status.IPV4_SNAT_IPV6_OK ||
                                          status == Status.IPV4_OK_IPV6_FIREWALLED)) ||
                    (status == Status.OK && (old == Status.DIFFERENT ||
                                             old == Status.REJECT_UNSOLICITED ||
                                             old == Status.IPV4_FIREWALLED_IPV6_OK ||
                                             old == Status.IPV4_SNAT_IPV6_OK ||
                                             old == Status.IPV4_OK_IPV6_FIREWALLED))) {
                    if (status != _reachabilityStatusPending) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Old status: " + old + " status pending confirmation: " + status +
                                      " Caused by update: " + newStatus);
                        _reachabilityStatusPending = status;
                        _testEvent.forceRunSoon(isIPv6);
                        return;
                    }
                }
                _reachabilityStatusUnchanged = 0;
                long now = _context.clock().now();
                _reachabilityStatusLastUpdated = now;
                _reachabilityStatus = status;
            } else {
                _reachabilityStatusUnchanged++;
            }
            _reachabilityStatusPending = status;
        }
        if (status != old) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Old status: " + old + " New status: " + status +
                          " Caused by update: " + newStatus +
                          " from: ", new Exception("traceback"));
            if (old != Status.UNKNOWN)
                _context.router().eventLog().addEvent(EventLog.REACHABILITY,
                   "from " + _t(old.toStatusString()) + " to " +  _t(status.toStatusString()));
            // Always rebuild when the status changes, even if our address hasn't changed,
            // as rebuildExternalAddress() calls replaceAddress() which calls CSFI.notifyReplaceAddress()
            // which will start up NTCP inbound when we transition to OK.
            // if (needsRebuild())
                rebuildExternalAddress();
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Status unchanged: " + _reachabilityStatus +
                          " after update: " + newStatus +
                          " (unchanged " + _reachabilityStatusUnchanged + " consecutive times), last updated " +
                          DataHelper.formatDuration(_context.clock().now() - _reachabilityStatusLastUpdated) + " ago");
        }
    }

    private static final String PROP_REACHABILITY_STATUS_OVERRIDE = "i2np.udp.status";

    /**
     * Previously returned short, now enum as of 0.9.20
     */
    public Status getReachabilityStatus() { 
        String override = _context.getProperty(PROP_REACHABILITY_STATUS_OVERRIDE);
        if (override != null) {
            if ("ok".equals(override))
                return Status.OK;
            else if ("err-reject".equals(override))
                return Status.REJECT_UNSOLICITED;
            else if ("err-different".equals(override))
                return Status.DIFFERENT;
        }
        return _reachabilityStatus; 
    }

    /**
     * @deprecated unused
     */
    @Override
    @Deprecated
    public void recheckReachability() {
        // FIXME locking if we do this again
        //_testEvent.runTest();
    }
    
    /**
     *  Pick a Bob (if we are Alice) or a Charlie (if we are Bob).
     *
     *  For Bob (as called from PeerTestEvent below), returns an established IPv4/v6 peer.
     *  While the protocol allows Alice to select an unestablished Bob, we don't support that.
     *
     *  For Charlie (as called from PeerTestManager), returns an established IPv4 or IPv6 peer.
     *  (doesn't matter how Bob and Charlie communicate)
     *
     *  Any returned peer must advertise an IPv4 address to prove it is IPv4-capable.
     *  Ditto for v6.
     *
     *  @param peerRole The role of the peer we are looking for, BOB or CHARLIE only (NOT our role)
     *  @param isIPv6 true to get a v6-capable peer back
     *  @param dontInclude may be null
     *  @return IPv4 peer or null
     */
    PeerState pickTestPeer(PeerTestState.Role peerRole, boolean isIPv6, RemoteHostId dontInclude) {
        if (peerRole == ALICE)
            throw new IllegalArgumentException();
        List<PeerState> peers = new ArrayList<PeerState>(_peersByIdent.values());
        for (Iterator<PeerState> iter = new RandomIterator<PeerState>(peers); iter.hasNext(); ) {
            PeerState peer = iter.next();
            if ( (dontInclude != null) && (dontInclude.equals(peer.getRemoteHostId())) )
                continue;
            // enforce IPv4/v6 connection if we are ALICE looking for a BOB
            byte[] ip = peer.getRemoteIP();
            if (peerRole == BOB) {
                if (isIPv6) {
                    if (ip.length != 16)
                        continue;
                } else {
                    if (ip.length != 4)
                        continue;
                }
            }
            // enforce IPv4/v6 advertised for all
            RouterInfo peerInfo = _context.netDb().lookupRouterInfoLocally(peer.getRemotePeer());
            if (peerInfo == null)
                continue;
            if (isIPv6) {
                String v = peerInfo.getVersion();
                if (VersionComparator.comp(v, MIN_V6_PEER_TEST_VERSION) < 0)
                    continue;
            }
            ip = null;
            List<RouterAddress> addrs = getTargetAddresses(peerInfo);
            for (RouterAddress addr : addrs) {
                byte[] rip = addr.getIP();
                if (rip != null) {
                    if (isIPv6) {
                        if (rip.length != 16)
                            continue;
                    } else {
                        if (rip.length != 4)
                            continue;
                    }
                    // as of 0.9.27, we trust the 'B' cap for IPv6
                    String caps = addr.getOption(UDPAddress.PROP_CAPACITY);
                    if (caps != null && caps.contains(CAP_TESTING)) {
                        ip = rip;
                        break;
                    }
                }
            }
            if (ip == null)
                continue;
            if (isTooClose(ip))
                continue;
            return peer;
        }
        return null;
    }
    
    /**
     *  Periodically ping the introducers, split out since we need to
     *  do it faster than we rebuild our address.
     *  @since 0.8.11
     */
    private class PingIntroducers implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (introducersRequired())
                _introManager.pingIntroducers();
        }
    }

/*******
    private static final String BADIPS[] = new String[] { "192.168.0.1", "127.0.0.1", "10.3.4.5", "172.16.3.4", "224.5.6.7" };
    private static final String GOODIPS[] = new String[] { "192.167.0.1", "126.0.0.1", "11.3.4.5", "172.15.3.4", "223.5.6.7" };
    public static void main(String args[]) {
        for (int i = 0; i < BADIPS.length; i++) {
            try { 
                InetAddress addr = InetAddress.getByName(BADIPS[i]);
                boolean routable = isPubliclyRoutable(addr.getAddress());
                System.out.println("Routable: " + routable + " (" + BADIPS[i] + ")");
            } catch (Exception e) { e.printStackTrace(); }
        }
        for (int i = 0; i < GOODIPS.length; i++) {
            try { 
                InetAddress addr = InetAddress.getByName(GOODIPS[i]);
                boolean routable = isPubliclyRoutable(addr.getAddress());
                System.out.println("Routable: " + routable + " (" + GOODIPS[i] + ")");
            } catch (Exception e) { e.printStackTrace(); }
        }
    }
*******/
}
