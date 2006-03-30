package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.*;

import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

public class OutboundMessageRegistry {
    private Log _log;
    /** list of currently active MessageSelector instances */
    private List _selectors;
    /** map of active MessageSelector to the OutNetMessage causing it (for quick removal) */
    private Map _selectorToMessage;
    /** set of active OutNetMessage (for quick removal and selector fetching) */
    private Set _activeMessages;
    private CleanupTask _cleanupTask;
    private RouterContext _context;
    
    public OutboundMessageRegistry(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(OutboundMessageRegistry.class);
        _selectors = new ArrayList(64);
        _selectorToMessage = new HashMap(64);
        _activeMessages = new HashSet(64);
        _cleanupTask = new CleanupTask();
    }
    
    public void shutdown() {}
    
    /**
     * Retrieve all messages that are waiting for the specified message.  In
     * addition, those matches may include instructions to either continue or not
     * continue waiting for further replies - if it should continue, the matched
     * message remains in the registry, but if it shouldn't continue, the matched
     * message is removed from the registry.  
     *
     * @param message Payload received that may be a reply to something we sent
     * @return List of OutNetMessage describing messages that were waiting for 
     *         the payload
     */
    public List getOriginalMessages(I2NPMessage message) {
        ArrayList matchedSelectors = null;
        ArrayList removedSelectors = null;
        long beforeSync = _context.clock().now();
        synchronized (_selectors) {
            for (int i = 0; i < _selectors.size(); i++) {
                MessageSelector sel = (MessageSelector)_selectors.get(i);
                boolean isMatch = sel.isMatch(message);
                if (isMatch) {
                    if (matchedSelectors == null) matchedSelectors = new ArrayList(1);
                    matchedSelectors.add(sel);
                    if (!sel.continueMatching()) {
                        if (removedSelectors == null) removedSelectors = new ArrayList(1);
                        removedSelectors.add(sel);
                        _selectors.remove(i);
                        i--;
                    }
                }
            }  
        }

        List rv = null;
        if (matchedSelectors != null) {
            rv = new ArrayList(matchedSelectors.size());
            for (int i = 0; i < matchedSelectors.size(); i++) {
                MessageSelector sel = (MessageSelector)matchedSelectors.get(i);
                boolean removed = false;
                OutNetMessage msg = null;
                synchronized (_selectorToMessage) {
                    if ( (removedSelectors != null) && (removedSelectors.contains(sel)) ) {
                        msg = (OutNetMessage)_selectorToMessage.remove(sel);
                        removed = true;
                    } else {
                        msg = (OutNetMessage)_selectorToMessage.get(sel);
                    }
                    if (msg != null)
                        rv.add(msg);
                }
                if (removed && msg != null) {
                    synchronized (_activeMessages) {
                        _activeMessages.remove(msg);
                    }
                }
            }
        } else {
            rv = Collections.EMPTY_LIST;
        }

        return rv;
    }
    
    public OutNetMessage registerPending(MessageSelector replySelector, ReplyJob onReply, Job onTimeout, int timeoutMs) {
        OutNetMessage msg = new OutNetMessage(_context);
        msg.setExpiration(_context.clock().now() + timeoutMs);
        msg.setOnFailedReplyJob(onTimeout);
        msg.setOnFailedSendJob(onTimeout);
        msg.setOnReplyJob(onReply);
        msg.setReplySelector(replySelector);
        registerPending(msg, true);
        return msg;
    }
    
    public void registerPending(OutNetMessage msg) { registerPending(msg, false); }
    public void registerPending(OutNetMessage msg, boolean allowEmpty) {
        if ( (!allowEmpty) && (msg.getMessage() == null) )
                throw new IllegalArgumentException("OutNetMessage doesn't contain an I2NPMessage? wtf");
        MessageSelector sel = msg.getReplySelector();
        if (sel == null) throw new IllegalArgumentException("No reply selector?  wtf");

        boolean alreadyPending = false;
        synchronized (_activeMessages) {
            if (!_activeMessages.add(msg))
                return; // dont add dups
        }
        synchronized (_selectorToMessage) { _selectorToMessage.put(sel, msg); }
        synchronized (_selectors) { _selectors.add(sel); }

        _cleanupTask.scheduleExpiration(sel);
    }
    
    public void unregisterPending(OutNetMessage msg) {
        MessageSelector sel = msg.getReplySelector();
        // remember, order matters
        synchronized (_selectors) { _selectors.add(sel); }
        synchronized (_selectorToMessage) { _selectorToMessage.put(sel, msg); }
        synchronized (_activeMessages) { _activeMessages.remove(msg); }
    }

    public void renderStatusHTML(Writer out) throws IOException {}
    
    private class CleanupTask implements SimpleTimer.TimedEvent {
        private List _removing;
        private long _nextExpire;
        public CleanupTask() {
            _removing = new ArrayList(4);
            _nextExpire = -1;
        }
        public void timeReached() {
            long now = _context.clock().now();
            synchronized (_selectors) {
                for (int i = 0; i < _selectors.size(); i++) {
                    MessageSelector sel = (MessageSelector)_selectors.get(i);
                    long expiration = sel.getExpiration();
                    if (expiration <= now) {
                        _removing.add(sel);
                        _selectors.remove(i);
                        i--;
                    } else if (expiration < _nextExpire || _nextExpire < now) {
                        _nextExpire = expiration;
                    }
                }
            }
            if (_removing.size() > 0) {
                for (int i = 0; i < _removing.size(); i++) {
                    MessageSelector sel = (MessageSelector)_removing.get(i);
                    OutNetMessage msg = null;
                    synchronized (_selectorToMessage) {
                        msg = (OutNetMessage)_selectorToMessage.remove(sel);
                    }
                    if (msg != null) {
                        synchronized (_activeMessages) {
                            _activeMessages.remove(msg);
                        }
                        Job fail = msg.getOnFailedReplyJob();
                        if (fail != null)
                            _context.jobQueue().addJob(fail);
                    }
                }
                _removing.clear();
            }

            if (_nextExpire <= now)
                _nextExpire = now + 10*1000;
            SimpleTimer.getInstance().addEvent(CleanupTask.this, _nextExpire - now);
        }
        public void scheduleExpiration(MessageSelector sel) {
            long now = _context.clock().now();
            if ( (_nextExpire <= now) || (sel.getExpiration() < _nextExpire) ) {
                _nextExpire = sel.getExpiration();
                SimpleTimer.getInstance().addEvent(CleanupTask.this, _nextExpire - now);
            }
        }
    }
}
