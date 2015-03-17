package net.i2p.router.transport.udp;

import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
   
/**
 * Blocking thread to grab new messages off the outbound queue and
 * plopping them into our active pool.  
 *
 * WARNING - UNUSED since 0.6.1.11
 *
 */
class OutboundRefiller implements Runnable {
    private RouterContext _context;
    private Log _log;
    private OutboundMessageFragments _fragments;
    private MessageQueue _messages;
    private boolean _alive;
    // private Object _refillLock;
    
    public OutboundRefiller(RouterContext ctx, OutboundMessageFragments fragments, MessageQueue messages) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundRefiller.class);
        _fragments = fragments;
        _messages = messages;
        // _refillLock = this;
        _context.statManager().createRateStat("udp.timeToActive", "Message lifetime until it reaches the outbound fragment queue", "udp", UDPTransport.RATES);
    }
    
    public void startup() {
        _alive = true;
        I2PThread t = new I2PThread(this, "UDP outbound refiller", true);
        t.start();
    }
    public void shutdown() { _alive = false; }
     
    public void run() {
        while (_alive) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Check the fragments to see if we can add more...");
            boolean wantMore = _fragments.waitForMoreAllowed();
            if (wantMore) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Want more fragments...");
                OutNetMessage msg = _messages.getNext(-1);
                if (msg != null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("New message found to fragments: " + msg);
                    _context.statManager().addRateData("udp.timeToActive", msg.getLifetime(), msg.getLifetime());
                    _fragments.add(msg);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("No message found to fragment");
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("No more fragments allowed, looping");
            }
        }
    }
}
