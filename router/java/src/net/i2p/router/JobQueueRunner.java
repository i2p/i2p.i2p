package net.i2p.router;

import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/** a do run run run a do run run */
class JobQueueRunner implements Runnable {
    private final static Log _log = new Log(JobQueueRunner.class);
    private boolean _keepRunning;
    private int _id;
    private long _numJobs;
    private Job _currentJob;
    
    static {
	StatManager.getInstance().createRateStat("jobQueue.jobRun", "How long jobs take", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
	StatManager.getInstance().createRateStat("jobQueue.jobLag", "How long jobs have to wait before running", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
	StatManager.getInstance().createRateStat("jobQueue.jobWait", "How long does a job sat on the job queue?", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
	StatManager.getInstance().createRateStat("jobQueue.jobRunnerInactive", "How long are runners inactive?", "JobQueue", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
    
    public JobQueueRunner(int id) {
	_id = id;
	_keepRunning = true;
	_numJobs = 0;
	_currentJob = null;
    }
    public Job getCurrentJob() { return _currentJob; }
    public int getRunnerId() { return _id; }
    public void stopRunning() { _keepRunning = false; }
    public void run() {
	long lastActive = Clock.getInstance().now();;
	while ( (_keepRunning) && (JobQueue.getInstance().isAlive()) ) { 
	    try {
		Job job = JobQueue.getInstance().getNext();
		if (job == null) continue;
		long now = Clock.getInstance().now();
		
		long enqueuedTime = 0;
		if (job instanceof JobImpl) {
		    long when = ((JobImpl)job).getMadeReadyOn();
		    if (when <= 0) {
			_log.error("Job was not made ready?! " + job, new Exception("Not made ready?!"));
		    } else {
			enqueuedTime = now - when;
		    }
		}
		
		long betweenJobs = now - lastActive;
		StatManager.getInstance().addRateData("jobQueue.jobRunnerInactive", betweenJobs, betweenJobs);
		_currentJob = job;
		if (_log.shouldLog(Log.DEBUG))
		    _log.debug("Runner " + _id + " running job " + job.getJobId() + ": " + job.getName());
		long origStartAfter = job.getTiming().getStartAfter();
		long doStart = Clock.getInstance().now();
		job.getTiming().start();
		runCurrentJob();
		job.getTiming().end();
		long duration = job.getTiming().getActualEnd() - job.getTiming().getActualStart();

		long beforeUpdate = Clock.getInstance().now();
		JobQueue.getInstance().updateStats(job, doStart, origStartAfter, duration);
		long diff = Clock.getInstance().now() - beforeUpdate;
		
		StatManager.getInstance().addRateData("jobQueue.jobRun", duration, duration);
		StatManager.getInstance().addRateData("jobQueue.jobLag", doStart - origStartAfter, 0);
		StatManager.getInstance().addRateData("jobQueue.jobWait", enqueuedTime, enqueuedTime);
		
		if (diff > 100) {
		    if (_log.shouldLog(Log.WARN))
			_log.warn("Updating statistics for the job took too long [" + diff + "ms]");
		}
		if (_log.shouldLog(Log.DEBUG))
		    _log.debug("Job duration " + duration + "ms for " + job.getName() + " with lag of " + (doStart-origStartAfter) + "ms");
		lastActive = Clock.getInstance().now();
		_currentJob = null;
	    } catch (Throwable t) {
		if (_log.shouldLog(Log.CRIT))
		    _log.log(Log.CRIT, "WTF, error running?", t);
	    }
	}
	if (_log.shouldLog(Log.CRIT))
	    _log.log(Log.CRIT, "Queue runner " + _id + " exiting");
	JobQueue.getInstance().removeRunner(_id);
    }
    
    private void runCurrentJob() {
	try {
	    _currentJob.runJob();
	} catch (OutOfMemoryError oom) {
	    try {
		if (_log.shouldLog(Log.CRIT))
		    _log.log(Log.CRIT, "Router ran out of memory, shutting down", oom);
		Router.getInstance().shutdown();
	    } catch (Throwable t) {	
		System.err.println("***Router ran out of memory, shutting down hard");
	    }
	    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
	    System.exit(-1);
	} catch (Throwable t) {
	    if (_log.shouldLog(Log.CRIT))
		_log.log(Log.CRIT, "Error processing job [" + _currentJob.getName() + "] on thread " + _id + ": " + t.getMessage(), t);
	    if (_log.shouldLog(Log.ERROR))
		_log.error("The above job was enqueued by: ", _currentJob.getAddedBy());
	    JobQueue.getInstance().dumpRunners(true);
	}
    }
}
