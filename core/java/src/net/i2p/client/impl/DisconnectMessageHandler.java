package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.data.i2cp.DisconnectMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Handle I2CP disconnect messages from the router
 *
 * @author jrandom
 */
class DisconnectMessageHandler extends HandlerImpl {
    public DisconnectMessageHandler(I2PAppContext context) {
        super(context, DisconnectMessage.MESSAGE_TYPE);
    }
    
    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        String reason = ((DisconnectMessage)message).getReason();
        session.propogateError(reason, new I2PSessionException("Disconnect Message received: " + reason));
        session.destroySession(false);
        if (reason.contains("restart")) {
            Thread t = new I2PAppThread(new Reconnector(session), "Reconnect " + session, true);
            t.start();
        }
    }

    /** @since 0.8.8 */
    private static class Reconnector implements Runnable {
        private final I2PSessionImpl _session;

        public Reconnector(I2PSessionImpl session) {
             _session = session;
        }

        public void run() {
            try { Thread.sleep(40*1000); } catch (InterruptedException ie) {}
            _session.reconnect();
        }
    }
}
