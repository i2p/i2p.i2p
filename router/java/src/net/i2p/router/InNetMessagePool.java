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
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.router.transport.OutboundMessageRegistry;
import net.i2p.util.Log;
import net.i2p.stat.StatManager;

/**
 * Manage a pool of inbound InNetMessages.  This pool is filled by the 
 * Network communication system when it receives messages, and various jobs 
 * periodically retrieve them for processing.
 *
 */
public class InNetMessagePool {
    private final static Log _log = new Log(InNetMessagePool.class);
    private static InNetMessagePool _instance = new InNetMessagePool();
    public final static InNetMessagePool getInstance() { return _instance; }
    private List _messages;
    private Map _handlerJobBuilders;
    
    private InNetMessagePool() {
	_messages = new ArrayList();
	_handlerJobBuilders = new HashMap();
	StatManager.getInstance().createFrequencyStat("inNetPool.dropped", "How frequently we drop a message", "InNetPool", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
	StatManager.getInstance().createFrequencyStat("inNetPool.duplicate", "How frequently we receive a duplicate message", "InNetPool", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
  
    public HandlerJobBuilder registerHandlerJobBuilder(int i2npMessageType, HandlerJobBuilder builder) {
	return (HandlerJobBuilder)_handlerJobBuilders.put(new Integer(i2npMessageType), builder);
    }
  
    public HandlerJobBuilder unregisterHandlerJobBuilder(int i2npMessageType) {
	return (HandlerJobBuilder)_handlerJobBuilders.remove(new Integer(i2npMessageType));
    }
    
    /**
     * Add a new message to the pool, returning the number of messages in the 
     * pool so that the comm system can throttle inbound messages.  If there is 
     * a HandlerJobBuilder for the inbound message type, the message is loaded
     * into a job created by that builder and queued up for processing instead
     * (though if the builder doesn't create a job, it is added to the pool)
     *
     */
    public int add(InNetMessage msg) {
	Date exp = msg.getMessage().getMessageExpiration();
	boolean valid = MessageValidator.getInstance().validateMessage(msg.getMessage().getUniqueId(), exp.getTime());
	if (!valid) {
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Duplicate message received [" + msg.getMessage().getUniqueId() + " expiring on " + exp + "]: " + msg.getMessage().getClass().getName());
	    StatManager.getInstance().updateFrequency("inNetPool.dropped");
	    StatManager.getInstance().updateFrequency("inNetPool.duplicate");
	    MessageHistory.getInstance().droppedOtherMessage(msg.getMessage());
	    MessageHistory.getInstance().messageProcessingError(msg.getMessage().getUniqueId(), msg.getMessage().getClass().getName(), "Duplicate/expired");
	    return -1;
	} else {
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Message received [" + msg.getMessage().getUniqueId() + " expiring on " + exp + "] is NOT a duplicate or exipired");
	}
	
	int size = -1;
	int type = msg.getMessage().getType();
	HandlerJobBuilder builder = (HandlerJobBuilder)_handlerJobBuilders.get(new Integer(type));
	
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("Add message to the inNetMessage pool - builder: " + builder + " message class: " + msg.getMessage().getClass().getName());
	
	if (builder != null) {
	    Job job = builder.createJob(msg.getMessage(), msg.getFromRouter(), msg.getFromRouterHash(), msg.getReplyBlock());
	    if (job != null) {
		JobQueue.getInstance().addJob(job);
		synchronized (_messages) { 
		    size = _messages.size();
		}
	    }
	}
	
	List origMessages = OutboundMessageRegistry.getInstance().getOriginalMessages(msg.getMessage());
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("Original messages for inbound message: " + origMessages.size());
	if (origMessages.size() > 1) {
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Orig: " + origMessages + " \nthe above are replies for: " + msg, new Exception("Multiple matches"));
	}
	
	for (int i = 0; i < origMessages.size(); i++) {
	    OutNetMessage omsg = (OutNetMessage)origMessages.get(i);
	    ReplyJob job = omsg.getOnReplyJob();
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Original message [" + i + "] " + omsg.getReplySelector() + " : " + omsg + ": reply job: " + job);
	    
	    if (job != null) {
		job.setMessage(msg.getMessage());
		JobQueue.getInstance().addJob(job);
	    }
	}
		

	if (origMessages.size() <= 0) {
	    // not handled as a reply
	    if (size == -1) { 
		// was not handled via HandlerJobBuilder
		MessageHistory.getInstance().droppedOtherMessage(msg.getMessage());
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Message " + msg.getMessage() + " was not handled by a HandlerJobBuilder - DROPPING: " + msg, new Exception("DROPPED MESSAGE"));
		StatManager.getInstance().updateFrequency("inNetPool.dropped");
		//_log.error("Pending registry: \n" + OutboundMessageRegistry.getInstance().renderStatusHTML());
	    } else {
		String mtype = msg.getMessage().getClass().getName();
		MessageHistory.getInstance().receiveMessage(mtype, msg.getMessage().getUniqueId(), msg.getMessage().getMessageExpiration(), msg.getFromRouterHash(), true);	
		return size;
	    }
	}
	
	String mtype = msg.getMessage().getClass().getName();
	MessageHistory.getInstance().receiveMessage(mtype, msg.getMessage().getUniqueId(), msg.getMessage().getMessageExpiration(), msg.getFromRouterHash(), true);	
	return size;
    }
    
    /**
     * Remove up to maxNumMessages InNetMessages from the pool and return them.
     *
     */
    public List getNext(int maxNumMessages) {
	ArrayList msgs = new ArrayList(maxNumMessages);
	synchronized (_messages) {
	    for (int i = 0; (i < maxNumMessages) && (_messages.size() > 0); i++) 
		msgs.add(_messages.remove(0));
	}
	return msgs;
    }
    
    /**
     * Retrieve the next message
     *
     */
    public InNetMessage getNext() {
	synchronized (_messages) {
	    if (_messages.size() <= 0) return null;
	    return (InNetMessage)_messages.remove(0);
	}
    }
    
    /**
     * Retrieve the size of the pool
     *
     */
    public int getCount() {
	synchronized (_messages) {
	    return _messages.size(); 
	}
    }
    
    public void dumpPoolInfo() {
	if (!_log.shouldLog(Log.DEBUG)) return;
	
	StringBuffer buf = new StringBuffer();
	buf.append("\nDumping Inbound Network Message Pool.  Total # message: ").append(getCount()).append("\n");
	synchronized (_messages) {
	    for (Iterator iter = _messages.iterator(); iter.hasNext();) {
		InNetMessage msg = (InNetMessage)iter.next();
		buf.append("Message ").append(msg.getMessage()).append("\n\n");
	    }
	}
	_log.debug(buf.toString());
    }
    
}
