package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;

import net.i2p.client.I2PClient;
import net.i2p.router.message.OutboundCache;
import net.i2p.router.message.OutboundClientMessageOneShotJob;
import net.i2p.util.Log;

/**
 * Manage all of the inbound and outbound client messages maintained by the router.
 * The ClientManager subsystem fetches messages from this for locally deliverable
 * messages and adds in remotely deliverable messages.  Remotely deliverable messages
 * are picked up by interested jobs and processed and transformed into an OutNetMessage
 * to be eventually placed in the OutNetMessagePool.
 *
 */
public class ClientMessagePool {
    private final Log _log;
    private final RouterContext _context;
    private final OutboundCache _cache;
    
    public ClientMessagePool(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(ClientMessagePool.class);
        _cache = new OutboundCache(_context);
        OutboundClientMessageOneShotJob.init(_context);
    }
  
    /**
     *  @since 0.8.8
     */
    public void shutdown() {
        _cache.clearAllCaches();
    }

    /**
     *  @since 0.8.8
     */
    public void restart() {
        shutdown();
    }

    /**
     * Add a new message to the pool.  The message can either be locally or 
     * remotely destined.
     *
     */
    public void add(ClientMessage msg) {
        add(msg, false);
    }
    /**
     * If we're coming from the client subsystem itself, we already know whether
     * the target is definitely remote and as such don't need to recheck 
     * ourselves, but if we aren't certain, we want it to check for us.
     *
     * @param isDefinitelyRemote true if we know for sure that the target is not local
     *
     */
    public void add(ClientMessage msg, boolean isDefinitelyRemote) {
        if ( !isDefinitelyRemote ||
             (_context.clientManager().isLocal(msg.getDestination())) ||
             (_context.clientManager().isLocal(msg.getDestinationHash())) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding message for local delivery");
            _context.clientManager().messageReceived(msg);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding message for remote delivery");
            OutboundClientMessageOneShotJob j = new OutboundClientMessageOneShotJob(_context, _cache, msg);
            if (true) // blocks the I2CP reader for a nontrivial period of time
                j.runJob();
            else
                _context.jobQueue().addJob(j);
        }
    }
    
/******
    private boolean isGuaranteed(ClientMessage msg) {
        Properties opts = null;
        if (msg.getSenderConfig() != null)
            opts = msg.getSenderConfig().getOptions();
        if (opts != null) {
            String val = opts.getProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_BEST_EFFORT);
            return val.equals(I2PClient.PROP_RELIABILITY_GUARANTEED);
        } else {
            return false;
        }
    }
******/
}
