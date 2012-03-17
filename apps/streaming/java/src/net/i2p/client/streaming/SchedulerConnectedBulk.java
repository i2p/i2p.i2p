package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * <p>Scheduler used for after our SYN has been sent and ACKed but one
 * (or more) sides haven't closed the stream yet.  In addition, the 
 * stream must be using the BULK profile, rather than the INTERACTIVE
 * profile.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Packets sent and ACKs received.</li>
 * <li>At least one direction is not closed</li>
 * </ul>
 *
 * <h2>Events:</h2><ul>
 * <li>Packets received (which may or may not ACK the ones sent)</li>
 * <li>Message flush (explicitly, through a full buffer, or stream closure)</li>
 * <li>RESET received</li>
 * <li>Message sending fails (error talking to the session)</li>
 * <li>Message sending fails (too many resends)</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <li>{@link SchedulerClosing closing} - after both sending and receiving a CLOSE</li>
 * <li>{@link SchedulerClosed closed} - after both sending and receiving ACKs on the CLOSE</li>
 * <li>{@link SchedulerDead dead} - after sending or receiving a RESET</li>
 * </ul>
 *
 */
class SchedulerConnectedBulk extends SchedulerImpl {

    public SchedulerConnectedBulk(I2PAppContext ctx) {
        super(ctx);
    }
    
    public boolean accept(Connection con) {
        boolean ok = (con != null) && 
                     (con.getHighestAckedThrough() >= 0) &&
                     (con.getOptions().getProfile() == ConnectionOptions.PROFILE_BULK) &&
                     (!con.getResetReceived()) &&
                     ( (con.getCloseSentOn() <= 0) || (con.getCloseReceivedOn() <= 0) );
        if (!ok) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("con: " + con + " closeSentOn: " + con.getCloseSentOn() 
            //               + " closeReceivedOn: " + con.getCloseReceivedOn());
        }
        return ok;
    }
    
    public void eventOccurred(Connection con) {
        if (con.getNextSendTime() <= 0) 
            return;
        
        long timeTillSend = con.getNextSendTime() - _context.clock().now();
        
        if (timeTillSend <= 0) {
            con.setNextSendTime(-1);
            con.sendAvailable();
        } else {
            reschedule(timeTillSend, con);
        }
    }
}
