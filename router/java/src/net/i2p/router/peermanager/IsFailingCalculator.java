package net.i2p.router.peermanager;

import net.i2p.util.Log;
import net.i2p.util.Clock;

/**
 * Simple boolean calculation to determine whether the given profile is "failing" -
 * meaning we shouldn't bother trying to get them to do something (however, if we 
 * have a specific need to contact them in particular - e.g. instructions in a garlic
 * or leaseSet - we will try).  The currently implemented algorithm determines that 
 * a profile is failing if withing the last few minutes, they've done something bad: <ul>
 * <li>It has a comm error (TCP disconnect, etc) in the last minute or two</li>
 * <li>They've failed to respond to a db message in the last minute or two</li>
 * <li>They've rejected a tunnel in the last 5 minutes</li>
 * <li>They've been unreachable any time in the last 5 minutes</li>
 * </ul>
 *
 */
class IsFailingCalculator extends Calculator {
    private final static Log _log = new Log(IsFailingCalculator.class);
    
    /** if they haven't b0rked in the last 5 minutes, they're ok */
    private final static long GRACE_PERIOD = 5*60*1000;
    
    public boolean calcBoolean(PeerProfile profile) {
	// have we failed in the last 119 seconds?
	if ( (profile.getCommError().getRate(60*1000).getCurrentEventCount() > 0) ||
	     (profile.getCommError().getRate(60*1000).getLastEventCount() > 0) ) {
	    return true;
	} else {
	    if ( (profile.getDBHistory().getFailedLookupRate().getRate(60*1000).getCurrentEventCount() > 0) ||
	         (profile.getDBHistory().getFailedLookupRate().getRate(60*1000).getLastEventCount() > 0) ) {
		 // are they overloaded (or disconnected)?
		 return true;
	    }
	    
	    long recently = Clock.getInstance().now() - GRACE_PERIOD;
	    
	    if (profile.getTunnelHistory().getLastRejected() >= recently) {
		// have they refused to participate in a tunnel in the last 5 minutes?
		return true;
	    }
	    
	    if (profile.getLastSendFailed() >= recently)
		return true;
	    
	    return false;
	}
    }
}
