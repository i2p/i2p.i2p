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
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.util.Log;

/**
 * Handle I2CP SessionStatusMessagese from the router, updating the session as
 * necssary.
 *
 * @author jrandom
 */
class SessionStatusMessageHandler extends HandlerImpl {
    public SessionStatusMessageHandler(I2PAppContext context) {
        super(context, SessionStatusMessage.MESSAGE_TYPE);
    }
    
    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        SessionStatusMessage msg = (SessionStatusMessage) message;
        session.setSessionId(msg.getSessionId());
        switch (msg.getStatus()) {
        case SessionStatusMessage.STATUS_CREATED:
            _log.info("Session created successfully");
            break;
        case SessionStatusMessage.STATUS_DESTROYED:
            _log.warn("Session destroyed");
            session.propogateError("Destroyed", new I2PSessionException("Session Status Message received"));
            //session.destroySession();
            session.reconnect(); // la la la
            break;
        case SessionStatusMessage.STATUS_INVALID:
            _log.warn("Session invalid");
            session.propogateError("Invalid", new I2PSessionException("Session Status Message received"));
            session.destroySession(); // ok, honor this destroy message, because we're b0rked
            break;
        case SessionStatusMessage.STATUS_UPDATED:
            _log.info("Session status updated");
            break;
        default:
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unknown session status sent: " + msg.getStatus());
        }
        return;
    }
}
