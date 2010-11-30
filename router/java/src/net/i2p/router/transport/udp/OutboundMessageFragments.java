package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
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
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    // private ActiveThrottle _throttle; // LINT not used ??
    /** peers we are actively sending messages to */
    private final List<PeerState> _activePeers;
    private boolean _alive;
    /** which peer should we build the next packet out of? */
    private int _nextPeer;
    private PacketBuilder _builder;
    private long _lastCycleTime = System.currentTimeMillis();

    /** if we can handle more messages explicitly, set this to true */
    // private boolean _allowExcess; // LINT not used??
    // private volatile long _packetsRetransmitted; // LINT not used??

    // private static final int MAX_ACTIVE = 64; // not used.
    // don't send a packet more than 10 times
    static final int MAX_VOLLEYS = 10;

    public OutboundMessageFragments(RouterContext ctx, UDPTransport transport, ActiveThrottle throttle) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundMessageFragments.class);
        _transport = transport;
        // _throttle = throttle;
        _activePeers = new ArrayList(256);
        _builder = new PacketBuilder(ctx, transport);
        _alive = true;
        // _allowExcess = false;
        _context.statManager().createRateStat("udp.sendVolleyTime", "Long it takes to send a full volley", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendConfirmTime", "How long it takes to send a message and get the ACK", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendConfirmFragments", "How many fragments are included in a fully ACKed message", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendConfirmVolley", "How many times did fragments need to be sent before ACK", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendFailed", "How many sends a failed message was pushed", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendAggressiveFailed", "How many volleys was a packet sent before we gave up", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.outboundActiveCount", "How many messages are in the peer's active pool", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendRejected", "What volley are we on when the peer was throttled (time == message lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.partialACKReceived", "How many fragments were partially ACKed (time == message lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendSparse", "How many fragments were partially ACKed and hence not resent (time == message lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPiggyback", "How many acks were piggybacked on a data packet (time == message lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendPiggybackPartial", "How many partial acks were piggybacked on a data packet (time == message lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.packetsRetransmitted", "Lifetime of packets during their retransmission (period == packets transmitted, lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.peerPacketsRetransmitted", "How many packets have been retransmitted to the peer (lifetime) when a burst of packets are retransmitted (period == packets transmitted, lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.blockedRetransmissions", "How packets have been transmitted to the peer when we blocked a retransmission to them?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendCycleTime", "How long it takes to cycle through all of the active messages?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendCycleTimeSlow", "How long it takes to cycle through all of the active messages, when its going slowly?", "udp", UDPTransport.RATES);
    }

    public void startup() { _alive = true; }
    public void shutdown() {
        _alive = false;
        synchronized (_activePeers) {
            _activePeers.notifyAll();
        }
    }
    void dropPeer(PeerState peer) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Dropping peer " + peer.getRemotePeer().toBase64());
        peer.dropOutbound();
        synchronized (_activePeers) {
            _activePeers.remove(peer);
            _activePeers.notifyAll();
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
        if (true) return true;
        /*

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
         */
        return false;
    }

    /**
     * Add a new message to the active pool
     *
     */
    public void add(OutNetMessage msg) {
        I2NPMessage msgBody = msg.getMessage();
        RouterInfo target = msg.getTarget();
        if ( (msgBody == null) || (target == null) )
            return;

        // todo: make sure the outNetMessage is initialzed once and only once
        OutboundMessageState state = new OutboundMessageState(_context);
        boolean ok = state.initialize(msg, msgBody);
        if (ok) {
            PeerState peer = _transport.getPeerState(target.getIdentity().calculateHash());
            if (peer == null) {
                _transport.failed(msg, "Peer disconnected quickly");
                state.releaseResources();
                return;
            }
            int active = peer.add(state);
            synchronized (_activePeers) {
                if (!_activePeers.contains(peer)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Add a new message to a new peer " + peer.getRemotePeer().toBase64());
                    _activePeers.add(peer);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Add a new message to an existing peer " + peer.getRemotePeer().toBase64());
                }
                _activePeers.notifyAll();
            }
            //msg.timestamp("made active along with: " + active);
            _context.statManager().addRateData("udp.outboundActiveCount", active, 0);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error initializing " + msg);
        }
        //finishMessages();
    }

    /**
     * short circuit the OutNetMessage, letting us send the establish
     * complete message reliably
     */
    public void add(OutboundMessageState state) {
        PeerState peer = state.getPeer();
        if (peer == null)
            throw new RuntimeException("wtf, null peer for " + state);
        int active = peer.add(state);
        synchronized (_activePeers) {
            if (!_activePeers.contains(peer)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Add a new message to a new peer " + peer.getRemotePeer().toBase64());
                if (_activePeers.isEmpty())
                    _lastCycleTime = System.currentTimeMillis();
                _activePeers.add(peer);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Add a new message to an existing peer " + peer.getRemotePeer().toBase64());
            }
            _activePeers.notifyAll();
        }
        _context.statManager().addRateData("udp.outboundActiveCount", active, 0);
        // should we finish messages here too?
        /*
        synchronized (_activeMessages) {
            _activeMessages.add(state);
            if (_activeMessages.size() == 1)
                _lastCycleTime = System.currentTimeMillis();
            _activeMessages.notifyAll();
        }
         */
    }

    /**
     * Remove any expired or complete messages
     */
    private void finishMessages() {
        int rv = 0;
        List peers = null;
        synchronized (_activePeers) {
            peers = new ArrayList(_activePeers.size());
            for (int i = 0; i < _activePeers.size(); i++) {
                PeerState state = _activePeers.get(i);
                if (state.getOutboundMessageCount() <= 0) {
                    _activePeers.remove(i);
                    i--;
                } else {
                    peers.add(state);
                }
            }
            _activePeers.notifyAll();
        }
        for (int i = 0; i < peers.size(); i++) {
            PeerState state = (PeerState)peers.get(i);
            int remaining = state.finishMessages();
            if (remaining <= 0) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("No more pending messages for " + state.getRemotePeer().toBase64());
            }
            rv += remaining;
        }
    }

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
            int nextSendDelay = -1;
            finishMessages();
            try {
                synchronized (_activePeers) {
                    for (int i = 0; i < _activePeers.size(); i++) {
                        int cur = (i + _nextPeer) % _activePeers.size();
                        if (cur == 0) {
                            // FIXME or delete, these stats aren't much help since they include the sleep time
                            long ts = System.currentTimeMillis();
                            long cycleTime = ts - _lastCycleTime;
                            _lastCycleTime = ts;
                            _context.statManager().addRateData("udp.sendCycleTime", cycleTime, _activePeers.size());
                            // make longer than the default sleep time below
                            if (cycleTime > 1100)
                                _context.statManager().addRateData("udp.sendCycleTimeSlow", cycleTime, _activePeers.size());
                        }
                        peer = _activePeers.get(i);
                        state = peer.allocateSend();
                        if (state != null) {
                            // we have something to send and we will be returning it
                            _nextPeer = i + 1;
                            break;
                        } else {
                            // Update the minimum delay for all peers (getNextDelay() returns 1 for "now")
                            // which will be used if we found nothing to send across all peers
                            int delay = peer.getNextDelay();
                            if ( (nextSendDelay <= 0) || (delay < nextSendDelay) )
                                nextSendDelay = delay;
                            peer = null;
                            state = null;
                        }
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Done looping, next peer we are sending for: " +
                                   (peer != null ? peer.getRemotePeer().toBase64() : "none"));
                    if (state == null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("wait for " + nextSendDelay);
                        // wait.. or somethin'
                        // wait a min of 10 and a max of 3000 ms no matter what peer.getNextDelay() says
                        if (nextSendDelay > 0)
                            _activePeers.wait(Math.min(Math.max(nextSendDelay, 10), 3000));
                        else
                            _activePeers.wait(1000);
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("dont wait: alive=" + _alive + " state = " + state);
                    }
                }
            } catch (InterruptedException ie) {
                // noop
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Woken up while waiting");
            }
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending " + state);

        UDPPacket packets[] = preparePackets(state, peer);
        if ( (state != null) && (state.getMessage() != null) ) {
            int valid = 0;
            for (int i = 0; packets != null && i < packets.length ; i++)
                if (packets[i] != null)
                    valid++;
            /*
            state.getMessage().timestamp("sending a volley of " + valid
                                         + " lastReceived: "
                                         + (_context.clock().now() - peer.getLastReceiveTime())
                                         + " lastSentFully: "
                                         + (_context.clock().now() - peer.getLastSendFullyTime()));
            */
        }
        return packets;
    }

    private UDPPacket[] preparePackets(OutboundMessageState state, PeerState peer) {
        if ( (state != null) && (peer != null) ) {
            int fragments = state.getFragmentCount();
            if (fragments < 0)
                return null;

            // ok, simplest possible thing is to always tack on the bitfields if
            List<Long> msgIds = peer.getCurrentFullACKs();
            if (msgIds == null) msgIds = new ArrayList();
            List<ACKBitfield> partialACKBitfields = new ArrayList();
            peer.fetchPartialACKs(partialACKBitfields);
            int piggybackedPartialACK = partialACKBitfields.size();
            // getCurrentFullACKs() already makes a copy, do we need to copy again?
            List<Long> remaining = new ArrayList(msgIds);
            int sparseCount = 0;
            UDPPacket rv[] = new UDPPacket[fragments]; //sparse
            for (int i = 0; i < fragments; i++) {
                if (state.needsSending(i)) {
                    try {
                        rv[i] = _builder.buildPacket(state, i, peer, remaining, partialACKBitfields);
                    } catch (ArrayIndexOutOfBoundsException aioobe) {
                        _log.log(Log.CRIT, "Corrupt trying to build a packet - please tell jrandom: " +
                                 partialACKBitfields + " / " + remaining + " / " + msgIds);
                        sparseCount++;
                        continue;
                    }
                    if (rv[i] == null) {
                        sparseCount++;
                        continue;
                    }
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
                    Long id = msgIds.get(i);
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
            if (state.getPushCount() > 1) {
                int toSend = fragments-sparseCount;
                peer.messageRetransmitted(toSend);
                // _packetsRetransmitted += toSend; // lifetime for the transport
                _context.statManager().addRateData("udp.peerPacketsRetransmitted", peer.getPacketsRetransmitted(), peer.getPacketsTransmitted());
                _context.statManager().addRateData("udp.packetsRetransmitted", state.getLifetime(), peer.getPacketsTransmitted());
                if (_log.shouldLog(Log.INFO))
                    _log.info("Retransmitting " + state + " to " + peer);
                _context.statManager().addRateData("udp.sendVolleyTime", state.getLifetime(), toSend);
            }
            return rv;
        } else {
            // !alive
            return null;
        }
    }

    /**
     * We received an ACK of the given messageId from the given peer, so if it
     * is still unacked, mark it as complete.
     *
     * @return fragments acked
     */
    public int acked(long messageId, Hash ackedBy) {
        PeerState peer = _transport.getPeerState(ackedBy);
        if (peer != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("acked [" + messageId + "] by " + ackedBy.toBase64());
            return peer.acked(messageId);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("acked [" + messageId + "] by an unknown remote peer?  " + ackedBy.toBase64());
            return 0;
        }
    }

    public void acked(ACKBitfield bitfield, Hash ackedBy) {
        PeerState peer = _transport.getPeerState(ackedBy);
        if (peer != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("partial acked [" + bitfield + "] by " + ackedBy.toBase64());
            peer.acked(bitfield);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("partial acked [" + bitfield + "] by an unknown remote peer?  " + ackedBy.toBase64());
        }
    }

    public interface ActiveThrottle {
        public void choke(Hash peer);
        public void unchoke(Hash peer);
        public boolean isChoked(Hash peer);
    }
}
