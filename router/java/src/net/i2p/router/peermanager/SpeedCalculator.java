package net.i2p.router.peermanager;

import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Quantify how fast the peer is - how fast they respond to our requests, how fast
 * they pass messages on, etc.  This should be affected both by their bandwidth/latency,
 * as well as their load.
 *
 */
public class SpeedCalculator extends Calculator {
    private Log _log;
    private RouterContext _context;
    
    public SpeedCalculator(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(SpeedCalculator.class);
    }
    
    public double calc(PeerProfile profile) {
        double dbResponseTime = profile.getDbResponseTime().getRate(60*1000).getLifetimeAverageValue();
        double tunnelResponseTime = profile.getTunnelCreateResponseTime().getRate(60*1000).getLifetimeAverageValue();
        double roundTripRate = Math.max(dbResponseTime, tunnelResponseTime);
        
        // send and receive rates are the (period rate) * (saturation %)
        double sendRate = calcSendRate(profile);
        double receiveRate = calcReceiveRate(profile);
        
        
        double val = 60000.0d - 0.1*roundTripRate + sendRate + receiveRate;
        // if we don't have any data, the rate is 0
        if ( (roundTripRate == 0.0d) && (sendRate == 0.0d) )
            val = 0.0;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("roundTripRate: " + roundTripRate + "ms sendRate: " + sendRate + "bytes/second, receiveRate: " + receiveRate + "bytes/second, val: " + val + " for " + profile.getPeer().toBase64());
        
        val += profile.getSpeedBonus();
        return val;
    }
    
    private double calcSendRate(PeerProfile profile) { return calcRate(profile.getSendSuccessSize()); }
    private double calcReceiveRate(PeerProfile profile) { return calcRate(profile.getReceiveSize()); }
    
    private double calcRate(RateStat stat) {
        double rate = 0.0d;
        Rate hourRate = stat.getRate(60*60*1000);
        rate = calcRate(hourRate);
        return rate;
    }
    
    private double calcRate(Rate rate) {
        long events = rate.getLastEventCount() + rate.getCurrentEventCount();
        if (events >= 1) {
            double ms = rate.getLastTotalEventTime() + rate.getCurrentTotalEventTime();
            double bytes = rate.getLastTotalValue() + rate.getCurrentTotalValue();
            if ( (bytes > 0) && (ms > 0) ) {
                return (bytes * 1000.0d) / ms;
            }
        }
        return 0.0d;
    }
    
}
