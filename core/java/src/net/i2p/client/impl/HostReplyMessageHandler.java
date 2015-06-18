package net.i2p.client.impl;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */

import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.HostReplyMessage;
import net.i2p.util.Log;

import net.i2p.data.Destination;

/**
 * Handle I2CP dest replies from the router
 *
 * @since 0.9.11
 */
class HostReplyMessageHandler extends HandlerImpl {

    public HostReplyMessageHandler(I2PAppContext ctx) {
        super(ctx, HostReplyMessage.MESSAGE_TYPE);
    }
    
    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        HostReplyMessage msg = (HostReplyMessage) message;
        Destination d = msg.getDestination();
        long id = msg.getReqID();
        if (d != null) {
            session.destReceived(id, d);
        } else {
            session.destLookupFailed(id);
        }
    }
}
