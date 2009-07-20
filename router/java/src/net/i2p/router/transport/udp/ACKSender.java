package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Blocking thread that is given peers by the inboundFragment pool, sending out
 * any outstanding ACKs.  
 *
 */
public class ACKSender implements Runnable {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private PacketBuilder _builder;
    /** list of peers (PeerState) who we have received data from but not yet ACKed to */
    private final List _peersToACK;
    private boolean _alive;
    
    /** how frequently do we want to send ACKs to a peer? */
    static final int ACK_FREQUENCY = 500;
    
    public ACKSender(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(ACKSender.class);
        _transport = transport;
        _peersToACK = new ArrayList(4);
        _builder = new PacketBuilder(_context, transport);
        _alive = true;
        _context.statManager().createRateStat("udp.sendACKCount", "how many ack messages were sent to a peer", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.ackFrequency", "how long ago did we send an ACK to this peer?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendACKRemaining", "when we ack a peer, how many peers are left waiting to ack?", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.abortACK", "How often do we schedule up an ACK send only to find it had already been sent (through piggyback)?", "udp", UDPTransport.RATES);
    }
    
    public void ackPeer(PeerState peer) {
        synchronized (_peersToACK) {
            if (!_peersToACK.contains(peer))
                _peersToACK.add(peer);
            _peersToACK.notifyAll();
        }
    }
    
    public void startup() {
        _alive = true;
        I2PThread t = new I2PThread(this, "UDP ACK sender");
        t.setDaemon(true);
        t.start();
    }
    
    public void shutdown() { 
        _alive = false;
        synchronized (_peersToACK) {
            _peersToACK.clear();
            _peersToACK.notifyAll();
        }
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
        while (_alive) {
            PeerState peer = null;
            long now = _context.clock().now();
            long remaining = -1;
            try {
                synchronized (_peersToACK) {
                    for (int i = 0; i < _peersToACK.size(); i++) {
                        PeerState cur = (PeerState)_peersToACK.get(i);
                        long wanted = cur.getWantedACKSendSince();
                        long delta = wanted + ackFrequency(now-cur.getLastACKSend(), cur.getRTT()) - now;
                        if ( ( (wanted > 0) && (delta < 0) ) || (cur.unsentACKThresholdReached()) ) {
                            _peersToACK.remove(i);
                            peer = cur;
                            break;
                        } 
                    }
                    
                    if (peer == null) {
                        if (_peersToACK.size() <= 0)
                            _peersToACK.wait();
                        else
                            _peersToACK.wait(50);
                    } else {
                        remaining = _peersToACK.size();
                    }
                }
            } catch (InterruptedException ie) {}
                
            if (peer != null) {
                long lastSend = peer.getLastACKSend();
                long wanted = peer.getWantedACKSendSince();
                List ackBitfields = peer.retrieveACKBitfields(false);
                
                if (wanted < 0)
                    _log.error("wtf, why are we acking something they dont want?  remaining=" + remaining + ", peer=" + peer + ", bitfields=" + ackBitfields);
                
                if ( (ackBitfields != null) && (ackBitfields.size() > 0) ) {
                    _context.statManager().addRateData("udp.sendACKCount", ackBitfields.size(), 0);
                    if (remaining > 0)
                        _context.statManager().addRateData("udp.sendACKRemaining", remaining, 0);
                    now = _context.clock().now();
                    if (lastSend < 0)
                        lastSend = now - 1;
                    _context.statManager().addRateData("udp.ackFrequency", now-lastSend, now-wanted);
                    //_context.statManager().getStatLog().addData(peer.getRemoteHostId().toString(), "udp.peer.sendACKCount", ackBitfields.size(), 0);
                    UDPPacket ack = _builder.buildACK(peer, ackBitfields);
                    ack.markType(1);
                    ack.setFragmentCount(-1);
                    ack.setMessageType(42);
                    
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
