package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Coordinate the outbound fragments and select the next one to be built.
 * This pool contains messages we are actively trying to send, essentially 
 * doing a round robin across each message to send one fragment, as implemented
 * in {@link #getNextPacket()}.  This also honors per-peer throttling, taking 
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
    /** OutboundMessageState for messages being sent */
    private List _activeMessages;
    private boolean _alive;
    /** which message should we build the next packet out of? */
    private int _nextPacketMessage;
    private PacketBuilder _builder;
    /** if we can handle more messages explicitly, set this to true */
    private boolean _allowExcess;
    
    private static final int MAX_ACTIVE = 64;
    // don't send a packet more than 10 times
    private static final int MAX_VOLLEYS = 10;
    
    public OutboundMessageFragments(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundMessageFragments.class);
        _transport = transport;
        _activeMessages = new ArrayList(MAX_ACTIVE);
        _nextPacketMessage = 0;
        _builder = new PacketBuilder(ctx);
        _alive = true;
        _allowExcess = false;
        _context.statManager().createRateStat("udp.sendVolleyTime", "Long it takes to send a full volley", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendConfirmTime", "How long it takes to send a message and get the ACK", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendConfirmFragments", "How many fragments are included in a fully ACKed message", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendConfirmVolley", "How many times did fragments need to be sent before ACK", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendFailed", "How many fragments were in a message that couldn't be delivered", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.sendAggressiveFailed", "How many volleys was a packet sent before we gave up", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.outboundActiveCount", "How many messages are in the active pool when a new one is added", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
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
        while (_alive) {
            finishMessages();
            try {
                synchronized (_activeMessages) {
                    if (!_alive)
                        return false;
                    else if (_activeMessages.size() < MAX_ACTIVE)
                        return true;
                    else if (_allowExcess)
                        return true;
                    else
                        _activeMessages.wait();
                }
            } catch (InterruptedException ie) {}
        }
        return false;
    }
    
    /**
     * Add a new message to the active pool
     *
     */
    public void add(OutNetMessage msg) {
        OutboundMessageState state = new OutboundMessageState(_context);
        boolean ok = state.initialize(msg);
        state.setPeer(_transport.getPeerState(msg.getTarget().getIdentity().calculateHash()));
        finishMessages();
        int active = 0;
        synchronized (_activeMessages) {
            if (ok)
                _activeMessages.add(state);
            active = _activeMessages.size();
            _activeMessages.notifyAll();
        }
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
                if (state.isComplete()) {
                    _activeMessages.remove(i);
                    _transport.succeeded(state.getMessage());
                    state.releaseResources();
                    i--;
                } else if (state.isExpired()) {
                    _activeMessages.remove(i);
                    _context.statManager().addRateData("udp.sendFailed", state.getFragmentCount(), state.getLifetime());

                    if (state.getMessage() != null) {
                        _transport.failed(state.getMessage());
                    } else {
                        // it can not have an OutNetMessage if the source is the
                        // final after establishment message
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Unable to send an expired direct message: " + state);
                    }
                    state.releaseResources();
                    i--;
                } else if (state.getPushCount() > MAX_VOLLEYS) {
                    _activeMessages.remove(i);
                    _context.statManager().addRateData("udp.sendAggressiveFailed", state.getPushCount(), state.getLifetime());
                    //if (state.getPeer() != null)
                    //    state.getPeer().congestionOccurred();

                    if (state.getMessage() != null) {
                        _transport.failed(state.getMessage());
                    } else {
                        // it can not have an OutNetMessage if the source is the
                        // final after establishment message
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Unable to send a direct message after too many volleys: " + state);
                    }
                    state.releaseResources();
                    i--;
                }
            }
        }
    }
    
    private static final long SECOND_MASK = 1023l;

    
    /**
     * Grab the next packet that we want to send, blocking until one is ready.
     * This is the main driver for the packet scheduler
     *
     */
    public UDPPacket getNextPacket() {
        PeerState peer = null;
        OutboundMessageState state = null;
        int currentFragment = -1;
        while (_alive && (currentFragment < 0) ) {
            long now = _context.clock().now();
            long nextSend = -1;
            finishMessages();
            synchronized (_activeMessages) {
                for (int i = 0; i < _activeMessages.size(); i++) {
                    int cur = (i + _nextPacketMessage) % _activeMessages.size();
                    state = (OutboundMessageState)_activeMessages.get(cur);
                    if (state.getNextSendTime() <= now) {
                        peer = state.getPeer(); // known if this is immediately after establish
                        if (peer == null)
                            peer = _transport.getPeerState(state.getMessage().getTarget().getIdentity().calculateHash());
                        
                        if (peer == null) {
                            // peer disconnected (whatever that means)
                            _activeMessages.remove(cur);
                            _transport.failed(state.getMessage());
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Peer disconnected for " + state);
                            state.releaseResources();
                            i--;
                        } else {
                            if (!state.isFragmented()) {
                                state.fragment(fragmentSize(peer.getMTU()));
                                
                                if (_log.shouldLog(Log.INFO))
                                    _log.info("Fragmenting " + state);
                            }
                            
                            int oldVolley = state.getPushCount();
                            // pickNextFragment increments the pushCount every
                            // time we cycle through all of the packets
                            currentFragment = state.pickNextFragment();

                            int fragmentSize = state.fragmentSize(currentFragment);
                            if (peer.allocateSendingBytes(fragmentSize)) {
                                if (_log.shouldLog(Log.INFO))
                                    _log.info("Allocation of " + fragmentSize + " allowed with " 
                                              + peer.getSendWindowBytesRemaining() 
                                              + "/" + peer.getSendWindowBytes() 
                                              + " remaining"
                                              + " for message " + state.getMessageId() + ": " + state);
                                
                                if (state.justBeganVolley() && (state.getPushCount() > 0) && (state.getFragmentCount() > 1)) {
                                    peer.messageRetransmitted();
                                    if (_log.shouldLog(Log.ERROR))
                                        _log.error("Retransmitting " + state + " to " + peer);
                                }
                                
                                // for fairness, we move on in a round robin
                                _nextPacketMessage = i + 1;
                                
                                if (currentFragment >= state.getFragmentCount() - 1) {
                                    // this is the last fragment
                                    _context.statManager().addRateData("udp.sendVolleyTime", state.getLifetime(), state.getFragmentCount());
                                    if (state.getPeer() != null) {
                                        int rto = state.getPeer().getRTO() * state.getPushCount();
                                        //_log.error("changed volley, rto=" + rto + " volley="+ state.getPushCount());
                                        state.setNextSendTime(now + rto);
                                    } else {
                                        _log.error("changed volley, unknown peer");
                                        state.setNextSendTime(now + 1000 + _context.random().nextInt(2000));
                                    }
                                } else {
                                    if (peer.getSendWindowBytesRemaining() > 0)
                                        state.setNextSendTime(now);
                                    else
                                        state.setNextSendTime((now + 1024) & ~SECOND_MASK);
                                }
                                break;
                            } else {
                                if (_log.shouldLog(Log.WARN))
                                    _log.warn("Allocation of " + fragmentSize + " rejected w/ wsize=" + peer.getSendWindowBytes()
                                              + " available=" + peer.getSendWindowBytesRemaining()
                                              + " for message " + state.getMessageId() + ": " + state);
                                state.setNextSendTime((now + 1024) & ~SECOND_MASK);
                                currentFragment = -1;
                            }
                        }
                    } 
                    long time = state.getNextSendTime();
                    if ( (nextSend < 0) || (time < nextSend) )
                        nextSend = time;
                } // end of the for(activeMessages)
            
                if (currentFragment < 0) {
                    if (nextSend <= 0) {
                        try {
                            _activeMessages.wait();
                        } catch (InterruptedException ie) {}
                    } else {
                        // none of the packets were eligible for sending
                        long delay = nextSend - now;
                        if (delay <= 0)
                            delay = 10;
                        if (delay > 1000) 
                            delay = 1000;
                        _allowExcess = true;
                        _activeMessages.notifyAll();
                        try {
                            _activeMessages.wait(delay);
                        } catch (InterruptedException ie) {}
                    }
                }
                _allowExcess = false;
            } // end of the synchronized block
        } // end of the while (alive && !found)
        
        if (currentFragment >= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Building packet for fragment " + currentFragment 
                          + " of " + state + " to " + peer);
            UDPPacket rv = _builder.buildPacket(state, currentFragment, peer);
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
                            return 0;
                        }
                    }
                    // either the message was a short circuit after establishment,
                    // or it was received from who we sent it to.  yay!
                    _activeMessages.remove(i);
                    _activeMessages.notifyAll();
                    break;
                } else {
                    state = null;
                }
            }
        }
        
        if (state != null) {
            int numSends = state.getMaxSends();
            if (_log.shouldLog(Log.INFO))
                _log.info("Received ack of " + messageId + " by " + ackedBy.toBase64() 
                          + " after " + state.getLifetime() + " and " + numSends + " sends");
            _context.statManager().addRateData("udp.sendConfirmTime", state.getLifetime(), state.getLifetime());
            _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount(), state.getLifetime());
            _context.statManager().addRateData("udp.sendConfirmVolley", numSends, state.getFragmentCount());
            if ( (numSends > 1) && (state.getPeer() != null) )
                state.getPeer().congestionOccurred();
            _transport.succeeded(state.getMessage());
            int numFragments = state.getFragmentCount();
            if (state.getPeer() != null) {
                // this adjusts the rtt/rto/window/etc
                state.getPeer().messageACKed(numFragments*state.getFragmentSize(), state.getLifetime(), state.getMaxSends());
            } else {
                _log.warn("message acked, but no peer attacked: " + state);
            }
            state.releaseResources();
            return numFragments;
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Received an ACK for a message not pending: " + messageId);
            return 0;
        }
    }
    
    /**
     * Receive a set of fragment ACKs for a given messageId from the 
     * specified peer
     *
     */
    public void acked(long messageId, int ackedFragments[], Hash ackedBy) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Received partial ack of " + messageId + " by " + ackedBy.toBase64());
        OutboundMessageState state = null;
        synchronized (_activeMessages) {
            // linear search, since its tiny
            for (int i = 0; i < _activeMessages.size(); i++) {
                state = (OutboundMessageState)_activeMessages.get(i);
                if (state.getMessage().getMessageId() == messageId) {
                    Hash expectedBy = state.getMessage().getTarget().getIdentity().calculateHash();
                    if (!expectedBy.equals(ackedBy)) {
                        return;
                    } else {
                        state.acked(ackedFragments);
                        if (state.isComplete()) {
                            _activeMessages.remove(i);
                            _activeMessages.notifyAll();
                        }
                        break;
                    }
                }
            }
        }
        
        if ( (state != null) && (state.isComplete()) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Received ack of " + messageId + " by " + ackedBy.toBase64() 
                          + " after " + state.getLifetime());
            _context.statManager().addRateData("udp.sendConfirmTime", state.getLifetime(), state.getLifetime());
            _context.statManager().addRateData("udp.sendConfirmFragments", state.getFragmentCount(), state.getLifetime());
            _transport.succeeded(state.getMessage());
            state.releaseResources();
        }
    }
}
