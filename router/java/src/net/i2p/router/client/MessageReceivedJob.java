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
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Async job to notify the client that a new message is available for them,
 * or just send it directly if specified.
 *
 */
class MessageReceivedJob extends JobImpl {
    private final Log _log;
    private final ClientConnectionRunner _runner;
    private final Payload _payload;
    private final boolean _sendDirect;

    public MessageReceivedJob(RouterContext ctx, ClientConnectionRunner runner, Destination toDest,
                              Destination fromDest, Payload payload, boolean sendDirect) {
        super(ctx);
        _log = ctx.logManager().getLog(MessageReceivedJob.class);
        _runner = runner;
        _payload = payload;
        _sendDirect = sendDirect;
    }
    
    public String getName() { return "Deliver New Message"; }

    public void runJob() {
        if (_runner.isDead()) return;
        MessageId id = null;
        long nextID = _runner.getNextMessageId();
        try {
            if (_sendDirect) {
                sendMessage(nextID);
            } else {
                id = new MessageId(nextID);
                _runner.setPayload(id, _payload);
                messageAvailable(id, _payload.getSize());
            }
        } catch (I2CPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error writing out the message", ime);
            if (!_sendDirect)
                _runner.removePayload(id);
        }
    }
    
    /**
     * Deliver notification to the client that the given message is available.
     */
    private void messageAvailable(MessageId id, long size) throws I2CPMessageException {
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Sending message available: " + id + " to sessionId " + _runner.getSessionId() 
        //               + " (with nonce=1)", new Exception("available"));
        MessageStatusMessage msg = new MessageStatusMessage();
        msg.setMessageId(id.getMessageId());
        msg.setSessionId(_runner.getSessionId().getSessionId());
        msg.setSize(size);
        // has to be >= 0, it is initialized to -1
        msg.setNonce(1);
        msg.setStatus(MessageStatusMessage.STATUS_AVAILABLE);
        _runner.doSend(msg);
    }
    
    /**
     *  Deliver the message directly, skip notification
     *  @since 0.9.4
     */
    private void sendMessage(long id) throws I2CPMessageException {
        MessagePayloadMessage msg = new MessagePayloadMessage();
        msg.setMessageId(id);
        msg.setSessionId(_runner.getSessionId().getSessionId());
        msg.setPayload(_payload);
        _runner.doSend(msg);
    }
}
