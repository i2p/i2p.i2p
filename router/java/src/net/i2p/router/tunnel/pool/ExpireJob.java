package net.i2p.router.tunnel.pool;

import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 *  This runs twice for each tunnel.
 *  The first time, remove it from the LeaseSet.
 *  The second time, stop accepting data for it.
 */
class ExpireJob extends JobImpl {
    private final TunnelPool _pool;
    private final TunnelCreatorConfig _cfg;
    private boolean _leaseUpdated;
    private final long _dropAfter;

    private static final long OB_EARLY_EXPIRE = 30*1000;
    private static final long IB_EARLY_EXPIRE = OB_EARLY_EXPIRE + 7500;

    public ExpireJob(RouterContext ctx, TunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _pool = pool;
        _cfg = cfg;
        // we act as if this tunnel expires a random skew before it actually does
        // so we rebuild out of sync.  otoh, we will honor tunnel messages on it
        // up through the full lifetime of the tunnel, plus a clock skew, since
        // others may be sending to the published lease expirations
        // Also skew the inbound away from the outbound
        long expire = cfg.getExpiration();
        _dropAfter = expire + Router.CLOCK_FUDGE_FACTOR;
        if (_pool.getSettings().isInbound())
            expire -= IB_EARLY_EXPIRE + ctx.random().nextLong(IB_EARLY_EXPIRE);
        else
            expire -= OB_EARLY_EXPIRE + ctx.random().nextLong(OB_EARLY_EXPIRE);
        // See comments in TunnelPool.locked_buildNewLeaseSet
        cfg.setExpiration(expire);
        getTiming().setStartAfter(expire);
    }

    public String getName() {
        return "Expire our tunnel";
    }

    public void runJob() {
        if (!_leaseUpdated) {
            _pool.removeTunnel(_cfg);
            _leaseUpdated = true;
            // noop for outbound
            _pool.refreshLeaseSet();
            long timeToDrop = _dropAfter - getContext().clock().now();
            requeue(timeToDrop);
        } else {
            // already removed/refreshed, but now lets make it
            // so we dont even honor the tunnel anymore
            getContext().tunnelDispatcher().remove(_cfg);
        }
    }
}
