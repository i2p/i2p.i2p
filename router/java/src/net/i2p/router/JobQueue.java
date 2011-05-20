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
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.DataHelper;
import net.i2p.router.networkdb.HandleDatabaseLookupMessageJob;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Manage the pending jobs according to whatever algorithm is appropriate, giving
 * preference to earlier scheduled jobs.
 *
 */
public class JobQueue {
    private final Log _log;
    private final RouterContext _context;
    
    /** Integer (runnerId) to JobQueueRunner for created runners */
    private final Map<Integer, JobQueueRunner> _queueRunners;
    /** a counter to identify a job runner */
    private volatile static int _runnerId = 0;
    /** list of jobs that are ready to run ASAP */
    private final BlockingQueue<Job> _readyJobs;
    /** list of jobs that are scheduled for running in the future */
    private final List<Job> _timedJobs;
    /** job name to JobStat for that job */
    private final Map<String, JobStats> _jobStats;
    /** how many job queue runners can go concurrently */
    private int _maxRunners = 1; 
    private final QueuePumper _pumper;
    /** will we allow the # job runners to grow beyond 1? */
    private boolean _allowParallelOperation;
    /** have we been killed or are we alive? */
    private boolean _alive;
    
    private final Object _jobLock;
    
    /** how many when we go parallel */
    private static final int RUNNERS;
    static {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 128*1024*1024l;
        if (maxMemory < 64*1024*1024)
            RUNNERS = 3;
        else if (maxMemory < 256*1024*1024)
            RUNNERS = 4;
        else
            RUNNERS = 5;
    }

    /** default max # job queue runners operating */
    private final static int DEFAULT_MAX_RUNNERS = 1;
    /** router.config parameter to override the max runners @deprecated unimplemented */
    private final static String PROP_MAX_RUNNERS = "router.maxJobRunners";
    
    /** how frequently should we check and update the max runners */
    private final static long MAX_LIMIT_UPDATE_DELAY = 60*1000;
    
    /** if a job is this lagged, spit out a warning, but keep going */
    private long _lagWarning = DEFAULT_LAG_WARNING;
    private final static long DEFAULT_LAG_WARNING = 5*1000;
    /** @deprecated unimplemented */
    private final static String PROP_LAG_WARNING = "router.jobLagWarning";
    
    /** if a job is this lagged, the router is hosed, so spit out a warning (dont shut it down) */
    private long _lagFatal = DEFAULT_LAG_FATAL;
    private final static long DEFAULT_LAG_FATAL = 30*1000;
    /** @deprecated unimplemented */
    private final static String PROP_LAG_FATAL = "router.jobLagFatal";
    
    /** if a job takes this long to run, spit out a warning, but keep going */
    private long _runWarning = DEFAULT_RUN_WARNING;
    private final static long DEFAULT_RUN_WARNING = 5*1000;
    /** @deprecated unimplemented */
    private final static String PROP_RUN_WARNING = "router.jobRunWarning";
    
    /** if a job takes this long to run, the router is hosed, so spit out a warning (dont shut it down) */
    private long _runFatal = DEFAULT_RUN_FATAL;
    private final static long DEFAULT_RUN_FATAL = 30*1000;
    /** @deprecated unimplemented */
    private final static String PROP_RUN_FATAL = "router.jobRunFatal";
    
    /** don't enforce fatal limits until the router has been up for this long */
    private long _warmupTime = DEFAULT_WARMUP_TIME;
    private final static long DEFAULT_WARMUP_TIME = 10*60*1000;
    /** @deprecated unimplemented */
    private final static String PROP_WARMUP_TIME = "router.jobWarmupTime";
    
    /** max ready and waiting jobs before we start dropping 'em */
    private int _maxWaitingJobs = DEFAULT_MAX_WAITING_JOBS;
    private final static int DEFAULT_MAX_WAITING_JOBS = 100;
    /** @deprecated unimplemented */
    private final static String PROP_MAX_WAITING_JOBS = "router.maxWaitingJobs";

    /** 
     * queue runners wait on this whenever they're not doing anything, and 
     * this gets notified *once* whenever there are ready jobs
     */
    private final Object _runnerLock = new Object();
    
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
        _readyJobs = new LinkedBlockingQueue();
        _timedJobs = new ArrayList(64);
        _jobLock = new Object();
        _queueRunners = new ConcurrentHashMap(RUNNERS);
        _jobStats = new ConcurrentHashMap();
        _allowParallelOperation = false;
        _pumper = new QueuePumper();
        I2PThread pumperThread = new I2PThread(_pumper, "Job Queue Pumper", true);
        //pumperThread.setPriority(I2PThread.NORM_PRIORITY+1);
        pumperThread.start();
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

        long numReady = 0;
        boolean alreadyExists = false;
        boolean dropped = false;
        // getNext() is now outside the jobLock, is that ok?
        synchronized (_jobLock) {
            if (_readyJobs.contains(job))
                alreadyExists = true;
            numReady = _readyJobs.size();
            if (!alreadyExists) {
                if (_timedJobs.contains(job))
                    alreadyExists = true;
            }

            if (shouldDrop(job, numReady)) {
                job.dropped();
                dropped = true;
            } else {
                if (!alreadyExists) {
                    if (job.getTiming().getStartAfter() <= _context.clock().now()) {
                        // don't skew us - its 'start after' its been queued, or later
                        job.getTiming().setStartAfter(_context.clock().now());
                        if (job instanceof JobImpl)
                            ((JobImpl)job).madeReady();
                        _readyJobs.offer(job);
                    } else {
                        _timedJobs.add(job);
                    }
                }
            }
            _jobLock.notifyAll();
        }
        
        _context.statManager().addRateData("jobQueue.readyJobs", numReady, 0);
        if (dropped) {
            _context.statManager().addRateData("jobQueue.droppedJobs", 1, 1);
            if (_log.shouldLog(Log.ERROR))
                _log.error("Dropping job due to overload!  # ready jobs: " 
                          + numReady + ": job = " + job);
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
     */
    public boolean isJobActive(Job job) {
        if (_readyJobs.contains(job) || _timedJobs.contains(job))
            return true;
        for (JobQueueRunner runner: _queueRunners.values())
            if (runner.getCurrentJob() == job)
                return true;
        return false;
    }
    
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
        Class cls = job.getClass();
        if (numReady > _maxWaitingJobs) {
            // lets not try to drop too many tunnel messages...
            //if (cls == HandleTunnelMessageJob.class)
            //    return true;
                
            // we don't really *need* to answer DB lookup messages
            if (cls == HandleDatabaseLookupMessageJob.class)
                return true;

        }
        return false;
    }
    
    public void allowParallelOperation() { 
        _allowParallelOperation = true; 
        runQueue(RUNNERS);
    }
    
    /** @deprecated do you really want to do this? */
    public void restart() {
        synchronized (_jobLock) {
            _timedJobs.clear();
            _readyJobs.clear();
            _jobLock.notifyAll();
        }
    }
    
    void shutdown() { 
        _alive = false; 
        _timedJobs.clear();
        _readyJobs.clear();
        // The JobQueueRunners are NOT daemons,
        // so they must be stopped.
        Job poison = new PoisonJob();
        for (int i = 0; i < _queueRunners.size(); i++)
            _readyJobs.offer(poison);


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
                    Thread t = new I2PThread(runner, "JobQueue " + (++_runnerId) + '/' + numThreads, false);
                    //t.setPriority(I2PThread.MAX_PRIORITY-1);
                    t.start();
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
    private final class QueuePumper implements Runnable, Clock.ClockUpdateListener {
        public QueuePumper() { 
            _context.clock().addUpdateListener(this);
        }
        public void run() {
            try {
                while (_alive) {
                    long now = _context.clock().now();
                    long timeToWait = -1;
                    List<Job> toAdd = null;
                    try {
                        synchronized (_jobLock) {
                            for (int i = 0; i < _timedJobs.size(); i++) {
                                Job j = _timedJobs.get(i);
                                // find jobs due to start before now
                                long timeLeft = j.getTiming().getStartAfter() - now;
                                if (timeLeft <= 0) {
                                    if (j instanceof JobImpl)
                                        ((JobImpl)j).madeReady();

                                    if (toAdd == null) toAdd = new ArrayList(4);
                                    toAdd.add(j);
                                    _timedJobs.remove(i);
                                    i--; // so the index stays consistent
                                } else {
                                    if ( (timeToWait <= 0) || (timeLeft < timeToWait) )
                                        timeToWait = timeLeft;
                                }
                            }

                            if (toAdd != null) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("Not waiting - we have " + toAdd.size() + " newly ready jobs");
                                // rather than addAll, which allocs a byte array rv before adding, 
                                // we iterate, since toAdd is usually going to only be 1 or 2 entries
                                // and since readyJobs will often have the space, we can avoid the
                                // extra alloc.  (no, i'm not just being insane - i'm updating this based
                                // on some profiling data ;)
                                for (int i = 0; i < toAdd.size(); i++)
                                    _readyJobs.offer(toAdd.get(i));
                                _jobLock.notifyAll();
                            } else {
                                if (timeToWait < 0)
                                    timeToWait = 30*1000;
                                else if (timeToWait < 10)
                                    timeToWait = 10;
                                else if (timeToWait > 10*1000)
                                    timeToWait = 10*1000;
                                //if (_log.shouldLog(Log.DEBUG))
                                //    _log.debug("Waiting " + timeToWait + " before rechecking the timed queue");
                                _jobLock.wait(timeToWait);
                            }
                        } // synchronize (_jobLock)
                    } catch (InterruptedException ie) {}
                } // while (_alive)
            } catch (Throwable t) {
                _context.clock().removeUpdateListener(this);
                if (_log.shouldLog(Log.ERROR))
                    _log.error("wtf, pumper killed", t);
            }
        }

        public void offsetChanged(long delta) {
            updateJobTimings(delta);
            synchronized (_jobLock) {
                _jobLock.notifyAll();
            }
        }

    }
    
    /**
     * Update the clock data for all jobs in process or scheduled for
     * completion.
     */
    private void updateJobTimings(long delta) {
        synchronized (_jobLock) {
            for (int i = 0; i < _timedJobs.size(); i++) {
                Job j = _timedJobs.get(i);
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
            _jobStats.put(key, stats);
            // yes, if two runners finish the same job at the same time, this could
            // create an extra object.  but, who cares, its pushed out of the map
            // immediately anyway.
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

    //// 
    // the remainder are utility methods for dumping status info
    ////
    
    public void renderStatusHTML(Writer out) throws IOException {
        List<Job> readyJobs = null;
        List<Job> timedJobs = null;
        List<Job> activeJobs = new ArrayList(RUNNERS);
        List<Job> justFinishedJobs = new ArrayList(RUNNERS);
        //out.write("<!-- jobQueue rendering -->\n");
        out.flush();
        
        //int states[] = null;
        int numRunners = 0;

        {
            //states = new int[_queueRunners.size()];
            int i = 0;
            for (Iterator<JobQueueRunner> iter = _queueRunners.values().iterator(); iter.hasNext(); i++) {
                JobQueueRunner runner = iter.next();
                //states[i] = runner.getState();
                Job job = runner.getCurrentJob();
                if (job != null) {
                    activeJobs.add(job);
                } else {
                    job = runner.getLastJob();
                    if (job != null)
                        justFinishedJobs.add(job);
                }
            }
            numRunners = _queueRunners.size();
        }
        
/*******
        StringBuilder str = new StringBuilder(128);
        str.append("<!-- after queueRunner sync: states: ");
        for (int i = 0; states != null && i < states.length; i++)
            str.append(states[i]).append(" ");
        str.append(" -->\n");
        str.append("<!-- jobs: ");
        for (int i = 0; i < activeJobs.size(); i++)
            str.append(activeJobs.get(i).toString()).append(" ");
        str.append("-->\n");
        out.write(str.toString());
        out.flush();
*******/
        
        synchronized (_jobLock) {
            readyJobs = new ArrayList(_readyJobs); 
            timedJobs = new ArrayList(_timedJobs);
        }
        //out.write("<!-- jobQueue rendering: after jobLock sync -->\n");
        //out.flush();
        
        StringBuilder buf = new StringBuilder(32*1024);
        buf.append("<b><div class=\"joblog\"><h3>I2P Job Queue</h3><div class=\"wideload\">Job runners: ").append(numRunners);
        //buf.append(" [states=");
        //if (states != null) 
        //    for (int i = 0; i < states.length; i++) 
        //        buf.append(states[i]).append(" ");
        //buf.append(']');
        buf.append("</b><br>\n");

        long now = _context.clock().now();

        buf.append("<hr><b>Active jobs: ").append(activeJobs.size()).append("</b><ol>\n");
        for (int i = 0; i < activeJobs.size(); i++) {
            Job j = activeJobs.get(i);
            buf.append("<li>[started ").append(DataHelper.formatDuration(now-j.getTiming().getStartAfter())).append(" ago]: ");
            buf.append(j.toString()).append("</li>\n");
        }
        buf.append("</ol>\n");
        buf.append("<hr><b>Just finished jobs: ").append(justFinishedJobs.size()).append("</b><ol>\n");
        for (int i = 0; i < justFinishedJobs.size(); i++) {
            Job j = justFinishedJobs.get(i);
            buf.append("<li>[finished ").append(DataHelper.formatDuration(now-j.getTiming().getActualEnd())).append(" ago]: ");
            buf.append(j.toString()).append("</li>\n");
        }
        buf.append("</ol>\n");
        buf.append("<hr><b>Ready/waiting jobs: ").append(readyJobs.size()).append("</b><ol>\n");
        for (int i = 0; i < readyJobs.size(); i++) {
            Job j = readyJobs.get(i);
            buf.append("<li>[waiting ");
            buf.append(DataHelper.formatDuration(now-j.getTiming().getStartAfter()));
            buf.append("]: ");
            buf.append(j.toString()).append("</li>\n");
        }
        buf.append("</ol>\n");
        out.flush();

        buf.append("<hr><b>Scheduled jobs: ").append(timedJobs.size()).append("</b><ol>\n");
        TreeMap<Long, Job> ordered = new TreeMap();
        for (int i = 0; i < timedJobs.size(); i++) {
            Job j = timedJobs.get(i);
            ordered.put(Long.valueOf(j.getTiming().getStartAfter()), j);
        }
        for (Iterator<Job> iter = ordered.values().iterator(); iter.hasNext(); ) {
            Job j = iter.next();
            long time = j.getTiming().getStartAfter() - now;
            buf.append("<li>").append(j.getName()).append(" in ");
            buf.append(DataHelper.formatDuration(time)).append("</li>\n");
        }
        buf.append("</ol></div>\n");
        
        //out.write("<!-- jobQueue rendering: after main buffer, before stats -->\n");
        out.flush();
        
        getJobStats(buf);
        
        //out.write("<!-- jobQueue rendering: after stats -->\n");
        out.flush();
        
        out.write(buf.toString());
    }
    
    /** render the HTML for the job stats */
    private void getJobStats(StringBuilder buf) { 
        buf.append("<table>\n" +
                   "<tr><th>Job</th><th>Runs</th>" +
                   "<th>Time</th><th><i>Avg</i></th><th><i>Max</i></th><th><i>Min</i></th>" +
                   "<th>Pending</th><th><i>Avg</i></th><th><i>Max</i></th><th><i>Min</i></th></tr>\n");
        long totRuns = 0;
        long totExecTime = 0;
        long avgExecTime = 0;
        long maxExecTime = -1;
        long minExecTime = -1;
        long totPendingTime = 0;
        long avgPendingTime = 0;
        long maxPendingTime = -1;
        long minPendingTime = -1;

        TreeMap<String, JobStats> tstats = new TreeMap(_jobStats);
        
        for (Iterator<JobStats> iter = tstats.values().iterator(); iter.hasNext(); ) {
            JobStats stats = iter.next();
            buf.append("<tr>");
            buf.append("<td><b>").append(stats.getName()).append("</b></td>");
            buf.append("<td align=\"right\">").append(stats.getRuns()).append("</td>");
            buf.append("<td align=\"right\">").append(stats.getTotalTime()).append("</td>");
            buf.append("<td align=\"right\">").append(stats.getAvgTime()).append("</td>");
            buf.append("<td align=\"right\">").append(stats.getMaxTime()).append("</td>");
            buf.append("<td align=\"right\">").append(stats.getMinTime()).append("</td>");
            buf.append("<td align=\"right\">").append(stats.getTotalPendingTime()).append("</td>");
            buf.append("<td align=\"right\">").append(stats.getAvgPendingTime()).append("</td>");
            buf.append("<td align=\"right\">").append(stats.getMaxPendingTime()).append("</td>");
            buf.append("<td align=\"right\">").append(stats.getMinPendingTime()).append("</td>");
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

        buf.append("<tr class=\"tablefooter\">");
        buf.append("<td><b>").append("SUMMARY").append("</b></td>");
        buf.append("<td align=\"right\">").append(totRuns).append("</td>");
        buf.append("<td align=\"right\">").append(totExecTime).append("</td>");
        buf.append("<td align=\"right\">").append(avgExecTime).append("</td>");
        buf.append("<td align=\"right\">").append(maxExecTime).append("</td>");
        buf.append("<td align=\"right\">").append(minExecTime).append("</td>");
        buf.append("<td align=\"right\">").append(totPendingTime).append("</td>");
        buf.append("<td align=\"right\">").append(avgPendingTime).append("</td>");
        buf.append("<td align=\"right\">").append(maxPendingTime).append("</td>");
        buf.append("<td align=\"right\">").append(minPendingTime).append("</td>");
        buf.append("</tr></table></div>\n");
    }
}
