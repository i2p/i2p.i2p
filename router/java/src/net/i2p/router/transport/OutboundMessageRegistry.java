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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  Tracks outbound messages.
 */
public class OutboundMessageRegistry {
    private final Log _log;
    /** list of currently active MessageSelector instances */
    private final List<MessageSelector> _selectors;
    /** map of active MessageSelector to either an OutNetMessage or a List of OutNetMessages causing it (for quick removal) */
    private final Map<MessageSelector, Object> _selectorToMessage;
    /**
     *  set of active OutNetMessage (for quick removal and selector fetching)
     *  !! Really? seems only for dup detection in registerPending().
     *  Changed to concurrent, but it could perhaps be removed completely,
     *  It would seem difficult to add a dup since every OutNetMessage is different,
     *  and it's generally instantiated just before ctx.outNetMessagePool().add().
     *  But in TransportImpl.afterSend() it does requeue a previous ONM if allowRequeue=true.
     */
    private final Set<OutNetMessage> _activeMessages;
    private final CleanupTask _cleanupTask;
    private final RouterContext _context;
    
    public OutboundMessageRegistry(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(OutboundMessageRegistry.class);
        _selectors = new ArrayList<MessageSelector>(64);
        _selectorToMessage = new HashMap<MessageSelector, Object>(64);
        _activeMessages = new ConcurrentHashSet<OutNetMessage>(64);
        _cleanupTask = new CleanupTask();
    }
    
    /**
     *  Does something @since 0.8.8
     */
    public void shutdown() {
        synchronized (_selectors) {
            _selectors.clear();
        }
        synchronized (_selectorToMessage) { 
            _selectorToMessage.clear();
        }
        // Calling the fail job for every active message would
        // be way too much at shutdown/restart, right?
        _activeMessages.clear();
    }
    
    /**
     *  @since 0.8.8
     */
    public void restart() {
        shutdown();
    }
    
    /**
     * Retrieve all messages that are waiting for the specified message.  In
     * addition, those matches may include instructions to either continue or not
     * continue waiting for further replies - if it should continue, the matched
     * message remains in the registry, but if it shouldn't continue, the matched
     * message is removed from the registry.  
     *
     * This is called only by InNetMessagePool.
     *
     * TODO this calls isMatch() in the selectors from inside the lock, which
     * can lead to deadlocks if the selector does too much in isMatch().
     * Remove the lock if possible.
     *
     * @param message Payload received that may be a reply to something we sent
     * @return non-null List of OutNetMessage describing messages that were waiting for 
     *         the payload
     */
    @SuppressWarnings("unchecked")
    public List<OutNetMessage> getOriginalMessages(I2NPMessage message) {
        List<MessageSelector> matchedSelectors = null;
        List<MessageSelector> removedSelectors = null;

        synchronized (_selectors) {
            // ConcurrentModificationException - why?
            //for (Iterator<MessageSelector> iter = _selectors.iterator(); iter.hasNext(); ) {
            //    MessageSelector sel = iter.next();
            for (int i = 0; i < _selectors.size(); i++) {
                MessageSelector sel = _selectors.get(i);
                boolean isMatch = sel.isMatch(message);
                if (isMatch) {
                    if (matchedSelectors == null) matchedSelectors = new ArrayList<MessageSelector>(1);
                    matchedSelectors.add(sel);
                    if (!sel.continueMatching()) {
                        if (removedSelectors == null) removedSelectors = new ArrayList<MessageSelector>(1);
                        removedSelectors.add(sel);
                        //iter.remove();
                        _selectors.remove(i);
                        i--;
                    }
                }
            }  
        }

        List<OutNetMessage> rv;
        if (matchedSelectors != null) {
            rv = new ArrayList<OutNetMessage>(matchedSelectors.size());
            for (MessageSelector sel : matchedSelectors) {
                boolean removed = false;
                OutNetMessage msg = null;
                List<OutNetMessage> msgs = null;
                synchronized (_selectorToMessage) {
                    Object o = null;
                    if ( (removedSelectors != null) && (removedSelectors.contains(sel)) ) {
                        o = _selectorToMessage.remove(sel);
                        removed = true;
                    } else {
                        o = _selectorToMessage.get(sel);
                    }
                    
                    if (o instanceof OutNetMessage) {
                        msg = (OutNetMessage)o;
                        rv.add(msg);
                    } else if (o instanceof List) {
                        msgs = (List<OutNetMessage>)o;
                        rv.addAll(msgs);
                    }
                }
                if (removed) {
                    if (msg != null) {
                        _activeMessages.remove(msg);
                    } else if (msgs != null) {
                        _activeMessages.removeAll(msgs);
                    }
                }
            }
        } else {
            rv = Collections.emptyList();
        }

        return rv;
    }
    
    /**
     *  Registers a new, empty OutNetMessage, with the reply and timeout jobs specified.
     *  The onTimeout job is called at replySelector.getExpiration() (if no reply is received by then)
     *
     *  @param replySelector non-null; The same selector may be used for more than one message.
     *  @param onReply non-null
     *  @param onTimeout may be null
     *  @return a dummy OutNetMessage where getMessage() is null. Use it to call unregisterPending() later if desired.
     */
    public OutNetMessage registerPending(MessageSelector replySelector, ReplyJob onReply, Job onTimeout) {
        OutNetMessage msg = new OutNetMessage(_context);
        msg.setOnFailedReplyJob(onTimeout);
        msg.setOnReplyJob(onReply);
        msg.setReplySelector(replySelector);
        registerPending(msg, true);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Registered: " + replySelector + " with reply job " + onReply +
                       " and timeout job " + onTimeout);
        return msg;
    }
    
    /**
     *  Register the message. Each message must have a non-null
     *  selector at msg.getReplySelector().
     *  The same selector may be used for more than one message.
     *
     *  @param msg msg.getMessage() and msg.getReplySelector() must be non-null
     */
    public void registerPending(OutNetMessage msg) { registerPending(msg, false); }

    /**
     *  @param allowEmpty is msg.getMessage() allowed to be null?
     */
    @SuppressWarnings("unchecked")
    private void registerPending(OutNetMessage msg, boolean allowEmpty) {
        if ( (!allowEmpty) && (msg.getMessage() == null) )
                throw new IllegalArgumentException("OutNetMessage doesn't contain an I2NPMessage? Impossible?");
        MessageSelector sel = msg.getReplySelector();
        if (sel == null) throw new IllegalArgumentException("No reply selector? Impossible?");

        if (!_activeMessages.add(msg))
            return; // dont add dups

        synchronized (_selectorToMessage) { 
            Object oldMsg = _selectorToMessage.put(sel, msg);
            if (oldMsg != null) {
                List<OutNetMessage> multi = null;
                if (oldMsg instanceof OutNetMessage) {
                    //multi = Collections.synchronizedList(new ArrayList(4));
                    multi = new ArrayList<OutNetMessage>(4);
                    multi.add((OutNetMessage)oldMsg);
                    multi.add(msg);
                    _selectorToMessage.put(sel, multi);
                } else if (oldMsg instanceof List) {
                    multi = (List<OutNetMessage>)oldMsg;
                    multi.add(msg);
                    _selectorToMessage.put(sel, multi);
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("a single message selector [" + sel + "] with multiple messages ("+ multi + ")");
            }
        }
        synchronized (_selectors) { _selectors.add(sel); }

        _cleanupTask.scheduleExpiration(sel);
    }
    
    /**
     *  @param msg may be be null
     */
    @SuppressWarnings("unchecked")
    public void unregisterPending(OutNetMessage msg) {
        if (msg == null) return;
        MessageSelector sel = msg.getReplySelector();
        boolean stillActive = false;
        synchronized (_selectorToMessage) { 
            Object old = _selectorToMessage.remove(sel);
            if (old != null) {
                if (old instanceof List) {
                    List<OutNetMessage> l = (List<OutNetMessage>)old;
                    l.remove(msg);
                    if (!l.isEmpty()) {
                        _selectorToMessage.put(sel, l);
                        stillActive = true;
                    }
                }
            }
        }
        if (!stillActive)
            synchronized (_selectors) { _selectors.remove(sel); }
        _activeMessages.remove(msg);
    }

    /** @deprecated unused */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {}
    
    private class CleanupTask extends SimpleTimer2.TimedEvent {
        /** LOCKING: _selectors */
        private long _nextExpire;

        public CleanupTask() {
            super(_context.simpleTimer2());
            _nextExpire = -1;
        }

        @SuppressWarnings("unchecked")
        public void timeReached() {
            long now = _context.clock().now();
            List<MessageSelector> removing = new ArrayList<MessageSelector>(8);
            synchronized (_selectors) {
                // CME?
                //for (Iterator<MessageSelector> iter = _selectors.iterator(); iter.hasNext(); ) {
                //    MessageSelector sel = iter.next();
                for (int i = 0; i < _selectors.size(); i++) {
                    MessageSelector sel = _selectors.get(i);
                    long expiration = sel.getExpiration();
                    if (expiration <= now) {
                        removing.add(sel);
                        //iter.remove();
                        _selectors.remove(i);
                        i--;
                    } else if (expiration < _nextExpire || _nextExpire < now) {
                        _nextExpire = expiration;
                    }
                }
            }
            boolean log = _log.shouldLog(Log.DEBUG);
            if (!removing.isEmpty()) {
                for (MessageSelector sel : removing) {
                    OutNetMessage msg = null;
                    List<OutNetMessage> msgs = null;
                    synchronized (_selectorToMessage) {
                        Object o = _selectorToMessage.remove(sel);
                        if (o instanceof OutNetMessage) {
                            msg = (OutNetMessage)o;
                        } else if (o instanceof List) {
                            //msgs = new ArrayList((List)o);
                            msgs = (List<OutNetMessage>)o;
                        }
                    }
                    if (msg != null) {
                        _activeMessages.remove(msg);
                        Job fail = msg.getOnFailedReplyJob();
                        if (fail != null)
                            _context.jobQueue().addJob(fail);
                        if (log)
                            _log.debug("Expired: " + sel + " with timeout job " + fail);
                    } else if (msgs != null) {
                        _activeMessages.removeAll(msgs);
                        for (OutNetMessage m : msgs) {
                            Job fail = m.getOnFailedReplyJob();
                            if (fail != null)
                                _context.jobQueue().addJob(fail);
                            if (log)
                                _log.debug("Expired: " + sel + " with timeout job(s) " + fail);
                        }
                    } else {
                        if (log)
                            _log.debug("Expired: " + sel + " with no known messages");
                    }
                }
            }

            if (log) {
                int e = removing.size();
                int r;
                synchronized(_selectors) {
                    r = _selectors.size();
                }
                int a = _activeMessages.size();
                if (r > 0 || e > 0 || a > 0)
                    _log.debug("Expired: " + e + " remaining: " + r + " active: " + a);
            }
            synchronized(_selectors) {
                if (_nextExpire <= now)
                    _nextExpire = now + 10*1000;
                schedule(_nextExpire - now);
            }
        }

        public void scheduleExpiration(MessageSelector sel) {
            long now = _context.clock().now();
            synchronized(_selectors) {
                if ( (_nextExpire <= now) || (sel.getExpiration() < _nextExpire) ) {
                    _nextExpire = sel.getExpiration();
                    reschedule(_nextExpire - now);
                }
            }
        }
    }
}
