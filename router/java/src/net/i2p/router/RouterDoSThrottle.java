package net.i2p.router;

import net.i2p.data.Hash;

/**
 * Minor extention of the router throttle to handle some DoS events and 
 * throttle accordingly.
 *
 * @deprecated unused
 */
class RouterDoSThrottle extends RouterThrottleImpl {
    public RouterDoSThrottle(RouterContext context) {
        super(context);
        context.statManager().createRateStat("router.throttleNetDbDoS", "How many netDb lookup messages have we received so far during a period with a DoS detected", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }
    
    private volatile long _currentLookupPeriod;
    private volatile int _currentLookupCount;
    // if we receive over 20 netDb lookups in 10 seconds, someone is acting up
    private static final long LOOKUP_THROTTLE_PERIOD = 10*1000;
    private static final long LOOKUP_THROTTLE_MAX = 20;
    
    @Override
    public boolean acceptNetDbLookupRequest(Hash key) { 
        // if we were going to refuse it anyway, drop it
        boolean shouldAccept = super.acceptNetDbLookupRequest(key);
        if (!shouldAccept) return false;
        
        // now lets check for DoS
        long now = _context.clock().now();
        if (_currentLookupPeriod + LOOKUP_THROTTLE_PERIOD > now) {
            // same period, check for DoS
            _currentLookupCount++;
            if (_currentLookupCount >= LOOKUP_THROTTLE_MAX) {
                _context.statManager().addRateData("router.throttleNetDbDoS", _currentLookupCount, 0);
                int rand = _context.random().nextInt(_currentLookupCount);
                if (rand > LOOKUP_THROTTLE_MAX) {
                    return false;
                } else {
                    return true;
                }
            } else {
                // no DoS, at least, not yet
                return true;
            }
        } else {
            // on to the next period, reset counter, no DoS
            // (no, I'm not worried about concurrency here)
            _currentLookupPeriod = now;
            _currentLookupCount = 1;
            return true;
        }
    }

}
