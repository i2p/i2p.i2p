package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.SessionStatusMessage;

/**
 * Handle I2CP SessionStatusMessagese from the router, updating the session as
 * necssary.
 *
 * @author jrandom
 */
class SessionStatusMessageHandler extends HandlerImpl {
    public SessionStatusMessageHandler() {
        super(SessionStatusMessage.MESSAGE_TYPE);
    }

    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        _log.debug("Handle message " + message);
        SessionStatusMessage msg = (SessionStatusMessage) message;
        session.setSessionId(msg.getSessionId());
        switch (msg.getStatus()) {
        case SessionStatusMessage.STATUS_CREATED:
            _log.info("Session created successfully");
            break;
        case SessionStatusMessage.STATUS_DESTROYED:
            _log.info("Session destroyed");
            session.destroySession();
            break;
        case SessionStatusMessage.STATUS_INVALID:
            session.destroySession();
            break;
        case SessionStatusMessage.STATUS_UPDATED:
            _log.info("Session status updated");
            break;
        default:
            _log.warn("Unknown session status sent: " + msg.getStatus());
        }
        return;
    }
}