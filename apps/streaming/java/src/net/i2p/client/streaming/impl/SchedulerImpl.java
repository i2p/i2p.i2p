package net.i2p.client.streaming.impl;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

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
        con.scheduleConnectionEvent(msToWait);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
