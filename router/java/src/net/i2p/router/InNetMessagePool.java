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
import java.util.List;
import java.util.Map;

import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.util.Log;

/**
 * Manage a pool of inbound InNetMessages.  This pool is filled by the 
 * Network communication system when it receives messages, and various jobs 
 * periodically retrieve them for processing.
 *
 */
public class InNetMessagePool {
    private Log _log;
    private RouterContext _context;
    private List _messages;
    private Map _handlerJobBuilders;
    
    public InNetMessagePool(RouterContext context) {
        _context = context;
        _messages = new ArrayList();
        _handlerJobBuilders = new HashMap();
        _log = _context.logManager().getLog(InNetMessagePool.class);
        _context.statManager().createRateStat("inNetPool.dropped", "How often do we drop a message", "InNetPool", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("inNetPool.droppedDeliveryStatusDelay", "How long after a delivery status message is created do we receive it back again (for messages that are too slow to be handled)", "InNetPool", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("inNetPool.duplicate", "How often do we receive a duplicate message", "InNetPool", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
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
        boolean valid = _context.messageValidator().validateMessage(msg.getMessage().getUniqueId(), exp.getTime());
        if (!valid) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Duplicate message received [" + msg.getMessage().getUniqueId() 
                          + " expiring on " + exp + "]: " + msg.getMessage().getClass().getName());
            _context.statManager().addRateData("inNetPool.dropped", 1, 0);
            _context.statManager().addRateData("inNetPool.duplicate", 1, 0);
            _context.messageHistory().droppedOtherMessage(msg.getMessage());
            _context.messageHistory().messageProcessingError(msg.getMessage().getUniqueId(), 
                                                                msg.getMessage().getClass().getName(), 
                                                                "Duplicate/expired");
            return -1;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Message received [" + msg.getMessage().getUniqueId() 
                           + " expiring on " + exp + "] is NOT a duplicate or exipired");
        }

        int size = -1;
        int type = msg.getMessage().getType();
        HandlerJobBuilder builder = (HandlerJobBuilder)_handlerJobBuilders.get(new Integer(type));

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Add message to the inNetMessage pool - builder: " + builder 
                       + " message class: " + msg.getMessage().getClass().getName());

        if (builder != null) {
            Job job = builder.createJob(msg.getMessage(), msg.getFromRouter(), 
                                        msg.getFromRouterHash(), msg.getReplyBlock());
            if (job != null) {
                _context.jobQueue().addJob(job);
                synchronized (_messages) { 
                    size = _messages.size();
                }
            }
        }

        List origMessages = _context.messageRegistry().getOriginalMessages(msg.getMessage());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Original messages for inbound message: " + origMessages.size());
        if (origMessages.size() > 1) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Orig: " + origMessages + " \nthe above are replies for: " + msg, 
                           new Exception("Multiple matches"));
        }

        for (int i = 0; i < origMessages.size(); i++) {
            OutNetMessage omsg = (OutNetMessage)origMessages.get(i);
            ReplyJob job = omsg.getOnReplyJob();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Original message [" + i + "] " + omsg.getReplySelector() 
                           + " : " + omsg + ": reply job: " + job);

            if (job != null) {
                job.setMessage(msg.getMessage());
                _context.jobQueue().addJob(job);
            }
        }

        if (origMessages.size() <= 0) {
            // not handled as a reply
            if (size == -1) { 
                // was not handled via HandlerJobBuilder
                _context.messageHistory().droppedOtherMessage(msg.getMessage());
                if (msg.getMessage().getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
                    long timeSinceSent = _context.clock().now() - 
                                        ((DeliveryStatusMessage)msg.getMessage()).getArrival().getTime();
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Dropping unhandled delivery status message created " + timeSinceSent + "ms ago: " + msg);
                    _context.statManager().addRateData("inNetPool.droppedDeliveryStatusDelay", timeSinceSent, timeSinceSent);
                } else {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Message " + msg.getMessage() + " was not handled by a HandlerJobBuilder - DROPPING: " 
                                   + msg, new Exception("DROPPED MESSAGE"));
                    _context.statManager().addRateData("inNetPool.dropped", 1, 0);
                }
            } else {
                String mtype = msg.getMessage().getClass().getName();
                _context.messageHistory().receiveMessage(mtype, msg.getMessage().getUniqueId(), 
                                                         msg.getMessage().getMessageExpiration(), 
                                                         msg.getFromRouterHash(), true);	
                return size;
            }
        }

        String mtype = msg.getMessage().getClass().getName();
        _context.messageHistory().receiveMessage(mtype, msg.getMessage().getUniqueId(), 
                                                 msg.getMessage().getMessageExpiration(), 
                                                 msg.getFromRouterHash(), true);	
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
}
