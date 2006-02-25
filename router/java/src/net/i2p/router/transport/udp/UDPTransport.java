package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import java.io.IOException;
import java.io.Writer;

import java.text.DecimalFormat;

import java.util.*;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.Router;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.transport.TransportBid;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 *
 */
public class UDPTransport extends TransportImpl implements TimedWeightedPriorityMessageQueue.FailedListener {
    private RouterContext _context;
    private Log _log;
    private UDPEndpoint _endpoint;
    /** Peer (Hash) to PeerState */
    private Map _peersByIdent;
    /** RemoteHostId to PeerState */
    private Map _peersByRemoteHost;
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
    /** port number on which we can be reached, or -1 */
    private int _externalListenPort;
    /** IP address of externally reachable host, or null */
    private InetAddress _externalListenHost;
    /** introduction key */
    private SessionKey _introKey;
    
    /** shared fast bid for connected peers */
    private TransportBid _fastBid;
    /** shared slow bid for unconnected peers */
    private TransportBid _slowBid;
    /** shared slow bid for unconnected peers when we want to prefer UDP */
    private TransportBid _slowPreferredBid;
    
    /** list of RemoteHostId for peers whose packets we want to drop outright */
    private List _dropList;
    
    private static final int DROPLIST_PERIOD = 10*60*1000;
    private static final int MAX_DROPLIST_SIZE = 256;
    
    public static final String STYLE = "SSU";
    public static final String PROP_INTERNAL_PORT = "i2np.udp.internalPort";

    /** define this to explicitly set an external IP address */
    public static final String PROP_EXTERNAL_HOST = "i2np.udp.host";
    /** define this to explicitly set an external port */
    public static final String PROP_EXTERNAL_PORT = "i2np.udp.port";
    /** 
     * If i2np.udp.alwaysPreferred is set, the UDP bids will always be under 
     * the bid from the TCP transport - even if a TCP connection already 
     * exists.  If this is true (the default), it will always prefer UDP, otherwise
     * it will prefer UDP unless no UDP session exists and a TCP connection 
     * already exists.
     */
    public static final String PROP_ALWAYS_PREFER_UDP = "i2np.udp.alwaysPreferred";
    private static final String DEFAULT_ALWAYS_PREFER_UDP = "true";
    
    public static final String PROP_FIXED_PORT = "i2np.udp.fixedPort";
    private static final String DEFAULT_FIXED_PORT = "true";

    /** do we require introducers, regardless of our status? */
    public static final String PROP_FORCE_INTRODUCERS = "i2np.udp.forceIntroducers";
    /** do we allow direct SSU connections, sans introducers?  */
    public static final String PROP_ALLOW_DIRECT = "i2np.udp.allowDirect";
        
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
        _slowBid = new SharedBid(1000);
        _slowPreferredBid = new SharedBid(75);
        
        _fragments = new OutboundMessageFragments(_context, this, _activeThrottle);
        _inboundFragments = new InboundMessageFragments(_context, _fragments, this);
        _flooder = new UDPFlooder(_context, this);
        _expireEvent = new ExpirePeerEvent();
        _testEvent = new PeerTestEvent();
        _reachabilityStatus = CommSystemFacade.STATUS_UNKNOWN;
        _introManager = new IntroductionManager(_context, this);
        _introducersSelectedOn = -1;
        _lastInboundReceivedOn = -1;
        _needsRebuild = true;
        
        _context.statManager().createRateStat("udp.alreadyConnected", "What is the lifetime of a reestablished session", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.droppedPeer", "How long ago did we receive from a dropped peer (duration == session lifetime", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.droppedPeerInactive", "How long ago did we receive from a dropped peer (duration == session lifetime)", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.statusOK", "How many times the peer test returned OK", "udp", new long[] { 5*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.statusDifferent", "How many times the peer test returned different IP/ports", "udp", new long[] { 5*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.statusReject", "How many times the peer test returned reject unsolicited", "udp", new long[] { 5*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.statusUnknown", "How many times the peer test returned an unknown result", "udp", new long[] { 5*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.addressTestInsteadOfUpdate", "How many times we fire off a peer test of ourselves instead of adjusting our own reachable address?", "udp", new long[] { 1*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.addressUpdated", "How many times we adjust our own reachable IP address", "udp", new long[] { 1*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.proactiveReestablish", "How long a session was idle for when we proactively reestablished it", "udp", new long[] { 1*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.dropPeerDroplist", "How many peers currently have their packets dropped outright when a new peer is added to the list?", "udp", new long[] { 1*60*1000, 20*60*1000 });
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
            String portStr = _context.getProperty(PROP_INTERNAL_PORT);
            if (portStr != null) {
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException nfe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Invalid port specified [" + portStr + "]");
                }
            }
            if (port <= 0) {
                port = 8887;
                //port = 1024 + _context.random().nextInt(31*1024);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Selecting an arbitrary port to bind to: " + port);
                _context.router().setConfigSetting(PROP_INTERNAL_PORT, port+"");
                // attempt to use it as our external port - this will be overridden by
                // externalAddressReceived(...)
                _context.router().setConfigSetting(PROP_EXTERNAL_PORT, port+"");
                _context.router().saveConfig();
            }
        } else {
            port = _externalListenPort;
            if (_log.shouldLog(Log.INFO))
                _log.info("Binding to the explicitly specified external port: " + port);
        }
        if (_endpoint == null) {
            try {
                _endpoint = new UDPEndpoint(_context, this, port);
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
     * Someone we tried to contact gave us what they think our IP address is.
     * Right now, we just blindly trust them, changing our IP and port on a
     * whim.  this is not good ;)
     *
     */
    void externalAddressReceived(Hash from, byte ourIP[], int ourPort) {
        boolean isValid = isValid(ourIP);
        boolean explicitSpecified = explicitAddressSpecified();
        boolean inboundRecent = _lastInboundReceivedOn + ALLOW_IP_CHANGE_INTERVAL > System.currentTimeMillis();
        if (_log.shouldLog(Log.INFO))
            _log.info("External address received: " + RemoteHostId.toString(ourIP) + ":" + ourPort + " from " 
                      + from.toBase64() + ", isValid? " + isValid + ", explicitSpecified? " + explicitSpecified 
                      + ", receivedInboundRecent? " + inboundRecent + " status " + _reachabilityStatus);
        
        if (explicitSpecified) 
            return;
            
        boolean fixedPort = getIsPortFixed();
        boolean updated = false;
        boolean fireTest = false;
        if (!isValid) {
            // ignore them 
            if (_log.shouldLog(Log.ERROR))
                _log.error("The router " + from.toBase64() + " told us we have an invalid IP - " 
                           + RemoteHostId.toString(ourIP) + ".  Lets throw tomatoes at them");
            _context.shitlist().shitlistRouter(from, "They said we had an invalid IP");
            return;
        } else if (inboundRecent) {
            // use OS clock since its an ordering thing, not a time thing
            if (_log.shouldLog(Log.INFO))
                _log.info("Ignoring IP address suggestion, since we have received an inbound con recently");
        } else {
            synchronized (this) {
                if ( (_externalListenHost == null) ||
                     (!eq(_externalListenHost.getAddress(), _externalListenPort, ourIP, ourPort)) ) {
                    if ( (_reachabilityStatus == CommSystemFacade.STATUS_UNKNOWN) ||
                         (_context.clock().now() - _reachabilityStatusLastUpdated > 2*TEST_FREQUENCY) ) {
                        // they told us something different and our tests are either old or failing
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Trying to change our external address...");
                        try {
                            _externalListenHost = InetAddress.getByAddress(ourIP);
                            if (!fixedPort)
                                _externalListenPort = ourPort;
                            rebuildExternalAddress();
                            replaceAddress(_externalAddress);
                            updated = true;
                        } catch (UnknownHostException uhe) {
                            _externalListenHost = null;
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Error trying to change our external address", uhe);
                        }
                    } else {
                        // they told us something different, but our tests are recent and positive,
                        // so lets test again
                        fireTest = true;
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Different address, but we're fine..");
                    }
                } else {
                    // matched what we expect
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Same address as the current one");
                }
            }
        }
        
        if (fireTest) {
            _context.statManager().addRateData("udp.addressTestInsteadOfUpdate", 1, 0);
            _testEvent.forceRun();
            SimpleTimer.getInstance().addEvent(_testEvent, 5*1000);
        } else if (updated) {
            _context.statManager().addRateData("udp.addressUpdated", 1, 0);
            if (!fixedPort)
                _context.router().setConfigSetting(PROP_EXTERNAL_PORT, ourPort+"");
            _context.router().saveConfig();
            _context.router().rebuildRouterInfo();
            _testEvent.forceRun();
            SimpleTimer.getInstance().addEvent(_testEvent, 5*1000);
        }
    }
    
    private static final boolean eq(byte laddr[], int lport, byte raddr[], int rport) {
        return (rport == lport) && DataHelper.eq(laddr, raddr);
    }
    
    public final boolean isValid(byte addr[]) {
        if (addr == null) return false;
        if (addr.length < 4) return false;
        if (isPubliclyRoutable(addr)) 
            return true;
        return Boolean.valueOf(_context.getProperty("i2np.udp.allowLocal", "false")).booleanValue();
    }
    
    private boolean getIsPortFixed() {
        return DEFAULT_FIXED_PORT.equals(_context.getProperty(PROP_FIXED_PORT, DEFAULT_FIXED_PORT));
    }
    /** 
     * get the state for the peer at the given remote host/port, or null 
     * if no state exists
     */
    public PeerState getPeerState(RemoteHostId hostInfo) {
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
        _context.shitlist().unshitlistRouter(peer.getRemotePeer());

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
    
    public RouterAddress getCurrentAddress() {
        if (needsRebuild())
            rebuildExternalAddress(false);
        return super.getCurrentAddress();
    }
    
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
                        SimpleTimer.getInstance().addEvent(new RemoveDropList(remote), DROPLIST_PERIOD);
                    }
                }
                _context.shitlist().shitlistRouter(peerHash, "Part of the wrong network");                
                dropPeer(peerHash);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping the peer " + peerHash.toBase64() + " because they are in the wrong net");
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
    
    public boolean isInDropList(RemoteHostId peer) { synchronized (_dropList) { return _dropList.contains(peer); } }
    
    void dropPeer(Hash peer) {
        PeerState state = getPeerState(peer);
        if (state != null)
            dropPeer(state, false);
    }
    private void dropPeer(PeerState peer, boolean shouldShitlist) {
        if (_log.shouldLog(Log.WARN)) {
            long now = _context.clock().now();
            StringBuffer buf = new StringBuffer(4096);
            long timeSinceSend = now - peer.getLastSendTime();
            long timeSinceRecv = now - peer.getLastReceiveTime();
            long timeSinceAck  = now - peer.getLastACKSend();
            buf.append("Dropping remote peer: ").append(peer.toString()).append(" shitlist? ").append(shouldShitlist);
            buf.append(" lifetime: ").append(now - peer.getKeyEstablishedTime());
            buf.append(" time since send/recv/ack: ").append(timeSinceSend).append(" / ");
            buf.append(timeSinceRecv).append(" / ").append(timeSinceAck);
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
                long now = _context.clock().now();
                _context.statManager().addRateData("udp.droppedPeer", now - peer.getLastReceiveTime(), now - peer.getKeyEstablishedTime());
                _context.shitlist().shitlistRouter(peer.getRemotePeer(), "dropped after too many retries");
            } else {
                long now = _context.clock().now();
                _context.statManager().addRateData("udp.droppedPeerInactive", now - peer.getLastReceiveTime(), now - peer.getKeyEstablishedTime());
            }
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
        if ( (altByIdent != null) && (peer != altByIdent) ) dropPeer(altByIdent, shouldShitlist);
        if ( (altByHost != null) && (peer != altByHost) ) dropPeer(altByHost, shouldShitlist);
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
            if (_log.shouldLog(Log.INFO)) {
                if (rv) {
                    _log.info("Need to initialize our direct SSU info");
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
        Hash to = toAddress.getIdentity().calculateHash();
        PeerState peer = getPeerState(to);
        if (peer != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("bidding on a message to an established peer: " + peer);
            return _fastBid;
        } else {
            if (null == toAddress.getTargetAddress(STYLE))
                return null;

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("bidding on a message to an unestablished peer: " + to.toBase64());
            if (alwaysPreferUDP())
                return _slowPreferredBid;
            else
                return _slowBid;
        }
    }

    private boolean alwaysPreferUDP() {
        String pref = _context.getProperty(PROP_ALWAYS_PREFER_UDP, DEFAULT_ALWAYS_PREFER_UDP);
        return (pref != null) && "true".equals(pref);
    }
    
    private static final int MAX_IDLE_TIME = 60*1000;
    
    public String getStyle() { return STYLE; }
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
                    dropPeer(peer, false);
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
    }
    
    void setExternalListenPort(int port) { _externalListenPort = port; }
    void setExternalListenHost(InetAddress addr) { _externalListenHost = addr; }
    void setExternalListenHost(byte addr[]) throws UnknownHostException { 
        _externalListenHost = InetAddress.getByAddress(addr); 
    }

    private boolean explicitAddressSpecified() {
        return (_context.getProperty(PROP_EXTERNAL_HOST) != null);
    }
    
    void rebuildExternalAddress() { rebuildExternalAddress(true); }
    void rebuildExternalAddress(boolean allowRebuildRouterInfo) {
        if (_context.router().isHidden())
            return;
        
        // if the external port is specified, we want to use that to bind to even
        // if we don't know the external host.
        String port = _context.getProperty(PROP_EXTERNAL_PORT);
        if (port != null) { 
            try {
                _externalListenPort = Integer.parseInt(port);    
            } catch (NumberFormatException nfe) {
                _externalListenPort = -1;
            }
        }
        
        if (explicitAddressSpecified()) {
            try {
                String host = _context.getProperty(PROP_EXTERNAL_HOST);
                _externalListenHost = InetAddress.getByName(host);
            } catch (UnknownHostException uhe) {
                _externalListenHost = null;
            }
        }
            
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
        String forceIntroducers = _context.getProperty(PROP_FORCE_INTRODUCERS);
        if ( (forceIntroducers != null) && (Boolean.valueOf(forceIntroducers).booleanValue()) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Force introducers specified");
            return true;
        }
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
            if (_log.shouldLog(Log.WARN))
                _log.warn("Consecutive failure #" + consecutive 
                          + " on " + msg.toString()
                          + " to " + msg.getPeer());
            if ( (consecutive > MAX_CONSECUTIVE_FAILED) && (msg.getPeer().getInactivityTime() > DROP_INACTIVITY_TIME))
                dropPeer(msg.getPeer(), false);
            else if (consecutive > 2 * MAX_CONSECUTIVE_FAILED) // they're sending us data, but we cant reply?
                dropPeer(msg.getPeer(), false);
        }
        noteSend(msg, false);
        if (m != null)
            super.afterSend(m, false);
    }
    
    private void noteSend(OutboundMessageState msg, boolean successful) {
        int pushCount = msg.getPushCount();
        int sends = msg.getMaxSends();
        boolean expired = msg.isExpired();
     
        OutNetMessage m = msg.getMessage();
        PeerState p = msg.getPeer();
        StringBuffer buf = new StringBuffer(64);
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
        if (_log.shouldLog(Log.WARN))
            _log.warn("Sending message failed: " + msg, new Exception("failed from"));
        
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
    
    private static class AlphaComparator implements Comparator {
        private static final AlphaComparator _instance = new AlphaComparator();
        public static final AlphaComparator instance() { return _instance; }
        
        public int compare(Object lhs, Object rhs) {
            if ( (lhs == null) || (rhs == null) || !(lhs instanceof PeerState) || !(rhs instanceof PeerState)) 
                throw new IllegalArgumentException("rhs = " + rhs + " lhs = " + lhs);
            PeerState l = (PeerState)lhs;
            PeerState r = (PeerState)rhs;
            // base64 retains binary ordering
            return DataHelper.compareTo(l.getRemotePeer().getData(), r.getRemotePeer().getData());
        }
        
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
    
    public void renderStatusHTML(Writer out) throws IOException {
        TreeSet peers = new TreeSet(AlphaComparator.instance());
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
        
        StringBuffer buf = new StringBuffer(512);
        buf.append("<b id=\"udpcon\">UDP connections: ").append(peers.size()).append("</b><br />\n");
        buf.append("<table border=\"1\">\n");
        buf.append(" <tr><td><b><a href=\"#def.peer\">peer</a></b></td><td><b><a href=\"#def.idle\">idle</a></b></td>");
        buf.append("     <td><b><a href=\"#def.rate\">in/out</a></b></td>\n");
        buf.append("     <td><b><a href=\"#def.up\">up</a></b></td><td><b><a href=\"#def.skew\">skew</a></b></td>\n");
        buf.append("     <td><b><a href=\"#def.cwnd\">cwnd</a></b></td><td><b><a href=\"#def.ssthresh\">ssthresh</a></b></td>\n");
        buf.append("     <td><b><a href=\"#def.rtt\">rtt</a></b></td><td><b><a href=\"#def.dev\">dev</a></b></td><td><b><a href=\"#def.rto\">rto</a></b></td>\n");
        buf.append("     <td><b><a href=\"#def.mtu\">mtu</a></b></td><td><b><a href=\"#def.send\">send</a></b></td><td><b><a href=\"#def.recv\">recv</a></b></td>\n");
        buf.append("     <td><b><a href=\"#def.resent\">resent</a></b></td><td><b><a href=\"#def.dupRecv\">dupRecv</a></b></td>\n");
        buf.append(" </tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        long now = _context.clock().now();
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            PeerState peer = (PeerState)iter.next();
            if (now-peer.getLastReceiveTime() > 60*60*1000)
                continue; // don't include old peers
            
            buf.append("<tr>");
            
            String name = peer.getRemotePeer().toBase64().substring(0,6);
            buf.append("<td valign=\"top\" nowrap=\"nowrap\"><code>");
            buf.append("<a href=\"netdb.jsp#");
            buf.append(name);
            buf.append("\">");
            buf.append(name).append("@");
            byte ip[] = peer.getRemoteIP();
            for (int j = 0; j < ip.length; j++) {
                int num = ip[j] & 0xFF;
                if (num < 10)
                    buf.append("00");
                else if (num < 100)
                    buf.append("0");
                buf.append(num);
                if (j + 1 < ip.length)
                    buf.append('.');
            }
            buf.append(':');
            int port = peer.getRemotePort();
            if (port < 10)
                buf.append("0000");
            else if (port < 100)
                buf.append("000");
            else if (port < 1000)
                buf.append("00");
            else if (port < 10000)
                buf.append("0");
            buf.append(port);
            buf.append("</a>");
            if (peer.getWeRelayToThemAs() > 0)
                buf.append("&gt;");
            else
                buf.append("&nbsp;");
            if (peer.getTheyRelayToUsAs() > 0)
                buf.append("&lt;");
            else
                buf.append("&nbsp;");
            
            boolean appended = false;
            if (_activeThrottle.isChoked(peer.getRemotePeer())) {
                if (!appended) buf.append("<br />");
                buf.append(" [choked]");
                appended = true;
            }
            if (peer.getConsecutiveFailedSends() > 0) {
                if (!appended) buf.append("<br />");
                buf.append(" [").append(peer.getConsecutiveFailedSends()).append(" failures]");
                appended = true;
            }
            if (_context.shitlist().isShitlisted(peer.getRemotePeer())) {
                if (!appended) buf.append("<br />");
                buf.append(" [shitlisted]");
                appended = true;
            }
            buf.append("</code></td>");
            
            long idleIn = (now-peer.getLastReceiveTime())/1000;
            long idleOut = (now-peer.getLastSendTime())/1000;
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(idleIn);
            buf.append("s/");
            buf.append(idleOut);
            buf.append("s</code></td>");
 
            int recvBps = (idleIn > 2 ? 0 : peer.getReceiveBps());
            int sendBps = (idleOut > 2 ? 0 : peer.getSendBps());
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(formatKBps(recvBps));
            buf.append("KBps/");
            buf.append(formatKBps(sendBps));
            buf.append("KBps ");
            //buf.append(formatKBps(peer.getReceiveACKBps()));
            //buf.append("KBps/");
            //buf.append(formatKBps(peer.getSendACKBps()));
            //buf.append("KBps ");
            buf.append("</code></td>");

            long uptime = now - peer.getKeyEstablishedTime();
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(DataHelper.formatDuration(uptime));
            buf.append("</code></td>");
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(peer.getClockSkew()/1000);
            buf.append("s</code></td>");
            offsetTotal = offsetTotal + peer.getClockSkew();

            long sendWindow = peer.getSendWindowBytes();
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(sendWindow/1024);
            buf.append("K");
            buf.append("/").append(peer.getConcurrentSends());
            buf.append("/").append(peer.getConcurrentSendWindow());
            buf.append("/").append(peer.getConsecutiveSendRejections());
            buf.append("</code></td>");

            buf.append("<td valign=\"top\" ><code>");
            buf.append(peer.getSlowStartThreshold()/1024);
            buf.append("K</code></td>");

            int rtt = peer.getRTT();
            int rto = peer.getRTO();
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(rtt);
            buf.append("</code></td>");
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(peer.getRTTDeviation());
            buf.append("</code></td>");

            buf.append("<td valign=\"top\" ><code>");
            buf.append(rto);
            buf.append("</code></td>");
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(peer.getMTU()).append("/").append(peer.getReceiveMTU());
            
            //.append('/');
            //buf.append(peer.getMTUIncreases()).append('/');
            //buf.append(peer.getMTUDecreases());
            buf.append("</code></td>");
        
            long sent = peer.getPacketsTransmitted();
            long recv = peer.getPacketsReceived();
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(sent);
            buf.append("</code></td>");
            
            buf.append("<td valign=\"top\" ><code>");
            buf.append(recv);
            buf.append("</code></td>");
            
            //double sent = (double)peer.getPacketsPeriodTransmitted();
            //double sendLostPct = 0;
            //if (sent > 0)
            //    sendLostPct = (double)peer.getPacketsRetransmitted()/(sent);
            
            long resent = peer.getPacketsRetransmitted();
            long dupRecv = peer.getPacketsReceivedDuplicate();
            
            buf.append("<td valign=\"top\" ><code>");
            //buf.append(formatPct(sendLostPct));
            buf.append(resent); // + "/" + peer.getPacketsPeriodRetransmitted() + "/" + sent);
            //buf.append(peer.getPacketRetransmissionRate());
            buf.append("</code></td>");
            
            double recvDupPct = (double)peer.getPacketsReceivedDuplicate()/(double)peer.getPacketsReceived();
            buf.append("<td valign=\"top\" ><code>");
            buf.append(dupRecv); //formatPct(recvDupPct));
            buf.append("</code></td>");

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
        
        buf.append("<tr><td colspan=\"15\"><hr /></td></tr>\n");
        buf.append(" <tr><td colspan=\"2\"><b>Total</b></td>");
        buf.append("     <td>");
        buf.append(formatKBps(bpsIn)).append("KBps/").append(formatKBps(bpsOut));
        buf.append("KBps</td>");
        buf.append("     <td>").append(numPeers > 0 ? DataHelper.formatDuration(uptimeMsTotal/numPeers) : "0s");
        buf.append("</td><td>").append(numPeers > 0 ? DataHelper.formatDuration(offsetTotal/numPeers) : "0ms").append("</td>\n");
        buf.append("     <td>");
        buf.append(numPeers > 0 ? cwinTotal/(numPeers*1024) + "K" : "0K");
        buf.append("</td><td>&nbsp;</td>\n");
        buf.append("     <td>");
        buf.append(numPeers > 0 ? rttTotal/numPeers : 0);
        buf.append("</td><td>&nbsp;</td><td>");
        buf.append(numPeers > 0 ? rtoTotal/numPeers : 0);
        buf.append("</td>\n     <td>&nbsp;</td><td>");
        buf.append(sendTotal).append("</td><td>").append(recvTotal).append("</td>\n");
        buf.append("     <td>").append(resentTotal);
        buf.append("</td><td>").append(dupRecvTotal).append("</td>\n");
        buf.append(" </tr>\n");
        buf.append("<tr><td colspan=\"15\" valign=\"top\" align=\"left\">");
        long bytesTransmitted = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        double averagePacketSize = _context.statManager().getRate("udp.sendPacketSize").getLifetimeAverageValue();
        // lifetime value, not just the retransmitted packets of current connections
        resentTotal = (long)_context.statManager().getRate("udp.packetsRetransmitted").getLifetimeEventCount();
        double nondupSent = ((double)bytesTransmitted - ((double)resentTotal)*averagePacketSize);
        double bwResent = (nondupSent <= 0 ? 0d : ((((double)resentTotal)*averagePacketSize) / nondupSent));
        buf.append("Percentage of bytes retransmitted (lifetime): ").append(formatPct(bwResent));
        buf.append(" <i>(includes retransmission required by packet loss)</i><br />\n");
        buf.append("</td></tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        out.write(KEY);
        out.write("</table>\n");
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
    
    private static final String KEY = "<tr><td colspan=\"15\" valign=\"top\" align=\"left\">" +
        "<b id=\"def.peer\">peer</b>: the remote peer (&lt; means they offer to introduce us, &gt; means we offer to introduce them)<br />\n" +
        "<b id=\"def.idle\">idle</b>: the idle time is how long since a packet has been received or sent<br />\n" +
        "<b id=\"def.rate\">in/out</b>: the rates show a smoothed inbound and outbound transfer rate (KBytes per second)<br />\n" +
        "<b id=\"def.up\">up</b>: the uptime is how long ago this session was established<br />\n" +
        "<b id=\"def.skew\">skew</b>: the skew says how far off the other user's clock is, relative to your own<br />\n" +
        "<b id=\"def.cwnd\">cwnd</b>: the congestion window is how many bytes in 'in flight' you can send w/out an acknowledgement / <br />\n" +
        "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; the number of currently active messages being sent /<br />\n" +
        "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; the maximum number of concurrent messages to send /<br />\n"+ 
        "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; the number of consecutive sends which were blocked due to throws message window size<br />\n" +
        "<b id=\"def.ssthresh\">ssthresh</b>: the slow start threshold help make sure the cwnd doesn't grow too fast<br />\n" +
        "<b id=\"def.rtt\">rtt</b>: the round trip time is how long it takes to get an acknowledgement of a packet<br />\n" +
        "<b id=\"def.dev\">dev</b>: the standard deviation of the round trip time, to help control the retransmit timeout<br />\n" +
        "<b id=\"def.rto\">rto</b>: the retransmit timeout controls how frequently an unacknowledged packet will be retransmitted<br />\n" +
        "<b id=\"def.mtu\">mtu</b>: current sending packet size / estimated receiving packet size<br />\n" +
        "<b id=\"def.send\">send</b>: the number of packets sent to the peer<br />\n" +
        "<b id=\"def.recv\">recv</b>: the number of packets received from the peer<br />\n" +
        "<b id=\"def.resent\">resent</b>: the number of packets retransmitted to the peer<br />\n" +
        "<b id=\"def.dupRecv\">dupRecv</b>: the number of duplicate packets received from the peer" +
        "</td></tr>\n";
    
    /**
     * Cache the bid to reduce object churn
     */
    private class SharedBid extends TransportBid {
        public SharedBid(int ms) { super(); setLatencyMs(ms); }
        public Transport getTransport() { return UDPTransport.this; }
        public String toString() { return "UDP bid @ " + getLatencyMs(); }
    }
    
    private static final int EXPIRE_TIMEOUT = 10*60*1000;
    
    private class ExpirePeerEvent implements SimpleTimer.TimedEvent {
        private List _expirePeers;
        private List _expireBuffer;
        private boolean _alive;
        public ExpirePeerEvent() {
            _expirePeers = new ArrayList(128);
            _expireBuffer = new ArrayList(128);
        }
        public void timeReached() {
            long inactivityCutoff = _context.clock().now() - EXPIRE_TIMEOUT;
            _expireBuffer.clear();
            synchronized (_expirePeers) {
                int sz = _expirePeers.size();
                for (int i = 0; i < sz; i++) {
                    PeerState peer = (PeerState)_expirePeers.get(i);
                    if ( (peer.getLastReceiveTime() < inactivityCutoff) && (peer.getLastSendTime() < inactivityCutoff) ) {
                        _expireBuffer.add(peer);
                        _expirePeers.remove(i);
                        i--;
                        sz--;
                    }
                }
            }
            for (int i = 0; i < _expireBuffer.size(); i++)
                dropPeer((PeerState)_expireBuffer.get(i), false);
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
        if ( (status != old) && (status != CommSystemFacade.STATUS_UNKNOWN) ) {
            if (needsRebuild())
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
    public void recheckReachability() {
        _testEvent.runTest();
    }
    
    public PeerState pickTestPeer(RemoteHostId dontInclude) {
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
        if (true) return true;
        String val = _context.getProperty(PROP_SHOULD_TEST);
        return ( (val != null) && ("true".equals(val)) );
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
                long delay = _context.random().nextInt(2*TEST_FREQUENCY);
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
