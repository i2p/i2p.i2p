package net.i2p.router.tunnel.pool;

import java.util.Set;

import net.i2p.data.Certificate;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.GarlicMessageBuilder;
import net.i2p.router.message.PayloadGarlicConfig;
import net.i2p.util.Log;

/**
 * Wrap the tunnel request in a garlic to the participant, and then send it out
 * a tunnel.
 *
 */
class SendGarlicMessageJob extends JobImpl {
    private Log _log;
    private I2NPMessage _payload;
    private RouterInfo _target;
    private MessageSelector _replySelector;
    private ReplyJob _onReply;
    private Job _onTimeout;
    private SessionKey _sentKey;
    private Set _sentTags;
    
    /** only elGamal the message, never use session tags */
    private static final boolean FORCE_ELGAMAL = false;
    
    public SendGarlicMessageJob(RouterContext ctx, I2NPMessage payload, RouterInfo target, MessageSelector selector, ReplyJob onReply, Job onTimeout, SessionKey sentKey, Set sentTags) {
        super(ctx);
        _log = ctx.logManager().getLog(SendGarlicMessageJob.class);
        _payload = payload;
        _target = target;
        _replySelector = selector;
        _onReply = onReply;
        _onTimeout = onTimeout;
        _sentKey = sentKey;
        _sentTags = sentTags;
    }
    public String getName() { return "build and send request garlic"; }
    
    public void runJob() {
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
    
        PayloadGarlicConfig payload = new PayloadGarlicConfig();
        payload.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        payload.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        payload.setPayload(_payload);
        payload.setRecipient(_target);
        payload.setDeliveryInstructions(instructions);
        payload.setRequestAck(false);
        payload.setExpiration(_payload.getMessageExpiration());
        int timeout = (int)(payload.getExpiration() - getContext().clock().now());
        
        GarlicMessage msg = null;
        if (FORCE_ELGAMAL)
            msg = GarlicMessageBuilder.buildMessage(getContext(), payload, _sentKey, _sentTags, 0, true);
        else
            msg = GarlicMessageBuilder.buildMessage(getContext(), payload, _sentKey, _sentTags);

        // so we will look for the reply
        OutNetMessage dummyMessage = getContext().messageRegistry().registerPending(_replySelector, _onReply, _onTimeout, timeout);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Scheduling timeout job (" + _onTimeout + ") to be run in " + timeout + "ms");
  
        // now find an outbound tunnel and send 'er off
        TunnelInfo out = getContext().tunnelManager().selectOutboundTunnel();
        if (out == null) {
            if (_onTimeout != null) 
                getContext().jobQueue().addJob(_onTimeout);
            getContext().messageRegistry().unregisterPending(dummyMessage);
            return;
        }
        TunnelId outId = out.getSendTunnelId(0);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Dispatching the garlic request out " + outId + " targetting " + _target.getIdentity().calculateHash().toBase64().substring(0,4));
        getContext().tunnelDispatcher().dispatchOutbound(msg, outId, _target.getIdentity().calculateHash());
    }
}
