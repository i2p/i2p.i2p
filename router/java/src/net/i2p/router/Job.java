package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */


/**
 * Defines an executable task
 *
 */
public interface Job {
    /**
     * Descriptive name of the task
     */
    public String getName();
    /** unique id */
    public long getJobId();
    /**
     * Timing criteria for the task
     */
    public JobTiming getTiming();
    /**
     * Actually perform the task.  This call blocks until the Job is complete.
     */
    public void runJob();
    
    public Exception getAddedBy();
    
    /** 
     * the router is extremely overloaded, so this job has been dropped.  if for
     * some reason the job *must* do some cleanup / requeueing of other tasks, it
     * should do so here.
     *
     */
    public void dropped();
}
