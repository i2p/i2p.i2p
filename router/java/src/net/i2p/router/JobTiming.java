package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.util.Clock;

/**
 * Define the timing requirements and statistics for a particular job
 *
 */
public class JobTiming implements Clock.ClockUpdateListener {
    private long _start;
    private long _actualStart;
    private long _actualEnd;
    private final RouterContext _context;
    
    public JobTiming(RouterContext context) {
        _context = context;
        _start = context.clock().now();
        //context.clock().addUpdateListener(this);
    }
    
    /**
     * # of milliseconds after the epoch to start the job
     *
     */
    public long getStartAfter() { return _start; }
    public void setStartAfter(long startTime) { 
        _start = startTime; 
        // sure, this current job object may not already be on the queue, so
        // telling the queue of the update may be irrelevent...
        // but...
        // ...
        // who cares?   this helps in the case where it is on the queue
        _context.jobQueue().timingUpdated();
    }
    
    /**
     * # of milliseconds after the epoch the job actually started
     *
     */
    public long getActualStart() { return _actualStart; }
    public void setActualStart(long actualStartTime) { _actualStart = actualStartTime; }
    /**
     * Notify the timing that the job began
     *
     */
    public void start() { _actualStart = _context.clock().now(); }
    /**
     * # of milliseconds after the epoch the job actually ended
     *
     */
    public long getActualEnd() { return _actualEnd; }
    public void setActualEnd(long actualEndTime) { _actualEnd = actualEndTime; }
    /**
     * Notify the timing that the job finished
     *
     */
    public void end() {
        _actualEnd = _context.clock().now();
        //_context.clock().removeUpdateListener(this);
    }
    
    public void offsetChanged(long delta) {
        if (_start != 0)
            _start += delta;
        if (_actualStart != 0)
            _actualStart += delta;
        if (_actualEnd != 0)
            _actualEnd += delta;
    }
}
