package net.i2p.router.peermanager;

import net.i2p.router.RouterContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Estimate how many of our tunnels the peer can join per hour.
 * Pseudocode:
 * <pre>
 *   int growthFactor = 5;
 *   int capacity = 0;
 *   foreach i (10, 30, 60) {
 *     if (# tunnels rejected in last $i minutes > 0) continue;
 *     int val = (# tunnels joined in last $i minutes) * (60 / $i);
 *     val -= (# tunnels failed in last $i minutes) * (60 / $i);
 *     if (val &gt;= 0)   // if we're failing lots of tunnels, dont grow
 *       capacity += ((val + growthFactor) * periodWeight($i));
 *   }
 *   
 *   periodWeight(int curWeight) {
 *     switch (curWeight) {
 *       case 10: return .6;
 *       case 30: return .3;
 *       case 60: return .1;
 *     }
 *   }
 * </pre>
 *
 */
public class CapacityCalculator extends Calculator {
    private Log _log;
    private RouterContext _context;
    
    public CapacityCalculator(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(CapacityCalculator.class);
    }
    
    /** used to adjust each period so that we keep trying to expand the peer's capacity */
    static long GROWTH_FACTOR = 5;
    
    /** the calculator estimates over a 1 hour period */
    private static long ESTIMATE_PERIOD = 60*60*1000;
    
    public double calc(PeerProfile profile) {
        double capacity = 0;
        
        RateStat acceptStat = profile.getTunnelCreateResponseTime();
        RateStat rejectStat = profile.getTunnelHistory().getRejectionRate();
        RateStat failedStat = profile.getTunnelHistory().getFailedRate();
        
        capacity += estimatePartial(acceptStat, rejectStat, failedStat, 10*60*1000);
        capacity += estimatePartial(acceptStat, rejectStat, failedStat, 30*60*1000);
        capacity += estimatePartial(acceptStat, rejectStat, failedStat, 60*60*1000);
        capacity += estimatePartial(acceptStat, rejectStat, failedStat, 24*60*60*1000);
        
        if (tooOld(profile))
            capacity = 1;
        
        capacity += profile.getReliabilityBonus();
        return capacity;
    }
    
    /**
     * If we haven't heard from them in an hour, they aren't too useful.
     *
     */
    private boolean tooOld(PeerProfile profile) {
        if (profile.getIsActive(60*60*1000)) 
            return false;
        else 
            return true;
    }
    
    private double estimatePartial(RateStat acceptStat, RateStat rejectStat, RateStat failedStat, int period) {
        Rate curAccepted = acceptStat.getRate(period);
        Rate curRejected = rejectStat.getRate(period);
        Rate curFailed = failedStat.getRate(period);

        long eventCount = 0;
        if (curAccepted != null)
            eventCount = curAccepted.getCurrentEventCount() + curAccepted.getLastEventCount();
        double stretch = ESTIMATE_PERIOD / period;
        double val = eventCount * stretch;
        long failed = 0;
        if (curFailed != null)
            failed = curFailed.getCurrentEventCount() + curFailed.getLastEventCount();
        if (failed > 0)
            val -= failed * stretch;
        
        if ( (period == 10*60*1000) && (curRejected.getCurrentEventCount() + curRejected.getLastEventCount() > 0) )
            return 0.0d;
        else
            val -= stretch * (curRejected.getCurrentEventCount() + curRejected.getLastEventCount());
        
        if (val >= 0) {
            return (val + GROWTH_FACTOR) * periodWeight(period);
        } else {
            // failed too much, don't grow
            return 0.0d;
        }
    }
    
    private double periodWeight(int period) {
        switch (period) {
            case 10*60*1000: return .4;
            case 30*60*1000: return .3;
            case 60*60*1000: return .2;
            case 24*60*60*1000: return .1;
            default: throw new IllegalArgumentException("wtf, period [" + period + "]???");
        }
    }
}
