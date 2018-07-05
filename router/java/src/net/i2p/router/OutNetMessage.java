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

import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.util.CDPQEntry;
import net.i2p.util.Log;

/**
 * Wrap up an outbound I2NP message, along with the information associated with its
 * delivery and jobs to be fired off if particular events occur.
 *
 */
public class OutNetMessage implements CDPQEntry {
    private final Log _log;
    private final RouterContext _context;
    private final RouterInfo _target;
    private final I2NPMessage _message;
    private final int _messageTypeId;
    /** cached message ID, for use after we discard the message */
    private final long _messageId;
    private final int _messageSize;
    private final int _priority;
    private final long _expiration;
    private Job _onSend;
    private Job _onFailedSend;
    private ReplyJob _onReply;
    private Job _onFailedReply;
    private MessageSelector _replySelector;
    private Set<String> _failedTransports;
    private long _sendBegin;
    //private Exception _createdBy;
    private final long _created;
    private long _enqueueTime;
    private long _seqNum;
    /** for debugging, contains a mapping of even name to Long (e.g. "begin sending", "handleOutbound", etc) */
    private HashMap<String, Long> _timestamps;
    /**
     * contains a list of timestamp event names in the order they were fired
     * (some JVMs have less than 10ms resolution, so the Long above doesn't guarantee order)
     */
    private List<String> _timestampOrder;
    
    /**
     *  Priorities, higher is higher priority.
     *  @since 0.9.3
     */
    public static final int PRIORITY_HIGHEST = 1000;
    public static final int PRIORITY_MY_BUILD_REQUEST = 500;
    public static final int PRIORITY_MY_NETDB_LOOKUP = 500;
    public static final int PRIORITY_MY_NETDB_STORE = 460;
    public static final int PRIORITY_EXPLORATORY = 455;
    /** may be adjusted +/- 25 for outbound traffic */
    public static final int PRIORITY_MY_DATA = 425;
    public static final int PRIORITY_HIS_BUILD_REQUEST = 300;
    public static final int PRIORITY_BUILD_REPLY = 300;
    public static final int PRIORITY_NETDB_REPLY = 300;
    public static final int PRIORITY_HIS_NETDB_STORE = 200;
    public static final int PRIORITY_NETDB_FLOOD = 200;
    public static final int PRIORITY_PARTICIPATING = 200;
    public static final int PRIORITY_MY_NETDB_STORE_LOW = 150;
    public static final int PRIORITY_NETDB_EXPLORE = 100;
    public static final int PRIORITY_NETDB_HARVEST = 100;
    public static final int PRIORITY_LOWEST = 100;

    /**
     *  Null msg and target, zero expiration (used in OutboundMessageRegistry only)
     *  @since 0.9.9
     */
    public OutNetMessage(RouterContext context) {
        this(context, null, 0, -1, null);
    }

    /**
     *  Standard constructor
     *  @param msg generally non-null
     *  @param target generally non-null
     *  @since 0.9.9
     */
    public OutNetMessage(RouterContext context, I2NPMessage msg, long expiration, int priority, RouterInfo target) {
        _context = context;
        _log = context.logManager().getLog(OutNetMessage.class);
        _message = msg;
        if (msg != null) {
            _messageTypeId = msg.getType();
            _messageId = msg.getUniqueId();
            _messageSize = _message.getMessageSize();
        } else {
            _messageTypeId = 0;
            _messageId = 0;
            _messageSize = 0;
        }
        _priority = priority;
        _expiration = expiration;
        _target = target;

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
    @Deprecated
    public Map<String, Long> getTimestamps() {
        if (_log.shouldLog(Log.INFO)) {
            synchronized (this) {
                locked_initTimestamps();
                return new HashMap<String, Long>(_timestamps);
            }
        }
        return Collections.emptyMap();
    }

    /** @deprecated unused */
    @Deprecated
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
            _timestamps = new HashMap<String, Long>(8);
            _timestampOrder = new ArrayList<String>(8);
        }
    }
    
    /**
     * @deprecated
     * @return null always
     */
    @Deprecated
    public Exception getCreatedBy() { return null; }
    
    /**
     * Specifies the router to which the message should be delivered.
     * Generally non-null but may be null in special cases.
     */
    public RouterInfo getTarget() { return _target; }

    /**
     * Specifies the message to be sent.
     * Generally non-null but may be null in special cases.
     */
    public I2NPMessage getMessage() { return _message; }

    /**
     *  For debugging only.
     *  @return the simple class name
     */
    public String getMessageType() {
        return _message != null ? _message.getClass().getSimpleName() : "null";
    }

    public int getMessageTypeId() { return _messageTypeId; }
    public long getMessageId() { return _messageId; }
    
    /**
     * How large the message is, including the full 16 byte header.
     * Transports with different header sizes should adjust.
     */
    public int getMessageSize() {
        return _messageSize;
    }
    
    /**
     *  Copies the message data to outbuffer.
     *  Used only by VM Comm System.
     *  @return the length, or -1 if message is null
     */
    public int getMessageData(byte outBuffer[]) {
        if (_message == null) {
            return -1;
        } else {
            int len = _message.toByteArray(outBuffer);
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

    /**
     * Specify the # ms since the epoch after which if the message has not been
     * sent the OnFailedSend job should be fired and the message should be
     * removed from the pool.  If the message has already been sent, this
     * expiration is ignored and the expiration from the ReplySelector is used.
     *
     */
    public long getExpiration() { return _expiration; }

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
    
    public synchronized void transportFailed(String transportStyle) { 
        if (_failedTransports == null)
            _failedTransports = new HashSet<String>(2);
        _failedTransports.add(transportStyle); 
    }

    public synchronized Set<String> getFailedTransports() { 
        return (_failedTransports == null ? Collections.<String> emptySet() : _failedTransports); 
    }
    
    /** when did the sending process begin */
    public long getSendBegin() { return _sendBegin; }

    public void beginSend() { _sendBegin = _context.clock().now(); }

    public long getCreated() { return _created; }

    /** time since the message was created */
    public long getLifetime() { return _context.clock().now() - _created; }

    /** time the transport tries to send the message (including any queueing) */
    public long getSendTime() { return _context.clock().now() - _sendBegin; }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void setEnqueueTime(long now) {
        _enqueueTime = now;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public long getEnqueueTime() {
        return _enqueueTime;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void drop() {
        // This is essentially what TransportImpl.afterSend(this, false) does
        // but we don't have a ref to the Transport.
        // No requeue with other transport allowed.
        if (_onFailedSend != null)
            _context.jobQueue().addJob(_onFailedSend);
        if (_onFailedReply != null)
            _context.jobQueue().addJob(_onFailedReply);
        if (_replySelector != null)
            _context.messageRegistry().unregisterPending(this);
        discardData();
        // we want this stat to reflect the lag
        _context.statManager().addRateData("transport.sendProcessingTime", _context.clock().now() - _enqueueTime);
    }

    /**
     *  For CDPQ
     *  @since 0.9.3
     */
    public void setSeqNum(long num) {
        _seqNum = num;
    }

    /**
     *  For CDPQ
     *  @since 0.9.3
     */
    public long getSeqNum() {
        return _seqNum;
    }

    /** 
     * We've done what we need to do with the data from this message, though
     * we may keep the object around for a while to use its ID, jobs, etc.
     */
    public void discardData() {
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("[OutNetMessage containing ");
        if (_message == null) {
            buf.append("*no message*");
        } else {
            buf.append("a ").append(_messageSize).append(" byte ");
            buf.append(getMessageType());
            buf.append(" ID ").append(_messageId);
        }
        buf.append(" expiring ").append(new Date(_expiration));
        buf.append(" priority ").append(_priority);
        if (_failedTransports != null)
            buf.append(" failed transports: ").append(_failedTransports);
        if (_target == null)
            buf.append(" (null target)");
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
}
