package net.i2p.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
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
    /** event time (Long) to event (TimedEvent) mapping */
    private Map _events;
    /** event (TimedEvent) to event time (Long) mapping */
    private Map _eventTimes;
    
    private SimpleTimer() {
        _events = new TreeMap();
        _eventTimes = new HashMap();
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
            // remove the old scheduled position, then reinsert it
            if (_eventTimes.containsKey(event))
                _events.remove(_eventTimes.get(event));
            while (_events.containsKey(new Long(eventTime)))
                eventTime++;
            _events.put(new Long(eventTime), event);
            _eventTimes.put(event, new Long(eventTime));
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
            List eventsToFire = new ArrayList(1);
            List timesToRemove = new ArrayList(1);
            while (true) {
                try {
                    synchronized (_events) {
                        if (_events.size() <= 0)
                            _events.wait();
                        long now = System.currentTimeMillis();
                        long nextEventDelay = -1;
                        for (Iterator iter = _events.keySet().iterator(); iter.hasNext(); ) {
                            Long when = (Long)iter.next();
                            if (when.longValue() <= now) {
                                TimedEvent evt = (TimedEvent)_events.get(when);
                                eventsToFire.add(evt);
                                timesToRemove.add(when);
                            } else {
                                nextEventDelay = when.longValue() - now;
                                break;
                            }
                        }
                        if (timesToRemove.size() > 0) { 
                            for (int i = 0; i < timesToRemove.size(); i++) 
                                _events.remove(timesToRemove.get(i));
                            for (int i = 0; i < eventsToFire.size(); i++)
                                _eventTimes.remove(eventsToFire.get(i));
                        } else { 
                            if (nextEventDelay != -1)
                                _events.wait(nextEventDelay);
                            else
                                _events.wait();
                        }
                    }
                } catch (InterruptedException ie) {
                    // ignore
                } catch (Throwable t) {
                    if (_log != null) {
                        _log.log(Log.CRIT, "Uncaught exception in the SimpleTimer!", t);
                    } else {
                        System.err.println("Uncaught exception in SimpleTimer");
                        t.printStackTrace();
                    }
                }
                
                for (int i = 0; i < eventsToFire.size(); i++) {
                    TimedEvent evt = (TimedEvent)eventsToFire.get(i);
                    try {
                        evt.timeReached();
                    } catch (Throwable t) {
                        log("wtf, event borked: " + evt, t);
                    }
                }
                eventsToFire.clear();
                timesToRemove.clear();
            }
        }
    }
}
