package net.i2p.router.tunnelmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.util.Log;

/**
 * Implement the tunnel selection algorithms
 *
 */
class PoolingTunnelSelector {
    private Log _log;
    private RouterContext _context;
    /** don't use a tunnel thats about to expire */
    private static long POOL_USE_SAFETY_MARGIN = 10*1000;
    
    public PoolingTunnelSelector(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(PoolingTunnelSelector.class);
    }
    
    public List selectOutboundTunnelIds(TunnelPool pool, TunnelSelectionCriteria criteria) {
        return selectOutboundTunnelIds(pool, criteria, true);
    }
    public List selectOutboundTunnelIds(TunnelPool pool, TunnelSelectionCriteria criteria, boolean recurse) {
        List tunnelIds = new ArrayList(criteria.getMinimumTunnelsRequired());
        
        Set outIds = pool.getOutboundTunnels();
        for (Iterator iter = outIds.iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = pool.getOutboundTunnel(id);
            if ( (info != null) && (info.getIsReady()) ) {
                if (isAlmostExpired(pool, id, POOL_USE_SAFETY_MARGIN)) {
                    if (_log.shouldLog(Log.INFO)) 
                        _log.info("Tunnel " + id + " is almost expired");
                } else {
                    tunnelIds.add(id);
                }
            } else {
                if (info == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Outbound tunnel " + id + " was not found?!  expire race perhaps?");
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Outbound tunnel " + id + " was not ready?! " + new Date(info.getSettings().getExpiration()));
                }
            }
        }
        
        boolean rebuilt = false;
        for (int i = outIds.size(); i < criteria.getMinimumTunnelsRequired(); i++) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Building fake tunnels because the outbound tunnels weren't sufficient");
            pool.buildFakeTunnels(true);
            rebuilt = true;
        }
        if (rebuilt && recurse)
            return selectOutboundTunnelIds(pool, criteria, false);
        
        List ordered = randomize(pool, tunnelIds);
        List rv = new ArrayList(criteria.getMinimumTunnelsRequired());
        for (Iterator iter = ordered.iterator(); iter.hasNext() && (rv.size() < criteria.getMinimumTunnelsRequired()); ) {
            rv.add(iter.next());
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Selecting outbound tunnelIds [all outbound tunnels: " + outIds.size() 
                      + ", tunnelIds ready: " + ordered.size() + ", rv: " + rv + "]");
        return rv;
    }
    
    public List selectInboundTunnelIds(TunnelPool pool, TunnelSelectionCriteria criteria) {
        return selectInboundTunnelIds(pool, criteria, true);
    }
    public List selectInboundTunnelIds(TunnelPool pool, TunnelSelectionCriteria criteria, boolean recurse) {
        List tunnels = new ArrayList(criteria.getMinimumTunnelsRequired());
        
        for (Iterator iter = pool.getFreeTunnels().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = pool.getFreeTunnel(id);
            if (info == null) continue;
            if (info.getIsReady()) {
                if (isAlmostExpired(pool, id, POOL_USE_SAFETY_MARGIN)) {
                    if (_log.shouldLog(Log.INFO)) 
                        _log.info("Tunnel " + id + " is almost expired");
                } else {
                    tunnels.add(id);
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Inbound tunnel " + id + " is not ready?! " 
                               + new Date(info.getSettings().getExpiration()));
            }
        }
        
        boolean rebuilt = false;
        for (int i = tunnels.size(); i < criteria.getMinimumTunnelsRequired(); i++) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Building fake tunnels because the inbound tunnels weren't sufficient");
            pool.buildFakeTunnels(true);
            rebuilt = true;
        }
        if (rebuilt && recurse)
            return selectInboundTunnelIds(pool, criteria, false);
        
        List ordered = randomize(pool, tunnels);
        List rv = new ArrayList(criteria.getMinimumTunnelsRequired());
        for (Iterator iter = ordered.iterator(); iter.hasNext() && (rv.size() < criteria.getMinimumTunnelsRequired()); ) {
            rv.add(iter.next());
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Selecting inbound tunnelIds [tunnelIds ready: " 
                      + tunnels.size() + ", rv: " + rv + "]");
        return rv;
    }
    
    ////
    // helpers
    ////
    
    
    private List randomize(TunnelPool pool, List tunnelIds) {
        List rv = new ArrayList(tunnelIds.size());
        for (Iterator iter = tunnelIds.iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            rv.add(id);
        }
        Collections.shuffle(rv, _context.random());
        return rv;
    }
    
    private boolean isAlmostExpired(TunnelPool pool, TunnelId id, long safetyMargin) {
        TunnelInfo info = pool.getTunnelInfo(id);
        if (info == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Tunnel " + id.getTunnelId() + " is not known");
            return true;
        }
        if (info.getSettings() == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Tunnel " + id.getTunnelId() + " has no settings");
            return true;
        }
        if (info.getSettings().getExpiration() <= 0) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Tunnel " + id.getTunnelId() + " has no expiration");
            return true;
        }
        if (info.getSettings().getExpiration() - safetyMargin <= _context.clock().now()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Expiration of tunnel " + id.getTunnelId() 
                           + " has almost been reached [" 
                           + new Date(info.getSettings().getExpiration()) + "]");
            return true;
        } else {
            return false;
        }
    }
}
