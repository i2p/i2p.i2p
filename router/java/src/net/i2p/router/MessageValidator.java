package net.i2p.router;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Singleton to manage the logic (and historical data) to determine whether a message
 * is valid or not (meaning it isn't expired and hasn't already been received).  We'll
 * need a revamp once we start dealing with long message expirations (since it might
 * involve keeping a significant number of entries in memory), but that probably won't
 * be necessary until I2P 3.0.
 *
 */
public class MessageValidator {
    private Log _log;
    private RouterContext _context;
    /**
     * Expiration date (as a Long) to message id (as a Long).
     * The expiration date (key) must be unique, so on collision, increment the value.
     * This keeps messageIds around longer than they need to be, but hopefully not by much ;)
     *
     */
    private TreeMap _receivedIdExpirations;
    /** Message id (as a Long) */
    private Set _receivedIds;
    /** synchronize on this before adjusting the received id data */
    private Object _receivedIdLock;
    
    
    public MessageValidator(RouterContext context) {
        _log = context.logManager().getLog(MessageValidator.class);
        _receivedIdExpirations = new TreeMap();
        _receivedIds = new HashSet(32*1024);
        _receivedIdLock = new Object();
        _context = context;
    }
    
    
    /**
     * Determine if this message should be accepted as valid (not expired, not a duplicate)
     *
     * @return true if the message should be accepted as valid, false otherwise
     */
    public boolean validateMessage(long messageId, long expiration) {
        long now = _context.clock().now();
        if (now - Router.CLOCK_FUDGE_FACTOR >= expiration) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rejecting message " + messageId + " because it expired " + (now-expiration) + "ms ago");
            return false;
        }
        
        boolean isDuplicate = noteReception(messageId, expiration);
        if (isDuplicate) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rejecting message " + messageId + " because it is a duplicate", new Exception("Duplicate origin"));
            return false;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Accepting message " + messageId + " because it is NOT a duplicate", new Exception("Original origin"));
            return true;
        }
    }
    
    /**
     * Note that we've received the message (which has the expiration given).
     * This functionality will need to be reworked for I2P 3.0 when we take into
     * consideration messages with significant user specified delays (since we dont
     * want to keep an infinite number of messages in RAM, etc)
     *
     * @return true if we HAVE already seen this message, false if not
     */
    private boolean noteReception(long messageId, long messageExpiration) {
        Long id = new Long(messageId);
        synchronized (_receivedIdLock) {
            locked_cleanReceivedIds(_context.clock().now() - Router.CLOCK_FUDGE_FACTOR);
            if (_receivedIds.contains(id)) {
                return true;
            } else {
                long date = messageExpiration;
                while (_receivedIdExpirations.containsKey(new Long(date)))
                    date++;
                _receivedIdExpirations.put(new Long(date), id);
                _receivedIds.add(id);
                return false;
            }
        }
    }
    
    /**
     * Clean the ids that we no longer need to keep track of to prevent replay
     * attacks.
     *
     */
    private void cleanReceivedIds() {
        long now = _context.clock().now() - Router.CLOCK_FUDGE_FACTOR ;
        synchronized (_receivedIdLock) {
            locked_cleanReceivedIds(now);
        }
    }
    
    /**
     * Clean the ids that we no longer need to keep track of to prevent replay
     * attacks - only call this from within a block synchronized on the received ID lock.
     *
     */
    private void locked_cleanReceivedIds(long now) {
        Set toRemoveIds = new HashSet(4);
        Set toRemoveDates = new HashSet(4);
        for (Iterator iter = _receivedIdExpirations.keySet().iterator(); iter.hasNext(); ) {
            Long date = (Long)iter.next();
            if (date.longValue() <= now) {
                // no need to keep track of things in the past
                toRemoveDates.add(date);
                toRemoveIds.add(_receivedIdExpirations.get(date));
            } else {
                // the expiration is in the future, we still need to keep track of
                // it to prevent replays
                break;
            }
        }
        for (Iterator iter = toRemoveDates.iterator(); iter.hasNext(); )
            _receivedIdExpirations.remove(iter.next());
        for (Iterator iter = toRemoveIds.iterator(); iter.hasNext(); )
            _receivedIds.remove(iter.next());
        if (_log.shouldLog(Log.INFO))
            _log.info("Cleaned out " + toRemoveDates.size() + " expired messageIds, leaving " + _receivedIds.size() + " remaining");
    }
    
    void shutdown() {
        StringBuffer buf = new StringBuffer(1024);
        buf.append("Validated messages: ").append(_receivedIds.size());
        _log.log(Log.CRIT, buf.toString());
    }
}
