package net.i2p.router.transport.udp;

import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Blocking thread that pulls peers off the inboundFragment pool and
 * sends them any outstanding ACKs.  The logic of what peers get ACKed when
 * is determined by the {@link InboundMessageFragments#getNextPeerToACK }
 *
 */
public class ACKSender implements Runnable {
    private RouterContext _context;
    private Log _log;
    private InboundMessageFragments _fragments;
    private UDPTransport _transport;
    private PacketBuilder _builder;
    
    public ACKSender(RouterContext ctx, InboundMessageFragments fragments, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(ACKSender.class);
        _fragments = fragments;
        _transport = transport;
        _builder = new PacketBuilder(_context, _transport);
    }
    
    public void run() {
        while (_fragments.isAlive()) {
            PeerState peer = _fragments.getNextPeerToACK();
            if (peer != null) {
                List acks = peer.retrieveACKs();
                if ( (acks != null) && (acks.size() > 0) ) {
                    UDPPacket ack = _builder.buildACK(peer, acks);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Sending ACK for " + acks);
                    _transport.send(ack);
                }
            }
        }
    }
    
}
