package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.MessageHistory;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.util.Clock;
import net.i2p.util.Log;

public class OutboundMessageRegistry {
    private final static Log _log = new Log(OutboundMessageRegistry.class);
    private static final OutboundMessageRegistry _instance = new OutboundMessageRegistry();
    public static OutboundMessageRegistry getInstance() { return _instance; }
    private TreeMap _pendingMessages;
    
    private final static long CLEANUP_DELAY = 1000*5; // how often to expire pending unreplied messages
    
    private OutboundMessageRegistry() {
	_pendingMessages = new TreeMap();
	JobQueue.getInstance().addJob(new CleanupPendingMessagesJob());
    }
    
    public List getOriginalMessages(I2NPMessage message) {
	
	HashSet matches = new HashSet(4);
	long beforeSync = Clock.getInstance().now();
	
	Map messages = null;
	synchronized (_pendingMessages) {
	    messages = (Map)_pendingMessages.clone();
	}
	
	long matchTime = 0;
	long continueTime = 0;
	int numMessages = messages.size();
	
	long afterSync1 = Clock.getInstance().now();
	
	ArrayList matchedRemove = new ArrayList(32);
	for (Iterator iter = messages.keySet().iterator(); iter.hasNext(); ) {
	    Long exp = (Long)iter.next();
	    OutNetMessage msg = (OutNetMessage)messages.get(exp);
	    MessageSelector selector = msg.getReplySelector();
	    if (selector != null) {
		long before = Clock.getInstance().now();
		boolean isMatch = selector.isMatch(message);
		long after = Clock.getInstance().now();
		long diff = after-before;
		if (diff > 100) {
		    if (_log.shouldLog(Log.WARN))
			_log.warn("Matching with selector took too long (" + diff + "ms) : " + selector.getClass().getName());
		}
		matchTime += diff;
		
		if (isMatch) {
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("Selector matches [" + selector);
		    matches.add(msg);
		    long beforeCon = Clock.getInstance().now();
		    boolean continueMatching = selector.continueMatching();
		    long afterCon = Clock.getInstance().now();
		    long diffCon = afterCon - beforeCon;
		    if (diffCon > 100) {
			if (_log.shouldLog(Log.WARN))
			    _log.warn("Error continueMatching on a match took too long (" + diffCon + "ms) : " + selector.getClass().getName());
		    }
		    continueTime += diffCon;
		    
		    if (continueMatching) {
			if (_log.shouldLog(Log.DEBUG))
			    _log.debug("Continue matching");
			// noop
		    } else {
			if (_log.shouldLog(Log.DEBUG))
			    _log.debug("Stop matching selector " + selector + " for message " + msg.getMessage().getClass().getName());
			matchedRemove.add(exp);
		    }
		} else {
		    //_log.debug("Selector does not match [" + selector + "]");
		}
	    }
	}
	long afterSearch = Clock.getInstance().now();
	
	for (Iterator iter = matchedRemove.iterator(); iter.hasNext(); ) {
	    Long expiration = (Long)iter.next();
	    OutNetMessage m = null;
	    long before = Clock.getInstance().now();
	    synchronized (_pendingMessages) {
		m = (OutNetMessage)_pendingMessages.remove(expiration);
	    }
	    long diff = Clock.getInstance().now() - before;
	    if (diff > 500)
		_log.error("Took too long syncing on remove (" + diff + "ms");
	    
	    if (m != null) {
		if (_log.shouldLog(Log.DEBUG))
		    _log.debug("Removing message with selector " + m.getReplySelector().getClass().getName() + " :" + m.getReplySelector().toString());
	    }
	}
	
	long delay = Clock.getInstance().now() - beforeSync;
	long search = afterSearch - afterSync1;
	long sync = afterSync1 - beforeSync;
	
	int level = Log.DEBUG;
	if (delay > 1000)
	    level = Log.ERROR;
	if (_log.shouldLog(level)) {
	    _log.log(level, "getMessages took " + delay + "ms with search time of " + search + "ms (match: " + matchTime + "ms, continue: " + continueTime + "ms, #: " + numMessages + ") and sync time of " + sync + "ms for " + matchedRemove.size() + " removed, " + matches.size() + " matches");
	}
	
	return new ArrayList(matches);
    }
    
    public void registerPending(OutNetMessage msg) {
	if (msg == null) {
	    throw new IllegalArgumentException("Null OutNetMessage specified?  wtf");
	} else if (msg.getMessage() == null) {
	    throw new IllegalArgumentException("OutNetMessage doesn't contain an I2NPMessage? wtf");
	}

	long beforeSync = Clock.getInstance().now();
	long afterSync1 = 0;
	long afterDone = 0;
	try {
	    OutNetMessage oldMsg = null;
	    synchronized (_pendingMessages) {
		if (_pendingMessages.containsValue(msg)) {
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("Not adding an already pending message: " + msg.getMessage().getUniqueId() + "\n: " + msg, new Exception("Duplicate message registration"));
		    return;
		}	
		
		long l = msg.getExpiration();
		while (_pendingMessages.containsKey(new Long(l)))
		    l++;
		_pendingMessages.put(new Long(l), msg);
	    }
	    afterSync1 = Clock.getInstance().now();
	    
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Register pending: " + msg.getReplySelector().getClass().getName() + " for " + msg.getMessage().getClass().getName() + ": " + msg.getReplySelector().toString(), new Exception("Register pending"));
	    afterDone = Clock.getInstance().now();
	} finally {
	    long delay = Clock.getInstance().now() - beforeSync;
	    long sync1 = afterSync1 - beforeSync;
	    long done = afterDone - afterSync1;
	    String warn = delay + "ms (sync = " + sync1 + "ms, done = " + done + "ms)";
	    if (delay > 1000) {
		_log.error("Synchronizing in the registry.register took too long!  " + warn);
		MessageHistory.getInstance().messageProcessingError(msg.getMessage().getUniqueId(), msg.getMessage().getClass().getName(), "RegisterPending took too long: " + warn);
	    } else {
		_log.debug("Synchronizing in the registry.register was quick:  " + warn);
	    }
	}
	//_log.debug("* Register called of " + msg + "\n\nNow pending are: " + renderStatusHTML(), new Exception("who registered a new one?"));
    }
    
    public void unregisterPending(OutNetMessage msg) {
	long beforeSync = Clock.getInstance().now();
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
			    _log.debug("Unregistered message " + msg.getReplySelector() + ": " + msg, new Exception("Who unregistered?"));
			_pendingMessages.remove(found);
		    } else {
			_log.error("Arg, couldn't find the message that we... thought we could find?", new Exception("WTF"));
		    }
		}
	    }
	} finally {
	    long delay = Clock.getInstance().now() - beforeSync;
	    String warn = delay + "ms";
	    if (delay > 1000) {
		_log.error("Synchronizing in the registry.unRegister took too long!  " + warn);
		MessageHistory.getInstance().messageProcessingError(msg.getMessage().getUniqueId(), msg.getMessage().getClass().getName(), "Unregister took too long: " + warn);
	    } else {
		_log.debug("Synchronizing in the registry.unRegister was quick:  " + warn);
	    }
	}
    }
    
    public String renderStatusHTML() {
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
	    buf.append("<li>").append(msg.getMessage().getClass().getName()).append(": expiring on ").append(new Date(exp.longValue()));
	    if (msg.getReplySelector() != null)
		buf.append(" with reply selector ").append(msg.getReplySelector().toString());
	    else
		buf.append(" with NO reply selector?  WTF!");
	    buf.append("</li>\n");
	}
	buf.append("</ul>");
	return buf.toString();
    }

    /**
     * Cleanup any messages that were pending replies but have expired
     *
     */
    private class CleanupPendingMessagesJob extends JobImpl { 
	public CleanupPendingMessagesJob() {
	    super();
	}

	public String getName() { return "Cleanup any messages that timed out"; }
	public void runJob() {
	    List toRemove = new ArrayList();
	    long now = Clock.getInstance().now();
	    Map messages = null;
	    synchronized (_pendingMessages) {
		messages = (Map)_pendingMessages.clone();
	    }
	    long afterCreate = Clock.getInstance().now();

	    for (Iterator iter = messages.keySet().iterator(); iter.hasNext(); ) {
		Long exp = (Long)iter.next();
		OutNetMessage msg = (OutNetMessage)messages.get(exp);
		if (msg.getExpiration() < now) {
		    toRemove.add(exp);
		}
	    }
	    long findRemove = Clock.getInstance().now();

	    long removeTime = 0;
	    long loopTime = 0;
	    
	    for (Iterator iter = toRemove.iterator(); iter.hasNext(); ) {
		long beforeRemove = Clock.getInstance().now();
		Long exp = (Long)iter.next();
		OutNetMessage msg = null;
		synchronized (_pendingMessages) {
		    msg = (OutNetMessage)_pendingMessages.remove(exp);
		}
		long afterRemove = Clock.getInstance().now();
		long diff = afterRemove - beforeRemove;
		
		if (diff > 500)
		    _log.error("Synchronize during remove took too long " + diff + "ms");
		removeTime += diff;
		
		if (msg != null) {
		    MessageHistory.getInstance().replyTimedOut(msg);
		    Job fail = msg.getOnFailedReplyJob();
		    if (fail != null) {
			if (_log.shouldLog(Log.DEBUG))
			    _log.debug("Removing message with selector " + msg.getReplySelector() + ": " + msg.getMessage().getClass().getName() + " and firing fail job: " + fail.getClass().getName());
			JobQueue.getInstance().addJob(fail);
		    } else {
			if (_log.shouldLog(Log.DEBUG))
			    _log.debug("Removing message with selector " + msg.getReplySelector() + " and not firing any job");
		    }
		}
		long doneLoop = Clock.getInstance().now();
		long ldiff = doneLoop - beforeRemove;
		if (ldiff > 500)
		    _log.error("Loop took too long [" + ldiff + "ms]");
		loopTime += ldiff;
	    }

	    long cleanupDelay = Clock.getInstance().now() - now;
	    long findTime = findRemove - afterCreate;
	    long syncTime = afterCreate - now;
	    String warn = cleanupDelay + "ms (syncTime = " + syncTime + "ms, findTime =" + findTime + "ms, removeTime = " + removeTime + "ms, loopTime = " + loopTime + ")";
	    if (cleanupDelay > 1000) {
		_log.error("Cleanup took too long! " + warn);
		// yes, the following is a kludge, as its not specific to a particular message but to a whole series of messages 
		MessageHistory.getInstance().messageProcessingError(-1, OutboundMessageRegistry.CleanupPendingMessagesJob.class.getName(), "Cleanup took too long: " + warn);
	    } else {
		_log.debug("Cleanup was quick: " + warn);
	    }
	    
	    requeue(CLEANUP_DELAY);
	}
    }
}
