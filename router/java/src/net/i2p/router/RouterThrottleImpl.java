package net.i2p.router;

import net.i2p.data.Hash;
import net.i2p.data.i2np.TunnelCreateMessage;
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
    }
    
    public boolean acceptNetworkMessage() {
        long lag = _context.jobQueue().getMaxLag();
        if (lag > JOB_LAG_LIMIT) {
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
    public boolean acceptTunnelRequest(TunnelCreateMessage msg) { 
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
            return false;
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
            return false;
        }
        if (rs != null)
            r = rs.getRate(60*1000);
        processTime = (r != null ? r.getAverageValue() : 0);
        if (processTime > 2000) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Refusing tunnel request with the job lag of " + lag 
                           + "since the 1 minute message processing time is too slow (" + processTime + ")");
            _context.statManager().addRateData("router.throttleTunnelProcessingTime1m", (long)processTime, (long)processTime);
            return false;
        }
        
        // ok, we're not hosed, but can we handle the bandwidth requirements 
        // of another tunnel?
        rs = _context.statManager().getRate("tunnel.participatingMessagesProcessed");
        r = null;
        if (rs != null)
            r = rs.getRate(10*60*1000);
        double msgsPerTunnel = (r != null ? r.getAverageValue() : 0);
        r = null;
        rs = _context.statManager().getRate("tunnel.relayMessageSize");
        if (rs != null)
            r = rs.getRate(10*60*1000);
        double bytesPerMsg = (r != null ? r.getAverageValue() : 0);
        double bytesPerTunnel = msgsPerTunnel * bytesPerMsg;

        int numTunnels = _context.tunnelManager().getParticipatingCount();
        double bytesAllocated =  (numTunnels + 1) * bytesPerTunnel;

        // the max # tunnels throttle is useful for shutting down the router - 
        // set this to 0, wait a few minutes, and the router can be shut off 
        // without killing anyone's tunnels
        String maxTunnels = _context.getProperty(PROP_MAX_TUNNELS);
        if (maxTunnels != null) {
            try {
                int max = Integer.parseInt(maxTunnels);
                if (numTunnels >= max) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Refusing tunnel request since we are already participating in " 
                                  + numTunnels + " (our max is " + max + ")");
                    _context.statManager().addRateData("router.throttleTunnelMaxExceeded", numTunnels, 0);
                    return false;
                }
            } catch (NumberFormatException nfe) {
                // no default, ignore it
            }
        }
        
        _context.statManager().addRateData("tunnel.bytesAllocatedAtAccept", (long)bytesAllocated, msg.getTunnelDurationSeconds()*1000);
        // todo: um, throttle (include bw usage of the netDb, our own tunnels, the clients,
        // and check to see that they are less than the bandwidth limits

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Accepting a new tunnel request (now allocating " + bytesAllocated + " bytes across " + numTunnels 
                       + " tunnels with lag of " + lag + " and " + throttleEvents + " throttle events)");
        return true;
    }
    
    protected RouterContext getContext() { return _context; }
}
