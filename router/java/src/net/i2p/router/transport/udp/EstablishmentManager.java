package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Coordinate the establishment of new sessions - both inbound and outbound.
 * This has its own thread to add packets to the packet queue when necessary,
 * as well as to drop any failed establishment attempts.
 *
 */
public class EstablishmentManager {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private PacketBuilder _builder;
    /** map of host+port (String) to InboundEstablishState */
    private Map _inboundStates;
    /** map of host+port (String) to OutboundEstablishState */
    private Map _outboundStates;
    private boolean _alive;
    private Object _activityLock;
    private int _activity;
    
    public EstablishmentManager(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(EstablishmentManager.class);
        _transport = transport;
        _builder = new PacketBuilder(ctx);
        _inboundStates = new HashMap(32);
        _outboundStates = new HashMap(32);
        _activityLock = new Object();
        _context.statManager().createRateStat("udp.inboundEstablishTime", "How long it takes for a new inbound session to be established", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.outboundEstablishTime", "How long it takes for a new outbound session to be established", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.inboundEstablishFailedState", "What state a failed inbound establishment request fails in", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.outboundEstablishFailedState", "What state a failed outbound establishment request fails in", "udp", new long[] { 60*60*1000, 24*60*60*1000 });
    }
    
    public void startup() {
        _alive = true;
        I2PThread t = new I2PThread(new Establisher(), "UDP Establisher");
        t.setDaemon(true);
        t.start();
    }
    public void shutdown() { 
        _alive = false;
        notifyActivity();
    }
    
    /**
     * Grab the active establishing state
     */
    InboundEstablishState getInboundState(InetAddress fromHost, int fromPort) {
        String from = PeerState.calculateRemoteHostString(fromHost.getAddress(), fromPort);
        synchronized (_inboundStates) {
            InboundEstablishState state = (InboundEstablishState)_inboundStates.get(from);
            if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("No inbound states for " + from + ", with remaining: " + _inboundStates);
            return state;
        }
    }
    
    OutboundEstablishState getOutboundState(InetAddress fromHost, int fromPort) {
        String from = PeerState.calculateRemoteHostString(fromHost.getAddress(), fromPort);
        synchronized (_outboundStates) {
            OutboundEstablishState state = (OutboundEstablishState)_outboundStates.get(from);
            if ( (state == null) && (_log.shouldLog(Log.DEBUG)) )
                _log.debug("No outbound states for " + from + ", with remaining: " + _outboundStates);
            return state;
        }
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
            _transport.failed(msg);
            return;
        }
        UDPAddress addr = new UDPAddress(ra);
        InetAddress remAddr = addr.getHostAddress();
        int port = addr.getPort();
        String to = PeerState.calculateRemoteHostString(remAddr.getAddress(), port);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Add outobund establish state to: " + to);
        
        synchronized (_outboundStates) {
            OutboundEstablishState state = (OutboundEstablishState)_outboundStates.get(to);
            if (state == null) {
                state = new OutboundEstablishState(_context, remAddr, port, 
                                                   msg.getTarget().getIdentity(), 
                                                   new SessionKey(addr.getIntroKey()));
                _outboundStates.put(to, state);
            }
            state.addMessage(msg);
        }
        
        notifyActivity();
    }
    
    /**
     * Got a SessionRequest (initiates an inbound establishment)
     *
     */
    void receiveSessionRequest(String from, InetAddress host, int port, UDPPacketReader reader) {
        InboundEstablishState state = null;
        synchronized (_inboundStates) {
            state = (InboundEstablishState)_inboundStates.get(from);
            if (state == null) {
                state = new InboundEstablishState(_context, host, port, _transport.getLocalPort());
                _inboundStates.put(from, state);
            }
        }
        state.receiveSessionRequest(reader.getSessionRequestReader());
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive session request from: " + state.getRemoteHostInfo());
        
        notifyActivity();
    }
    
    /** 
     * got a SessionConfirmed (should only happen as part of an inbound 
     * establishment) 
     */
    void receiveSessionConfirmed(String from, UDPPacketReader reader) {
        InboundEstablishState state = null;
        synchronized (_inboundStates) {
            state = (InboundEstablishState)_inboundStates.get(from);
        }
        if (state != null) {
            state.receiveSessionConfirmed(reader.getSessionConfirmedReader());
            notifyActivity();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive session confirmed from: " + state.getRemoteHostInfo());
        }
    }
    
    /**
     * Got a SessionCreated (in response to our outbound SessionRequest)
     *
     */
    void receiveSessionCreated(String from, UDPPacketReader reader) {
        OutboundEstablishState state = null;
        synchronized (_outboundStates) {
            state = (OutboundEstablishState)_outboundStates.get(from);
        }
        if (state != null) {
            state.receiveSessionCreated(reader.getSessionCreatedReader());
            notifyActivity();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive session created from: " + state.getRemoteHostInfo());
        }
    }

    /**
     * A data packet arrived on an outbound connection being established, which
     * means its complete (yay!).  This is a blocking call, more than I'd like...
     *
     */
    PeerState receiveData(OutboundEstablishState state) {
        state.dataReceived();
        synchronized (_outboundStates) {
            _outboundStates.remove(state.getRemoteHostInfo());
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Outbound established completely!  yay");
        PeerState peer = handleCompletelyEstablished(state);
        notifyActivity();
        return peer;
    }

    
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
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle completely established (inbound): " + state.getRemoteHostInfo());
        long now = _context.clock().now();
        RouterIdentity remote = state.getConfirmedIdentity();
        PeerState peer = new PeerState(_context);
        peer.setCurrentCipherKey(state.getCipherKey());
        peer.setCurrentMACKey(state.getMACKey());
        peer.setCurrentReceiveSecond(now - (now % 1000));
        peer.setKeyEstablishedTime(now);
        peer.setLastReceiveTime(now);
        peer.setLastSendTime(now);
        peer.setRemoteAddress(state.getSentIP(), state.getSentPort());
        peer.setRemotePeer(remote.calculateHash());
        if (true) // for now, only support direct
            peer.setRemoteRequiresIntroduction(false);
        peer.setTheyRelayToUsAs(0);
        peer.setWeRelayToThemAs(state.getSentRelayTag());
        
        _transport.addRemotePeerState(peer);
        
        _context.statManager().addRateData("udp.inboundEstablishTime", state.getLifetime(), 0);
        sendOurInfo(peer);
    }
    
    /** 
     * ok, fully received, add it to the established cons and send any
     * queued messages
     *
     */
    private PeerState handleCompletelyEstablished(OutboundEstablishState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle completely established (outbound): " + state.getRemoteHostInfo());
        long now = _context.clock().now();
        RouterIdentity remote = state.getRemoteIdentity();
        PeerState peer = new PeerState(_context);
        peer.setCurrentCipherKey(state.getCipherKey());
        peer.setCurrentMACKey(state.getMACKey());
        peer.setCurrentReceiveSecond(now - (now % 1000));
        peer.setKeyEstablishedTime(now);
        peer.setLastReceiveTime(now);
        peer.setLastSendTime(now);
        peer.setRemoteAddress(state.getSentIP(), state.getSentPort());
        peer.setRemotePeer(remote.calculateHash());
        if (true) // for now, only support direct
            peer.setRemoteRequiresIntroduction(false);
        peer.setTheyRelayToUsAs(state.getReceivedRelayTag());
        peer.setWeRelayToThemAs(0);
        
        _transport.addRemotePeerState(peer);
        
        _context.statManager().addRateData("udp.outboundEstablishTime", state.getLifetime(), 0);
        sendOurInfo(peer);
        
        while (true) {
            OutNetMessage msg = state.getNextQueuedMessage();
            if (msg == null)
                break;
            _transport.send(msg);
        }
        return peer;
    }
    
    private void sendOurInfo(PeerState peer) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Publishing to the peer after confirm: " + peer);
        
        DatabaseStoreMessage m = new DatabaseStoreMessage(_context);
        m.setKey(_context.routerHash());
        m.setRouterInfo(_context.router().getRouterInfo());
        m.setMessageExpiration(_context.clock().now() + 10*1000);
        _transport.send(m, peer);
    }
    
    private void sendCreated(InboundEstablishState state) {
        long now = _context.clock().now();
        if (true) // for now, don't offer to relay
            state.setSentRelayTag(0);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send created to: " + state.getRemoteHostInfo());
        
        state.generateSessionKey();
        _transport.send(_builder.buildSessionCreatedPacket(state, _transport.getExternalPort(), _transport.getIntroKey()));
        // if they haven't advanced to sending us confirmed packets in 5s,
        // repeat
        state.setNextSendTime(now + 5*1000);
    }

    private void sendRequest(OutboundEstablishState state) {
        long now = _context.clock().now();
        state.prepareSessionRequest();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send request to: " + state.getRemoteHostInfo());
        _transport.send(_builder.buildSessionRequestPacket(state));
        state.requestSent();
    }
    
    private void sendConfirmation(OutboundEstablishState state) {
        long now = _context.clock().now();
        boolean valid = state.validateSessionCreated();
        if (!valid) // validate clears fields on failure
            return;
        
        // gives us the opportunity to "detect" our external addr
        _transport.externalAddressReceived(state.getReceivedIP(), state.getReceivedPort());
        
        // signs if we havent signed yet
        state.prepareSessionConfirmed();
        
        UDPPacket packets[] = _builder.buildSessionConfirmedPackets(state, _context.router().getRouterInfo().getIdentity());
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send confirm to: " + state.getRemoteHostInfo());
        
        for (int i = 0; i < packets.length; i++)
            _transport.send(packets[i]);
        
        state.confirmedPacketsSent();
    }
    
    
    /**
     * Drive through the inbound establishment states, adjusting one of them
     * as necessary
     */
    private long handleInbound() {
        long now = _context.clock().now();
        long nextSendTime = -1;
        InboundEstablishState inboundState = null;
        synchronized (_inboundStates) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("# inbound states: " + _inboundStates.size());
            for (Iterator iter = _inboundStates.values().iterator(); iter.hasNext(); ) {
                InboundEstablishState cur = (InboundEstablishState)iter.next();
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
                } else {
                    if (cur.getNextSendTime() <= now) {
                        // our turn...
                        inboundState = cur;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Processing inbound that wanted activity");
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
                    if (inboundState.getConfirmedIdentity() != null) {
                        handleCompletelyEstablished(inboundState);
                        break;
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("why are we confirmed with no identity? " + inboundState);
                        break;
                    }
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
     */
    private long handleOutbound() {
        long now = _context.clock().now();
        long nextSendTime = -1;
        OutboundEstablishState outboundState = null;
        synchronized (_outboundStates) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("# outbound states: " + _outboundStates.size());
            for (Iterator iter = _outboundStates.values().iterator(); iter.hasNext(); ) {
                OutboundEstablishState cur = (OutboundEstablishState)iter.next();
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
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound wants activity: " + cur);
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
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound doesn't want activity: " + cur + " (next=" + (when-now) + ")");
                    }
                }
            }
        }

        if (outboundState != null) {
            if (outboundState.getLifetime() > MAX_ESTABLISH_TIME) {
                if (outboundState.getState() != OutboundEstablishState.STATE_CONFIRMED_COMPLETELY) {
                    while (true) {
                        OutNetMessage msg = outboundState.getNextQueuedMessage();
                        if (msg == null)
                            break;
                        _transport.failed(msg);
                    }
                    _context.shitlist().shitlistRouter(outboundState.getRemoteIdentity().calculateHash(), "Unable to establish");
                } else {
                    while (true) {
                        OutNetMessage msg = outboundState.getNextQueuedMessage();
                        if (msg == null)
                            break;
                        _transport.send(msg);
                    }
                }
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

    /**    
     * Driving thread, processing up to one step for an inbound peer and up to
     * one step for an outbound peer.  This is prodded whenever any peer's state
     * changes as well.
     *
     */    
    private class Establisher implements Runnable {
        public void run() {
            while (_alive) {
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
                    boolean interrupted = false;
                    try {
                        synchronized (_activityLock) {
                            if (_activity > 0)
                                continue;
                            if (nextSendTime == -1)
                                _activityLock.wait();
                            else
                                _activityLock.wait(delay);
                        }
                    } catch (InterruptedException ie) {
                        interrupted = true;
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("After waiting w/ nextSend=" + nextSendTime 
                                   + " and delay=" + delay + " and interrupted=" + interrupted);
                }
            }
        }
    }
}
