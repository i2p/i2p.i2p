package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * <p>Scheduler used for locally created connections where we have not yet
 * sent the initial SYN packet.</p>
 *
 * <h2>Entry conditions:</h2><ul>
 * <li>Locally created</li>
 * <li>No packets sent or received</li>
 * </ul>
 *
 * <h2>Events:</h2><ul>
 * <li>Message flush (explicitly, through a full buffer, or stream closure)</li>
 * <li>Initial delay timeout (causing implicit flush of any data available)</li>
 * </ul>
 *
 * <h2>Next states:</h2>
 * <li>{@link SchedulerConnecting connecting} - after sending a packet</li>
 * </ul>
 */
class SchedulerPreconnect extends SchedulerImpl {
    
    public SchedulerPreconnect(I2PAppContext ctx) {
        super(ctx);
    }
    
    public boolean accept(Connection con) {
        return (con != null) && 
               (con.getSendStreamId() <= 0) &&
               (con.getLastSendId() < 0);
    }
    
    public void eventOccurred(Connection con) {
        if (con.getNextSendTime() < 0)
            con.setNextSendTime(_context.clock().now() + con.getOptions().getConnectDelay());
        
        long timeTillSend = con.getNextSendTime() - _context.clock().now();
        if (timeTillSend <= 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Send available for the SYN on " + con);
            con.sendAvailable();
            con.setNextSendTime(-1);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Wait " + timeTillSend + " before sending the SYN on " + con);
            reschedule(timeTillSend, con);
        }
    }
}
