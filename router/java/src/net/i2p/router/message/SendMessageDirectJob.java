package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

public class SendMessageDirectJob extends JobImpl {
    private Log _log;
    private I2NPMessage _message;
    private Hash _targetHash;
    private RouterInfo _router;
    private long _expiration;
    private int _priority;
    private Job _onSend;
    private ReplyJob _onSuccess;
    private Job _onFail;
    private MessageSelector _selector;
    private boolean _alreadySearched;
    private boolean _sent;
    private long _searchOn;
    
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, int timeoutMs, int priority) {
        this(ctx, message, toPeer, null, null, null, null, timeoutMs, priority);
    }
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, ReplyJob onSuccess, Job onFail, MessageSelector selector, int timeoutMs, int priority) {
        this(ctx, message, toPeer, null, onSuccess, onFail, selector, timeoutMs, priority);
    }
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, Job onSend, ReplyJob onSuccess, Job onFail, MessageSelector selector, int timeoutMs, int priority) {
        super(ctx);
        _log = getContext().logManager().getLog(SendMessageDirectJob.class);
        _message = message;
        _targetHash = toPeer;
        _router = null;
        if (timeoutMs < 10*1000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Very little time given [" + timeoutMs + "], resetting to 5s", new Exception("stingy bastard"));
            _expiration = ctx.clock().now() + 10*1000;
        } else {
            _expiration = timeoutMs + ctx.clock().now();
        }
        _priority = priority;
        _searchOn = 0;
        _alreadySearched = false;
        _onSend = onSend;
        _onSuccess = onSuccess;
        _onFail = onFail;
        _selector = selector;
        if (message == null)
            throw new IllegalArgumentException("Attempt to send a null message");
        if (_targetHash == null)
            throw new IllegalArgumentException("Attempt to send a message to a null peer");
        _sent = false;
    }
    
    public String getName() { return "Send Message Direct"; }
    public void runJob() { 
        long now = getContext().clock().now();

        if (_expiration < now) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Timed out sending message " + _message + " directly (expiration = " 
                           + new Date(_expiration) + ") to " + _targetHash.toBase64(), getAddedBy());
            if (_onFail != null)
                getContext().jobQueue().addJob(_onFail);
            return;
        }

        if (_router != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Router specified, sending");
            send();
        } else {
            _router = getContext().netDb().lookupRouterInfoLocally(_targetHash);
            if (_router != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Router not specified but lookup found it");
                send();
            } else {
                if (!_alreadySearched) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Router not specified, so we're looking for it...");
                    getContext().netDb().lookupRouterInfo(_targetHash, this, this, 
                                                          _expiration - getContext().clock().now());
                    _searchOn = getContext().clock().now();
                    _alreadySearched = true;
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Unable to find the router to send to: " + _targetHash 
                                  + " after searching for " + (getContext().clock().now()-_searchOn) 
                                  + "ms, message: " + _message, getAddedBy());
                    if (_onFail != null)
                        getContext().jobQueue().addJob(_onFail);
                }
            }
        }
    }
    
    private void send() {
        if (_sent) { 
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not resending!", new Exception("blah")); 
            return; 
        }
        _sent = true;
        Hash to = _router.getIdentity().getHash();
        Hash us = getContext().routerHash();
        if (us.equals(to)) {
            if (_selector != null) {
                OutNetMessage outM = new OutNetMessage(getContext());
                outM.setExpiration(_expiration);
                outM.setMessage(_message);
                outM.setOnFailedReplyJob(_onFail);
                outM.setOnFailedSendJob(_onFail);
                outM.setOnReplyJob(_onSuccess);
                outM.setOnSendJob(_onSend);
                outM.setPriority(_priority);
                outM.setReplySelector(_selector);
                outM.setTarget(_router);
                getContext().messageRegistry().registerPending(outM);
            }

            if (_onSend != null)
                getContext().jobQueue().addJob(_onSend);

            getContext().inNetMessagePool().add(_message, _router.getIdentity(), null);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding " + _message.getClass().getName() 
                           + " to inbound message pool as it was destined for ourselves");
            //_log.debug("debug", _createdBy);
        } else {
            OutNetMessage msg = new OutNetMessage(getContext());
            msg.setExpiration(_expiration);
            msg.setMessage(_message);
            msg.setOnFailedReplyJob(_onFail);
            msg.setOnFailedSendJob(_onFail);
            msg.setOnReplyJob(_onSuccess);
            msg.setOnSendJob(_onSend);
            msg.setPriority(_priority);
            msg.setReplySelector(_selector);
            msg.setTarget(_router);
            getContext().outNetMessagePool().add(msg);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Adding " + _message.getClass().getName() 
                           + " to outbound message pool targeting " 
                           + _router.getIdentity().getHash().toBase64());
            //_log.debug("Message pooled: " + _message);
        }
    }
}
