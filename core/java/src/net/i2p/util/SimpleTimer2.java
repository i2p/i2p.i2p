package net.i2p.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;

/**
 * Simple event scheduler - toss an event on the queue and it gets fired at the
 * appropriate time.  The method that is fired however should NOT block (otherwise
 * they b0rk the timer).
 *
 * This rewrites the old SimpleTimer to use the java.util.concurrent.ScheduledThreadPoolExecutor.
 * SimpleTimer has problems with lock contention;
 * this should work a lot better.
 *
 * This supports cancelling and arbitrary rescheduling.
 * If you don't need that, use SimpleScheduler instead.
 *
 * SimpleTimer is deprecated, use this or SimpleScheduler.
 *
 * @author zzz
 */
public class SimpleTimer2 {

    /**
     *  If you have a context, use context.simpleTimer2() instead
     */
    public static SimpleTimer2 getInstance() {
        return I2PAppContext.getGlobalContext().simpleTimer2();
    }

    private static final int MIN_THREADS = 2;
    private static final int MAX_THREADS = 4;
    private final ScheduledThreadPoolExecutor _executor;
    private final String _name;
    private final AtomicInteger _count = new AtomicInteger();
    private final int _threads;

    /**
     *  To be instantiated by the context.
     *  Others should use context.simpleTimer2() instead
     */
    public SimpleTimer2(I2PAppContext context) {
        this(context, "SimpleTimer2");
    }

    /**
     *  To be instantiated by the context.
     *  Others should use context.simpleTimer2() instead
     */
    protected SimpleTimer2(I2PAppContext context, String name) {
        this(context, name, true);
    }

    /**
     *  To be instantiated by the context.
     *  Others should use context.simpleTimer2() instead
     *  @since 0.9
     */
    protected SimpleTimer2(I2PAppContext context, String name, boolean prestartAllThreads) {
        _name = name;
        long maxMemory = SystemVersion.getMaxMemory();
        _threads = (int) Math.max(MIN_THREADS, Math.min(MAX_THREADS, 1 + (maxMemory / (32*1024*1024))));
        _executor = new CustomScheduledThreadPoolExecutor(_threads, new CustomThreadFactory());
        if (prestartAllThreads)
            _executor.prestartAllCoreThreads();
        // don't bother saving ref to remove hook if somebody else calls stop
        context.addShutdownTask(new Shutdown());
    }
    
    /**
     * @since 0.8.8
     */
    private class Shutdown implements Runnable {
        public void run() {
            stop();
        }
    }

    /**
     * Stops the SimpleTimer.
     * Subsequent executions should not throw a RejectedExecutionException.
     * Cannot be restarted.
     */
    public void stop() {
        _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        _executor.shutdownNow();
    }

    private static class CustomScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
        public CustomScheduledThreadPoolExecutor(int threads, ThreadFactory factory) {
             super(threads, factory);
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t != null) { // shoudn't happen, caught in RunnableEvent.run()
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(SimpleTimer2.class);
                log.log(Log.CRIT, "event borked: " + r, t);
            }
        }
    }

    private class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(_name + ' ' + _count.incrementAndGet() + '/' + _threads);
// Uncomment this to test threadgrouping, but we should be all safe now that the constructor preallocates!
//            String name = rv.getThreadGroup().getName();
//            if(!name.equals("main")) {
//                (new Exception("OWCH! DAMN! Wrong ThreadGroup `" + name +"', `" + rv.getName() + "'")).printStackTrace();
//           }
            rv.setDaemon(true);
            rv.setPriority(Thread.NORM_PRIORITY + 1);
            return rv;
        }
    }

    private ScheduledFuture<?> schedule(TimedEvent t, long timeoutMs) {
        return _executor.schedule(t, timeoutMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Queue up the given event to be fired no sooner than timeoutMs from now.
     *
     * For transition from SimpleScheduler. Uncancellable.
     * New code should use SimpleTimer2.TimedEvent.
     *
     * @param event to be run once
     * @param timeoutMs run after this delay
     * @since 0.9.20
     */
    public void addEvent(final SimpleTimer.TimedEvent event, final long timeoutMs) {
        if (event == null)
            throw new IllegalArgumentException("addEvent null");

        new TimedEvent(this, timeoutMs) {
            @Override
            public void timeReached() {
                event.timeReached();
            }

            @Override
            public String toString() {
                return event.toString();
            }
        };
    }
    
    /**
     * Schedule periodic event
     *
     * The TimedEvent must not do its own rescheduling.
     * As all Exceptions are caught in run(), these will not prevent
     * subsequent executions (unlike SimpleTimer, where the TimedEvent does
     * its own rescheduling).
     * 
     * For transition from SimpleScheduler. Uncancellable.
     * New code should use SimpleTimer2.TimedEvent.
     * 
     * @since 0.9.20
     * @param timeoutMs run subsequent iterations of this event every timeoutMs ms, 5000 minimum
     * @throws IllegalArgumentException if timeoutMs less than 5000
     */
    public void addPeriodicEvent(final SimpleTimer.TimedEvent event, final long timeoutMs) {
        addPeriodicEvent(event, timeoutMs, timeoutMs);
    }
    
    /**
     * Schedule periodic event
     * 
     * The TimedEvent must not do its own rescheduling.
     * As all Exceptions are caught in run(), these will not prevent
     * subsequent executions (unlike SimpleTimer, where the TimedEvent does
     * its own rescheduling).
     * 
     * For transition from SimpleScheduler. Uncancellable.
     * New code should use SimpleTimer2.TimedEvent.
     * 
     * @since 0.9.20
     * @param delay run the first iteration of this event after delay ms
     * @param timeoutMs run subsequent iterations of this event every timeoutMs ms, 5000 minimum
     * @throws IllegalArgumentException if timeoutMs less than 5000
     */
    public void addPeriodicEvent(final SimpleTimer.TimedEvent event, final long delay,  final long timeoutMs) {
        
        new PeriodicTimedEvent(this, delay, timeoutMs) {
            @Override
            public void timeReached() {
                event.timeReached();
            }

            @Override
            public String toString() {
                return event.toString();
            }
        };        
    }

    /** 
     * state of a given TimedEvent
     * 
     * valid transitions:
     * {IDLE,CANCELLED,RUNNING} -&gt; SCHEDULED [ -&gt; SCHEDULED ]* -&gt; RUNNING -&gt; {IDLE,CANCELLED,SCHEDULED}
     * {IDLE,CANCELLED,RUNNING} -&gt; SCHEDULED [ -&gt; SCHEDULED ]* -&gt; CANCELLED
     * 
     * anything else is invalid.
     */
    private enum TimedEventState {
        IDLE,
        SCHEDULED,
        RUNNING,
        CANCELLED
    };
    
    
    /**
     * Similar to SimpleTimer.TimedEvent but users must extend instead of implement,
     * and all schedule and cancel methods are through this class rather than SimpleTimer2.
     *
     * To convert over, change implements SimpleTimer.TimedEvent to extends SimpleTimer2.TimedEvent,
     * and be sure to call super(SimpleTimer2.getInstance(), timeoutMs) in the constructor
     * (or super(SimpleTimer2.getInstance()); .... schedule(timeoutMs); if there is other stuff
     * in your constructor)
     *
     * Other porting:
     *   SimpleTimer.getInstance().addEvent(new foo(), timeout) =&gt; new foo(SimpleTimer2.getInstance(), timeout)
     *   SimpleTimer.getInstance().addEvent(this, timeout) =&gt; schedule(timeout)
     *   SimpleTimer.getInstance().addEvent(foo, timeout) =&gt; foo.reschedule(timeout)
     *   SimpleTimer.getInstance().removeEvent(foo) =&gt; foo.cancel()
     *
     * There's no global locking, but for scheduling, we synchronize on this
     * to reduce the chance of duplicates on the queue.
     *
     * schedule(ms) can get create duplicates
     * reschedule(ms) and reschedule(ms, true) can lose the timer
     * reschedule(ms, false) and forceReschedule(ms) are relatively safe from either
     *
     */
    public static abstract class TimedEvent implements Runnable {
        private final Log _log;
        private final SimpleTimer2 _pool;
        private int _fuzz;
        protected static final int DEFAULT_FUZZ = 3;
        private ScheduledFuture<?> _future; // _executor.remove() doesn't work so we have to use this
                                         // ... and I expect cancelling this way is more efficient

        /** state of the current event.  All access should be under lock. */
        protected TimedEventState _state;
        /** absolute time this event should run next time. LOCKING: this */
        private long _nextRun;
        /** whether this was scheduled during RUNNING state.  LOCKING: this */
        private boolean _rescheduleAfterRun;
        /** whether this was cancelled during RUNNING state.  LOCKING: this */
        private boolean _cancelAfterRun;
        
        /** must call schedule() later */
        public TimedEvent(SimpleTimer2 pool) {
            _pool = pool;
            _fuzz = DEFAULT_FUZZ;
            _log = I2PAppContext.getGlobalContext().logManager().getLog(SimpleTimer2.class);
            _state = TimedEventState.IDLE;
        }

        /** automatically schedules, don't use this one if you have other things to do first */
        public TimedEvent(SimpleTimer2 pool, long timeoutMs) {
            this(pool);
            schedule(timeoutMs);
        }

        /**
         * Don't bother rescheduling if +/- this many ms or less.
         * Use this to reduce timer queue and object churn for a sloppy timer like
         * an inactivity timer.
         * Default 3 ms.
         */
        public synchronized void setFuzz(int fuzz) {
            _fuzz = fuzz;
        }

        /**
         *  Slightly more efficient than reschedule().
         *  Does nothing if already scheduled.
         */
        public synchronized void schedule(long timeoutMs) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Scheduling: " + this + " timeout = " + timeoutMs + " state: " + _state);
            if (timeoutMs <= 0) {
                // streaming timers do call with timeoutMs == 0
                if (timeoutMs < 0 && _log.shouldLog(Log.WARN))
                    _log.warn("Sched. timeout < 0: " + this + " timeout = " + timeoutMs + " state: " + _state);
                timeoutMs = 1; // otherwise we may execute before _future is updated, which is fine
                               // except it triggers 'early execution' warning logging
            }

            // always set absolute time of execution
            _nextRun = timeoutMs + System.currentTimeMillis();
            _cancelAfterRun = false;
            
            switch(_state) {
              case RUNNING:
                _rescheduleAfterRun = true;  // signal that we need rescheduling.
                break;
              case IDLE:  // fall through
              case CANCELLED:
                _future = _pool.schedule(this, timeoutMs); 
                _state = TimedEventState.SCHEDULED;
                break;
              case SCHEDULED: // nothing
            }
        }

        /**
         * Use the earliest of the new time and the old time
         * May be called from within timeReached(), but schedule() is
         * better there.
         *
         * @param timeoutMs 
         */
        public void reschedule(long timeoutMs) {
            reschedule(timeoutMs, true);
        }

        /**
         * May be called from within timeReached(), but schedule() is
         * better there.
         *
         * @param timeoutMs 
         * @param useEarliestTime if its already scheduled, use the earlier of the 
         *                        two timeouts, else use the later
         */
        public synchronized void reschedule(long timeoutMs, boolean useEarliestTime) {
            if (timeoutMs <= 0) {
                if (timeoutMs < 0 && _log.shouldInfo())
                    _log.info("Resched. timeout < 0: " + this + " timeout = " + timeoutMs + " state: " + _state);
                timeoutMs = 1;
            }
            final long now = System.currentTimeMillis();
            long oldTimeout;
            boolean scheduled = _state == TimedEventState.SCHEDULED;
            if (scheduled)
                oldTimeout = _nextRun - now;
            else
                oldTimeout = timeoutMs;
            
            // don't bother rescheduling if within _fuzz ms
            if ((oldTimeout - _fuzz > timeoutMs && useEarliestTime) ||
                (oldTimeout + _fuzz < timeoutMs && !useEarliestTime)||
                (!scheduled)) {
                if (scheduled && oldTimeout <= 5) {
                    // don't reschedule to avoid race
                    if (_log.shouldWarn())
                        _log.warn("not rescheduling to " + timeoutMs + ", about to execute " + this + " in " + oldTimeout);
                    return;
                }
                if (scheduled && (now + timeoutMs) < _nextRun) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Re-scheduling: " + this + " timeout = " + timeoutMs + " old timeout was " + oldTimeout + " state: " + _state);
                    cancel();
                }
                schedule(timeoutMs);
            }
        }

        /**
         * Always use the new time - ignores fuzz
         * @param timeoutMs 
         */
        public synchronized void forceReschedule(long timeoutMs) {
            // don't cancel while running!
            if (_state == TimedEventState.SCHEDULED)
                cancel();
            schedule(timeoutMs);
        }

        /** @return true if cancelled */
        public synchronized boolean cancel() {
            // always clear
            _rescheduleAfterRun = false;
            
            switch(_state) {
              case CANCELLED:  // fall through
              case IDLE: 
                break; // my preference is to throw IllegalState here, but let it be.
              case RUNNING:
                _cancelAfterRun = true;
                return true;
              case SCHEDULED:
                // There's probably a race here, where it's cancelled after it's running
                // The result (if rescheduled) is a dup on the queue, see tickets 1694, 1705
                // Mitigated by close-to-execution check in reschedule()
                boolean cancelled = _future.cancel(true);
                if (cancelled)
                    _state = TimedEventState.CANCELLED;
                else
                    _log.error("could not cancel " + this + " to run in " + (_nextRun - System.currentTimeMillis()), new Exception());
                return cancelled;
            }
            return false;
            
        }

        public void run() {
            try {
                run2();
            } catch (RuntimeException re) {
                _log.error("timer error", re);
                throw re;
            }
        }

        private void run2() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Running: " + this);
            long before = System.currentTimeMillis();
            long delay = 0;
            synchronized(this) {
                if (Thread.currentThread().isInterrupted()) {
                    if (_log.shouldWarn())
                        _log.warn("I was interrupted in run, state "+_state+" event "+this);
                    return;
                }
                if (_rescheduleAfterRun)
                    throw new IllegalStateException(this + " rescheduleAfterRun cannot be true here");
                
                switch(_state) {
                  case CANCELLED: 
                      if (_log.shouldInfo())
                          _log.info("Not actually running: CANCELLED " + this);
                    return; // goodbye
                  case IDLE:  // fall through
                  case RUNNING:
                    throw new IllegalStateException(this + " not possible to be in " + _state);
                  case SCHEDULED:
                    // proceed, will switch to IDLE to reschedule
                }
                                               
                // if I was rescheduled by the user, re-submit myself to the executor.
                long difference = _nextRun - before; // careful with long uptimes
                if (difference > _fuzz) {
                    // proceed, switch to IDLE to reschedule
                    _state = TimedEventState.IDLE;
                    if (_log.shouldInfo())
                        _log.info("Early execution, Rescheduling for " + difference + " later: " + this);
                    schedule(difference); 
                    return;
                }
                
                // else proceed to run
                _state = TimedEventState.RUNNING;
            }
            // cancel()-ing after this point only works if the event supports it explicitly
            // none of these _future checks should be necessary anymore
            if (_future != null)
                delay = _future.getDelay(TimeUnit.MILLISECONDS);
            else if (_log.shouldLog(Log.WARN))
                _log.warn(_pool + " no _future " + this);
            // This can be an incorrect warning especially after a schedule(0)
            if (_log.shouldWarn()) {
                if (delay > 100)
                    _log.warn(_pool + " early execution " + delay + ": " + this);
                else if (delay < -1000)
                    _log.warn(" late execution " + (0 - delay) + ": " + this + _pool.debug());
            }
            try {
                timeReached();
            } catch (Throwable t) {
                _log.log(Log.CRIT, _pool + ": Timed task " + this + " exited unexpectedly, please report", t);
            } finally { // must be in finally
                synchronized(this) {
                    switch(_state) {
                      case SCHEDULED:  // fall through
                      case IDLE:
                        throw new IllegalStateException(this + " can't be " + _state);
                      case CANCELLED:
                        break; // nothing
                      case RUNNING: 
                        if (_cancelAfterRun) {
                            _cancelAfterRun = false;
                            _state = TimedEventState.CANCELLED;
                        } else {
                            _state = TimedEventState.IDLE; 
                            // do we need to reschedule?
                            if (_rescheduleAfterRun) {
                                _rescheduleAfterRun = false;
                                if (_log.shouldInfo())
                                    _log.info("Reschedule after run: " + this);
                                schedule(_nextRun - System.currentTimeMillis());
                            }
                        }
                    }
                }
            }
            long time = System.currentTimeMillis() - before;
            if (time > 500 && _log.shouldLog(Log.WARN))
                _log.warn(_pool + " event execution took " + time + ": " + this);
            else if (_log.shouldDebug())
                _log.debug("Execution finished in " + time + ": " + this);
            if (_log.shouldLog(Log.INFO)) {
                 // this call is slow - iterates through a HashMap -
                 // would be better to have a local AtomicLong if we care
                 long completed = _pool.getCompletedTaskCount();
                 if (completed % 250 == 0)
                     _log.info(_pool.debug());
            }
        }

        /** 
         * Simple interface for events to be queued up and notified on expiration
         * the time requested has been reached (this call should NOT block,
         * otherwise the whole SimpleTimer gets backed up)
         *
         */
        public abstract void timeReached();
    }

    @Override
    public String toString() {
        return _name;
    }

    /** warning - slow */
    private long getCompletedTaskCount() {
        return _executor.getCompletedTaskCount();
    }

    /** warning - slow */
    private String debug() {
        _executor.purge();  // Remove cancelled tasks from the queue so we get a good queue size stat
        return
            " Pool: " + _name +
            " Active: " + _executor.getActiveCount() + '/' + _executor.getPoolSize() +
            " Completed: " + _executor.getCompletedTaskCount() +
            " Queued: " + _executor.getQueue().size();
    }
    
    /**
     * For transition from SimpleScheduler.
     * @since 0.9.20
     */
    private static abstract class PeriodicTimedEvent extends TimedEvent {
        private final long _timeoutMs;
        
        /**
         * Schedule periodic event
         * 
         * @param delay run the first iteration of this event after delay ms
         * @param timeoutMs run subsequent iterations of this event every timeoutMs ms, 5000 minimum
         * @throws IllegalArgumentException if timeoutMs less than 5000
         */
        public PeriodicTimedEvent(SimpleTimer2 pool, long delay, long timeoutMs) {
            super(pool, delay);
            if (timeoutMs < 5000)
                throw new IllegalArgumentException("timeout minimum 5000");
            _timeoutMs = timeoutMs;
        }
        
        @Override
        public void run() {
            super.run();
            synchronized(this) {
                // Task may have rescheduled itself without actually running.
                // If we schedule again, it will be stuck in a scheduling loop.
                // This happens after a backwards clock shift.
                if (_state == TimedEventState.IDLE)
                    schedule(_timeoutMs);
            }
        }
    }
}

