package net.i2p.router.peermanager;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Quantify how fast the peer is - how fast they respond to our requests, how fast
 * they pass messages on, etc.  This should be affected both by their bandwidth/latency,
 * as well as their load.  The essence of the current algorithm is to determine 
 * approximately how many 2KB messages the peer can pass round trip within a single
 * minute - not based just on itself though, but including the delays of other peers
 * in the tunnels.  As such, more events make it more accurate.
 *
 */
public class SpeedCalculator extends Calculator {
    private Log _log;
    private RouterContext _context;
    
    /** 
     * minimum number of events to use a particular period's data.  If this many 
     * events haven't occurred in the period yet, the next largest period is tried.
     */
    public static final String PROP_EVENT_THRESHOLD = "speedCalculator.eventThreshold";
    public static final int DEFAULT_EVENT_THRESHOLD = 50;
    /** should the calculator use instantaneous rates, or period averages? */
    public static final String PROP_USE_INSTANTANEOUS_RATES = "speedCalculator.useInstantaneousRates";
    public static final boolean DEFAULT_USE_INSTANTANEOUS_RATES = false;
    /** should the calculator use tunnel test time only, or include all data? */
    public static final String PROP_USE_TUNNEL_TEST_ONLY = "speedCalculator.useTunnelTestOnly";
    public static final boolean DEFAULT_USE_TUNNEL_TEST_ONLY = false;
    
    public SpeedCalculator(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(SpeedCalculator.class);
    }
    
    public double calc(PeerProfile profile) {
        long threshold = getEventThreshold();
        boolean tunnelTestOnly = getUseTunnelTestOnly();
        
        long period = 10*60*1000;
        long events = getEventCount(profile, period, tunnelTestOnly);
        if (events < threshold) {
            period = 60*60*1000l;
            events = getEventCount(profile, period, tunnelTestOnly);
            if (events < threshold) {
                period = 24*60*60*1000;
                events = getEventCount(profile, period, tunnelTestOnly);
                if (events < threshold) {
                    period = -1;
                    events = getEventCount(profile, period, tunnelTestOnly);
                }
            }
        }
        
        double measuredRoundTripTime = getMeasuredRoundTripTime(profile, period, tunnelTestOnly);
        double measuredRTPerMinute = 0;
        if (measuredRoundTripTime > 0) 
            measuredRTPerMinute = (60000.0d / measuredRoundTripTime);
        
        double estimatedRTPerMinute = 0;
        double estimatedRoundTripTime = 0;
        if (!tunnelTestOnly) {
            estimatedRoundTripTime = getEstimatedRoundTripTime(profile, period);
            if (estimatedRoundTripTime > 0)
                estimatedRTPerMinute = (60000.0d / estimatedRoundTripTime);
        }

        double estimateFactor = getEstimateFactor(threshold, events);
        double rv = (1-estimateFactor)*measuredRTPerMinute + (estimateFactor)*estimatedRTPerMinute;
        
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("\n\nrv: " + rv + " events: " + events + " threshold: " + threshold + " period: " + period + " useTunnelTestOnly? " + tunnelTestOnly + "\n"
                       + "measuredRTT: " + measuredRoundTripTime + " measured events per minute: " + measuredRTPerMinute + "\n"
                       + "estimateRTT: " + estimatedRoundTripTime + " estimated events per minute: " + estimatedRTPerMinute + "\n" 
                       + "estimateFactor: " + estimateFactor + "\n"
                       + "for peer: " + profile.getPeer().toBase64());
        }
   
        rv += profile.getSpeedBonus();
        return rv;
    }

    /**
     * How much do we want to prefer the measured values more than the estimated 
     * values, as a fraction.  The value 1 means ignore the measured values, while
     * the value 0 means ignore the estimate, and everything inbetween means, well
     * everything inbetween.
     *
     */
    private double getEstimateFactor(long eventThreshold, long numEvents) {
        if (true) return 0.0d; // never use the estimate
        if (numEvents > eventThreshold) 
            return 0.0d;
        else
            return numEvents / eventThreshold;
    }
    
    /**
     * How many measured events do we have for the given period?  If the period is negative,
     * return the lifetime events.
     *
     */
    private long getEventCount(PeerProfile profile, long period, boolean tunnelTestOnly) {
        if (period < 0) {
            Rate dbResponseRate = profile.getDbResponseTime().getRate(60*60*1000l);
            Rate tunnelResponseRate = profile.getTunnelCreateResponseTime().getRate(60*60*1000l);
            Rate tunnelTestRate = profile.getTunnelTestResponseTime().getRate(60*60*1000l);
            
            long dbResponses = tunnelTestOnly ? 0 : dbResponseRate.getLifetimeEventCount();
            long tunnelResponses = tunnelTestOnly ? 0 : tunnelResponseRate.getLifetimeEventCount();
            long tunnelTests = tunnelTestRate.getLifetimeEventCount();

            return dbResponses + tunnelResponses + tunnelTests;
        } else {
            Rate dbResponseRate = profile.getDbResponseTime().getRate(period);
            Rate tunnelResponseRate = profile.getTunnelCreateResponseTime().getRate(period);
            Rate tunnelTestRate = profile.getTunnelTestResponseTime().getRate(period);

            long dbResponses = tunnelTestOnly ? 0 : dbResponseRate.getCurrentEventCount() + dbResponseRate.getLastEventCount();
            long tunnelResponses = tunnelTestOnly ? 0 : tunnelResponseRate.getCurrentEventCount() + tunnelResponseRate.getLastEventCount();
            long tunnelTests = tunnelTestRate.getCurrentEventCount() + tunnelTestRate.getLastEventCount();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("TunnelTests for period " + period + ": " + tunnelTests +
                           " last: " + tunnelTestRate.getLastEventCount() + " lifetime: " +
                           tunnelTestRate.getLifetimeEventCount());

            return dbResponses + tunnelResponses + tunnelTests;
        }
    }
    
    /**
     * Retrieve the average measured round trip time within the period specified (including 
     * db responses, tunnel create responses, and tunnel tests).  If the period is negative, 
     * it uses the lifetime stats.  In addition, it weights each of those three measurements
     * equally according to their event count (e.g. 4 dbResponses @ 10 seconds and 1 tunnel test
     * at 5 seconds will leave the average at 9 seconds)
     *
     */
    private double getMeasuredRoundTripTime(PeerProfile profile, long period, boolean tunnelTestOnly) {
        double activityTime = 0;
        double rtt = 0;
        double dbResponseTime = 0;
        double tunnelResponseTime = 0;
        double tunnelTestTime = 0;

        long dbResponses = 0;
        long tunnelResponses = 0;
        long tunnelTests = 0;

        long events = 0;
        
        if (period < 0) { 
            Rate dbResponseRate = profile.getDbResponseTime().getRate(60*60*1000l);
            Rate tunnelResponseRate = profile.getTunnelCreateResponseTime().getRate(60*60*1000l);
            Rate tunnelTestRate = profile.getTunnelTestResponseTime().getRate(60*60*1000l);

            dbResponses = tunnelTestOnly ? 0 : dbResponseRate.getLifetimeEventCount();
            tunnelResponses = tunnelTestOnly ? 0 : tunnelResponseRate.getLifetimeEventCount();
            tunnelTests = tunnelTestRate.getLifetimeEventCount();

            dbResponseTime = tunnelTestOnly ? 0 : dbResponseRate.getLifetimeAverageValue();
            tunnelResponseTime = tunnelTestOnly ? 0 : tunnelResponseRate.getLifetimeAverageValue();
            tunnelTestTime = tunnelTestRate.getLifetimeAverageValue();

            events = dbResponses + tunnelResponses + tunnelTests;
            if (events <= 0) return 0;
            activityTime = (dbResponses*dbResponseTime + tunnelResponses*tunnelResponseTime + tunnelTests*tunnelTestTime);
            rtt = activityTime / events;
        } else {
            Rate dbResponseRate = profile.getDbResponseTime().getRate(period);
            Rate tunnelResponseRate = profile.getTunnelCreateResponseTime().getRate(period);
            Rate tunnelTestRate = profile.getTunnelTestResponseTime().getRate(period);

            dbResponses = tunnelTestOnly ? 0 : dbResponseRate.getCurrentEventCount() + dbResponseRate.getLastEventCount();
            tunnelResponses = tunnelTestOnly ? 0 : tunnelResponseRate.getCurrentEventCount() + tunnelResponseRate.getLastEventCount();
            tunnelTests = tunnelTestRate.getCurrentEventCount() + tunnelTestRate.getLastEventCount();

            if (!tunnelTestOnly) {
                dbResponseTime = avg(dbResponseRate);
                tunnelResponseTime = avg(tunnelResponseRate);
            }
            tunnelTestTime = avg(tunnelTestRate);

            events = dbResponses + tunnelResponses + tunnelTests;
            if (events <= 0) return 0;
            activityTime = (dbResponses*dbResponseTime + tunnelResponses*tunnelResponseTime + tunnelTests*tunnelTestTime);
            rtt = activityTime / events;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("\nMeasured response time for " + profile.getPeer().toBase64() + " over " 
                       + DataHelper.formatDuration(period) + " with activityTime of " + activityTime
                       + ": " + rtt + "\nover " + events + " events (" 
                       + dbResponses + " dbResponses, " + tunnelResponses + " tunnelResponses, " 
                       + tunnelTests + " tunnelTests)\ntimes (" 
                       + dbResponseTime + "ms, " + tunnelResponseTime + "ms, " 
                       + tunnelTestTime + "ms respectively)");
        return rtt;
    }
    
    private double avg(Rate rate) {
        long events = rate.getCurrentEventCount() + rate.getLastEventCount();
        long time = rate.getCurrentTotalEventTime() + rate.getLastTotalEventTime();
        if ( (events > 0) && (time > 0) ) 
            return time / events;
        else
            return 0.0d;
    }
    
    private double getEstimatedRoundTripTime(PeerProfile profile, long period) {
        double estSendTime = getEstimatedSendTime(profile, period);
        double estRecvTime = getEstimatedReceiveTime(profile, period);
        return estSendTime + estRecvTime;
    }

    private double getEstimatedSendTime(PeerProfile profile, long period) {
        double bps = calcRate(profile.getSendSuccessSize(), period);
        if (bps <= 0) 
            return 0.0d;
        else
            return 2048.0d / bps;
    }
    private double getEstimatedReceiveTime(PeerProfile profile, long period) {
        double bps = calcRate(profile.getReceiveSize(), period);
        if (bps <= 0) 
            return 0.0d;
        else
            return 2048.0d / bps;
    }
    
    private double calcRate(RateStat stat, long period) {
        Rate rate = stat.getRate(period);
        if (rate == null) return 0.0d;
        return calcRate(rate, period);
    }
    
    private double calcRate(Rate rate, long period) {
        long events = rate.getCurrentEventCount();
        if (events >= 1) {
            double ms = rate.getCurrentTotalEventTime();
            double bytes = rate.getCurrentTotalValue();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("calculating rate: ms=" + ((int)ms) + " bytes=" + ((int)bytes));
            if ( (bytes > 0) && (ms > 0) ) {
                if (getUseInstantaneousRates()) {
                    return (bytes * 1000.0d) / ms;
                } else {
                    // period average
                    return (bytes * 1000.0d) / period; 
                }
            }
        }
        return 0.0d;
    }
    /**
     * What is the minimum number of measured events we want in a period before 
     * trusting the values?  This first checks the router's configuration, then
     * the context, and then finally falls back on a static default (100).
     *
     */
    private long getEventThreshold() {
        if (_context.router() != null) {
            String threshold = _context.router().getConfigSetting(PROP_EVENT_THRESHOLD);
            if (threshold != null) {
                try {
                    return Long.parseLong(threshold);
                } catch (NumberFormatException nfe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Event threshold for speed improperly set in the router config [" + threshold + "]", nfe);
                }
            }
        }
        String threshold = _context.getProperty(PROP_EVENT_THRESHOLD, ""+DEFAULT_EVENT_THRESHOLD);
        if (threshold != null) {
            try {
                return Long.parseLong(threshold);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Event threshold for speed improperly set in the router environment [" + threshold + "]", nfe);
            }
        }
        return DEFAULT_EVENT_THRESHOLD;
    }
    
    
    /**
     * Should we use instantaneous rates for the estimated speed, or the period rates? 
     * This first checks the router's configuration, then the context, and then 
     * finally falls back on a static default (true).
     *
     * @return true if we should use instantaneous rates, false if we should use period averages
     */
    private boolean getUseInstantaneousRates() {
        if (_context.router() != null) {
            String val = _context.router().getConfigSetting(PROP_USE_INSTANTANEOUS_RATES);
            if (val != null) {
                return Boolean.valueOf(val).booleanValue();
            }
        }
        String val = _context.getProperty(PROP_USE_INSTANTANEOUS_RATES, ""+DEFAULT_USE_INSTANTANEOUS_RATES);
        if (val != null) {
            return Boolean.valueOf(val).booleanValue();
        }
        return DEFAULT_USE_INSTANTANEOUS_RATES;
    }
    
    /**
     * Should we only use the measured tunnel testing time, or should we include 
     * measurements on the db responses and tunnel create responses.  This first 
     * checks the router's configuration, then the context, and then finally falls 
     * back on a static default (true).
     *
     * @return true if we should use tunnel test time only, false if we should use all available
     */
    private boolean getUseTunnelTestOnly() {
        if (_context.router() != null) {
            String val = _context.router().getConfigSetting(PROP_USE_TUNNEL_TEST_ONLY);
            if (val != null) {
                try {
                    boolean rv = Boolean.getBoolean(val);
                    if (_log.shouldLog(Log.DEBUG)) 
                        _log.debug("router config said " + PROP_USE_TUNNEL_TEST_ONLY + '=' + val);
                    return rv;
                } catch (NumberFormatException nfe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Tunnel test only for speed improperly set in the router config [" + val + "]", nfe);
                }
            }
        }
        String val = _context.getProperty(PROP_USE_TUNNEL_TEST_ONLY, ""+DEFAULT_USE_TUNNEL_TEST_ONLY);
        if (val != null) {
            try {
                boolean rv = Boolean.getBoolean(val);
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("router context said " + PROP_USE_TUNNEL_TEST_ONLY + '=' + val);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Tunnel test only for speed improperly set in the router environment [" + val + "]", nfe);
            }
        }
        
        if (_log.shouldLog(Log.DEBUG)) 
            _log.debug("no config for " + PROP_USE_TUNNEL_TEST_ONLY + ", using " + DEFAULT_USE_TUNNEL_TEST_ONLY);
        return DEFAULT_USE_TUNNEL_TEST_ONLY;
    }
}
