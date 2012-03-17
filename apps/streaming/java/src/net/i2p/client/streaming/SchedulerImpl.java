package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;

/**
 * Base scheduler
 */
abstract class SchedulerImpl implements TaskScheduler {
    protected final I2PAppContext _context;
    protected final Log _log;
    
    public SchedulerImpl(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(SchedulerImpl.class);
    }
    
    protected void reschedule(long msToWait, Connection con) {
        _context.simpleScheduler().addEvent(con.getConnectionEvent(), msToWait);
    }
}
