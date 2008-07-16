package net.i2p.router;

import net.i2p.data.Hash;
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
    private String _tunnelStatus;
    
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
    private static final String DEFAULT_MAX_TUNNELS = "3000";  // Unless share BW > 300KBps, BW limit will kick in first
    private static final String PROP_DEFAULT_KBPS_THROTTLE = "router.defaultKBpsThrottle";

    /** tunnel acceptance */
    public static final int TUNNEL_ACCEPT = 0;
    
    public RouterThrottleImpl(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(RouterThrottleImpl.class);
        setTunnelStatus();
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
        _context.statManager().createRateStat("router.throttleTunnelQueueOverload", "How many pending tunnel request messages have we received when we reject them due to overload (period = time to process each)?", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000});
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
            setTunnelStatus("Rejecting tunnels: Shutting down");
            return TunnelHistory.TUNNEL_REJECT_CRIT;
        }
        
        // Don't use CRIT because we don't want peers to think we're failing
        if (_context.router().getUptime() < 20*60*1000)
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;

        long lag = _context.jobQueue().getMaxLag();
// reject here if lag too high???
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
            setTunnelStatus("Rejecting tunnels: High message delay");
            return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
        }
        
        int numTunnels = _context.tunnelManager().getParticipatingCount();

        if (numTunnels > getMinThrottleTunnels()) {
            double tunnelGrowthFactor = getTunnelGrowthFactor();
            Rate avgTunnels = _context.statManager().getRate("tunnel.participatingTunnels").getRate(10*60*1000);
            if (avgTunnels != null) {
                double avg = 0;
                if (avgTunnels.getLastEventCount() > 0) 
                    avg = avgTunnels.getAverageValue();
                else
                    avg = avgTunnels.getLifetimeAverageValue();
                int min = getMinThrottleTunnels();
                if (avg < min)
                    avg = min;
                if ( (avg > 0) && (avg*tunnelGrowthFactor < numTunnels) ) {
                    // we're accelerating, lets try not to take on too much too fast
                    double probAccept = (avg*tunnelGrowthFactor) / numTunnels;
                    probAccept = probAccept * probAccept; // square the decelerator for tunnel counts
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
                        setTunnelStatus("Rejecting " + ((int) probAccept*100) + "% of tunnels: High number of requests");
                        return TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
                    }
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Accepting tunnel request, since the tunnel count average is " + avg
                                      + " and we only have " + numTunnels + ")");
                }
            }
        }
        
        double tunnelTestTimeGrowthFactor = getTunnelTestTimeGrowthFactor();
        Rate tunnelTestTime1m = _context.statManager().getRate("tunnel.testSuccessTime").getRate(1*60*1000);
        Rate tunnelTestTime10m = _context.statManager().getRate("tunnel.testSuccessTime").getRate(10*60*1000);
        if ( (tunnelTestTime1m != null) && (tunnelTestTime10m != null) && (tunnelTestTime1m.getLastEventCount() > 0) ) {
            double avg1m = tunnelTestTime1m.getAverageValue();
            double avg10m = 0;
            if (tunnelTestTime10m.getLastEventCount() > 0)
                avg10m = tunnelTestTime10m.getAverageValue();
            else
                avg10m = tunnelTestTime10m.getLifetimeAverageValue();

            if (avg10m < 5000)
                avg10m = 5000; // minimum before complaining

            if ( (avg10m > 0) && (avg1m > avg10m * tunnelTestTimeGrowthFactor) ) {
                double probAccept = (avg10m*tunnelTestTimeGrowthFactor)/avg1m;
                probAccept = probAccept * probAccept; // square the decelerator for test times
                int v = _context.random().nextInt(100);
                if (v < probAccept*100) {
                    // ok
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Probabalistically accept tunnel request (p=" + probAccept 
                                  + " v=" + v + " test time avg 1m=" + avg1m + " 10m=" + avg10m + ")");
                } else if (false) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Probabalistically refusing tunnel request (test time avg 1m=" + avg1m
                                  + " 10m=" + avg10m + ")");
                    _context.statManager().addRateData("router.throttleTunnelProbTestSlow", (long)(avg1m-avg10m), 0);
                    setTunnelStatus("Rejecting " + ((int) probAccept*100) + "% of tunnels: High test time");
                    return TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
                }
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Accepting tunnel request, since 60m test time average is " + avg10m
                              + " and past 1m only has " + avg1m + ")");
            }
        }
        
        String maxTunnels = _context.getProperty(PROP_MAX_TUNNELS, DEFAULT_MAX_TUNNELS);
        if (maxTunnels != null) {
            try {
                int max = Integer.parseInt(maxTunnels);
                if (numTunnels >= max) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Refusing tunnel request since we are already participating in " 
                                  + numTunnels + " (our max is " + max + ")");
                    _context.statManager().addRateData("router.throttleTunnelMaxExceeded", numTunnels, 0);
                    setTunnelStatus("Rejecting tunnels: Limit reached");
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
        double messagesPerTunnel = DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE;
        if (rs != null) {
            r = rs.getRate(10*60*1000);
            if (r != null) {
                if (r.getLastEventCount() > 0)
                    messagesPerTunnel = r.getAverageValue();
                else
                    messagesPerTunnel = r.getLifetimeAverageValue();
            }
        }
        if (messagesPerTunnel < DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE)
            messagesPerTunnel = DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE;

        double bytesAllocated = messagesPerTunnel * numTunnels * net.i2p.router.tunnel.TrivialPreprocessor.PREPROCESSED_SIZE;
        
        if (!allowTunnel(bytesAllocated, numTunnels)) {
            _context.statManager().addRateData("router.throttleTunnelBandwidthExceeded", (long)bytesAllocated, 0);
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }
        
/***
        int queuedRequests = _context.tunnelManager().getInboundBuildQueueSize();
        int timePerRequest = 1000;
        rs = _context.statManager().getRate("tunnel.decryptRequestTime");
        if (rs != null) {
            r = rs.getRate(60*1000);
            if (r.getLastEventCount() > 0)
                timePerRequest = (int)r.getAverageValue();
            else
                timePerRequest = (int)rs.getLifetimeAverageValue();
        }
        float pctFull = (queuedRequests * timePerRequest) / (4*1000f);
        double pReject = Math.pow(pctFull, 16); //1 - ((1-pctFull) * (1-pctFull));
***/
        // let it in because we drop overload- rejecting may be overkill,
        // especially since we've done the cpu-heavy lifting to figure out
        // whats up
        /*
        if ( (pctFull >= 1) || (pReject >= _context.random().nextFloat()) ) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rejecting a new tunnel request because we have too many pending requests (" + queuedRequests 
                          + " at " + timePerRequest + "ms each, %full = " + pctFull);
            _context.statManager().addRateData("router.throttleTunnelQueueOverload", queuedRequests, timePerRequest);
            return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
        }
        */

        // ok, all is well, let 'er in
        _context.statManager().addRateData("tunnel.bytesAllocatedAtAccept", (long)bytesAllocated, 60*10*1000);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Accepting a new tunnel request (now allocating " + bytesAllocated + " bytes across " + numTunnels 
                       + " tunnels with lag of " + lag + ")");
        return TUNNEL_ACCEPT;
    }

    private static final int DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE = 60; // .1KBps
    private static final int MIN_AVAILABLE_BPS = 4*1024; // always leave at least 4KBps free when allowing
    
    /**
     * with bytesAllocated already accounted for across the numTunnels existing
     * tunnels we have agreed to, can we handle another tunnel with our existing
     * bandwidth?
     *
     */
    private boolean allowTunnel(double bytesAllocated, int numTunnels) {
        int maxKBps = Math.min(_context.bandwidthLimiter().getOutboundKBytesPerSecond(), _context.bandwidthLimiter().getInboundKBytesPerSecond());
        int used1s = _context.router().get1sRate(); // dont throttle on the 1s rate, its too volatile
        int used15s = _context.router().get15sRate();
        int used1m = _context.router().get1mRate(); // dont throttle on the 1m rate, its too slow
        int used = Math.min(used15s,used1s);

        double share = _context.router().getSharePercentage();
        int availBps = (int)(((maxKBps*1024)*share) - used); //(int)(((maxKBps*1024) - used) * getSharePercentage());

        // Write stats before making decisions
        _context.statManager().addRateData("router.throttleTunnelBytesUsed", used, maxKBps);
        _context.statManager().addRateData("router.throttleTunnelBytesAllowed", availBps, (long)bytesAllocated);

        long overage = used1m - (maxKBps*1024);
        if ( (overage > 0) && 
             ((overage/(float)(maxKBps*1024f)) > _context.random().nextFloat()) ) {
                
            if (_log.shouldLog(Log.WARN)) _log.warn("Reject tunnel, 1m rate (" + used1m + ") indicates overload.");
            return false;
        }

//      if (true) {
            // ok, ignore any predictions of 'bytesAllocated', since that makes poorly
            // grounded conclusions about future use (or even the bursty use).  Instead,
            // simply say "do we have the bw to handle a new request"?
            float maxBps = maxKBps * 1024f;
            float pctFull = (maxBps - availBps) / (maxBps);
            double probReject = Math.pow(pctFull, 16); // steep curve 
            double rand = _context.random().nextFloat();
            boolean reject = (availBps < MIN_AVAILABLE_BPS) || (rand <= probReject);
            if (reject && _log.shouldLog(Log.WARN))
                _log.warn("reject = " + reject + " avail/maxK/used " + availBps + "/" + maxKBps + "/" 
                          + used + " pReject = " + probReject + " pFull = " + pctFull + " numTunnels = " + numTunnels 
                          + "rand = " + rand + " est = " + bytesAllocated + " share = " + (float)share);
            else if (_log.shouldLog(Log.DEBUG))
                _log.debug("reject = " + reject + " avail/maxK/used " + availBps + "/" + maxKBps + "/" 
                           + used + " pReject = " + probReject + " pFull = " + pctFull + " numTunnels = " + numTunnels 
                           + "rand = " + rand + " est = " + bytesAllocated + " share = " + (float)share);
            if (probReject >= 0.9)
                setTunnelStatus("Rejecting tunnels: Bandwidth limit");
            else if (probReject >= 0.5)
                setTunnelStatus("Rejecting " + ((int)(100.0*probReject)) + "% of tunnels: Bandwidth limit");
            else if(probReject >= 0.1)
                setTunnelStatus("Accepting " + (100-(int)(100.0*probReject)) + "% of tunnels");
            else
                setTunnelStatus("Accepting tunnels");
            return !reject;
//      }
        
        
        /*
        if (availBps <= 8*1024) {
            // lets be more conservative for people near their limit and assume 1KBps per tunnel
            boolean rv = ( (numTunnels + 1)*1024 < availBps);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Nearly full router (" + availBps + ") with " + numTunnels + " tunnels, allow a new request? " + rv);
            return rv;
        }
        */

/***
        double growthFactor = ((double)(numTunnels+1))/(double)numTunnels;
        double toAllocate = (numTunnels > 0 ? bytesAllocated * growthFactor : 0);
        
        double allocatedBps = toAllocate / (10 * 60);
        double pctFull = allocatedBps / availBps;
        
        if ( (pctFull < 1.0) && (pctFull >= 0.0) ) { // (_context.random().nextInt(100) > 100 * pctFull) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Allowing the tunnel w/ " + pctFull + " of our " + availBps
                           + "Bps/" + allocatedBps + "KBps allocated through " + numTunnels + " tunnels");
            return true;
        } else {
            double probAllow = availBps / (allocatedBps + availBps);
            boolean allow = (availBps > MIN_AVAILABLE_BPS) && (_context.random().nextFloat() <= probAllow);
            if (allow) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Probabalistically allowing the tunnel w/ " + (pctFull*100d) + "% of our " + availBps 
                               + "Bps allowed (" + toAllocate + "bytes / " + allocatedBps 
                               + "Bps) through " + numTunnels + " tunnels");
                return true;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Rejecting the tunnel w/ " + (pctFull*100d) + "% of our " + availBps 
                               + "Bps allowed (" + toAllocate + "bytes / " + allocatedBps 
                               + "Bps) through " + numTunnels + " tunnels");
                return false;
            }
        }
***/
    }
    
    /** dont ever probabalistically throttle tunnels if we have less than this many */
    private int getMinThrottleTunnels() { 
        try {
            return Integer.parseInt(_context.getProperty("router.minThrottleTunnels", "1000"));
        } catch (NumberFormatException nfe) {
            return 1000;
        }
    }
    
    private double getTunnelGrowthFactor() {
        try {
            return Double.parseDouble(_context.getProperty("router.tunnelGrowthFactor", "1.3"));
        } catch (NumberFormatException nfe) {
            return 1.3;
        }
    }

    private double getTunnelTestTimeGrowthFactor() {
        try {
            return Double.parseDouble(_context.getProperty("router.tunnelTestTimeGrowthFactor", "1.3"));
        } catch (NumberFormatException nfe) {
            return 1.3;
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
    
    public String getTunnelStatus() {
        return _tunnelStatus;
    }

    private void setTunnelStatus() {
// NPE, too early
//        if (_context.router().getRouterInfo().getBandwidthTier().equals("K"))
//            setTunnelStatus("Not expecting tunnel requests: Advertised bandwidth too low");
//        else
            setTunnelStatus("Rejecting tunnels");
    }

    public void setTunnelStatus(String msg) {
        _tunnelStatus = msg;
    }

    protected RouterContext getContext() { return _context; }
}
