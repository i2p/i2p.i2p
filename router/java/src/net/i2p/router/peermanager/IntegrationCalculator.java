package net.i2p.router.peermanager;

/**
 * Determine how well integrated the peer is - how likely they will be useful
 * to us if we are trying to get further connected.
 *
 */
class IntegrationCalculator {
    
    public static double calc(PeerProfile profile) {
        long val = 0;
        if (profile.getIsExpandedDB()) {
            // give more weight to recent counts
            val = profile.getDbIntroduction().getRate(24*60*60*1000l).getCurrentEventCount();
            val += 2 * 4 * profile.getDbIntroduction().getRate(6*60*60*1000l).getLastEventCount();
            val += 3 * 4 * profile.getDbIntroduction().getRate(6*60*60*1000l).getCurrentEventCount();
            val += 4 * 24 * profile.getDbIntroduction().getRate(60*60*1000l).getCurrentEventCount();
            val /= 10;
        }
        val += profile.getIntegrationBonus();
        return val;
    }
}
