package net.i2p.router;

import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Alternate location for determining the time which takes into account an offset.
 * This offset will ideally be periodically updated so as to serve as the difference
 * between the local computer's current time and the time as known by some reference
 * (such as an NTP synchronized clock).
 *
 * RouterClock is a subclass of Clock with access to router transports.
 * Configuration permitting, it will block clock offset changes
 * which would increase peer clock skew.
 */
public class RouterClock extends Clock {

    /**
     *  How often we will slew the clock
     *  i.e. ppm = 1000000/MAX_SLEW
     *  We should be able to slew really fast,
     *  this is probably a lot faster than what NTP does
     *  1/50 is 12s in a 10m tunnel lifetime, that should be fine.
     *  All of this is @since 0.7.12
     */
    private static final long MAX_SLEW = 50;
    private static final int DEFAULT_STRATUM = 8;
    private static final int WORST_STRATUM = 16;
    /** the max NTP Timestamper delay is 30m right now, make this longer than that */
    private static final long MIN_DELAY_FOR_WORSE_STRATUM = 45*60*1000;
    private volatile long _desiredOffset;
    private volatile long _lastSlewed;
    /** use system time for this */
    private long _lastChanged;
    private int _lastStratum;

    private final RouterContext _contextRC;

    public RouterClock(RouterContext context) {
        super(context);
        _contextRC = context;
        _lastStratum = WORST_STRATUM;
    }

    /**
     * Specify how far away from the "correct" time the computer is - a positive
     * value means that we are slow, while a negative value means we are fast.
     *
     */
    @Override
    public void setOffset(long offsetMs, boolean force) {
         setOffset(offsetMs, force, DEFAULT_STRATUM);
    }

    /** @since 0.7.12 */
    private void setOffset(long offsetMs, int stratum) {
         setOffset(offsetMs, false, stratum);
    }

    /** @since 0.7.12 */
    private void setOffset(long offsetMs, boolean force, int stratum) {
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
            
            // let's be perfect
            if (delta == 0) {
                getLog().debug("Not changing offset, delta=0");
                _alreadyChanged = true;
                return;
            }

            // only listen to a worse stratum if it's been a while
            if (_alreadyChanged && stratum > _lastStratum &&
                System.currentTimeMillis() - _lastChanged < MIN_DELAY_FOR_WORSE_STRATUM) {
                getLog().warn("Ignoring update from a stratum " + stratum +
                              " clock, we recently had an update from a stratum " + _lastStratum + " clock");
                return;
            }
            
            // If so configured, check sanity of proposed clock offset
            if (Boolean.valueOf(_contextRC.getProperty("router.clockOffsetSanityCheck","true")).booleanValue() &&
                _alreadyChanged) {

                // Try calculating peer clock skew
                long currentPeerClockSkew = _contextRC.commSystem().getFramedAveragePeerClockSkew(50);

                    // Predict the effect of applying the proposed clock offset
                    long predictedPeerClockSkew = currentPeerClockSkew + delta;

                    // Fail sanity check if applying the offset would increase peer clock skew
                    if ((Math.abs(predictedPeerClockSkew) > (Math.abs(currentPeerClockSkew) + 5*1000)) ||
                        (Math.abs(predictedPeerClockSkew) > 20*1000)) {

                        getLog().error("Ignoring clock offset " + offsetMs + "ms (current " + _offset +
                                       "ms) since it would increase peer clock skew from " + currentPeerClockSkew +
                                       "ms to " + predictedPeerClockSkew + "ms. Bad time server?");
                        return;
                    } else {
                        getLog().debug("Approving clock offset " + offsetMs + "ms (current " + _offset +
                                       "ms) since it would decrease peer clock skew from " + currentPeerClockSkew +
                                       "ms to " + predictedPeerClockSkew + "ms.");
                    }
            } // check sanity
        }

        if (_alreadyChanged) {
            // Update the target offset, slewing will take care of the rest
            if (delta > 15*1000)
                getLog().error("Warning - Updating target clock offset to " + offsetMs + "ms from " + _offset + "ms, Stratum " + stratum);
            else if (getLog().shouldLog(Log.INFO))
                getLog().info("Updating target clock offset to " + offsetMs + "ms from " + _offset + "ms, Stratum " + stratum);
            
            if (!_statCreated) {
                _contextRC.statManager().createRateStat("clock.skew", "How far is the already adjusted clock being skewed?", "Clock", new long[] { 10*60*1000, 3*60*60*1000, 24*60*60*60 });
                _statCreated = true;
            }
            _contextRC.statManager().addRateData("clock.skew", delta, 0);
            _desiredOffset = offsetMs;
        } else {
            getLog().log(Log.INFO, "Initializing clock offset to " + offsetMs + "ms, Stratum " + stratum);
            _alreadyChanged = true;
            _offset = offsetMs;
            _desiredOffset = offsetMs;
            // this is used by the JobQueue
            fireOffsetChanged(delta);
        }
        _lastChanged = System.currentTimeMillis();
        _lastStratum = stratum;

    }

    /**
     *  @param stratum used to determine whether we should ignore
     *  @since 0.7.12
     */
    @Override
    public void setNow(long realTime, int stratum) {
        long diff = realTime - System.currentTimeMillis();
        setOffset(diff, stratum);
    }

    /**
     * Retrieve the current time synchronized with whatever reference clock is in use.
     * Do really simple clock slewing, like NTP but without jitter prevention.
     * Slew the clock toward the desired offset, but only up to a maximum slew rate,
     * and never let the clock go backwards because of slewing.
     * 
     * Take care to only access the volatile variables once for speed and to
     * avoid having another thread change them
     *
     * This is called about a zillion times a second, so we can do the slewing right
     * here rather than in some separate thread to keep it simple.
     * Avoiding backwards clocks when updating in a thread would be hard too.
     */
    @Override
    public long now() {
        long systemNow = System.currentTimeMillis();
        // copy the global, so two threads don't both increment or decrement _offset
        long offset = _offset;
        if (systemNow >= _lastSlewed + MAX_SLEW) {
            // copy the global
            long desiredOffset = _desiredOffset;
            if (desiredOffset > offset) {
                // slew forward
                _offset = ++offset;
            } else if (desiredOffset < offset) {
                // slew backward, but don't let the clock go backward
                // this should be the first call since systemNow
                // was greater than lastSled + MAX_SLEW, i.e. different
                // from the last systemNow, thus we won't let the clock go backward,
                // no need to track when we were last called.
                _offset = --offset;
            }
            _lastSlewed = systemNow;
        }
        return offset + systemNow;
    }

    /*
     *  How far we still have to slew, for diagnostics
     *  @since 0.7.12
     */
    public long getDeltaOffset() {
        return _desiredOffset - _offset;
    }
    
}
