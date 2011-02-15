package net.i2p.util;

import java.util.ArrayList;
import java.util.HashMap;
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
 * WARNING - Deprecated.
 * This is an inefficient mess. Use SimpleScheduler or SimpleTimer2 if possible.
 */
public class SimpleTimer {
    private static final SimpleTimer _instance = new SimpleTimer();
    public static SimpleTimer getInstance() { return _instance; }
    private final I2PAppContext _context;
    private final Log _log;
    /** event time (Long) to event (TimedEvent) mapping */
    private final TreeMap _events;
    /** event (TimedEvent) to event time (Long) mapping */
    private Map _eventTimes;
    private final List _readyEvents;
    private SimpleStore runn;
    private static final int MIN_THREADS = 2;
    private static final int MAX_THREADS = 4;

    protected SimpleTimer() { this("SimpleTimer"); }
    protected SimpleTimer(String name) {
        runn = new SimpleStore(true);
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(SimpleTimer.class);
        _events = new TreeMap();
        _eventTimes = new HashMap(256);
        _readyEvents = new ArrayList(4);
        I2PThread runner = new I2PThread(new SimpleTimerRunner());
        runner.setName(name);
        runner.setDaemon(true);
        runner.start();
        long maxMemory = Runtime.getRuntime().maxMemory();
        int threads = (int) Math.max(MIN_THREADS, Math.min(MAX_THREADS, 1 + (maxMemory / (32*1024*1024))));
        for (int i = 1; i <= threads ; i++) {
            I2PThread executor = new I2PThread(new Executor(_context, _log, _readyEvents, runn));
            executor.setName(name + "Executor " + i + '/' + threads);
            executor.setDaemon(true);
            executor.start();
        }
    }
    
    /**
     * Removes the SimpleTimer.
     */
    public void removeSimpleTimer() {
        synchronized(_events) {
            runn.setAnswer(false);
            _events.notifyAll();
        }
    }

    /**
     * 
     * @param event
     * @param timeoutMs
     */
    public void reschedule(TimedEvent event, long timeoutMs) {
        addEvent(event, timeoutMs, false);
    }
    
    /**
     * Queue up the given event to be fired no sooner than timeoutMs from now.
     * However, if this event is already scheduled, the event will be scheduled
     * for the earlier of the two timeouts, which may be before this stated 
     * timeout.  If this is not the desired behavior, call removeEvent first.
     *
     * @param event
     * @param timeoutMs 
     */
    public void addEvent(TimedEvent event, long timeoutMs) { addEvent(event, timeoutMs, true); }
    /**
     * @param event 
     * @param timeoutMs 
     * @param useEarliestTime if its already scheduled, use the earlier of the 
     *                        two timeouts, else use the later
     */
    public void addEvent(TimedEvent event, long timeoutMs, boolean useEarliestTime) {
        int totalEvents = 0;
        long now = System.currentTimeMillis();
        long eventTime = now + timeoutMs;
        Long time = Long.valueOf(eventTime);
        synchronized (_events) {
            // remove the old scheduled position, then reinsert it
            Long oldTime = (Long)_eventTimes.get(event);
            if (oldTime != null) {
                if (useEarliestTime) {
                    if (oldTime.longValue() < eventTime) {
                        _events.notifyAll();
                        return; // already scheduled for sooner than requested
                    } else {
                        _events.remove(oldTime);
                    }
                } else {
                    if (oldTime.longValue() > eventTime) {
                        _events.notifyAll();
                        return; // already scheduled for later than the given period
                    } else {
                        _events.remove(oldTime);
                    }
                }
            }
            while (_events.containsKey(time))
                time = new Long(time.longValue() + 1);
            _events.put(time, event);
            _eventTimes.put(event, time);
            
            if ( (_events.size() != _eventTimes.size()) ) {
                _log.error("Skewed events: " + _events.size() + " for " + _eventTimes.size());
                for (Iterator iter = _eventTimes.keySet().iterator(); iter.hasNext(); ) {
                    TimedEvent evt = (TimedEvent)iter.next();
                    Long when = (Long)_eventTimes.get(evt);
                    TimedEvent cur = (TimedEvent)_events.get(when);
                    if (cur != evt) {
                        _log.error("event " + evt + " @ " + when + ": " + cur);
                    }
                }
            }
            
            totalEvents = _events.size();
            _events.notifyAll();
        }
        if (time.longValue() > eventTime + 100) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Lots of timer congestion, had to push " + event + " back "
                           + (time.longValue()-eventTime) + "ms (# events: " + totalEvents + ")");
        }
        long timeToAdd = System.currentTimeMillis() - now;
        if (timeToAdd > 50) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("timer contention: took " + timeToAdd + "ms to add a job with " + totalEvents + " queued");
        }
            
    }
    
    public boolean removeEvent(TimedEvent evt) {
        if (evt == null) return false;
        synchronized (_events) {
            Long when = (Long)_eventTimes.remove(evt);
            if (when != null)
                _events.remove(when);
            return null != when;
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
    
    private long _occurredTime;
    private long _occurredEventCount;
    // not used
    //  private TimedEvent _recentEvents[] = new TimedEvent[5];
    private class SimpleTimerRunner implements Runnable {
        public void run() {
            List eventsToFire = new ArrayList(1);
            while(runn.getAnswer()) {
                try {
                    synchronized (_events) {
                        //if (_events.size() <= 0)
                        //    _events.wait();
                        //if (_events.size() > 100)
                        //    _log.warn("> 100 events!  " + _events.values());
                        long now = System.currentTimeMillis();
                        long nextEventDelay = -1;
                        Object nextEvent = null;
                        while(runn.getAnswer()) {
                            if(_events.isEmpty()) {
                                break;
                            }
                            Long when = (Long)_events.firstKey();
                            if (when.longValue() <= now) {
                                TimedEvent evt = (TimedEvent)_events.remove(when);
                                if (evt != null) {                            
                                    _eventTimes.remove(evt);
                                    eventsToFire.add(evt);
                                } 
                            } else {
                                nextEventDelay = when.longValue() - now;
                                nextEvent = _events.get(when);
                                break;
                            }
                        }
                        if (eventsToFire.isEmpty()) { 
                            if (nextEventDelay != -1) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("Next event in " + nextEventDelay + ": " + nextEvent);
                                _events.wait(nextEventDelay);
                            } else {
                                _events.wait();
                            }
                        }
                    }
                } catch (ThreadDeath td) {
                    return; // die
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
                
                long now = System.currentTimeMillis();
                now = now - (now % 1000);

                synchronized (_readyEvents) {
                    for (int i = 0; i < eventsToFire.size(); i++) 
                        _readyEvents.add(eventsToFire.get(i));
                    _readyEvents.notifyAll();
                }

                if (_occurredTime == now) {
                    _occurredEventCount += eventsToFire.size();
                } else {
                    _occurredTime = now;
                    if (_occurredEventCount > 2500) {
                        StringBuilder buf = new StringBuilder(128);
                        buf.append("Too many simpleTimerJobs (").append(_occurredEventCount);
                        buf.append(") in a second!");
                        _log.log(Log.WARN, buf.toString());
                    }
                    _occurredEventCount = 0;
                }

                eventsToFire.clear();
            }
        }
    }
}

