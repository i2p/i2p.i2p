package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.util.Log;

/**
 * Maintain a pool of OutNetMessages destined for other routers, organized by
 * priority, expiring messages as necessary.  This pool is populated by anything
 * that wants to send a message, and the communication subsystem periodically 
 * retrieves messages for delivery.
 *
 * Actually, this doesn't 'pool' anything, it calls the comm system directly.
 * Nor does it organize by priority. But perhaps it could someday.
 */
public class OutNetMessagePool {
    private final Log _log;
    private final RouterContext _context;
    
    public OutNetMessagePool(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(OutNetMessagePool.class);
    }
    
    /**
     * Add a new message to the pool
     *
     */
    public void add(OutNetMessage msg) {
        boolean valid = validate(msg);
        if (!valid) {
            _context.messageRegistry().unregisterPending(msg);
            return;
        }        
        
        if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding outbound message to " 
                          + msg.getTarget().getIdentity().getHash().toBase64().substring(0,6)
                          + " with id " + msg.getMessage().getUniqueId()
                          + " expiring on " + msg.getMessage().getMessageExpiration()
                          + " of type " + msg.getMessageType());
        
        MessageSelector selector = msg.getReplySelector();
        if (selector != null) {
            _context.messageRegistry().registerPending(msg);
        }
        _context.commSystem().processMessage(msg);
        return;
    }
    
    private boolean validate(OutNetMessage msg) {
        if (msg == null) return false;
        if (msg.getMessage() == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Null message in the OutNetMessage - expired too soon");
            return false;
        }
        if (msg.getTarget() == null) {
            _log.error("No target in the OutNetMessage: " + msg, new Exception());
            return false;
        }
        if (msg.getPriority() < 0) {
            _log.warn("Priority less than 0?  sounds like nonsense to me... " + msg, new Exception("Negative priority"));
            return false;
        }
        if (msg.getExpiration() <= _context.clock().now()) {
            _log.error("Already expired!  wtf: " + msg, new Exception("Expired message"));
            return false;
        }
        return true;
    }
}
