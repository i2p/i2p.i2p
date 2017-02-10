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
import net.i2p.data.i2cp.SessionId;
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
    private final Destination _toDest;
    private final Payload _payload;
    private final boolean _sendDirect;

    /**
     *  @param toDest non-null, required to pick session
     *  @param fromDest ignored, generally null
     */
    public MessageReceivedJob(RouterContext ctx, ClientConnectionRunner runner, Destination toDest,
                              Destination fromDest, Payload payload, boolean sendDirect) {
        super(ctx);
        _log = ctx.logManager().getLog(MessageReceivedJob.class);
        _runner = runner;
        _toDest = toDest;
        _payload = payload;
        _sendDirect = sendDirect;
    }
    
    public String getName() { return "Deliver New Message"; }

    public void runJob() {
        receiveMessage();
    }

    /**
     *  Same as runJob() but with a return value
     *  @return success
     *  @since 0.9.29
     */
    public boolean receiveMessage() {
        if (_runner.isDead())
            return false;
        MessageId id = null;
        try {
            long nextID = _runner.getNextMessageId();
            if (_sendDirect) {
                sendMessage(nextID);
            } else {
                id = new MessageId(nextID);
                _runner.setPayload(id, _payload);
                messageAvailable(id, _payload.getSize());
            }
            return true;
        } catch (I2CPMessageException ime) {
            String msg = "Error sending data to client " + _runner.getDestHash();
            if (_log.shouldWarn())
                _log.warn(msg, ime);
            else
                _log.logAlways(Log.WARN, msg);
            if (id != null && !_sendDirect)
                _runner.removePayload(id);
            return false;
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
        SessionId sid = _runner.getSessionId(_toDest.calculateHash());
        if (sid == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No session for " + _toDest.calculateHash());
            return;
        }
        msg.setSessionId(sid.getSessionId());
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
        SessionId sid = _runner.getSessionId(_toDest.calculateHash());
        if (sid == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No session for " + _toDest.calculateHash());
            return;
        }
        msg.setSessionId(sid.getSessionId());
        msg.setPayload(_payload);
        _runner.doSend(msg);
    }
}
