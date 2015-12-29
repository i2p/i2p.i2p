package net.i2p.router.transport.udp;

import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
   
/**
 * Blocking thread to grab new packets off the outbound fragment
 * pool and toss 'em onto the outbound packet queues.
 *
 * Here we select which UDPEndpoint/UDPSender to send it out.
 */
class PacketPusher implements Runnable {
    // private RouterContext _context;
    private final Log _log;
    private final OutboundMessageFragments _fragments;
    private final List<UDPEndpoint> _endpoints;
    private volatile boolean _alive;
    
    public PacketPusher(RouterContext ctx, OutboundMessageFragments fragments, List<UDPEndpoint> endpoints) {
        // _context = ctx;
        _log = ctx.logManager().getLog(PacketPusher.class);
        _fragments = fragments;
        _endpoints = endpoints;
    }
    
    public synchronized void startup() {
        _alive = true;
        I2PThread t = new I2PThread(this, "UDP packet pusher", true);
        t.start();
    }
    
    public synchronized void shutdown() { _alive = false; }
     
    public void run() {
        while (_alive) {
            try {
                List<UDPPacket> packets = _fragments.getNextVolley();
                if (packets != null) {
                    for (int i = 0; i < packets.size(); i++) {
                         send(packets.get(i));
                    }
                }
            } catch (RuntimeException e) {
                _log.error("SSU Output Queue Error", e);
            }
        }
    }

    /**
     *  This sends it directly out, bypassing OutboundMessageFragments
     *  and the PacketPusher. The only queueing is for the bandwidth limiter.
     *  BLOCKING if OB queue is full.
     *
     *  @param packet non-null
     *  @since IPv6
     */
    public void send(UDPPacket packet) {
        boolean isIPv4 = packet.getPacket().getAddress().getAddress().length == 4;
        for (int j = 0; j < _endpoints.size(); j++) {
            // Find the best endpoint (socket) to send this out.
            // TODO if we have multiple IPv4, or multiple IPv6 endpoints,
            // we have to track which one we're using in the PeerState and
            // somehow set that in the UDPPacket so we're consistent
            UDPEndpoint ep;
            try {
                ep = _endpoints.get(j);
            } catch (IndexOutOfBoundsException ioobe) {
                // whups, list changed
                break;
            }
            if ((isIPv4 && ep.isIPv4()) ||
                ((!isIPv4) && ep.isIPv6())) {
                // BLOCKING if queue is full
                ep.getSender().add(packet);
                return;
            }
        }
        // not handled
        _log.error("No endpoint to send " + packet);
        packet.release();
    }
}
