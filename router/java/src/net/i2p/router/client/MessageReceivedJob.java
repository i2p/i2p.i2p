package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Destination;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Async job to notify the client that a new message is available for them
 *
 */
class MessageReceivedJob extends JobImpl {
    private Log _log;
    private ClientConnectionRunner _runner;
    private Payload _payload;
    public MessageReceivedJob(RouterContext ctx, ClientConnectionRunner runner, Destination toDest, Destination fromDest, Payload payload) {
        super(ctx);
        _log = ctx.logManager().getLog(MessageReceivedJob.class);
        _runner = runner;
        _payload = payload;
    }
    
    public String getName() { return "Deliver New Message"; }
    public void runJob() {
        if (_runner.isDead()) return;
        MessageId id = new MessageId();
        id.setMessageId(ClientConnectionRunner.getNextMessageId());
        _runner.setPayload(id, _payload);
        messageAvailable(id, _payload.getSize());
    }
    
    /**
     * Deliver notification to the client that the given message is available.
     */
    public void messageAvailable(MessageId id, long size) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending message available: " + id + " to sessionId " + _runner.getSessionId() 
                       + " (with nonce=1)", new Exception("available"));
        MessageStatusMessage msg = new MessageStatusMessage();
        msg.setMessageId(id.getMessageId());
        msg.setSessionId(_runner.getSessionId().getSessionId());
        msg.setSize(size);
        // has to be >= 0, it is initialized to -1
        msg.setNonce(1);
        msg.setStatus(MessageStatusMessage.STATUS_AVAILABLE);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error writing out the message status message", ime);
        }
    }
}
