package net.i2p.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.i2p.I2PAppContext;

class Executor implements Runnable {
    private I2PAppContext _context;
    private Log _log;
    private List _readyEvents;
    public Executor(I2PAppContext ctx, Log log, List events) {
        _context = ctx;
        _readyEvents = events;
    }
    public void run() {
        while (true) {
            SimpleTimer.TimedEvent evt = null;
            synchronized (_readyEvents) {
                if (_readyEvents.size() <= 0) 
                    try { _readyEvents.wait(); } catch (InterruptedException ie) {}
                if (_readyEvents.size() > 0) 
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
