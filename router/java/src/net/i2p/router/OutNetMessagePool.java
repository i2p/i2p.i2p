package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Comparator;

import net.i2p.util.Log;

/**
 * Maintain a pool of OutNetMessages destined for other routers, organized by
 * priority, expiring messages as necessary.  This pool is populated by anything
 * that wants to send a message, and the communication subsystem periodically 
 * retrieves messages for delivery.
 *
 */
public class OutNetMessagePool {
    private Log _log;
    private RouterContext _context;
    
    public OutNetMessagePool(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(OutNetMessagePool.class);
    }
    
    /**
     * Remove the highest priority message, or null if none are available.
     *
     */
    public OutNetMessage getNext() {
        return null;
    }
    
    /**
     * Add a new message to the pool
     *
     */
    public void add(OutNetMessage msg) {
        if (_log.shouldLog(Log.INFO))
                _log.info("Adding outbound message to " 
                          + msg.getTarget().getIdentity().getHash().toBase64().substring(0,6)
                          + " with id " + msg.getMessage().getUniqueId()
                          + " expiring on " + msg.getMessage().getMessageExpiration()
                          + " of type " + msg.getMessageType());
        
        boolean valid = validate(msg);
        if (!valid) return;
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
            _log.error("No target in the OutNetMessage: " + msg, new Exception("Definitely a fuckup"));
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
    
    /**
     * Clear any messages that have expired, enqueuing any appropriate jobs
     *
     */
    public void clearExpired() {
        // noop
    }
    
    /**
     * Retrieve the number of messages, regardless of priority.
     *
     */
    public int getCount() {  return 0; }
    
    /**
     * Retrieve the number of messages at the given priority.  This can be used for
     * subsystems that maintain a pool of messages to be sent whenever there is spare time, 
     * where all of these 'spare' messages are of the same priority.
     *
     */
    public int getCount(int priority) { return 0; }
    
    public void dumpPoolInfo() { return; }
    
    private static class ReverseIntegerComparator implements Comparator {
        public int compare(Object lhs, Object rhs) {
            if ( (lhs == null) || (rhs == null) ) return 0; // invalid, but never used
            if ( !(lhs instanceof Integer) || !(rhs instanceof Integer)) return 0; 
            Integer lv = (Integer)lhs;
            Integer rv = (Integer)rhs;
            return - (lv.compareTo(rv));
        }
    }
}
