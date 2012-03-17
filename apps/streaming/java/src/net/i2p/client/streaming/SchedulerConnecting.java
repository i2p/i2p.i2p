package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * <p>Scheduler used once we've sent our SYN but it hasn't been ACKed yet.
 * This connection may or may not be locally created.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Packets sent but none ACKed</li>
 * </ul>
 *
 * <h2>Events:</h2><ul>
 * <li>Packets received (which may or may not ACK the ones sent)</li>
 * <li>Message flush (explicitly, through a full buffer, or stream closure)</li>
 * <li>Connection establishment timeout</li>
 * <li>RESET received</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <li>{@link SchedulerConnectedBulk connected} - after receiving an ACK</li>
 * <li>{@link SchedulerClosing closing} - after both sending and receiving a CLOSE</li>
 * <li>{@link SchedulerClosed closed} - after both sending and receiving ACKs on the CLOSE</li>
 * <li>{@link SchedulerDead dead} - after sending or receiving a RESET</li>
 * </ul>
 *
 */
class SchedulerConnecting extends SchedulerImpl {
    
    public SchedulerConnecting(I2PAppContext ctx) {
        super(ctx);
    }
    
    public boolean accept(Connection con) {
        if (con == null) return false;
        boolean notYetConnected = (con.getIsConnected()) &&
                                  //(con.getSendStreamId() == null) && // not null on recv
                                  (con.getLastSendId() >= 0) &&
                                  (con.getHighestAckedThrough() < 0) && 
                                  (!con.getResetReceived());
        return notYetConnected;
    }
    
    public void eventOccurred(Connection con) {
        long waited = _context.clock().now() - con.getCreatedOn();
        if ( (con.getOptions().getConnectTimeout() > 0) && 
             (con.getOptions().getConnectTimeout() <= waited) ) {
            con.setConnectionError("Timeout waiting for ack (waited " + waited + "ms)");
            con.disconnect(false);
            reschedule(0, con);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("waited too long: " + waited);
            return;
        } else {
            // should we be doing a con.sendAvailable here?
            if (con.getOptions().getConnectTimeout() > 0)
                reschedule(con.getOptions().getConnectTimeout(), con);
        }
        /*        
        long timeTillSend = con.getNextSendTime() - _context.clock().now();
        if ( (timeTillSend <= 0) && (con.getNextSendTime() > 0) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("send next on " + con);
            con.sendAvailable();
            con.setNextSendTime(-1);
        } else {
            if (con.getNextSendTime() > 0) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("time till send: " + timeTillSend + " on " + con);
                reschedule(timeTillSend, con);
            }
        }
        */
    }
}
