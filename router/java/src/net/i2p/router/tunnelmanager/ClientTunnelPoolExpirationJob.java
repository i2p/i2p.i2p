package net.i2p.router.tunnelmanager;

import java.util.Date;
import java.util.Iterator;

import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Periodically go through all of the tunnels assigned to this client and mark 
 * them as no longer ready and/or drop them (as appropriate)
 *
 */
class ClientTunnelPoolExpirationJob extends JobImpl {
    private Log _log;
    private ClientTunnelPool _pool;
    private TunnelPool _tunnelPool;
    
    /** expire tunnels as necessary every 30 seconds */
    private final static long EXPIRE_POOL_DELAY = 30*1000; 
    
    /**
     * don't hard expire a tunnel until its later than expiration + buffer 
     */ 
    private final static long EXPIRE_BUFFER = 30*1000;
    
    public ClientTunnelPoolExpirationJob(RouterContext context, ClientTunnelPool pool, TunnelPool tunnelPool) {
        super(context);
        _log = context.logManager().getLog(ClientTunnelPoolExpirationJob.class);
        _pool = pool;
        _tunnelPool = tunnelPool;
        getTiming().setStartAfter(_context.clock().now() + EXPIRE_POOL_DELAY);
    }
    public String getName() { return "Expire Pooled Client Tunnels"; }
    public void runJob() {
        if (_pool.isStopped()) {
            if ( (_pool.getInactiveInboundTunnelIds().size() <= 0) &&
                 (_pool.getInboundTunnelIds().size() <= 0) ) {
                // this may get called twice - once here, and once by the ClientTunnelPoolManagerJob
                // but its safe to do, and passing around messages would be overkill.
                _tunnelPool.removeClientPool(_pool.getDestination());
                if (_log.shouldLog(Log.INFO))
                    _log.info("No more tunnels to expire in the client tunnel pool for the stopped client " + _pool.getDestination().calculateHash());
                return;
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Client " + _pool.getDestination().calculateHash() 
                              + " is stopped, but they still have some tunnels, so don't stop expiring");
            }
        }

        expireInactiveTunnels();
        expireActiveTunnels();

        requeue(EXPIRE_POOL_DELAY);
    }
    
    /**
     * Drop all inactive tunnels that are expired or are close enough to 
     * being expired that using them would suck.
     *
     */
    public void expireInactiveTunnels() {
        long now = _context.clock().now();
        long expire = now - EXPIRE_BUFFER - 2*Router.CLOCK_FUDGE_FACTOR;

        for (Iterator iter = _pool.getInactiveInboundTunnelIds().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = _pool.getInactiveInboundTunnel(id);
            if ( (info != null) && (info.getSettings() != null) ) {
                if (info.getSettings().getExpiration() < expire) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Expiring inactive tunnel " + id + " [" 
                                  + new Date(info.getSettings().getExpiration()) + "]");
                    _pool.removeInactiveInboundTunnel(id);
                } else if (info.getSettings().getExpiration() < now) {
                    _log.info("It is past the expiration for inactive tunnel " + id 
                              + " but not yet the buffer, mark it as no longer ready");
                    info.setIsReady(false);
                }
            }
        }
    }
    
    /**
     * Drop all active tunnels that are expired or are close enough to 
     * being expired that using them would suck.
     *
     */
    public void expireActiveTunnels() {
        long now = _context.clock().now();
        long expire = now - EXPIRE_BUFFER - 2*Router.CLOCK_FUDGE_FACTOR;

        for (Iterator iter = _pool.getInboundTunnelIds().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = _pool.getInboundTunnel(id);
            if ( (info != null) && (info.getSettings() != null) ) {
                if (info.getSettings().getExpiration() < expire) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Expiring active tunnel " + id + " [" 
                                  + new Date(info.getSettings().getExpiration()) + "]");
                    _pool.removeInboundTunnel(id);
                } else if (info.getSettings().getExpiration() < now) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("It is past the expiration for active tunnel " + id 
                                  + " but not yet the buffer, mark it as no longer ready");
                    info.setIsReady(false);
                }
            }
        }
    }
}
