package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Wrap up an outbound I2NP message, along with the information associated with its
 * delivery and jobs to be fired off if particular events occur.
 *
 */
public class OutNetMessage {
    private Log _log;
    private RouterContext _context;
    private RouterInfo _target;
    private I2NPMessage _message;
    private long _messageSize;
    private int _priority;
    private long _expiration;
    private Job _onSend;
    private Job _onFailedSend;
    private ReplyJob _onReply;
    private Job _onFailedReply;
    private MessageSelector _replySelector;
    private Set _failedTransports;
    private long _sendBegin;
    private Exception _createdBy;
    private long _created;
    /** for debugging, contains a mapping of even name to Long (e.g. "begin sending", "handleOutbound", etc) */
    private HashMap _timestamps;
    /**
     * contains a list of timestamp event names in the order they were fired
     * (some JVMs have less than 10ms resolution, so the Long above doesn't guarantee order)
     */
    private List _timestampOrder;
    
    public OutNetMessage(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(OutNetMessage.class);
        setTarget(null);
        _message = null;
        _messageSize = 0;
        setPriority(-1);
        setExpiration(-1);
        setOnSendJob(null);
        setOnFailedSendJob(null);
        setOnReplyJob(null);
        setOnFailedReplyJob(null);
        setReplySelector(null);
        _timestamps = new HashMap(8);
        _timestampOrder = new LinkedList();
        _failedTransports = new HashSet();
        _sendBegin = 0;
        _createdBy = new Exception("Created by");
        _created = context.clock().now();
        timestamp("Created");
    }
    
    public void timestamp(String eventName) {
        synchronized (_timestamps) {
            _timestamps.put(eventName, new Long(_context.clock().now()));
            _timestampOrder.add(eventName);
        }
    }
    public Map getTimestamps() {
        synchronized (_timestamps) {
            return (Map)_timestamps.clone();
        }
    }
    public Long getTimestamp(String eventName) {
        synchronized (_timestamps) {
            return (Long)_timestamps.get(eventName);
        }
    }
    
    public Exception getCreatedBy() { return _createdBy; }
    
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
    }
    
    public long getMessageSize() {
        if (_messageSize <= 0) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(4096); // large enough to hold most messages
                _message.writeBytes(baos);
                long sz = baos.size();
                baos.reset();
                _messageSize = sz;
            } catch (DataFormatException dfe) {
                _log.error("Error serializing the I2NPMessage for the OutNetMessage", dfe);
            } catch (IOException ioe) {
                _log.error("Error serializing the I2NPMessage for the OutNetMessage", ioe);
            }
        }
        return _messageSize;
    }
    public byte[] getMessageData() {
        if (_message == null) {
            return null;
        } else {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(4096); // large enough to hold most messages
                _message.writeBytes(baos);
                byte data[] = baos.toByteArray();
                baos.reset();
                return data;
            } catch (DataFormatException dfe) {
                _log.error("Error serializing the I2NPMessage for the OutNetMessage", dfe);
            } catch (IOException ioe) {
                _log.error("Error serializing the I2NPMessage for the OutNetMessage", ioe);
            }
            return null;
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
    
    public void transportFailed(String transportStyle) { _failedTransports.add(transportStyle); }
    public Set getFailedTransports() { return new HashSet(_failedTransports); }
    
    /** when did the sending process begin */
    public long getSendBegin() { return _sendBegin; }
    public void beginSend() { _sendBegin = _context.clock().now(); }
    
    public long getCreated() { return _created; }
    public long getLifetime() { return _context.clock().now() - _created; }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(128);
        buf.append("[OutNetMessage contains ");
        if (_message == null) {
            buf.append("*no message*");
        } else {
            buf.append("a ").append(_messageSize).append(" byte ");
            buf.append(_message.getClass().getName());
        }
        buf.append(" expiring on ").append(new Date(_expiration));
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
        buf.append(" {timestamps: \n");
        synchronized (_timestamps) {
            long lastWhen = -1;
            for (int i = 0; i < _timestampOrder.size(); i++) {
                String name = (String)_timestampOrder.get(i);
                Long when = (Long)_timestamps.get(name);
                buf.append("\t[");
                long diff = when.longValue() - lastWhen;
                if ( (lastWhen > 0) && (diff > 500) )
                    buf.append("**");
                if (lastWhen > 0)
                    buf.append(diff);
                else
                    buf.append(0);
                buf.append("ms: \t").append(name).append('=').append(formatDate(when.longValue())).append("]\n");
                lastWhen = when.longValue();
            }
        }
        buf.append("}");
        buf.append("]");
        return buf.toString();
    }
    
    private final static SimpleDateFormat _fmt = new SimpleDateFormat("HH:mm:ss.SSS");
    private final static String formatDate(long when) {
        Date d = new Date(when);
        synchronized (_fmt) {
            return _fmt.format(d);
        }
    }
    
    public int hashCode() {
        int rv = 0;
        rv += DataHelper.hashCode(_message);
        rv += DataHelper.hashCode(_target);
        // the others are pretty much inconsequential
        return rv;
    }
    
    public boolean equals(Object obj) {
        return obj == this; // two OutNetMessages are different even if they contain the same message
    }
}
