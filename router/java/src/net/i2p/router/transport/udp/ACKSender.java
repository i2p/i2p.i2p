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
    private List _peersToACK;
    private boolean _alive;
    
    /** how frequently do we want to send ACKs to a peer? */
    static final int ACK_FREQUENCY = 200;
    
    public ACKSender(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(ACKSender.class);
        _transport = transport;
        _peersToACK = new ArrayList(4);
        _builder = new PacketBuilder(_context);
        _alive = true;
        _context.statManager().createRateStat("udp.sendACKCount", "how many ack messages were sent to a peer", "udp", new long[] { 60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.ackFrequency", "how long ago did we send an ACK to this peer?", "udp", new long[] { 60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.sendACKRemaining", "when we ack a peer, how many peers are left waiting to ack?", "udp", new long[] { 60*1000, 60*60*1000 });
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
    
    public void run() {
        while (_alive) {
            PeerState peer = null;
            long now = _context.clock().now();
            long remaining = -1;
            try {
                synchronized (_peersToACK) {
                    for (int i = 0; i < _peersToACK.size(); i++) {
                        PeerState cur = (PeerState)_peersToACK.get(i);
                        long delta = cur.getWantedACKSendSince() + ACK_FREQUENCY - now;
                        if ( (delta < 0) || (cur.unsentACKThresholdReached()) ) {
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
                List acks = peer.retrieveACKs();
                if ( (acks != null) && (acks.size() > 0) ) {
                    _context.statManager().addRateData("udp.sendACKCount", acks.size(), 0);
                    _context.statManager().addRateData("udp.sendACKRemaining", remaining, 0);
                    now = _context.clock().now();
                    _context.statManager().addRateData("udp.ackFrequency", now-lastSend, now-wanted);
                    _context.statManager().getStatLog().addData(peer.getRemoteHostString(), "udp.peer.sendACKCount", acks.size(), 0);
                    UDPPacket ack = _builder.buildACK(peer, acks);
                    ack.markType(1);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Sending ACK for " + acks);
                    _transport.send(ack);
                    
                    if (wanted == peer.getWantedACKSendSince()) {
                        // still packets left to be ACKed, since wanted time
                        // is reset by retrieveACKs when all of the IDs are
                        // removed
                        ackPeer(peer);
                    }
                }
            }
        }
    }
    
}
