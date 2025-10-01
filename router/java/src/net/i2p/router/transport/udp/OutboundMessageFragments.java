package net.i2p.router.transport.udp;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.udp.PacketBuilder.Fragment;
import net.i2p.util.ConcurrentHashSet;
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
class OutboundMessageFragments {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;

    /**
     *  Peers we are actively sending messages to.
     *  We use the iterator so we treat it like a list,
     *  but we use a HashSet so remove() is fast and
     *  we don't need to do contains().
     *  Even though most (but NOT all) accesses are synchronized,
     *  we use a ConcurrentHashSet as the iterator is long-lived.
     */
    private final Set<PeerState> _activePeers;

    /**
     *  The long-lived iterator over _activePeers.
     */
    private Iterator<PeerState> _iterator;

    private volatile boolean _alive;
    private final PacketBuilder2 _builder2;

    /** if we can handle more messages explicitly, set this to true */
    // private boolean _allowExcess; // LINT not used??
    // private volatile long _packetsRetransmitted; // LINT not used??

    // private static final int MAX_ACTIVE = 64; // not used.
    // don't send a packet more than 10 times
    static final int MAX_VOLLEYS = 10;
    private static final int MAX_WAIT = 1000;

    public OutboundMessageFragments(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundMessageFragments.class);
        _transport = transport;
        _activePeers = new ConcurrentHashSet<PeerState>(256);
        _builder2 = transport.getBuilder2();
        _alive = true;
        // _allowExcess = false;
        _context.statManager().createRateStat("udp.sendVolleyTime", "Long it takes to send a full volley", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendConfirmTime", "How long it takes to send a message and get the ACK", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendConfirmFragments", "How many fragments are included in a fully ACKed message", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendFragmentsPerPacket", "How many fragments are sent in a data packet", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendConfirmVolley", "How many times did fragments need to be sent before ACK", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendFailed", "How many sends a failed message was pushed", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendAggressiveFailed", "How many volleys was a packet sent before we gave up", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.outboundActiveCount", "How many messages are in the peer's active pool", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.outboundActivePeers", "How many peers we are actively sending to", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendRejected", "What volley are we on when the peer was throttled", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.partialACKReceived", "How many fragments were partially ACKed", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.sendSparse", "How many fragments were partially ACKed and hence not resent (time == message lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRequiredRateStat("udp.packetsRetransmitted", "Lifetime of packets during retransmission (ms)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.peerPacketsRetransmitted", "How many packets have been retransmitted to the peer (lifetime) when a burst of packets are retransmitted (period == packets transmitted, lifetime)", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.sendCycleTime", "How long it takes to cycle through all of the active messages?", "udp", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.sendCycleTimeSlow", "How long it takes to cycle through all of the active messages, when its going slowly?", "udp", UDPTransport.RATES);
    }

    public synchronized void startup() { _alive = true; }

    public synchronized void shutdown() {
        _alive = false;
        _activePeers.clear();
        synchronized (_activePeers) {
            _activePeers.notify();
        }
    }

    void dropPeer(PeerState peer) {
        if (_log.shouldDebug())
            _log.debug("Dropping peer " + peer.getRemotePeer());
        peer.dropOutbound();
        _activePeers.remove(peer);
    }

    /**
     * Add a new message to the active pool
     *
     */
    public void add(OutNetMessage msg) {
        RouterInfo target = msg.getTarget();
        if (target == null)
            return;

        PeerState peer = _transport.getPeerState(target.getIdentity().calculateHash());
        try {
            // will throw IAE if peer == null
            OutboundMessageState state = new OutboundMessageState(_context, msg, peer);
            peer.add(state);
            add(peer, state.getMinSendSize());
        } catch (IllegalArgumentException iae) {
            _transport.failed(msg, "Peer disconnected quickly");
            return;
        }
    }

    /**
     *  Short circuit the OutNetMessage, letting us send the establish
     *  complete message reliably.
     *  If you have multiple messages, use the list variant,
     *  so the messages may be bundled efficiently.
     */
    public void add(OutboundMessageState state, PeerState peer) {
        if (peer == null)
            throw new RuntimeException("null peer for " + state);
        peer.add(state);
        add(peer, state.getMinSendSize());
        //_context.statManager().addRateData("udp.outboundActiveCount", active, 0);
    }

    /**
     *  Short circuit the OutNetMessage, letting us send multiple messages
     *  reliably and efficiently.
     *  @since 0.9.24
     */
    public void add(List<OutboundMessageState> states, PeerState peer) {
        if (peer == null)
            throw new RuntimeException("null peer");
        int sz = states.size();
        int min = peer.fragmentSize();
        for (int i = 0; i < sz; i++) {
            OutboundMessageState state = states.get(i);
            peer.add(state);
            int fsz = state.getMinSendSize();
            if (fsz < min)
                min = fsz;
        }
        add(peer, min);
        //_context.statManager().addRateData("udp.outboundActiveCount", active, 0);
    }

    /**
     * Add the peer to the list of peers wanting to transmit something.
     * This wakes up the packet pusher if it is sleeping.
     *
     * Avoid synchronization where possible.
     * There are small chances of races.
     * There are larger chances of adding the PeerState "behind" where
     * the iterator is now... but these issues are the same as before concurrentification.
     *
     * @param size the minimum size we can send, or 0 to always notify
     * @since 0.8.9
     */
    public void add(PeerState peer, int size) {
        boolean added = _activePeers.add(peer);
        if (added) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Add a new message to a new peer " + peer.getRemotePeer());
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Add a new message to an existing peer " + peer.getRemotePeer());
        }
        _context.statManager().addRateData("udp.outboundActivePeers", _activePeers.size());

        // Avoid sync if possible
        // no, this doesn't always work.
        // Also note that the iterator in getNextVolley may have alreay passed us,
        // or not reflect the addition.
        if (added || size <= 0 || peer.getSendWindowBytesRemaining() >= size) {
            synchronized (_activePeers) {
                _activePeers.notify();
            }
        }
    }

    /**
     * Remove any expired or complete messages
     */
/****
    private void finishMessages() {
        for (Iterator<PeerState> iter = _activePeers.iterator(); iter.hasNext(); ) {
             PeerState state = iter.next();
             if (state.getOutboundMessageCount() <= 0) {
                 iter.remove();
             } else {
                 int remaining = state.finishMessages();
                 if (remaining <= 0) {
                     if (_log.shouldLog(Log.DEBUG))
                         _log.debug("No more pending messages for " + state.getRemotePeer().toBase64());
                     iter.remove();
                 }
             }
         }
     }
****/
 
    /**
     * Fetch all the packets for a message volley, blocking until there is a
     * message which can be fully transmitted (or the transport is shut down).
     *
     * NOT thread-safe. Called by the PacketPusher thread only.
     *
     * @return null only on shutdown
     */
    public List<UDPPacket> getNextVolley() {
        PeerState peer = null;
        List<OutboundMessageState> states = null;
        // Keep track of how many we've looked at, since we don't start the iterator at the beginning.
        int peersProcessed = 0;
        int nextSendDelay = Integer.MAX_VALUE;
        while (_alive && (states == null) ) {
            // no, not every time - O(n**2) - do just before waiting below
            //finishMessages();

                    // do we need a new long-lived iterator?
                    if (_iterator == null ||
                        ((!_activePeers.isEmpty()) && (!_iterator.hasNext()))) {
                        _iterator = _activePeers.iterator();
                    }

                    // Go through all the peers that we are actively sending messages to.
                    // Call finishMessages() for each one, and remove them from the iterator
                    // if there is nothing left to send.
                    // Otherwise, return the volley to be sent.
                    // Otherwise, wait()
                    long now = _context.clock().now();
                    while (_iterator.hasNext()) {
                        PeerState p = _iterator.next();
                        int remaining = p.finishMessages(now);
                        if (remaining <= 0) {
                            // race with add()
                            _iterator.remove();
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("No more pending messages for " + p.getRemotePeer());
                            continue;
                        }
                        peersProcessed++;
                        states = p.allocateSend(now);
                        if (states != null) {
                            peer = p;
                            // we have something to send and we will be returning it
                            break;
                        } 
                        int delay = p.getNextDelay(now);
                        if (delay < nextSendDelay)
                            nextSendDelay = delay;
                        
                        if (peersProcessed >= _activePeers.size()) {
                            // we've gone all the way around, time to sleep
                            break;
                        }
                    }

                    //if (peer != null && _log.shouldLog(Log.DEBUG))
                    //    _log.debug("Done looping, next peer we are sending for: " +
                    //               peer.getRemotePeer());

                    // if we've gone all the way through the loop, wait
                    // ... unless nextSendDelay says we have more ready now
                    if (states == null && peersProcessed >= _activePeers.size() && nextSendDelay > 0) {
                        peersProcessed = 0;
                        // why? we do this in the loop one at a time
                        //finishMessages();
                        // wait a min of 10 and a max of MAX_WAIT ms no matter what peer.getNextDelay() says
                        // use max of 1 second so finishMessages() and/or PeerState.finishMessages()
                        // gets called regularly
                        int toWait = Math.min(Math.max(nextSendDelay, 10), MAX_WAIT);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("wait for " + toWait);

                        nextSendDelay = Integer.MAX_VALUE;
                        // wait.. or somethin'
                        synchronized (_activePeers) {
                            try {
                                _activePeers.wait(toWait);
                            } catch (InterruptedException ie) {
                                // noop
                                if (_log.shouldLog(Log.DEBUG))
                                     _log.debug("Woken up while waiting");
                            }
                        }
                    //} else {
                    //    if (_log.shouldLog(Log.DEBUG))
                    //        _log.debug("dont wait: alive=" + _alive + " state = " + state);
                    }

        } // while alive && state == null

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending " + DataHelper.toString(states));

        List<UDPPacket> packets = preparePackets(states, peer);

      /****
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
       ****/

        return packets;
    }

    /**
     * Wakes up the packet pusher thread.
     * @since 0.9.48
     */
    void nudge() {
        synchronized(_activePeers) {
            _activePeers.notify();
        }
    }

    /**
     *  @return null if state or peer is null
     */
    private List<UDPPacket> preparePackets(List<OutboundMessageState> states, PeerState peer) {
        if (states == null || peer == null)
            return null;

        // build the list of fragments to send
        List<Fragment> toSend = new ArrayList<Fragment>(8);
        for (OutboundMessageState state : states) {
            int queued = state.push(toSend);
            // per-state stats
            if (queued > 0 && state.getMaxSends() > 1) {
                int maxPktSz = state.fragmentSize(0);
                maxPktSz += SSU2Payload.BLOCK_HEADER_SIZE +
                                (peer.isIPv6() ? PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD : PacketBuilder2.MIN_DATA_PACKET_OVERHEAD);
                peer.messageRetransmitted(queued, maxPktSz);
                // _packetsRetransmitted += toSend; // lifetime for the transport
                long lifetime = state.getLifetime();
                int transmitted = peer.getPacketsTransmitted();
                _context.statManager().addRateData("udp.peerPacketsRetransmitted", peer.getPacketsRetransmitted(), transmitted);
                _context.statManager().addRateData("udp.packetsRetransmitted", lifetime, transmitted);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Retransmitting " + state + " to " + peer);
                _context.statManager().addRateData("udp.sendVolleyTime", lifetime, queued);
            }
        }

        if (toSend.isEmpty())
            return null;

        int fragmentsToSend = toSend.size();
        // sort by size, biggest first
        // don't bother unless more than one state (fragments are already sorted within a state)
        // This puts the DeliveryStatusMessage after the DatabaseStoreMessage, don't do it for now.
        // It also undoes the ordering of the priority queue in PeerState.
        //if (fragmentsToSend > 1 && states.size() > 1)
        //    Collections.sort(toSend, new FragmentComparator());

        List<Fragment> sendNext = new ArrayList<Fragment>(Math.min(toSend.size(), 4));
        List<UDPPacket> rv = new ArrayList<UDPPacket>(toSend.size());
        for (int i = 0; i < toSend.size(); i++) {
            Fragment next = toSend.get(i);
            sendNext.add(next);
            OutboundMessageState state = next.state;
            OutNetMessage msg = state.getMessage();
            int msgType = (msg != null) ? msg.getMessageTypeId() : -1;
            if (_log.shouldDebug())
                _log.debug("Building packet for " + next + " to " + peer);
            int curTotalDataSize = state.fragmentSize(next.num);
            curTotalDataSize += SSU2Util.FIRST_FRAGMENT_HEADER_SIZE;
            if (next.num > 0)
                curTotalDataSize += SSU2Util.DATA_FOLLOWON_EXTRA_SIZE;
            // now stuff in more fragments if they fit
            if (i +1 < toSend.size()) {
                int maxAvail = PacketBuilder2.getMaxAdditionalFragmentSize(peer, sendNext.size(), curTotalDataSize);
                // if less than 16, just use it for acks, don't even try to look for a tiny fragment
                if (maxAvail >= 16) {
                    for (int j = i + 1; j < toSend.size(); j++) {
                        next = toSend.get(j);
                        int nextDataSize = next.state.fragmentSize(next.num);
                        if (next.num > 0)
                            nextDataSize += SSU2Util.DATA_FOLLOWON_EXTRA_SIZE;
                        //if (PacketBuilder.canFitAnotherFragment(peer, sendNext.size(), curTotalDataSize, nextDataSize)) {
                        //if (_builder.canFitAnotherFragment(peer, sendNext.size(), curTotalDataSize, nextDataSize)) {
                        if (nextDataSize <= maxAvail) {
                            // add it
                            toSend.remove(j);
                            j--;
                            sendNext.add(next);
                            curTotalDataSize += nextDataSize;
                            maxAvail = PacketBuilder2.getMaxAdditionalFragmentSize(peer, sendNext.size(), curTotalDataSize);
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Adding in additional " + next + " to " + peer);
                            // if less than 16, just use it for acks, don't even try to look for a tiny fragment
                            if (maxAvail < 16)
                                break;
                        }  // else too big
                    }
                }
            }

            UDPPacket pkt;
                try {
                    pkt = _builder2.buildPacket(sendNext, (PeerState2) peer);
                } catch (IOException ioe) {
                    pkt = null;
                }
            if (pkt != null) {
                if (_log.shouldDebug())
                    _log.debug("Built packet with " + sendNext.size() + " fragments totalling " + curTotalDataSize +
                              " data bytes to " + peer);
                _context.statManager().addRateData("udp.sendFragmentsPerPacket", sendNext.size());
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Build packet FAIL for " + DataHelper.toString(sendNext) + " to " + peer);
                sendNext.clear();
                continue;
            }
            rv.add(pkt);

            // following for debugging and stats
            pkt.setFragmentCount(sendNext.size());
            pkt.setMessageType(msgType);  //type of first fragment
            sendNext.clear();
        }



        int sent = rv.size();
        peer.packetsTransmitted(sent);
        peer.clearWantedACKSendSince();
        if (_log.shouldDebug())
            _log.debug("Sent " + fragmentsToSend + " fragments of " + states.size() +
                      " messages in " + sent + " packets to " + peer);

        return rv;
    }

    /**
     *  Biggest first
     *  @since 0.9.16
     */
/****
    private static class FragmentComparator implements Comparator<Fragment>, Serializable {

        public int compare(Fragment l, Fragment r) {
            // reverse
            return r.state.fragmentSize(r.num) - l.state.fragmentSize(l.num);
        }
    }
****/

}
