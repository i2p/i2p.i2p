package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * <p>Scheduler used for after the final timeout has passed or the
 * connection was reset.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Both sides have closed and ACKed and the timeout has passed. <br />
 *     <b>or</b></li>
 * <li>A RESET was received</li>
 * </ul>
 *
 * <h2>Events:</h2><ul>
 * <li>None</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <li>None</li>
 * </ul>
 *
 *
 */
class SchedulerDead extends SchedulerImpl {
    private Log _log;
    public SchedulerDead(I2PAppContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(SchedulerDead.class);
    }
    
    public boolean accept(Connection con) {
        boolean ok = (con != null) && 
                     (con.getResetReceived()) ||
                     ((con.getCloseSentOn() > 0) &&
                      (con.getCloseReceivedOn() > 0) &&
                      (con.getUnackedPacketsReceived() <= 0) &&
                      (con.getUnackedPacketsSent() <= 0) &&
                      (con.getCloseSentOn() + SchedulerClosed.CLOSE_TIMEOUT <= _context.clock().now()));
        return ok;
    }
    
    public void eventOccurred(Connection con) {
        con.disconnectComplete();
    }
}
