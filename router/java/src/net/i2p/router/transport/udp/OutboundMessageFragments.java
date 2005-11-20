package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Coordinate the outbound fragments and select the next one to be built.
 * This pool contains messages we are actively trying to send, essentially 
 * doing a round robin across each message to send one fragment, as implemented
 * in {@link #getNextVolley()}.  This also honors per-peer throttling, taking 
 * note of each peer's allocations.  If a message has each of its fragments
 * sent more than a certain number of times, it is failed out.  In addition, 
 * this instance also receives notification of message ACKs from the 
 * {@link InboundMessageFragments}, signaling that we can stop sending a 
 * message.
 * 
 */
public class OutboundMessageFragments {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private ActiveThrottle _throttle;
    /** OutboundMessageState for messages being sent */
    private List _activeMessages;
    private boolean _alive;
    /** which message should we build the next packet out of? */
    private int _nextPacketMessage;
    private PacketBuilder _builder;
    /** if we can handle more messages explicitly, set this to true */
    private boolean _allowExcess;
    private volatile long _packetsRetransmitted;
    /** 
     * Map of peer to OutboundMessageState for messages being retransmitted, to
     * keep bad peers from bursting too much due to congestion/outage.  This
     * should only be accessed when holding the lock on _activeMessages.
     */
    private Map _retransmitters;
    
    private static final int MAX_ACTIVE = 64;
    // don't send a packet more than 10 times
    static final int MAX_VOLLEYS = 10;
    
    public OutboundMessageFragments(RouterContext ctx, UDPTransport transport, ActiveThrottle throttle) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundMessageFragments.class);
        _transport = transport;
        _throttle = throttle;
        _activeMessages = new ArrayList(MAX_ACTIVE);
        _retransmitters = new HashMap(MAX_ACTIVE);
        _nextPacketMessage = 0;
        _builder = new PacketBuilder(ctx, transport);
        _alive = true;
        _allowExcess = false;
        _context.statManager().createRateStat("udp.sendVolleyTime", "Long it takes to send a full volley", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendConfirmTime", "How long it takes to send a message and get the ACK", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendConfirmFragments", "How many fragments are included in a fully ACKed message", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendConfirmVolley", "How many times did fragments need to be sent before ACK", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendFailed", "How many sends a failed message was pushed", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendAggressiveFailed", "How many volleys was a packet sent before we gave up", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.outboundActiveCount", "How many messages are in the active pool when a new one is added", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendRejected", "What volley are we on when the peer was throttled (time == message lifetime)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.partialACKReceived", "How many fragments were partially ACKed (time == message lifetime)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendSparse", "How many fragments were partially ACKed and hence not resent (time == message lifetime)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendPiggyback", "How many acks were piggybacked on a data packet (time == message lifetime)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendPiggybackPartial", "How many partial acks were piggybacked on a data packet (time == message lifetime)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.activeDelay", "How often we wait blocking on the active queue", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.packetsRetransmitted", "How many packets have been retransmitted (lifetime) when a burst of packets are retransmitted (period == packets transmitted, lifetime)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.peerPacketsRetransmitted", "How many packets have been retransmitted to the peer (lifetime) when a burst of packets are retransmitted (period == packets transmitted, lifetime)", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.blockedRetransmissions", "How packets have been transmitted to the peer when we blocked a retransmission to them?", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }
    
    public void startup() { _alive = true; }
    public void shutdown() {
        _alive = false;
        synchronized (_activeMessages) {
            _activeMessages.notifyAll();
        }
    }
    
    /**
     * Block until we allow more messages to be admitted to the active
     * pool.  This is called by the {@link OutboundRefiller}
     *
     * @return true if more messages are allowed
     */
    public boolean waitForMoreAllowed() {
        // test without choking.  
        // perhaps this should check the lifetime of the first activeMessage?
        if (false) return true; 
        
        long start = _context.clock().now();
        int numActive = 0;
        int maxActive = Math.max(_transport.countActivePeers(), MAX_ACTIVE);
        while (_alive) {
            finishMessages();
            try {
                synchronized (_activeMessages) {
                    numActive = _activeMessages.size();
                    if (!_alive)
                        return false;
                    else if (numActive < maxActive)
                        return true;
                    else if (_allowExcess)
                        return true;
                    else
                        _activeMessages.wait(1000);
                }
                _context.statManager().addRateData("udp.activeDelay", numActive, _context.clock().now() - start);
            } catch (InterruptedException ie) {}
        }
        return false;
    }
    
    /**
     * Add a new message to the active pool
     *
     */
    public void add(OutNetMessage msg) {
        I2NPMessage msgBody = msg.getMessage();
        RouterInfo target = msg.getTarget();
        if ( (msgBody == null) || (target == null) ) {
            synchronized (_activeMessages) {
                _activeMessages.notifyAll();
            }
            return;
        }
        
        OutboundMessageState state = new OutboundMessageState(_context);
        boolean ok = state.initialize(msg, msgBody);
        state.setPeer(_transport.getPeerState(target.getIdentity().calculateHash()));
        finishMessages();
        int active = 0;
        synchronized (_activeMessages) {
            if (ok)
                _activeMessages.add(state);
            active = _activeMessages.size();
            _activeMessages.notifyAll();
        }
        msg.timestamp("made active along with: " + active);
        _context.statManager().addRateData("udp.outboundActiveCount", active, 0);
    }
    
    /** 
     * short circuit the OutNetMessage, letting us send the establish 
     * complete message reliably
     */
    public void add(OutboundMessageState state) {
        synchronized (_activeMessages) {
            _activeMessages.add(state);
            _activeMessages.notifyAll();
        }
    }

    /**
     * Remove any expired or complete messages
     */
    private void finishMessages() {
        synchronized (_activeMessages) {
            for (int i = 0; i < _activeMessages.size(); i++) {
                OutboundMessageState state = (OutboundMessageState)_activeMessages.get(i);
                PeerState peer = state.getPeer();
                if (state.isComplete()) {
                    _activeMessages.remove(i);
                    locked_removeRetransmitter(state);
                    _transport.succeeded(state.getMessage());
                    if ( (peer != null) && (peer.getSendWindowBytesRemaining() > 0) )
                        _throttle.unchoke(peer.getRemotePeer());
                    state.releaseResources();
                    if (i < _nextPacketMessage) {
                        _nextPacketMessage--; 
                        if (_nextPacketMessage < 0)
                            _nextPacketMessage = 0;
                    }
                    if (state.getMessage() != null)
                        state.getMessage().timestamp("sending complete");
                    i--;
                } else if (state.isExpired()) {
                    _activeMessages.remove(i);
                    locked_removeRetransmitter(state);
                    _context.statManager().addRateData("udp.sendFailed", state.getPushCount(), state.getLifetime());

                    if (state.getMessage() != null) {
                        state.getMessage().timestamp("expired in the active pool");
                        _transport.failed(state);
                    } else {
                        // it can not have an OutNetMessage if the source is the
                        // final after establishment message
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Unable to send an expired direct message: " + state);
                    }
                    if ( (peer != null) && (peer.getSendWindowBytesRemaining() > 0) )
                        _throttle.unchoke(peer.getRemotePeer());
                    state.releaseResources();
                    if (i < _nextPacketMessage) {
                        _nextPacketMessage--; 
                        if (_nextPacketMessage < 0)
                            _nextPacketMessage = 0;
                    }
                    i--;
                } else if (state.getPushCount() > MAX_VOLLEYS) {
                    _activeMessages.remove(i);
                    locked_removeRetransmitter(state);
                    _context.statManager().addRateData("udp.sendAggressiveFailed", state.getPushCount(), state.getLifetime());
                    //if (state.getPeer() != null)
                    //    state.getPeer().congestionOccurred();

                    if (state.getMessage() != null) {
                        state.getMessage().timestamp("too many sends");
                        _transport.failed(state);
                    } else {
                        // it can not have an OutNetMessage if the source is the
                        // final after establishment message
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Unable to send a direct message after too many volleys: " + state);
                    }
                    if ( (peer != null) && (peer.getSendWindowBytesRemaining() > 0) )
                        _throttle.unchoke(peer.getRemotePeer());
                    state.releaseResources();
                    if (i < _nextPacketMessage) {
                        _nextPacketMessage--; 
                        if (_nextPacketMessage < 0)
                            _nextPacketMessage = 0;
                    }
                    i--;
                } // end (pushCount > maxVolleys)
            } // end iterating over active
            _activeMessages.notifyAll();
        } // end synchronized
    }
    
    /**
     * Remove the block on retransmissions to the peer if and only if the given
     * message is the current "retransmitter" for it.
     *
     */
    private void locked_removeRetransmitter(OutboundMessageState state) {
        PeerState curPeer = state.getPeer();
        if (curPeer == null) {
            for (Iterator iter = _retransmitters.keySet().iterator(); iter.hasNext(); ) {
                PeerState cpeer = (PeerState)iter.next();
                OutboundMessageState cstate = (OutboundMessageState)_retransmitters.get(cpeer);
                if (cstate == state) {
                    iter.remove();
                    break;
                }
            }
        } else {
            OutboundMessageState remState = (OutboundMessageState)_retransmitters.get(curPeer);
            if (remState == state)
                _retransmitters.remove(curPeer);
        }
    }
    
    private static final long SECOND_MASK = 1023l;

    /**
     * Fetch all the packets for a message volley, blocking until there is a 
     * message which can be fully transmitted (or the transport is shut down).
     * The returned array may be sparse, with null packets taking the place of
     * already ACKed fragments.
     *
     */
    public UDPPacket[] getNextVolley() {
        PeerState peer = null;
        OutboundMessageState state = null;
        while (_alive && (state == null) ) {
            long now = _context.clock().now();
            long nextSend = -1;
            finishMessages();
            try {
                synchronized (_activeMessages) {
                    for (int i = 0; i < _activeMessages.size(); i++) {
                        int cur = (i + _nextPacketMessage) % _activeMessages.size();
                        state = (OutboundMessageState)_activeMessages.get(cur);
                        peer = state.getPeer(); // known if this is immediately after establish
                        if (peer == null)
                            peer = _transport.getPeerState(state.getMessage().getTarget().getIdentity().calculateHash());

                        if ((peer != null) && locked_shouldSend(state, peer)) {
                            // for fairness, we move on in a round robin
                            _nextPacketMessage = i + 1;
                            break;
                        } else {
                            if (peer == null) {
                                // peer disconnected
                                _activeMessages.remove(cur);
                                locked_removeRetransmitter(state);
                                if (state.getMessage() != null)
                                    state.getMessage().timestamp("peer disconnected");
                                _transport.failed(state);
                                if (_log.shouldLog(Log.WARN))
                                    _log.warn("Peer disconnected for " + state);
                                if ( (peer != null) && (peer.getSendWindowBytesRemaining() > 0) )
                                    _throttle.unchoke(peer.getRemotePeer());
                                state.releaseResources();
                                i--;
                            }

                            long time = state.getNextSendTime();
                            if ( (nextSend < 0) || (time < nextSend) )
                                nextSend = time;
                            state = null;
                            peer = null;
                        }
                    } // end of the for(activeMessages)

                    if (state == null) {
                        if (nextSend <= 0) {
                            _activeMessages.notifyAll();
                            _activeMessages.wait(1000);
                        } else {
                            // none of the packets were eligible for sending
                            long delay = nextSend - now;
                            if (delay <= 0)
                                delay = 10;
                            if (delay > 1000) 
                                delay = 1000;
                            _allowExcess = true;
                            _activeMessages.notifyAll();
                            _activeMessages.wait(delay);
                        }
                    } else {
                        _activeMessages.notifyAll();
                    }
                    _allowExcess = false;
                } // end of the synchronized block
            } catch (InterruptedException ie) {}
        } // end of the while (alive && !found)

        UDPPacket packets[] = preparePackets(state, peer);
        if ( (state != null) && (state.getMessage() != null) ) {
            int valid = 0;
            for (int i = 0; packets != null && i < packets.length ; i++)
                if (packets[i] != null)
                    valid++;
            state.getMessage().timestamp("sending a volley of " + valid
                                         + " lastReceived: " 
                                         + (_context.clock().now() - peer.getLastReceiveTime())
                                         + " lastSentFully: " 
                                         + (_context.clock().now() - peer.getLastSendFullyTime()));
        }
        return packets;
    }
    
    /**
     * If set to true, we should throttle retransmissions of all but the first message in
     * flight to a peer.  If set to false, we will only throttle the initial flight of a
     * message to a peer while a retransmission is going on.
     */
    private static final boolean THROTTLE_RESENDS = true;
    /** 
     * if true, throttle the initial volley of a message if there is a resend in progress.
     * if false, always send the first volley, regardless of retransmissions (but keeping in
     * mind bw/cwin throttle, etc)
     *
     */
    private static final boolean THROTTLE_INITIAL_SEND = false;
    
    private boolean locked_shouldSend(OutboundMessageState state, PeerState peer) {
        long now = _context.clock().now();
        if (state.getNextSendTime() <= now) {
            if (!state.isFragmented()) {
                state.fragment(fragmentSize(peer.getMTU()));
                if (state.getMessage() != null)
                    state.getMessage().timestamp("fragment into " + state.getFragmentCount());

                if (_log.shouldLog(Log.INFO))
                    _log.info("Fragmenting " + state);
            }

            OutboundMessageState curRetransMsg = (OutboundMessageState)_retransmitters.get(peer);
            if ( (curRetransMsg != null) && ( (curRetransMsg.isExpired() || curRetransMsg.isComplete()) ) ) {
                _retransmitters.remove(peer);
                curRetransMsg = null;
            }
            
            if ( (curRetransMsg != null) && (curRetransMsg != state) ) {
                // choke it, since there's already another message retransmitting to this
                // peer.
                _context.statManager().addRateData("udp.blockedRetransmissions", peer.getPacketsRetransmitted(), peer.getPacketsTransmitted());
                if ( (state.getMaxSends() <= 0) && (!THROTTLE_INITIAL_SEND) ) {
                    if (state.getMessage() != null)
                        state.getMessage().timestamp("another message is retransmitting, but we want to send our first volley...");
                } else if ( (state.getMaxSends() <= 0) || (THROTTLE_RESENDS) ) {
                    if (state.getMessage() != null)
                        state.getMessage().timestamp("choked, with another message retransmitting");
                    return false;
                } else {
                    if (state.getMessage() != null)
                        state.getMessage().timestamp("another message is retransmitting, but since we've already begun sending...");                    
                }
            }

            int size = state.getUnackedSize();
            if (peer.allocateSendingBytes(size)) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Allocation of " + size + " allowed with " 
                              + peer.getSendWindowBytesRemaining() 
                              + "/" + peer.getSendWindowBytes() 
                              + " remaining"
                              + " for message " + state.getMessageId() + ": " + state);

                if (state.getPushCount() > 0) {
                    _retransmitters.put(peer, state);

                    int fragments = state.getFragmentCount();
                    int toSend = 0;
                    for (int i = 0; i < fragments; i++) {
                        if (state.needsSending(i))
                            toSend++;
                    }

                    peer.messageRetransmitted(toSend);
                    _packetsRetransmitted += toSend; // lifetime for the transport
                    _context.statManager().addRateData("udp.peerPacketsRetransmitted", peer.getPacketsRetransmitted(), peer.getPacketsTransmitted());
                    _context.statManager().addRateData("udp.packetsRetransmitted", _packetsRetransmitted, peer.getPacketsTransmitted());
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Retransmitting " + state + " to " + peer);
                    _context.statManager().addRateData("udp.sendVolleyTime", state.getLifetime(), toSend);
                }

                state.push();
            
                int rto = peer.getRTO();// * state.getPushCount();
                state.setNextSendTime(now + rto);

                if (peer.getSendWindowBytesRemaining() > 0)
                    _throttle.unchoke(peer.getRemotePeer());
                return true;
            } else {
                _context.statManager().addRateData("udp.sendRejected", state.getPushCount(), state.getLifetime());
                if (state.getMessage() != null)
                    state.getMessage().timestamp("send rejected, available=" + peer.getSendWindowBytesRemaining());
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Allocation of " + size + " rejected w/ wsize=" + peer.getSendWindowBytes()
                              + " available=" + peer.getSendWindowBytesRemaining()
                              + " for message " + state.getMessageId() + ": " + state);
                state.setNextSendTime(now+(_context.random().nextInt(2*ACKSender.ACK_FREQUENCY))); //(now + 1024) & ~SECOND_MASK);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Retransmit after choke for next send time in " + (state.getNextSendTime()-now) + "ms");
                _throttle.choke(peer.getRemotePeer());

                if (state.getMessage() != null)
                    state.getMessage().timestamp("choked, not enough available, wsize=" 
                                                 + peer.getSendWindowBytes() + " available="
                                                 + peer.getSendWindowBytesRemaining());
                return false;
            }
        } // nextTime <= now 

        //if (state.getMessage() != null)
        //    state.getMessage().timestamp("choked, time remaining to retransmit: " + (state.getNextSendTime() - now));
        
        return false;
    }
    
    private UDPPacket[] preparePackets(OutboundMessageState state, PeerState peer) {
        if ( (state != null) && (peer != null) ) {
            int fragments = state.getFragmentCount();
            if (fragments < 0)
                return null;
            
            // ok, simplest possible thing is to always tack on the bitfields if
            List msgIds = peer.getCurrentFullACKs();
            List partialACKBitfields = new ArrayList();
            peer.fetchPartialACKs(partialACKBitfields);
            int piggybackedPartialACK = partialACKBitfields.size();
            List remaining = new ArrayList(msgIds);
            int sparseCount = 0;
            UDPPacket rv[] = new UDPPacket[fragments]; //sparse
            for (int i = 0; i < fragments; i++) {
                if (state.needsSending(i)) {
                    rv[i] = _builder.buildPacket(state, i, peer, remaining, partialACKBitfields);
                    rv[i].setFragmentCount(fragments);
                    OutNetMessage msg = state.getMessage();
                    if (msg != null)
                        rv[i].setMessageType(msg.getMessageTypeId());
                    else
                        rv[i].setMessageType(-1);
                } else {
                    sparseCount++;
                }
            }
            if (sparseCount > 0)
                remaining.clear();
            
            int piggybackedAck = 0;
            if (msgIds.size() != remaining.size()) {
                for (int i = 0; i < msgIds.size(); i++) {
                    Long id = (Long)msgIds.get(i);
                    if (!remaining.contains(id)) {
                        peer.removeACKMessage(id);
                        piggybackedAck++;
                    }
                }
            }
            
            if (sparseCount > 0)
                _context.statManager().addRateData("udp.sendSparse", sparseCount, state.getLifetime());
            if (piggybackedAck > 0)
                _context.statManager().addRateData("udp.sendPiggyback", piggybackedAck, state.getLifetime());
            if (piggybackedPartialACK - partialACKBitfields.size() > 0)
                _context.statManager().addRateData("udp.sendPiggybackPartial", piggybackedPartialACK - partialACKBitfields.size(), state.getLifetime());
            if (_log.shouldLog(Log.INFO))
                _log.info("Building packet for " + state + " to " + peer + " with sparse count: " + sparseCount);
            peer.packetsTransmitted(fragments - sparseCount);
            return rv;
        } else {
            // !alive
            return null;
        }
    }
    
    private static final int SSU_HEADER_SIZE = 46;
    static final int UDP_HEADER_SIZE = 8;
    static final int IP_HEADER_SIZE = 20;
    /** how much payload data can we shove in there? */
    private static final int fragmentSize(int mtu) {
        return mtu - SSU_HEADER_SIZE - UDP_HEADER_SIZE - IP_HEADER_SIZE;
    }
    
    /**
     * We received an ACK of the given messageId from the given peer, so if it
     * is still unacked, mark it as complete. 
     *
     * @return fragments acked
     */
    public int acked(long messageId, Hash ackedBy) {
        OutboundMessageState state = null;
        synchronized (_activeMessages) {
            // linear search, since its tiny
            for (int i = 0; i < _activeMessages.size(); i++) {
                state = (OutboundMessageState)_activeMessages.get(i);
                if (state.getMessageId() == messageId) {
                    OutNetMessage msg = state.getMessage();
                    if (msg != null) {
                        Hash expectedBy = msg.getTarget().getIdentity().getHash();
                        if (!expectedBy.equals(ackedBy)) {
                            state = null;
                            _activeMessages.notifyAll();
                            return 0;
                        }
                    }
                    // either the message was a short circuit after establishment,
                    // or it was received from who we sent it to.  yay!
                    _activeMessages.remove(i);
                    if (i < _nextPacketMessage) {
                        _nextPacketMessage--; 
                        if (_nextPacketMessage < 0)
                            _nextPacketMessage = 0;
                    }
                    locked_removeRetransmitter(state);
                    break;
                } else {
                    state = null;
                }
            }
            _activeMessages.notifyAll();
        }
        
        if (state != null) {
            int numSends = state.getMaxSends();
            if (state.getMessage() != null) {
                PeerState peer = state.getPeer();
                if (peer != null)
                    state.getMessage().timestamp("acked after " + numSends
                                                 + " lastReceived: " 
                                                 + (_context.clock().now() - peer.getLastReceiveTime())
                                                 + " lastSentFully: " 
                                                 + (_context.clock().now() - peer.getLastSendFullyTime()));
            }
                            
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Received ack of " + messageId + " by " + ackedBy.toBase64() 
                          + " after " + state.getLifetime() + " and " + numSends + " sends");
            _context.statManager().addRateData("udp.sendConfirmTime", state.getLifetime(), state.getLifetime());
            if (state.getFragmentCount() > 1)
                _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount(), state.getLifetime());
            if (numSends > 1)
                _context.statManager().addRateData("udp.sendConfirmVolley", numSends, state.getFragmentCount());
            _transport.succeeded(state.getMessage());
            int numFragments = state.getFragmentCount();
            PeerState peer = state.getPeer();
            if (peer != null) {
                // this adjusts the rtt/rto/window/etc
                peer.messageACKed(numFragments*state.getFragmentSize(), state.getLifetime(), numSends);
                if (peer.getSendWindowBytesRemaining() > 0)
                    _throttle.unchoke(peer.getRemotePeer());
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("message acked, but no peer attacked: " + state);
            }
            state.releaseResources();
            return numFragments;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received an ACK for a message not pending: " + messageId);
            return 0;
        }
    }
    
    public void acked(ACKBitfield bitfield, Hash ackedBy) {
        if (bitfield.receivedComplete()) {
            acked(bitfield.getMessageId(), ackedBy);
            return;
        }
        
        OutboundMessageState state = null;
        boolean isComplete = false;
        synchronized (_activeMessages) {
            // linear search, since its tiny
            for (int i = 0; i < _activeMessages.size(); i++) {
                state = (OutboundMessageState)_activeMessages.get(i);
                if (state.getMessageId() == bitfield.getMessageId()) {
                    OutNetMessage msg = state.getMessage();
                    if (msg != null) {
                        Hash expectedBy = msg.getTarget().getIdentity().getHash();
                        if (!expectedBy.equals(ackedBy)) {
                            state = null;
                            _activeMessages.notifyAll();
                            return;
                        }
                    }
                    isComplete = state.acked(bitfield);
                    if (isComplete) {
                        // either the message was a short circuit after establishment,
                        // or it was received from who we sent it to.  yay!
                        _activeMessages.remove(i);
                        if (i < _nextPacketMessage) {
                            _nextPacketMessage--; 
                            if (_nextPacketMessage < 0)
                                _nextPacketMessage = 0;
                        }
                    }
                    locked_removeRetransmitter(state);
                    break;
                } else {
                    state = null;
                }
            }
            _activeMessages.notifyAll();
        }
        
        if (state != null) {
            int numSends = state.getMaxSends();
                        
            int bits = bitfield.fragmentCount();
            int numACKed = 0;
            for (int i = 0; i < bits; i++)
                if (bitfield.received(i))
                    numACKed++;
            
            _context.statManager().addRateData("udp.partialACKReceived", numACKed, state.getLifetime());
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Received partial ack of " + state.getMessageId() + " by " + ackedBy.toBase64() 
                          + " after " + state.getLifetime() + " and " + numSends + " sends: " + bitfield + ": completely removed? " 
                          + isComplete + ": " + state);
            
            if (isComplete) {
                _context.statManager().addRateData("udp.sendConfirmTime", state.getLifetime(), state.getLifetime());
                if (state.getFragmentCount() > 1)
                    _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount(), state.getLifetime());
                if (numSends > 1)
                    _context.statManager().addRateData("udp.sendConfirmVolley", numSends, state.getFragmentCount());
                if (state.getMessage() != null)
                    state.getMessage().timestamp("partial ack to complete after " + numSends);
                _transport.succeeded(state.getMessage());
                
                if (state.getPeer() != null) {
                    // this adjusts the rtt/rto/window/etc
                    state.getPeer().messageACKed(state.getFragmentCount()*state.getFragmentSize(), state.getLifetime(), 0);
                    if (state.getPeer().getSendWindowBytesRemaining() > 0)
                        _throttle.unchoke(state.getPeer().getRemotePeer());
                }

                state.releaseResources();
            } else {
                if (state.getMessage() != null)
                    state.getMessage().timestamp("partial ack after " + numSends + ": " + bitfield.toString());
            }
            return;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received an ACK for a message not pending: " + bitfield);
            return;
        }
    }
    
    public interface ActiveThrottle {
        public void choke(Hash peer);
        public void unchoke(Hash peer);
        public boolean isChoked(Hash peer);
    }
}
