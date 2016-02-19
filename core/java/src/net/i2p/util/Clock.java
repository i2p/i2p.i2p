package net.i2p.util;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import net.i2p.I2PAppContext;
import net.i2p.time.BuildTime;
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
    protected final boolean _isSystemClockBad;
    protected long _startedOn;
    protected boolean _statCreated;
    protected volatile long _offset;
    protected boolean _alreadyChanged;
    private final Set<ClockUpdateListener> _listeners;
    
    public Clock(I2PAppContext context) {
        _context = context;
        _listeners = new CopyOnWriteArraySet<ClockUpdateListener>();
        long now = System.currentTimeMillis();
        long min = BuildTime.getEarliestTime();
        long max = BuildTime.getLatestTime();
        // If the system clock is obviously bad, set our offset so our time is something "close"
        // We do not call setOffset() here as it sets _alreadyChanged.
        // Don't use Log here.
        if (now < min) {
            // positive offset
            _offset = min - now;
            System.out.println("ERROR: System clock is invalid: " + new Date(now));
            now = min;
            _isSystemClockBad = true;
        } else if (now > max) {
            // negative offset
            _offset = max - now;
            System.out.println("ERROR: System clock is invalid: " + new Date(now));
            now = max;
            _isSystemClockBad = true;
        } else {
            _isSystemClockBad = false;
        }
        _startedOn = now;
    }

    public static Clock getInstance() {
        return I2PAppContext.getGlobalContext().clock();
    }
    
    /**
     *  This is a dummy, see RouterClock and RouterTimestamper for the real thing
     */
    public Timestamper getTimestamper() { return new Timestamper(); }
    
    /** we fetch it on demand to avoid circular dependencies (logging uses the clock) */
    protected Log getLog() { return _context.logManager().getLog(Clock.class); }

    /** if the clock is skewed by 3+ days, forget it */
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
     *
     * Warning - overridden in RouterClock
     *
     * @param offsetMs the delta from System.currentTimeMillis() (NOT the delta from now())
     */
    public synchronized void setOffset(long offsetMs, boolean force) {
        long delta = offsetMs - _offset;
        if (!force) {
            if (!_isSystemClockBad && (offsetMs > MAX_OFFSET || offsetMs < 0 - MAX_OFFSET)) {
                Log log = getLog();
                if (log.shouldLog(Log.WARN))
                    log.warn("Maximum offset shift exceeded [" + offsetMs + "], NOT HONORING IT");
                return;
            }
            
            // only allow substantial modifications before the first 10 minutes
            if (_alreadyChanged && (System.currentTimeMillis() - _startedOn > 10 * 60 * 1000)) {
                if ( (delta > MAX_LIVE_OFFSET) || (delta < 0 - MAX_LIVE_OFFSET) ) {
                    Log log = getLog();
                    if (log.shouldLog(Log.WARN))
                        log.warn("The clock has already been updated, but you want to change it by "
                                           + delta + " to " + offsetMs + "?  Did something break?");
                    return;
                }
            }
            
            if ((delta < MIN_OFFSET_CHANGE) && (delta > 0 - MIN_OFFSET_CHANGE)) {
                Log log = getLog();
                if (log.shouldLog(Log.DEBUG))
                    log.debug("Not changing offset since it is only " + delta + "ms");
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
                _context.statManager().createRequiredRateStat("clock.skew", "Clock step adjustment (ms)", "Clock", new long[] { 10*60*1000, 3*60*60*1000, 24*60*60*1000 });
                _statCreated = true;
            }
            _context.statManager().addRateData("clock.skew", delta, 0);
        } else {
            Log log = getLog();
            if (log.shouldLog(Log.INFO))
                log.info("Initializing clock offset to " + offsetMs + "ms from " + _offset + "ms");
        }
        _alreadyChanged = true;
        _offset = offsetMs;
        fireOffsetChanged(delta);
    }

    /*
     * @return the current delta from System.currentTimeMillis() in milliseconds
     */
    public synchronized long getOffset() {
        return _offset;
    }
    
    public boolean getUpdatedSuccessfully() { return _alreadyChanged; }
    
    
    public void setNow(long realTime) {
        if (realTime < BuildTime.getEarliestTime() || realTime > BuildTime.getLatestTime()) {
            Log log = getLog();
            String msg = "Invalid time received: " + new Date(realTime);
            if (log.shouldWarn())
                log.warn(msg, new Exception());
            else
                log.logAlways(Log.WARN, msg);
            return;
        }
        long diff = realTime - System.currentTimeMillis();
        setOffset(diff);
    }

    /**
     *  Warning - overridden in RouterClock
     *
     *  @param stratum ignored
     *  @since 0.7.12
     */
    public void setNow(long realTime, int stratum) {
        setNow(realTime);
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
