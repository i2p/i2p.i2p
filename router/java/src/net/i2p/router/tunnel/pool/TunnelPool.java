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

import net.i2p.router.JobImpl;
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
    private int _buildsThisMinute;
    private long _currentMinute;
    private RefreshJob _refreshJob;
    private TunnelInfo _lastSelected;
    private long _lastSelectionPeriod;
    
    /**
     * Only 5 builds per minute per pool, even if we have failing tunnels,
     * etc.  On overflow, the necessary additional tunnels are built by the
     * RefreshJob
     */
    private static final int MAX_BUILDS_PER_MINUTE = 10;
    
    public TunnelPool(RouterContext ctx, TunnelPoolManager mgr, TunnelPoolSettings settings, TunnelPeerSelector sel, TunnelBuilder builder) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPool.class);
        _manager = mgr;
        _settings = settings;
        _tunnels = new ArrayList(settings.getLength() + settings.getBackupQuantity());
        _peerSelector = sel;
        _builder = builder;
        _alive = false;
        _lastSelectionPeriod = 0;
        _lastSelected = null;
        _lifetimeProcessed = 0;
        _buildsThisMinute = 0;
        _currentMinute = ctx.clock().now();
        _refreshJob = new RefreshJob(ctx);
        refreshSettings();
    }
    
    public void startup() {
        _alive = true;
        _refreshJob.getTiming().setStartAfter(_context.clock().now() + 60*1000);
        _context.jobQueue().addJob(_refreshJob);
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
        _lastSelectionPeriod = 0;
        _lastSelected = null;
    }

    private int countUsableTunnels() {
        int valid = 0;
        synchronized (_tunnels) {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                if (info.getExpiration() > _context.clock().now() + 3*_settings.getRebuildPeriod())
                    valid++;
            }
        }
        return valid;
    }
    
    /**
     * Fire up as many buildTunnel tasks as necessary, returning how many
     * were added
     *
     */
    int refreshBuilders() {
        if ( (_settings.getDestination() != null) && (!_context.clientManager().isLocal(_settings.getDestination())) )
            _alive = false;
        if (!_alive) return 0;
        // only start up new build tasks if we need more of 'em
        int target = _settings.getQuantity() + _settings.getBackupQuantity();
        int usableTunnels = countUsableTunnels();
        
        if ( (target > usableTunnels) && (_log.shouldLog(Log.INFO)) )
            _log.info(toString() + ": refreshing builders, previously had " + usableTunnels
                          + ", want a total of " + target + ", creating " 
                          + (target-usableTunnels) + " new ones.");

        if (target > usableTunnels) {
            long minute = _context.clock().now();
            minute = minute - (minute % 60*1000);
            if (_currentMinute < minute) {
                _currentMinute = minute;
                _buildsThisMinute = 0;
            }
            int build = (target - usableTunnels);
            if (build > (MAX_BUILDS_PER_MINUTE - _buildsThisMinute))
                build = (MAX_BUILDS_PER_MINUTE - _buildsThisMinute);
            
            int wanted = build;
            build = _manager.allocateBuilds(build);
            
            if ( (wanted != build) && (_log.shouldLog(Log.ERROR)) )
                _log.error("Wanted to build " + wanted + " tunnels, but throttled down to " 
                           + build + ", due to concurrent requests (cpu overload?)");
            
            for (int i = 0; i < build; i++)
                _builder.buildTunnel(_context, this);
            _buildsThisMinute += build;

            return build;
        } else {
            return 0;
        }
    }
    
    TunnelPoolManager getManager() { return _manager; }
    
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
     * when selecting tunnels, stick with the same one for a brief 
     * period to allow batching if we can.
     */
    private long curPeriod() {
        long period = _context.clock().now();
        long ms = period % 1000;
        if (ms > 500)
            period = period - ms + 500;
        else
            period = period - ms;
        return period;
    }
    
    /**
     * Pull a random tunnel out of the pool.  If there are none available but
     * the pool is configured to allow 0hop tunnels, this builds a fake one
     * and returns it.
     *
     */
    public TunnelInfo selectTunnel() { return selectTunnel(true); }
    private TunnelInfo selectTunnel(boolean allowRecurseOnFail) {
        long period = curPeriod();
        synchronized (_tunnels) {
            if (_lastSelectionPeriod == period) {
                if ( (_lastSelected != null) && 
                     (_lastSelected.getExpiration() > period) &&
                     (_tunnels.contains(_lastSelected)) )
                    return _lastSelected;
            }
            _lastSelectionPeriod = period;
            _lastSelected = null;

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
                        _lastSelected = info;
                        return info;
                    }
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": after " + _tunnels.size() + " tries, no unexpired ones were found: " + _tunnels);
            }
        }
        
        if (_alive && _settings.getAllowZeroHop())
            buildFallback();
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
    
    int getTunnelCount() { synchronized (_tunnels) { return _tunnels.size(); } }
    
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
        
        refreshBuilders();
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
            if (_lastSelected == info) {
                _lastSelected = null;
                _lastSelectionPeriod = 0;
            }
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
                    buildFallback();
            }
        }

        boolean connected = true;
        if ( (_settings.getDestination() != null) && (!_context.clientManager().isLocal(_settings.getDestination())) )
            connected = false;
        if ( (getTunnelCount() <= 0) && (!connected) ) {
            _manager.removeTunnels(_settings.getDestination());
            return;
        }
        refreshBuilders();
    }

    public void tunnelFailed(PooledTunnelCreatorConfig cfg) {
        if (_log.shouldLog(Log.WARN))
            _log.warn(toString() + ": Tunnel failed: " + cfg);
        int remaining = 0;
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.remove(cfg);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
            remaining = _tunnels.size();
            if (_lastSelected == cfg) {
                _lastSelected = null;
                _lastSelectionPeriod = 0;
            }
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
                    buildFallback();
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
                        buildFallback();
            }
        }
    }

    void buildFallback() {
        int quantity = _settings.getBackupQuantity() + _settings.getQuantity();
        int usable = countUsableTunnels();
        if (usable >= quantity) return;

        if (_log.shouldLog(Log.INFO))
            _log.info(toString() + ": building a fallback tunnel (usable: " + usable + " needed: " + quantity + ")");
        if ( (usable == 0) && (_settings.getAllowZeroHop()) )
            _builder.buildTunnel(_context, this, true);
        //else
        //    _builder.buildTunnel(_context, this);
        refreshBuilders();
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

    /**
     * We choke the # of rebuilds per pool per minute, so we need this to
     * make sure to build enough tunnels.
     *
     */
    private class RefreshJob extends JobImpl {
        public RefreshJob(RouterContext ctx) {
            super(ctx);
        }
        public String getName() { return "Refresh pool"; }
        public void runJob() {
            if (!_alive) return;
            int added = refreshBuilders();
            if ( (added > 0) && (_log.shouldLog(Log.WARN)) )
                _log.warn("Passive rebuilding a tunnel for " + TunnelPool.this.toString());
            requeue(30*1000);
        }
    }
}
