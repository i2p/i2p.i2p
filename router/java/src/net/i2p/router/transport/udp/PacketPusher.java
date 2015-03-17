package net.i2p.router.transport.udp;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
   
/**
 * Blocking thread to grab new packets off the outbound fragment
 * pool and toss 'em onto the outbound packet queue
 *
 */
class PacketPusher implements Runnable {
    // private RouterContext _context;
    private final Log _log;
    private final OutboundMessageFragments _fragments;
    private final UDPSender _sender;
    private volatile boolean _alive;
    
    public PacketPusher(RouterContext ctx, OutboundMessageFragments fragments, UDPSender sender) {
        // _context = ctx;
        _log = ctx.logManager().getLog(PacketPusher.class);
        _fragments = fragments;
        _sender = sender;
    }
    
    public void startup() {
        _alive = true;
        I2PThread t = new I2PThread(this, "UDP packet pusher", true);
        t.start();
    }
    
    public void shutdown() { _alive = false; }
     
    public void run() {
        while (_alive) {
            try {
                UDPPacket packets[] = _fragments.getNextVolley();
                if (packets != null) {
                    for (int i = 0; i < packets.length; i++) {
                        if (packets[i] != null) // null for ACKed fragments
                            //_sender.add(packets[i], 0); // 0 does not block //100); // blocks for up to 100ms
                            _sender.add(packets[i]);
                    }
                }
            } catch (Exception e) {
                _log.error("SSU Output Queue Error", e);
            }
        }
    }
}
