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
        
    /** how many relays offered to us will we use at a time? */
    public static final int PUBLIC_RELAY_COUNT = 3;
    
    /** configure the priority queue with the given split points */
    private static final int PRIORITY_LIMITS[] = new int[] { 100, 200, 300, 400, 500, 1000 };
    /** configure the priority queue with the given weighting per priority group */
    private static final int PRIORITY_WEIGHT[] = new int[] { 1, 1, 1, 1, 1, 2 };

    /** should we flood all UDP peers with the configured rate? */
    private static final boolean SHOULD_FLOOD_PEERS = false;
    
    private static final int MAX_CONSECUTIVE_FAILED = 5;
    
    private static final int TEST_FREQUENCY = 3*60*1000;
    
    public UDPTransport(RouterContext ctx) {
        super(ctx);
        _context = ctx;
        _log = ctx.logManager().getLog(UDPTransport.class);
        _peersByIdent = new HashMap(128);
        _peersByRemoteHost = new HashMap(128);
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
        
        _context.statManager().createRateStat("udp.alreadyConnected", "What is the lifetime of a reestablished session", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.droppedPeer", "How long ago did we receive from a dropped peer (duration == session lifetime", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.droppedPeerInactive", "How long ago did we receive from a dropped peer (duration == session lifetime)", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.statusOK", "How many times the peer test returned OK", "udp", new long[] { 5*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.statusDifferent", "How many times the peer test returned different IP/ports", "udp", new long[] { 5*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.statusReject", "How many times the peer test returned reject unsolicited", "udp", new long[] { 5*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.statusUnknown", "How many times the peer test returned an unknown result", "udp", new long[] { 5*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.addressTestInsteadOfUpdate", "How many times we fire off a peer test of ourselves instead of adjusting our own reachable address?", "udp", new long[] { 1*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.addressUpdated", "How many times we adjust our own reachable IP address", "udp", new long[] { 1*60*1000, 20*60*1000, 60*60*1000, 24*60*60*1000 });
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
                      + ", receivedInboundRecent? " + inboundRecent);
        
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
            if (_log.shouldLog(Log.WARN))
                _log.warn("Ignoring IP address suggestion, since we have received an inbound con recently");
        } else {
            synchronized (this) {
                if ( (_externalListenHost == null) ||
                     (!eq(_externalListenHost.getAddress(), _externalListenPort, ourIP, ourPort)) ) {
                    if ( (_reachabilityStatus == CommSystemFacade.STATUS_UNKNOWN) ||
                         (_context.clock().now() - _reachabilityStatusLastUpdated > 2*TEST_FREQUENCY) ) {
                        // they told us something different and our tests are either old or failing
                        try {
                            _externalListenHost = InetAddress.getByAddress(ourIP);
                            if (!fixedPort)
                                _externalListenPort = ourPort;
                            rebuildExternalAddress();
                            replaceAddress(_externalAddress);
                            updated = true;
                        } catch (UnknownHostException uhe) {
                            _externalListenHost = null;
                        }
                    } else {
                        // they told us something different, but our tests are recent and positive,
                        // so lets test again
                        fireTest = true;
                    }
                } else {
                    // matched what we expect
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
        long oldEstablishedOn = -1;
        PeerState oldPeer = null;
        if (peer.getRemotePeer() != null) {
            synchronized (_peersByIdent) {
                oldPeer = (PeerState)_peersByIdent.put(peer.getRemotePeer(), peer);
                if ( (oldPeer != null) && (oldPeer != peer) ) {
                    // should we transfer the oldPeer's RTT/RTO/etc? nah
                    // or perhaps reject the new session?  nah, 
                    // using the new one allow easier reconnect
                    oldEstablishedOn = oldPeer.getKeyEstablishedTime();
                }
            }
        }
        
        oldPeer = null;
        
        RemoteHostId remoteId = peer.getRemoteHostId();
        if (remoteId == null) return false;
        
        synchronized (_peersByRemoteHost) {
            oldPeer = (PeerState)_peersByRemoteHost.put(remoteId, peer);
            if ( (oldPeer != null) && (oldPeer != peer) ) {
                //_peersByRemoteHost.put(remoteString, oldPeer);
                //return false;
                oldEstablishedOn = oldPeer.getKeyEstablishedTime();
            }
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
        
        // if we need introducers, try to shift 'em around every 10 minutes
        if (introducersRequired() && (_introducersSelectedOn < _context.clock().now() - 10*60*1000))
            rebuildExternalAddress();
        
        if (getReachabilityStatus() != CommSystemFacade.STATUS_OK) {
            _testEvent.forceRun();
            SimpleTimer.getInstance().addEvent(_testEvent, 0);
        }
        return true;
    }
    
    public RouterAddress getCurrentAddress() {
        // if we need introducers, try to shift 'em around every 10 minutes
        if (introducersRequired() && (_introducersSelectedOn < _context.clock().now() - 10*60*1000))
            rebuildExternalAddress(false);
        return super.getCurrentAddress();
    }
    
    private void dropPeer(PeerState peer) {
        dropPeer(peer, true);
    }
    private void dropPeer(PeerState peer, boolean shouldShitlist) {
        if (_log.shouldLog(Log.INFO)) {
            long now = _context.clock().now();
            StringBuffer buf = new StringBuffer(4096);
            long timeSinceSend = now - peer.getLastSendTime();
            long timeSinceRecv = now - peer.getLastReceiveTime();
            long timeSinceAck  = now - peer.getLastACKSend();
            buf.append("Dropping remote peer: ").append(peer.toString()).append(" shitlist? ").append(shouldShitlist);
            buf.append(" lifetime: ").append(now - peer.getKeyEstablishedTime());
            buf.append(" time since send/recv/ack: ").append(timeSinceSend).append(" / ");
            buf.append(timeSinceRecv).append(" / ").append(timeSinceAck);
            
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
            _log.info(buf.toString(), new Exception("Dropped by"));
        }
        
        _introManager.remove(peer);
        // a bit overzealous - perhaps we should only rebuild the external if the peer being dropped
        // is one of our introducers?
        rebuildExternalAddress();
        
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
                _peersByIdent.remove(peer.getRemotePeer());
            }
        }
        
        RemoteHostId remoteId = peer.getRemoteHostId();
        if (remoteId != null) {
            synchronized (_peersByRemoteHost) {
                _peersByRemoteHost.remove(remoteId);
            }
        }
        
        // unchoke 'em, but just because we'll never talk again...
        _activeThrottle.unchoke(peer.getRemotePeer());
        
        if (SHOULD_FLOOD_PEERS)
            _flooder.removePeer(peer);
        _expireEvent.remove(peer);
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
    
    public String getStyle() { return STYLE; }
    public void send(OutNetMessage msg) { 
        if (msg == null) return;
        if (msg.getTarget() == null) return;
        if (msg.getTarget().getIdentity() == null) return;
        
        Hash to = msg.getTarget().getIdentity().calculateHash();
        if (getPeerState(to) != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending outbound message to an established peer: " + to.toBase64());
            _outboundMessages.add(msg);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending outbound message to an unestablished peer: " + to.toBase64());
            _establisher.establish(msg);
        }
    }
    void send(I2NPMessage msg, PeerState peer) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Injecting a data message to a new peer: " + peer);
        OutboundMessageState state = new OutboundMessageState(_context);
        state.initialize(msg, peer);
        _fragments.add(state);
    }

    public OutNetMessage getNextMessage() { return getNextMessage(-1); }
    /**
     * Get the next message, blocking until one is found or the expiration
     * reached.
     *
     * @param blockUntil expiration, or -1 if indefinite
     */
    public OutNetMessage getNextMessage(long blockUntil) {
        return _outboundMessages.getNext(blockUntil);
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
        boolean introducersRequired = introducersRequired();
        if (introducersRequired) {
            List peers = new ArrayList(PUBLIC_RELAY_COUNT);
            int found = 0;
            _introManager.pickInbound(peers, PUBLIC_RELAY_COUNT);
            if (_log.shouldLog(Log.INFO))
                _log.info("Introducers required, picked peers: " + peers);
            for (int i = 0; i < peers.size(); i++) {
                PeerState peer = (PeerState)peers.get(i);
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer.getRemotePeer());
                if (ri == null) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Picked peer has no local routerInfo: " + peer);
                    continue;
                }
                RouterAddress ra = ri.getTargetAddress(STYLE);
                if (ra == null) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Picked peer has no SSU address: " + ri);
                    continue;
                }
                UDPAddress ura = new UDPAddress(ra);
                options.setProperty(UDPAddress.PROP_INTRO_HOST_PREFIX + i, peer.getRemoteHostId().toHostString());
                options.setProperty(UDPAddress.PROP_INTRO_PORT_PREFIX + i, String.valueOf(peer.getRemotePort()));
                options.setProperty(UDPAddress.PROP_INTRO_KEY_PREFIX + i, Base64.encode(ura.getIntroKey()));
                options.setProperty(UDPAddress.PROP_INTRO_TAG_PREFIX + i, String.valueOf(peer.getTheyRelayToUsAs()));
                found++;
            }
            if (found > 0) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Picked peers: " + found);
                _introducersSelectedOn = _context.clock().now();
            }
        }
        if ( (_externalListenPort > 0) && (_externalListenHost != null) && (isValid(_externalListenHost.getAddress())) ) {
            options.setProperty(UDPAddress.PROP_PORT, String.valueOf(_externalListenPort));
            options.setProperty(UDPAddress.PROP_HOST, _externalListenHost.getHostAddress());
            // if we have explicit external addresses, they had better be reachable
            if (introducersRequired)
                options.setProperty(UDPAddress.PROP_CAPACITY, ""+UDPAddress.CAPACITY_TESTING);
            else
                options.setProperty(UDPAddress.PROP_CAPACITY, ""+UDPAddress.CAPACITY_TESTING + UDPAddress.CAPACITY_INTRODUCER);
        }
        options.setProperty(UDPAddress.PROP_INTRO_KEY, _introKey.toBase64());
        
        RouterAddress addr = new RouterAddress();
        addr.setCost(5);
        addr.setExpiration(null);
        addr.setTransportStyle(STYLE);
        addr.setOptions(options);
        
        boolean wantsRebuild = false;
        if ( (_externalAddress == null) || !(_externalAddress.equals(addr)) )
            wantsRebuild = true;
        _externalAddress = addr;
        if (_log.shouldLog(Log.INFO))
            _log.info("Address rebuilt: " + addr);
        replaceAddress(addr);
        if (allowRebuildRouterInfo)
            _context.router().rebuildRouterInfo();
    }
    
    public boolean introducersRequired() {
        String forceIntroducers = _context.getProperty(PROP_FORCE_INTRODUCERS);
        if ( (forceIntroducers != null) && (Boolean.valueOf(forceIntroducers).booleanValue()) )
            return true;
        switch (getReachabilityStatus()) {
            case CommSystemFacade.STATUS_REJECT_UNSOLICITED:
            case CommSystemFacade.STATUS_DIFFERENT:
                return true;
            default:
                return false;
        }
    }
    
    String getPacketHandlerStatus() {
        PacketHandler handler = _handler;
        if (handler != null)
            return handler.getHandlerStatus();
        else
            return "";
    }
    
    public void failed(OutboundMessageState msg) {
        if (msg == null) return;
        int consecutive = 0;
        if ( (msg.getPeer() != null) && 
             ( (msg.getMaxSends() >= OutboundMessageFragments.MAX_VOLLEYS) ||
               (msg.isExpired())) ) {
            consecutive = msg.getPeer().incrementConsecutiveFailedSends();
            if (_log.shouldLog(Log.WARN))
                _log.warn("Consecutive failure #" + consecutive + " sending to " + msg.getPeer());
            if (consecutive > MAX_CONSECUTIVE_FAILED)
                dropPeer(msg.getPeer());
        }
        failed(msg.getMessage());
    }
    
    public void failed(OutNetMessage msg) {
        if (msg == null) return;
        if (_log.shouldLog(Log.WARN))
            _log.warn("Sending message failed: " + msg, new Exception("failed from"));
        super.afterSend(msg, false);
    }
    public void succeeded(OutNetMessage msg) {
        if (msg == null) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending message succeeded: " + msg);
        super.afterSend(msg, true);
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
        buf.append(" <tr><td><b>peer</b></td><td><b>idle</b></td>");
        buf.append("     <td><b>in/out</b></td>\n");
        buf.append("     <td><b>up</b></td><td><b>skew</b></td>\n");
        buf.append("     <td><b>cwnd</b></td><td><b>ssthresh</b></td>\n");
        buf.append("     <td><b>rtt</b></td><td><b>dev</b></td><td><b>rto</b></td>\n");
        buf.append("     <td><b>send</b></td><td><b>recv</b></td>\n");
        buf.append("     <td><b>resent</b></td><td><b>dupRecv</b></td>\n");
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
 
            int recvBps = (idleIn > 10 ? 0 : peer.getReceiveBps());
            int sendBps = (idleOut > 10 ? 0 : peer.getSendBps());
            
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
            buf.append("K</code></td>");

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

            buf.append("</tr>");
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
        
        buf.append("<tr><td colspan=\"14\"><hr /></td></tr>\n");
        buf.append(" <tr><td colspan=\"2\"><b>Total</b></td>");
        buf.append("     <td>");
        buf.append(formatKBps(bpsIn)).append("KBps/").append(formatKBps(bpsOut));
        buf.append("KBps</td>");
        buf.append("     <td>").append(numPeers > 0 ? DataHelper.formatDuration(uptimeMsTotal/numPeers) : "0s");
        buf.append("</td><td>&nbsp;</td>\n");
        buf.append("     <td>");
        buf.append(numPeers > 0 ? cwinTotal/(numPeers*1024) + "K" : "0K");
        buf.append("</td><td>&nbsp;</td>\n");
        buf.append("     <td>");
        buf.append(numPeers > 0 ? rttTotal/numPeers : 0);
        buf.append("</td><td>&nbsp;</td><td>");
        buf.append(numPeers > 0 ? rtoTotal/numPeers : 0);
        buf.append("</td>\n     <td>");
        buf.append(sendTotal).append("</td><td>").append(recvTotal).append("</td>\n");
        buf.append("     <td>").append(resentTotal);
        buf.append("</td><td>").append(dupRecvTotal).append("</td>\n");
        buf.append(" </tr>\n");
        buf.append("<tr><td colspan=\"14\" valign=\"top\" align=\"left\">");
        long bytesTransmitted = _context.bandwidthLimiter().getTotalAllocatedOutboundBytes();
        double averagePacketSize = _context.statManager().getRate("udp.sendPacketSize").getLifetimeAverageValue();
        double nondupSent = ((double)bytesTransmitted - ((double)resentTotal)*averagePacketSize);
        double bwResent = (nondupSent <= 0 ? 0d : ((((double)resentTotal)*averagePacketSize) / nondupSent));
        buf.append("Percentage of bytes retransmitted (lifetime): ").append(formatPct(bwResent));
        buf.append(" <i>(includes retransmission required by packet loss)</i><br />\n");
        buf.append("</td></tr>\n");
        out.write(buf.toString());
        buf.setLength(0);
        
        out.write("</table>\n");
        
        buf.append("<b>Average clock skew, UDP peers:");
        if (peers.size() > 0)
            buf.append(offsetTotal / peers.size()).append("ms</b><br><br>\n");
        else
            buf.append("n/a</b><br><br>\n");
        
        out.write(buf.toString());
        buf.setLength(0);
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
        private List _peers;
        // toAdd and toRemove are kept separate from _peers so that add and
        // remove calls won't block packet handling while the big iteration is
        // in process
        private List _toAdd;
        private List _toRemove;
        private boolean _alive;
        public ExpirePeerEvent() {
            _peers = new ArrayList(128);
            _toAdd = new ArrayList(4);
            _toRemove = new ArrayList(4);
        }
        public void timeReached() {
            long inactivityCutoff = _context.clock().now() - EXPIRE_TIMEOUT;
            for (int i = 0; i < _peers.size(); i++) {
                PeerState peer = (PeerState)_peers.get(i);
                if ( (peer.getLastReceiveTime() < inactivityCutoff) && (peer.getLastSendTime() < inactivityCutoff) ) {
                    dropPeer(peer, false);
                    _peers.remove(i);
                    i--;
                }
            }
            synchronized (_toAdd) {
                for (int i = 0; i < _toAdd.size(); i++) {
                    PeerState peer = (PeerState)_toAdd.get(i);
                    _peers.remove(peer);  // in case we are switching peers
                    _peers.add(peer);
                }
                _toAdd.clear();
            }
            synchronized (_toRemove) {
                for (int i = 0; i < _toRemove.size(); i++) {
                    PeerState peer = (PeerState)_toRemove.get(i);
                    _peers.remove(peer);
                }
                _toRemove.clear();
            }
            if (_alive)
                SimpleTimer.getInstance().addEvent(ExpirePeerEvent.this, 5*60*1000);
        }
        public void add(PeerState peer) {
            synchronized (_toAdd) {
                _toAdd.add(peer);
            }
        }
        public void remove(PeerState peer) {
            synchronized (_toRemove) {
                _toRemove.add(peer);
            }
        }
        public void setIsAlive(boolean isAlive) {
            _alive = isAlive;
            if (isAlive) {
                SimpleTimer.getInstance().addEvent(ExpirePeerEvent.this, 5*60*1000);
            } else {
                SimpleTimer.getInstance().removeEvent(ExpirePeerEvent.this);
                synchronized (_toAdd) {
                    _toAdd.clear();
                }
                synchronized (_peers) {
                    _peers.clear();
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
            
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to run a periodic test, as there are no peers with the capacity required");
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
