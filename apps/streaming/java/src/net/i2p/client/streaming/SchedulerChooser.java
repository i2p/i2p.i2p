package net.i2p.client.streaming;

import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Examine a connection's state and pick the right scheduler for it.
 *
 */
class SchedulerChooser {
    private final I2PAppContext _context;
    private final Log _log;
    private final TaskScheduler _nullScheduler;
    /** list of TaskScheduler objects */
    private final List _schedulers;
    
    public SchedulerChooser(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(SchedulerChooser.class);
        _schedulers = createSchedulers();
        _nullScheduler = new NullScheduler();
    }
    
    public TaskScheduler getScheduler(Connection con) {
        for (int i = 0; i < _schedulers.size(); i++) {
            TaskScheduler scheduler = (TaskScheduler)_schedulers.get(i);
            if (scheduler.accept(con)) {
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Scheduling for " + con + " with " + scheduler.getClass().getName());
                return scheduler;
            }
        }
        return _nullScheduler;
    }
    
    private List createSchedulers() {
        List rv = new ArrayList(8);
        rv.add(new SchedulerHardDisconnected(_context));
        rv.add(new SchedulerPreconnect(_context));
        rv.add(new SchedulerConnecting(_context));
        rv.add(new SchedulerReceived(_context));
        rv.add(new SchedulerConnectedBulk(_context));
        rv.add(new SchedulerClosing(_context));
        rv.add(new SchedulerClosed(_context));
        rv.add(new SchedulerDead(_context));
        return rv;
    }

    private class NullScheduler implements TaskScheduler {
        private final Log _log;
        public NullScheduler() {
            _log = _context.logManager().getLog(NullScheduler.class);
        }
        
        public void eventOccurred(Connection con) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Yell at jrandom: Event occurred on " + con, new Exception("source"));
        }
        public boolean accept(Connection con) { return true; }
    };
}
