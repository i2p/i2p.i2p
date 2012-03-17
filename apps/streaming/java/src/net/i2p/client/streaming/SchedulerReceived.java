package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Scheduler used after receiving an inbound connection but before
 * we have sent our own SYN.
 *
 */
class SchedulerReceived extends SchedulerImpl {
    
    public SchedulerReceived(I2PAppContext ctx) {
        super(ctx);
    }
    
    public boolean accept(Connection con) {
        return (con != null) && 
               (con.getLastSendId() < 0) &&
               (con.getSendStreamId() > 0);
    }
    
    public void eventOccurred(Connection con) {
        if (con.getUnackedPacketsReceived() <= 0) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("hmm, state is received, but no unacked packets received?");
            return;
        }
        
        long timeTillSend = con.getNextSendTime() - _context.clock().now();
        if (timeTillSend <= 0) {
            if (con.getNextSendTime() > 0) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("received con... send a packet");
                con.sendAvailable();
                con.setNextSendTime(-1);
            } else {
                con.setNextSendTime(_context.clock().now() + con.getOptions().getSendAckDelay());
                reschedule(con.getOptions().getSendAckDelay(), con);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("received con... time till next send: " + timeTillSend);
            reschedule(timeTillSend, con);
        }
    }
}
