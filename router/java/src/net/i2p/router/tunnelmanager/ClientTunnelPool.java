package net.i2p.router.tunnelmanager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.TunnelId;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

class ClientTunnelPool {
    private Log _log;
    private Destination _dest;
    private ClientTunnelSettings _settings;
    private TunnelPool _pool;
    private Map _inboundTunnels; // TunnelId --> TunnelInfo for inbound tunnels
    private Map _inactiveInboundTunnels; // TunnelId --> TunnelInfo for inbound tunnels no longer in use (but not expired)
    private ClientTunnelPoolManagerJob _mgrJob;
    private ClientLeaseSetManagerJob _leaseMgrJob;
    private ClientTunnelPoolExpirationJob _tunnelExpirationJob;
    private boolean _isStopped;
    private static int __poolId;
    private int _poolId;
    private RouterContext _context;
    
    public ClientTunnelPool(RouterContext ctx, Destination dest, ClientTunnelSettings settings, 
                            TunnelPool pool) {
        _context = ctx;
        _log = ctx.logManager().getLog(ClientTunnelPool.class);
        _dest = dest;
        _settings = settings;
        _pool = pool;
        _inboundTunnels = new HashMap();
        _inactiveInboundTunnels = new HashMap();
        _isStopped = true;
        _poolId = ++__poolId;
    }
    
    public void startPool() {
        if (!_isStopped) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Pool " + _poolId +": Not starting the pool /again/ (its already running)");
            return;
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Pool " + _poolId +": Starting up the pool ");
        }
        _isStopped = false;
        if (_mgrJob == null) {
            _mgrJob = new ClientTunnelPoolManagerJob(_context, _pool, this);
            _context.jobQueue().addJob(_mgrJob);
        }
        if (_leaseMgrJob == null) {
            _leaseMgrJob = new ClientLeaseSetManagerJob(_context, this);
            _context.jobQueue().addJob(_leaseMgrJob);
        } else {
            // we just restarted, so make sure we ask for a new leaseSet ASAP
            _leaseMgrJob.forceRequestLease();
            _leaseMgrJob.getTiming().setStartAfter(_context.clock().now());
            _context.jobQueue().addJob(_leaseMgrJob);
        }
        if (_tunnelExpirationJob == null) {
            _tunnelExpirationJob = new ClientTunnelPoolExpirationJob(_context, this, _pool);
            _context.jobQueue().addJob(_tunnelExpirationJob);
        }
    }
    public void stopPool() { _isStopped = true; }
    public boolean isStopped() { return _isStopped; }
    
    public void setClientSettings(ClientTunnelSettings settings) { 
        _settings = settings;
        if (settings != null) {
            _log.info("Client settings specified - the client may have reconnected, so restart the pool");
            startPool(); 
        }
    }
    public ClientTunnelSettings getClientSettings() { return _settings; }
    
    public Destination getDestination() { return _dest; }
    
    public void moveToInactive(TunnelId id) {
        TunnelInfo info = removeInboundTunnel(id);
        if (info != null) {
            _context.messageHistory().tunnelJoined("inactive inbound", info);
            synchronized (_inactiveInboundTunnels) {
                _inactiveInboundTunnels.put(id, info);
            }
            _log.info("Marking tunnel " + id + " as inactive");
        }
    }
    
    void setActiveTunnels(Set activeTunnels) {
        for (Iterator iter = activeTunnels.iterator(); iter.hasNext(); ) {
            TunnelInfo info = (TunnelInfo)iter.next();
            _context.messageHistory().tunnelJoined("active inbound", info);
            synchronized (_inboundTunnels) {
                _inboundTunnels.put(info.getTunnelId(), info);
            }
        }
    }
    void setInactiveTunnels(Set inactiveTunnels) {
        for (Iterator iter = inactiveTunnels.iterator(); iter.hasNext(); ) {
            TunnelInfo info = (TunnelInfo)iter.next();
            _context.messageHistory().tunnelJoined("inactive inbound", info);
            synchronized (_inactiveInboundTunnels) {
                _inactiveInboundTunnels.put(info.getTunnelId(), info);
            }
        }
    }
    
    /** 
     * Go through all of the client's inbound tunnels and determine how many are safe for
     * use over the next period, either as part of a LeaseSet or as the target for a reply / etc.
     *
     */
    public int getSafePoolSize() {
        return getSafePoolSize(0);
    }
    /**
     * Get the safe # pools at some point in the future
     * 
     * @param futureMs number of milliseconds in the future that we want to check safety for
     */
    public int getSafePoolSize(long futureMs) {
        int numSafe = 0;
        long expireAfter = _context.clock().now() + Router.CLOCK_FUDGE_FACTOR + futureMs;
        for (Iterator iter = getInboundTunnelIds().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = getInboundTunnel(id);
            if ( (info != null) && (info.getIsReady()) && (info.getSettings().getExpiration() > expireAfter) )
                numSafe++;
        }
        return numSafe;
    }
    
    /**
     * Set of tunnelIds of inbound tunnels
     *
     */
    public Set getInboundTunnelIds() { 
        synchronized (_inboundTunnels) {
            return new HashSet(_inboundTunnels.keySet());
        }
    }
    public boolean isInboundTunnel(TunnelId id) {
        synchronized (_inboundTunnels) {
            return _inboundTunnels.containsKey(id);
        }
    }
    public TunnelInfo getInboundTunnel(TunnelId id) {
        synchronized (_inboundTunnels) {
            return (TunnelInfo)_inboundTunnels.get(id);
        }
    }
    public void addInboundTunnel(TunnelInfo tunnel) {
        _context.messageHistory().tunnelJoined("active inbound", tunnel);
        synchronized (_inboundTunnels) {
            _inboundTunnels.put(tunnel.getTunnelId(), tunnel);
        }
    }
    public TunnelInfo removeInboundTunnel(TunnelId id) {
        synchronized (_inboundTunnels) {
            return (TunnelInfo)_inboundTunnels.remove(id);
        }
    }
    
    public Set getInactiveInboundTunnelIds() { 
        synchronized (_inactiveInboundTunnels) {
            return new HashSet(_inactiveInboundTunnels.keySet());
        }
    }
    public boolean isInactiveInboundTunnel(TunnelId id) {
        synchronized (_inactiveInboundTunnels) {
            return _inactiveInboundTunnels.containsKey(id);
        }
    }
    public TunnelInfo getInactiveInboundTunnel(TunnelId id) { 
        synchronized (_inactiveInboundTunnels) {
            return (TunnelInfo)_inactiveInboundTunnels.get(id);
        }
    }
    public TunnelInfo removeInactiveInboundTunnel(TunnelId id) {
        synchronized (_inactiveInboundTunnels) {
            return (TunnelInfo)_inactiveInboundTunnels.remove(id);
        }
    }
}
