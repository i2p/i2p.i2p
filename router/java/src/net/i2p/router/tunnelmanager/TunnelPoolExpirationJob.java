package net.i2p.router.tunnelmanager;

import java.util.Date;
import java.util.Iterator;

import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Periodically go through all of the tunnels not assigned to a client and mark
 * them as no longer ready and/or drop them (as appropriate)
 *
 */
class TunnelPoolExpirationJob extends JobImpl {
    private Log _log;
    private TunnelPool _pool;
    
    /** expire tunnels as necessary every 30 seconds */
    private final static long EXPIRE_POOL_DELAY = 30*1000;
    
    /**
     * don't hard expire a tunnel until its later than expiration + buffer
     */
    private final static long EXPIRE_BUFFER = 30*1000;
    
    public TunnelPoolExpirationJob(RouterContext ctx, TunnelPool pool) {
        super(ctx);
        _log = ctx.logManager().getLog(TunnelPoolExpirationJob.class);
        _pool = pool;
        getTiming().setStartAfter(getContext().clock().now() + EXPIRE_POOL_DELAY);
    }
    public String getName() { return "Expire Pooled Tunnels"; }
    public void runJob() {
        if (!_pool.isLive())
            return;
        expireFree();
        expireOutbound();
        expireParticipants();
        expirePending();
        requeue(EXPIRE_POOL_DELAY);
    }
    
    /**
     * Drop all pooled free tunnels that are expired or are close enough to
     * being expired that allocating them to a client would suck.
     *
     */
    public void expireFree() {
        long now = getContext().clock().now();
        long expire = now - EXPIRE_BUFFER - Router.CLOCK_FUDGE_FACTOR;
        
        for (Iterator iter = _pool.getFreeTunnels().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = _pool.getFreeTunnel(id);
            if ( (info != null) && (info.getSettings() != null) ) {
                if (info.getSettings().getExpiration() < expire) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Expiring free inbound tunnel " + id + " [" 
                                  + new Date(info.getSettings().getExpiration()) 
                                  + "] (expire = " + new Date(expire) + ")");
                    _pool.removeFreeTunnel(id);
                } else if (info.getSettings().getExpiration() < now) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("It is past the expiration for free inbound tunnel " + id 
                                  + " but not yet the buffer, mark it as no longer ready");
                    info.setIsReady(false);
                }
            }
        }
    }
    
    /**
     * Drop all pooled outbound tunnels that are expired
     *
     */
    public void expireOutbound() {
        long now = getContext().clock().now();
        long expire = now - EXPIRE_BUFFER - Router.CLOCK_FUDGE_FACTOR;
        
        for (Iterator iter = _pool.getOutboundTunnels().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = _pool.getOutboundTunnel(id);
            if ( (info != null) && (info.getSettings() != null) ) {
                if (info.getSettings().getExpiration() < expire) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Expiring outbound tunnel " + id + " [" 
                                  + new Date(info.getSettings().getExpiration()) + "]");
                    _pool.removeOutboundTunnel(id);
                } else if (info.getSettings().getExpiration() < now) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("It is past the expiration for outbound tunnel " + id 
                                  + " but not yet the buffer, mark it as no longer ready");
                    info.setIsReady(false);
                }
            }
        }
    }
    
    /**
     * Drop all tunnels we are participating in (but not managing) that are expired
     *
     */
    public void expireParticipants() {
        long now = getContext().clock().now();
        long expire = now - EXPIRE_BUFFER - Router.CLOCK_FUDGE_FACTOR;
        
        for (Iterator iter = _pool.getParticipatingTunnels().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = _pool.getParticipatingTunnel(id);
            if ( (info != null) && (info.getSettings() != null) ) {
                if (info.getSettings().getExpiration() < expire) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Expiring participation in tunnel " + id + " [" 
                                  + new Date(info.getSettings().getExpiration()) + "]");
                    _pool.removeParticipatingTunnel(id);
                }
            }
        }
    }
    
    /**
     * Drop all tunnels that were in the process of being built, but expired before being handled
     *
     */
    public void expirePending() {
        long now = getContext().clock().now();
        long expire = now - EXPIRE_BUFFER - Router.CLOCK_FUDGE_FACTOR;
        
        for (Iterator iter = _pool.getPendingTunnels().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = _pool.getPendingTunnel(id);
            if ( (info != null) && (info.getSettings() != null) ) {
                if (info.getSettings().getExpiration() < expire) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Expiring pending tunnel " + id + " [" 
                                  + new Date(info.getSettings().getExpiration()) + "]");
                    _pool.removePendingTunnel(id);
                }
            }
        }
    }
}
