package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer;
import net.i2p.util.Log;

/**
 * Base scheduler
 */
abstract class SchedulerImpl implements TaskScheduler {
    protected I2PAppContext _context;
    private Log _log;
    
    public SchedulerImpl(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(SchedulerImpl.class);
    }
    
    protected void reschedule(long msToWait, Connection con) {
        SimpleTimer.getInstance().addEvent(new ConEvent(con), msToWait);
    }
    
    private class ConEvent implements SimpleTimer.TimedEvent {
        private Connection _connection;
        private Exception _addedBy;
        public ConEvent(Connection con) { 
            _connection = con; 
            _addedBy = new Exception("added by");
        }
        public void timeReached() {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("firing event on " + _connection, _addedBy);
            _connection.eventOccurred(); 
        }
    }
}
