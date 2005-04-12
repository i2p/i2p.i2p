package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Organize the received data message fragments, allowing its 
 * {@link MessageReceiver} to pull off completed messages and its 
 * {@link ACKSender} to pull off peers who need to receive an ACK for
 * these messages.  In addition, it drops failed fragments and keeps a
 * minimal list of the most recently completed messages (even though higher
 * up in the router we have full blown replay detection, its nice to have a
 * basic line of defense here)
 *
 */
public class InboundMessageFragments {
    private RouterContext _context;
    private Log _log;
    /** Map of peer (Hash) to a Map of messageId (Long) to InboundMessageState objects */
    private Map _inboundMessages;
    /** list of peers (PeerState) who we have received data from but not yet ACKed to */
    private List _unsentACKs;
    /** list of messages (InboundMessageState) fully received but not interpreted yet */
    private List _completeMessages;
    /** list of message IDs (Long) recently received, so we can ignore in flight dups */
    private List _recentlyCompletedMessages;
    private OutboundMessageFragments _outbound;
    private UDPTransport _transport;
    /** this can be broken down further, but to start, OneBigLock does the trick */
    private Object _stateLock;
    private boolean _alive;
    
    private static final int RECENTLY_COMPLETED_SIZE = 100;
    /** how frequently do we want to send ACKs to a peer? */
    private static final int ACK_FREQUENCY = 100;
        
    public InboundMessageFragments(RouterContext ctx, OutboundMessageFragments outbound, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundMessageFragments.class);
        _inboundMessages = new HashMap(64);
        _unsentACKs = new ArrayList(64);
        _completeMessages = new ArrayList(64);
        _recentlyCompletedMessages = new ArrayList(RECENTLY_COMPLETED_SIZE);
        _outbound = outbound;
        _transport = transport;
        _context.statManager().createRateStat("udp.receivedCompleteTime", "How long it takes to receive a full message", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receivedCompleteFragments", "How many fragments go in a fully received message", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receivedACKs", "How many messages were ACKed at a time", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.ignoreRecentDuplicate", "Take note that we received a packet for a recently completed message", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receiveMessagePeriod", "How long it takes to pull the message fragments out of a packet", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("udp.receiveACKPeriod", "How long it takes to pull the ACKs out of a packet", "udp", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _stateLock = this;
    }
    
    public void startup() { 
        _alive = true; 
        I2PThread t = new I2PThread(new ACKSender(_context, this, _transport), "UDP ACK sender");
        t.setDaemon(true);
        t.start();
        
        t = new I2PThread(new MessageReceiver(_context, this, _transport), "UDP message receiver");
        t.setDaemon(true);
        t.start();
    }
    public void shutdown() {
        _alive = false;
        synchronized (_stateLock) {
            _completeMessages.clear();
            _unsentACKs.clear();
            _inboundMessages.clear();
            _stateLock.notifyAll();
        }
    }
    public boolean isAlive() { return _alive; }

    /**
     * Pull the fragments and ACKs out of the authenticated data packet
     */
    public void receiveData(PeerState from, UDPPacketReader.DataReader data) {
        long beforeMsgs = _context.clock().now();
        receiveMessages(from, data);
        long afterMsgs = _context.clock().now();
        receiveACKs(from, data);
        long afterACKs = _context.clock().now();
        
        _context.statManager().addRateData("udp.receiveMessagePeriod", afterMsgs-beforeMsgs, afterACKs-beforeMsgs);
        _context.statManager().addRateData("udp.receiveACKPeriod", afterACKs-afterMsgs, afterACKs-beforeMsgs);
    }
    
    /**
     * Pull out all the data fragments and shove them into InboundMessageStates.
     * Along the way, if any state expires, or a full message arrives, move it
     * appropriately.
     *
     */
    private void receiveMessages(PeerState from, UDPPacketReader.DataReader data) {
        int fragments = data.readFragmentCount();
        if (fragments <= 0) return;
        synchronized (_stateLock) {
            Map messages = (Map)_inboundMessages.get(from.getRemotePeer());
            if (messages == null) {
                messages = new HashMap(fragments);
                _inboundMessages.put(from.getRemotePeer(), messages);
            }
        
            for (int i = 0; i < fragments; i++) {
                Long messageId = new Long(data.readMessageId(i));
            
                if (_recentlyCompletedMessages.contains(messageId)) {
                    _context.statManager().addRateData("udp.ignoreRecentDuplicate", 1, 0);
                    continue;
                }
            
                int size = data.readMessageFragmentSize(i);
                InboundMessageState state = null;
                boolean messageComplete = false;
                boolean messageExpired = false;
                boolean fragmentOK = false;
                state = (InboundMessageState)messages.get(messageId);
                if (state == null) {
                    state = new InboundMessageState(_context, messageId.longValue(), from.getRemotePeer());
                    messages.put(messageId, state);
                }
                fragmentOK = state.receiveFragment(data, i);
                if (state.isComplete()) {
                    messageComplete = true;
                    messages.remove(messageId);
                    
                   while (_recentlyCompletedMessages.size() >= RECENTLY_COMPLETED_SIZE)
                        _recentlyCompletedMessages.remove(0);
                    _recentlyCompletedMessages.add(messageId);

                    _completeMessages.add(state);
                    
                    from.messageFullyReceived(messageId);
                    if (!_unsentACKs.contains(from))
                        _unsentACKs.add(from);
                    
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Message received completely!  " + state);

                    _context.statManager().addRateData("udp.receivedCompleteTime", state.getLifetime(), state.getLifetime());
                    _context.statManager().addRateData("udp.receivedCompleteFragments", state.getFragmentCount(), state.getLifetime());

                    _stateLock.notifyAll();
                } else if (state.isExpired()) {
                    messageExpired = true;
                    messages.remove(messageId);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Message expired while only being partially read: " + state);
                    state.releaseResources();
                }
                
                if (!fragmentOK)
                    break;
            }
        }
    }
    
    private void receiveACKs(PeerState from, UDPPacketReader.DataReader data) {
        if (data.readACKsIncluded()) {
            int fragments = 0;
            long acks[] = data.readACKs();
            _context.statManager().addRateData("udp.receivedACKs", acks.length, 0);
            for (int i = 0; i < acks.length; i++) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Full ACK of message " + acks[i] + " received!");
                fragments += _outbound.acked(acks[i], from.getRemotePeer());
            }
            from.messageACKed(fragments * from.getMTU()); // estimated size
        }
        if (data.readECN())
            from.ECNReceived();
        else
            from.dataReceived();
    }
    
    /**
     * Blocking call to pull off the next fully received message
     *
     */
    public InboundMessageState receiveNextMessage() {
        while (_alive) {
            try {
                synchronized (_stateLock) {
                    if (_completeMessages.size() > 0)
                        return (InboundMessageState)_completeMessages.remove(0);
                    _stateLock.wait();
                }
            } catch (InterruptedException ie) {}
        }
        return null;
    }
    
    /** 
     * Pull off the peer who we next want to send ACKs/NACKs to.
     * This call blocks, and only returns null on shutdown.
     *
     */
    public PeerState getNextPeerToACK() {
        while (_alive) {
            try {
                long now = _context.clock().now();
                synchronized (_stateLock) {
                    for (int i = 0; i < _unsentACKs.size(); i++) {
                        PeerState peer = (PeerState)_unsentACKs.get(i);
                        if (peer.getLastACKSend() + ACK_FREQUENCY <= now) {
                            _unsentACKs.remove(i);
                            peer.setLastACKSend(now);
                            return peer;
                        }
                    }
                    if (_unsentACKs.size() > 0)
                        _stateLock.wait(_context.random().nextInt(100));
                    else
                        _stateLock.wait();
                }
            } catch (InterruptedException ie) {}
        }
        return null;
    }
}
