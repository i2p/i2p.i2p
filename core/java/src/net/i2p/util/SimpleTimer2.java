package net.i2p.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;

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
    private volatile int _count;
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
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 96*1024*1024l;
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
                log.log(Log.CRIT, "wtf, event borked: " + r, t);
            }
        }
    }

    private class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(_name + ' ' + (++_count) + '/' + _threads);
// Uncomment this to test threadgrouping, but we should be all safe now that the constructor preallocates!
//            String name = rv.getThreadGroup().getName();
//            if(!name.equals("main")) {
//                (new Exception("OWCH! DAMN! Wrong ThreadGroup `" + name +"', `" + rv.getName() + "'")).printStackTrace();
//           }
            rv.setDaemon(true);
            return rv;
        }
    }

    private ScheduledFuture schedule(TimedEvent t, long timeoutMs) {
        return _executor.schedule(t, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 
     * state of a given TimedEvent
     * 
     * valid transitions:
     * {IDLE,CANCELLED,RUNNING} -> SCHEDULED [ -> SCHEDULED ]* -> RUNNING -> {IDLE,CANCELLED,SCHEDULED}
     * {IDLE,CANCELLED,RUNNING} -> SCHEDULED [ -> SCHEDULED ]* -> CANCELLED
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
     *   SimpleTimer.getInstance().addEvent(new foo(), timeout) => new foo(SimpleTimer2.getInstance(), timeout)
     *   SimpleTimer.getInstance().addEvent(this, timeout) => schedule(timeout)
     *   SimpleTimer.getInstance().addEvent(foo, timeout) => foo.reschedule(timeout)
     *   SimpleTimer.getInstance().removeEvent(foo) => foo.cancel()
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
        private ScheduledFuture _future; // _executor.remove() doesn't work so we have to use this
                                         // ... and I expect cancelling this way is more efficient

        /** state of the current event.  All access should be under lock. */
        private TimedEventState _state;
        /** absolute time this event should run next time. LOCKING: this */
        private long _nextRun;
        /** whether this was scheduled during RUNNING state.  LOCKING: this */
        private boolean _rescheduleAfterRun;
        
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
         *  More efficient than reschedule().
         *  Only call this after calling the non-scheduling constructor,
         *  or from within timeReached(), or you will get duplicates on the queue.
         *  Otherwise use reschedule().
         */
        public synchronized void schedule(long timeoutMs) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Scheduling: " + this + " timeout = " + timeoutMs + " state: " + _state);
            if (timeoutMs <= 0 && _log.shouldLog(Log.WARN))
                timeoutMs = 1; // otherwise we may execute before _future is updated, which is fine
                               // except it triggers 'early execution' warning logging

            // always set absolute time of execution
            _nextRun = timeoutMs + System.currentTimeMillis();
            
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
         * Do not call from within timeReached()
         *
         * @param timeoutMs 
         */
        public void reschedule(long timeoutMs) {
            reschedule(timeoutMs, true);
        }

        /**
         * useEarliestTime must be false if called from within timeReached(), as
         * it won't be rescheduled, in favor of the currently running task
         *
         * @param timeoutMs 
         * @param useEarliestTime if its already scheduled, use the earlier of the 
         *                        two timeouts, else use the later
         */
        public synchronized void reschedule(long timeoutMs, boolean useEarliestTime) {
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
            cancel();
            schedule(timeoutMs);
        }

        /** returns true if cancelled */
        public synchronized boolean cancel() {
            // always clear
            _rescheduleAfterRun = false;
            
            switch(_state) {
              case CANCELLED:  // fall through
              case IDLE: 
                break; // my preference is to throw IllegalState here, but let it be.
              case RUNNING:    // fall through
              case SCHEDULED:
                boolean cancelled = _future.cancel(false);
                if (cancelled)
                    _state = TimedEventState.CANCELLED;
                else {} // log something as this could be serious, we remain RUNNING otherwise
                return cancelled;
            }
            return false;
            
        }

        public void run() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Running: " + this);
            long before = System.currentTimeMillis();
            long delay = 0;
            synchronized(this) {
                if (_rescheduleAfterRun)
                    throw new IllegalStateException("rescheduleAfterRun cannot be true here");
                
                switch(_state) {
                  case CANCELLED: 
                    return; // goodbye
                  case IDLE:  // fall through
                  case RUNNING:
                    throw new IllegalStateException("not possible to be in " + _state);
                  case SCHEDULED: // proceed, switch to IDLE in case I need to reschedule
                    _state = TimedEventState.IDLE;
                }
                                               
                // if I was rescheduled by the user, re-submit myself to the executor.
                int difference = (int)(_nextRun - before); // careful with long uptimes
                if (difference > _fuzz) {
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
                _log.warn(_pool + " wtf, no _future " + this);
            // This can be an incorrect warning especially after a schedule(0)
            if (_log.shouldLog(Log.WARN) && delay > 100)
                _log.warn(_pool + " wtf, early execution " + delay + ": " + this);
            else if (_log.shouldLog(Log.WARN) && delay < -1000)
                _log.warn(" wtf, late execution " + (0 - delay) + ": " + this + _pool.debug());
            try {
                timeReached();
            } catch (Throwable t) {
                _log.log(Log.CRIT, _pool + ": Timed task " + this + " exited unexpectedly, please report", t);
            } finally { // must be in finally
                synchronized(this) {
                    switch(_state) {
                      case SCHEDULED:  // fall through
                      case IDLE:
                        throw new IllegalStateException("can't be " + _state);
                      case CANCELLED:
                        break; // nothing
                      case RUNNING: 
                        _state = TimedEventState.IDLE; 
                        // do we need to reschedule?
                        if (_rescheduleAfterRun) {
                            _rescheduleAfterRun = false;
                            schedule(_nextRun - System.currentTimeMillis());
                        }
                    }
                }
            }
            long time = System.currentTimeMillis() - before;
            if (time > 500 && _log.shouldLog(Log.WARN))
                _log.warn(_pool + " wtf, event execution took " + time + ": " + this);
            long completed = _pool.getCompletedTaskCount();
            if (_log.shouldLog(Log.INFO) && completed % 250  == 0)
                _log.info(_pool.debug());
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

    private long getCompletedTaskCount() {
        return _executor.getCompletedTaskCount();
    }

    private String debug() {
        _executor.purge();  // Remove cancelled tasks from the queue so we get a good queue size stat
        return
            " Pool: " + _name +
            " Active: " + _executor.getActiveCount() + '/' + _executor.getPoolSize() +
            " Completed: " + _executor.getCompletedTaskCount() +
            " Queued: " + _executor.getQueue().size();
    }
}

