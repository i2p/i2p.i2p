package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.TreeMap;

import net.i2p.router.message.HandleSourceRouteReplyMessageJob;
import net.i2p.router.networkdb.HandleDatabaseLookupMessageJob;
import net.i2p.router.tunnelmanager.HandleTunnelCreateMessageJob;
import net.i2p.router.tunnelmanager.RequestTunnelJob;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Manage the pending jobs according to whatever algorithm is appropriate, giving
 * preference to earlier scheduled jobs.
 *
 */
public class JobQueue {
    private Log _log;
    private RouterContext _context;
    
    /** Integer (runnerId) to JobQueueRunner for created runners */
    private HashMap _queueRunners;
    /** a counter to identify a job runner */
    private volatile static int _runnerId = 0;
    /** list of jobs that are ready to run ASAP */
    private ArrayList _readyJobs;
    /** list of jobs that are scheduled for running in the future */
    private ArrayList _timedJobs;
    /** when true, don't run any new jobs or update any limits, etc */
    private boolean _paused;
    /** job name to JobStat for that job */
    private TreeMap _jobStats;
    /** how many job queue runners can go concurrently */
    private int _maxRunners; 
    private QueuePumper _pumper;
    /** will we allow the # job runners to grow beyond 1? */
    private boolean _allowParallelOperation;
    /** have we been killed or are we alive? */
    private boolean _alive;
    
    /** default max # job queue runners operating */
    private final static int DEFAULT_MAX_RUNNERS = 1;
    /** router.config parameter to override the max runners */
    private final static String PROP_MAX_RUNNERS = "router.maxJobRunners";
    
    /** how frequently should we check and update the max runners */
    private final static long MAX_LIMIT_UPDATE_DELAY = 60*1000;
    
    /** if a job is this lagged, spit out a warning, but keep going */
    private long _lagWarning = DEFAULT_LAG_WARNING;
    private final static long DEFAULT_LAG_WARNING = 5*1000;
    private final static String PROP_LAG_WARNING = "router.jobLagWarning";
    
    /** if a job is this lagged, the router is hosed, so shut it down */
    private long _lagFatal = DEFAULT_LAG_FATAL;
    private final static long DEFAULT_LAG_FATAL = 30*1000;
    private final static String PROP_LAG_FATAL = "router.jobLagFatal";
    
    /** if a job takes this long to run, spit out a warning, but keep going */
    private long _runWarning = DEFAULT_RUN_WARNING;
    private final static long DEFAULT_RUN_WARNING = 5*1000;
    private final static String PROP_RUN_WARNING = "router.jobRunWarning";
    
    /** if a job takes this long to run, the router is hosed, so shut it down */
    private long _runFatal = DEFAULT_RUN_FATAL;
    private final static long DEFAULT_RUN_FATAL = 30*1000;
    private final static String PROP_RUN_FATAL = "router.jobRunFatal";
    
    /** don't enforce fatal limits until the router has been up for this long */
    private long _warmupTime = DEFAULT_WARMUP_TIME;
    private final static long DEFAULT_WARMUP_TIME = 10*60*1000;
    private final static String PROP_WARMUM_TIME = "router.jobWarmupTime";
    
    /** max ready and waiting jobs before we start dropping 'em */
    private int _maxWaitingJobs = DEFAULT_MAX_WAITING_JOBS;
    private final static int DEFAULT_MAX_WAITING_JOBS = 20;
    private final static String PROP_MAX_WAITING_JOBS = "router.maxWaitingJobs";
    
    static {
    }
    
    /** 
     * queue runners wait on this whenever they're not doing anything, and 
     * this gets notified *once* whenever there are ready jobs
     */
    private Object _runnerLock = new Object();
    
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

        _alive = true;
        _readyJobs = new ArrayList();
        _timedJobs = new ArrayList();
        _queueRunners = new HashMap();
        _paused = false;
        _jobStats = new TreeMap();
        _allowParallelOperation = false;
        _pumper = new QueuePumper();
        I2PThread pumperThread = new I2PThread(_pumper);
        pumperThread.setDaemon(true);
        pumperThread.setName("QueuePumper");
        pumperThread.setPriority(I2PThread.MIN_PRIORITY);
        pumperThread.start();
    }
    
    /**
     * Enqueue the specified job
     *
     */
    public void addJob(Job job) {
        if (job == null) return;

        if (job instanceof JobImpl)
            ((JobImpl)job).addedToQueue();

        boolean isReady = false;
        long numReady = 0;
        boolean alreadyExists = false;
        synchronized (_readyJobs) {
            if (_readyJobs.contains(job))
                alreadyExists = true;
            numReady = _readyJobs.size();
        }
        if (!alreadyExists) {
            synchronized (_timedJobs) {
                if (_timedJobs.contains(job))
                    alreadyExists = true;
            }
        }

        _context.statManager().addRateData("jobQueue.readyJobs", numReady, 0);
        if (shouldDrop(job, numReady)) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Dropping job due to overload!  # ready jobs: " 
                           + numReady + ": job = " + job);
            job.dropped();
            _context.statManager().addRateData("jobQueue.droppedJobs", 1, 1);
            awaken(1);
            return;
        }

        if (!alreadyExists) {
            if (job.getTiming().getStartAfter() <= _context.clock().now()) {
                // don't skew us - its 'start after' its been queued, or later
                job.getTiming().setStartAfter(_context.clock().now());
                if (job instanceof JobImpl)
                    ((JobImpl)job).madeReady();
                synchronized (_readyJobs) {
                    _readyJobs.add(job);
                    isReady = true;
                }
            } else {
                synchronized (_timedJobs) {
                    _timedJobs.add(job);
                }
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Not adding already enqueued job " + job.getName());
        }

        if (isReady) {
            // wake up at most one runner
            awaken(1);
        }

        return;
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
        Class cls = job.getClass();
        if (numReady > _maxWaitingJobs) {

            // heavy cpu load, plus we're allowed to be unreliable with these two
            // [but garlics can contain our payloads, so lets not drop them]
            //if (cls == HandleGarlicMessageJob.class)
            //    return true;
            if (cls == HandleSourceRouteReplyMessageJob.class)
                return true;

            // lets not try to drop too many tunnel messages...
            //if (cls == HandleTunnelMessageJob.class)
            //    return true;
                
            // we don't really *need* to answer DB lookup messages
            if (cls == HandleDatabaseLookupMessageJob.class)
                return true;

            // tunnels are a bitch, but its dropped() builds a pair of fake ones just in case
            if (cls == RequestTunnelJob.class)
                return true;

            // if we're already this loaded, dont take more tunnels
            if (cls == HandleTunnelCreateMessageJob.class)
                return true;
        }
        return false;
    }
    
    public void allowParallelOperation() { _allowParallelOperation = true; }
    void shutdown() { _alive = false; }
    boolean isAlive() { return _alive; }
    
    /**
     * Blocking call to retrieve the next ready job
     *
     */
    Job getNext() {
        while (_alive) {
            while (_paused) {
                try { Thread.sleep(30); } catch (InterruptedException ie) {}
            }
            Job rv = null;
            int ready = 0;
            synchronized (_readyJobs) {
                ready = _readyJobs.size();
                if (ready > 0)
                    rv = (Job)_readyJobs.remove(0);
            }
            if (rv != null) {
                // we found one, but there may be more, so wake up enough
                // other runners
                awaken(ready-1);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Using a ready job after waking up " + (ready-1) + " others");
                return rv;
            }
            
            try {
                synchronized (_runnerLock) {
                    _runnerLock.wait(1000);
                }
            } catch (InterruptedException ie) {}
        }
        return null;
    }
    
    /**
     * Move newly ready timed jobs to the ready queue.  Returns the 
     * number of ready jobs after the check is completed
     *
     */
    private int checkJobTimings() {
        boolean newJobsReady = false;
        long now = _context.clock().now();
        ArrayList toAdd = new ArrayList(4);
        synchronized (_timedJobs) {
            for (int i = 0; i < _timedJobs.size(); i++) {
                Job j = (Job)_timedJobs.get(i);
                // find jobs due to start before now
                if (j.getTiming().getStartAfter() <= now) {
                    if (j instanceof JobImpl)
                        ((JobImpl)j).madeReady();

                    toAdd.add(j);
                    _timedJobs.remove(i);
                    i--; // so the index stays consistent
                }
            }
        }

        int ready = 0;
        synchronized (_readyJobs) {
            _readyJobs.addAll(toAdd);
            ready = _readyJobs.size();
        }

        return ready;
    }
    
    /**
     * Start up the queue with the specified number of concurrent processors.
     * If this method has already been called, it will adjust the number of 
     * runners to meet the new number.  This does not kill jobs running on
     * excess threads, it merely instructs the threads to die after finishing
     * the current job.
     *
     */
    public void runQueue(int numThreads) {
        synchronized (_queueRunners) {
            // we're still starting up [serially] and we've got at least one runner,
            // so dont do anything
            if ( (_queueRunners.size() > 0) && (!_allowParallelOperation) ) return;

            // we've already enabled parallel operation, so grow to however many are
            // specified
            if (_queueRunners.size() < numThreads) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Increasing the number of queue runners from " 
                              + _queueRunners.size() + " to " + numThreads);
                for (int i = _queueRunners.size(); i < numThreads; i++) {
                    JobQueueRunner runner = new JobQueueRunner(_context, i);
                    _queueRunners.put(new Integer(i), runner);
                    Thread t = new I2PThread(runner);
                    t.setName("JobQueue"+(_runnerId++));
                    t.setDaemon(false);
                    t.start();
                }
            } else if (_queueRunners.size() == numThreads) {
                // noop
            } else { // numThreads < # runners, so shrink
                //for (int i = _queueRunners.size(); i > numThreads; i++) {
                //     QueueRunner runner = (QueueRunner)_queueRunners.get(new Integer(i));
                //     runner.stopRunning();
                //}
            }
        }
    }
        
    //public void pauseQueue() { _paused = true; }
    //public void unpauseQueue() { _paused = false; }    
    void removeRunner(int id) { _queueRunners.remove(new Integer(id)); }


    /**
     * Notify a sufficient number of waiting runners, and if necessary, increase
     * the number of runners (up to maxRunners)
     *
     */
    private void awaken(int numMadeReady) {
        // notify a sufficient number of waiting runners
        for (int i = 0; i < numMadeReady; i++) {
            synchronized (_runnerLock) {
                _runnerLock.notify();
            }
        }

        int numRunners = 0;
        synchronized (_queueRunners) {
            numRunners = _queueRunners.size();
        }

        if (numRunners > 1) {
            if (numMadeReady > numRunners) {
                if (numMadeReady  < _maxRunners) {
                    _log.info("Too much job contention (" + numMadeReady + " ready and waiting, " + numRunners + " runners exist), adding " + numMadeReady + " new runners (with max " + _maxRunners + ")");
                    runQueue(numMadeReady);
                } else {
                    _log.info("Too much job contention (" + numMadeReady + " ready and waiting, " + numRunners + " runners exist), increasing to our max of " + _maxRunners + " runners");
                    runQueue(_maxRunners);
                }
            }
        }
    }
    
    /**
     * Responsible for moving jobs from the timed queue to the ready queue, 
     * adjusting the number of queue runners, as well as periodically updating the 
     * max number of runners.
     *
     */
    private final class QueuePumper implements Runnable, Clock.ClockUpdateListener {
        private long _lastLimitUpdated;
        public QueuePumper() { 
            _lastLimitUpdated = 0; 
            _context.clock().addUpdateListener(this);
        }
        public void run() {
            try {
                while (_alive) {
                    while (_paused) {
                        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    }
                    
                    // periodically update our max runners limit
                    long now = _context.clock().now();
                    if (now > _lastLimitUpdated + MAX_LIMIT_UPDATE_DELAY) { 
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Updating the limits");
                        updateMaxLimit();
                        updateTimingLimits();
                        _lastLimitUpdated = now;
                    }

                    // turn timed jobs into ready jobs
                    int numMadeReady = checkJobTimings(); 

                    awaken(numMadeReady);

                    try { Thread.sleep(500); } catch (InterruptedException ie) {}
                }
            } catch (Throwable t) {
                _context.clock().removeUpdateListener(this);
                if (_log.shouldLog(Log.ERROR))
                    _log.error("wtf, pumper killed", t);
            }
        }

        public void offsetChanged(long delta) {
            if (_lastLimitUpdated > 0)
                _lastLimitUpdated += delta;
        }

    }
    
    /**
     * calculate and update the job timings
     * if it was lagged too much or took too long to run, spit out
     * a warning (and if its really excessive, kill the router)
     */ 
    void updateStats(Job job, long doStart, long origStartAfter, long duration) {
        String key = job.getName();
        long lag = doStart - origStartAfter; // how long were we ready and waiting?
        MessageHistory hist = _context.messageHistory();
        long uptime = _context.router().getUptime();

        synchronized (_jobStats) {
            if (!_jobStats.containsKey(key))
                _jobStats.put(key, new JobStats(key));
            JobStats stats = (JobStats)_jobStats.get(key);
    
            stats.jobRan(duration, lag);
        }

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
    
    ////
    // update config params
    ////
    
    /**
     * Update the max number of job queue runners 
     *
     */
    private void updateMaxLimit() {
        String str = _context.router().getConfigSetting(PROP_MAX_RUNNERS);
        if (str != null) {
            try {
                _maxRunners = Integer.parseInt(str);
                return;
            } catch (NumberFormatException nfe) {
                _log.error("Invalid maximum job runners [" + str + "]");
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Defaulting the maximum job runners to " + DEFAULT_MAX_RUNNERS);
        _maxRunners = DEFAULT_MAX_RUNNERS;
    }
  
    /**
     * Update the job lag and run threshold for warnings and fatalities, as well
     * as the warmup time before which fatalities will be ignored
     *
     */
    private void updateTimingLimits() {
        String str = _context.router().getConfigSetting(PROP_LAG_WARNING);
        if (str != null) {
            try {
                _lagWarning = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                _log.error("Invalid job lag warning [" + str + "]");
                _lagWarning = DEFAULT_LAG_WARNING;
            }
        } else {
            _lagWarning = DEFAULT_LAG_WARNING;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Setting the warning job lag time to " + _lagWarning + "ms");

        str = _context.router().getConfigSetting(PROP_LAG_FATAL);
        if (str != null) {
            try {
                _lagFatal = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                _log.error("Invalid job lag fatal [" + str + "]");
                _lagFatal = DEFAULT_LAG_FATAL;
            }
        } else {
            _lagFatal = DEFAULT_LAG_FATAL;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Setting the fatal job lag time to " + _lagFatal + "ms");

        str = _context.router().getConfigSetting(PROP_RUN_WARNING);
        if (str != null) {
            try {
                _runWarning = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                _log.error("Invalid job run warning [" + str + "]");
                _runWarning = DEFAULT_RUN_WARNING;
            }
        } else {
            _runWarning = DEFAULT_RUN_WARNING;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Setting the warning job run time to " + _runWarning + "ms");

        str = _context.router().getConfigSetting(PROP_RUN_FATAL);
        if (str != null) {
            try {
                _runFatal = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                _log.error("Invalid job run fatal [" + str + "]");
                _runFatal = DEFAULT_RUN_FATAL;
            }
        } else {
            _runFatal = DEFAULT_RUN_FATAL;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Setting the fatal job run time to " + _runFatal + "ms");

        str = _context.router().getConfigSetting(PROP_WARMUM_TIME);
        if (str != null) {
            try {
                _warmupTime = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                _log.error("Invalid warmup time [" + str + "]");
                _warmupTime = DEFAULT_WARMUP_TIME;
            }
        } else {
            _warmupTime = DEFAULT_WARMUP_TIME;
        }

        str = _context.router().getConfigSetting(PROP_MAX_WAITING_JOBS);
        if (str != null) {
            try {
                _maxWaitingJobs = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                _log.error("Invalid max waiting jobs [" + str + "]");
                _maxWaitingJobs = DEFAULT_MAX_WAITING_JOBS;
            }
        } else {
            _maxWaitingJobs = DEFAULT_MAX_WAITING_JOBS;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Setting the max waiting jobs to " + _maxWaitingJobs);
    }
        
    //// 
    // the remainder are utility methods for dumping status info
    ////
    
    public String renderStatusHTML() {
        ArrayList readyJobs = null;
        ArrayList timedJobs = null;
        ArrayList activeJobs = new ArrayList(4);
        synchronized (_readyJobs) { readyJobs = new ArrayList(_readyJobs); }
        synchronized (_timedJobs) { timedJobs = new ArrayList(_timedJobs); }
        synchronized (_queueRunners) {
            for (Iterator iter = _queueRunners.values().iterator(); iter.hasNext();) {
                JobQueueRunner runner = (JobQueueRunner)iter.next();
                Job job = runner.getCurrentJob();
                if (job != null)
                    activeJobs.add(job.getName());
                }
        }
        StringBuffer buf = new StringBuffer(20*1024);
        buf.append("<h2>JobQueue</h2>");
        buf.append("# runners: ");
        synchronized (_queueRunners) {
            buf.append(_queueRunners.size());
        }
        buf.append("<br />\n");
        buf.append("# active jobs: ").append(activeJobs.size()).append("<ol>\n");
        for (int i = 0; i < activeJobs.size(); i++) {
            buf.append("<li>").append(activeJobs.get(i)).append("</li>\n");
        }
        buf.append("</ol>\n");
        buf.append("# ready/waiting jobs: ").append(readyJobs.size()).append(" <i>(lots of these mean there's likely a big problem)</i><ol>\n");
        for (int i = 0; i < readyJobs.size(); i++) {
            buf.append("<li>").append(readyJobs.get(i)).append("</li>\n");
        }
        buf.append("</ol>\n");

        buf.append("# timed jobs: ").append(timedJobs.size()).append("<ol>\n");
        TreeMap ordered = new TreeMap();
        for (int i = 0; i < timedJobs.size(); i++) {
            Job j = (Job)timedJobs.get(i);
            ordered.put(new Long(j.getTiming().getStartAfter()), j);
        }
        for (Iterator iter = ordered.values().iterator(); iter.hasNext(); ) {
            Job j = (Job)iter.next();
            buf.append("<li>").append(j.getName()).append(" @ ");
            buf.append(new Date(j.getTiming().getStartAfter())).append("</li>\n");
        }
        buf.append("</ol>\n");
        buf.append(getJobStats());
        return buf.toString();
    }
    
    /** render the HTML for the job stats */
    private String getJobStats() { 
        StringBuffer buf = new StringBuffer(16*1024);
        buf.append("<table border=\"1\">\n");
        buf.append("<tr><td><b>Job</b></td><td><b>Runs</b></td>");
        buf.append("<td><b>Time</b></td><td><b><i>Avg</i></b></td><td><b><i>Max</i></b></td><td><b><i>Min</i></b></td>");
        buf.append("<td><b>Pending</b></td><td><b><i>Avg</i></b></td><td><b><i>Max</i></b></td><td><b><i>Min</i></b></td></tr>\n");
        long totRuns = 0;
        long totExecTime = 0;
        long avgExecTime = 0;
        long maxExecTime = -1;
        long minExecTime = -1;
        long totPendingTime = 0;
        long avgPendingTime = 0;
        long maxPendingTime = -1;
        long minPendingTime = -1;

        TreeMap tstats = null;
        synchronized (_jobStats) {
            tstats = (TreeMap)_jobStats.clone();
        }

        for (Iterator iter = tstats.values().iterator(); iter.hasNext(); ) {
            JobStats stats = (JobStats)iter.next();
            buf.append("<tr>");
            buf.append("<td><b>").append(stats.getName()).append("</b></td>");
            buf.append("<td>").append(stats.getRuns()).append("</td>");
            buf.append("<td>").append(stats.getTotalTime()).append("</td>");
            buf.append("<td>").append(stats.getAvgTime()).append("</td>");
            buf.append("<td>").append(stats.getMaxTime()).append("</td>");
            buf.append("<td>").append(stats.getMinTime()).append("</td>");
            buf.append("<td>").append(stats.getTotalPendingTime()).append("</td>");
            buf.append("<td>").append(stats.getAvgPendingTime()).append("</td>");
            buf.append("<td>").append(stats.getMaxPendingTime()).append("</td>");
            buf.append("<td>").append(stats.getMinPendingTime()).append("</td>");
            buf.append("</tr>\n");
            totRuns += stats.getRuns();
            totExecTime += stats.getTotalTime();
            if (stats.getMaxTime() > maxExecTime)
                maxExecTime = stats.getMaxTime();
            if ( (minExecTime < 0) || (minExecTime > stats.getMinTime()) )
                minExecTime = stats.getMinTime();
            totPendingTime += stats.getTotalPendingTime();
            if (stats.getMaxPendingTime() > maxPendingTime)
                maxPendingTime = stats.getMaxPendingTime();
            if ( (minPendingTime < 0) || (minPendingTime > stats.getMinPendingTime()) )
                minPendingTime = stats.getMinPendingTime();
        }

        if (totRuns != 0) {
            if (totExecTime != 0)
                avgExecTime = totExecTime / totRuns;
            if (totPendingTime != 0)
                avgPendingTime = totPendingTime / totRuns;
        }

        buf.append("<tr><td colspan=\"10\"><hr /></td><tr>");
        buf.append("<tr>");
        buf.append("<td><i><b>").append("SUMMARY").append("</b></i></td>");
        buf.append("<td><i>").append(totRuns).append("</i></td>");
        buf.append("<td><i>").append(totExecTime).append("</i></td>");
        buf.append("<td><i>").append(avgExecTime).append("</i></td>");
        buf.append("<td><i>").append(maxExecTime).append("</i></td>");
        buf.append("<td><i>").append(minExecTime).append("</i></td>");
        buf.append("<td><i>").append(totPendingTime).append("</i></td>");
        buf.append("<td><i>").append(avgPendingTime).append("</i></td>");
        buf.append("<td><i>").append(maxPendingTime).append("</i></td>");
        buf.append("<td><i>").append(minPendingTime).append("</i></td>");
        buf.append("</tr>\n");
	
        buf.append("</table>\n");
        return buf.toString();
    }
}
