package net.i2p.router;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/** a do run run run a do run run */
class JobQueueRunner extends I2PThread {
    private final Log _log;
    private final RouterContext _context;
    private volatile boolean _keepRunning;
    private final int _id;
    private volatile Job _currentJob;
    private volatile Job _lastJob;
    private volatile long _lastBegin;
    private volatile long _lastEnd;
    //private volatile int _state;
    
    public JobQueueRunner(RouterContext context, int id) {
        _context = context;
        _id = id;
        _keepRunning = true;
        _log = _context.logManager().getLog(JobQueueRunner.class);
        setPriority(NORM_PRIORITY + 1);
        // all createRateStat in JobQueue
        //_state = 1;
    }
    
    //final int getState() { return _state; }
    
    public Job getCurrentJob() { return _currentJob; }
    public Job getLastJob() { return _lastJob; }
    public int getRunnerId() { return _id; }
    public void stopRunning() { _keepRunning = false; }
    public void startRunning() { _keepRunning = true; }
    public long getLastBegin() { return _lastBegin; }
    public long getLastEnd() { return _lastEnd; }
    public void run() {
        //_state = 2;
        long lastActive = _context.clock().now();
        while ( (_keepRunning) && (_context.jobQueue().isAlive()) ) { 
            //_state = 3;
            try {
                Job job = _context.jobQueue().getNext();
                //_state = 4;
                if (job == null) {
                    //_state = 5;
                    if (_context.router().isAlive())
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("getNext returned null - dead?");
                    continue;
                }
                long now = _context.clock().now();

                long enqueuedTime = 0;
                if (job instanceof JobImpl) {
                    //_state = 6;
                    long when = ((JobImpl)job).getMadeReadyOn();
                    if (when <= 0) {
                        //_state = 7;
                        _log.error("Job was not made ready?! " + job, 
                                   new Exception("Not made ready?!"));
                    } else {
                        //_state = 8;
                        enqueuedTime = now - when;
                    }
                }

                _currentJob = job;
                _lastJob = null;
                //_state = 9;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Runner " + _id + " running job " + job.getJobId() + ": " + job.getName());
                long origStartAfter = job.getTiming().getStartAfter();
                long doStart = _context.clock().now();
                //_state = 10;
                job.getTiming().start();
                runCurrentJob();
                job.getTiming().end();
                //_state = 11;
                long duration = job.getTiming().getActualEnd() - job.getTiming().getActualStart();
                long beforeUpdate = _context.clock().now();
                //_state = 12;
                _context.jobQueue().updateStats(job, doStart, origStartAfter, duration);
                //_state = 13;
                long diff = _context.clock().now() - beforeUpdate;

                long lag = doStart - origStartAfter;
                if (lag < 0) lag = 0;
                
                //_context.statManager().addRateData("jobQueue.jobRunnerInactive", betweenJobs, betweenJobs);
                _context.statManager().addRateData("jobQueue.jobRun", duration, duration);
                _context.statManager().addRateData("jobQueue.jobLag", lag);
                _context.statManager().addRateData("jobQueue.jobWait", enqueuedTime, enqueuedTime);

                if (duration > 1000) {
                    _context.statManager().addRateData("jobQueue.jobRunSlow", duration, duration);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Duration of " + duration + " (lag "+ (doStart-origStartAfter) 
                                  + ") on job " + _currentJob);
                }
                
                //_state = 14;
                
                if (diff > 100) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Updating statistics for the job took too long [" + diff + "ms]");
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Job duration " + duration + "ms for " + job.getName() 
                               + " with lag of " + (doStart-origStartAfter) + "ms");
                lastActive = _context.clock().now();
                _lastJob = _currentJob;
                _currentJob = null;
                _lastEnd = lastActive;
                //_state = 15;
                
                //if ( (jobNum % 10) == 0)
                //    System.gc();
            } catch (Throwable t) {
                _log.log(Log.CRIT, "error running?", t);
            }
        }
        //_state = 16;
        if (_context.router().isAlive())
            _log.log(Log.CRIT, "Queue runner " + _id + " exiting");
        _context.jobQueue().removeRunner(_id);
        //_state = 17;
    }
    
    private void runCurrentJob() {
        try {
            //_state = 18;
            _lastBegin = _context.clock().now();
            _currentJob.runJob();
            //_state = 19;
        } catch (OutOfMemoryError oom) {
            try {
                if (SystemVersion.isAndroid())
                    _context.router().shutdown(Router.EXIT_OOM);
                else
                    fireOOM(oom);
            } catch (Throwable t) {}
        } catch (Throwable t) {
            //_state = 21;
            _log.log(Log.CRIT, "Error processing job [" + _currentJob.getName() 
                                   + "] on thread " + _id + ": " + t.getMessage(), t);
            //if (_log.shouldLog(Log.ERROR))
            //    _log.error("The above job was enqueued by: ", _currentJob.getAddedBy());
        }
    }
}
