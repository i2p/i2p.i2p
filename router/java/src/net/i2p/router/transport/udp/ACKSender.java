package net.i2p.router.transport.udp;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Blocking thread that is given peers by the inboundFragment pool, sending out
 * any outstanding ACKs.  
 * The ACKs are sent directly to UDPSender,
 * bypassing OutboundMessageFragments and PacketPusher.
 */
class ACKSender implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final PacketBuilder _builder;
    /** list of peers (PeerState) who we have received data from but not yet ACKed to */
    private final BlockingQueue<PeerState> _peersToACK;
    private boolean _alive;
    private static final long POISON_PS = -9999999999l;
    
    /** how frequently do we want to send ACKs to a peer? */
    static final int ACK_FREQUENCY = 500;
    
    public ACKSender(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(ACKSender.class);
        _transport = transport;
        _peersToACK = new LinkedBlockingQueue();
        _builder = new PacketBuilder(_context, transport);
        _alive = true;
        _context.statManager().createRateStat("udp.sendACKCount", "how many ack messages were sent to a peer", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.ackFrequency", "how long ago did we send an ACK to this peer?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendACKRemaining", "when we ack a peer, how many peers are left waiting to ack?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.abortACK", "How often do we schedule up an ACK send only to find it had already been sent (through piggyback)?", "udp", UDPTransport.RATES);
    }
    
    /**
     *  Add to the queue.
     *  For speed, don't check for duplicates here.
     *  The runner will remove them in its own thread.
     */
    public void ackPeer(PeerState peer) {
        if (_alive)
            _peersToACK.offer(peer);
    }
    
    public void startup() {
        _alive = true;
        _peersToACK.clear();
        I2PThread t = new I2PThread(this, "UDP ACK sender", true);
        t.start();
    }
    
    public void shutdown() { 
        _alive = false;
        PeerState poison = new PeerState(_context, _transport, null, 0, null, false);
        poison.setTheyRelayToUsAs(POISON_PS);
        _peersToACK.offer(poison);
        for (int i = 1; i <= 5 && !_peersToACK.isEmpty(); i++) {
            try {
                Thread.sleep(i * 50);
            } catch (InterruptedException ie) {}
        }
        _peersToACK.clear();
    }
    
    private long ackFrequency(long timeSinceACK, long rtt) {
        // if we are actively pumping lots of data to them, we can depend upon
        // the unsentACKThreshold to figure out when to send an ACK instead of
        // using the timer, so we can set the timeout/frequency higher
        if (timeSinceACK < 2*1000)
            return Math.max(rtt/2, 500);
        else
            return ACK_FREQUENCY;
    }
    
    public void run() {

        // we use a Set to strip out dups that come in on the Queue
        Set<PeerState> notYet = new HashSet();
        while (_alive) {
            PeerState peer = null;
            long now = 0;
            long remaining = -1;
            long wanted = 0;

                while (_alive) {
                    // Pull from the queue until we find one ready to ack
                    // Any that are not ready we will put back on the queue
                    PeerState cur = null;
                    try {
                        if (notYet.isEmpty())
                            // wait forever
                            cur = _peersToACK.take();
                        else
                            // Don't wait if nothing there, just put everybody back and sleep below
                            cur = _peersToACK.poll();
                    } catch (InterruptedException ie) {}

                    if (cur != null) {
                        if (cur.getTheyRelayToUsAs() == POISON_PS)
                            return;
                        wanted = cur.getWantedACKSendSince();
                        now = _context.clock().now();
                        long delta = wanted + ackFrequency(now-cur.getLastACKSend(), cur.getRTT()) - now;
                        if (wanted <= 0) {
                            // it got acked by somebody - discard, remove any dups, and go around again
                            notYet.remove(cur);
                        } else if ( (delta <= 0) || (cur.unsentACKThresholdReached()) ) {
                            // found one to ack
                            peer = cur;
                            notYet.remove(cur); // in case a dup
                            try {
                                // bulk operations may throw an exception
                                _peersToACK.addAll(notYet);
                            } catch (NoSuchElementException nsee) {}
                            notYet.clear();
                            break;
                        } else { 
                            // not yet, go around again
                            // moving from the Queue to the Set and then back removes duplicates
                            boolean added = notYet.add(cur);
                            if (added && _log.shouldLog(Log.DEBUG))
                                _log.debug("Pending ACK (delta = " + delta + ") for " + cur);
                        } 
                    } else if (!notYet.isEmpty()) {
                        // put them all back and wait a while
                        try {
                            // bulk operations may throw an exception
                            _peersToACK.addAll(notYet);
                        } catch (Exception e) {}
                        if (_log.shouldLog(Log.INFO))
                            _log.info("sleeping, pending size = " + notYet.size());
                        notYet.clear();
                        try {
                            // sleep a little longer than the divided frequency,
                            // so it will be ready after we circle around a few times
                            Thread.sleep(5 + (ACK_FREQUENCY / 3));
                        } catch (InterruptedException ie) {}
                    } // else go around again where we will wait at take()
                } // inner while()
                    
            if (peer != null) {
                long lastSend = peer.getLastACKSend();
                // set above before the break
                //long wanted = peer.getWantedACKSendSince();
                List<ACKBitfield> ackBitfields = peer.retrieveACKBitfields(false);
                
                if (wanted < 0) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("wtf, why are we acking something they dont want?  remaining=" + remaining + ", peer=" + peer + ", bitfields=" + ackBitfields);
                    continue;
                }
                
                if (!ackBitfields.isEmpty()) {
                    _context.statManager().addRateData("udp.sendACKCount", ackBitfields.size(), 0);
                    if (remaining > 0)
                        _context.statManager().addRateData("udp.sendACKRemaining", remaining, 0);
                    // set above before the break
                    //now = _context.clock().now();
                    if (lastSend < 0)
                        lastSend = now - 1;
                    _context.statManager().addRateData("udp.ackFrequency", now-lastSend, now-wanted);
                    //_context.statManager().getStatLog().addData(peer.getRemoteHostId().toString(), "udp.peer.sendACKCount", ackBitfields.size(), 0);
                    UDPPacket ack = _builder.buildACK(peer, ackBitfields);
                    ack.markType(1);
                    ack.setFragmentCount(-1);
                    ack.setMessageType(PacketBuilder.TYPE_ACK);
                    
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Sending ACK for " + ackBitfields);
                    boolean ok = peer.allocateSendingBytes(ack.getPacket().getLength(), true);
                    // ignore whether its ok or not, its a bloody ack.  this should be fixed, probably.
                    _transport.send(ack);
                    
                    if ( (wanted > 0) && (wanted <= peer.getWantedACKSendSince()) ) {
                        // still full packets left to be ACKed, since wanted time
                        // is reset by retrieveACKBitfields when all of the IDs are
                        // removed
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Rerequesting ACK for peer " + peer);
                        ackPeer(peer);
                    }
                } else {
                    _context.statManager().addRateData("udp.abortACK", 1, 0);
                }
            }
        }
    }
}
