package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * <p>Scheduler used for after both SYNs have been ACKed and both sides 
 * have closed the stream, but either we haven't ACKed their close or
 * they haven't ACKed ours.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Both sides have closed.</li>
 * <li>At least one direction has not ACKed the close.</li>
 * </ul>
 *
 * <h2>Events:</h2><ul>
 * <li>Packets received (which may or may not ACK the ones sent)</li>
 * <li>RESET received</li>
 * <li>Message sending fails (error talking to the session)</li>
 * <li>Message sending fails (too many resends)</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <li>{@link SchedulerClosed closed} - after both sending and receiving ACKs on the CLOSE</li>
 * <li>{@link SchedulerDead dead} - after sending or receiving a RESET</li>
 * </ul>
 *
 */
class SchedulerClosing extends SchedulerImpl {

    public SchedulerClosing(I2PAppContext ctx) {
        super(ctx);
    }
    
    public boolean accept(Connection con) {
        if (con == null)
            return false;
        long timeSinceClose = _context.clock().now() - con.getCloseSentOn();
        boolean ok = (!con.getResetSent()) && 
                     (!con.getResetReceived()) &&
                     ( (con.getCloseSentOn() > 0) || (con.getCloseReceivedOn() > 0) ) &&
                     (timeSinceClose < Connection.DISCONNECT_TIMEOUT) &&
                     ( (con.getUnackedPacketsReceived() > 0) || (con.getUnackedPacketsSent() > 0) );
        return ok;
    }
    
    public void eventOccurred(Connection con) {
        long nextSend = con.getNextSendTime();
        long now = _context.clock().now();
        long remaining;
        if (nextSend <= 0) {
            remaining = con.getOptions().getSendAckDelay();
            nextSend = now + remaining;
            con.setNextSendTime(nextSend);
        } else {
            remaining = nextSend - now;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Event occurred w/ remaining: " + remaining + " on " + con);
        if (remaining <= 0) {
            if (con.getCloseSentOn() <= 0) {
                con.sendAvailable();
            } else {
                //con.ackImmediately();
            }
            con.setNextSendTime(now + con.getOptions().getSendAckDelay());
        } else {
            //if (remaining < 5*1000)
            //    remaining = 5*1000;
            //con.setNextSendTime(when
            reschedule(remaining, con);
        }
    }
}
