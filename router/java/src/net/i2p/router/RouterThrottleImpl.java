package net.i2p.router;

import net.i2p.data.Hash;
import net.i2p.data.i2np.TunnelCreateMessage;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Simple throttle that basically stops accepting messages or nontrivial 
 * requests if the jobQueue lag is too large.  
 *
 */
class RouterThrottleImpl implements RouterThrottle {
    private RouterContext _context;
    private Log _log;
    
    /** 
     * arbitrary hard limit of 2 seconds - if its taking this long to get 
     * to a job, we're congested.
     *
     */
    private static int JOB_LAG_LIMIT = 2000;
    /**
     * Arbitrary hard limit - if we throttle our network connection this many
     * times in the previous 10-20 minute period, don't accept requests to 
     * participate in tunnels.
     *
     */
    private static int THROTTLE_EVENT_LIMIT = 300;
    
    private static final String PROP_MAX_TUNNELS = "router.maxParticipatingTunnels";
    private static final String PROP_DEFAULT_KBPS_THROTTLE = "router.defaultKBpsThrottle";
    private static final String PROP_BANDWIDTH_SHARE_PERCENTAGE = "router.sharePercentage";
    
    /** tunnel acceptance */
    public static final int TUNNEL_ACCEPT = 0;
    
    public RouterThrottleImpl(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(RouterThrottleImpl.class);
        _context.statManager().createRateStat("router.throttleNetworkCause", "How lagged the jobQueue was when an I2NP was throttled", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleNetDbCause", "How lagged the jobQueue was when a networkDb request was throttled", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelCause", "How lagged the jobQueue was when a tunnel request was throttled", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("tunnel.bytesAllocatedAtAccept", "How many bytes had been 'allocated' for participating tunnels when we accepted a request?", "Tunnels", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelProcessingTime1m", "How long it takes to process a message (1 minute average) when we throttle a tunnel?", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelProcessingTime10m", "How long it takes to process a message (10 minute average) when we throttle a tunnel?", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelMaxExceeded", "How many tunnels we are participating in when we refuse one due to excees?", "Throttle", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelProbTooFast", "How many tunnels beyond the previous 1h average are we participating in when we throttle?", "Throttle", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelProbTestSlow", "How slow are our tunnel tests when our average exceeds the old average and we throttle?", "Throttle", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelBandwidthExceeded", "How much bandwidth is allocated when we refuse due to bandwidth allocation?", "Throttle", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
    }
    
    public boolean acceptNetworkMessage() {
        long lag = _context.jobQueue().getMaxLag();
        if ( (lag > JOB_LAG_LIMIT) && (_context.router().getUptime() > 60*1000) ) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Throttling network reader, as the job lag is " + lag);
            _context.statManager().addRateData("router.throttleNetworkCause", lag, lag);
            return false;
        } else {
            return true;
        }
    }
    
    public boolean acceptNetDbLookupRequest(Hash key) { 
        long lag = _context.jobQueue().getMaxLag();
        if (lag > JOB_LAG_LIMIT) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Refusing netDb request, as the job lag is " + lag);
            _context.statManager().addRateData("router.throttleNetDbCause", lag, lag);
            return false;
        } else {
            return true;
        } 
    }
    
    public int acceptTunnelRequest(TunnelCreateMessage msg) { 
        long lag = _context.jobQueue().getMaxLag();
        RateStat rs = _context.statManager().getRate("router.throttleNetworkCause");
        Rate r = null;
        if (rs != null)
            r = rs.getRate(10*60*1000);
        long throttleEvents = (r != null ? r.getCurrentEventCount() + r.getLastEventCount() : 0);
        if (throttleEvents > THROTTLE_EVENT_LIMIT) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Refusing tunnel request with the job lag of " + lag 
                           + " since there have been " + throttleEvents 
                           + " throttle events in the last 15 minutes or so");
            _context.statManager().addRateData("router.throttleTunnelCause", lag, lag);
            return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
        }
        
        rs = _context.statManager().getRate("transport.sendProcessingTime");
        r = null;
        if (rs != null)
            r = rs.getRate(10*60*1000);
        double processTime = (r != null ? r.getAverageValue() : 0);
        if (processTime > 1000) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Refusing tunnel request with the job lag of " + lag 
                           + "since the 10 minute message processing time is too slow (" + processTime + ")");
            _context.statManager().addRateData("router.throttleTunnelProcessingTime10m", (long)processTime, (long)processTime);
            return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
        }
        if (rs != null)
            r = rs.getRate(60*1000);
        processTime = (r != null ? r.getAverageValue() : 0);
        if (processTime > 2000) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Refusing tunnel request with the job lag of " + lag 
                           + "since the 1 minute message processing time is too slow (" + processTime + ")");
            _context.statManager().addRateData("router.throttleTunnelProcessingTime1m", (long)processTime, (long)processTime);
            return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
        }
        
        int numTunnels = _context.tunnelManager().getParticipatingCount();

        if (_context.getProperty(Router.PROP_SHUTDOWN_IN_PROGRESS) != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Refusing tunnel request since we are shutting down ASAP");
            return TunnelHistory.TUNNEL_REJECT_CRIT;
        }
        
        if (numTunnels > getMinThrottleTunnels()) {
            double growthFactor = getTunnelGrowthFactor();
            Rate avgTunnels = _context.statManager().getRate("tunnel.participatingTunnels").getRate(60*60*1000);
            if (avgTunnels != null) {
                double avg = 0;
                if (avgTunnels.getLastEventCount() > 0) 
                    avg = avgTunnels.getAverageValue();
                else
                    avg = avgTunnels.getLifetimeAverageValue();
                int min = getMinThrottleTunnels();
                if (avg < min)
                    avg = min;
                if ( (avg > 0) && (avg*growthFactor < numTunnels) ) {
                    // we're accelerating, lets try not to take on too much too fast
                    double probAccept = (avg*growthFactor) / numTunnels;
                    int v = _context.random().nextInt(100);
                    if (v < probAccept*100) {
                        // ok
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Probabalistically accept tunnel request (p=" + probAccept 
                                      + " v=" + v + " avg=" + avg + " current=" + numTunnels + ")");
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Probabalistically refusing tunnel request (avg=" + avg
                                      + " current=" + numTunnels + ")");
                        _context.statManager().addRateData("router.throttleTunnelProbTooFast", (long)(numTunnels-avg), 0);
                        return TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
                    }
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Accepting tunnel request, since the average is " + avg
                                      + " and we only have " + numTunnels + ")");
                }
            }
            
            Rate tunnelTestTime10m = _context.statManager().getRate("tunnel.testSuccessTime").getRate(10*60*1000);
            Rate tunnelTestTime60m = _context.statManager().getRate("tunnel.testSuccessTime").getRate(60*60*1000);
            if ( (tunnelTestTime10m != null) && (tunnelTestTime60m != null) && (tunnelTestTime10m.getLastEventCount() > 0) ) {
                double avg10m = tunnelTestTime10m.getAverageValue();
                double avg60m = 0;
                if (tunnelTestTime60m.getLastEventCount() > 0)
                    avg60m = tunnelTestTime60m.getAverageValue();
                else
                    avg60m = tunnelTestTime60m.getLifetimeAverageValue();
                
                if (avg60m < 2000)
                    avg60m = 2000; // minimum before complaining
                
                if ( (avg60m > 0) && (avg10m > avg60m * growthFactor) ) {
                    double probAccept = (avg60m*growthFactor)/avg10m;
                    int v = _context.random().nextInt(100);
                    if (v < probAccept*100) {
                        // ok
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Probabalistically accept tunnel request (p=" + probAccept 
                                      + " v=" + v + " test time avg 10m=" + avg10m + " 60m=" + avg60m + ")");
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Probabalistically refusing tunnel request (test time avg 10m=" + avg10m
                                      + " 60m=" + avg60m + ")");
                        _context.statManager().addRateData("router.throttleTunnelProbTestSlow", (long)(avg10m-avg60m), 0);
                        return TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
                    }
                }
            }
        }
        
        String maxTunnels = _context.getProperty(PROP_MAX_TUNNELS);
        if (maxTunnels != null) {
            try {
                int max = Integer.parseInt(maxTunnels);
                if (numTunnels >= max) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Refusing tunnel request since we are already participating in " 
                                  + numTunnels + " (our max is " + max + ")");
                    _context.statManager().addRateData("router.throttleTunnelMaxExceeded", numTunnels, 0);
                    return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                }
            } catch (NumberFormatException nfe) {
                // no default, ignore it
            }
        }

        // ok, we're not hosed, but can we handle the bandwidth requirements 
        // of another tunnel?
        rs = _context.statManager().getRate("tunnel.participatingMessageCount");
        r = null;
        if (rs != null)
            r = rs.getRate(10*60*1000);
        double bytesAllocated = (r != null ? r.getCurrentTotalValue() * 1024 : 0);
        
        if (!allowTunnel(bytesAllocated, numTunnels)) {
            _context.statManager().addRateData("router.throttleTunnelBandwidthExceeded", (long)bytesAllocated, 0);
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }
        _context.statManager().addRateData("tunnel.bytesAllocatedAtAccept", (long)bytesAllocated, msg.getDurationSeconds()*1000);
        

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Accepting a new tunnel request (now allocating " + bytesAllocated + " bytes across " + numTunnels 
                       + " tunnels with lag of " + lag + " and " + throttleEvents + " throttle events)");
        return TUNNEL_ACCEPT;
    }
    
    /**
     * with bytesAllocated already accounted for across the numTunnels existing
     * tunnels we have agreed to, can we handle another tunnel with our existing
     * bandwidth?
     *
     */
    private boolean allowTunnel(double bytesAllocated, int numTunnels) {
        long bytesAllowed = getBytesAllowed();
        
        bytesAllowed *= getSharePercentage();
        
        double bytesPerTunnel = (numTunnels > 0 ? bytesAllocated / numTunnels : 0);
        double toAllocate = (numTunnels > 0 ? bytesPerTunnel * (numTunnels + 1) : 0);
        
        double pctFull = toAllocate / bytesAllowed;
        
        double allocatedKBps = toAllocate / (10 * 60 * 1024);
        
        if (pctFull < 1.0) { // (_context.random().nextInt(100) > 100 * pctFull) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Probabalistically allowing the tunnel w/ " + pctFull + " of our " + bytesAllowed
                           + "bytes/" + allocatedKBps + "KBps allocated through " + numTunnels + " tunnels");
            return true;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Rejecting the tunnel w/ " + pctFull + " of our " + bytesAllowed 
                           + "bytes allowed (" + toAllocate + "bytes / " + allocatedKBps 
                           + "KBps) through " + numTunnels + " tunnels");
            return false;
        }
    }
    
    /** 
     * What fraction of the bandwidth specified in our bandwidth limits should
     * we allow to be consumed by participating tunnels?
     *
     */
    private double getSharePercentage() {
        String pct = _context.getProperty(PROP_BANDWIDTH_SHARE_PERCENTAGE, "0.8");
        if (pct != null) {
            try {
                return Double.parseDouble(pct);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Unable to get the share percentage");
            }
        }
        return 0.8;
    }
        
    /** 
     * BytesPerSecond that we can pass along data
     */
    private long getBytesAllowed() {
        String kbpsOutStr = _context.getProperty("i2np.bandwidth.outboundKBytesPerSecond");
        long kbpsOut = -1;
        if (kbpsOutStr != null) {
            try {
                kbpsOut = Integer.parseInt(kbpsOutStr);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Unable to get the bytes allowed (outbound)");
            }
        }
        
        String kbpsInStr = _context.getProperty("i2np.bandwidth.inboundKBytesPerSecond");
        long kbpsIn = -1;
        if (kbpsInStr != null) {
            try {
                kbpsIn = Integer.parseInt(kbpsInStr);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Unable to get the bytes allowed (inbound)");
            }
        }
        
        // whats our choke?
        long kbps = (kbpsOut > kbpsIn ? kbpsIn : kbpsOut);
        
        if (kbps <= 0) {
            try {
                kbps = Integer.parseInt(_context.getProperty(PROP_DEFAULT_KBPS_THROTTLE, "64")); // absurd
            } catch (NumberFormatException nfe) {
                kbps = 64;
            }
        }
        
        return kbps
               * 60 // per minute
               * 10 // per 10 minute period
               * 1024; // bytes;
    }
    
    /** dont ever probabalistically throttle tunnels if we have less than this many */
    private int getMinThrottleTunnels() { 
        try {
            return Integer.parseInt(_context.getProperty("router.minThrottleTunnels", "40"));
        } catch (NumberFormatException nfe) {
            return 40;
        }
    }
    
    private double getTunnelGrowthFactor() {
        try {
            return Double.parseDouble(_context.getProperty("router.tunnelGrowthFactor", "3.0"));
        } catch (NumberFormatException nfe) {
            return 3.0;
        }
    }
    
    public long getMessageDelay() {
        Rate delayRate = _context.statManager().getRate("transport.sendProcessingTime").getRate(60*1000);
        return (long)delayRate.getAverageValue();
    }
    
    public long getTunnelLag() {
        Rate lagRate = _context.statManager().getRate("tunnel.testSuccessTime").getRate(10*60*1000);
        return (long)lagRate.getAverageValue();
    }
    
    public double getInboundRateDelta() {
        RateStat receiveRate = _context.statManager().getRate("transport.sendMessageSize");
        double nowBps = getBps(receiveRate.getRate(60*1000));
        double fiveMinBps = getBps(receiveRate.getRate(5*60*1000));
        double hourBps = getBps(receiveRate.getRate(60*60*1000));
        double dailyBps = getBps(receiveRate.getRate(24*60*60*1000));
        
        if (nowBps < 0) return 0;
        if (dailyBps > 0) return nowBps - dailyBps;
        if (hourBps > 0) return nowBps - hourBps;
        if (fiveMinBps > 0) return nowBps - fiveMinBps;
        return 0;
    }
    private double getBps(Rate rate) {
        if (rate == null) return -1;
        double bytes = rate.getLastTotalValue();
        return (bytes*1000.0d)/rate.getPeriod(); 
    }
    
    protected RouterContext getContext() { return _context; }
}
