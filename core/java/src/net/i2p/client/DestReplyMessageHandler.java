package net.i2p.client;

/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */

import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.util.Log;

import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 * Handle I2CP dest replies from the router
 */
class DestReplyMessageHandler extends HandlerImpl {
    public DestReplyMessageHandler(I2PAppContext ctx) {
        super(ctx, DestReplyMessage.MESSAGE_TYPE);
    }
    
    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        DestReplyMessage msg = (DestReplyMessage) message;
        Destination d = msg.getDestination();
        if (d != null) {
            session.destReceived(d);
        } else {
            Hash h = msg.getHash();
            if (h != null)
                session.destLookupFailed(h);
        }
        // else let it time out
    }
}
