package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobQueue;
import net.i2p.router.MessageHistory;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.OutNetMessagePool;
import net.i2p.router.ProfileManager;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Defines a way to send a message to another peer and start listening for messages
 *
 */
public abstract class TransportImpl implements Transport {
    private Log _log;
    private TransportEventListener _listener;
    private Set _currentAddresses;
    private List _sendPool;
    protected RouterContext _context;

    public TransportImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(TransportImpl.class);
        
        _context.statManager().createFrequencyStat("transport.sendMessageFailureFrequency", "How often do we fail to send messages?", "Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.sendMessageSize", "How large are the messages sent?", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.receiveMessageSize", "How large are the messages received?", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.sendProcessingTime", "How long does it take from noticing that we want to send the message to having it completely sent (successfully or failed)?", "Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _sendPool = new LinkedList();
        _currentAddresses = new HashSet();
    }
    
    public OutNetMessage getNextMessage() {
        OutNetMessage msg = null;
        synchronized (_sendPool) {
            if (_sendPool.size() <= 0) return null;
            msg = (OutNetMessage)_sendPool.remove(0); // use priority queues later
        }
        msg.beginSend();
        return msg;
    }
    
    public void afterSend(OutNetMessage msg, boolean sendSuccessful) {
        boolean log = false;
        msg.timestamp("afterSend(" + sendSuccessful + ")");
        if (!sendSuccessful)
            msg.transportFailed(getStyle());

        long lifetime = msg.getLifetime();
        if (lifetime > 5000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("afterSend: [success=" + sendSuccessful + "]\n" + msg.toString());
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("afterSend: [success=" + sendSuccessful + "]\n" + msg.toString());
        }

        if (sendSuccessful) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Send message " + msg.getMessage().getClass().getName() + " to " 
                           + msg.getTarget().getIdentity().getHash().toBase64() + " with transport " 
                           + getStyle() + " successfully");
            Job j = msg.getOnSendJob();
            if (j != null) 
                _context.jobQueue().addJob(j);
            log = true;
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Failed to send message " + msg.getMessage().getClass().getName() 
                          + " to " + msg.getTarget().getIdentity().getHash().toBase64() 
                          + " with transport " + getStyle() + " (details: " + msg + ")");
            if ( (msg.getExpiration() <= 0) || (msg.getExpiration() > _context.clock().now()) ) {
                // this may not be the last transport available - keep going
                _context.outNetMessagePool().add(msg);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("No more time left (" + new Date(msg.getExpiration()) 
                              + ", expiring without sending successfully the " 
                              + msg.getMessage().getClass().getName());
                if (msg.getOnFailedSendJob() != null)
                    _context.jobQueue().addJob(msg.getOnFailedSendJob());
                MessageSelector selector = msg.getReplySelector();
                if (selector != null) {
                    _context.messageRegistry().unregisterPending(msg);
                }
                log = true;
            }
        }

        if (log) {
            I2NPMessage dmsg = msg.getMessage();
            String type = dmsg.getClass().getName();
            _context.messageHistory().sendMessage(type, dmsg.getUniqueId(), dmsg.getMessageExpiration(), msg.getTarget().getIdentity().getHash(), sendSuccessful);
        }

        long now = _context.clock().now();
        long sendTime = now - msg.getSendBegin();
        long allTime = now - msg.getCreated();
        if (allTime > 5*1000) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Took too long from preperation to afterSend(ok? " + sendSuccessful 
                          + "): " + allTime + "ms " + " after failing on: " 
                          + msg.getFailedTransports() + " and succeeding on " + getStyle());
            if (allTime > 60*1000) {
                // WTF!!@#
                if (_log.shouldLog(Log.WARN))
                    _log.warn("WTF, more than a minute slow? " + msg.getMessage().getClass().getName() 
                              + " of id " + msg.getMessage().getUniqueId() + " (send begin on " 
                              + new Date(msg.getSendBegin()) + " / created on " 
                              + new Date(msg.getCreated()) + "): " + msg, msg.getCreatedBy());
                _context.messageHistory().messageProcessingError(msg.getMessage().getUniqueId(), msg.getMessage().getClass().getName(), "Took too long to send [" + allTime + "ms]");
            }
        }

        _context.statManager().addRateData("transport.sendProcessingTime", msg.getLifetime(), msg.getLifetime());

        if (sendSuccessful) {
            _context.profileManager().messageSent(msg.getTarget().getIdentity().getHash(), getStyle(), sendTime, msg.getMessageSize());
            _context.statManager().addRateData("transport.sendMessageSize", msg.getMessageSize(), sendTime);
        } else {
            _context.profileManager().messageFailed(msg.getTarget().getIdentity().getHash(), getStyle());
            _context.statManager().updateFrequency("transport.sendMessageFailureFrequency");
        }
    }
    
    /**
     * Asynchronously send the message as requested in the message and, if the
     * send is successful, queue up any msg.getOnSendJob job, and register it
     * with the OutboundMessageRegistry (if it has a reply selector).  If the
     * send fails, queue up any msg.getOnFailedSendJob
     *
     */
    public void send(OutNetMessage msg) {
        boolean duplicate = false;
        synchronized (_sendPool) {
            if (_sendPool.contains(msg)) 
                duplicate = true;
            else
                _sendPool.add(msg);
        }
        if (duplicate) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Message already is in the queue?  wtf.  msg = " + msg, 
                           new Exception("wtf, requeued?"));
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Message added to send pool");
        outboundMessageReady();
        if (_log.shouldLog(Log.INFO))
            _log.debug("OutboundMessageReady called");
    }
    /**
     * This message is called whenever a new message is added to the send pool,
     * and it should not block
     */
    protected abstract void outboundMessageReady();
    
    public void messageReceived(I2NPMessage inMsg, RouterIdentity remoteIdent, Hash remoteIdentHash, long msToReceive, int bytesReceived) {
        if (_log.shouldLog(Log.INFO)) {
            StringBuffer buf = new StringBuffer(128);
            buf.append("Message received: ").append(inMsg.getClass().getName());
            buf.append(" in ").append(msToReceive).append("ms containing ");
            buf.append(bytesReceived).append(" bytes ");
            buf.append(" from ");
            if (remoteIdentHash != null) {
                buf.append(remoteIdentHash.toBase64());
            } else if (remoteIdent != null) {
                buf.append(remoteIdent.getHash().toBase64());
            } else {
                buf.append("[unknown]");
            }
            buf.append(" and forwarding to listener: ");
            if (_listener != null)
                buf.append(_listener);

            _log.info(buf.toString());
        }

        if (remoteIdent != null)
            remoteIdentHash = remoteIdent.getHash();
        if (remoteIdentHash != null) {
            _context.profileManager().messageReceived(remoteIdentHash, getStyle(), msToReceive, bytesReceived);
            _context.statManager().addRateData("transport.receiveMessageSize", bytesReceived, msToReceive);
        }

        //// this functionality is built into the InNetMessagePool
        //String type = inMsg.getClass().getName();
        //MessageHistory.getInstance().receiveMessage(type, inMsg.getUniqueId(), inMsg.getMessageExpiration(), remoteIdentHash, true);
            
        if (_listener != null) {
            _listener.messageReceived(inMsg, remoteIdent, remoteIdentHash);
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("WTF! Null listener! this = " + toString(), new Exception("Null listener"));
        }
    }
 
    /**
     * Pull the first workable target address for this transport
     *
     */
    protected RouterAddress getTargetAddress(RouterInfo address) {
        if (address == null) return null;
        for (Iterator iter = address.getAddresses().iterator(); iter.hasNext(); ) {
            RouterAddress addr = (RouterAddress)iter.next();
            if (getStyle().equals(addr.getTransportStyle())) 
                return addr;
        }
        return null;
    }
	
    public Set getCurrentAddresses() { return _currentAddresses; }
    protected void addCurrentAddress(RouterAddress address) { _currentAddresses.add(address); }
    protected void removeCurrentAddress(RouterAddress address) { _currentAddresses.remove(address); }
    public void setListener(TransportEventListener listener) { _listener = listener; }
    
    public String renderStatusHTML() { return null; }
}
