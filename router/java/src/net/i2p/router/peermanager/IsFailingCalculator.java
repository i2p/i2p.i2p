package net.i2p.router.peermanager;

import net.i2p.router.RouterContext;
import net.i2p.stat.Rate;
import net.i2p.util.Log;

/**
 * Simple boolean calculation to determine whether the given profile is "failing" -
 * meaning we shouldn't bother trying to get them to do something.  However, if we
 * have a specific need to contact them in particular - e.g. instructions in a garlic
 * or leaseSet - we will try.  The currently implemented algorithm determines that
 * a profile is failing if withing the last few minutes, they've done something bad: <ul>
 * <li>It has a comm error (TCP disconnect, etc) in the last minute or two</li>
 * <li>They've failed to respond to a db message in the last minute or two</li>
 * <li>They've rejected a tunnel in the last 5 minutes</li>
 * <li>They've been unreachable any time in the last 5 minutes</li>
 * </ul>
 *
 */
public class IsFailingCalculator extends Calculator {
    private Log _log;
    private RouterContext _context;
    
    /** if they haven't b0rked in the last 2 minutes, they're ok */
    private final static long GRACE_PERIOD = 2*60*1000;
    
    public IsFailingCalculator(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(IsFailingCalculator.class);
    }
    
    public boolean calcBoolean(PeerProfile profile) {
        // have we failed in the last 119 seconds?
        if ( (profile.getCommError().getRate(60*1000).getCurrentEventCount() > 0) ||
             (profile.getCommError().getRate(60*1000).getLastEventCount() > 0) || 
             (profile.getCommError().getRate(10*60*1000).getCurrentEventCount() > 0) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Peer " + profile.getPeer().toBase64() 
                           + " is failing because it had a comm error recently ");
            return true;
        } else {
            
            //if ( (profile.getDBHistory().getFailedLookupRate().getRate(60*1000).getCurrentEventCount() > 0) ||
            //     (profile.getDBHistory().getFailedLookupRate().getRate(60*1000).getLastEventCount() > 0) ) {
            //    // are they overloaded (or disconnected)?
            //    return true;
            //}
            
            Rate rejectRate = profile.getTunnelHistory().getRejectionRate().getRate(10*60*1000);
            if (rejectRate.getCurrentEventCount() >= 2) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Peer " + profile.getPeer().toBase64() 
                               + " is failing because they rejected some tunnels recently");
                return true;
            }
            
            ////
            // the right way to behave would be to use some statistical 
            // analysis on the failure rate, and only mark the peer as failing
            // if their rate exceeded the expected rate (mean, median, stddev, etc)
            ////
            
            //Rate failedRate = profile.getTunnelHistory().getFailedRate().getRate(60*1000);
            //if (failedRate.getCurrentEventCount() >= 2) {
            //    if (_log.shouldLog(Log.DEBUG))
            //        _log.debug("Peer " + profile.getPeer().toBase64() 
            //                   + " is failing because too many of their tunnels failed recently");
            //    return true;
            //}
            
            return false;
        }
    }
}
