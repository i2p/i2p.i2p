package net.i2p.router.peermanager;

import net.i2p.util.Log;
import net.i2p.util.Clock;

/**
 * Determine how reliable the peer is - how likely they'll be able to respond or
 * otherwise carry out whatever we ask them to (or even merely be reachable)
 *
 */
class ReliabilityCalculator extends Calculator {
    private final static Log _log = new Log(ReliabilityCalculator.class);
    
    public double calc(PeerProfile profile) {
	// if we've never succeeded (even if we've never tried), the reliability is zip
	if (profile.getSendSuccessSize().getRate(60*60*1000).getLifetimeEventCount() < 0)
	    return profile.getReliabilityBonus();
	
	long val = 0;
	val += profile.getSendSuccessSize().getRate(60*1000).getCurrentEventCount() * 5;
	val += profile.getSendSuccessSize().getRate(60*1000).getLastEventCount() * 2;
	val += profile.getSendSuccessSize().getRate(60*60*1000).getLastEventCount();
	val += profile.getSendSuccessSize().getRate(60*60*1000).getCurrentEventCount();
	
	val += profile.getTunnelCreateResponseTime().getRate(60*1000).getCurrentEventCount() * 10;
	val += profile.getTunnelCreateResponseTime().getRate(60*1000).getLastEventCount() * 5;
	val += profile.getTunnelCreateResponseTime().getRate(60*60*1000).getCurrentEventCount();
	val += profile.getTunnelCreateResponseTime().getRate(60*60*1000).getLastEventCount();
	
	val -= profile.getSendFailureSize().getRate(60*1000).getLastEventCount() * 5;
	val -= profile.getSendFailureSize().getRate(60*60*1000).getCurrentEventCount()*2;
	val -= profile.getSendFailureSize().getRate(60*60*1000).getLastEventCount()*2;
	
	// penalize them heavily for dropping netDb requests
	val -= profile.getDBHistory().getFailedLookupRate().getRate(60*1000).getCurrentEventCount() * 10;
	val -= profile.getDBHistory().getFailedLookupRate().getRate(60*1000).getLastEventCount() * 5;
	//val -= profile.getDBHistory().getFailedLookupRate().getRate(60*60*1000).getCurrentEventCount();
	//val -= profile.getDBHistory().getFailedLookupRate().getRate(60*60*1000).getLastEventCount();
	//val -= profile.getDBHistory().getFailedLookupRate().getRate(24*60*60*1000).getCurrentEventCount() * 50;
	//val -= profile.getDBHistory().getFailedLookupRate().getRate(24*60*60*1000).getLastEventCount() * 20;
	
	val -= profile.getCommError().getRate(60*1000).getCurrentEventCount() * 200;
	val -= profile.getCommError().getRate(60*1000).getLastEventCount() * 200;
	
	val -= profile.getCommError().getRate(60*60*1000).getCurrentEventCount() * 50;
	val -= profile.getCommError().getRate(60*60*1000).getLastEventCount() * 50;
	
	val -= profile.getCommError().getRate(24*60*60*1000).getCurrentEventCount() * 10;
	
	long now = Clock.getInstance().now();
	
	long timeSinceRejection = now - profile.getTunnelHistory().getLastRejected();
	if (timeSinceRejection > 60*60*1000) {
	    // noop.  rejection was over 60 minutes ago
	} else if (timeSinceRejection > 10*60*1000) {
	    val -= 10; // 10-60 minutes ago we got a rejection
	} else if (timeSinceRejection > 60*1000) {
	    val -= 50; // 1-10 minutes ago we got a rejection
	} else {
	    val -= 100; // we got a rejection within the last minute
	}
	
	if ( (profile.getLastSendSuccessful() > 0) && (now - 24*60*60*1000 > profile.getLastSendSuccessful()) ) {
	    // we know they're real, but we havent sent them a message successfully in over a day.  
	    val -= 1000;
	}
	
	val += profile.getReliabilityBonus();
	return val;
    }
}
