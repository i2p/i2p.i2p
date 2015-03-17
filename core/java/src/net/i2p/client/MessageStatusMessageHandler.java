package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.ReceiveMessageBeginMessage;
import net.i2p.util.Log;

/**
 * Handle I2CP MessageStatusMessages from the router.  This currently only takes
 * into account status of available, automatically prefetching them as soon as 
 * possible
 *
 */
class MessageStatusMessageHandler extends HandlerImpl {
    public MessageStatusMessageHandler(I2PAppContext context) {
        super(context, MessageStatusMessage.MESSAGE_TYPE);
    }
    
    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        MessageStatusMessage msg = (MessageStatusMessage) message;
        switch (msg.getStatus()) {
            case MessageStatusMessage.STATUS_AVAILABLE:
                ReceiveMessageBeginMessage m = new ReceiveMessageBeginMessage();
                m.setMessageId(msg.getMessageId());
                m.setSessionId(msg.getSessionId());
                try {
                    session.sendMessage(m);
                } catch (I2PSessionException ise) {
                    _log.error("Error asking for the message", ise);
                }
                return;
            case MessageStatusMessage.STATUS_SEND_ACCEPTED:
                session.receiveStatus((int)msg.getMessageId(), msg.getNonce(), msg.getStatus());
                // noop
                return;
            case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_SUCCESS:
            case MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS:
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message delivery succeeded for message " + msg.getMessageId());
                //if (!skipStatus)
                session.receiveStatus((int)msg.getMessageId(), msg.getNonce(), msg.getStatus());
                return;
            case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE:
            case MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE:
                if (_log.shouldLog(Log.INFO))
                    _log.info("Message delivery FAILED for message " + msg.getMessageId());
                //if (!skipStatus)
                session.receiveStatus((int)msg.getMessageId(), msg.getNonce(), msg.getStatus());
                return;
            default:
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Invalid message delivery status received: " + msg.getStatus());
        }
    }
}