package net.i2p.router;

import net.i2p.util.Log;

/** a do run run run a do run run */
class JobQueueRunner implements Runnable {
    private Log _log;
    private RouterContext _context;
    private boolean _keepRunning;
    private int _id;
    private long _numJobs;
    private Job _currentJob;
    private Job _lastJob;
    
    public JobQueueRunner(RouterContext context, int id) {
        _context = context;
        _id = id;
        _keepRunning = true;
        _numJobs = 0;
        _currentJob = null;
        _lastJob = null;
        _log = _context.logManager().getLog(JobQueueRunner.class);
        _context.statManager().createRateStat("jobQueue.jobRun", "How long jobs take", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("jobQueue.jobLag", "How long jobs have to wait before running", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("jobQueue.jobWait", "How long does a job sat on the job queue?", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("jobQueue.jobRunnerInactive", "How long are runners inactive?", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
    
    public Job getCurrentJob() { return _currentJob; }
    public Job getLastJob() { return _lastJob; }
    public int getRunnerId() { return _id; }
    public void stopRunning() { _keepRunning = false; }
    public void startRunning() { _keepRunning = true; }
    public void run() {
        long lastActive = _context.clock().now();
        long jobNum = 0;
        while ( (_keepRunning) && (_context.jobQueue().isAlive()) ) { 
            try {
                Job job = _context.jobQueue().getNext();
                if (job == null) {
                    if (_context.router().isAlive())
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("getNext returned null - dead?");
                    continue;
                }
                long now = _context.clock().now();

                long enqueuedTime = 0;
                if (job instanceof JobImpl) {
                    long when = ((JobImpl)job).getMadeReadyOn();
                    if (when <= 0) {
                        _log.error("Job was not made ready?! " + job, 
                                   new Exception("Not made ready?!"));
                    } else {
                        enqueuedTime = now - when;
                    }
                }

                long betweenJobs = now - lastActive;
                _currentJob = job;
                _lastJob = null;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Runner " + _id + " running job " + job.getJobId() + ": " + job.getName());
                long origStartAfter = job.getTiming().getStartAfter();
                long doStart = _context.clock().now();
                job.getTiming().start();
                runCurrentJob();
                job.getTiming().end();
                long duration = job.getTiming().getActualEnd() - job.getTiming().getActualStart();
                long beforeUpdate = _context.clock().now();
                _context.jobQueue().updateStats(job, doStart, origStartAfter, duration);
                long diff = _context.clock().now() - beforeUpdate;

                _context.statManager().addRateData("jobQueue.jobRunnerInactive", betweenJobs, betweenJobs);
                _context.statManager().addRateData("jobQueue.jobRun", duration, duration);
                _context.statManager().addRateData("jobQueue.jobLag", doStart - origStartAfter, 0);
                _context.statManager().addRateData("jobQueue.jobWait", enqueuedTime, enqueuedTime);

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
                jobNum++;
                
                //if ( (jobNum % 10) == 0)
                //    System.gc();
            } catch (Throwable t) {
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "WTF, error running?", t);
            }
        }
        if (_context.router().isAlive())
            if (_log.shouldLog(Log.CRIT))
                _log.log(Log.CRIT, "Queue runner " + _id + " exiting");
        _context.jobQueue().removeRunner(_id);
    }
    
    private void runCurrentJob() {
        try {
            _currentJob.runJob();
        } catch (OutOfMemoryError oom) {
            try {
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "Router ran out of memory, shutting down", oom);
                _log.log(Log.CRIT, _currentJob.getClass().getName());
                _context.router().shutdown(Router.EXIT_OOM);
            } catch (Throwable t) {	
                System.err.println("***Router ran out of memory, shutting down hard");
            }
            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            System.exit(-1);
        } catch (Throwable t) {
            if (_log.shouldLog(Log.CRIT))
                _log.log(Log.CRIT, "Error processing job [" + _currentJob.getName() 
                                   + "] on thread " + _id + ": " + t.getMessage(), t);
            if (_log.shouldLog(Log.ERROR))
                _log.error("The above job was enqueued by: ", _currentJob.getAddedBy());
        }
    }
}
