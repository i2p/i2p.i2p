package net.i2p.router;

import net.i2p.util.Log;
import net.i2p.I2PAppContext;
import net.i2p.router.RouterContext;
import net.i2p.util.Clock;

/**
 * Alternate location for determining the time which takes into account an offset.
 * This offset will ideally be periodically updated so as to serve as the difference
 * between the local computer's current time and the time as known by some reference
 * (such as an NTP synchronized clock).
 *
 * RouterClock is a subclass of Clock with access to router transports.
 * TODO: RouterClock is intended to become capable of second-guessing
 * the sanity of clock adjustments.
 */
public class RouterClock extends Clock {

    RouterContext _context;

    public RouterClock(RouterContext context) {
        super(context);
        _context = context;
    }

    /**
     * Specify how far away from the "correct" time the computer is - a positive
     * value means that we are slow, while a negative value means we are fast.
     *
     */
    public void setOffset(long offsetMs, boolean force) {

        // To test new routines, calculate median peer clock skew
        Long medianPeerClockSkew = _context.commSystem().getMedianPeerClockSkew();

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
            
            if (!_statCreated)
                _context.statManager().createRateStat("clock.skew", "How far is the already adjusted clock being skewed?", "Clock", new long[] { 10*60*1000, 3*60*60*1000, 24*60*60*60 });
                _statCreated = true;
            _context.statManager().addRateData("clock.skew", delta, 0);
        } else {
            getLog().log(Log.INFO, "Initializing clock offset to " + offsetMs + "ms from " + _offset + "ms");
        }
        _alreadyChanged = true;
        _offset = offsetMs;
        fireOffsetChanged(delta);
    }

}
