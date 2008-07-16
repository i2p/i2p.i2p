package net.i2p.router.peermanager;

import net.i2p.router.RouterContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Simple speed calculator that just counts how many messages go through the 
 * tunnel.
 *
 */
public class StrictSpeedCalculator extends Calculator {
    private Log _log;
    private RouterContext _context;
    
    public StrictSpeedCalculator(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(StrictSpeedCalculator.class);
    }
    
    public double calc(PeerProfile profile) {
        return countSuccesses(profile);
    }
    private double countSuccesses(PeerProfile profile) {
        RateStat success = profile.getTunnelHistory().getProcessSuccessRate();
        RateStat failure = profile.getTunnelHistory().getProcessFailureRate();
        return messagesPerMinute(success, failure);
    }
    private double messagesPerMinute(RateStat success, RateStat failure) {
        double rv = 0.0d;
        if (success != null) {
            Rate rate = null;
            long periods[] = success.getPeriods();
            for (int i = 0; i < periods.length; i++) {
                rate = success.getRate(periods[i]);
                if ( (rate != null) && (rate.getCurrentTotalValue() > 0) )
                    break;
            }
            
            double value = rate.getCurrentTotalValue();
            value += rate.getLastTotalValue();
            rv = value * 10.0d * 60.0d * 1000.0d / (double)rate.getPeriod();
            
            // if any of the messages are getting fragmented and cannot be
            // handled, penalize like crazy
            Rate fail = failure.getRate(rate.getPeriod());
            if (fail.getCurrentTotalValue() > 0)
                rv /= fail.getCurrentTotalValue();
        }
        return rv;
    }
    
    /*
    public double calc(PeerProfile profile) {
        double successCount = countSuccesses(profile);
        double failureCount = countFailures(profile);
        
        double rv = successCount - 5*failureCount;
        if (rv < 0)
            rv = 0;
        return rv;
    }
    private double countSuccesses(PeerProfile profile) {
        RateStat success = profile.getTunnelHistory().getProcessSuccessRate();
        return messagesPerMinute(success);
    }
    private double countFailures(PeerProfile profile) {
        RateStat failure = profile.getTunnelHistory().getProcessFailureRate();
        return messagesPerMinute(failure);
    }
    private double messagesPerMinute(RateStat stat) {
        double rv = 0.0d;
        if (stat != null) {
            Rate rate = null;
            long periods[] = stat.getPeriods();
            for (int i = 0; i < periods.length; i++) {
                rate = stat.getRate(periods[i]);
                if ( (rate != null) && (rate.getCurrentTotalValue() > 0) )
                    break;
            }
            
            double value = rate.getCurrentTotalValue();
            value += rate.getLastTotalValue();
            rv = value * 60.0d * 1000.0d / (double)rate.getPeriod();
        }
        return rv;
    }
     */
}
