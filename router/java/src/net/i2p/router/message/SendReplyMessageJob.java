package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.SourceRouteBlock;
import net.i2p.data.i2np.SourceRouteReplyMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Send a SourceRouteReplyMessage to the location specified in the source route block.
 * This uses the simplest technique (don't garlic, and send direct to where the
 * SourceRouteBlock requested), but it could instead garlic it and send it via a
 * tunnel or garlic route it additionally)
 *
 */
public class SendReplyMessageJob extends JobImpl {
    private Log _log;
    private SourceRouteBlock _block;
    private I2NPMessage _message;
    private int _priority;
    
    public SendReplyMessageJob(RouterContext context, SourceRouteBlock block, I2NPMessage message, int priority) {
        super(context);
        _log = context.logManager().getLog(SendReplyMessageJob.class);
        _block = block;
        _message = message;
        _priority = priority;
    }
    
    public void runJob() {
        SourceRouteReplyMessage msg = new SourceRouteReplyMessage(getContext());
        msg.setMessage(_message);
        msg.setEncryptedHeader(_block.getData());
        msg.setMessageExpiration(_message.getMessageExpiration());
        
        send(msg);
    }
    
    /**
     * Send the message on its way. <p />
     *
     * This could garlic route the message to the _block.getRouter, or it could
     * send it there via a tunnel, or it could just send it direct. <p />
     *
     * For simplicity, its currently going direct.
     *
     */
    protected void send(I2NPMessage msg) {
        _log.info("Sending reply with " + _message.getClass().getName() + " in a sourceRouteeplyMessage to " + _block.getRouter().toBase64());
        int timeout = (int)(msg.getMessageExpiration().getTime()-getContext().clock().now());
        SendMessageDirectJob j = new SendMessageDirectJob(getContext(), msg, _block.getRouter(), timeout, _priority);
        getContext().jobQueue().addJob(j);
    }
    
    public String getName() { return "Send Reply Message"; }
}
