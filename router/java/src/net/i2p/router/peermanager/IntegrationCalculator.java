package net.i2p.router.peermanager;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

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
    
    @Override
    public double calc(PeerProfile profile) {
        // give more weight to recent counts
        long val = profile.getDbIntroduction().getRate(24*60*60*1000l).getCurrentEventCount();
        val += 2 * 4 * profile.getDbIntroduction().getRate(6*60*60*1000l).getLastEventCount();
        val += 3 * 4 * profile.getDbIntroduction().getRate(6*60*60*1000l).getCurrentEventCount();
        val += 4 * 24 * profile.getDbIntroduction().getRate(60*60*1000l).getCurrentEventCount();
        val /= 10;
        val += profile.getIntegrationBonus();
        return val;
    }
}
