package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

import net.i2p.router.transport.OutboundMessageRegistry;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Maintain a pool of OutNetMessages destined for other routers, organized by
 * priority, expiring messages as necessary.  This pool is populated by anything
 * that wants to send a message, and the communication subsystem periodically 
 * retrieves messages for delivery.
 *
 */
public class OutNetMessagePool {
    private final static Log _log = new Log(OutNetMessagePool.class);
    private static OutNetMessagePool _instance = new OutNetMessagePool();
    public static OutNetMessagePool getInstance() { return _instance; } 
    private TreeMap _messageLists; // priority --> List of OutNetMessage objects, where HIGHEST priority first
    
    private OutNetMessagePool() {
	_messageLists = new TreeMap(new ReverseIntegerComparator());
    }
    
    /**
     * Remove the highest priority message, or null if none are available.
     *
     */
    public OutNetMessage getNext() {
	synchronized (_messageLists) {
	    if (_messageLists.size() <= 0) return null;
	    for (Iterator iter = _messageLists.keySet().iterator(); iter.hasNext(); ) {
		Integer priority = (Integer)iter.next();
		List messages = (List)_messageLists.get(priority);
		if (messages.size() > 0) {
		    _log.debug("Found a message of priority " + priority);
		    return (OutNetMessage)messages.remove(0);
		}
	    }
	    // no messages of any priority
	    return null;
	}
    }
    
    /**
     * Add a new message to the pool
     *
     */
    public void add(OutNetMessage msg) {
	boolean valid = validate(msg);
	if (!valid) return;
	if (true) { // skip the pool
	    MessageSelector selector = msg.getReplySelector();
	    if (selector != null) {
		OutboundMessageRegistry.getInstance().registerPending(msg);
	    }
	    CommSystemFacade.getInstance().processMessage(msg);
	    return;
	}
    
	synchronized (_messageLists) {
	    Integer pri = new Integer(msg.getPriority());
	    if ( (_messageLists.size() <= 0) || (!_messageLists.containsKey(pri)) )
		_messageLists.put(new Integer(msg.getPriority()), new ArrayList(32));
	    List messages = (List)_messageLists.get(pri);
	    messages.add(msg);
	}
    }
    
    private boolean validate(OutNetMessage msg) {
	if (msg == null) return false;
	if (msg.getMessage() == null) {
	    _log.error("Null message in the OutNetMessage: " + msg, new Exception("Someone fucked up"));
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
	if (msg.getExpiration() <= Clock.getInstance().now()) {
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
	long now = Clock.getInstance().now();
	List jobsToEnqueue = new ArrayList();
	synchronized (_messageLists) {
	    for (Iterator iter = _messageLists.values().iterator(); iter.hasNext();) {
		List toRemove = new ArrayList();
		List messages = (List)iter.next();
		for (Iterator msgIter = messages.iterator(); msgIter.hasNext(); ) {
		    OutNetMessage msg = (OutNetMessage)msgIter.next();
		    if (msg.getExpiration() <= now) {
			_log.warn("Outbound network message expired: " + msg);
			toRemove.add(msg);
			jobsToEnqueue.add(msg.getOnFailedSendJob());
		    }
		}
		messages.removeAll(toRemove);
	    }
	}
	for (int i = 0; i < jobsToEnqueue.size(); i++) {
	    Job j = (Job)jobsToEnqueue.get(i);
	    JobQueue.getInstance().addJob(j);
	}
    }
    
    /**
     * Retrieve the number of messages, regardless of priority.
     *
     */
    public int getCount() { 
	int size = 0;
	synchronized (_messageLists) {
	    for (Iterator iter = _messageLists.values().iterator(); iter.hasNext(); ) {
		List lst = (List)iter.next();
		size += lst.size();
	    }
	}
	return size;
    }
    
    /**
     * Retrieve the number of messages at the given priority.  This can be used for
     * subsystems that maintain a pool of messages to be sent whenever there is spare time, 
     * where all of these 'spare' messages are of the same priority.
     *
     */
    public int getCount(int priority) {
	synchronized (_messageLists) {
	    Integer pri = new Integer(priority);
	    List messages = (List)_messageLists.get(pri);
	    if (messages == null) 
		return 0;
	    else 
		return messages.size();
	}
    }
    
    public void dumpPoolInfo() {
	StringBuffer buf = new StringBuffer();
	buf.append("\nDumping Outbound Network Message Pool.  Total # message: ").append(getCount()).append("\n");
	synchronized (_messageLists) {
	    for (Iterator iter = _messageLists.keySet().iterator(); iter.hasNext();) {
		Integer pri = (Integer)iter.next();
		List messages = (List)_messageLists.get(pri);
		if (messages.size() > 0) {
		    buf.append("Messages of priority ").append(pri).append(": ").append(messages.size()).append("\n");
		    buf.append("---------------------------\n");
		    for (Iterator msgIter = messages.iterator(); msgIter.hasNext(); ) {
			OutNetMessage msg = (OutNetMessage)msgIter.next();
			buf.append("Message ").append(msg.getMessage()).append("\n\n");
		    }
		    buf.append("---------------------------\n");
		}
	    }
	}
	_log.debug(buf.toString());
    }
    
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
