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
import net.i2p.data.i2cp.SetDateMessage;
import net.i2p.util.Clock;

/**
 * Handle I2CP time messages from the router
 *
 * @author jrandom
 */
class SetDateMessageHandler extends HandlerImpl {
    public SetDateMessageHandler(I2PAppContext ctx) {
        super(ctx, SetDateMessage.MESSAGE_TYPE);
    }

    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        _log.debug("Handle message " + message);
        SetDateMessage msg = (SetDateMessage) message;
        Clock.getInstance().setNow(msg.getDate().getTime());
        session.dateUpdated();
    }
}