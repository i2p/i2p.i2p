package net.i2p.router.tunnelmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import net.i2p.data.TunnelId;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;
import net.i2p.router.RouterContext;

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
        List tunnelIds = new LinkedList();
        
        for (int i = pool.getOutboundTunnelCount(); i < criteria.getMinimumTunnelsRequired(); i++) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Building fake tunnels because the outbound tunnels weren't sufficient");
            pool.buildFakeTunnels();
        }
        
        Set outIds = pool.getOutboundTunnels();
        for (Iterator iter = outIds.iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = pool.getOutboundTunnel(id);
            if ( (info != null) && (info.getIsReady()) ) {
                tunnelIds.add(id);
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
        List ordered = randomize(pool, tunnelIds);
        List rv = new ArrayList(criteria.getMinimumTunnelsRequired());
        for (Iterator iter = ordered.iterator(); iter.hasNext() && (rv.size() < criteria.getMinimumTunnelsRequired()); ) {
            rv.add(iter.next());
        }
        _log.info("Selecting outbound tunnelIds [all outbound tunnels: " + outIds.size() + ", tunnelIds ready: " + ordered.size() + ", rv: " + rv + "]");
        return rv;
    }
    
    public List selectInboundTunnelIds(TunnelPool pool, TunnelSelectionCriteria criteria) {
        List tunnels = new LinkedList();
        
        for (int i = pool.getFreeTunnelCount(); i < criteria.getMinimumTunnelsRequired(); i++) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Building fake tunnels because the inbound tunnels weren't sufficient");
            pool.buildFakeTunnels();
        }
        
        for (Iterator iter = pool.getFreeTunnels().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = pool.getFreeTunnel(id);
            if (info == null) continue;
            if (info.getIsReady()) {
                tunnels.add(id);
            } else {
                _log.debug("Inbound tunnel " + id + " is not ready?! " + new Date(info.getSettings().getExpiration()));
            }
        }
        
        List ordered = randomize(pool, tunnels);
        List rv = new ArrayList(criteria.getMinimumTunnelsRequired());
        for (Iterator iter = ordered.iterator(); iter.hasNext() && (rv.size() < criteria.getMinimumTunnelsRequired()); ) {
            rv.add(iter.next());
        }
        _log.info("Selecting inbound tunnelIds [tunnelIds ready: " + tunnels.size() + ", rv: " + rv + "]");
        return rv;
    }
    
    ////
    // helpers
    ////
    
    
    private List randomize(TunnelPool pool, List tunnelIds) {
        List rv = new ArrayList(tunnelIds.size());
        for (Iterator iter = tunnelIds.iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            if (isAlmostExpired(pool, id, POOL_USE_SAFETY_MARGIN))
                continue;
            rv.add(id);
        }
        Collections.shuffle(rv, _context.random());
        return rv;
    }
    
    private boolean isAlmostExpired(TunnelPool pool, TunnelId id, long safetyMargin) {
        TunnelInfo info = pool.getTunnelInfo(id);
        if (info == null) return true;
        if (info.getSettings() == null) return true;
        if (info.getSettings().getExpiration() <= 0) return true;
        if (info.getSettings().getExpiration() - safetyMargin <= _context.clock().now()) {
            _log.debug("Expiration of tunnel " + id.getTunnelId() + " has almost been reached [" + new Date(info.getSettings().getExpiration()) + "]");
            return true;
        } else {
            return false;
        }
    }
}
