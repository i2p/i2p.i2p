package net.i2p.util;

import java.util.List;

import net.i2p.I2PAppContext;

/**
 *  Deprecated - used only by SimpleTimer
 */
class Executor implements Runnable {
    private final I2PAppContext _context;
    private final Log _log;
    private final List<SimpleTimer.TimedEvent> _readyEvents;
    private final SimpleStore runn;

    public Executor(I2PAppContext ctx, Log log, List<SimpleTimer.TimedEvent> events, SimpleStore x) {
        _context = ctx;
        _log = log;
        _readyEvents = events;
        runn = x;
    }

    public void run() {
        while(runn.getAnswer()) {
            SimpleTimer.TimedEvent evt = null;
            synchronized (_readyEvents) {
                if (_readyEvents.isEmpty()) 
                    try { _readyEvents.wait(); } catch (InterruptedException ie) {}
                if (!_readyEvents.isEmpty()) 
                    evt = _readyEvents.remove(0);
            }

            if (evt != null) {
                long before = _context.clock().now();
                try {
                    evt.timeReached();
                } catch (Throwable t) {
                    _log.error("Executing task " + evt + " exited unexpectedly, please report", t);
                }
                long time = _context.clock().now() - before;
                if ( (time > 1000) && (_log.shouldLog(Log.WARN)) )
                    _log.warn("wtf, event execution took " + time + ": " + evt);
            }
        }
    }
}
