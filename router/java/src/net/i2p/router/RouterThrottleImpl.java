package net.i2p.router;

import net.i2p.data.Hash;
import net.i2p.data.i2np.TunnelCreateMessage;
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
    
    public RouterThrottleImpl(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(RouterThrottleImpl.class);
        _context.statManager().createRateStat("router.throttleNetworkCause", "How lagged the jobQueue was when an I2NP was throttled", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleNetDbCause", "How lagged the jobQueue was when a networkDb request was throttled", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelCause", "How lagged the jobQueue was when a tunnel request was throttled", "Throttle", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("tunnel.bytesAllocatedAtAccept", "How many bytes had been 'allocated' for participating tunnels when we accepted a request?", "Tunnels", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
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
        if (lag > JOB_LAG_LIMIT) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Refusing tunnel request, as the job lag is " + lag);
            _context.statManager().addRateData("router.throttleTunnelCause", lag, lag);
            return false;
        } else {
            // ok, we're not hosed, but can we handle the bandwidth requirements 
            // of another tunnel?
            double msgsPerTunnel = _context.statManager().getRate("tunnel.participatingMessagesProcessed").getRate(10*60*1000).getAverageValue();
            double bytesPerMsg = _context.statManager().getRate("tunnel.relayMessageSize").getRate(10*60*1000).getAverageValue();
            double bytesPerTunnel = msgsPerTunnel * bytesPerMsg;
            
            
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            double bytesAllocated =  (numTunnels + 1) * bytesPerTunnel;
            
            _context.statManager().addRateData("tunnel.bytesAllocatedAtAccept", (long)bytesAllocated, msg.getTunnelDurationSeconds()*1000);
            // todo: um, throttle (include bw usage of the netDb, our own tunnels, the clients,
            // and check to see that they are less than the bandwidth limits
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Accepting a new tunnel request (now allocating " + bytesAllocated + " bytes across " + numTunnels + " tunnels");
            return true;          
        }
    }
}
