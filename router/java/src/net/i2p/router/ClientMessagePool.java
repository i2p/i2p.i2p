package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

//import net.i2p.router.message.ProcessOutboundClientMessageJob;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.i2p.router.message.OutboundClientMessageJob;
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
    private Log _log;
    private RouterContext _context;
    
    public ClientMessagePool(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(ClientMessagePool.class);
    }
  
    /**
     * Add a new message to the pool.  The message can either be locally or 
     * remotely destined.
     *
     */
    public void add(ClientMessage msg) {
        if ( (_context.clientManager().isLocal(msg.getDestination())) ||
             (_context.clientManager().isLocal(msg.getDestinationHash())) ) {
            _log.debug("Adding message for local delivery");
            _context.clientManager().messageReceived(msg);
        } else {
            _log.debug("Adding message for remote delivery");
            _context.jobQueue().addJob(new OutboundClientMessageJob(_context, msg));
        }
    }
}
