package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;

import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 *
 */
public class TunnelPool {
    private RouterContext _context;
    private Log _log;
    private TunnelPoolSettings _settings;
    private ArrayList _tunnels;
    private TunnelPeerSelector _peerSelector;
    private TunnelBuilder _builder;
    private TunnelPoolManager _manager;
    private boolean _alive;
    private long _lifetimeProcessed;
    
    /** 
     * list of pool tokens (Object) passed around during building/rebuilding/etc.
     * if/when the token is removed from this list, that sequence of building/rebuilding/etc
     * should cease (though others may continue).
     *
     */
    private List _tokens;
    
    public TunnelPool(RouterContext ctx, TunnelPoolManager mgr, TunnelPoolSettings settings, TunnelPeerSelector sel, TunnelBuilder builder) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPool.class);
        _manager = mgr;
        _settings = settings;
        _tunnels = new ArrayList(settings.getLength() + settings.getBackupQuantity());
        _peerSelector = sel;
        _builder = builder;
        _tokens = new ArrayList(settings.getBackupQuantity() + settings.getQuantity());
        _alive = false;
        _lifetimeProcessed = 0;
        refreshSettings();
    }
    
    public void startup() {
        _alive = true;
        int added = refreshBuilders();
        if (added <= 0) {
            // we just reconnected and didn't require any new tunnel builders.
            // however, we /do/ want a leaseSet, so build one
            LeaseSet ls = null;
            synchronized (_tunnels) {
                if (_settings.isInbound() && (_settings.getDestination() != null) )
                    ls = locked_buildNewLeaseSet();
            }

            if (ls != null)
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
        }
    }
    public void shutdown() {
        _alive = false;
        synchronized (_tokens) { _tokens.clear(); }
    }
    
    private int refreshBuilders() {
        // only start up new build tasks if we need more of 'em
        int target = _settings.getQuantity() + _settings.getBackupQuantity();
        int oldTokenCount = 0;
        List newTokens = null;
        synchronized (_tokens) {
            oldTokenCount = _tokens.size();
            while (_tokens.size() > target)
                _tokens.remove(0);
            if (_tokens.size() < target) {
                int wanted = target - _tokens.size();
                newTokens = new ArrayList(wanted);
                for (int i = 0; i < wanted; i++) {
                    Object token = new Object();
                    newTokens.add(token);
                    _tokens.add(token);
                }
            }
        }
        
        if (newTokens != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info(toString() + ": refreshing builders, previously had " + oldTokenCount 
                          + ", want a total of " + target + ", creating " 
                          + newTokens.size() + " new ones.");
            for (int i = 0; i < newTokens.size(); i++) {
                Object token = newTokens.get(i);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(toString() + ": Building a tunnel with the token " + token);
                _builder.buildTunnel(_context, this, token);
            }
            return newTokens.size();
        } else {
            return 0;
        }
    }
    
    /** do we still need this sequence of build/rebuild/etc to continue? */
    public boolean keepBuilding(Object token) {
        boolean connected = true;
        boolean rv = false;
        int remaining = 0;
        int wanted = _settings.getQuantity() + _settings.getBackupQuantity();
        if ( (_settings.getDestination() != null) && (!_context.clientManager().isLocal(_settings.getDestination())) )
            connected = false;
        synchronized (_tokens) {
            if (!connected) {
                // client disconnected, so stop rebuilding this series
                _tokens.remove(token);
                rv = false;
            } else {
                rv = _tokens.contains(token); 
            }
            remaining = _tokens.size();
        } 
        
        if (remaining <= 0) {
            _manager.removeTunnels(_settings.getDestination());
        }
        
        if (!rv) {
            if (_log.shouldLog(Log.INFO))
                _log.info(toString() + ": keepBuilding does NOT want building to continue (want " 
                          + wanted + ", have " + remaining);
        }
        return rv;
    }
    
    void refreshSettings() {
        if (_settings.getDestination() != null) {
            return; // don't override client specified settings
        } else {
            if (_settings.isExploratory()) {
                Properties props = _context.router().getConfigMap();
                if (_settings.isInbound())
                    _settings.readFromProperties(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY, props);
                else
                    _settings.readFromProperties(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY, props);
            }
        }
    }
    
    /**
     * Pull a random tunnel out of the pool.  If there are none available but
     * the pool is configured to allow 0hop tunnels, this builds a fake one
     * and returns it.
     *
     */
    public TunnelInfo selectTunnel() { return selectTunnel(true); }
    private TunnelInfo selectTunnel(boolean allowRecurseOnFail) {
        synchronized (_tunnels) {
            if (_tunnels.size() <= 0) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": No tunnels to select from");
            } else {
                // pick 'em randomly
                Collections.shuffle(_tunnels, _context.random());
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                    if (info.getExpiration() > _context.clock().now()) {
                        //_log.debug("Selecting tunnel: " + info + " - " + _tunnels);
                        return info;
                    }
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": after " + _tunnels.size() + " tries, no unexpired ones were found: " + _tunnels);
            }
        }
        
        if (_alive && _settings.getAllowZeroHop())
            buildFake();
        if (allowRecurseOnFail)
            return selectTunnel(false); 
        else
            return null;
    }
    
    public TunnelInfo getTunnel(TunnelId gatewayId) {
        synchronized (_tunnels) {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                if (_settings.isInbound()) {
                    if (info.getReceiveTunnelId(0).equals(gatewayId))
                        return info;
                } else {
                    if (info.getSendTunnelId(0).equals(gatewayId))
                        return info;
                }
            }
        }
        return null;
    }
    
    /**
     * Return a list of tunnels in the pool
     *
     * @return list of TunnelInfo objects
     */
    public List listTunnels() {
        synchronized (_tunnels) {
            return new ArrayList(_tunnels);
        }
    }
    
    public TunnelBuilder getBuilder() { return _builder; }
    public TunnelPoolSettings getSettings() { return _settings; }
    public void setSettings(TunnelPoolSettings settings) { 
        _settings = settings; 
        if (_settings != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info(toString() + ": Settings updated on the pool: " + settings);
            refreshBuilders(); // to start/stop new sequences, in case the quantities changed
        }
    }
    public TunnelPeerSelector getSelector() { return _peerSelector; }
    public boolean isAlive() { return _alive; }
    public int size() { 
        synchronized (_tunnels) {
            return _tunnels.size();
        }
    }
    
    public void addTunnel(TunnelInfo info) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": Adding tunnel " + info);
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.add(info);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
        }
        
        if (ls != null)
            _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
    }
    
    public void removeTunnel(TunnelInfo info) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": Removing tunnel " + info);
        int remaining = 0;
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.remove(info);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
            remaining = _tunnels.size();
        }
        
        _lifetimeProcessed += info.getProcessedMessagesCount();
        
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            if (ls != null) {
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": unable to build a new leaseSet on removal (" + remaining 
                              + " remaining), request a new tunnel");
                if (_settings.getAllowZeroHop())
                    buildFake();
            }
        }
        refreshBuilders();
    }

    public void tunnelFailed(PooledTunnelCreatorConfig cfg) {
        if (_log.shouldLog(Log.WARN))
            _log.warn(toString() + ": Tunnel failed: " + cfg, new Exception("failure cause"));
        int remaining = 0;
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.remove(cfg);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
            remaining = _tunnels.size();
        }
        
        _lifetimeProcessed += cfg.getProcessedMessagesCount();
        
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            if (ls != null) {
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": unable to build a new leaseSet on failure (" + remaining 
                              + " remaining), request a new tunnel");
                if (remaining < _settings.getBackupQuantity() + _settings.getQuantity())
                    buildFake(false);
            }
        }
        refreshBuilders();
    }

    void refreshLeaseSet() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": refreshing leaseSet on tunnel expiration (but prior to grace timeout)");
        int remaining = 0;
        LeaseSet ls = null;
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            synchronized (_tunnels) {
                ls = locked_buildNewLeaseSet();
                remaining = _tunnels.size();
            }
            if (ls != null) {
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": unable to build a new leaseSet on expire (" + remaining 
                              + " remaining), request a new tunnel");
                if ( (remaining < _settings.getBackupQuantity() + _settings.getQuantity()) 
                   && (_settings.getAllowZeroHop()) )
                        buildFake();
            }
        }
    }

    void buildFake() { buildFake(true); }
    void buildFake(boolean zeroHop) {
        int quantity = _settings.getBackupQuantity() + _settings.getQuantity();
        boolean needed = true;
        synchronized (_tunnels) {
            if (_tunnels.size() > quantity) {
                int valid = 0;
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                    if (info.getExpiration() > _context.clock().now()) {
                        valid++;
                        if (valid >= quantity)
                            break;
                    }
                }
                if (valid >= quantity)
                    needed = false;
            }
        }
        
        if (!needed) return;

        if (_log.shouldLog(Log.INFO))
            _log.info(toString() + ": building a fake tunnel (allow zeroHop? " + zeroHop + ")");
        Object tempToken = new Object();
        synchronized (_tokens) {
            _tokens.add(tempToken);
        }
        _builder.buildTunnel(_context, this, zeroHop, tempToken);
        synchronized (_tokens) {
            _tokens.remove(tempToken);
        }
    }
    
    /**
     * Build a leaseSet with all of the tunnels that aren't about to expire
     *
     */
    private LeaseSet locked_buildNewLeaseSet() {
        long expireAfter = _context.clock().now() + _settings.getRebuildPeriod();
        
        LeaseSet ls = new LeaseSet();
        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo tunnel = (TunnelInfo)_tunnels.get(i);
            if (tunnel.getExpiration() <= expireAfter)
                continue; // expires too soon, skip it
            
            TunnelId inId = tunnel.getReceiveTunnelId(0);
            Hash gw = tunnel.getPeer(0);
            if ( (inId == null) || (gw == null) ) {
                _log.error(toString() + ": wtf, tunnel has no inbound gateway/tunnelId? " + tunnel);
                continue;
            }
            Lease lease = new Lease();
            lease.setEndDate(new Date(tunnel.getExpiration()));
            lease.setTunnelId(inId);
            lease.setGateway(gw);
            ls.addLease(lease);
        }
        
        int wanted = _settings.getQuantity();
        
        if (ls.getLeaseCount() < wanted) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(toString() + ": Not enough leases (" + ls.getLeaseCount() + ", wanted " + wanted + ")");
            return null;
        } else {
            // linear search to trim down the leaseSet, removing the ones that 
            // will expire the earliest.  cheaper than a tree for this size
            while (ls.getLeaseCount() > wanted) {
                int earliestIndex = -1;
                long earliestExpiration = -1;
                for (int i = 0; i < ls.getLeaseCount(); i++) {
                    Lease cur = ls.getLease(i);
                    if ( (earliestExpiration < 0) || (cur.getEndDate().getTime() < earliestExpiration) ) {
                        earliestIndex = i;
                        earliestExpiration = cur.getEndDate().getTime();
                    }
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(toString() + ": Dropping older lease from the leaseSet: " + earliestIndex + " out of " + ls.getLeaseCount());
                ls.removeLease(earliestIndex);
            }
            return ls;
        }
    }
    
    public long getLifetimeProcessed() { return _lifetimeProcessed; }
    
    public String toString() {
        if (_settings.isExploratory()) {
            if (_settings.isInbound())
                return "Inbound exploratory pool";
            else
                return "Outbound exploratory pool";
        } else {
            StringBuffer rv = new StringBuffer(32);
            if (_settings.isInbound())
                rv.append("Inbound client pool for ");
            else
                rv.append("Outbound client pool for ");
            if (_settings.getDestinationNickname() != null)
                rv.append(_settings.getDestinationNickname());
            else
                rv.append(_settings.getDestination().toBase64().substring(0,4));
            return rv.toString();
        }
            
    }
}
