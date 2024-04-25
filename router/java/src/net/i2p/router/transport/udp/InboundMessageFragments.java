package net.i2p.router.transport.udp;

import java.util.Map;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.util.Log;

/**
 * Organize the received data message fragments, feeding completed messages
 * to the {@link MessageReceiver} and telling the {@link PeerState}
 * to ACK.  In addition, it drops failed fragments and keeps a
 * minimal list of the most recently completed messages (even though higher
 * up in the router we have full blown replay detection, its nice to have a
 * basic line of defense here).
 *
 */
class InboundMessageFragments /*implements UDPTransport.PartialACKSource */{
    private final RouterContext _context;
    private final Log _log;
    /** list of message IDs recently received, so we can ignore in flight dups */
    private DecayingBloomFilter _recentlyCompletedMessages;
    private final OutboundMessageFragments _outbound;
    private final UDPTransport _transport;
    private final MessageReceiver _messageReceiver;
    private volatile boolean _alive;
    
    /** decay the recently completed every 20 seconds */
    private static final int DECAY_PERIOD = 10*1000;
        
    public InboundMessageFragments(RouterContext ctx, OutboundMessageFragments outbound, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundMessageFragments.class);
        //_inboundMessages = new HashMap(64);
        _outbound = outbound;
        _transport = transport;
        _messageReceiver = new MessageReceiver(_context, _transport);
        _context.statManager().createRateStat("udp.receivedCompleteTime", "How long it takes to receive a full message", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivedCompleteFragments", "How many fragments go in a fully received message", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivedACKs", "How many messages were ACKed at a time", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.ignoreRecentDuplicate", "Take note that we received a packet for a recently completed message", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receiveMessagePeriod", "How long it takes to pull the message fragments out of a packet", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receiveACKPeriod", "How long it takes to pull the ACKs out of a packet", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePiggyback", "How many acks were included in a packet with data fragments (time == # data fragments)", "udp", UDPTransport.RATES);
    }
    
    public synchronized void startup() { 
        _alive = true; 
        // may want to extend the DecayingBloomFilter so we can use a smaller 
        // array size (currently its tuned for 10 minute rates for the 
        // messageValidator)
        _recentlyCompletedMessages = new DecayingHashSet(_context, DECAY_PERIOD, 4, "UDPIMF");
        _messageReceiver.startup();
    }

    public synchronized void shutdown() {
        _alive = false;
        if (_recentlyCompletedMessages != null)
            _recentlyCompletedMessages.stopDecaying();
        _recentlyCompletedMessages = null;
        _messageReceiver.shutdown();
    }

    public boolean isAlive() { return _alive; }

    /**
     * This message was received.
     * SSU 2 only.
     * No stats updated here, caller should handle stats.
     *
     * @return true if this message was a duplicate
     * @since 0.9.54
     */

    public boolean messageReceived(long messageID) {
        return _recentlyCompletedMessages.add(messageID);
    }

    /**
     * Was this message recently received?
     * SSU 2 only.
     * No stats updated here, caller should handle stats.
     *
     * @return true if this message was recently received.
     * @since 0.9.54
     */

    public boolean wasRecentlyReceived(long messageID) {
        return _recentlyCompletedMessages.isKnown(messageID);
    }

    /**
     * Modifiable Long, no locking
     * @since 0.9.49
     */
    public static class ModifiableLong {
        public long value;
        public ModifiableLong(long val) { value = val; }
    }
}
