package net.i2p.client.streaming;

import net.i2p.I2PAppContext;

/**
 * <p>Scheduler used after we've locally done a hard disconnect, 
 * but the final timeout hasn't passed.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Locally disconnected hard.</li>
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
class SchedulerHardDisconnected extends SchedulerImpl {

    public SchedulerHardDisconnected(I2PAppContext ctx) {
        super(ctx);
    }
    
    public boolean accept(Connection con) {
        if (con == null) return false;
        long timeSinceClose = _context.clock().now() - con.getCloseSentOn();
        if (con.getResetSent())
            timeSinceClose = _context.clock().now() - con.getResetSentOn();
        boolean ok = (con.getHardDisconnected() || con.getResetSent()) && 
                     (timeSinceClose < Connection.DISCONNECT_TIMEOUT);
        return ok || con.getResetReceived();
    }
    
    public void eventOccurred(Connection con) {
        // noop.  we do the timeout through the simpleTimer anyway
    }
}
