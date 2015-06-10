package net.i2p.router.tunnel.pool;

import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Log;

/**
 *  A tunnel pool with its own settings and Destination,
 *  but uses another pool for its tunnels.
 *
 *  @since 0.9.21
 */
public class AliasedTunnelPool extends TunnelPool {
    
    private final TunnelPool _aliasOf;

    AliasedTunnelPool(RouterContext ctx, TunnelPoolManager mgr, TunnelPoolSettings settings, TunnelPool aliasOf) {
        super(ctx, mgr, settings, null);
        if (settings.isExploratory())
            throw new IllegalArgumentException();
        if (settings.getAliasOf() == null)
            throw new IllegalArgumentException();
        _aliasOf = aliasOf;
    }
    
    @Override
    synchronized void startup() {
        if (_log.shouldLog(Log.INFO))
            _log.info(toString() + ": Startup() called, was already alive? " + _alive, new Exception());
        _alive = true;
        super.refreshLeaseSet();
    }
    
    @Override
    synchronized void shutdown() {
        if (_log.shouldLog(Log.WARN))
            _log.warn(toString() + ": Shutdown called");
        _alive = false;
    }
    
    @Override
    TunnelInfo selectTunnel() {
        return _aliasOf.selectTunnel();
    }

    @Override
    TunnelInfo selectTunnel(Hash closestTo) {
        return _aliasOf.selectTunnel(closestTo);
    }
    
    @Override
    public TunnelInfo getTunnel(TunnelId gatewayId) {
        return _aliasOf.getTunnel(gatewayId);
    }
    
    @Override
    public List<TunnelInfo> listTunnels() {
        return _aliasOf.listTunnels();
    }
    
    @Override
    boolean needFallback() {
        return false;
    }

    @Override
    public List<PooledTunnelCreatorConfig> listPending() {
        return _aliasOf.listPending();
    }
    
    @Override
    public boolean isAlive() {
        return _alive && _aliasOf.isAlive();
    }

    @Override
    public int size() { 
        return _aliasOf.size();
    }
    
    @Override
    void addTunnel(TunnelInfo info) {
        _aliasOf.addTunnel(info);
    }
    
    @Override
    void removeTunnel(TunnelInfo info) {
        _aliasOf.removeTunnel(info);
    }

    @Override
    void tunnelFailed(TunnelInfo cfg) {
        _aliasOf.tunnelFailed(cfg);
    }

    @Override
    void tunnelFailed(TunnelInfo cfg, Hash blamePeer) {
        _aliasOf.tunnelFailed(cfg, blamePeer);
    }

    @Override
    void refreshLeaseSet() {}

    @Override
    boolean buildFallback() {
        return _aliasOf.buildFallback();
    }

    @Override
    protected LeaseSet locked_buildNewLeaseSet() {
        LeaseSet ls =  _context.netDb().lookupLeaseSetLocally(_aliasOf.getSettings().getDestination());
        if (ls == null)
            return null;
        // copy everything so it isn't corrupted
        LeaseSet rv = new LeaseSet();
        for (int i = 0; i < ls.getLeaseCount(); i++) {
            Lease old = ls.getLease(i);
            Lease lease = new Lease();
            lease.setEndDate(old.getEndDate());
            lease.setTunnelId(old.getTunnelId());
            lease.setGateway(old.getGateway());
            rv.addLease(lease);
        }
        return rv;
    }

    @Override
    public long getLifetimeProcessed() {
        return _aliasOf.getLifetimeProcessed();
    }

    @Override
    int countHowManyToBuild() {
        return 0;
    }
    
    @Override
    PooledTunnelCreatorConfig configureNewTunnel() {
        return null;
    }

    @Override
    void buildComplete(PooledTunnelCreatorConfig cfg) {}
    
    @Override
    public String toString() {
        return "Aliased " + super.toString();
    }
}
