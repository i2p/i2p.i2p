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
import net.i2p.util.Log;

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
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        SetDateMessage msg = (SetDateMessage) message;
        // Only do this if we are NOT in the router context;
        // otherwise, it sets getUpdatedSuccessfully() in Clock when all
        // we did was get the time from ourselves.
        if (!_context.isRouterContext())
            Clock.getInstance().setNow(msg.getDate().getTime());
        // TODO - save router's version string for future reference
        session.dateUpdated();
    }
}
