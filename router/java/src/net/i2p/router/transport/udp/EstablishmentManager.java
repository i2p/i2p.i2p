package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import static net.i2p.router.transport.udp.InboundEstablishState.InboundState.*;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.*;
import net.i2p.util.Addresses;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Coordinate the establishment of new sessions - both inbound and outbound.
 * This has its own thread to add packets to the packet queue when necessary,
 * as well as to drop any failed establishment attempts.
 *
 */
class EstablishmentManager {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final PacketBuilder _builder;
    /** map of RemoteHostId to InboundEstablishState */
    private final ConcurrentHashMap<RemoteHostId, InboundEstablishState> _inboundStates;
    /** map of RemoteHostId to OutboundEstablishState */
    private final ConcurrentHashMap<RemoteHostId, OutboundEstablishState> _outboundStates;
    /** map of RemoteHostId to List of OutNetMessage for messages exceeding capacity */
    private final ConcurrentHashMap<RemoteHostId, List<OutNetMessage>> _queuedOutbound;
    /** map of nonce (Long) to OutboundEstablishState */
    private final ConcurrentHashMap<Long, OutboundEstablishState> _liveIntroductions;
    private volatile boolean _alive;
    private final Object _activityLock;
    private int _activity;
    
    /** max outbound in progress - max inbound is half of this */
    private static final int DEFAULT_MAX_CONCURRENT_ESTABLISH = 30;
    private static final String PROP_MAX_CONCURRENT_ESTABLISH = "i2np.udp.maxConcurrentEstablish";

    /** max pending outbound connections (waiting because we are at MAX_CONCURRENT_ESTABLISH) */
    private static final int MAX_QUEUED_OUTBOUND = 50;

    /** max queued msgs per peer while the peer connection is queued */
    private static final int MAX_QUEUED_PER_PEER = 3;
    
    private static final long MAX_NONCE = 0xFFFFFFFFl;

    /**
     * Kill any outbound that takes more than this.
     * Two round trips (Req-Created-Confirmed-Data) for direct;
     * 3 1/2 round trips (RReq-RResp+Intro-HolePunch-Req-Created-Confirmed-Data) for indirect.
     * Note that this is way too long for us to be able to fall back to NTCP
     * for individual messages unless the message timer fires first.
     * But SSU probably isn't higher priority than NTCP.
     * And it's important to not fail an establishment too soon and waste it.
     */
    private static final int MAX_OB_ESTABLISH_TIME = 35*1000;

    /**
     * Kill any inbound that takes more than this
     * One round trip (Created-Confirmed)
     */
    private static final int MAX_IB_ESTABLISH_TIME = 20*1000;

    /** max before receiving a response to a single message during outbound establishment */
    private static final int OB_MESSAGE_TIMEOUT = 15*1000;

    /** for the DSM and or netdb store */
    private static final int DATA_MESSAGE_TIMEOUT = 10*1000;
    
    public EstablishmentManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(EstablishmentManager.class);
        _transport = transport;
        _builder = new PacketBuilder(ctx, transport);
        _inboundStates = new ConcurrentHashMap();
        _outboundStates = new ConcurrentHashMap();
        _queuedOutbound = new ConcurrentHashMap();
        _liveIntroductions = new ConcurrentHashMap();
        _activityLock = new Object();
        _context.statManager().createRateStat("udp.inboundEstablishTime", "How long it takes for a new inbound session to be established", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.outboundEstablishTime", "How long it takes for a new outbound session to be established", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.inboundEstablishFailedState", "What state a failed inbound establishment request fails in", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.outboundEstablishFailedState", "What state a failed outbound establishment request fails in", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendIntroRelayRequest", "How often we send a relay request to reach a peer", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendIntroRelayTimeout", "How often a relay request times out before getting a response (due to the target or intro peer being offline)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveIntroRelayResponse", "How long it took to receive a relay response", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishDropped", "Dropped an inbound establish message", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishRejected", "How many pending outbound connections are there when we refuse to add any more?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.establishOverflow", "How many messages were queued up on a pending connection when it was too much?", "udp", UDPTransport.RATES);
        // following are for PeerState
        _context.statManager().createRateStat("udp.congestionOccurred", "How large the cwin was when congestion occurred (duration == sendBps)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.congestedRTO", "retransmission timeout after congestion (duration == rtt dev)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendACKPartial", "Number of partial ACKs sent (duration == number of full ACKs in that ack packet)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendBps", "How fast we are transmitting when a packet is acked", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveBps", "How fast we are receiving when a packet is fully received (at most one per second)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.mtuIncrease", "How many retransmissions have there been to the peer when the MTU was increased (period is total packets transmitted)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.mtuDecrease", "How many retransmissions have there been to the peer when the MTU was decreased (period is total packets transmitted)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.rejectConcurrentActive", "How many messages are currently being sent to the peer when we reject it (period is how many concurrent packets we allow)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.allowConcurrentActive", "How many messages are currently being sent to the peer when we accept it (period is how many concurrent packets we allow)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.rejectConcurrentSequence", "How many consecutive concurrency rejections have we had when we stop rejecting (period is how many concurrent packets we are on)", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.queueDropSize", "How many messages were queued up when it was considered full, causing a tail drop?", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.queueAllowTotalLifetime", "When a peer is retransmitting and we probabalistically allow a new message, what is the sum of the pending message lifetimes? (period is the new message's lifetime)?", "udp", UDPTransport.RATES);
    }
    
    public void startup() {
        _alive = true;
        I2PThread t = new I2PThread(new Establisher(), "UDP Establisher", true);
        t.start();
    }

    public void shutdown() { 
        _alive = false;
        notifyActivity();
    }
    
    /**
     * Grab the active establishing state
     * @return null if none
     */
    InboundEstablishState getInboundState(RemoteHostId from) {
            InboundEstablishState state = _inboundStates.get(from);
            // if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
            //     _log.debug("No inbound states for " + from + ", with remaining: " + _inboundStates);
            return state;
    }
    
    /**
     * Grab the active establishing state
     * @return null if none
     */
    OutboundEstablishState getOutboundState(RemoteHostId from) {
            OutboundEstablishState state = _outboundStates.get(from);
            // if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
            //     _log.debug("No outbound states for " + from + ", with remaining: " + _outboundStates);
            return state;
    }
    
    /**
     * How many concurrent outbound sessions to deal with
     */
    private int getMaxConcurrentEstablish() {
        return _context.getProperty(PROP_MAX_CONCURRENT_ESTABLISH, DEFAULT_MAX_CONCURRENT_ESTABLISH);
    }
    
    /**
     * Send the message to its specified recipient by establishing a connection
     * with them and sending it off.  This call does not block, and on failure,
     * the message is failed.
     *
     */
    public void establish(OutNetMessage msg) {
        RouterAddress ra = msg.getTarget().getTargetAddress(_transport.getStyle());
        if (ra == null) {
            _transport.failed(msg, "Remote peer has no address, cannot establish");
            return;
        }
        if (msg.getTarget().getNetworkId() != Router.NETWORK_ID) {
            _context.shitlist().shitlistRouter(msg.getTarget().getIdentity().calculateHash());
            _transport.markUnreachable(msg.getTarget().getIdentity().calculateHash());
            _transport.failed(msg, "Remote peer is on the wrong network, cannot establish");
            return;
        }
        UDPAddress addr = new UDPAddress(ra);
        RemoteHostId to = null;
        InetAddress remAddr = addr.getHostAddress();
        int port = addr.getPort();
        if (remAddr != null && port > 0 && port <= 65535) {
            to = new RemoteHostId(remAddr.getAddress(), port);

            if (!_transport.isValid(to.getIP())) {
                _transport.failed(msg, "Remote peer's IP isn't valid");
                _transport.markUnreachable(msg.getTarget().getIdentity().calculateHash());
                //_context.shitlist().shitlistRouter(msg.getTarget().getIdentity().calculateHash(), "Invalid SSU address", UDPTransport.STYLE);
                return;
            }
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Add outbound establish state to: " + to);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Add indirect outbound establish state to: " + addr);
            to = new RemoteHostId(msg.getTarget().getIdentity().calculateHash().getData());
        }
        
        OutboundEstablishState state = null;
        int deferred = 0;
        boolean rejected = false;
        int queueCount = 0;

            state = _outboundStates.get(to);
            if (state == null) {
                if (_outboundStates.size() >= getMaxConcurrentEstablish()) {
                    if (_queuedOutbound.size() >= MAX_QUEUED_OUTBOUND) {
                        rejected = true;
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Queueing outbound establish, increase " + PROP_MAX_CONCURRENT_ESTABLISH);
                        List<OutNetMessage> newQueued = new ArrayList(MAX_QUEUED_PER_PEER);
                        List<OutNetMessage> queued = _queuedOutbound.putIfAbsent(to, newQueued);
                        if (queued == null)
                            queued = newQueued;
                        // this used to be inside a synchronized (_outboundStates) block,
                        // but that's now a CHM, so protect the ArrayList
                        // There are still races possible but this should prevent AIOOBE and NPE
                        synchronized (queued) {
                            queueCount = queued.size();
                            if (queueCount < MAX_QUEUED_PER_PEER) {
                                queued.add(msg);
                                // increment for the stat below
                                queueCount++;
                            }
                            deferred = _queuedOutbound.size();
                        }
                    }
                } else {
                    // must have a valid session key
                    byte[] keyBytes = addr.getIntroKey();
                    if (keyBytes == null) {
                        _transport.markUnreachable(msg.getTarget().getIdentity().calculateHash());
                        _transport.failed(msg, "Peer has no key, cannot establish");
                        return;
                    }
                    SessionKey sessionKey;
                    try {
                        sessionKey = new SessionKey(keyBytes);
                    } catch (IllegalArgumentException iae) {
                        _transport.markUnreachable(msg.getTarget().getIdentity().calculateHash());
                        _transport.failed(msg, "Peer has bad key, cannot establish");
                        return;
                    }
                    state = new OutboundEstablishState(_context, remAddr, port,
                                                       msg.getTarget().getIdentity(), 
                                                       sessionKey, addr, _transport.getDHBuilder());
                    OutboundEstablishState oldState = _outboundStates.putIfAbsent(to, state);
                    boolean isNew = oldState == null;
                    if (!isNew)
                        // whoops, somebody beat us to it, throw out the state we just created
                        state = oldState;
                }
            }
            if (state != null) {
                state.addMessage(msg);
                List<OutNetMessage> queued = _queuedOutbound.remove(to);
                if (queued != null) {
                    // see comments above
                    synchronized (queued) {
                        for (OutNetMessage m : queued) {
                            state.addMessage(m);
                        }
                    }
                }
            }
        
        if (rejected) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rejecting outbound establish");
            _transport.failed(msg, "Too many pending outbound connections");
            _context.statManager().addRateData("udp.establishRejected", deferred, 0);
            return;
        }
        if (queueCount >= MAX_QUEUED_PER_PEER) {
            _transport.failed(msg, "Too many pending messages for the given peer");
            _context.statManager().addRateData("udp.establishOverflow", queueCount, deferred);
            return;
        }
        
        if (deferred > 0)
            msg.timestamp("too many deferred establishers");
        else if (state != null)
            msg.timestamp("establish state already waiting");
        notifyActivity();
    }
    
    /**
     * How many concurrent inbound sessions to deal with
     */
    private int getMaxInboundEstablishers() { 
        return getMaxConcurrentEstablish()/2; 
    }
    
    /**
     * Got a SessionRequest (initiates an inbound establishment)
     *
     */
    void receiveSessionRequest(RemoteHostId from, UDPPacketReader reader) {
        if (!_transport.isValid(from.getIP())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Receive session request from invalid IP: " + from);
            return;
        }
        
        int maxInbound = getMaxInboundEstablishers();
        
        boolean isNew = false;

            InboundEstablishState state = _inboundStates.get(from);
            if (state == null) {
                // TODO this is insufficient to prevent DoSing, especially if
                // IP spoofing is used. For further study.
                if (_inboundStates.size() >= maxInbound) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping inbound establish, increase " + PROP_MAX_CONCURRENT_ESTABLISH);
                    _context.statManager().addRateData("udp.establishDropped", 1);
                    return; // drop the packet
                }
            
                if (_context.blocklist().isBlocklisted(from.getIP())) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Receive session request from blocklisted IP: " + from);
                    return; // drop the packet
                }
                if (!_transport.allowConnection())
                    return; // drop the packet
                state = new InboundEstablishState(_context, from.getIP(), from.getPort(), _transport.getLocalPort(),
                                                  _transport.getDHBuilder());
                state.receiveSessionRequest(reader.getSessionRequestReader());
                InboundEstablishState oldState = _inboundStates.putIfAbsent(from, state);
                isNew = oldState == null;
                if (!isNew)
                    // whoops, somebody beat us to it, throw out the state we just created
                    state = oldState;
            }

        if (isNew) {
            // we don't expect inbound connections when hidden, but it could happen
            // Don't offer if we are approaching max connections. While Relay Intros do not
            // count as connections, we have to keep the connection to this peer up longer if
            // we are offering introductions.
            if ((!_context.router().isHidden()) && (!_transport.introducersRequired()) && _transport.haveCapacity() &&
                !((FloodfillNetworkDatabaseFacade)_context.netDb()).floodfillEnabled()) {
                // ensure > 0
                long tag = 1 + _context.random().nextLong(MAX_TAG_VALUE);
                state.setSentRelayTag(tag);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Received NEW session request from " + from + ", sending relay tag " + tag);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Received session request, but our status is " + _transport.getReachabilityStatus());
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive DUP session request from: " + state.getRemoteHostId());
        }
        
        notifyActivity();
    }
    
    /** 
     * got a SessionConfirmed (should only happen as part of an inbound 
     * establishment) 
     */
    void receiveSessionConfirmed(RemoteHostId from, UDPPacketReader reader) {
        InboundEstablishState state = _inboundStates.get(from);
        if (state != null) {
            state.receiveSessionConfirmed(reader.getSessionConfirmedReader());
            notifyActivity();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive session confirmed from: " + state.getRemoteHostId().toString());
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Receive (DUP?) session confirmed from: " + from);
        }
    }
    
    /**
     * Got a SessionCreated (in response to our outbound SessionRequest)
     *
     */
    void receiveSessionCreated(RemoteHostId from, UDPPacketReader reader) {
        OutboundEstablishState state = _outboundStates.get(from);
        if (state != null) {
            state.receiveSessionCreated(reader.getSessionCreatedReader());
            notifyActivity();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive session created from: " + state.getRemoteHostId().toString());
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Receive (DUP?) session created from: " + from);
        }
    }

    /**
     * Got a SessionDestroy on an established conn
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from, PeerState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session destroy (EST) from: " + from);
        _transport.dropPeer(state, false, "received destroy message");
    }

    /**
     * Got a SessionDestroy during outbound establish
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from, OutboundEstablishState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session destroy (OB) from: " + from);
        _outboundStates.remove(from);
        Hash peer = state.getRemoteIdentity().calculateHash();
        _transport.dropPeer(peer, false, "received destroy message during OB establish");
    }

    /**
     * Got a SessionDestroy - maybe during an inbound establish?
     * TODO - PacketHandler won't look up inbound establishes
     * As this packet was essentially unauthenticated (i.e. intro key, not session key)
     * we just log it as it could be spoofed.
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Receive session destroy (none) from: " + from);
        //InboundEstablishState state = _inboundStates.remove(from);
        //if (state != null) {
        //    Hash peer = state.getConfirmedIdentity().calculateHash();
        //    if (peer != null)
        //        _transport.dropPeer(peer, false, "received destroy message");
        //}
    }

    /**
     * A data packet arrived on an outbound connection being established, which
     * means its complete (yay!).  This is a blocking call, more than I'd like...
     *
     */
    PeerState receiveData(OutboundEstablishState state) {
        state.dataReceived();
        //int active = 0;
        //int admitted = 0;
        //int remaining = 0;

            //active = _outboundStates.size();
            _outboundStates.remove(state.getRemoteHostId());
                // there shouldn't have been queued messages for this active state, but just in case...
                List<OutNetMessage> queued = _queuedOutbound.remove(state.getRemoteHostId());
                if (queued != null) {
                    // see comments above
                    synchronized (queued) {
                        for (OutNetMessage m : queued) {
                            state.addMessage(m);
                        }
                    }
                }
                
                locked_admitQueued();
            //remaining = _queuedOutbound.size();

        //if (admitted > 0)
        //    _log.log(Log.CRIT, "Admitted " + admitted + " with " + remaining + " remaining queued and " + active + " active");
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Outbound established completely!  yay: " + state);
        PeerState peer = handleCompletelyEstablished(state);
        notifyActivity();
        return peer;
    }

    /**
     *  Move pending OB messages from _queuedOutbound to _outboundStates.
     *  This isn't so great because _queuedOutbound is not a FIFO.
     */
    private int locked_admitQueued() {
        if (_queuedOutbound.isEmpty())
            return 0;
        int admitted = 0;
        int max = getMaxConcurrentEstablish();
        for (Iterator<Map.Entry<RemoteHostId, List<OutNetMessage>>> iter = _queuedOutbound.entrySet().iterator();
             iter.hasNext() && _outboundStates.size() < max; ) {
            // ok, active shrunk, lets let some queued in.

            Map.Entry<RemoteHostId, List<OutNetMessage>> entry = iter.next();
            iter.remove();
            RemoteHostId to = entry.getKey();
            List<OutNetMessage> allQueued = entry.getValue();
            List<OutNetMessage> queued = new ArrayList();
            long now = _context.clock().now();
            synchronized (allQueued) {
                for (OutNetMessage msg : allQueued) {
                    if (now - Router.CLOCK_FUDGE_FACTOR > msg.getExpiration()) {
                        _transport.failed(msg, "Took too long in est. mgr OB queue");
                    } else {
                        queued.add(msg);
                    }

                }
            }
            if (queued.isEmpty())
                continue;
            
            OutNetMessage msg = queued.get(0);
            RouterAddress ra = msg.getTarget().getTargetAddress(_transport.getStyle());
            if (ra == null) {
                for (int i = 0; i < queued.size(); i++) 
                    _transport.failed(queued.get(i), "Cannot admit to the queue, as it has no address");
                continue;
            }
            UDPAddress addr = new UDPAddress(ra);
            InetAddress remAddr = addr.getHostAddress();
            int port = addr.getPort();

            OutboundEstablishState qstate = new OutboundEstablishState(_context, remAddr, port, 
                                               msg.getTarget().getIdentity(), 
                                               new SessionKey(addr.getIntroKey()), addr,
                                               _transport.getDHBuilder());
            OutboundEstablishState old = _outboundStates.putIfAbsent(to, qstate);
            if (old != null)
                qstate = old;

            for (int i = 0; i < queued.size(); i++) {
                OutNetMessage m = queued.get(i);
                m.timestamp("no longer deferred... establishing");
                qstate.addMessage(m);
            }
            admitted++;
        }
        return admitted;
    }
    
    private void notifyActivity() {
        synchronized (_activityLock) { 
            _activity++;
            _activityLock.notifyAll(); 
        }
    }
    
    /** 
     * ok, fully received, add it to the established cons and queue up a
     * netDb store to them
     *
     */
    private void handleCompletelyEstablished(InboundEstablishState state) {
        if (state.complete()) return;
        
        RouterIdentity remote = state.getConfirmedIdentity();
        PeerState peer = new PeerState(_context, _transport,
                                       state.getSentIP(), state.getSentPort(), remote.calculateHash(), true);
        peer.setCurrentCipherKey(state.getCipherKey());
        peer.setCurrentMACKey(state.getMACKey());
        peer.setWeRelayToThemAs(state.getSentRelayTag());
        // 0 is the default
        //peer.setTheyRelayToUsAs(0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle completely established (inbound): " + state.getRemoteHostId().toString() 
                       + " - " + peer.getRemotePeer());
        
        //if (true) // for now, only support direct
        //    peer.setRemoteRequiresIntroduction(false);
        
        _transport.addRemotePeerState(peer);
        
        _transport.inboundConnectionReceived();
        _transport.setIP(remote.calculateHash(), state.getSentIP());
        
        _context.statManager().addRateData("udp.inboundEstablishTime", state.getLifetime(), 0);
        sendInboundComplete(peer);
    }

    /**
     * dont send our info immediately, just send a small data packet, and 5-10s later, 
     * if the peer isnt shitlisted, *then* send them our info.  this will help kick off
     * the oldnet
     * The "oldnet" was < 0.6.1.10, it is long gone.
     * The delay really slows down the network.
     * The peer is unshitlisted and marked reachable by addRemotePeerState() which calls markReachable()
     * so the check below is fairly pointless.
     * If for some strange reason an oldnet router (NETWORK_ID == 1) does show up,
     *  it's handled in UDPTransport.messageReceived()
     * (where it will get dropped, marked unreachable and shitlisted at that time).
     */
    private void sendInboundComplete(PeerState peer) {
        // SimpleTimer.getInstance().addEvent(new PublishToNewInbound(peer), 10*1000);
        if (_log.shouldLog(Log.INFO))
            _log.info("Completing to the peer after confirm: " + peer);
        DeliveryStatusMessage dsm = new DeliveryStatusMessage(_context);
        dsm.setArrival(Router.NETWORK_ID); // overloaded, sure, but future versions can check this
                                           // This causes huge values in the inNetPool.droppedDeliveryStatusDelay stat
                                           // so it needs to be caught in InNetMessagePool.
        dsm.setMessageExpiration(_context.clock().now() + DATA_MESSAGE_TIMEOUT);
        dsm.setMessageId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        _transport.send(dsm, peer);

        // just do this inline
        //_context.simpleScheduler().addEvent(new PublishToNewInbound(peer), 0);

            Hash hash = peer.getRemotePeer();
            if ((hash != null) && (!_context.shitlist().isShitlisted(hash)) && (!_transport.isUnreachable(hash))) {
                // ok, we are fine with them, send them our latest info
                //if (_log.shouldLog(Log.INFO))
                //    _log.info("Publishing to the peer after confirm plus delay (without shitlist): " + peer);
                sendOurInfo(peer, true);
            } else {
                // nuh uh.
                if (_log.shouldLog(Log.WARN))
                    _log.warn("NOT publishing to the peer after confirm plus delay (WITH shitlist): " + (hash != null ? hash.toString() : "unknown"));
            }
    }
    
    /** 
     * ok, fully received, add it to the established cons and send any
     * queued messages
     *
     */
    private PeerState handleCompletelyEstablished(OutboundEstablishState state) {
        if (state.complete()) {
            RouterIdentity rem = state.getRemoteIdentity();
            if (rem != null)
                return _transport.getPeerState(rem.getHash());
        }
        
        long now = _context.clock().now();
        RouterIdentity remote = state.getRemoteIdentity();
        PeerState peer = new PeerState(_context, _transport,
                                       state.getSentIP(), state.getSentPort(), remote.calculateHash(), false);
        peer.setCurrentCipherKey(state.getCipherKey());
        peer.setCurrentMACKey(state.getMACKey());
        peer.setTheyRelayToUsAs(state.getReceivedRelayTag());
        // 0 is the default
        //peer.setWeRelayToThemAs(0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle completely established (outbound): " + state.getRemoteHostId().toString() 
                       + " - " + peer.getRemotePeer());
        
        
        _transport.addRemotePeerState(peer);
        _transport.setIP(remote.calculateHash(), state.getSentIP());
        
        _context.statManager().addRateData("udp.outboundEstablishTime", state.getLifetime(), 0);
        sendOurInfo(peer, false);
        
        OutNetMessage msg;
        while ((msg = state.getNextQueuedMessage()) != null) {
            if (now - Router.CLOCK_FUDGE_FACTOR > msg.getExpiration()) {
                msg.timestamp("took too long but established...");
                _transport.failed(msg, "Took too long to establish, but it was established");
            } else {
                msg.timestamp("session fully established and sent");
                _transport.send(msg);
            }
        }
        return peer;
    }
    
    private void sendOurInfo(PeerState peer, boolean isInbound) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Publishing to the peer after confirm: " + 
                      (isInbound ? " inbound con from " + peer : "outbound con to " + peer));
        
        DatabaseStoreMessage m = new DatabaseStoreMessage(_context);
        m.setEntry(_context.router().getRouterInfo());
        m.setMessageExpiration(_context.clock().now() + DATA_MESSAGE_TIMEOUT);
        _transport.send(m, peer);
    }
    
    /** the relay tag is a 4-byte field in the protocol */
    public static final long MAX_TAG_VALUE = 0xFFFFFFFFl;
    
    private void sendCreated(InboundEstablishState state) {
        long now = _context.clock().now();
        // This is usually handled in receiveSessionRequest() above, except, I guess,
        // if the session isn't new and we are going through again.
        // Don't offer if we are approaching max connections (see comments above)
        // Also don't offer if we are floodfill, as this extends the max idle time
        // and we will have lots of incoming conns
        if ((!_context.router().isHidden()) && (!_transport.introducersRequired()) && _transport.haveCapacity() &&
            !((FloodfillNetworkDatabaseFacade)_context.netDb()).floodfillEnabled()) {
            // offer to relay
            // (perhaps we should check our bw usage and/or how many peers we are 
            //  already offering introducing?)
            if (state.getSentRelayTag() == 0) {
                // ensure > 0
                state.setSentRelayTag(1 + _context.random().nextLong(MAX_TAG_VALUE));
            } else {
                // don't change it, since we've already prepared our sig
            }
        } else {
            // don't offer to relay
            state.setSentRelayTag(0);
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send created to: " + state.getRemoteHostId().toString());
        
        try {
            state.generateSessionKey();
        } catch (DHSessionKeyBuilder.InvalidPublicParameterException ippe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Peer " + state.getRemoteHostId() + " sent us an invalid DH parameter (or were spoofed)", ippe);
            _inboundStates.remove(state.getRemoteHostId());
            return;
        }
        _transport.send(_builder.buildSessionCreatedPacket(state, _transport.getExternalPort(), _transport.getIntroKey()));
        state.createdPacketSent();
    }

    /**
     *  Caller should probably synch on outboundState
     */
    private void sendRequest(OutboundEstablishState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send SessionRequest to: " + state.getRemoteHostId());
        UDPPacket packet = _builder.buildSessionRequestPacket(state);
        if (packet != null) {
            _transport.send(packet);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build a session request packet for " + state.getRemoteHostId());
        }
        state.requestSent();
    }
    
    /**
     *  Send RelayRequests to multiple introducers.
     *  This may be called multiple times, it sets the nonce the first time only
     *  Caller should probably synch on state.
     */
    private void handlePendingIntro(OutboundEstablishState state) {
        long nonce = state.getIntroNonce();
        if (nonce < 0) {
            OutboundEstablishState old;
            do {
                nonce = _context.random().nextLong(MAX_NONCE);
                old = _liveIntroductions.putIfAbsent(Long.valueOf(nonce), state);
            } while (old != null);
            state.setIntroNonce(nonce);
        }
        _context.statManager().addRateData("udp.sendIntroRelayRequest", 1, 0);
        UDPPacket requests[] = _builder.buildRelayRequest(_transport, state, _transport.getIntroKey());
        for (int i = 0; i < requests.length; i++) {
            if (requests[i] != null)
                _transport.send(requests[i]);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send intro for " + state.getRemoteHostId() + " with our intro key as " + _transport.getIntroKey());
        state.introSent();
    }

    void receiveRelayResponse(RemoteHostId bob, UDPPacketReader reader) {
        long nonce = reader.getRelayResponseReader().readNonce();
        OutboundEstablishState state = _liveIntroductions.remove(Long.valueOf(nonce));
        if (state == null) 
            return; // already established
        
        int sz = reader.getRelayResponseReader().readCharlieIPSize();
        byte ip[] = new byte[sz];
        reader.getRelayResponseReader().readCharlieIP(ip, 0);
        InetAddress addr = null;
        try {
            addr = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Introducer for " + state + " (" + bob + ") sent us an invalid IP for our target: " + Addresses.toString(ip), uhe);
            // these two cause this peer to requeue for a new intro peer
            state.introductionFailed();
            notifyActivity();
            return;
        }
        _context.statManager().addRateData("udp.receiveIntroRelayResponse", state.getLifetime(), 0);
        int port = reader.getRelayResponseReader().readCharliePort();
        if (_log.shouldLog(Log.INFO))
            _log.info("Received relay intro for " + state.getRemoteIdentity().calculateHash() + " - they are on " 
                      + addr.toString() + ":" + port + " (according to " + bob + ")");
        RemoteHostId oldId = state.getRemoteHostId();
        state.introduced(addr, ip, port);
        _outboundStates.remove(oldId);
        _outboundStates.put(state.getRemoteHostId(), state);
        notifyActivity();
    }
    
    /**
     *  Note that while a SessionConfirmed could in theory be fragmented,
     *  in practice a RouterIdentity is 387 bytes and a single fragment is 512 bytes max,
     *  so it will never be fragmented.
     *  Caller should probably synch on state.
     */
    private void sendConfirmation(OutboundEstablishState state) {
        boolean valid = state.validateSessionCreated();
        if (!valid) // validate clears fields on failure
            return;
        
        if (!_transport.isValid(state.getReceivedIP()) || !_transport.isValid(state.getRemoteHostId().getIP())) {
            state.fail();
            return;
        }
        
        // gives us the opportunity to "detect" our external addr
        _transport.externalAddressReceived(state.getRemoteIdentity().calculateHash(), state.getReceivedIP(), state.getReceivedPort());
        
        // signs if we havent signed yet
        state.prepareSessionConfirmed();
        
        // BUG - handle null return
        UDPPacket packets[] = _builder.buildSessionConfirmedPackets(state, _context.router().getRouterInfo().getIdentity());
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send confirm to: " + state);
        
        for (int i = 0; i < packets.length; i++)
            _transport.send(packets[i]);
        
        state.confirmedPacketsSent();
    }
    
    /**
     *  Tell the other side never mind.
     *  This is only useful after we have received SessionCreated,
     *  and sent SessionConfirmed, but not yet gotten a data packet as an
     *  ack to the SessionConfirmed - otherwise we haven't generated the keys.
     *  Caller should probably synch on state.
     *
     *  @since 0.9.2
     */
    private void sendDestroy(OutboundEstablishState state) {
        UDPPacket packet = _builder.buildSessionDestroyPacket(state);
        if (packet != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Send destroy to: " + state);
            _transport.send(packet);
        }
    }
    
    /**
     *  Tell the other side never mind.
     *  This is only useful after we have sent SessionCreated,
     *  but not received SessionConfirmed
     *  Otherwise we haven't generated the keys.
     *  Caller should probably synch on state.
     *
     *  @since 0.9.2
     */
    private void sendDestroy(InboundEstablishState state) {
        UDPPacket packet = _builder.buildSessionDestroyPacket(state);
        if (packet != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Send destroy to: " + state);
            _transport.send(packet);
        }
    }
    
    /**
     * Drive through the inbound establishment states, adjusting one of them
     * as necessary
     * @return next requested time or -1
     */
    private long handleInbound() {
        long now = _context.clock().now();
        long nextSendTime = -1;
        InboundEstablishState inboundState = null;
        boolean expired = false;

            for (Iterator<InboundEstablishState> iter = _inboundStates.values().iterator(); iter.hasNext(); ) {
                InboundEstablishState cur = iter.next();
                if (cur.getState() == IB_STATE_CONFIRMED_COMPLETELY) {
                    // completely received (though the signature may be invalid)
                    iter.remove();
                    inboundState = cur;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing completely confirmed inbound state");
                    break;
                } else if (cur.getLifetime() > MAX_IB_ESTABLISH_TIME) {
                    // took too long
                    iter.remove();
                    inboundState = cur;
                    //_context.statManager().addRateData("udp.inboundEstablishFailedState", cur.getState(), cur.getLifetime());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing expired inbound state");
                    expired = true;
                    break;
                } else if (cur.getState() == IB_STATE_FAILED) {
                    iter.remove();
                    //_context.statManager().addRateData("udp.inboundEstablishFailedState", cur.getState(), cur.getLifetime());
                } else {
                    if (cur.getNextSendTime() <= now) {
                        // our turn...
                        inboundState = cur;
                        // if (_log.shouldLog(Log.DEBUG))
                        //     _log.debug("Processing inbound that wanted activity");
                        break;
                    } else {
                        // nothin to do but wait for them to send us
                        // stuff, so lets move on to the next one being
                        // established
                        long when = -1;
                        if (cur.getNextSendTime() <= 0) {
                            when = cur.getEstablishBeginTime() + MAX_IB_ESTABLISH_TIME;
                        } else {
                            when = cur.getNextSendTime();
                        }
                        if (when < nextSendTime)
                            nextSendTime = when;
                    }
                }
            }

        if (inboundState != null) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Processing for inbound: " + inboundState);
            synchronized (inboundState) {
                switch (inboundState.getState()) {
                  case IB_STATE_REQUEST_RECEIVED:
                    if (!expired)
                        sendCreated(inboundState);
                    break;

                  case IB_STATE_CREATED_SENT: // fallthrough
                  case IB_STATE_CONFIRMED_PARTIALLY:
                    if (expired) {
                        sendDestroy(inboundState);
                    } else if (inboundState.getNextSendTime() <= now) {
                        sendCreated(inboundState);
                    }
                    break;

                  case IB_STATE_CONFIRMED_COMPLETELY:
                    RouterIdentity remote = inboundState.getConfirmedIdentity();
                    if (remote != null) {
                        if (_context.shitlist().isShitlistedForever(remote.calculateHash())) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Dropping inbound connection from permanently shitlisted peer: " + remote.calculateHash());
                            // So next time we will not accept the con, rather than doing the whole handshake
                            _context.blocklist().add(inboundState.getSentIP());
                            inboundState.fail();
                        } else {
                            handleCompletelyEstablished(inboundState);
                        }
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("confirmed with invalid? " + inboundState);
                        inboundState.fail();
                    }
                    break;

                  case IB_STATE_FAILED:
                    break; // already removed;

                  case IB_STATE_UNKNOWN:
                    // Can't happen, always call receiveSessionRequest() before putting in map
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("hrm, state is unknown for " + inboundState);
                }
            }

            // ok, since there was something to do, we want to loop again
            nextSendTime = now;
        }
        
        return nextSendTime;
    }
    
    
    /**
     * Drive through the outbound establishment states, adjusting one of them
     * as necessary
     * @return next requested time or -1
     */
    private long handleOutbound() {
        long now = _context.clock().now();
        long nextSendTime = -1;
        OutboundEstablishState outboundState = null;
        //int admitted = 0;
        //int remaining = 0;
        //int active = 0;

            for (Iterator<OutboundEstablishState> iter = _outboundStates.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState cur = iter.next();
                if (cur.getState() == OB_STATE_CONFIRMED_COMPLETELY) {
                    // completely received
                    iter.remove();
                    outboundState = cur;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing confirmed outbound: " + cur);
                    break;
                } else if (cur.getLifetime() > MAX_OB_ESTABLISH_TIME) {
                    // took too long
                    iter.remove();
                    outboundState = cur;
                    //_context.statManager().addRateData("udp.outboundEstablishFailedState", cur.getState(), cur.getLifetime());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing expired outbound: " + cur);
                    break;
                } else {
                    if (cur.getNextSendTime() <= now) {
                        // our turn...
                        outboundState = cur;
                        // if (_log.shouldLog(Log.DEBUG))
                        //     _log.debug("Outbound wants activity: " + cur);
                        break;
                    } else {
                        // nothin to do but wait for them to send us
                        // stuff, so lets move on to the next one being
                        // established
                        long when = -1;
                        if (cur.getNextSendTime() <= 0) {
                            when = cur.getEstablishBeginTime() + MAX_OB_ESTABLISH_TIME;
                        } else {
                            when = cur.getNextSendTime();
                        }
                        if ( (nextSendTime <= 0) || (when < nextSendTime) )
                            nextSendTime = when;
                        // if (_log.shouldLog(Log.DEBUG))
                        //     _log.debug("Outbound doesn't want activity: " + cur + " (next=" + (when-now) + ")");
                    }
                }
            }
            
            //admitted = locked_admitQueued();    
            //remaining = _queuedOutbound.size();

        //if (admitted > 0)
        //    _log.log(Log.CRIT, "Admitted " + admitted + " in push with " + remaining + " remaining queued and " + active + " active");
        
        if (outboundState != null) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Processing for outbound: " + outboundState);
            synchronized (outboundState) {
                boolean expired = outboundState.getLifetime() > MAX_OB_ESTABLISH_TIME;
                switch (outboundState.getState()) {
                    case OB_STATE_UNKNOWN:
                        if (expired)
                            processExpired(outboundState);
                        else
                            sendRequest(outboundState);
                        break;

                    case OB_STATE_REQUEST_SENT:
                        // no response yet (or it was invalid), lets retry
                        long rtime = outboundState.getRequestSentTime();
                        if (expired || (rtime > 0 && rtime + OB_MESSAGE_TIMEOUT < now))
                            processExpired(outboundState);
                        else if (outboundState.getNextSendTime() <= now)
                            sendRequest(outboundState);
                        break;

                    case OB_STATE_CREATED_RECEIVED:
                        if (expired)
                            processExpired(outboundState);
                        else if (outboundState.getNextSendTime() <= now)
                            sendConfirmation(outboundState);
                        break;

                    case OB_STATE_CONFIRMED_PARTIALLY:
                        long ctime = outboundState.getConfirmedSentTime();
                        if (expired || (ctime > 0 && ctime + OB_MESSAGE_TIMEOUT < now)) {
                            sendDestroy(outboundState);
                            processExpired(outboundState);
                        } else if (outboundState.getNextSendTime() <= now) {
                            sendConfirmation(outboundState);
                        }
                        break;

                    case OB_STATE_CONFIRMED_COMPLETELY:
                        if (expired)
                            processExpired(outboundState);
                        else
                            handleCompletelyEstablished(outboundState);
                        break;

                    case OB_STATE_PENDING_INTRO:
                        long itime = outboundState.getIntroSentTime();
                        if (expired || (itime > 0 && itime + OB_MESSAGE_TIMEOUT < now))
                            processExpired(outboundState);
                        else if (outboundState.getNextSendTime() <= now)
                            handlePendingIntro(outboundState);
                        break;
                }
            }
            
            // ok, since there was something to do, we want to loop again
            nextSendTime = now;
        }
        
        return nextSendTime;
    }
    
    /**
     *  Caller should probably synch on outboundState
     */
    private void processExpired(OutboundEstablishState outboundState) {
        long nonce = outboundState.getIntroNonce();
        if (nonce >= 0) {
            // remove only if value == state
            boolean removed = _liveIntroductions.remove(Long.valueOf(nonce), outboundState);
            if (removed) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Send intro for " + outboundState.getRemoteHostId() + " timed out");
                _context.statManager().addRateData("udp.sendIntroRelayTimeout", 1, 0);
            }
        }
        // should have already been removed in handleOutbound() above
        // remove only if value == state
        boolean removed = _outboundStates.remove(outboundState.getRemoteHostId(), outboundState);
        if (outboundState.getState() != OB_STATE_CONFIRMED_COMPLETELY) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Lifetime of expired outbound establish: " + outboundState.getLifetime());
            while (true) {
                OutNetMessage msg = outboundState.getNextQueuedMessage();
                if (msg == null)
                    break;
                _transport.failed(msg, "Expired during failed establish");
            }
            String err = "Took too long to establish OB connection, state = " + outboundState.getState();
            Hash peer = outboundState.getRemoteIdentity().calculateHash();
            //_context.shitlist().shitlistRouter(peer, err, UDPTransport.STYLE);
            _transport.markUnreachable(peer);
            _transport.dropPeer(peer, false, err);
            //_context.profileManager().commErrorOccurred(peer);
        } else {
            while (true) {
                OutNetMessage msg = outboundState.getNextQueuedMessage();
                if (msg == null)
                    break;
                _transport.send(msg);
            }
        }
    }

    /**    
     * Driving thread, processing up to one step for an inbound peer and up to
     * one step for an outbound peer.  This is prodded whenever any peer's state
     * changes as well.
     *
     */    
    private class Establisher implements Runnable {
        public void run() {
            while (_alive) {
                try {
                    doPass();
                } catch (RuntimeException re) {
                    _log.log(Log.CRIT, "Error in the establisher", re);
                }
            }
            _inboundStates.clear();
            _outboundStates.clear();
            _queuedOutbound.clear();
            _liveIntroductions.clear();
        }
    }

    // Debugging
    private long _lastPrinted;
    private static final long PRINT_INTERVAL = 5*1000;
    
    private void doPass() {
        if (_log.shouldLog(Log.DEBUG) && _lastPrinted + PRINT_INTERVAL < _context.clock().now()) {
            _lastPrinted = _context.clock().now();
            int iactive = _inboundStates.size();
            int oactive = _outboundStates.size();
            if (iactive > 0 || oactive > 0) {
                int queued = _queuedOutbound.size();
                int live = _liveIntroductions.size();
                _log.debug("OB states: " + oactive + " IB states: " + iactive +
                           " OB queued: " + queued + " intros: " + live);
            }
        }
        _activity = 0;
        long now = _context.clock().now();
        long nextSendTime = -1;
        long nextSendInbound = handleInbound();
        long nextSendOutbound = handleOutbound();
        if (nextSendInbound > 0)
            nextSendTime = nextSendInbound;
        if ( (nextSendTime < 0) || (nextSendOutbound < nextSendTime) )
            nextSendTime = nextSendOutbound;

        long delay = nextSendTime - now;
        if ( (nextSendTime == -1) || (delay > 0) ) {
            if (delay > 1000)
                delay = 1000;
            try {
                synchronized (_activityLock) {
                    if (_activity > 0)
                        return;
                    if (nextSendTime == -1)
                        _activityLock.wait(1000);
                    else
                        _activityLock.wait(delay);
                }
            } catch (InterruptedException ie) {
            }
            // if (_log.shouldLog(Log.DEBUG))
            //     _log.debug("After waiting w/ nextSend=" + nextSendTime 
            //                + " and delay=" + delay + " and interrupted=" + interrupted);
        }
    }
}
