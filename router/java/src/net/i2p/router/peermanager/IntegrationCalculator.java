package net.i2p.router.peermanager;

import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Determine how well integrated the peer is - how likely they will be useful
 * to us if we are trying to get further connected.
 *
 */
public class IntegrationCalculator extends Calculator {
    private Log _log;
    private RouterContext _context;
    
    public IntegrationCalculator(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(IntegrationCalculator.class);
    }
    
    public double calc(PeerProfile profile) {
        long val = profile.getDbIntroduction().getRate(24*60*60*1000l).getCurrentEventCount();
        val += profile.getIntegrationBonus();
        return val;
    }
}
