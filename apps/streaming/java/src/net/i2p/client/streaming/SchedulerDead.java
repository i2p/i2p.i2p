package net.i2p.client.streaming;

import net.i2p.I2PAppContext;

/**
 * <p>Scheduler used for after the final timeout has passed or the
 * connection was reset.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Both sides have closed and ACKed and the timeout has passed. <br>
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

    public SchedulerDead(I2PAppContext ctx) {
        super(ctx);
    }
    
    public boolean accept(Connection con) {
        if (con == null) return false;
        long timeSinceClose = _context.clock().now() - con.getDisconnectScheduledOn();
        boolean nothingLeftToDo = (con.getDisconnectScheduledOn() > 0) && 
                                  (timeSinceClose >= Connection.DISCONNECT_TIMEOUT);
        boolean timedOut = (con.getOptions().getConnectTimeout() < con.getLifetime()) && 
                           con.getSendStreamId() <= 0 &&
                           con.getLifetime() >= Connection.DISCONNECT_TIMEOUT;
        return nothingLeftToDo || timedOut;
    }
    
    public void eventOccurred(Connection con) {
        con.disconnectComplete();
    }
}
