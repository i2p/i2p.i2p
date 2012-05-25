package net.i2p.router.transport.udp;

import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.Vector;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.TransportBid;
import net.i2p.router.transport.TransportImpl;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 *
 */
public class UDPTransport extends TransportImpl implements TimedWeightedPriorityMessageQueue.FailedListener {
    private RouterContext _context; // LINT -- field hides a field
    private Log _log;
    private UDPEndpoint _endpoint;
    /** Peer (Hash) to PeerState */
    private final Map _peersByIdent;
    /** RemoteHostId to PeerState */
    private final Map _peersByRemoteHost;
    private PacketHandler _handler;
    private EstablishmentManager _establisher;
    private MessageQueue _outboundMessages;
    private OutboundMessageFragments _fragments;
    private OutboundMessageFragments.ActiveThrottle _activeThrottle;
    private OutboundRefiller _refiller;
    private PacketPusher _pusher;
    private InboundMessageFragments _inboundFragments;
    private UDPFlooder _flooder;
    private PeerTestManager _testManager;
    private IntroductionManager _introManager;
    private ExpirePeerEvent _expireEvent;
    private PeerTestEvent _testEvent;
    private short _reachabilityStatus;
    private long _reachabilityStatusLastUpdated;
    private long _introducersSelectedOn;
    private long _lastInboundReceivedOn;
    
    /** do we need to rebuild our external router address asap? */
    private boolean _needsRebuild;
    
    /** summary info to distribute */
    private RouterAddress _externalAddress;
    /** port number on which we can be reached, or -1 for error, or 0 for unset */
    private int _externalListenPort;
    /** IP address of externally reachable host, or null */
    private InetAddress _externalListenHost;
    /** introduction key */
    private SessionKey _introKey;
    
    /** shared fast bid for connected peers */
    private TransportBid _fastBid;
    /** shared slow bid for unconnected peers when we want to prefer UDP */
    private TransportBid _slowBid;
    /** save some conns for inbound */
    private TransportBid _nearCapacityBid;
    /** shared slow bid for unconnected peers */
    private TransportBid _slowestBid;
    /** shared fast bid for unconnected peers when we want to prefer UDP */
    private TransportBid _fastPreferredBid;
    /** shared slow bid for unconnected peers when we want to always prefer UDP */
    private TransportBid _slowPreferredBid;
    private TransportBid _transientFail;
    
    /** list of RemoteHostId for peers whose packets we want to drop outright */
    private final List _dropList;
    
    private int _expireTimeout;

    private static final int DROPLIST_PERIOD = 10*60*1000;
    private static final int MAX_DROPLIST_SIZE = 256;
    
    public static final String STYLE = "SSU";
    public static final String PROP_INTERNAL_PORT = "i2np.udp.internalPort";
    public static final int DEFAULT_INTERNAL_PORT = 8887;
    /** since fixed port defaults to true, this doesnt do anything at the moment.
     *  We should have an exception if it matches the existing low port. */
    private static final int MIN_EXTERNAL_PORT = 1024;

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
    
    /** if true (default), we don't change our advertised port no matter what our peers tell us */
    public static final String PROP_FIXED_PORT = "i2np.udp.fixedPort";
    private static final String DEFAULT_FIXED_PORT = "true";

    /** allowed sources of address updates */
    public static final String PROP_SOURCES = "i2np.udp.addressSources";
    public static final String DEFAULT_SOURCES = "local,upnp,ssu";
    /** remember IP changes */
    public static final String PROP_IP= "i2np.lastIP";
    public static final String PROP_IP_CHANGE = "i2np.lastIPChange";

    /** do we require introducers, regardless of our status? */
    public static final String PROP_FORCE_INTRODUCERS = "i2np.udp.forceIntroducers";
    /** do we allow direct SSU connections, sans introducers?  */
    public static final String PROP_ALLOW_DIRECT = "i2np.udp.allowDirect";
    public static final String PROP_BIND_INTERFACE = "i2np.udp.bindInterface";
        
    /** how many relays offered to us will we use at a time? */
    public static final int PUBLIC_RELAY_COUNT = 3;
    
    /** configure the priority queue with the given split points */
    private static final int PRIORITY_LIMITS[] = new int[] { 100, 200, 300, 400, 500, 1000 };
    /** configure the priority queue with the given weighting per priority group */
    private static final int PRIORITY_WEIGHT[] = new int[] { 1, 1, 1, 1, 1, 2 };

    /** should we flood all UDP peers with the configured rate? */
    private static final boolean SHOULD_FLOOD_PEERS = false;
    
    private static final int MAX_CONSECUTIVE_FAILED = 5;
    
    private static final int TEST_FREQUENCY = 13*60*1000;
    public static final long[] RATES = { 10*60*1000 };
    
    public UDPTransport(RouterContext ctx) {
        super(ctx);
        _context = ctx;
        _log = ctx.logManager().getLog(UDPTransport.class);
        _peersByIdent = new HashMap(128);
        _peersByRemoteHost = new HashMap(128);
        _dropList = new ArrayList(256);
        _endpoint = null;
        
        TimedWeightedPriorityMessageQueue mq = new TimedWeightedPriorityMessageQueue(ctx, PRIORITY_LIMITS, PRIORITY_WEIGHT, this);
        _outboundMessages = mq;
        _activeThrottle = mq;

        _fastBid = new SharedBid(50);
        _slowBid = new SharedBid(65);
        _fastPreferredBid = new SharedBid(15);
        _slowPreferredBid = new SharedBid(20);
        _slowestBid = new SharedBid(80);
        _nearCapacityBid = new SharedBid(100);
        _transientFail = new SharedBid(TransportBid.TRANSIENT_FAIL);
        
        _fragments = new OutboundMessageFragments(_context, this, _activeThrottle);
        _inboundFragments = new InboundMessageFragments(_context, _fragments, this);
        _flooder = new UDPFlooder(_context, this);
        _expireTimeout = EXPIRE_TIMEOUT;
        _expireEvent = new ExpirePeerEvent();
        _testEvent = new PeerTestEvent();
        _reachabilityStatus = CommSystemFacade.STATUS_UNKNOWN;
        _introManager = new IntroductionManager(_context, this);
        _introducersSelectedOn = -1;
        _lastInboundReceivedOn = -1;
        _needsRebuild = true;
        
        _context.statManager().createRateStat("udp.alreadyConnected", "What is the lifetime of a reestablished session", "udp", RATES);
        _context.statManager().createRateStat("udp.droppedPeer", "How long ago did we receive from a dropped peer (duration == session lifetime", "udp", RATES);
        _context.statManager().createRateStat("udp.droppedPeerInactive", "How long ago did we receive from a dropped peer (duration == session lifetime)", "udp", RATES);
        _context.statManager().createRateStat("udp.statusOK", "How many times the peer test returned OK", "udp", RATES);
        _context.statManager().createRateStat("udp.statusDifferent", "How many times the peer test returned different IP/ports", "udp", RATES);
        _context.statManager().createRateStat("udp.statusReject", "How many times the peer test returned reject unsolicited", "udp", RATES);
        _context.statManager().createRateStat("udp.statusUnknown", "How many times the peer test returned an unknown result", "udp", RATES);
        _context.statManager().createRateStat("udp.addressTestInsteadOfUpdate", "How many times we fire off a peer test of ourselves instead of adjusting our own reachable address?", "udp", RATES);
        _context.statManager().createRateStat("udp.addressUpdated", "How many times we adjust our own reachable IP address", "udp", RATES);
        _context.statManager().createRateStat("udp.proactiveReestablish", "How long a session was idle for when we proactively reestablished it", "udp", RATES);
        _context.statManager().createRateStat("udp.dropPeerDroplist", "How many peers currently have their packets dropped outright when a new peer is added to the list?", "udp", RATES);
        _context.statManager().createRateStat("udp.dropPeerConsecutiveFailures", "How many consecutive failed sends to a peer did we attempt before giving up and reestablishing a new session (lifetime is inactivity perood)", "udp", RATES);
        __instance = this;
    }
    
    public void startup() {
        if (_fragments != null)
            _fragments.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        if (_handler != null) 
            _handler.shutdown();
        if (_endpoint != null)
            _endpoint.shutdown();
        if (_establisher != null)
            _establisher.shutdown();
        if (_refiller != null)
            _refiller.shutdown();
        if (_inboundFragments != null)
            _inboundFragments.shutdown();
        if (_flooder != null)
            _flooder.shutdown();
        _introManager.reset();
        
        _introKey = new SessionKey(new byte[SessionKey.KEYSIZE_BYTES]);
        System.arraycopy(_context.routerHash().getData(), 0, _introKey.getData(), 0, SessionKey.KEYSIZE_BYTES);
        
        rebuildExternalAddress();
        
        int port = -1;
        if (_externalListenPort <= 0) {
            // no explicit external port, so lets try an internal one
            port = _context.getProperty(PROP_INTERNAL_PORT, DEFAULT_INTERNAL_PORT);
            // attempt to use it as our external port - this will be overridden by
            // externalAddressReceived(...)
            _context.router().setConfigSetting(PROP_EXTERNAL_PORT, port+"");
            _context.router().saveConfig();
        } else {
            port = _externalListenPort;
            if (_log.shouldLog(Log.INFO))
                _log.info("Binding to the explicitly specified external port: " + port);
        }
        if (_endpoint == null) {
            String bindTo = _context.getProperty(PROP_BIND_INTERFACE);
            InetAddress bindToAddr = null;
            if (bindTo != null) {
                try {
                    bindToAddr = InetAddress.getByName(bindTo);
                } catch (UnknownHostException uhe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Invalid SSU bind interface specified [" + bindTo + "]", uhe);
                    bindToAddr = null;
                }
            }
            try {
                _endpoint = new UDPEndpoint(_context, this, port, bindToAddr);
            } catch (SocketException se) {
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "Unable to listen on the UDP port (" + port + ")", se);
                return;
            }
        } else {
            _endpoint.setListenPort(port);
        }
        
        if (_establisher == null)
            _establisher = new EstablishmentManager(_context, this);
        
        if (_testManager == null)
            _testManager = new PeerTestManager(_context, this);
        
        if (_handler == null)
            _handler = new PacketHandler(_context, this, _endpoint, _establisher, _inboundFragments, _testManager, _introManager);
        
        if (_refiller == null)
            _refiller = new OutboundRefiller(_context, _fragments, _outboundMessages);
        
        if (_flooder == null)
            _flooder = new UDPFlooder(_context, this);
        
        _endpoint.startup();
        _establisher.startup();
        _handler.startup();
        _fragments.startup();
        _inboundFragments.startup();
        _pusher = new PacketPusher(_context, _fragments, _endpoint.getSender());
        _pusher.startup();
        _refiller.startup();
        _flooder.startup();
        _expireEvent.setIsAlive(true);
        _testEvent.setIsAlive(true); // this queues it for 3-6 minutes in the future...
        SimpleTimer.getInstance().addEvent(_testEvent, 10*1000); // lets requeue it for Real Soon
    }
    
    public void shutdown() {
        if (_endpoint != null)
            _endpoint.shutdown();
        if (_flooder != null)
            _flooder.shutdown();
        if (_refiller != null)
            _refiller.shutdown();
        if (_handler != null)
            _handler.shutdown();
        if (_fragments != null)
            _fragments.shutdown();
        if (_pusher != null)
            _pusher.shutdown();
        if (_establisher != null)
            _establisher.shutdown();
        if (_inboundFragments != null)
            _inboundFragments.shutdown();
        _expireEvent.setIsAlive(false);
        _testEvent.setIsAlive(false);
    }
    
    /**
     * Introduction key that people should use to contact us
     *
     */
    public SessionKey getIntroKey() { return _introKey; }
    public int getLocalPort() { return _externalListenPort; }
    public InetAddress getLocalAddress() { return _externalListenHost; }
    public int getExternalPort() { return _externalListenPort; }
    public int getRequestedPort() {
        if (_externalListenPort > 0)
            return _externalListenPort;
        return _context.getProperty(PROP_INTERNAL_PORT, DEFAULT_INTERNAL_PORT);
    }

    /**
     * If we have received an inbound connection in the last 2 minutes, don't allow 
     * our IP to change.
     */
    private static final int ALLOW_IP_CHANGE_INTERVAL = 2*60*1000;
    
    void inboundConnectionReceived() { 
        // use OS clock since its an ordering thing, not a time thing
        _lastInboundReceivedOn = System.currentTimeMillis(); 
    }
    
    /**
     * From config, UPnP, local i/f, ...
     *
     * @param source used for logging only
     * @param ip publicly routable IPv4 only
     * @param port 0 if unknown
     */
    public void externalAddressReceived(String source, byte[] ip, int port) {
        String s = RemoteHostId.toString(ip);
        if (_log.shouldLog(Log.WARN))
            _log.warn("Received address: " + s + " port: " + port + " from: " + source);
        if (explicitAddressSpecified())
            return;
        String sources = _context.getProperty(PROP_SOURCES, DEFAULT_SOURCES);
        if (!sources.contains(source))
            return;
        boolean changed = changeAddress(ip, port);
        // Assume if we have an interface with a public IP that we aren't firewalled.
        // If this is wrong, the peer test will figure it out and change the status.
        if (changed && source.equals(Transport.SOURCE_INTERFACE))
            setReachabilityStatus(CommSystemFacade.STATUS_OK);
    }

    /**
     *  Callback from UPnP.
     *  If we we have an IP address and UPnP claims success, believe it.
     *  If this is wrong, the peer test will figure it out and change the status.
     *  Don't do anything if UPnP claims failure.
     */
    public void forwardPortStatus(int port, boolean success, String reason) {
        if (_log.shouldLog(Log.WARN)) {
            if (success)
                _log.warn("UPnP has opened the SSU port: " + port);
            else
                _log.warn("UPnP has failed to open the SSU port: " + port + " reason: " + reason);
        }
        if (success && _externalListenHost != null)
            setReachabilityStatus(CommSystemFacade.STATUS_OK);
    }

    /**
     * Someone we tried to contact gave us what they think our IP address is.
     * Right now, we just blindly trust them, changing our IP and port on a
     * whim.  this is not good ;)
     *
     * Todo:
     *   - Much better tracking of troublemakers
     *   - Disable if we have good local address or UPnP
     * 
     * @param from Hash of inbound destination
     * @param ourIP publicly routable IPv4 only
     * @param ourPort >= 1024
     */
    void externalAddressReceived(Hash from, byte ourIP[], int ourPort) {
        boolean isValid = isValid(ourIP) &&
                          (ourPort >= MIN_EXTERNAL_PORT || ourPort == _externalListenPort || _externalListenPort <= 0);
        boolean explicitSpecified = explicitAddressSpecified();
        boolean inboundRecent = _lastInboundReceivedOn + ALLOW_IP_CHANGE_INTERVAL > System.currentTimeMillis();
        if (_log.shouldLog(Log.INFO))
            _log.info("External address received: " + RemoteHostId.toString(ourIP) + ":" + ourPort + " from " 
                      + from.toBase64() + ", isValid? " + isValid + ", explicitSpecified? " + explicitSpecified 
                      + ", receivedInboundRecent? " + inboundRecent + " status " + _reachabilityStatus);
        
        if (explicitSpecified) 
            return;
        String sources = _context.getProperty(PROP_SOURCES, DEFAULT_SOURCES);
        if (!sources.contains("ssu"))
            return;
        
        if (!isValid) {
            // ignore them 
            if (_log.shouldLog(Log.ERROR))
                _log.error("The router " + from.toBase64() + " told us we have an invalid IP - " 
                           + RemoteHostId.toString(ourIP) + " port " +  ourPort + ".  Lets throw tomatoes at them");
            markUnreachable(from);
            //_context.shitlist().shitlistRouter(from, "They said we had an invalid IP", STYLE);
            return;
        } else if (inboundRecent && _externalListenPort > 0 && _externalListenHost != null) {
            // use OS clock since its an ordering thing, not a time thing
            if (_log.shouldLog(Log.INFO))
                _log.info("Ignoring IP address suggestion, since we have received an inbound con recently");
        } else {
            changeAddress(ourIP, ourPort);
        }
        
    }
    
    /**
     * @param ourPort >= 1024 or 0 for no change
     */
    private boolean changeAddress(byte ourIP[], int ourPort) {
        /** this defaults to true, which means we never change our external port based on what somebody tells us */
        boolean fixedPort = getIsPortFixed();
        boolean updated = false;
        boolean fireTest = false;

            synchronized (this) {
                if ( (_externalListenHost == null) ||
                     (!eq(_externalListenHost.getAddress(), _externalListenPort, ourIP, ourPort)) ) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Change address? status = " + _reachabilityStatus +
                                  " diff = " + (_context.clock().now() - _reachabilityStatusLastUpdated) +
                                  " old = " + _externalListenHost + ':' + _externalListenPort);
                    if ( (_reachabilityStatus != CommSystemFacade.STATUS_OK) ||
                         (_externalListenHost == null) || (_externalListenPort <= 0) ||
                         (_context.clock().now() - _reachabilityStatusLastUpdated > 2*TEST_FREQUENCY) ) {
                        // they told us something different and our tests are either old or failing
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Trying to change our external address...");
                        try {
                            _externalListenHost = InetAddress.getByAddress(ourIP);
                            // fixed port defaults to true so we never do this
                            if (ourPort >= MIN_EXTERNAL_PORT && !fixedPort)
                                _externalListenPort = ourPort;
                            if (_externalListenPort > 0)  {
                                rebuildExternalAddress();
                                replaceAddress(_externalAddress);
                                updated = true;
                            }
                        } catch (UnknownHostException uhe) {
                            _externalListenHost = null;
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Error trying to change our external address", uhe);
                        }
                    } else {
                        // they told us something different, but our tests are recent and positive,
                        // so lets test again
                        fireTest = true;
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Different address, but we're fine.. (" + _reachabilityStatus + ")");
                    }
                } else {
                    // matched what we expect
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Same address as the current one");
                }
            }

        if (fireTest) {
            _context.statManager().addRateData("udp.addressTestInsteadOfUpdate", 1, 0);
        } else if (updated) {
            _context.statManager().addRateData("udp.addressUpdated", 1, 0);
            if (!fixedPort)
                _context.router().setConfigSetting(PROP_EXTERNAL_PORT, ourPort+"");
            // queue a country code lookup of the new IP
            _context.commSystem().queueLookup(ourIP);
            // store these for laptop-mode (change ident on restart... or every time... when IP changes)
            String oldIP = _context.getProperty(PROP_IP);
            if (!_externalListenHost.getHostAddress().equals(oldIP)) {
                _context.router().setConfigSetting(PROP_IP, _externalListenHost.getHostAddress());
                _context.router().setConfigSetting(PROP_IP_CHANGE, "" + _context.clock().now());
                _context.router().saveConfig();
            }
            _context.router().rebuildRouterInfo();
        }
        _testEvent.forceRun();
        SimpleTimer.getInstance().addEvent(_testEvent, 5*1000);
        return updated;
    }

    private static final boolean eq(byte laddr[], int lport, byte raddr[], int rport) {
        return (rport == lport) && DataHelper.eq(laddr, raddr);
    }
    
    public final boolean isValid(byte addr[]) {
        if (addr == null) return false;
        if (addr.length < 4) return false;
        if (isPubliclyRoutable(addr)) 
            return true;
        return Boolean.valueOf(_context.getProperty("i2np.udp.allowLocal")).booleanValue();
    }
    
    private boolean getIsPortFixed() {
        return DEFAULT_FIXED_PORT.equals(_context.getProperty(PROP_FIXED_PORT, DEFAULT_FIXED_PORT));
    }
    /** 
     * get the state for the peer at the given remote host/port, or null 
     * if no state exists
     */
    public PeerState getPeerState(RemoteHostId hostInfo) { // LINT -- Exporting non-public type through public API
        synchronized (_peersByRemoteHost) {
            return (PeerState)_peersByRemoteHost.get(hostInfo);
        }
    }
    
    /** 
     * get the state for the peer with the given ident, or null 
     * if no state exists
     */
    public PeerState getPeerState(Hash remotePeer) { 
        synchronized (_peersByIdent) {
            return (PeerState)_peersByIdent.get(remotePeer);
        }
    }
    
    /**
     * get the state for the peer being introduced, or null if we aren't
     * offering to introduce anyone with that tag.
     */
    public PeerState getPeerState(long relayTag) {
        return _introManager.get(relayTag);
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
        Hash remotePeer = peer.getRemotePeer();
        long oldEstablishedOn = -1;
        PeerState oldPeer = null;
        if (remotePeer != null) {
            synchronized (_peersByIdent) {
                oldPeer = (PeerState)_peersByIdent.put(remotePeer, peer);
                if ( (oldPeer != null) && (oldPeer != peer) ) {
                    // transfer over the old state/inbound message fragments/etc
                    peer.loadFrom(oldPeer);
                    oldEstablishedOn = oldPeer.getKeyEstablishedTime();
                }
            }
        }
        
        if (oldPeer != null) {
            oldPeer.dropOutbound();
            _introManager.remove(oldPeer);
            _expireEvent.remove(oldPeer);
        }
        oldPeer = null;
        
        RemoteHostId remoteId = peer.getRemoteHostId();
        if (remoteId == null) return false;
        
        synchronized (_peersByRemoteHost) {
            oldPeer = (PeerState)_peersByRemoteHost.put(remoteId, peer);
            if ( (oldPeer != null) && (oldPeer != peer) ) {
                // transfer over the old state/inbound message fragments/etc
                peer.loadFrom(oldPeer);
                oldEstablishedOn = oldPeer.getKeyEstablishedTime();
            }
        }
        
        if (oldPeer != null) {
            oldPeer.dropOutbound();
            _introManager.remove(oldPeer);
            _expireEvent.remove(oldPeer);
        }
        
        if ( (oldPeer != null) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Peer already connected: old=" + oldPeer + " new=" + peer, new Exception("dup"));
        
        _activeThrottle.unchoke(peer.getRemotePeer());
        markReachable(peer.getRemotePeer(), peer.isInbound());
        //_context.shitlist().unshitlistRouter(peer.getRemotePeer(), STYLE);

        if (SHOULD_FLOOD_PEERS)
            _flooder.addPeer(peer);
        
        _expireEvent.add(peer);
        
        _introManager.add(peer);
        
        if (oldEstablishedOn > 0)
            _context.statManager().addRateData("udp.alreadyConnected", oldEstablishedOn, 0);
        
        if (needsRebuild())
            rebuildExternalAddress();
        
        if (getReachabilityStatus() != CommSystemFacade.STATUS_OK) {
            _testEvent.forceRun();
            SimpleTimer.getInstance().addEvent(_testEvent, 0);
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
            if ( (dsm.getRouterInfo() != null) && 
                 (dsm.getRouterInfo().getNetworkId() != Router.NETWORK_ID) ) {
                /*
                if (remoteIdentHash != null) {
                    _context.shitlist().shitlistRouter(remoteIdentHash, "Sent us a peer from the wrong network");
                    dropPeer(remoteIdentHash);
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Dropping the peer " + remoteIdentHash
                                   + " because they are in the wrong net");
                } else if (remoteIdent != null) {
                    _context.shitlist().shitlistRouter(remoteIdent.calculateHash(), "Sent us a peer from the wrong network");
                    dropPeer(remoteIdent.calculateHash());
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Dropping the peer " + remoteIdent.calculateHash()
                                   + " because they are in the wrong net");
                }
                 */
                Hash peerHash = dsm.getRouterInfo().getIdentity().calculateHash();
                PeerState peer = getPeerState(peerHash);
                if (peer != null) {
                    RemoteHostId remote = peer.getRemoteHostId();
                    boolean added = false;
                    int droplistSize = 0;
                    synchronized (_dropList) {
                        if (!_dropList.contains(remote)) {
                            while (_dropList.size() > MAX_DROPLIST_SIZE)
                                _dropList.remove(0);
                            _dropList.add(remote);
                            added = true;
                        }
                        droplistSize = _dropList.size();
                    }
                    if (added) {
                        _context.statManager().addRateData("udp.dropPeerDroplist", droplistSize, 0);
                        SimpleScheduler.getInstance().addEvent(new RemoveDropList(remote), DROPLIST_PERIOD);
                    }
                }
                markUnreachable(peerHash);
                _context.shitlist().shitlistRouter(peerHash, "Part of the wrong network, version = " + dsm.getRouterInfo().getOption("router.version"));
                //_context.shitlist().shitlistRouter(peerHash, "Part of the wrong network", STYLE);
                dropPeer(peerHash, false, "wrong network");
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping the peer " + peerHash.toBase64() + " because they are in the wrong net: " + dsm.getRouterInfo());
                return;
            } else {
                if (dsm.getRouterInfo() != null) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received an RI from the same net");
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received a leaseSet: " + dsm);
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
        private RemoteHostId _peer;
        public RemoveDropList(RemoteHostId peer) { _peer = peer; }
        public void timeReached() { 
            synchronized (_dropList) {
                _dropList.remove(_peer);
            }
        }
    }
    
    public boolean isInDropList(RemoteHostId peer) { synchronized (_dropList) { return _dropList.contains(peer); } }// LINT -- Exporting non-public type through public API
    
    void dropPeer(Hash peer, boolean shouldShitlist, String why) {
        PeerState state = getPeerState(peer);
        if (state != null)
            dropPeer(state, shouldShitlist, why);
    }
    private void dropPeer(PeerState peer, boolean shouldShitlist, String why) {
        if (_log.shouldLog(Log.WARN)) {
            long now = _context.clock().now();
            StringBuilder buf = new StringBuilder(4096);
            long timeSinceSend = now - peer.getLastSendTime();
            long timeSinceRecv = now - peer.getLastReceiveTime();
            long timeSinceAck  = now - peer.getLastACKSend();
            long timeSinceSendOK = now - peer.getLastSendFullyTime();
            int consec = peer.getConsecutiveFailedSends();
            buf.append("Dropping remote peer: ").append(peer.toString()).append(" shitlist? ").append(shouldShitlist);
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
            _log.warn(buf.toString(), new Exception("Dropped by"));
        }
        
        peer.dropOutbound();
        peer.expireInboundMessages();
        _introManager.remove(peer);
        _fragments.dropPeer(peer);
        
        PeerState altByIdent = null;
        PeerState altByHost = null;
        
        if (peer.getRemotePeer() != null) {
            dropPeerCapacities(peer);
            
            if (shouldShitlist) {
                markUnreachable(peer.getRemotePeer());
                //_context.shitlist().shitlistRouter(peer.getRemotePeer(), "dropped after too many retries", STYLE);
            }
            long now = _context.clock().now();
            _context.statManager().addRateData("udp.droppedPeer", now - peer.getLastReceiveTime(), now - peer.getKeyEstablishedTime());
            synchronized (_peersByIdent) {
                altByIdent = (PeerState)_peersByIdent.remove(peer.getRemotePeer());
            }
        }
        
        RemoteHostId remoteId = peer.getRemoteHostId();
        if (remoteId != null) {
            synchronized (_peersByRemoteHost) {
                altByHost = (PeerState)_peersByRemoteHost.remove(remoteId);
            }
        }
        
        // unchoke 'em, but just because we'll never talk again...
        _activeThrottle.unchoke(peer.getRemotePeer());
        
        if (SHOULD_FLOOD_PEERS)
            _flooder.removePeer(peer);
        _expireEvent.remove(peer);
        
        if (needsRebuild())
            rebuildExternalAddress();
        
        // deal with races to make sure we drop the peers fully
        if ( (altByIdent != null) && (peer != altByIdent) ) dropPeer(altByIdent, shouldShitlist, "recurse");
        if ( (altByHost != null) && (peer != altByHost) ) dropPeer(altByHost, shouldShitlist, "recurse");
    }
    
    private boolean needsRebuild() {
        if (_needsRebuild) return true; // simple enough
        if (_context.router().isHidden()) return false;
        if (introducersRequired()) {
            RouterAddress addr = _externalAddress;
            UDPAddress ua = new UDPAddress(addr);
            int valid = 0;
            Hash peerHash = new Hash();
            for (int i = 0; i < ua.getIntroducerCount(); i++) {
                // warning: this is only valid as long as we use the ident hash as their key.
                peerHash.setData(ua.getIntroducerKey(i));
                PeerState peer = getPeerState(peerHash);
                if (peer != null)
                    valid++;
            }
            if (valid >= PUBLIC_RELAY_COUNT) {
                // try to shift 'em around every 10 minutes or so
                if (_introducersSelectedOn < _context.clock().now() - 10*60*1000) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Our introducers are valid, but thy havent changed in a while, so lets rechoose");
                    return true;
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Our introducers are valid and haven't changed in a while");
                    return false;
                }
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Our introducers are not valid (" +valid + ")");
                return true;
            }
        } else {
            boolean rv = (_externalListenHost == null) || (_externalListenPort <= 0);
            if (!rv) {
                RouterAddress addr = _externalAddress;
                UDPAddress ua = new UDPAddress(addr);
                if (ua.getIntroducerCount() > 0)
                    rv = true;  // status == ok and we don't actually need introducers, so rebuild
            }
            if (_log.shouldLog(Log.INFO)) {
                if (rv) {
                    _log.info("Need to initialize our direct SSU info (" + _externalListenHost + ":" + _externalListenPort + ")");
                } else {
                    RouterAddress addr = _externalAddress;
                    UDPAddress ua = new UDPAddress(addr);
                    if ( (ua.getPort() <= 0) || (ua.getHost() == null) ) {
                        _log.info("Our direct SSU info is initialized, but not used in our address yet");
                        rv = true;
                    } else {
                        _log.info("Our direct SSU info is initialized");
                    }
                }
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
    
    int send(UDPPacket packet) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending packet " + packet);
        return _endpoint.send(packet); 
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
                return _fastPreferredBid;
            else
                return _fastBid;
        } else {
            // If we don't have a port, all is lost
            if ( _reachabilityStatus == CommSystemFacade.STATUS_HOSED) {
                markUnreachable(to);
                return null;
            }

            // Validate his SSU address
            RouterAddress addr = toAddress.getTargetAddress(STYLE);
            if (addr == null) {
                markUnreachable(to);
                return null;
            }
            UDPAddress ua = new UDPAddress(addr);
            if (ua == null) {
                markUnreachable(to);
                return null;
            }
            if (ua.getIntroducerCount() <= 0) {
                InetAddress ia = ua.getHostAddress();
                if (ua.getPort() <= 0 || ia == null || !isPubliclyRoutable(ia.getAddress())) {
                    markUnreachable(to);
                    return null;
                }
            }
            if (!allowConnection())
                return _transientFail;

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("bidding on a message to an unestablished peer: " + to.toBase64());

            // Try to maintain at least 3 peers so we can determine our IP address and
            // we have a selection to run peer tests with.
            int count;
            synchronized (_peersByIdent) {
                count = _peersByIdent.size();
            }
            if (alwaysPreferUDP() || count < 3)
                return _slowPreferredBid;
            else if (preferUDP())
                return _slowBid;
            else if (haveCapacity())
                return _slowestBid;
            else
                return _nearCapacityBid;
        }
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
    public static final int EXPIRE_TIMEOUT = 30*60*1000;
    private static final int MAX_IDLE_TIME = EXPIRE_TIMEOUT;
    private static final int MIN_EXPIRE_TIMEOUT = 10*60*1000;
    
    public String getStyle() { return STYLE; }
    @Override
    public void send(OutNetMessage msg) { 
        if (msg == null) return;
        if (msg.getTarget() == null) return;
        if (msg.getTarget().getIdentity() == null) return;
    
        msg.timestamp("sending on UDP transport");
        Hash to = msg.getTarget().getIdentity().calculateHash();
        PeerState peer = getPeerState(to);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending to " + (to != null ? to.toBase64() : ""));
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
                        _log.debug("Proactive reestablish to " + to.toBase64());
                    _establisher.establish(msg);
                    _context.statManager().addRateData("udp.proactiveReestablish", now-lastSend, now-peer.getKeyEstablishedTime());
                    return;
                }
            }
            msg.timestamp("enqueueing for an already established peer");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Add to fragments for " + to.toBase64());
            if (true) // skip the priority queue and go straight to the active pool
                _fragments.add(msg);
            else
                _outboundMessages.add(msg);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Establish new connection to " + to.toBase64());
            msg.timestamp("establishing a new connection");
            _establisher.establish(msg);
        }
    }
    void send(I2NPMessage msg, PeerState peer) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Injecting a data message to a new peer: " + peer);
        OutboundMessageState state = new OutboundMessageState(_context);
        boolean ok = state.initialize(msg, peer);
        if (ok)
            _fragments.add(state);
    }
    
    // we don't need the following, since we have our own queueing
    protected void outboundMessageReady() { throw new UnsupportedOperationException("Not used for UDP"); }
    
    public RouterAddress startListening() {
        startup();
        return _externalAddress;
    }
    
    public void stopListening() {
        shutdown();
        // will this work?
        _externalAddress = null;
        replaceAddress(null);
    }
    
    private boolean explicitAddressSpecified() {
        return (_context.getProperty(PROP_EXTERNAL_HOST) != null);
    }
    
    void rebuildExternalAddress() { rebuildExternalAddress(true); }
    void rebuildExternalAddress(boolean allowRebuildRouterInfo) {
        // if the external port is specified, we want to use that to bind to even
        // if we don't know the external host.
        _externalListenPort = _context.getProperty(PROP_EXTERNAL_PORT, -1);
        
        if (explicitAddressSpecified()) {
            try {
                String host = _context.getProperty(PROP_EXTERNAL_HOST);
                _externalListenHost = InetAddress.getByName(host);
            } catch (UnknownHostException uhe) {
                _externalListenHost = null;
            }
        }
            
        if (_context.router().isHidden())
            return;
        
        Properties options = new Properties(); 
        boolean directIncluded = false;
        if ( allowDirectUDP() && (_externalListenPort > 0) && (_externalListenHost != null) && (isValid(_externalListenHost.getAddress())) ) {
            options.setProperty(UDPAddress.PROP_PORT, String.valueOf(_externalListenPort));
            options.setProperty(UDPAddress.PROP_HOST, _externalListenHost.getHostAddress());
            directIncluded = true;
        }
        
        boolean introducersRequired = introducersRequired();
        boolean introducersIncluded = false;
        if (introducersRequired || !directIncluded) {
            int found = _introManager.pickInbound(options, PUBLIC_RELAY_COUNT);
            if (found > 0) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Picked peers: " + found);
                _introducersSelectedOn = _context.clock().now();
                introducersIncluded = true;
            }
        }
        
        // if we have explicit external addresses, they had better be reachable
        if (introducersRequired)
            options.setProperty(UDPAddress.PROP_CAPACITY, ""+UDPAddress.CAPACITY_TESTING);
        else
            options.setProperty(UDPAddress.PROP_CAPACITY, ""+UDPAddress.CAPACITY_TESTING + UDPAddress.CAPACITY_INTRODUCER);

        if (directIncluded || introducersIncluded) {
            options.setProperty(UDPAddress.PROP_INTRO_KEY, _introKey.toBase64());

            RouterAddress addr = new RouterAddress();
            addr.setCost(5);
            addr.setExpiration(null);
            addr.setTransportStyle(STYLE);
            addr.setOptions(options);

            boolean wantsRebuild = false;
            if ( (_externalAddress == null) || !(_externalAddress.equals(addr)) )
                wantsRebuild = true;

            RouterAddress oldAddress = _externalAddress;
            _externalAddress = addr;
            if (_log.shouldLog(Log.INFO))
                _log.info("Address rebuilt: " + addr);
            replaceAddress(addr, oldAddress);
            if (allowRebuildRouterInfo && wantsRebuild)
                _context.router().rebuildRouterInfo();
            _needsRebuild = false;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Wanted to rebuild my SSU address, but couldn't specify either the direct or indirect info (needs introducers? " 
                           + introducersRequired + ")", new Exception("source"));
            _needsRebuild = true;
        }
    }

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
                    if ("true".equalsIgnoreCase(_context.getProperty(Router.PROP_DYNAMIC_KEYS, "false"))) {
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
    
    public boolean introducersRequired() {
        /******************
         *  Don't do this anymore, as we are removing the checkbox from the UI,
         *  and we rarely if ever see the problem of false negatives for firewall detection -
         *  it's usually false positives.
         ******************
        String forceIntroducers = _context.getProperty(PROP_FORCE_INTRODUCERS);
        if ( (forceIntroducers != null) && (Boolean.valueOf(forceIntroducers).booleanValue()) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Force introducers specified");
            return true;
        }
        *******************/
        short status = getReachabilityStatus();
        switch (status) {
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
            case CommSystemFacade.STATUS_DIFFERENT:
                if (_log.shouldLog(Log.INFO))
                    _log.info("Require introducers, because our status is " + status);
                return true;
            default:
                if (!allowDirectUDP()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Require introducers, because we do not allow direct UDP connections");
                    return true;
                }
                return false;
        }
    }
    
    /** default true */
    private boolean allowDirectUDP() {
        String allowDirect = _context.getProperty(PROP_ALLOW_DIRECT);
        return ( (allowDirect == null) || (Boolean.valueOf(allowDirect).booleanValue()) );
    }

    String getPacketHandlerStatus() {
        PacketHandler handler = _handler;
        if (handler != null)
            return handler.getHandlerStatus();
        else
            return "";
    }

    private static final int DROP_INACTIVITY_TIME = 60*1000;
    
    public void failed(OutboundMessageState msg) { failed(msg, true); }
    void failed(OutboundMessageState msg, boolean allowPeerFailure) {
        if (msg == null) return;
        int consecutive = 0;
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
            consecutive = msg.getPeer().incrementConsecutiveFailedSends();
            if (_log.shouldLog(Log.INFO))
                _log.info("Consecutive failure #" + consecutive 
                          + " on " + msg.toString()
                          + " to " + msg.getPeer());
            if ( (_context.clock().now() - msg.getPeer().getLastSendFullyTime() <= 60*1000) || (consecutive < MAX_CONSECUTIVE_FAILED) ) {
                // ok, a few conseutive failures, but we /are/ getting through to them
            } else {
                _context.statManager().addRateData("udp.dropPeerConsecutiveFailures", consecutive, msg.getPeer().getInactivityTime());
                dropPeer(msg.getPeer(), false, "too many failures");
            }
            //if ( (consecutive > MAX_CONSECUTIVE_FAILED) && (msg.getPeer().getInactivityTime() > DROP_INACTIVITY_TIME))
            //    dropPeer(msg.getPeer(), false);
            //else if (consecutive > 2 * MAX_CONSECUTIVE_FAILED) // they're sending us data, but we cant reply?
            //    dropPeer(msg.getPeer(), false);
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
            _log.info("Sending message failed: " + msg, new Exception("failed from"));
        
        if (!_context.messageHistory().getDoLog())
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

    @Override
    public int countPeers() {
        synchronized (_peersByIdent) {
            return _peersByIdent.size();
        }
    }

    @Override
    public int countActivePeers() {
        long now = _context.clock().now();
        int active = 0;
        int inactive = 0;
        synchronized (_peersByIdent) {
            for (Iterator iter = _peersByIdent.values().iterator(); iter.hasNext(); ) {
                PeerState peer = (PeerState)iter.next();
                if (now-peer.getLastReceiveTime() > 5*60*1000)
                    inactive++;
                else
                    active++;
            }
        }
        return active;
    }
    
    @Override
    public int countActiveSendPeers() {
        long now = _context.clock().now();
        int active = 0;
        int inactive = 0;
        synchronized (_peersByIdent) {
            for (Iterator iter = _peersByIdent.values().iterator(); iter.hasNext(); ) {
                PeerState peer = (PeerState)iter.next();
                if (now-peer.getLastSendFullyTime() > 1*60*1000)
                    inactive++;
                else
                    active++;
            }
        }
        return active;
    }
    
    @Override
    public boolean isEstablished(Hash dest) {
        return getPeerState(dest) != null;
    }

    public boolean allowConnection() {
        synchronized (_peersByIdent) {
            return _peersByIdent.size() < getMaxConnections();
        }
    }

    /**
     * Return our peer clock skews on this transport.
     * Vector composed of Long, each element representing a peer skew in seconds.
     */
    @Override
    public Vector getClockSkews() {

        Vector skews = new Vector();
        Vector peers = new Vector();

        synchronized (_peersByIdent) {
            peers.addAll(_peersByIdent.values());
        }

        long now = _context.clock().now();
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            PeerState peer = (PeerState)iter.next();
            if (now-peer.getLastReceiveTime() > 60*60*1000) continue; // skip old peers
            skews.addElement(new Long (peer.getClockSkew()));
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("UDP transport returning " + skews.size() + " peer clock skews.");
        return skews;
    }
    
    private static UDPTransport __instance;
    /** **internal, do not use** */
    public static final UDPTransport _instance() { return __instance; }
    /** **internal, do not use** return the peers (Hash) of active peers. */
    public List _getActivePeers() {
        List peers = new ArrayList(128);
        synchronized (_peersByIdent) {
            peers.addAll(_peersByIdent.keySet());
        }
        
        long now = _context.clock().now();
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            PeerState state = getPeerState(peer);
            if (now-state.getLastReceiveTime() > 5*60*1000)
                iter.remove(); // don't include old peers
        }
        return peers;
    }
    
    private static final int FLAG_ALPHA = 0;
    private static final int FLAG_IDLE_IN = 1;
    private static final int FLAG_IDLE_OUT = 2;
    private static final int FLAG_RATE_IN = 3;
    private static final int FLAG_RATE_OUT = 4;
    private static final int FLAG_SKEW = 5;
    private static final int FLAG_CWND= 6;
    private static final int FLAG_SSTHRESH = 7;
    private static final int FLAG_RTT = 8;
    private static final int FLAG_DEV = 9;
    private static final int FLAG_RTO = 10;
    private static final int FLAG_MTU = 11;
    private static final int FLAG_SEND = 12;
    private static final int FLAG_RECV = 13;
    private static final int FLAG_RESEND = 14;
    private static final int FLAG_DUP = 15;
    private static final int FLAG_UPTIME = 16;
    
    private Comparator getComparator(int sortFlags) {
        Comparator rv = null;
        switch (Math.abs(sortFlags)) {
            case FLAG_IDLE_IN:
                rv = IdleInComparator.instance();
                break;
            case FLAG_IDLE_OUT:
                rv = IdleOutComparator.instance();
                break;
            case FLAG_RATE_IN:
                rv = RateInComparator.instance();
                break;
            case FLAG_RATE_OUT:
                rv = RateOutComparator.instance();
                break;
            case FLAG_UPTIME:
                rv = UptimeComparator.instance();
                break;
            case FLAG_SKEW:
                rv = SkewComparator.instance();
                break;
            case FLAG_CWND:
                rv = CwndComparator.instance();
                break;
            case FLAG_SSTHRESH:
                rv = SsthreshComparator.instance();
                break;
            case FLAG_RTT:
                rv = RTTComparator.instance();
                break;
            case FLAG_DEV:
                rv = DevComparator.instance();
                break;
            case FLAG_RTO:
                rv = RTOComparator.instance();
                break;
            case FLAG_MTU:
                rv = MTUComparator.instance();
                break;
            case FLAG_SEND:
                rv = SendCountComparator.instance();
                break;
            case FLAG_RECV:
                rv = RecvCountComparator.instance();
                break;
            case FLAG_RESEND:
                rv = ResendComparator.instance();
                break;
            case FLAG_DUP:
                rv = DupComparator.instance();
                break;
            case FLAG_ALPHA:
            default:
                rv = AlphaComparator.instance();
                break;
        }
        if (sortFlags < 0)
            rv = new InverseComparator(rv);
        return rv;
    }
    private static class AlphaComparator extends PeerComparator {
        private static final AlphaComparator _instance = new AlphaComparator();
        public static final AlphaComparator instance() { return _instance; }
    }
    private static class IdleInComparator extends PeerComparator {
        private static final IdleInComparator _instance = new IdleInComparator();
        public static final IdleInComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = r.getLastReceiveTime() - l.getLastReceiveTime();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class IdleOutComparator extends PeerComparator {
        private static final IdleOutComparator _instance = new IdleOutComparator();
        public static final IdleOutComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = r.getLastSendTime() - l.getLastSendTime();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class RateInComparator extends PeerComparator {
        private static final RateInComparator _instance = new RateInComparator();
        public static final RateInComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getReceiveBps() - r.getReceiveBps();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class RateOutComparator extends PeerComparator {
        private static final RateOutComparator _instance = new RateOutComparator();
        public static final RateOutComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getSendBps() - r.getSendBps();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class UptimeComparator extends PeerComparator {
        private static final UptimeComparator _instance = new UptimeComparator();
        public static final UptimeComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = r.getKeyEstablishedTime() - l.getKeyEstablishedTime();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class SkewComparator extends PeerComparator {
        private static final SkewComparator _instance = new SkewComparator();
        public static final SkewComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = Math.abs(l.getClockSkew()) - Math.abs(r.getClockSkew());
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class CwndComparator extends PeerComparator {
        private static final CwndComparator _instance = new CwndComparator();
        public static final CwndComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getSendWindowBytes() - r.getSendWindowBytes();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class SsthreshComparator extends PeerComparator {
        private static final SsthreshComparator _instance = new SsthreshComparator();
        public static final SsthreshComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getSlowStartThreshold() - r.getSlowStartThreshold();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class RTTComparator extends PeerComparator {
        private static final RTTComparator _instance = new RTTComparator();
        public static final RTTComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getRTT() - r.getRTT();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class DevComparator extends PeerComparator {
        private static final DevComparator _instance = new DevComparator();
        public static final DevComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getRTTDeviation() - r.getRTTDeviation();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class RTOComparator extends PeerComparator {
        private static final RTOComparator _instance = new RTOComparator();
        public static final RTOComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getRTO() - r.getRTO();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class MTUComparator extends PeerComparator {
        private static final MTUComparator _instance = new MTUComparator();
        public static final MTUComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getMTU() - r.getMTU();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class SendCountComparator extends PeerComparator {
        private static final SendCountComparator _instance = new SendCountComparator();
        public static final SendCountComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsTransmitted() - r.getPacketsTransmitted();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class RecvCountComparator extends PeerComparator {
        private static final RecvCountComparator _instance = new RecvCountComparator();
        public static final RecvCountComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsReceived() - r.getPacketsReceived();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class ResendComparator extends PeerComparator {
        private static final ResendComparator _instance = new ResendComparator();
        public static final ResendComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsRetransmitted() - r.getPacketsRetransmitted();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    private static class DupComparator extends PeerComparator {
        private static final DupComparator _instance = new DupComparator();
        public static final DupComparator instance() { return _instance; }
        @Override
        protected int compare(PeerState l, PeerState r) {
            long rv = l.getPacketsReceivedDuplicate() - r.getPacketsReceivedDuplicate();
            if (rv == 0) // fallback on alpha
                return super.compare(l, r);
            else
                return (int)rv;
        }
    }
    
    private static class PeerComparator implements Comparator {
        public int compare(Object lhs, Object rhs) {
            if ( (lhs == null) || (rhs == null) || !(lhs instanceof PeerState) || !(rhs instanceof PeerState)) 
                throw new IllegalArgumentException("rhs = " + rhs + " lhs = " + lhs);
            return compare((PeerState)lhs, (PeerState)rhs);
        }
        protected int compare(PeerState l, PeerState r) {
            // base64 retains binary ordering
            return l.getRemotePeer().toBase64().compareTo(r.getRemotePeer().toBase64());
        }
    }
    private static class InverseComparator implements Comparator {
        private Comparator _comp;
        public InverseComparator(Comparator comp) { _comp = comp; }
        public int compare(Object lhs, Object rhs) {
            return -1 * _comp.compare(lhs, rhs);
        }
    }
    
    private void appendSortLinks(StringBuilder buf, String urlBase, int sortFlags, String descr, int ascending) {
        if (sortFlags == ascending) {
            buf.append(" <a href=\"").append(urlBase).append("?sort=").append(0-ascending);
            buf.append("\" title=\"").append(descr).append("\">V</a><b>^</b> ");
        } else if (sortFlags == 0 - ascending) {
            buf.append(" <b>V</b><a href=\"").append(urlBase).append("?sort=").append(ascending);
            buf.append("\" title=\"").append(descr).append("\">^</a> ");
        } else {
            buf.append(" <a href=\"").append(urlBase).append("?sort=").append(0-ascending);
            buf.append("\" title=\"").append(descr).append("\">V</a><a href=\"").append(urlBase).append("?sort=").append(ascending);
            buf.append("\" title=\"").append(descr).append("\">^</a> ");
        }
    }
    
    //public void renderStatusHTML(Writer out) throws IOException { renderStatusHTML(out, 0); }
    public void renderStatusHTML(Writer out, int sortFlags) throws IOException {}
    @Override
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException {
        TreeSet peers = new TreeSet(getComparator(sortFlags));
        synchronized (_peersByIdent) {
            peers.addAll(_peersByIdent.values());
        }
        long offsetTotal = 0;

        int bpsIn = 0;
        int bpsOut = 0;
        long uptimeMsTotal = 0;
        long cwinTotal = 0;
        long rttTotal = 0;
        long rtoTotal = 0;
        long sendTotal = 0;
        long recvTotal = 0;
        long resentTotal = 0;
        long dupRecvTotal = 0;
        int numPeers = 0;
        
        StringBuilder buf = new StringBuilder(512);
        buf.append("<p><b id=\"udpcon\"><h3>UDP connections: ").append(peers.size());
        buf.append(". Limit: ").append(getMaxConnections());
        buf.append(". Timeout: ").append(DataHelper.formatDuration(_expireTimeout));
        buf.append(".</b></h3>\n");
        buf.append("<div class=\"wideload\"><table>\n");
        buf.append("<tr><th class=\"smallhead\" nowrap><a href=\"#def.peer\">Peer</a>");
        if (sortFlags != FLAG_ALPHA)
            buf.append(" <a href=\"").append(urlBase).append("?sort=0\">V</a> ");
        buf.append("</th><th class=\"smallhead\" nowrap><a href=\"#def.dir\" title=\"Direction/Introduction\">Dir</a></th><th class=\"smallhead\" nowrap><a href=\"#def.idle\">Idle</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by idle inbound", FLAG_IDLE_IN);
        buf.append("/");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by idle outbound", FLAG_IDLE_OUT);
        buf.append("</th>");
        buf.append("<th class=\"smallhead\" nowrap><a href=\"#def.rate\">In/Out</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by inbound rate", FLAG_RATE_IN);
        buf.append("/");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by outbound rate", FLAG_RATE_OUT);
        buf.append("</th>\n");
        buf.append("<th class=\"smallhead\" nowrap><a href=\"#def.up\">Up</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by connection uptime", FLAG_UPTIME);
        buf.append("</th><th class=\"smallhead\" nowrap><a href=\"#def.skew\">Skew</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by clock skew", FLAG_SKEW);
        buf.append("</th>\n");
        buf.append("<th class=\"smallhead\" nowrap><a href=\"#def.cwnd\">Cwnd</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by congestion window", FLAG_CWND);
        buf.append("</th><th class=\"smallhead\" nowrap><a href=\"#def.ssthresh\">Sst</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by slow start threshold", FLAG_SSTHRESH);
        buf.append("</th>\n");
        buf.append("<th class=\"smallhead\" nowrap><a href=\"#def.rtt\">Rtt</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by round trip time", FLAG_RTT);
        buf.append("</th><th class=\"smallhead\" nowrap><a href=\"#def.dev\">Dev</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by round trip time deviation", FLAG_DEV);
        buf.append("</th><th class=\"smallhead\" nowrap><a href=\"#def.rto\">Rto</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by retransmission timeout", FLAG_RTO);
        buf.append("</th>\n");
        buf.append("<th class=\"smallhead\" nowrap><a href=\"#def.mtu\">Mtu</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by maximum transmit unit", FLAG_MTU);
        buf.append("</th><th class=\"smallhead\" nowrap><a href=\"#def.send\">TX</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by packets sent", FLAG_SEND);
        buf.append("</th><th class=\"smallhead\" nowrap><a href=\"#def.recv\">RX</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by packets received", FLAG_RECV);
        buf.append("</th>\n");
        buf.append("<th class=\"smallhead\" nowrap><a href=\"#def.resent\">ReTX</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by packets retransmitted", FLAG_RESEND);
        buf.append("</th><th class=\"smallhead\" nowrap><a href=\"#def.dupRecv\">DupRX</a><br>");
        appendSortLinks(buf, urlBase, sortFlags, "Sort by packets received more than once", FLAG_DUP);
        buf.append("</th>\n");
        buf.append("</tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        long now = _context.clock().now();
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            PeerState peer = (PeerState)iter.next();
            if (now-peer.getLastReceiveTime() > 60*60*1000)
                continue; // don't include old peers
            
            buf.append("<tr> <td class=\"cells\" align=\"left\" nowrap>");
            buf.append(_context.commSystem().renderPeerHTML(peer.getRemotePeer()));
            //byte ip[] = peer.getRemoteIP();
            //if (ip != null)
            //    buf.append(' ').append(_context.blocklist().toStr(ip));
            buf.append("</td> <td class=\"cells\" nowrap align=\"left\">");
            if (peer.isInbound())
                buf.append("<img src=\"/themes/console/images/inbound.png\" alt=\"Inbound\" title=\"Inbound\"/> ");
            else
                buf.append("<img src=\"/themes/console/images/outbound.png\" alt=\"Outbound\" title=\"Outbound\"/> ");
            if (peer.getWeRelayToThemAs() > 0)
                buf.append("^");
            else
                buf.append("&nbsp;");
            if (peer.getTheyRelayToUsAs() > 0)
                buf.append("v");
            
            boolean appended = false;
            if (_activeThrottle.isChoked(peer.getRemotePeer())) {
                if (!appended) buf.append("<br />");
                buf.append(" <i>Choked</i>");
                appended = true;
            }
            if (peer.getConsecutiveFailedSends() > 0) {
                if (!appended) buf.append("<br />");
                buf.append(" <i>").append(peer.getConsecutiveFailedSends()).append(" fail(s)</i>");
                appended = true;
            }
            if (_context.shitlist().isShitlisted(peer.getRemotePeer(), STYLE)) {
                if (!appended) buf.append("<br />");
                buf.append(" <i>Shitlist</i>");
                appended = true;
            }
            //byte[] ip = getIP(peer.getRemotePeer());
            //if (ip != null)
            //    buf.append(' ').append(_context.blocklist().toStr(ip));
            buf.append("</td>");
            
            long idleIn = (now-peer.getLastReceiveTime())/1000;
            long idleOut = (now-peer.getLastSendTime())/1000;
            if (idleIn < 0) idleIn = 0;
            if (idleOut < 0) idleOut = 0;
            
            buf.append(" <td class=\"cells\" align=\"right\" >");
            buf.append(idleIn);
            buf.append("s/");
            buf.append(idleOut);
            buf.append("s</td>");
 
            int recvBps = (idleIn > 2 ? 0 : peer.getReceiveBps());
            int sendBps = (idleOut > 2 ? 0 : peer.getSendBps());
            
            buf.append(" <td class=\"cells\" align=\"right\" nowrap>");
            buf.append(formatKBps(recvBps));
            buf.append("/");
            buf.append(formatKBps(sendBps));
            buf.append("K/s ");
            //buf.append(formatKBps(peer.getReceiveACKBps()));
            //buf.append("K/s/");
            //buf.append(formatKBps(peer.getSendACKBps()));
            //buf.append("K/s ");
            buf.append("</td>");

            long uptime = now - peer.getKeyEstablishedTime();
            
            buf.append(" <td class=\"cells\" align=\"center\" >");
            buf.append(DataHelper.formatDuration(uptime));
            buf.append("</td>");
            
            buf.append(" <td class=\"cells\" align=\"center\" >");
            buf.append(peer.getClockSkew());
            buf.append("s</td>");
            offsetTotal = offsetTotal + peer.getClockSkew();

            long sendWindow = peer.getSendWindowBytes();
            
            buf.append(" <td class=\"cells\" align=\"right\" >");
            buf.append(sendWindow/1024);
            buf.append("K");
            buf.append("/").append(peer.getConcurrentSends());
            buf.append("/").append(peer.getConcurrentSendWindow());
            buf.append("/").append(peer.getConsecutiveSendRejections());
            buf.append("</td>");

            buf.append(" <td class=\"cells\" align=\"center\" >");
            buf.append(peer.getSlowStartThreshold()/1024);
            buf.append("K</td>");

            int rtt = peer.getRTT();
            int rto = peer.getRTO();
            
            buf.append(" <td class=\"cells\" align=\"right\" >");
            buf.append(rtt);
            buf.append("</td>");
            
            buf.append(" <td class=\"cells\" align=\"right\" >");
            buf.append(peer.getRTTDeviation());
            buf.append("</td>");

            buf.append(" <td class=\"cells\" align=\"right\" >");
            buf.append(rto);
            buf.append("</td>");
            
            buf.append(" <td class=\"cells\" align=\"right\" >");
            buf.append(peer.getMTU()).append("/").append(peer.getReceiveMTU());
            
            //.append('/');
            //buf.append(peer.getMTUIncreases()).append('/');
            //buf.append(peer.getMTUDecreases());
            buf.append("</td>");
        
            long sent = peer.getPacketsTransmitted();
            long recv = peer.getPacketsReceived();
            
            buf.append(" <td class=\"cells\" align=\"center\" >");
            buf.append(sent);
            buf.append("</td>");
            
            buf.append(" <td class=\"cells\" align=\"center\" >");
            buf.append(recv);
            buf.append("</td>");
            
            //double sent = (double)peer.getPacketsPeriodTransmitted();
            //double sendLostPct = 0;
            //if (sent > 0)
            //    sendLostPct = (double)peer.getPacketsRetransmitted()/(sent);
            
            long resent = peer.getPacketsRetransmitted();
            long dupRecv = peer.getPacketsReceivedDuplicate();
            
            buf.append(" <td class=\"cells\" align=\"center\" >");
            //buf.append(formatPct(sendLostPct));
            buf.append(resent); // + "/" + peer.getPacketsPeriodRetransmitted() + "/" + sent);
            //buf.append(peer.getPacketRetransmissionRate());
            buf.append("</td>");
            
            double recvDupPct = (double)peer.getPacketsReceivedDuplicate()/(double)peer.getPacketsReceived();
            buf.append(" <td class=\"cells\" align=\"center\" >");
            buf.append(dupRecv); //formatPct(recvDupPct));
            buf.append("</td>");

            buf.append("</tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
            
            bpsIn += recvBps;
            bpsOut += sendBps;
        
            uptimeMsTotal += uptime;
            cwinTotal += sendWindow;
            rttTotal += rtt;
            rtoTotal += rto;
        
            sendTotal += sent;
            recvTotal += recv;
            resentTotal += resent;
            dupRecvTotal += dupRecv;
            
            numPeers++;
        }
        
//        buf.append("<tr><td colspan=\"16\"><hr /></td></tr>\n");
        buf.append(" <tr class=\"tablefooter\"> <td colspan=\"3\" align=\"right\"><b>Total</b></td>");
        buf.append("      <td align=\"center\" nowrap><b>");
        buf.append(formatKBps(bpsIn)).append("/").append(formatKBps(bpsOut));
        buf.append("K/s</b></td>");
        buf.append("      <td align=\"center\"><b>").append(numPeers > 0 ? DataHelper.formatDuration(uptimeMsTotal/numPeers) : "0s");
        buf.append("</b></td> <td align=\"center\"><b>").append(numPeers > 0 ? DataHelper.formatDuration(offsetTotal*1000/numPeers) : "0ms").append("</b></td>\n");
        buf.append("      <td align=\"center\"><b>");
        buf.append(numPeers > 0 ? cwinTotal/(numPeers*1024) + "K" : "0K");
        buf.append("</b></td> <td>&nbsp;</td>\n");
        buf.append("      <td align=\"center\"><b>");
        buf.append(numPeers > 0 ? rttTotal/numPeers : 0);
        buf.append("</b></td> <td>&nbsp;</td> <td align=\"center\"><b>");
        buf.append(numPeers > 0 ? rtoTotal/numPeers : 0);
        buf.append("</b></td>\n      <td>&nbsp;</td> <td align=\"center\"><b>");
        buf.append(sendTotal).append("</td></b> <td align=\"center\"><b>").append(recvTotal).append("</b></td>\n");
        buf.append("      <td align=\"center\"><b>").append(resentTotal);
        buf.append("</b></td> <td align=\"center\"><b>").append(dupRecvTotal).append("</b></td>\n");
        buf.append(" </tr></table></div></p><p>\n");
        long bytesTransmitted = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        // NPE here early
        double averagePacketSize = _context.statManager().getRate("udp.sendPacketSize").getLifetimeAverageValue();
        // lifetime value, not just the retransmitted packets of current connections
        resentTotal = (long)_context.statManager().getRate("udp.packetsRetransmitted").getLifetimeEventCount();
        double nondupSent = ((double)bytesTransmitted - ((double)resentTotal)*averagePacketSize);
        double bwResent = (nondupSent <= 0 ? 0d : ((((double)resentTotal)*averagePacketSize) / nondupSent));
        buf.append("<h3>Percentage of bytes retransmitted (lifetime): ").append(formatPct(bwResent));
        buf.append("</h3><i>(Includes retransmission required by packet loss)</i><br /></p>\n");
        out.write(buf.toString());
        buf.setLength(0);
        out.write(KEY);
    }
    
    private static final DecimalFormat _fmt = new DecimalFormat("#,##0.00");
    private static final String formatKBps(int bps) {
        synchronized (_fmt) {
            return _fmt.format((float)bps/1024);
        }
    }
    private static final DecimalFormat _pctFmt = new DecimalFormat("#0.0%");
    private static final String formatPct(double pct) {
        synchronized (_pctFmt) {
            return _pctFmt.format(pct);
        }
    }
    
    private static final String KEY = "<h3>Definitions:</h3><div class=\"configure\">" +
        "<br><b id=\"def.peer\">Peer</b>: the remote peer.<br />\n" +
        "<b id=\"def.dir\">Dir</b>: v means they offer to introduce us, ^ means we offer to introduce them.<br />\n" +
        "<b id=\"def.idle\">Idle</b>: the idle time is how long since a packet has been received or sent.<br />\n" +
        "<b id=\"def.rate\">In/out</b>: the rates show a smoothed inbound and outbound transfer rate (KBytes per second).<br />\n" +
        "<b id=\"def.up\">Up</b>: the uptime is how long ago this session was established.<br />\n" +
        "<b id=\"def.skew\">Skew</b>: the skew says how far off the other user's clock is, relative to your own.<br />\n" +
        "<b id=\"def.cwnd\">Cwnd</b>: the congestion window is how many bytes in 'in flight' you can send w/out an acknowledgement, / <br />\n" +
        "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; the number of currently active messages being sent, /<br />\n" +
        "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; the maximum number of concurrent messages to send, /<br />\n"+ 
        "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; the number of consecutive sends which were blocked due to throws message window size.<br />\n" +
        "<b id=\"def.ssthresh\">Sst</b>: the slow start threshold helps make sure the cwnd doesn't grow too fast.<br />\n" +
        "<b id=\"def.rtt\">Rtt</b>: the round trip time is how long it takes to get an acknowledgement of a packet.<br />\n" +
        "<b id=\"def.dev\">Dev</b>: the standard deviation of the round trip time, to help control the retransmit timeout.<br />\n" +
        "<b id=\"def.rto\">Rto</b>: the retransmit timeout controls how frequently an unacknowledged packet will be retransmitted.<br />\n" +
        "<b id=\"def.mtu\">Mtu</b>: current sending packet size / estimated receiving packet size.<br />\n" +
        "<b id=\"def.send\">TX</b>: the number of packets sent to the peer.<br />\n" +
        "<b id=\"def.recv\">RX</b>: the number of packets received from the peer.<br />\n" +
        "<b id=\"def.resent\">ReTX</b>: the number of packets retransmitted to the peer.<br />\n" +
        "<b id=\"def.dupRecv\">DupRX</b>: the number of duplicate packets received from the peer." +
        "</div>\n";
    
    /**
     * Cache the bid to reduce object churn
     */
    private class SharedBid extends TransportBid {
        public SharedBid(int ms) { super(); setLatencyMs(ms); }
        @Override
        public Transport getTransport() { return UDPTransport.this; }
        @Override
        public String toString() { return "UDP bid @ " + getLatencyMs(); }
    }
    
    private class ExpirePeerEvent implements SimpleTimer.TimedEvent {
        private final List _expirePeers;
        private List _expireBuffer;
        private boolean _alive;
        public ExpirePeerEvent() {
            _expirePeers = new ArrayList(128);
            _expireBuffer = new ArrayList(128);
        }
        public void timeReached() {
            // Increase allowed idle time if we are well under allowed connections, otherwise decrease
            if (haveCapacity())
                _expireTimeout = Math.min(_expireTimeout + 15*1000, EXPIRE_TIMEOUT);
            else
                _expireTimeout = Math.max(_expireTimeout - 45*1000, MIN_EXPIRE_TIMEOUT);
            long shortInactivityCutoff = _context.clock().now() - _expireTimeout;
            long longInactivityCutoff = _context.clock().now() - EXPIRE_TIMEOUT;
            long pingCutoff = _context.clock().now() - (2 * 60*60*1000);
            _expireBuffer.clear();
            synchronized (_expirePeers) {
                int sz = _expirePeers.size();
                for (int i = 0; i < sz; i++) {
                    PeerState peer = (PeerState)_expirePeers.get(i);
                    long inactivityCutoff;
                    // if we offered to introduce them, or we used them as introducer in last 2 hours
                    if (peer.getWeRelayToThemAs() > 0 || peer.getIntroducerTime() > pingCutoff)
                        inactivityCutoff = longInactivityCutoff;
                    else
                        inactivityCutoff = shortInactivityCutoff;
                    if ( (peer.getLastReceiveTime() < inactivityCutoff) && (peer.getLastSendTime() < inactivityCutoff) ) {
                        _expireBuffer.add(peer);
                        _expirePeers.remove(i);
                        i--;
                        sz--;
                    }
                }
            }
            for (int i = 0; i < _expireBuffer.size(); i++)
                dropPeer((PeerState)_expireBuffer.get(i), false, "idle too long");
            _expireBuffer.clear();

            if (_alive)
                SimpleTimer.getInstance().addEvent(ExpirePeerEvent.this, 30*1000);
        }
        public void add(PeerState peer) {
            synchronized (_expirePeers) {
                _expirePeers.add(peer);
            }
        }
        public void remove(PeerState peer) {
            synchronized (_expirePeers) {
                _expirePeers.remove(peer);
            }
        }
        public void setIsAlive(boolean isAlive) {
            _alive = isAlive;
            if (isAlive) {
                SimpleTimer.getInstance().addEvent(ExpirePeerEvent.this, 30*1000);
            } else {
                SimpleTimer.getInstance().removeEvent(ExpirePeerEvent.this);
                synchronized (_expirePeers) {
                    _expirePeers.clear();
                }
            }
        }
    }
    
    /**
     * If we haven't had a non-unknown test result in 5 minutes, we really dont know.  Otherwise,
     * when we receive an unknown we should ignore that value and try again (with different peers)
     *
     */
    private static final long STATUS_GRACE_PERIOD = 5*60*1000;
    private long _statusLastCalled;
    private short _lastStatus = CommSystemFacade.STATUS_UNKNOWN;
    
    void setReachabilityStatus(short status) { 
        short old = _reachabilityStatus;
        long now = _context.clock().now();
        switch (status) {
            case CommSystemFacade.STATUS_OK:
                _context.statManager().addRateData("udp.statusOK", 1, 0);
                _reachabilityStatus = status; 
                _reachabilityStatusLastUpdated = now;
                break;
            case CommSystemFacade.STATUS_DIFFERENT:
                _context.statManager().addRateData("udp.statusDifferent", 1, 0);
                _reachabilityStatus = status; 
                _reachabilityStatusLastUpdated = now;
                break;
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
                _context.statManager().addRateData("udp.statusReject", 1, 0);
// if old != unsolicited && now - lastUpdated > STATUS_GRACE_PERIOD)
//
                // fall through...
            case CommSystemFacade.STATUS_HOSED:
                _reachabilityStatus = status; 
                _reachabilityStatusLastUpdated = now;
                break;
            case CommSystemFacade.STATUS_UNKNOWN:
            default:
                _context.statManager().addRateData("udp.statusUnknown", 1, 0);
                //if (now - _reachabilityStatusLastUpdated < STATUS_GRACE_PERIOD) {
                //    _testEvent.forceRun();
                //    SimpleTimer.getInstance().addEvent(_testEvent, 5*1000);
                //} else {
                //    _reachabilityStatus = status;
                //    _reachabilityStatusLastUpdated = now;
                //}
                break;
        }
        _statusLastCalled = now;
        _lastStatus = status;
        if ( (status != old) && (status != CommSystemFacade.STATUS_UNKNOWN) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Old status: " + old + " New status: " + status + " from: ", new Exception("traceback"));
            // Always rebuild when the status changes, even if our address hasn't changed,
            // as rebuildExternalAddress() calls replaceAddress() which calls CSFI.notifyReplaceAddress()
            // which will start up NTCP inbound when we transition to OK.
            // if (needsRebuild())
                rebuildExternalAddress();
        }
    }
    private static final String PROP_REACHABILITY_STATUS_OVERRIDE = "i2np.udp.status";
    public short getReachabilityStatus() { 
        String override = _context.getProperty(PROP_REACHABILITY_STATUS_OVERRIDE);
        if (override == null)
            return _reachabilityStatus;
            
        if ("ok".equals(override))
            return CommSystemFacade.STATUS_OK;
        else if ("err-reject".equals(override))
            return CommSystemFacade.STATUS_REJECT_UNSOLICITED;
        else if ("err-different".equals(override))
            return CommSystemFacade.STATUS_DIFFERENT;
        
        return _reachabilityStatus; 
    }
    @Override
    public void recheckReachability() {
        _testEvent.runTest();
    }
    
    public PeerState pickTestPeer(RemoteHostId dontInclude) {// LINT -- Exporting non-public type through public API
        List peers = null;
        synchronized (_peersByIdent) {
            peers = new ArrayList(_peersByIdent.values());
        }
        Collections.shuffle(peers, _context.random());
        for (int i = 0; i < peers.size(); i++) {
            PeerState peer = (PeerState)peers.get(i);
            if ( (dontInclude != null) && (dontInclude.equals(peer.getRemoteHostId())) )
                continue;
            RouterInfo peerInfo = _context.netDb().lookupRouterInfoLocally(peer.getRemotePeer());
            if (peerInfo == null)
                continue;
            RouterAddress addr = peerInfo.getTargetAddress(STYLE);
            if (addr != null)
                return peer;
        }
        return null;
    }
    
    private static final String PROP_SHOULD_TEST = "i2np.udp.shouldTest";
    
    private boolean shouldTest() {
        return ! _context.router().isHidden();
        //String val = _context.getProperty(PROP_SHOULD_TEST);
        //return ( (val != null) && ("true".equals(val)) );
    }
    
    private class PeerTestEvent implements SimpleTimer.TimedEvent {
        private boolean _alive;
        /** when did we last test our reachability */
        private long _lastTested;
        private boolean _forceRun;

        public void timeReached() {
            if (shouldTest()) {
                long now = _context.clock().now();
                if ( (_forceRun) || (now - _lastTested >= TEST_FREQUENCY) ) {
                    runTest();
                }
            }
            if (_alive) {
                long delay = (TEST_FREQUENCY / 2) + _context.random().nextInt(TEST_FREQUENCY);
                if (delay <= 0)
                    throw new RuntimeException("wtf, delay is " + delay);
                SimpleTimer.getInstance().addEvent(PeerTestEvent.this, delay);
            }
        }
        
        private void runTest() {
            PeerState bob = pickTestPeer(null);
            if (bob != null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Running periodic test with bob = " + bob);
                _testManager.runTest(bob.getRemoteIPAddress(), bob.getRemotePort(), bob.getCurrentCipherKey(), bob.getCurrentMACKey());
                _lastTested = _context.clock().now();
                _forceRun = false;
                return;
            }
            
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to run a periodic test, as there are no peers with the capacity required");
            _forceRun = false;
        }
        
        void forceRun() { _forceRun = true; }
        
        public void setIsAlive(boolean isAlive) {
            _alive = isAlive;
            if (isAlive) {
                long delay = _context.random().nextInt(2*TEST_FREQUENCY);
                SimpleTimer.getInstance().addEvent(PeerTestEvent.this, delay);
            } else {
                SimpleTimer.getInstance().removeEvent(PeerTestEvent.this);
            }
        }
    }
    
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
}
