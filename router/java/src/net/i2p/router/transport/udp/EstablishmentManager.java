package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

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
    private boolean _alive;
    private final Object _activityLock;
    private int _activity;
    
    /** max outbound in progress */
    private static final int DEFAULT_MAX_CONCURRENT_ESTABLISH = 20;
    private static final String PROP_MAX_CONCURRENT_ESTABLISH = "i2np.udp.maxConcurrentEstablish";

    /** max pending outbound connections (waiting because we are at MAX_CONCURRENT_ESTABLISH) */
    private static final int MAX_QUEUED_OUTBOUND = 50;

    /** max queued msgs per peer while the peer connection is queued */
    private static final int MAX_QUEUED_PER_PEER = 3;
  
    
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
        _context.statManager().createRateStat("udp.inboundEstablishFailedState", "What state a failed inbound establishment request fails in", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.outboundEstablishFailedState", "What state a failed outbound establishment request fails in", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendIntroRelayRequest", "How often we send a relay request to reach a peer", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendIntroRelayTimeout", "How often a relay request times out before getting a response (due to the target or intro peer being offline)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receiveIntroRelayResponse", "How long it took to receive a relay response", "udp", UDPTransport.RATES);
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
     */
    InboundEstablishState getInboundState(RemoteHostId from) {
            InboundEstablishState state = _inboundStates.get(from);
            // if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
            //     _log.debug("No inbound states for " + from + ", with remaining: " + _inboundStates);
            return state;
    }
    
    OutboundEstablishState getOutboundState(RemoteHostId from) {
            OutboundEstablishState state = _outboundStates.get(from);
            // if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
            //     _log.debug("No outbound states for " + from + ", with remaining: " + _outboundStates);
            return state;
    }
    
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
        if ( (remAddr != null) && (port > 0) ) {
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
                    if (_queuedOutbound.size() > MAX_QUEUED_OUTBOUND) {
                        rejected = true;
                    } else {
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
                    else
                        SimpleScheduler.getInstance().addEvent(new Expire(to, state), 10*1000);
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
            msg.timestamp("too many deferred establishers: " + deferred);
        else if (state != null)
            msg.timestamp("establish state already waiting " + state.getLifetime());
        notifyActivity();
    }
    
    private class Expire implements SimpleTimer.TimedEvent {
        private RemoteHostId _to;
        private OutboundEstablishState _state;
        public Expire(RemoteHostId to, OutboundEstablishState state) { 
            _to = to;
            _state = state; 
        }
        public void timeReached() {
            // remove only if value == state
            boolean removed = _outboundStates.remove(_to, _state);
            if (removed) {
                _context.statManager().addRateData("udp.outboundEstablishFailedState", _state.getState(), _state.getLifetime());
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Timing out expired outbound: " + _state);
                processExpired(_state);
            }
        }
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
        if (!_transport.isValid(from.getIP()))
            return;
        
        int maxInbound = getMaxInboundEstablishers();
        
        boolean isNew = false;

            if (_inboundStates.size() >= maxInbound)
                return; // drop the packet
            
            InboundEstablishState state = _inboundStates.get(from);
            if (state == null) {
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
                    _log.info("Received session request from " + from + ", sending relay tag " + tag);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Received session request, but our status is " + _transport.getReachabilityStatus());
            }
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session request from: " + state.getRemoteHostId().toString());
        
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
        _transport.dropPeer(peer, false, "received destroy message");
    }

    /**
     * Got a SessionDestroy - maybe after an inbound establish
     * @since 0.8.1
     */
    void receiveSessionDestroy(RemoteHostId from) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session destroy (IB) from: " + from);
        InboundEstablishState state = _inboundStates.remove(from);
        if (state != null) {
            Hash peer = state.getConfirmedIdentity().calculateHash();
            if (peer != null)
                _transport.dropPeer(peer, false, "received destroy message");
        }
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
                
                //admitted = locked_admitQueued();
            //remaining = _queuedOutbound.size();

        //if (admitted > 0)
        //    _log.log(Log.CRIT, "Admitted " + admitted + " with " + remaining + " remaining queued and " + active + " active");
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Outbound established completely!  yay: " + state);
        PeerState peer = handleCompletelyEstablished(state);
        notifyActivity();
        return peer;
    }

/********
    private int locked_admitQueued() {
        int admitted = 0;
        while ( (!_queuedOutbound.isEmpty()) && (_outboundStates.size() < getMaxConcurrentEstablish()) ) {
            // ok, active shrunk, lets let some queued in.  duplicate the synchronized 
            // section from the add(

            RemoteHostId to = (RemoteHostId)_queuedOutbound.keySet().iterator().next();
            List queued = (List)_queuedOutbound.remove(to);

            if (queued.isEmpty())
                continue;
            
            OutNetMessage msg = (OutNetMessage)queued.get(0);
            RouterAddress ra = msg.getTarget().getTargetAddress(_transport.getStyle());
            if (ra == null) {
                for (int i = 0; i < queued.size(); i++) 
                    _transport.failed((OutNetMessage)queued.get(i), "Cannot admit to the queue, as it has no address");
                continue;
            }
            UDPAddress addr = new UDPAddress(ra);
            InetAddress remAddr = addr.getHostAddress();
            int port = addr.getPort();

            OutboundEstablishState qstate = new OutboundEstablishState(_context, remAddr, port, 
                                               msg.getTarget().getIdentity(), 
                                               new SessionKey(addr.getIntroKey()), addr);
            _outboundStates.put(to, qstate);
            SimpleScheduler.getInstance().addEvent(new Expire(to, qstate), 10*1000);

            for (int i = 0; i < queued.size(); i++) {
                OutNetMessage m = (OutNetMessage)queued.get(i);
                m.timestamp("no longer deferred... establishing");
                qstate.addMessage(m);
            }
            admitted++;
        }
        return admitted;
    }
*******/
    
    private void notifyActivity() {
        synchronized (_activityLock) { 
            _activity++;
            _activityLock.notifyAll(); 
        }
    }
    
    /** kill any inbound or outbound that takes more than 30s */
    private static final int MAX_ESTABLISH_TIME = 30*1000;
    
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
                       + " - " + peer.getRemotePeer().toBase64());
        
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
        dsm.setMessageExpiration(_context.clock().now()+10*1000);
        dsm.setMessageId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
        _transport.send(dsm, peer);
        SimpleScheduler.getInstance().addEvent(new PublishToNewInbound(peer), 0);
    }
    private class PublishToNewInbound implements SimpleTimer.TimedEvent {
        private PeerState _peer;
        public PublishToNewInbound(PeerState peer) { _peer = peer; }
        public void timeReached() {
            Hash peer = _peer.getRemotePeer();
            if ((peer != null) && (!_context.shitlist().isShitlisted(peer)) && (!_transport.isUnreachable(peer))) {
                // ok, we are fine with them, send them our latest info
                if (_log.shouldLog(Log.INFO))
                    _log.info("Publishing to the peer after confirm plus delay (without shitlist): " + peer.toBase64());
                sendOurInfo(_peer, true);
            } else {
                // nuh uh.  fuck 'em.
                if (_log.shouldLog(Log.WARN))
                    _log.warn("NOT publishing to the peer after confirm plus delay (WITH shitlist): " + (peer != null ? peer.toBase64() : "unknown"));
            }
            _peer = null;
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
                       + " - " + peer.getRemotePeer().toBase64());
        
        
        _transport.addRemotePeerState(peer);
        _transport.setIP(remote.calculateHash(), state.getSentIP());
        
        _context.statManager().addRateData("udp.outboundEstablishTime", state.getLifetime(), 0);
        sendOurInfo(peer, false);
        
        int i = 0;
        while (true) {
            OutNetMessage msg = state.getNextQueuedMessage();
            if (msg == null)
                break;
            if (now - Router.CLOCK_FUDGE_FACTOR > msg.getExpiration()) {
                msg.timestamp("took too long but established...");
                _transport.failed(msg, "Took too long to establish, but it was established");
            } else {
                msg.timestamp("session fully established and sent " + i);
                _transport.send(msg);
            }
            i++;
        }
        return peer;
    }
    
    private void sendOurInfo(PeerState peer, boolean isInbound) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Publishing to the peer after confirm: " + 
                      (isInbound ? " inbound con from " + peer : "outbound con to " + peer));
        
        DatabaseStoreMessage m = new DatabaseStoreMessage(_context);
        m.setEntry(_context.router().getRouterInfo());
        m.setMessageExpiration(_context.clock().now() + 10*1000);
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
        // if they haven't advanced to sending us confirmed packets in 1s,
        // repeat
        state.setNextSendTime(now + 1000);
    }

    private void sendRequest(OutboundEstablishState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send request to: " + state.getRemoteHostId().toString());
        UDPPacket packet = _builder.buildSessionRequestPacket(state);
        if (packet != null) {
            _transport.send(packet);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to build a session request packet for " + state.getRemoteHostId());
        }
        state.requestSent();
    }
    
    private static final long MAX_NONCE = 0xFFFFFFFFl;
    /** if we don't get a relayResponse in 3 seconds, try again */
    private static final int INTRO_ATTEMPT_TIMEOUT = 3*1000;
    
    private void handlePendingIntro(OutboundEstablishState state) {
        long nonce = _context.random().nextLong(MAX_NONCE);
        while (true) {
                OutboundEstablishState old = _liveIntroductions.putIfAbsent(Long.valueOf(nonce), state);
                if (old != null) {
                    nonce = _context.random().nextLong(MAX_NONCE);
                } else {
                    break;
                }
        }
        SimpleScheduler.getInstance().addEvent(new FailIntroduction(state, nonce), INTRO_ATTEMPT_TIMEOUT);
        state.setIntroNonce(nonce);
        _context.statManager().addRateData("udp.sendIntroRelayRequest", 1, 0);
        UDPPacket requests[] = _builder.buildRelayRequest(_transport, state, _transport.getIntroKey());
        for (int i = 0; i < requests.length; i++) {
            if (requests[i] != null)
                _transport.send(requests[i]);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send intro for " + state.getRemoteHostId().toString() + " with our intro key as " + _transport.getIntroKey().toBase64());
        state.introSent();
    }
    private class FailIntroduction implements SimpleTimer.TimedEvent {
        private long _nonce;
        private OutboundEstablishState _state;
        public FailIntroduction(OutboundEstablishState state, long nonce) {
            _nonce = nonce;
            _state = state;
        }
        public void timeReached() {
            // remove only if value equal to state
            boolean removed = _liveIntroductions.remove(Long.valueOf(_nonce), _state);
            if (removed) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Send intro for " + _state.getRemoteHostId().toString() + " timed out");
                _context.statManager().addRateData("udp.sendIntroRelayTimeout", 1, 0);
                notifyActivity();
            }
        }
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
                _log.warn("Introducer for " + state + " (" + bob + ") sent us an invalid IP for our targer: " + Base64.encode(ip), uhe);
            // these two cause this peer to requeue for a new intro peer
            state.introductionFailed();
            notifyActivity();
            return;
        }
        _context.statManager().addRateData("udp.receiveIntroRelayResponse", state.getLifetime(), 0);
        int port = reader.getRelayResponseReader().readCharliePort();
        if (_log.shouldLog(Log.INFO))
            _log.info("Received relay intro for " + state.getRemoteIdentity().calculateHash().toBase64() + " - they are on " 
                      + addr.toString() + ":" + port + " (according to " + bob.toString(true) + ")");
        RemoteHostId oldId = state.getRemoteHostId();
        state.introduced(addr, ip, port);
        _outboundStates.remove(oldId);
        _outboundStates.put(state.getRemoteHostId(), state);
        notifyActivity();
    }
    
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
            _log.debug("Send confirm to: " + state.getRemoteHostId().toString());
        
        for (int i = 0; i < packets.length; i++)
            _transport.send(packets[i]);
        
        state.confirmedPacketsSent();
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

            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("# inbound states: " + _inboundStates.size());
            for (Iterator<InboundEstablishState> iter = _inboundStates.values().iterator(); iter.hasNext(); ) {
                InboundEstablishState cur = iter.next();
                if (cur.getState() == InboundEstablishState.STATE_CONFIRMED_COMPLETELY) {
                    // completely received (though the signature may be invalid)
                    iter.remove();
                    inboundState = cur;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing completely confirmed inbound state");
                    break;
                } else if (cur.getLifetime() > MAX_ESTABLISH_TIME) {
                    // took too long, fuck 'em
                    iter.remove();
                    _context.statManager().addRateData("udp.inboundEstablishFailedState", cur.getState(), cur.getLifetime());
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing expired inbound state");
                } else if (cur.getState() == InboundEstablishState.STATE_FAILED) {
                    iter.remove();
                    _context.statManager().addRateData("udp.inboundEstablishFailedState", cur.getState(), cur.getLifetime());
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
                            when = cur.getEstablishBeginTime() + MAX_ESTABLISH_TIME;
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
            switch (inboundState.getState()) {
                case InboundEstablishState.STATE_REQUEST_RECEIVED:
                    sendCreated(inboundState);
                    break;
                case InboundEstablishState.STATE_CREATED_SENT: // fallthrough
                case InboundEstablishState.STATE_CONFIRMED_PARTIALLY:
                    // if its been 5s since we sent the SessionCreated, resend
                    if (inboundState.getNextSendTime() <= now)
                        sendCreated(inboundState);
                    break;
                case InboundEstablishState.STATE_CONFIRMED_COMPLETELY:
                    RouterIdentity remote = inboundState.getConfirmedIdentity();
                    if (remote != null) {
                        if (_context.shitlist().isShitlistedForever(remote.calculateHash())) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Dropping inbound connection from permanently shitlisted peer: " + remote.calculateHash().toBase64());
                            // So next time we will not accept the con, rather than doing the whole handshake
                            _context.blocklist().add(inboundState.getSentIP());
                            inboundState.fail();
                            break;
                        }
                        handleCompletelyEstablished(inboundState);
                        break;
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("confirmed with invalid? " + inboundState);
                        inboundState.fail();
                        break;
                    }
                case InboundEstablishState.STATE_FAILED:
                    break; // already removed;
                case InboundEstablishState.STATE_UNKNOWN: // fallthrough
                default:
                    // wtf
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("hrm, state is unknown for " + inboundState);
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

            //active = _outboundStates.size();
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("# outbound states: " + _outboundStates.size());
            for (Iterator<OutboundEstablishState> iter = _outboundStates.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState cur = iter.next();
                if (cur == null) continue;
                if (cur.getState() == OutboundEstablishState.STATE_CONFIRMED_COMPLETELY) {
                    // completely received
                    iter.remove();
                    outboundState = cur;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing confirmed outbound: " + cur);
                    break;
                } else if (cur.getLifetime() > MAX_ESTABLISH_TIME) {
                    // took too long, fuck 'em
                    iter.remove();
                    outboundState = cur;
                    _context.statManager().addRateData("udp.outboundEstablishFailedState", cur.getState(), cur.getLifetime());
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
                            when = cur.getEstablishBeginTime() + MAX_ESTABLISH_TIME;
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
            if (outboundState.getLifetime() > MAX_ESTABLISH_TIME) {
                processExpired(outboundState);
            } else {
                switch (outboundState.getState()) {
                    case OutboundEstablishState.STATE_UNKNOWN:
                        sendRequest(outboundState);
                        break;
                    case OutboundEstablishState.STATE_REQUEST_SENT:
                        // no response yet (or it was invalid), lets retry
                        if (outboundState.getNextSendTime() <= now)
                            sendRequest(outboundState);
                        break;
                    case OutboundEstablishState.STATE_CREATED_RECEIVED: // fallthrough
                    case OutboundEstablishState.STATE_CONFIRMED_PARTIALLY:
                        if (outboundState.getNextSendTime() <= now)
                            sendConfirmation(outboundState);
                        break;
                    case OutboundEstablishState.STATE_CONFIRMED_COMPLETELY:
                        handleCompletelyEstablished(outboundState);
                        break;
                    case OutboundEstablishState.STATE_PENDING_INTRO:
                        handlePendingIntro(outboundState);
                        break;
                    default:
                        // wtf
                }
            }
            
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Since something happened outbound, next=now");
            // ok, since there was something to do, we want to loop again
            nextSendTime = now;
        } else {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Nothing happened outbound, next is in " + (nextSendTime-now));
        }
        
        return nextSendTime;
    }
    
    private void processExpired(OutboundEstablishState outboundState) {
        if (outboundState.getState() != OutboundEstablishState.STATE_CONFIRMED_COMPLETELY) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Lifetime of expired outbound establish: " + outboundState.getLifetime());
            while (true) {
                OutNetMessage msg = outboundState.getNextQueuedMessage();
                if (msg == null)
                    break;
                _transport.failed(msg, "Expired during failed establish");
            }
            String err = null;
            switch (outboundState.getState()) {
                case OutboundEstablishState.STATE_CONFIRMED_PARTIALLY:
                    err = "Took too long to establish remote connection (confirmed partially)";
                    break;
                case OutboundEstablishState.STATE_CREATED_RECEIVED:
                    err = "Took too long to establish remote connection (created received)";
                    break;
                case OutboundEstablishState.STATE_REQUEST_SENT:
                    err = "Took too long to establish remote connection (request sent)";
                    break;
                case OutboundEstablishState.STATE_PENDING_INTRO:
                    err = "Took too long to establish remote connection (intro failed)";
                    break;
                case OutboundEstablishState.STATE_UNKNOWN: // fallthrough
                default:
                    err = "Took too long to establish remote connection (unknown state)";
            }

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
                } catch (OutOfMemoryError oom) {
                    throw oom;
                } catch (RuntimeException re) {
                    _log.log(Log.CRIT, "Error in the establisher", re);
                }
            }
        }
    }
    
    private void doPass() {
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
