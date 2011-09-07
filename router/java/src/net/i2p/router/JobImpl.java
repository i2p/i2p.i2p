package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.concurrent.atomic.AtomicLong;

import net.i2p.util.Log;

/**
 * Base implementation of a Job
 */
public abstract class JobImpl implements Job {
    private final RouterContext _context;
    private final JobTiming _timing;
    private static AtomicLong _idSrc = new AtomicLong();
    private final long _id;
    //private Exception _addedBy;
    private long _madeReadyOn;
    
    public JobImpl(RouterContext context) {
        _context = context;
        _timing = new JobTiming(context);
        _id = _idSrc.incrementAndGet();
    }
    
    public long getJobId() { return _id; }
    public JobTiming getTiming() { return _timing; }
    
    public final RouterContext getContext() { return _context; }
    
    @Override
    public String toString() { 
        StringBuilder buf = new StringBuilder(128);
        buf.append(getClass().getSimpleName());
        buf.append(": Job ").append(_id).append(": ").append(getName());
        return buf.toString();
    }
    
    /**
     *  @deprecated
     *  As of 0.8.1, this is a noop, as it just adds classes to the log manager
     *  class list for no good reason. Logging in jobs is almost always
     *  set explicitly rather than by class name.
     */
    void addedToQueue() {
        //if (_context.logManager().getLog(getClass()).shouldLog(Log.DEBUG))
        //    _addedBy = new Exception();
    }
    
    /**
     *  @deprecated
     *  @return null always
     */
    public Exception getAddedBy() { return null; }
    public long getMadeReadyOn() { return _madeReadyOn; }
    public void madeReady() { _madeReadyOn = _context.clock().now(); }
    public void dropped() {}
    
    protected void requeue(long delayMs) { 
        getTiming().setStartAfter(_context.clock().now() + delayMs);
        _context.jobQueue().addJob(this);
    }
}
