package net.i2p.util;

import java.util.List;

import net.i2p.I2PAppContext;

class Executor implements Runnable {
    private I2PAppContext _context;
    private Log _log;
    private final List _readyEvents;
    private SimpleStore runn;

    public Executor(I2PAppContext ctx, Log log, List events, SimpleStore x) {
        _context = ctx;
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
                    evt = (SimpleTimer.TimedEvent)_readyEvents.remove(0);
            }

            if (evt != null) {
                long before = _context.clock().now();
                try {
                    evt.timeReached();
                } catch (Throwable t) {
                    log("wtf, event borked: " + evt, t);
                }
                long time = _context.clock().now() - before;
                if ( (time > 1000) && (_log != null) && (_log.shouldLog(Log.WARN)) )
                    _log.warn("wtf, event execution took " + time + ": " + evt);
            }
        }
    }
    
    private void log(String msg, Throwable t) {
        synchronized (this) {
            if (_log == null) 
                _log = I2PAppContext.getGlobalContext().logManager().getLog(SimpleTimer.class);
        }
        _log.log(Log.CRIT, msg, t);
    }
}
