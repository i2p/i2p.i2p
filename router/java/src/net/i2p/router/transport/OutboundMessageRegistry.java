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
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.i2p.data.Hash;
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
    /** Expiration date (Long) to OutNetMessage */
    private TreeMap _pendingMessages;
    private RouterContext _context;
    
    private final static long CLEANUP_DELAY = 1000*5; // how often to expire pending unreplied messages
    
    public OutboundMessageRegistry(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(OutboundMessageRegistry.class);
        _pendingMessages = new TreeMap();
        //_context.jobQueue().addJob(new CleanupPendingMessagesJob());
    }
    
    public void shutdown() {
        if (_log.shouldLog(Log.WARN)) {
            StringBuffer buf = new StringBuffer(1024);
            buf.append("Pending messages: ").append(_pendingMessages.size()).append("\n");
            for (Iterator iter = _pendingMessages.values().iterator(); iter.hasNext(); ) {
                buf.append(iter.next().toString()).append("\n\t");
            }
            _log.log(Log.WARN, buf.toString());
        }
    }
    
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
        ArrayList matches = new ArrayList(2);
        long beforeSync = _context.clock().now();

        Map messages = null;
        long matchTime = 0;
        long continueTime = 0;
        int numMessages = 0;
        long afterSync1 = 0;
        long afterSearch = 0;
        int matchedRemoveCount = 0;
        StringBuffer slow = null; // new StringBuffer(256);

        synchronized (_pendingMessages) {
            messages = _pendingMessages; //(Map)_pendingMessages.clone();
            
            numMessages = messages.size();
            afterSync1 = _context.clock().now();

            for (Iterator iter = messages.keySet().iterator(); iter.hasNext(); ) {
                Long exp = (Long)iter.next();
                OutNetMessage msg = (OutNetMessage)messages.get(exp);
                MessageSelector selector = msg.getReplySelector();
                if (selector != null) {
                    long before = _context.clock().now();
                    boolean isMatch = selector.isMatch(message);
                    long after = _context.clock().now();
                    long diff = after-before;
                    if (diff > 100) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Matching with selector took too long (" + diff + "ms) : " 
                                      + selector.getClass().getName());
                        if (slow == null) slow = new StringBuffer(256);
                        slow.append(selector.getClass().getName()).append(": ");
                        slow.append(diff).append(" ");
                    }
                    matchTime += diff;

                    if (isMatch) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Selector matches [" + selector);
                        if (!matches.contains(msg))
                            matches.add(msg);
                        long beforeCon = _context.clock().now();
                        boolean continueMatching = selector.continueMatching();
                        long afterCon = _context.clock().now();
                        long diffCon = afterCon - beforeCon;
                        if (diffCon > 100) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Error continueMatching on a match took too long (" 
                                          + diffCon + "ms) : " + selector.getClass().getName());
                        }
                        continueTime += diffCon;

                        if (continueMatching) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Continue matching");
                            // noop
                        } else {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Stop matching selector " + selector + " for message " 
                                           + msg.getMessageType());
                            
                            // i give in mihi, i'll use iter.remove just this once ;)
                            // (TreeMap supports it, and this synchronized block is a hotspot)
                            iter.remove();

                            matchedRemoveCount++;
                        }
                    } else {
                        //_log.debug("Selector does not match [" + selector + "]");
                    }
                }
            }
            afterSearch = _context.clock().now();
        }
        
        long delay = _context.clock().now() - beforeSync;
        long search = afterSearch - afterSync1;
        long sync = afterSync1 - beforeSync;

        int level = Log.DEBUG;
        if (delay > 1000)
            level = Log.ERROR;
        if (_log.shouldLog(level)) {
            StringBuffer buf = new StringBuffer(1024);
            buf.append("getMessages took ").append(delay).append("ms with search time of");
            buf.append(search).append("ms (match: ").append(matchTime).append("ms, continue: ");
            buf.append(continueTime).append("ms, #: ").append(numMessages).append(") and sync time of ");
            buf.append(sync).append("ms for ");
            buf.append(matchedRemoveCount);
            buf.append(" removed, ").append(matches.size()).append(" matches: slow = ");
            if (slow != null)
                buf.append(slow.toString());
            _log.log(level, buf.toString());
        }

        return matches;
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

    
    public void registerPending(OutNetMessage msg) {
        registerPending(msg, false);
    }
    public void registerPending(OutNetMessage msg, boolean allowEmpty) {
        if (msg == null)
            throw new IllegalArgumentException("Null OutNetMessage specified?  wtf");
        if (!allowEmpty) {
            if (msg.getMessage() == null)
                throw new IllegalArgumentException("OutNetMessage doesn't contain an I2NPMessage? wtf");
        }

        long beforeSync = _context.clock().now();
        long afterSync1 = 0;
        long afterDone = 0;
        try {
            OutNetMessage oldMsg = null;
            long l = msg.getExpiration();
            synchronized (_pendingMessages) {
                if (_pendingMessages.containsValue(msg)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Not adding an already pending message: " + msg, 
                                   new Exception("Duplicate message registration"));
                    return;
                }               

                while (_pendingMessages.containsKey(new Long(l)))
                    l++;
                _pendingMessages.put(new Long(l), msg);
            }
            afterSync1 = _context.clock().now();
            
            // this may get orphaned if the message is matched or explicitly
            // removed, but its cheap enough to do an extra remove on the map 
            // that to poll the list periodically
            SimpleTimer.getInstance().addEvent(new CleanupExpiredTask(l), l - _context.clock().now());

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Register pending: " + msg.getReplySelector().getClass().getName() 
                           + " for " + msg.getMessage() + ": " 
                           + msg.getReplySelector().toString(), new Exception("Register pending"));
            afterDone = _context.clock().now();
        } finally {
            long delay = _context.clock().now() - beforeSync;
            long sync1 = afterSync1 - beforeSync;
            long done = afterDone - afterSync1;
            String warn = delay + "ms (sync = " + sync1 + "ms, done = " + done + "ms)";
            if ( (delay > 1000) && (_log.shouldLog(Log.WARN)) ) {
                _log.error("Synchronizing in the registry.register took too long!  " + warn);
                //_context.messageHistory().messageProcessingError(msg.getMessage().getUniqueId(), 
                //                                                 msg.getMessage().getClass().getName(), 
                //                                                 "RegisterPending took too long: " + warn);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Synchronizing in the registry.register was quick:  " + warn);
            }
        }
        //_log.debug("* Register called of " + msg + "\n\nNow pending are: " + renderStatusHTML(), new Exception("who registered a new one?"));
    }
    
    public void unregisterPending(OutNetMessage msg) {
        long beforeSync = _context.clock().now();
        try {
            synchronized (_pendingMessages) {
                if (_pendingMessages.containsValue(msg)) {
                    Long found = null;
                    for (Iterator iter = _pendingMessages.keySet().iterator(); iter.hasNext();) {
                        Long exp = (Long)iter.next();
                        Object val = _pendingMessages.get(exp);
                        if (val.equals(msg)) {
                            found = exp;
                            break;
                        }
                    }
                    
                    if (found != null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Unregistered message " + msg.getReplySelector() 
                                       + ": " + msg, new Exception("Who unregistered?"));
                        _pendingMessages.remove(found);
                    } else {
                        _log.error("Arg, couldn't find the message that we... thought we could find?", 
                                   new Exception("WTF"));
                    }
                }
            }
        } finally {
            long delay = _context.clock().now() - beforeSync;
            String warn = delay + "ms";
            if ( (delay > 1000) && (_log.shouldLog(Log.WARN)) ) {
                _log.warn("Synchronizing in the registry.unRegister took too long!  " + warn);
                _context.messageHistory().messageProcessingError(msg.getMessageId(), msg.getMessageType(), "Unregister took too long: " + warn);
            } else {
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Synchronizing in the registry.unRegister was quick:  " + warn);
            }
        }
    }

    public void peerFailed(Hash peer) {
        List failed = null;
        int numFailed = 0;
        synchronized (_pendingMessages) {
            for (Iterator iter = _pendingMessages.values().iterator(); iter.hasNext(); ) {
                OutNetMessage msg = (OutNetMessage)iter.next();
                if (msg.getTarget() != null) {
                    Hash to = msg.getTarget().getIdentity().calculateHash();
                    if (to.equals(peer)) {
                        if (failed == null)
                            failed = new ArrayList(4);
                        failed.add(msg);
                        iter.remove();
                        numFailed++;
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Peer failed: " + peer.toBase64().substring(0,6) 
                                       + " but not killing a message to " 
                                       + to.toBase64().substring(0,6));
                    }
                }
            }
        }
        if (failed != null) {
            for (int i = 0; i < failed.size(); i++) {
                OutNetMessage msg = (OutNetMessage)failed.get(i);
                msg.discardData();
                if (msg.getOnFailedSendJob() != null)
                    _context.jobQueue().addJob(msg.getOnFailedSendJob());
            }
        }
        
        if (_log.shouldLog(Log.WARN))
            _log.warn("Peer failed: " + peer.toBase64().substring(0,6) + " killing " + numFailed);
    }
    
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuffer buf = new StringBuffer(8192);
        buf.append("<h2>Pending messages</h2>\n");
        Map msgs = null;
        synchronized (_pendingMessages) {
            msgs = (Map)_pendingMessages.clone();
        }
        buf.append("<ul>");
        for (Iterator iter = msgs.keySet().iterator(); iter.hasNext();) {
            Long exp = (Long)iter.next();
            OutNetMessage msg = (OutNetMessage)msgs.get(exp);
            buf.append("<li>").append(msg.getMessageType());
            buf.append(": expiring on ").append(new Date(exp.longValue()));
            if (msg.getTarget() != null)
                buf.append(" targetting ").append(msg.getTarget().getIdentity().getHash());
            if (msg.getReplySelector() != null)
                buf.append(" with reply selector ").append(msg.getReplySelector().toString());
            else
                buf.append(" with NO reply selector?  WTF!");
            buf.append("</li>\n");
        }
        buf.append("</ul>");
        out.write(buf.toString());
        out.flush();
    }
    
    private class CleanupExpiredTask implements SimpleTimer.TimedEvent {
        private long _expiration;
        public CleanupExpiredTask(long expiration) {
            _expiration = expiration;
        }
        public void timeReached() {
            OutNetMessage msg = null;
            synchronized (_pendingMessages) {
                msg = (OutNetMessage)_pendingMessages.remove(new Long(_expiration));
            }
            if (msg != null) {
                _context.messageHistory().replyTimedOut(msg);
                Job fail = msg.getOnFailedReplyJob();
                if (fail != null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing message with selector " + msg.getReplySelector() 
                                   + ": " + msg.getMessageType() 
                                   + " and firing fail job: " + fail.getClass().getName());
                    _context.jobQueue().addJob(fail);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Removing message with selector " + msg.getReplySelector() 
                                   + " and not firing any job");
                }
            }
        }
    }

    /**
     * Cleanup any messages that were pending replies but have expired
     *
     */
    /*
    private class CleanupPendingMessagesJob extends JobImpl { 
        public CleanupPendingMessagesJob() {
            super(OutboundMessageRegistry.this._context);
        }

        public String getName() { return "Cleanup any messages that timed out"; }
        
        public void runJob() {
            List removed = removeMessages();
            
            RouterContext ctx = OutboundMessageRegistry.this._context;
                
            for (int i = 0; i < removed.size(); i++) {
                OutNetMessage msg = (OutNetMessage)removed.get(i);

                if (msg != null) {
                    _context.messageHistory().replyTimedOut(msg);
                    Job fail = msg.getOnFailedReplyJob();
                    if (fail != null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Removing message with selector " + msg.getReplySelector() 
                                       + ": " + msg.getMessageType() 
                                       + " and firing fail job: " + fail.getClass().getName());
                        _context.jobQueue().addJob(fail);
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Removing message with selector " + msg.getReplySelector() 
                                       + " and not firing any job");
                    }
                }
            }

            requeue(CLEANUP_DELAY);
        }
        
        /**
         * Remove any messages whose expirations are in the past
         *
         * @return list of OutNetMessage objects that have expired
         */ /*
        private List removeMessages() {
            long now = OutboundMessageRegistry.this._context.clock().now();
            List removedMessages = new ArrayList(2);
            List expirationsToRemove = null;
            synchronized (_pendingMessages) {
                for (Iterator iter = _pendingMessages.keySet().iterator(); iter.hasNext();) {
                    Long expiration = (Long)iter.next();
                    if (expiration.longValue() < now) {
                        if (expirationsToRemove == null)
                            expirationsToRemove = new ArrayList(8);
                        expirationsToRemove.add(expiration);
                    } else {
                        // its sorted
                        break;
                    }
                }

                if (expirationsToRemove != null) {
                    for (int i = 0; i < expirationsToRemove.size(); i++) {
                        Long expiration = (Long)expirationsToRemove.get(i);
                        OutNetMessage msg = (OutNetMessage)_pendingMessages.remove(expiration);
                        if (msg != null)
                            removedMessages.add(msg);
                    }
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Removed " + removedMessages.size() + " messages");
            return removedMessages;
        }
    }
    */
}
