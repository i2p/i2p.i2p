package net.i2p.router.peermanager;

import net.i2p.util.Log;

/**
 * Determine how well integrated the peer is - how likely they will be useful 
 * to us if we are trying to get further connected.
 *
 */
class IntegrationCalculator extends Calculator {
    private final static Log _log = new Log(IntegrationCalculator.class);
    
    public double calc(PeerProfile profile) {
	long val = profile.getDbIntroduction().getRate(24*60*60*1000l).getCurrentEventCount();
	val += profile.getIntegrationBonus();
	return val;
    }
}
