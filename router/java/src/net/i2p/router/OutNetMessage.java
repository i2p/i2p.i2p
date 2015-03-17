package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.util.Log;

/**
 * Wrap up an outbound I2NP message, along with the information associated with its
 * delivery and jobs to be fired off if particular events occur.
 *
 */
public class OutNetMessage {
    private final Log _log;
    private final RouterContext _context;
    private RouterInfo _target;
    private I2NPMessage _message;
    /** cached message class name, for use after we discard the message */
    private String _messageType;
    private int _messageTypeId;
    /** cached message ID, for use after we discard the message */
    private long _messageId;
    private long _messageSize;
    private int _priority;
    private long _expiration;
    private Job _onSend;
    private Job _onFailedSend;
    private ReplyJob _onReply;
    private Job _onFailedReply;
    private MessageSelector _replySelector;
    private Set<String> _failedTransports;
    private long _sendBegin;
    //private Exception _createdBy;
    private final long _created;
    /** for debugging, contains a mapping of even name to Long (e.g. "begin sending", "handleOutbound", etc) */
    private HashMap<String, Long> _timestamps;
    /**
     * contains a list of timestamp event names in the order they were fired
     * (some JVMs have less than 10ms resolution, so the Long above doesn't guarantee order)
     */
    private List<String> _timestampOrder;
    private Object _preparationBuf;
    
    public OutNetMessage(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(OutNetMessage.class);
        _priority = -1;
        _expiration = -1;
        //_createdBy = new Exception("Created by");
        _created = context.clock().now();
        if (_log.shouldLog(Log.INFO))
            timestamp("Created");
        //_context.messageStateMonitor().outboundMessageAdded();
        //_context.statManager().createRateStat("outNetMessage.timeToDiscard", 
        //                                      "How long until we discard an outbound msg?",
        //                                      "OutNetMessage", new long[] { 5*60*1000, 30*60*1000, 60*60*1000 });
    }
    
    /**
     * Stamp the message's progress.
     * Only useful if log level is INFO or DEBUG
     *
     * @param eventName what occurred 
     * @return how long this message has been 'in flight'
     */
    public long timestamp(String eventName) {
        long now = _context.clock().now();
        if (_log.shouldLog(Log.INFO)) {
            // only timestamp if we are debugging
            synchronized (this) {
                locked_initTimestamps();
                // ???
                //while (_timestamps.containsKey(eventName)) {
                //    eventName = eventName + '.';
                //}
                _timestamps.put(eventName, Long.valueOf(now));
                _timestampOrder.add(eventName);
            }
        }
        return now - _created;
    }

    /** @deprecated unused */
    public Map<String, Long> getTimestamps() {
        if (_log.shouldLog(Log.INFO)) {
            synchronized (this) {
                locked_initTimestamps();
                return (Map<String, Long>)_timestamps.clone();
            }
        }
        return Collections.EMPTY_MAP;
    }

    /** @deprecated unused */
    public Long getTimestamp(String eventName) {
        if (_log.shouldLog(Log.INFO)) {
            synchronized (this) {
                locked_initTimestamps();
                return _timestamps.get(eventName);
            }
        }
        return Long.valueOf(0);
    }

    private void locked_initTimestamps() {
        if (_timestamps == null) {
            _timestamps = new HashMap(8);
            _timestampOrder = new ArrayList(8);
        }
    }
    
    /**
     * @deprecated
     * @return null always
     */
    public Exception getCreatedBy() { return null; }
    
    /**
     * Specifies the router to which the message should be delivered.
     *
     */
    public RouterInfo getTarget() { return _target; }
    public void setTarget(RouterInfo target) { _target = target; }

    /**
     * Specifies the message to be sent
     *
     */
    public I2NPMessage getMessage() { return _message; }

    public void setMessage(I2NPMessage msg) {
        _message = msg;
        if (msg != null) {
            _messageType = msg.getClass().getSimpleName();
            _messageTypeId = msg.getType();
            _messageId = msg.getUniqueId();
            _messageSize = _message.getMessageSize();
        }
    }
    
    /**
     *  @return the simple class name
     */
    public String getMessageType() { return _messageType; }

    public int getMessageTypeId() { return _messageTypeId; }
    public long getMessageId() { return _messageId; }
    
    public long getMessageSize() {
        if (_messageSize <= 0) {
            _messageSize = _message.getMessageSize();
        }
        return _messageSize;
    }
    
    public int getMessageData(byte outBuffer[]) {
        if (_message == null) {
            return -1;
        } else {
            int len = _message.toByteArray(outBuffer);
            _messageSize = len;
            return len;
        }
    }
    
    /**
     * Specify the priority of the message, where higher numbers are higher
     * priority.  Higher priority messages should be delivered before lower
     * priority ones, though some algorithm may be used to avoid starvation.
     *
     */
    public int getPriority() { return _priority; }
    public void setPriority(int priority) { _priority = priority; }
    /**
     * Specify the # ms since the epoch after which if the message has not been
     * sent the OnFailedSend job should be fired and the message should be
     * removed from the pool.  If the message has already been sent, this
     * expiration is ignored and the expiration from the ReplySelector is used.
     *
     */
    public long getExpiration() { return _expiration; }
    public void setExpiration(long expiration) { _expiration = expiration; }
    /**
     * After the message is successfully passed to the router specified, the
     * given job is enqueued.
     *
     */
    public Job getOnSendJob() { return _onSend; }
    public void setOnSendJob(Job job) { _onSend = job; }
    /**
     * If the router could not be reached or the expiration passed, this job
     * is enqueued.
     *
     */
    public Job getOnFailedSendJob() { return _onFailedSend; }
    public void setOnFailedSendJob(Job job) { _onFailedSend = job; }
    /**
     * If the MessageSelector detects a reply, this job is enqueued
     *
     */
    public ReplyJob getOnReplyJob() { return _onReply; }
    public void setOnReplyJob(ReplyJob job) { _onReply = job; }
    /**
     * If the Message selector is specified but it doesn't find a reply before
     * its expiration passes, this job is enqueued.
     */
    public Job getOnFailedReplyJob() { return _onFailedReply; }
    public void setOnFailedReplyJob(Job job) { _onFailedReply = job; }
    /**
     * Defines a MessageSelector to find a reply to this message.
     *
     */
    public MessageSelector getReplySelector() { return _replySelector; }
    public void setReplySelector(MessageSelector selector) { _replySelector = selector; }
    
    public void transportFailed(String transportStyle) { 
        if (_failedTransports == null)
            _failedTransports = new HashSet(2);
        _failedTransports.add(transportStyle); 
    }
    /** not thread safe - dont fail transports and iterate over this at the same time */
    public Set getFailedTransports() { 
        return (_failedTransports == null ? Collections.EMPTY_SET : _failedTransports); 
    }
    
    /** when did the sending process begin */
    public long getSendBegin() { return _sendBegin; }

    public void beginSend() { _sendBegin = _context.clock().now(); }

    public void prepared(Object buf) { 
        _preparationBuf = buf;
    }

    public Object releasePreparationBuffer() { 
        Object rv = _preparationBuf;
        _preparationBuf = null;
        return rv;
    }
    
    public long getCreated() { return _created; }

    /** time since the message was created */
    public long getLifetime() { return _context.clock().now() - _created; }

    /** time the transport tries to send the message (including any queueing) */
    public long getSendTime() { return _context.clock().now() - _sendBegin; }

    /** 
     * We've done what we need to do with the data from this message, though
     * we may keep the object around for a while to use its ID, jobs, etc.
     */
    public void discardData() {
        if ( (_message != null) && (_messageSize <= 0) )
            _messageSize = _message.getMessageSize();
        if (_log.shouldLog(Log.DEBUG)) {
            long timeToDiscard = _context.clock().now() - _created;
            _log.debug("Discard " + _messageSize + "byte " + _messageType + " message after " 
                       + timeToDiscard);
        }
        _message = null;
        //_context.statManager().addRateData("outNetMessage.timeToDiscard", timeToDiscard, timeToDiscard);
        //_context.messageStateMonitor().outboundMessageDiscarded();
    }
    
    /*
    public void finalize() throws Throwable {
        if (_message != null) {
            if (_log.shouldLog(Log.WARN)) {
                StringBuilder buf = new StringBuilder(1024);
                buf.append("Undiscarded ").append(_messageSize).append("byte ");
                buf.append(_messageType).append(" message created ");
                buf.append((_context.clock().now() - _created)).append("ms ago: ");
                buf.append(_messageId); // .append(" to ").append(_target.calculateHash().toBase64());
                buf.append(", timing - \n");
                renderTimestamps(buf);
                _log.warn(buf.toString(), _createdBy);
            }
            _context.messageStateMonitor().outboundMessageDiscarded();
        }
        _context.messageStateMonitor().outboundMessageFinalized();
        super.finalize();
    }
    */

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("[OutNetMessage containing ");
        if (_message == null) {
            buf.append("*no message*");
        } else {
            buf.append("a ").append(_messageSize).append(" byte ");
            buf.append(_messageType);
        }
        buf.append(" expiring on ").append(new Date(_expiration));
        if (_failedTransports != null)
            buf.append(" failed delivery on transports ").append(_failedTransports);
        if (_target == null)
            buf.append(" targetting no one in particular...");
        else
            buf.append(" targetting ").append(_target.getIdentity().getHash().toBase64());
        if (_onReply != null)
            buf.append(" with onReply job: ").append(_onReply);
        if (_onSend != null)
            buf.append(" with onSend job: ").append(_onSend);
        if (_onFailedReply != null)
            buf.append(" with onFailedReply job: ").append(_onFailedReply);
        if (_onFailedSend != null)
            buf.append(" with onFailedSend job: ").append(_onFailedSend);
        if (_timestamps != null && _timestampOrder != null && _log.shouldLog(Log.INFO)) {
            buf.append(" {timestamps: \n");
            renderTimestamps(buf);
            buf.append("}");
        }
        buf.append("]");
        return buf.toString();
    }
    
    /**
     *  Only useful if log level is INFO or DEBUG;
     *  locked_initTimestamps() must have been called previously
     */
    private void renderTimestamps(StringBuilder buf) {
            synchronized (this) {
                long lastWhen = -1;
                for (int i = 0; i < _timestampOrder.size(); i++) {
                    String name = _timestampOrder.get(i);
                    Long when = _timestamps.get(name);
                    buf.append("\t[");
                    long diff = when.longValue() - lastWhen;
                    if ( (lastWhen > 0) && (diff > 500) )
                        buf.append("**");
                    if (lastWhen > 0)
                        buf.append(diff);
                    else
                        buf.append(0);
                    buf.append("ms: \t").append(name);
                    buf.append('=').append(formatDate(when.longValue()));
                    buf.append("]\n");
                    lastWhen = when.longValue();
                }
            }
    }
    
    private final static SimpleDateFormat _fmt = new SimpleDateFormat("HH:mm:ss.SSS");
    private final static String formatDate(long when) {
        Date d = new Date(when);
        synchronized (_fmt) {
            return _fmt.format(d);
        }
    }
    
/****

    @Override
    public int hashCode() {
        int rv = DataHelper.hashCode(_message);
        rv ^= DataHelper.hashCode(_target);
        // the others are pretty much inconsequential
        return rv;
    }
    
    @Override
    public boolean equals(Object obj) {
        //if(obj == null) return false;
        //if(!(obj instanceof OutNetMessage)) return false;
        return obj == this; // two OutNetMessages are different even if they contain the same message
    }
****/
}
