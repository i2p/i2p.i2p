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
     * arbitrary hard limit of 10 seconds - if its taking this long to get 
     * to a job, we're congested.
     *
     */
    private static int JOB_LAG_LIMIT = 2*1000;
    /**
     * Arbitrary hard limit - if we throttle our network connection this many
     * times in the previous 2 minute period, don't accept requests to 
     * participate in tunnels.
     *
     */
    private static int THROTTLE_EVENT_LIMIT = 30;
    
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
        _context.statManager().createRateStat("router.throttleTunnelBytesAllowed", "How many bytes are allowed to be sent when we get a tunnel request (period is how many are currently allocated)?", "Throttle", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelBytesUsed", "Used Bps at request (period = max KBps)?", "Throttle", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelFailCount1m", "How many messages failed to be sent in the last 2 minutes when we throttle based on a spike in failures (period = 10 minute average failure count)?", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000});
    }
    
    public boolean acceptNetworkMessage() {
        //if (true) return true;
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
    
    public int acceptTunnelRequest() { 
        if (_context.getProperty(Router.PROP_SHUTDOWN_IN_PROGRESS) != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Refusing tunnel request since we are shutting down ASAP");
            return TunnelHistory.TUNNEL_REJECT_CRIT;
        }
        
        long lag = _context.jobQueue().getMaxLag();
        RateStat rs = _context.statManager().getRate("transport.sendProcessingTime");
        Rate r = null;
        if (rs != null)
            r = rs.getRate(60*1000);
        double processTime = (r != null ? r.getAverageValue() : 0);
        if (processTime > 5000) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Refusing tunnel request with the job lag of " + lag 
                           + "since the 1 minute message processing time is too slow (" + processTime + ")");
            _context.statManager().addRateData("router.throttleTunnelProcessingTime1m", (long)processTime, (long)processTime);
            return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
        }
        
        int numTunnels = _context.tunnelManager().getParticipatingCount();

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
        double messagesPerTunnel = (r != null ? r.getAverageValue() : 0d);
        if (messagesPerTunnel < DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE)
            messagesPerTunnel = DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE;
        int participatingTunnels = _context.tunnelManager().getParticipatingCount();
        double bytesAllocated = messagesPerTunnel * participatingTunnels * 1024;
        
        if (!allowTunnel(bytesAllocated, numTunnels)) {
            _context.statManager().addRateData("router.throttleTunnelBandwidthExceeded", (long)bytesAllocated, 0);
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }
        _context.statManager().addRateData("tunnel.bytesAllocatedAtAccept", (long)bytesAllocated, 60*10*1000);
        

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Accepting a new tunnel request (now allocating " + bytesAllocated + " bytes across " + numTunnels 
                       + " tunnels with lag of " + lag + ")");
        return TUNNEL_ACCEPT;
    }

    private int get1sRate() {
        return (int)Math.max(_context.bandwidthLimiter().getSendBps(), _context.bandwidthLimiter().getReceiveBps());
    }
    private int get1mRate() {
        int send = 0;
        RateStat rs = _context.statManager().getRate("bw.sendRate");
        if (rs != null)
            send = (int)rs.getRate(1*60*1000).getAverageValue();
        int recv = 0;
        rs = _context.statManager().getRate("bw.recvRate");
        if (rs != null)
            recv = (int)rs.getRate(1*60*1000).getAverageValue();
        return Math.max(send, recv);
    }
    private int get5mRate() {
        int send = 0;
        RateStat rs = _context.statManager().getRate("bw.sendRate");
        if (rs != null)
            send = (int)rs.getRate(5*60*1000).getAverageValue();
        int recv = 0;
        rs = _context.statManager().getRate("bw.recvRate");
        if (rs != null)
            recv = (int)rs.getRate(5*60*1000).getAverageValue();
        return Math.max(send, recv);
    }
    
    private static final int DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE = 600; // 1KBps
    
    /**
     * with bytesAllocated already accounted for across the numTunnels existing
     * tunnels we have agreed to, can we handle another tunnel with our existing
     * bandwidth?
     *
     */
    private boolean allowTunnel(double bytesAllocated, int numTunnels) {
        int maxKBps = Math.min(_context.bandwidthLimiter().getOutboundKBytesPerSecond(), _context.bandwidthLimiter().getInboundKBytesPerSecond());
        int used1s = get1sRate();
        int used1m = get1mRate();
        int used5m = get5mRate();
        int used = Math.max(Math.max(used1s, used1m), used5m);
        int availBps = (int)(((maxKBps*1024) - used) * getSharePercentage());

        _context.statManager().addRateData("router.throttleTunnelBytesUsed", used, maxKBps);
        _context.statManager().addRateData("router.throttleTunnelBytesAllowed", availBps, (long)bytesAllocated);

        if (availBps <= 8*1024) {
            // lets be more conservative for people near their limit and assume 1KBps per tunnel
            return ( (numTunnels + 1)*1024 < availBps);
        }

        double growthFactor = ((double)(numTunnels+1))/(double)numTunnels;
        double toAllocate = (numTunnels > 0 ? bytesAllocated * growthFactor : 0);
        
        double allocatedKBps = toAllocate / (10 * 60 * 1024);
        double pctFull = allocatedKBps / availBps;
        
        if ( (pctFull < 1.0) && (pctFull >= 0.0) ) { // (_context.random().nextInt(100) > 100 * pctFull) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Probabalistically allowing the tunnel w/ " + pctFull + " of our " + availBps
                           + "Bps/" + allocatedKBps + "KBps allocated through " + numTunnels + " tunnels");
            return true;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rejecting the tunnel w/ " + pctFull + " of our " + availBps 
                           + "Bps allowed (" + toAllocate + "bytes / " + allocatedKBps 
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
                double d = Double.parseDouble(pct);
                if (d > 1)
                    return d/100d; // *cough* sometimes its 80 instead of .8 (!stab jrandom)
                else
                    return d;
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Unable to get the share percentage");
            }
        }
        return 0.8;
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
