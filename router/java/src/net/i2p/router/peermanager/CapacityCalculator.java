package net.i2p.router.peermanager;

import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;

/**
 * Estimate how many of our tunnels the peer can join per hour.
 */
class CapacityCalculator {
    
    public static final String PROP_COUNTRY_BONUS = "profileOrganizer.sameCountryBonus";

    /** used to adjust each period so that we keep trying to expand the peer's capacity */
    static final long GROWTH_FACTOR = 5;
    
    /** the calculator estimates over a 1 hour period */
    private static long ESTIMATE_PERIOD = 60*60*1000;

    // total of all possible bonuses should be less than 4, since
    // crappy peers start at 1 and the base is 5.
    private static final double PENALTY_NEW = 4;
    private static final double BONUS_ESTABLISHED = 0.65;
    private static final double BONUS_SAME_COUNTRY = 0;
    private static final double BONUS_XOR = .25;
    private static final double PENALTY_UNREACHABLE = 2;
    private static final double PENALTY_NO_RI = 2;
    private static final double PENALTY_L_CAP = 1;
    private static final double PENALTY_NO_R_CAP = 1;
    private static final double PENALTY_U_CAP = 2;
    private static final double PENALTY_LAST_SEND_FAIL = 4;
    private static final String PROP_D_CAP = "router.penaltyCapD";
    private static final double PENALTY_CAP_D = 0.5;
    private static final String PROP_E_CAP = "router.penaltyCapE";
    private static final double PENALTY_CAP_E = 0.9;
    // private static final String PROP_G_CAP = "router.gcapPenalty";
    // private static final double PENALTY_G_CAP = 0;
    private static final double PENALTY_RECENT_SEND_FAIL = 4;
    private static final double BONUS_LAST_SEND_SUCCESS = 1;
    private static final double BONUS_RECENT_SEND_SUCCESS = 1;
    // we make this a bonus for non-ff, not a penalty for ff, so we
    // don't drive the ffs below the default
    private static final double BONUS_NON_FLOODFILL = 1.0;
    
    public static double calc(PeerProfile profile) {
        double capacity;
        RouterContext context = profile.getContext();
        long now = context.clock().now();
        TunnelHistory history = profile.getTunnelHistory();
        long down = context.router().getEstimatedDowntime();
        long up = context.router().getUptime();
        boolean enableAgeChecks = (down > 0 && down < 45*60*1000) || up > 60*60*1000;
        if (enableAgeChecks && tooOld(profile, now)) {
            capacity = 1;
        } else {
            RateStat acceptStat = profile.getTunnelCreateResponseTime();
            RateStat rejectStat = history.getRejectionRate();
            RateStat failedStat = history.getFailedRate();
        
            double capacity10m = estimateCapacity(acceptStat, rejectStat, failedStat, 10*60*1000);
            // if we actively know they're bad, who cares if they used to be good?
            if (capacity10m <= 0) {
                capacity = 0;
            } else {
                double capacity60m = estimateCapacity(acceptStat, rejectStat, failedStat, 60*60*1000);
                double capacity1d  = estimateCapacity(acceptStat, rejectStat, failedStat, 24*60*60*1000);

                // now take into account recent tunnel rejections
                long cutoff = now - PeerManager.REORGANIZE_TIME_LONG;
                if (history.getLastRejectedProbabalistic() > cutoff) {
                    capacity10m /= 2;
                } else if (history.getLastRejectedTransient() > cutoff) {
                    // never happens
                    capacity10m /= 4;
                }

                capacity = capacity10m * 0.4 + 
                           capacity60m * 0.5 + 
                           capacity1d  * 0.1;
            }
        }

        // penalize new profiles
        if (enableAgeChecks) {
            long firstHeard = profile.getFirstHeardAbout();
            long ago = now - firstHeard;
            if (ago < 2*60*60*1000)
                capacity -= PENALTY_NEW * (2*60*60*1000 - ago) / 2*60*60*1000;
        }
        // boost connected peers
        if (profile.isEstablished())
            capacity += BONUS_ESTABLISHED;

/*
        // boost same country
        if (profile.isSameCountry()) {
            double bonus = BONUS_SAME_COUNTRY;
            String b = context.getProperty(PROP_COUNTRY_BONUS);
            if (b != null) {
                try {
                    bonus = Double.parseDouble(b);
                } catch (NumberFormatException nfe) {}
            }
            capacity += bonus;
        }
*/

        // penalize unreachable peers
        if (profile.wasUnreachable())
            capacity -= PENALTY_UNREACHABLE;

        // credit non-floodfill to reduce conn limit issues at floodfills
        // TODO only if we aren't floodfill ourselves?
        // null for tests
        NetworkDatabaseFacade ndb =  context.netDb();
        if (ndb != null) {
            RouterInfo ri = (RouterInfo) ndb.lookupLocallyWithoutValidation(profile.getPeer());
            if (ri != null) {
                if (!FloodfillNetworkDatabaseFacade.isFloodfill(ri))
                    capacity += BONUS_NON_FLOODFILL;
                String caps = ri.getCapabilities();
                if (caps.indexOf(Router.CAPABILITY_REACHABLE) < 0)
                    capacity -= PENALTY_NO_R_CAP;
                if (caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0)
                    capacity -= PENALTY_U_CAP;
                if (caps.indexOf(Router.CAPABILITY_BW32) >= 0)
                    capacity -= PENALTY_L_CAP;
                if (caps.indexOf(Router.CAPABILITY_CONGESTION_MODERATE) >= 0) {
                    String dcapPenalty = context.getProperty(PROP_D_CAP);
                    if (dcapPenalty != null) {
                        double dcap = Double.parseDouble(dcapPenalty);
                        capacity -= dcap;
                    } else {
                        capacity -= PENALTY_CAP_D;
                    }
                } else if (caps.indexOf(Router.CAPABILITY_CONGESTION_SEVERE) >= 0){
                    String ecapPenalty = context.getProperty(PROP_E_CAP);
                    if (ecapPenalty != null) {
                        double ecap = Double.parseDouble(ecapPenalty);
                        capacity -= ecap;
                    } else {
                        capacity -= PENALTY_CAP_E;
                    }
                }
            } else {
                capacity -= PENALTY_NO_RI;
            }
        }

        long lastGood = profile.getLastSendSuccessful();
        long lastBad = profile.getLastSendFailed();
        if (lastBad > lastGood) {
            capacity -= PENALTY_LAST_SEND_FAIL;
            if (lastGood > now - 30*60*1000)
                capacity += PENALTY_RECENT_SEND_FAIL;
        } else if (lastGood > 0) {
            capacity += BONUS_LAST_SEND_SUCCESS;
            if (lastGood > now - 30*60*1000)
                capacity += BONUS_RECENT_SEND_SUCCESS;
        }

        // a tiny tweak to break ties and encourage closeness, -.25 to +.25
        capacity -= profile.getXORDistance() * (BONUS_XOR / 128);

        capacity += profile.getCapacityBonus();
        if (capacity < 0)
            capacity = 0;
        
        return capacity;
    }
    
    /**
     * If we haven't heard from them in an hour, they aren't too useful.
     *
     */
    private static boolean tooOld(PeerProfile profile, long now) {
        return !profile.getIsActive(60*60*1000, now);
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
     *         which is, more or less, max(0, 5 + (A * (A / (A + 2R))) - (4 * F))
     */
    private static double estimateCapacity(RateStat acceptStat, RateStat rejectStat, RateStat failedStat, int period) {
        Rate curAccepted = acceptStat.getRate(period);
        Rate curRejected = rejectStat.getRate(period);
        Rate curFailed = failedStat.getRate(period);
        RateAverages ra = RateAverages.getTemp();

        double eventCount = 0;
        if (curAccepted != null) {
            eventCount = curAccepted.computeAverages(ra, false).getTotalEventCount();
            // Punish for rejections.
            // We don't want to simply do eventCount -= rejected or we get to zero with 50% rejection,
            // and we don't want everybody to be at zero during times of congestion.
            if (eventCount > 0 && curRejected != null) {
                long rejected = curRejected.computeAverages(ra,false).getTotalEventCount();
                if (rejected > 0)
                    eventCount *= eventCount / (eventCount + (2 * rejected));
            }
        }

        double stretch = ((double)ESTIMATE_PERIOD) / period;
        double val = eventCount * stretch;

        // Let's say a failure is 4 times worse than a rejection.
        // It's actually much worse than that, but with 2-hop tunnels and a 8-peer
        // fast pool, for example, you have a 1/7 chance of being falsely blamed.
        // We also don't want to drive everybody's capacity to zero, that isn't helpful.
        if (curFailed != null) {
            double failed = curFailed.computeAverages(ra, false).getTotalValues();
            if (failed > 0) {
                //if ( (period <= 10*60*1000) && (curFailed.getCurrentEventCount() > 0) )
                //    return 0.0d; // their tunnels have failed in the last 0-10 minutes
                //else
                // .04 = 4.0 / 100.0 adjustment to failed
                val -= 0.04 * failed * stretch;
            }
        }
        
        val += GROWTH_FACTOR;
        
        if (val >= 0) {
            return val;
        } else {
            return 0.0d;
        }
    }
}
