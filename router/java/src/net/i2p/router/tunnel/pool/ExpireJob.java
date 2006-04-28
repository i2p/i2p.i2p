package net.i2p.router.tunnel.pool;

import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

class ExpireJob extends JobImpl {
    private TunnelPool _pool;
    private TunnelCreatorConfig _cfg;
    private boolean _leaseUpdated;
    private long _dropAfter;
    public ExpireJob(RouterContext ctx, TunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _pool = pool;
        _cfg = cfg;
        _leaseUpdated = false;
        // we act as if this tunnel expires a random skew before it actually does
        // so we rebuild out of sync.  otoh, we will honor tunnel messages on it
        // up through the full lifetime of the tunnel, plus a clock skew, since
        // others may be sending to the published lease expirations
        long expire = cfg.getExpiration();
        _dropAfter = expire + Router.CLOCK_FUDGE_FACTOR;
        expire -= ctx.random().nextLong(5*60*1000);
        cfg.setExpiration(expire);
        getTiming().setStartAfter(expire);
    }
    public String getName() {
        if (_pool.getSettings().isExploratory()) {
            if (_pool.getSettings().isInbound()) {
                return "Expire exploratory inbound tunnel";
            } else {
                return "Expire exploratory outbound tunnel";
            }
        } else {
            StringBuffer rv = new StringBuffer(32);
            if (_pool.getSettings().isInbound())
                rv.append("Expire inbound client tunnel for ");
            else
                rv.append("Expire outbound client tunnel for ");
            if (_pool.getSettings().getDestinationNickname() != null)
                rv.append(_pool.getSettings().getDestinationNickname());
            else
                rv.append(_pool.getSettings().getDestination().toBase64().substring(0,4));
            return rv.toString();
        }
    }
    public void runJob() {
        if (!_leaseUpdated) {
            _pool.removeTunnel(_cfg);
            _leaseUpdated = true;
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
