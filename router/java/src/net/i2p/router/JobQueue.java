package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.DataHelper;
import net.i2p.router.message.HandleGarlicMessageJob;
import net.i2p.router.networkdb.kademlia.HandleFloodfillDatabaseLookupMessageJob;
import net.i2p.router.networkdb.kademlia.IterativeSearchJob;
import net.i2p.router.RouterClock;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Manage the pending jobs according to whatever algorithm is appropriate, giving
 * preference to earlier scheduled jobs.
 *
 * For use by the router only. Not to be used by applications or plugins.
 */
public class JobQueue {
    private final Log _log;
    private final RouterContext _context;
    
    /** Integer (runnerId) to JobQueueRunner for created runners */
    private final Map<Integer, JobQueueRunner> _queueRunners;
    /** a counter to identify a job runner */
    private final static AtomicInteger _runnerId = new AtomicInteger(0);
    /** list of jobs that are ready to run ASAP */
    private final BlockingQueue<Job> _readyJobs;
    /** SortedSet of jobs that are scheduled for running in the future, earliest first */
    private final Set<Job> _timedJobs;
    /** job name to JobStat for that job */
    private final ConcurrentHashMap<String, JobStats> _jobStats;
    private final QueuePumper _pumper;
    /** will we allow the # job runners to grow beyond 1? */
    private volatile boolean _allowParallelOperation;
    /** have we been killed or are we alive? */
    private volatile boolean _alive;
    
    private final Object _jobLock;
    private volatile long _nextPumperRun;
    
    /** how many when we go parallel */
    private static final int RUNNERS;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        if (maxMemory < 64*1024*1024)
            RUNNERS = 3;
        else if (maxMemory < 256*1024*1024)
            RUNNERS = 4;
        else
            RUNNERS = 5;
    }

    /** default max # job queue runners operating */
    private final static int DEFAULT_MAX_RUNNERS = 1;
    /** router.config parameter to override the max runners */
    private final static String PROP_MAX_RUNNERS = "router.maxJobRunners";
    
    /** how frequently should we check and update the max runners */
    private final static long MAX_LIMIT_UPDATE_DELAY = 60*1000;
    
    /** if a job is this lagged, spit out a warning, but keep going */
    private long _lagWarning = DEFAULT_LAG_WARNING;
    private final static long DEFAULT_LAG_WARNING = 5*1000;
    /** @deprecated unimplemented */
    @Deprecated
    private final static String PROP_LAG_WARNING = "router.jobLagWarning";
    
    /** if a job is this lagged, the router is hosed, so spit out a warning (dont shut it down) */
    private long _lagFatal = DEFAULT_LAG_FATAL;
    private final static long DEFAULT_LAG_FATAL = 30*1000;
    /** @deprecated unimplemented */
    @Deprecated
    private final static String PROP_LAG_FATAL = "router.jobLagFatal";
    
    /** if a job takes this long to run, spit out a warning, but keep going */
    private long _runWarning = DEFAULT_RUN_WARNING;
    private final static long DEFAULT_RUN_WARNING = 5*1000;
    /** @deprecated unimplemented */
    @Deprecated
    private final static String PROP_RUN_WARNING = "router.jobRunWarning";
    
    /** if a job takes this long to run, the router is hosed, so spit out a warning (dont shut it down) */
    private long _runFatal = DEFAULT_RUN_FATAL;
    private final static long DEFAULT_RUN_FATAL = 30*1000;
    /** @deprecated unimplemented */
    @Deprecated
    private final static String PROP_RUN_FATAL = "router.jobRunFatal";
    
    /** don't enforce fatal limits until the router has been up for this long */
    private long _warmupTime = DEFAULT_WARMUP_TIME;
    private final static long DEFAULT_WARMUP_TIME = 10*60*1000;
    /** @deprecated unimplemented */
    @Deprecated
    private final static String PROP_WARMUP_TIME = "router.jobWarmupTime";
    
    /** max ready and waiting jobs before we start dropping 'em */
    private int _maxWaitingJobs = DEFAULT_MAX_WAITING_JOBS;
    private final static int DEFAULT_MAX_WAITING_JOBS = 25;
    private final static long MIN_LAG_TO_DROP = 500;

    /** @deprecated unimplemented */
    @Deprecated
    private final static String PROP_MAX_WAITING_JOBS = "router.maxWaitingJobs";

    /** 
     * queue runners wait on this whenever they're not doing anything, and 
     * this gets notified *once* whenever there are ready jobs
     */
    private final Object _runnerLock = new Object();
    
    /** 
     *  Does not start the pumper. Caller MUST call startup.
     */
    public JobQueue(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(JobQueue.class);
        _context.statManager().createRateStat("jobQueue.readyJobs", 
                                              "How many ready and waiting jobs there are?", 
                                              "JobQueue", 
                                              new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("jobQueue.droppedJobs", 
                                              "How many jobs do we drop due to insane overload?", 
                                              "JobQueue", 
                                              new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("jobQueue.queuedJobs",
                                              "How many scheduled jobs are there?",
                                              "JobQueue",
                                              new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        // following are for JobQueueRunner
        _context.statManager().createRateStat("jobQueue.jobRun", "How long jobs take", "JobQueue", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("jobQueue.jobRunSlow", "How long jobs that take over a second take", "JobQueue", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRequiredRateStat("jobQueue.jobLag", "Job run delay (ms)", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("jobQueue.jobWait", "How long does a job sit on the job queue?", "JobQueue", new long[] { 60*60*1000l, 24*60*60*1000l });
        //_context.statManager().createRateStat("jobQueue.jobRunnerInactive", "How long are runners inactive?", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });

        _readyJobs = new LinkedBlockingQueue<Job>();
        _timedJobs = new TreeSet<Job>(new JobComparator());
        _jobLock = new Object();
        _queueRunners = new ConcurrentHashMap<Integer,JobQueueRunner>(RUNNERS);
        _jobStats = new ConcurrentHashMap<String,JobStats>();
        _pumper = new QueuePumper();
    }
    
    /**
     * Enqueue the specified job
     *
     */
    public void addJob(Job job) {
        if (job == null || !_alive) return;

        // This does nothing
        //if (job instanceof JobImpl)
        //    ((JobImpl)job).addedToQueue();

        long numReady;
        boolean alreadyExists = false;
        boolean dropped = false;
        // getNext() is now outside the jobLock, is that ok?
        long now = _context.clock().now();
        long start = job.getTiming().getStartAfter();
        if (start > now + 3*24*60*60*1000L) {
            // catch bugs, Job.requeue() argument is a delay not a time
            if (_log.shouldLog(Log.WARN))
                _log.warn("Scheduling job far in the future: " + (new Date(start)) + ' ' + job);
        }
        synchronized (_jobLock) {
            if (_readyJobs.contains(job))
                alreadyExists = true;
            numReady = _readyJobs.size();
            if (!alreadyExists) {
                //if (_timedJobs.contains(job))
                //    alreadyExists = true;
                // Always remove and re-add, since it needs to be
                // re-sorted in the TreeSet.
                boolean removed = _timedJobs.remove(job);
                if (removed && _log.shouldLog(Log.WARN))
                    _log.warn("Rescheduling job: " + job);
            }

            if ((!alreadyExists) && shouldDrop(job, numReady)) {
                job.dropped();
                dropped = true;
            } else {
                if (!alreadyExists) {
                    if (start <= now) {
                        // don't skew us - its 'start after' its been queued, or later
                        job.getTiming().setStartAfter(now);
                        if (job instanceof JobImpl)
                            ((JobImpl)job).madeReady();
                        _readyJobs.offer(job);
                    } else {
                        _timedJobs.add(job);
                        // only notify for _timedJobs, as _readyJobs does not use that lock
                        // only notify if sooner, to reduce contention
                        if (start < _nextPumperRun)
                            _jobLock.notifyAll();
                    }
                }
            }
        }
        
        _context.statManager().addRateData("jobQueue.readyJobs", numReady);
        _context.statManager().addRateData("jobQueue.queuedJobs", _timedJobs.size());
        if (dropped) {
            _context.statManager().addRateData("jobQueue.droppedJobs", 1);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping job due to overload!  # ready jobs: " 
                          + numReady + ": job = " + job);
            String key = job.getName();
            JobStats stats = _jobStats.get(key);
            if (stats == null) {
                stats = new JobStats(key);
                JobStats old = _jobStats.putIfAbsent(key, stats);
                if (old != null)
                    stats = old;
            }
            stats.jobDropped();
        }
    }
    
    public void removeJob(Job job) {
        synchronized (_jobLock) {
            _readyJobs.remove(job);
            _timedJobs.remove(job);
        }
    }
    
    /**
     * Returns <code>true</code> if a given job is waiting or running;
     * <code>false</code> if the job is finished or doesn't exist in the queue.
     *
     * @deprecated unused
     */
    @Deprecated
    public boolean isJobActive(Job job) {
        synchronized (_jobLock) {
            if (_readyJobs.contains(job) || _timedJobs.contains(job))
                return true;
        }
        for (JobQueueRunner runner: _queueRunners.values())
            if (runner.getCurrentJob() == job)
                return true;
        return false;
    }
    
    /**
     *  @deprecated contention - see JobTiming.setStartAfter() comments
     */
    @Deprecated
    public void timingUpdated() {
        synchronized (_jobLock) {
            _jobLock.notifyAll();
        }
    }
    
    public int getReadyCount() { 
            return _readyJobs.size();
    }

    public long getMaxLag() { 
            // first job is the one that has been waiting the longest
            Job j = _readyJobs.peek();
            if (j == null) return 0;
            JobTiming jt = j.getTiming();
            // PoisonJob timing is null, prevent NPE at shutdown
            if (jt == null)
                return 0;
            long startAfter = jt.getStartAfter();
            return _context.clock().now() - startAfter;
    }
    
    /** 
     * are we so overloaded that we should drop the given job?  
     * This is driven both by the numReady and waiting jobs, the type of job
     * in question, and what the router's router.maxWaitingJobs config parameter 
     * is set to.
     *
     */
    private boolean shouldDrop(Job job, long numReady) {
        if (_maxWaitingJobs <= 0) return false; // dont ever drop jobs
        if (!_allowParallelOperation) return false; // dont drop during startup [duh]
        if (numReady > _maxWaitingJobs) {
            Class<? extends Job> cls = job.getClass();
            // lets not try to drop too many tunnel messages...
            //if (cls == HandleTunnelMessageJob.class)
            //    return true;
                
            // we don't really *need* to answer DB lookup messages
            // This is pretty lame, there's actually a ton of different jobs we
            // could drop, but is it worth making a list?
            //
            // Garlic added in 0.9.19, floodfills were getting overloaded
            // with encrypted lookups
            //
            // ISJ added in 0.9.31, can get backed up due to DNS
            //
            // Obviously we can only drop one-shot jobs, not those that requeue
            //
            if (cls == HandleFloodfillDatabaseLookupMessageJob.class ||
                cls == HandleGarlicMessageJob.class ||
                cls == IterativeSearchJob.class) {
                // this tail drops based on the lag at the tail, which
                // makes no sense...
                //JobTiming jt = job.getTiming();
                //if (jt != null) {
                //    long lag =  _context.clock().now() - jt.getStartAfter();
                //    if (lag >= MIN_LAG_TO_DROP)
                //        return true;
                //}

                // this tail drops based on the lag at the head
                if (getMaxLag() >= MIN_LAG_TO_DROP)
                    return true;
            }
        }
        return false;
    }
    
    public void allowParallelOperation() { 
        _allowParallelOperation = true; 
        runQueue(_context.getProperty(PROP_MAX_RUNNERS, RUNNERS));
    }
    
    /** 
     *  Start the pumper.
     *  @since 0.9.19
     */
    public void startup() {
        _alive = true;
        I2PThread pumperThread = new I2PThread(_pumper, "Job Queue Pumper", true);
        pumperThread.setPriority(Thread.NORM_PRIORITY + 1);
        pumperThread.start();
    }

    /** @deprecated do you really want to do this? */
    @Deprecated
    public void restart() {
        synchronized (_jobLock) {
            _timedJobs.clear();
            _readyJobs.clear();
            _jobLock.notifyAll();
        }
    }
    
    void shutdown() { 
        _alive = false; 
        synchronized (_jobLock) {
            _timedJobs.clear();
            _readyJobs.clear();
            _jobLock.notifyAll();
        }
        // The JobQueueRunners are NOT daemons,
        // so they must be stopped.
        Job poison = new PoisonJob();
        for (JobQueueRunner runner : _queueRunners.values()) {
             runner.stopRunning();
            _readyJobs.offer(poison);
            // TODO interrupt thread for each runner
        }
        _queueRunners.clear();
        _jobStats.clear();
        _runnerId.set(0);

      /********
        if (_log.shouldLog(Log.WARN)) {
            StringBuilder buf = new StringBuilder(1024);
            buf.append("current jobs: \n");
            for (Iterator iter = _queueRunners.values().iterator(); iter.hasNext(); ) {
                JobQueueRunner runner = iter.next();
                Job j = runner.getCurrentJob();

                buf.append("Runner ").append(runner.getRunnerId()).append(": ");
                if (j == null) {
                    buf.append("no current job ");
                } else {
                    buf.append(j.toString());
                    buf.append(" started ").append(_context.clock().now() - j.getTiming().getActualStart());
                    buf.append("ms ago");
                }

                j = runner.getLastJob();
                if (j == null) {
                    buf.append("no last job");
                } else {
                    buf.append(j.toString());
                    buf.append(" started ").append(_context.clock().now() - j.getTiming().getActualStart());
                    buf.append("ms ago and finished ");
                    buf.append(_context.clock().now() - j.getTiming().getActualEnd());
                    buf.append("ms ago");
                }
            }
            buf.append("\nready jobs: ").append(_readyJobs.size()).append("\n\t");
            for (int i = 0; i < _readyJobs.size(); i++) 
                buf.append(_readyJobs.get(i).toString()).append("\n\t");
            buf.append("\n\ntimed jobs: ").append(_timedJobs.size()).append("\n\t");
            for (int i = 0; i < _timedJobs.size(); i++) 
                buf.append(_timedJobs.get(i).toString()).append("\n\t");
            _log.log(Log.WARN, buf.toString());
        }
      ********/
    }

    boolean isAlive() { return _alive; }
    
    /**
     * When did the most recently begin job start?
     */
    public long getLastJobBegin() { 
        long when = -1;
        for (JobQueueRunner runner : _queueRunners.values()) {
            long cur = runner.getLastBegin();
            if (cur > when)
                cur = when;
        }
        return when; 
    }
    /**
     * When did the most recently begin job start?
     */
    public long getLastJobEnd() { 
        long when = -1;
        for (JobQueueRunner runner : _queueRunners.values()) {
            long cur = runner.getLastEnd();
            if (cur > when)
                cur = when;
        }
        return when; 
    }
    /** 
     * retrieve the most recently begin and still currently active job, or null if
     * no jobs are running
     */
    public Job getLastJob() { 
        Job j = null;
        long when = -1;
        for (JobQueueRunner cur : _queueRunners.values()) {
            if (cur.getLastBegin() > when) {
                j = cur.getCurrentJob();
                when = cur.getLastBegin();
            }
        }
        return j;
    }
    
    /**
     * Blocking call to retrieve the next ready job
     *
     */
    Job getNext() {
        while (_alive) {
            try {
                Job j = _readyJobs.take();
                if (j.getJobId() == POISON_ID)
                    break;
                return j;
            } catch (InterruptedException ie) {}
        }
        if (_log.shouldLog(Log.WARN))
            _log.warn("No longer alive, returning null");
        return null;
    }
    
    /**
     * Start up the queue with the specified number of concurrent processors.
     * If this method has already been called, it will adjust the number of 
     * runners to meet the new number.  This does not kill jobs running on
     * excess threads, it merely instructs the threads to die after finishing
     * the current job.
     *
     */
    public synchronized void runQueue(int numThreads) {
            // we're still starting up [serially] and we've got at least one runner,
            // so dont do anything
            if ( (!_queueRunners.isEmpty()) && (!_allowParallelOperation) ) return;

            // we've already enabled parallel operation, so grow to however many are
            // specified
            if (_queueRunners.size() < numThreads) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Increasing the number of queue runners from " 
                              + _queueRunners.size() + " to " + numThreads);
                for (int i = _queueRunners.size(); i < numThreads; i++) {
                    JobQueueRunner runner = new JobQueueRunner(_context, i);
                    _queueRunners.put(Integer.valueOf(i), runner);
                    runner.setName("JobQueue " + _runnerId.incrementAndGet() + '/' + numThreads);
                    runner.start();
                }
            } else if (_queueRunners.size() == numThreads) {
                for (JobQueueRunner runner : _queueRunners.values()) {
                    runner.startRunning();
                }
            } else { // numThreads < # runners, so shrink
                //for (int i = _queueRunners.size(); i > numThreads; i++) {
                //     QueueRunner runner = (QueueRunner)_queueRunners.get(new Integer(i));
                //     runner.stopRunning();
                //}
            }
    }
        
    void removeRunner(int id) { _queueRunners.remove(Integer.valueOf(id)); }
    
    /**
     * Responsible for moving jobs from the timed queue to the ready queue, 
     * adjusting the number of queue runners, as well as periodically updating the 
     * max number of runners.
     *
     */
    private final class QueuePumper implements Runnable, Clock.ClockUpdateListener, RouterClock.ClockShiftListener {
        public QueuePumper() { 
            _context.clock().addUpdateListener(this);
            ((RouterClock) _context.clock()).addShiftListener(this);
        }

        public void run() {
            try {
                while (_alive) {
                    long now = _context.clock().now();
                    long timeToWait = -1;
                    try {
                        synchronized (_jobLock) {
                            Job lastJob = null;
                            long lastTime = Long.MIN_VALUE;
                            for (Iterator<Job> iter = _timedJobs.iterator(); iter.hasNext(); ) {
                                Job j = iter.next();
                                // find jobs due to start before now
                                long timeLeft = j.getTiming().getStartAfter() - now;
                                if (lastJob != null && lastTime > j.getTiming().getStartAfter()) {
                                    _log.error("Job " + lastJob + " out of order with job " + j +
                                             " difference of " + DataHelper.formatDuration(lastTime - j.getTiming().getStartAfter()));
                                }
                                lastJob = j;
                                lastTime = lastJob.getTiming().getStartAfter();
                                if (timeLeft <= 0) {
                                    if (j instanceof JobImpl)
                                        ((JobImpl)j).madeReady();

                                    _readyJobs.offer(j);
                                    iter.remove();
                                } else {
                                    //if ( (timeToWait <= 0) || (timeLeft < timeToWait) )
                                    // _timedJobs is now a TreeSet, so once we hit one that is
                                    // not ready yet, we can break
                                    // NOTE: By not going through the whole thing, a single job changing
                                    // setStartAfter() to some far-away time, without
                                    // calling addJob(), could clog the whole queue forever.
                                    // Hopefully nobody does that, and as a backup, we hope
                                    // that the TreeSet will eventually resort it from other addJob() calls.
                                        timeToWait = timeLeft;

                                    // failsafe - remove and re-add, peek at the next job,
                                    // break and go around again
                                    if (timeToWait > 10*1000 && iter.hasNext()) {
                                        if (_log.shouldLog(Log.INFO))
                                            _log.info("Failsafe re-sort job " + j +
                                                " with delay " + DataHelper.formatDuration(timeToWait));
                                        iter.remove();
                                        Job nextJob = iter.next();
                                        _timedJobs.add(j);
                                        long nextTimeLeft = nextJob.getTiming().getStartAfter() - now;
                                        if (timeToWait > nextTimeLeft) {
                                            _log.error("Job " + j + " out of order with job " + nextJob +
                                                " difference of " + DataHelper.formatDuration(timeToWait - nextTimeLeft));
                                            timeToWait = Math.max(10, nextTimeLeft);
                                        }
                                    }
                                    break;
                                }
                            }
                                if (timeToWait < 0)
                                    timeToWait = 1000;
                                else if (timeToWait < 10)
                                    timeToWait = 10;
                                else if (timeToWait > 10*1000)
                                    timeToWait = 10*1000;
                                //if (_log.shouldLog(Log.DEBUG))
                                //    _log.debug("Waiting " + timeToWait + " before rechecking the timed queue");
                                _nextPumperRun = _context.clock().now() + timeToWait;
                                _jobLock.wait(timeToWait);
                        } // synchronize (_jobLock)
                    } catch (InterruptedException ie) {}
                } // while (_alive)
            } catch (Throwable t) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("pumper killed?!", t);
            } finally {
                _context.clock().removeUpdateListener(this);
                ((RouterClock) _context.clock()).removeShiftListener(this);
            }
        }

        public void offsetChanged(long delta) {
            updateJobTimings(delta);
            synchronized (_jobLock) {
                _jobLock.notifyAll();
            }
        }

        /**
         *  Clock shift listener.
         *  Only adjust timings for negative shifts.
         *  For positive shifts, just wake up the pumper.
         *  @since 0.9.23
         */
        public void clockShift(long delta) {
            if (delta < 0) {
                offsetChanged(delta);
            } else {
                synchronized (_jobLock) {
                    _jobLock.notifyAll();
                }
            }
        }

    }
    
    /**
     * Update the clock data for all jobs in process or scheduled for
     * completion.
     */
    private void updateJobTimings(long delta) {
        synchronized (_jobLock) {
            for (Job j : _timedJobs) {
                j.getTiming().offsetChanged(delta);
            }
            for (Job j : _readyJobs) {
                j.getTiming().offsetChanged(delta);
            }
        }
        synchronized (_runnerLock) {
            for (JobQueueRunner runner : _queueRunners.values()) {
                Job job = runner.getCurrentJob();
                if (job != null)
                    job.getTiming().offsetChanged(delta);
            }
        }
    }
    
    /**
     * calculate and update the job timings
     * if it was lagged too much or took too long to run, spit out
     * a warning (and if its really excessive, kill the router)
     */ 
    void updateStats(Job job, long doStart, long origStartAfter, long duration) {
        if (_context.router() == null) return;
        String key = job.getName();
        long lag = doStart - origStartAfter; // how long were we ready and waiting?
        MessageHistory hist = _context.messageHistory();
        long uptime = _context.router().getUptime();

        if (lag < 0) lag = 0;
        if (duration < 0) duration = 0;
        
        JobStats stats = _jobStats.get(key);
        if (stats == null) {
            stats = new JobStats(key);
            JobStats old = _jobStats.putIfAbsent(key, stats);
            if (old != null)
                stats = old;
        }
        stats.jobRan(duration, lag);

        String dieMsg = null;

        if (lag > _lagWarning) {
            dieMsg = "Lag too long for job " + job.getName() + " [" + lag + "ms and a run time of " + duration + "ms]";
        } else if (duration > _runWarning) {
            dieMsg = "Job run too long for job " + job.getName() + " [" + lag + "ms lag and run time of " + duration + "ms]";
        }

        if (dieMsg != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(dieMsg);
            if (hist != null)
                hist.messageProcessingError(-1, JobQueue.class.getName(), dieMsg);
        }

        if ( (lag > _lagFatal) && (uptime > _warmupTime) ) {
            // this is fscking bad - the network at this size shouldn't have this much real contention
            // so we're going to DIE DIE DIE
            if (_log.shouldLog(Log.WARN))
                _log.log(Log.WARN, "The router is either incredibly overloaded or (more likely) there's an error.", new Exception("ttttooooo mmmuuuccccchhhh llllaaagggg"));
            //try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            //Router.getInstance().shutdown();
            return;
        }
        
        if ( (uptime > _warmupTime) && (duration > _runFatal) ) {
            // slow CPUs can get hosed with ElGamal, but 10s is too much.
            if (_log.shouldLog(Log.WARN))
                _log.log(Log.WARN, "The router is incredibly overloaded - either you have a 386, or (more likely) there's an error. ", new Exception("ttttooooo sssllloooowww"));
            //try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            //Router.getInstance().shutdown();
            return;
        }
    }
    
        
    /** job ID counter changed from int to long so it won't wrap negative */
    private static final int POISON_ID = -99999;

    private static class PoisonJob implements Job {
        public String getName() { return null; }
        public long getJobId() { return POISON_ID; }
        public JobTiming getTiming() { return null; }
        public void runJob() {}
        public Exception getAddedBy() { return null; }
        public void dropped() {}
    }

    /**
     *  Comparator for the _timedJobs TreeSet.
     *  Ensure different jobs with the same timing are different so they aren't removed.
     *  @since 0.8.9
     */
    private static class JobComparator implements Comparator<Job>, Serializable {
         public int compare(Job l, Job r) {
             // equals first, Jobs generally don't override so this should be fast
             // And this MUST be first so we can remove a job even if its timing has changed.
             if (l.equals(r))
                 return 0;
             // This is for _timedJobs, which always have a JobTiming.
             // PoisonJob only goes in _readyJobs.
             long ld = l.getTiming().getStartAfter() - r.getTiming().getStartAfter();
             if (ld < 0)
                 return -1;
             if (ld > 0)
                 return 1;
             ld = l.getJobId() - r.getJobId();
             if (ld < 0)
                 return -1;
             if (ld > 0)
                 return 1;
             return l.hashCode() - r.hashCode();
        }
    }

    /**
     *  Dump the current state.
     *  For the router console jobs status page.
     *
     *  @param readyJobs out parameter
     *  @param timedJobs out parameter
     *  @param activeJobs out parameter
     *  @param justFinishedJobs out parameter
     *  @return number of job runners
     *  @since 0.8.9
     */
    public int getJobs(Collection<Job> readyJobs, Collection<Job> timedJobs,
                       Collection<Job> activeJobs, Collection<Job> justFinishedJobs) {
        for (JobQueueRunner runner :_queueRunners.values()) {
            Job job = runner.getCurrentJob();
            if (job != null) {
                activeJobs.add(job);
            } else {
                job = runner.getLastJob();
                if (job != null)
                    justFinishedJobs.add(job);
            }
        }
        synchronized (_jobLock) {
            readyJobs.addAll(_readyJobs); 
            timedJobs.addAll(_timedJobs);
        }
        return _queueRunners.size();
    }

    /**
     *  Current job stats.
     *  For the router console jobs status page.
     *
     *  @since 0.8.9
     */
    public Collection<JobStats> getJobStats() {
        return Collections.unmodifiableCollection(_jobStats.values());
    }

    /** @deprecated moved to router console */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {
    }
}
