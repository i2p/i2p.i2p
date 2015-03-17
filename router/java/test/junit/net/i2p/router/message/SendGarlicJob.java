package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Set;

import net.i2p.data.SessionKey;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Build a garlic message from config, encrypt it, and enqueue it for delivery.
 *
 */
public class SendGarlicJob extends JobImpl {
    private Log _log;
    //private RouterInfo _target;
    private GarlicConfig _config;
    private Job _onSend;
    private Job _onSendFailed;
    private ReplyJob _onReply;
    private Job _onReplyFailed;
    private long _timeoutMs;
    private int _priority;
    private MessageSelector _replySelector;
    private GarlicMessage _message;
    private SessionKey _wrappedKey;
    private Set _wrappedTags;
    
    /**
     *
     * @param config ???
     * @param onSend after the ping is successful
     * @param onSendFailed after the ping fails or times out
     * @param onReply ???
     * @param onReplyFailed ???
     * @param timeoutMs how long to wait before timing out
     * @param priority how high priority to send this test
     * @param replySelector ???
     */
    public SendGarlicJob(RouterContext ctx, GarlicConfig config, Job onSend, Job onSendFailed, ReplyJob onReply, Job onReplyFailed, long timeoutMs, int priority, MessageSelector replySelector) {
        this(ctx, config, onSend, onSendFailed, onReply, onReplyFailed, timeoutMs, priority, replySelector, new SessionKey(), new HashSet());
    }
    public SendGarlicJob(RouterContext ctx, GarlicConfig config, Job onSend, Job onSendFailed, ReplyJob onReply, Job onReplyFailed, long timeoutMs, int priority, MessageSelector replySelector, SessionKey wrappedKey, Set wrappedTags) {
        super(ctx);
        _log = ctx.logManager().getLog(SendGarlicJob.class);
        if (config == null) throw new IllegalArgumentException("No config specified");
        if (config.getRecipient() == null) throw new IllegalArgumentException("No recipient in the config");
        //_target = target;
        _config = config;
        _onSend = onSend;
        _onSendFailed = onSendFailed;
        _onReply = onReply;
        _onReplyFailed = onReplyFailed;
        _timeoutMs = timeoutMs;
        _priority = priority;
        _replySelector = replySelector;
        _message = null;
        _wrappedKey = wrappedKey;
        _wrappedTags = wrappedTags;
    }
    
    public String getName() { return "Build Garlic Message"; }
    
    public void runJob() {
        long before = getContext().clock().now();
        _message = GarlicMessageBuilder.buildMessage(getContext(), _config, _wrappedKey, _wrappedTags);
        long after = getContext().clock().now();
        if ( (after - before) > 1000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Building the garlic took too long [" + (after-before)+" ms]");
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Building the garlic was fast! " + (after - before) + " ms");
        }
        getContext().jobQueue().addJob(new SendJob(getContext()));
    }
    
    private class SendJob extends JobImpl {
        public SendJob(RouterContext enclosingContext) {
            super(enclosingContext);
        }
        public String getName() { return "Send Built Garlic Message"; }
        public void runJob() {
            if (_config.getRecipient() != null)
                _log.info("sending garlic to recipient " + _config.getRecipient().getIdentity().getHash().toBase64());
            else
                _log.info("sending garlic to public key " + _config.getRecipientPublicKey());
            sendGarlic();
        }
    }
    
    private void sendGarlic() {
        OutNetMessage msg = new OutNetMessage(getContext());
        long when = _message.getMessageExpiration(); // + Router.CLOCK_FUDGE_FACTOR;
        msg.setExpiration(when);
        msg.setMessage(_message);
        msg.setOnFailedReplyJob(_onReplyFailed);
        msg.setOnFailedSendJob(_onSendFailed);
        msg.setOnReplyJob(_onReply);
        msg.setOnSendJob(_onSend);
        msg.setPriority(_priority);
        msg.setReplySelector(_replySelector);
        msg.setTarget(_config.getRecipient());
        //_log.info("Sending garlic message to [" + _config.getRecipient() + "] encrypted with " + _config.getRecipientPublicKey() + " or " + _config.getRecipient().getIdentity().getPublicKey());
        //_log.debug("Garlic config data:\n" + _config);
        //msg.setTarget(_target);
        getContext().outNetMessagePool().add(msg);
        _log.debug("Garlic message added to outbound network message pool");
    }
}

