package net.i2p.util;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import net.i2p.I2PAppContext;
import net.i2p.time.Timestamper;

/**
 * Alternate location for determining the time which takes into account an offset.
 * This offset will ideally be periodically updated so as to serve as the difference
 * between the local computer's current time and the time as known by some reference
 * (such as an NTP synchronized clock).
 *
 * Protected members are used in the subclass RouterClock,
 * which has access to a router's transports (particularly peer clock skews)
 * to second-guess the sanity of clock adjustments.
 *
 */
public class Clock implements Timestamper.UpdateListener {
    protected final I2PAppContext _context;
    private final Timestamper _timestamper;
    protected long _startedOn;
    protected boolean _statCreated;
    protected volatile long _offset;
    protected boolean _alreadyChanged;
    private final Set<ClockUpdateListener> _listeners;
    
    public Clock(I2PAppContext context) {
        _context = context;
        _listeners = new CopyOnWriteArraySet();
        _timestamper = new Timestamper(context, this);
        _startedOn = System.currentTimeMillis();
    }

    public static Clock getInstance() {
        return I2PAppContext.getGlobalContext().clock();
    }
    
    public Timestamper getTimestamper() { return _timestamper; }
    
    /** we fetch it on demand to avoid circular dependencies (logging uses the clock) */
    protected Log getLog() { return _context.logManager().getLog(Clock.class); }

    /** if the clock is skewed by 3+ days, fuck 'em */
    public final static long MAX_OFFSET = 3 * 24 * 60 * 60 * 1000;
    /** after we've started up and shifted the clock, don't allow shifts of more than 10 minutes */
    public final static long MAX_LIVE_OFFSET = 10 * 60 * 1000;
    /** if the clock skewed changes by less than this, ignore the update (so we don't slide all over the place) */
    public final static long MIN_OFFSET_CHANGE = 5 * 1000;

    /**
     * Specify how far away from the "correct" time the computer is - a positive
     * value means that the system time is slow, while a negative value means the system time is fast.
     *
     * @param offsetMs the delta from System.currentTimeMillis() (NOT the delta from now())
     */
    public void setOffset(long offsetMs) {
        setOffset(offsetMs, false);        
    }
    
    /**
     * Specify how far away from the "correct" time the computer is - a positive
     * value means that the system time is slow, while a negative value means the system time is fast.
     * Warning - overridden in RouterClock
     *
     * @param offsetMs the delta from System.currentTimeMillis() (NOT the delta from now())
     */
    public void setOffset(long offsetMs, boolean force) {
        if (false) return;
        long delta = offsetMs - _offset;
        if (!force) {
            if ((offsetMs > MAX_OFFSET) || (offsetMs < 0 - MAX_OFFSET)) {
                getLog().error("Maximum offset shift exceeded [" + offsetMs + "], NOT HONORING IT");
                return;
            }
            
            // only allow substantial modifications before the first 10 minutes
            if (_alreadyChanged && (System.currentTimeMillis() - _startedOn > 10 * 60 * 1000)) {
                if ( (delta > MAX_LIVE_OFFSET) || (delta < 0 - MAX_LIVE_OFFSET) ) {
                    getLog().log(Log.CRIT, "The clock has already been updated, but you want to change it by "
                                           + delta + " to " + offsetMs + "?  Did something break?");
                    return;
                }
            }
            
            if ((delta < MIN_OFFSET_CHANGE) && (delta > 0 - MIN_OFFSET_CHANGE)) {
                getLog().debug("Not changing offset since it is only " + delta + "ms");
                _alreadyChanged = true;
                return;
            }
        }
        if (_alreadyChanged) {
            if (delta > 15*1000)
                getLog().log(Log.CRIT, "Updating clock offset to " + offsetMs + "ms from " + _offset + "ms");
            else if (getLog().shouldLog(Log.INFO))
                getLog().info("Updating clock offset to " + offsetMs + "ms from " + _offset + "ms");
            
            if (!_statCreated) {
                _context.statManager().createRequiredRateStat("clock.skew", "Clock step adjustment (ms)", "Clock", new long[] { 10*60*1000, 3*60*60*1000, 24*60*60*60 });
                _statCreated = true;
            }
            _context.statManager().addRateData("clock.skew", delta, 0);
        } else {
            getLog().log(Log.INFO, "Initializing clock offset to " + offsetMs + "ms from " + _offset + "ms");
        }
        _alreadyChanged = true;
        _offset = offsetMs;
        fireOffsetChanged(delta);
    }

    /*
     * @return the current delta from System.currentTimeMillis() in milliseconds
     */
    public long getOffset() {
        return _offset;
    }
    
    public boolean getUpdatedSuccessfully() { return _alreadyChanged; }
    
    
    public void setNow(long realTime) {
        long diff = realTime - System.currentTimeMillis();
        setOffset(diff);
    }

    /**
     *  @param stratum ignored
     *  @since 0.7.12
     */
    public void setNow(long realTime, int stratum) {
        long diff = realTime - System.currentTimeMillis();
        setOffset(diff);
    }

    /**
     * Retrieve the current time synchronized with whatever reference clock is in
     * use.
     *
     */
    public long now() {
        return _offset + System.currentTimeMillis();
    }

    public void addUpdateListener(ClockUpdateListener lsnr) {
            _listeners.add(lsnr);
    }

    public void removeUpdateListener(ClockUpdateListener lsnr) {
            _listeners.remove(lsnr);
    }

    protected void fireOffsetChanged(long delta) {
            for (ClockUpdateListener lsnr : _listeners) {
                lsnr.offsetChanged(delta);
            }
    }

    public interface ClockUpdateListener {

        /**
         *  @param delta = (new offset - old offset),
         *         where each offset = (now() - System.currentTimeMillis())
         */
        public void offsetChanged(long delta);
    }
}
