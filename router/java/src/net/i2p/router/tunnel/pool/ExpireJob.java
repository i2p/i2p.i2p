package net.i2p.router.tunnel.pool;

import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

class ExpireJob extends JobImpl {
    private TunnelPool _pool;
    private TunnelCreatorConfig _cfg;
    private boolean _leaseUpdated;
    public ExpireJob(RouterContext ctx, TunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _pool = pool;
        _cfg = cfg;
        _leaseUpdated = false;
        // give 'em some extra time before dropping 'em
        getTiming().setStartAfter(cfg.getExpiration()); // + Router.CLOCK_FUDGE_FACTOR);
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
            requeue(Router.CLOCK_FUDGE_FACTOR);
        } else {
            // already removed/refreshed, but now lets make it
            // so we dont even honor the tunnel anymore
            getContext().tunnelDispatcher().remove(_cfg);
        }
    }
}
