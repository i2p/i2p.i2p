package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * <p>Scheduler used for after both sides have had their close packets
 * ACKed, but the final timeout hasn't passed.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Both sides have closed and ACKed.</li>
 * <li>Less than the final timeout period has passed since the last ACK.</li>
 * </ul>
 *
 * <h2>Events:</h2><ul>
 * <li>Packets received</li>
 * <li>RESET received</li>
 * <li>Message sending fails (error talking to the session)</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <li>{@link SchedulerDead dead} - after the final timeout passes</li>
 * </ul>
 *
 *
 */
class SchedulerClosed extends SchedulerImpl {
    private Log _log;
    public SchedulerClosed(I2PAppContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(SchedulerClosed.class);
    }
    
    public boolean accept(Connection con) {
        boolean ok = (con != null) && 
                     (con.getCloseSentOn() > 0) &&
                     (con.getCloseReceivedOn() > 0) &&
                     (con.getUnackedPacketsReceived() <= 0) &&
                     (con.getUnackedPacketsSent() <= 0) &&
                     (!con.getResetReceived()) &&
                     (con.getCloseSentOn() + Connection.DISCONNECT_TIMEOUT > _context.clock().now());
        return ok;
    }
    
    public void eventOccurred(Connection con) {
        long timeLeft = con.getCloseSentOn() + Connection.DISCONNECT_TIMEOUT - _context.clock().now();
        reschedule(timeLeft, con);
    }
}
