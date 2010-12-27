package net.i2p.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;

import net.i2p.I2PAppContext;

/**
 * Simple event scheduler - toss an event on the queue and it gets fired at the
 * appropriate time.  The method that is fired however should NOT block (otherwise
 * they b0rk the timer).
 *
 * This is like SimpleTimer but addEvent() for an existing event adds a second
 * job. Unlike SimpleTimer, events cannot be cancelled or rescheduled.
 *
 * For events that cannot or will not be cancelled or rescheduled -
 * for example, a call such as:
 *       SimpleTimer.getInstance().addEvent(new FooEvent(bar), timeoutMs);
 * use SimpleScheduler instead to reduce lock contention in SimpleTimer...
 *
 * For periodic events, use addPeriodicEvent(). Unlike SimpleTimer,
 * uncaught Exceptions will not prevent subsequent executions.
 *
 * @author zzz
 */
public class SimpleScheduler {
    private static final SimpleScheduler _instance = new SimpleScheduler();
    public static SimpleScheduler getInstance() { return _instance; }
    private static final int MIN_THREADS = 2;
    private static final int MAX_THREADS = 4;
    private I2PAppContext _context;
    private Log _log;
    private ScheduledThreadPoolExecutor _executor;
    private String _name;
    private int _count;
    private final int _threads;

    protected SimpleScheduler() { this("SimpleScheduler"); }
    protected SimpleScheduler(String name) {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(SimpleScheduler.class);
        _name = name;
        _count = 0;
        long maxMemory = Runtime.getRuntime().maxMemory();
        _threads = (int) Math.max(MIN_THREADS, Math.min(MAX_THREADS, 1 + (maxMemory / (32*1024*1024))));
        _executor = new ScheduledThreadPoolExecutor(_threads, new CustomThreadFactory());
        _executor.prestartAllCoreThreads();
    }
    
    /**
     * Removes the SimpleScheduler.
     */
    public void stop() {
        _executor.shutdownNow();
    }

    /**
     * Queue up the given event to be fired no sooner than timeoutMs from now.
     *
     * @param event
     * @param timeoutMs 
     */
    public void addEvent(SimpleTimer.TimedEvent event, long timeoutMs) {
        if (event == null)
            throw new IllegalArgumentException("addEvent null");
        RunnableEvent re = new RunnableEvent(event, timeoutMs);
        re.schedule();
    }
    
    /**
     * Queue up the given event to be fired after timeoutMs and every
     * timeoutMs thereafter. The TimedEvent must not do its own rescheduling.
     * As all Exceptions are caught in run(), these will not prevent
     * subsequent executions (unlike SimpleTimer, where the TimedEvent does
     * its own rescheduling).
     */
    public void addPeriodicEvent(SimpleTimer.TimedEvent event, long timeoutMs) {
        addPeriodicEvent(event, timeoutMs, timeoutMs);
    }
    
    /**
     * Queue up the given event to be fired after initialDelay and every
     * timeoutMs thereafter. The TimedEvent must not do its own rescheduling.
     * As all Exceptions are caught in run(), these will not prevent
     * subsequent executions (unlike SimpleTimer, where the TimedEvent does
     * its own rescheduling)
     *
     * @param event
     * @param initialDelay (ms)
     * @param timeoutMs 
     */
    public void addPeriodicEvent(SimpleTimer.TimedEvent event, long initialDelay, long timeoutMs) {
        if (event == null)
            throw new IllegalArgumentException("addEvent null");
        RunnableEvent re = new PeriodicRunnableEvent(event, initialDelay, timeoutMs);
        re.schedule();
    }
    
    private class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(_name +  ' ' + (++_count) + '/' + _threads);
// Uncomment this to test threadgrouping, but we should be all safe now that the constructor preallocates!
//            String name = rv.getThreadGroup().getName();
//            if(!name.equals("main")) {
//                (new Exception("OWCH! DAMN! Wrong ThreadGroup `" + name +"', `" + rv.getName() + "'")).printStackTrace();
//            }
            rv.setDaemon(true);
            return rv;
        }
    }

    /**
     * Same as SimpleTimer.TimedEvent but use run() instead of timeReached(), and remembers the time
     */
    private class RunnableEvent implements Runnable {
        protected SimpleTimer.TimedEvent _timedEvent;
        protected long _scheduled;

        public RunnableEvent(SimpleTimer.TimedEvent t, long timeoutMs) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Creating w/ delay " + timeoutMs + " : " + t);
            _timedEvent = t;
            _scheduled = timeoutMs + System.currentTimeMillis();
        }
        public void schedule() {
            _executor.schedule(this, _scheduled - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }
        public void run() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Running: " + _timedEvent);
            long before = System.currentTimeMillis();
            if (_log.shouldLog(Log.WARN) && before < _scheduled - 100)
                _log.warn(_name + " wtf, early execution " + (_scheduled - before) + ": " + _timedEvent);
            else if (_log.shouldLog(Log.WARN) && before > _scheduled + 1000)
                _log.warn(" wtf, late execution " + (before - _scheduled) + ": " + _timedEvent + debug());
            try {
                _timedEvent.timeReached();
            } catch (Throwable t) {
                _log.log(Log.CRIT, _name + " wtf, event borked: " + _timedEvent, t);
            }
            long time = System.currentTimeMillis() - before;
            if (time > 1000 && _log.shouldLog(Log.WARN))
                _log.warn(_name + " wtf, event execution took " + time + ": " + _timedEvent);
            long completed = _executor.getCompletedTaskCount();
            if (_log.shouldLog(Log.INFO) && completed % 250  == 0)
                _log.info(debug());
        }
    }

    /** Run every timeoutMs. TimedEvent must not do its own reschedule via addEvent() */
    private class PeriodicRunnableEvent extends RunnableEvent {
        private long _timeoutMs;
        private long _initialDelay;
        public PeriodicRunnableEvent(SimpleTimer.TimedEvent t, long initialDelay, long timeoutMs) {
            super(t, timeoutMs);
            _initialDelay = initialDelay;
            _timeoutMs = timeoutMs;
            _scheduled = initialDelay + System.currentTimeMillis();
        }
        @Override
        public void schedule() {
            _executor.scheduleWithFixedDelay(this, _initialDelay, _timeoutMs, TimeUnit.MILLISECONDS);
        }
        @Override
        public void run() {
            super.run();
            _scheduled = _timeoutMs + System.currentTimeMillis();
        }
    }

    private String debug() {
        return
            " Pool: " + _name +
            " Active: " + _executor.getActiveCount() + '/' + _executor.getPoolSize() +
            " Completed: " + _executor.getCompletedTaskCount() +
            " Queued: " + _executor.getQueue().size();
    }
}

