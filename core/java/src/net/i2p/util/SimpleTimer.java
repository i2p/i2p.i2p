package net.i2p.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.i2p.I2PAppContext;

/**
 * Simple event scheduler - toss an event on the queue and it gets fired at the
 * appropriate time.  The method that is fired however should NOT block (otherwise
 * they b0rk the timer).
 *
 */
public class SimpleTimer {
    private static final SimpleTimer _instance = new SimpleTimer();
    public static SimpleTimer getInstance() { return _instance; }
    private Log _log;
    private Map _events;
    
    private SimpleTimer() {
        _events = new TreeMap();
        I2PThread runner = new I2PThread(new SimpleTimerRunner());
        runner.setName("SimpleTimer");
        runner.setDaemon(true);
        runner.start();
    }
    
    /**
     * Queue up the given event to be fired no sooner than timeoutMs from now
     *
     */
    public void addEvent(TimedEvent event, long timeoutMs) {
        long eventTime = System.currentTimeMillis() + timeoutMs;
        synchronized (_events) {
            while (_events.containsKey(new Long(eventTime)))
                eventTime++;
            _events.put(new Long(eventTime), event);
            _events.notifyAll();
        }
    }
    
    /**
     * Simple interface for events to be queued up and notified on expiration
     */
    public interface TimedEvent {
        /** 
         * the time requested has been reached (this call should NOT block,
         * otherwise the whole SimpleTimer gets backed up)
         *
         */
        public void timeReached();
    }
    
    private void log(String msg, Throwable t) {
        synchronized (this) {
            if (_log == null) 
                _log = I2PAppContext.getGlobalContext().logManager().getLog(SimpleTimer.class);
        }
        _log.log(Log.CRIT, msg, t);
    }
    
    private class SimpleTimerRunner implements Runnable {
        public void run() {
            while (true) {
                try {
                    synchronized (_events) {
                        if (_events.size() <= 0)
                            _events.wait();
                        long now = System.currentTimeMillis();
                        long nextEventDelay = -1;
                        List removed = null;
                        for (Iterator iter = _events.keySet().iterator(); iter.hasNext(); ) {
                            Long when = (Long)iter.next();
                            if (when.longValue() <= now) {
                                TimedEvent evt = (TimedEvent)_events.get(when);
                                try {
                                    evt.timeReached();
                                } catch (Throwable t) {
                                    log("wtf, event borked: " + evt, t);
                                }
                                if (removed == null)
                                    removed = new ArrayList(1);
                                removed.add(when);
                            } else {
                                nextEventDelay = when.longValue() - now;
                                break;
                            }
                        }
                        if (removed != null) {
                            for (int i = 0; i < removed.size(); i++) 
                                _events.remove(removed.get(i));
                        }
                        if (nextEventDelay != -1)
                            _events.wait(nextEventDelay);
                        else
                            _events.wait();
                    }
                } catch (InterruptedException ie) {}
            }
        }
    }
}
