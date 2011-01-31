package net.i2p.router.peermanager;

import net.i2p.I2PAppContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

/**
 * Estimate how many of our tunnels the peer can join per hour.
 */
class CapacityCalculator {
    private static final I2PAppContext _context = I2PAppContext.getGlobalContext();
    
    /** used to adjust each period so that we keep trying to expand the peer's capacity */
    static long GROWTH_FACTOR = 5;
    
    /** the calculator estimates over a 1 hour period */
    private static long ESTIMATE_PERIOD = 60*60*1000;
    
    public static double calc(PeerProfile profile) {
        double capacity;

        if (tooOld(profile)) { 
            capacity = 1;
        } else {
            RateStat acceptStat = profile.getTunnelCreateResponseTime();
            RateStat rejectStat = profile.getTunnelHistory().getRejectionRate();
            RateStat failedStat = profile.getTunnelHistory().getFailedRate();
        
            double capacity10m = estimateCapacity(acceptStat, rejectStat, failedStat, 10*60*1000);
            // if we actively know they're bad, who cares if they used to be good?
            if (capacity10m <= 0) {
                capacity = 0;
            } else {
                double capacity30m = estimateCapacity(acceptStat, rejectStat, failedStat, 30*60*1000);
                double capacity60m = estimateCapacity(acceptStat, rejectStat, failedStat, 60*60*1000);
                double capacity1d  = estimateCapacity(acceptStat, rejectStat, failedStat, 24*60*60*1000);
        
                capacity = capacity10m * periodWeight(10*60*1000) + 
                           capacity30m * periodWeight(30*60*1000) + 
                           capacity60m * periodWeight(60*60*1000) + 
                           capacity1d  * periodWeight(24*60*60*1000);
            }
        }        
        
        // now take into account non-rejection tunnel rejections (which haven't 
        // incremented the rejection counter, since they were only temporary)
        long now = _context.clock().now();
        if (profile.getTunnelHistory().getLastRejectedTransient() > now - 5*60*1000)
            capacity = 1;
        else if (profile.getTunnelHistory().getLastRejectedProbabalistic() > now - 5*60*1000)
            capacity -= _context.random().nextInt(5);
        
        capacity += profile.getCapacityBonus();
        if (capacity < 0)
            capacity = 0;
        
        return capacity;
    }
    
    /**
     * If we haven't heard from them in an hour, they aren't too useful.
     *
     */
    private static boolean tooOld(PeerProfile profile) {
        if (profile.getIsActive(60*60*1000)) 
            return false;
        else 
            return true;
    }
    
    /**
     * Compute a tunnel accept capacity-per-hour for the given period
     * This is perhaps the most critical part of the peer ranking and selection
     * system, so adjust with great care and testing to ensure good network
     * performance and prevent congestion collapse.
     *
     * The baseline or "growth factor" is 5.
     * Rejects will not reduce the baseline. Failures will.
     *
     * @param acceptStat Accept counter (1 = 1 accept)
     * @param rejectStat Reject counter (1 = 1 reject)
     * @param failedStat Failed counter (100 = 1 fail)
     *
     * Let A = accects, R = rejects, F = fails
     * @return estimated and adjusted accepts per hour, for the given period
     *         which is, more or less, max(0, 5 + (A * (A / (A + R))) - (4 * F))
     */
    private static double estimateCapacity(RateStat acceptStat, RateStat rejectStat, RateStat failedStat, int period) {
        Rate curAccepted = acceptStat.getRate(period);
        Rate curRejected = rejectStat.getRate(period);
        Rate curFailed = failedStat.getRate(period);

        long eventCount = 0;
        if (curAccepted != null)
            eventCount = curAccepted.getCurrentEventCount() + curAccepted.getLastEventCount();
        // Punish for rejections.
        // We don't want to simply do eventCount -= rejected or we get to zero with 50% rejection,
        // and we don't want everybody to be at zero during times of congestion.
        if (eventCount > 0) {
            long rejected = curRejected.getCurrentEventCount() + curRejected.getLastEventCount();
            eventCount = eventCount * eventCount / (eventCount + rejected);
        }
        double stretch = ((double)ESTIMATE_PERIOD) / period;
        double val = eventCount * stretch;
        long failed = 0;
        // Let's say a failure is 4 times worse than a rejection.
        // It's actually much worse than that, but with 2-hop tunnels and a 8-peer
        // fast pool, for example, you have a 1/7 chance of being falsely blamed.
        // We also don't want to drive everybody's capacity to zero, that isn't helpful.
        if (curFailed != null)
            failed = (long) (0.5 + (4.0 * (curFailed.getCurrentTotalValue() + curFailed.getLastTotalValue()) / 100.0));
        if (failed > 0) {
            //if ( (period <= 10*60*1000) && (curFailed.getCurrentEventCount() > 0) )
            //    return 0.0d; // their tunnels have failed in the last 0-10 minutes
            //else
            val -= failed * stretch;
        }
        
        val += GROWTH_FACTOR;
        
        if (val >= 0) {
            return val;
        } else {
            return 0.0d;
        }
    }
    
    private static double periodWeight(int period) {
        switch (period) {
            case 10*60*1000: return .4;
            case 30*60*1000: return .3;
            case 60*60*1000: return .2;
            case 24*60*60*1000: return .1;
            default: throw new IllegalArgumentException("wtf, period [" + period + "]???");
        }
    }
}
