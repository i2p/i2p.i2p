package net.i2p.router.tunnel.pool;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Build a new tunnel to replace the existing one before it expires.  This job
 * should be removed (or scheduled to run immediately) if the tunnel fails.
 * If an exploratory tunnel build at a random time between 3 1/2 and 4 minutes early;
 * else if only one tunnel in pool build 4 minutes early;
 * otherwise build at a random time between 2 and 4 minutes early.
 * Five build attempts in parallel if an exploratory tunnel.
 */
class RebuildJob extends JobImpl {
    private TunnelPool _pool;
    private TunnelCreatorConfig _cfg;
    
    public RebuildJob(RouterContext ctx, TunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _pool = pool;
        _cfg = cfg;
        long rebuildOn;
        if (_pool.getSettings().isExploratory()) {
            rebuildOn = cfg.getExpiration() - (((pool.getSettings().getRebuildPeriod() * 7) / 2));
            rebuildOn -= ctx.random().nextInt(pool.getSettings().getRebuildPeriod() / 2);
        } else if ((pool.getSettings().getQuantity() + pool.getSettings().getBackupQuantity()) == 1) {
            rebuildOn = cfg.getExpiration() - (pool.getSettings().getRebuildPeriod() * 4);
        } else {
            rebuildOn = cfg.getExpiration() - (pool.getSettings().getRebuildPeriod() * 2);
            rebuildOn -= ctx.random().nextInt(pool.getSettings().getRebuildPeriod() * 2);
        }
        getTiming().setStartAfter(rebuildOn);
    }
    public String getName() {
        if (_pool.getSettings().isExploratory()) {
            if (_pool.getSettings().isInbound()) {
                return "Rebuild exploratory inbound tunnel";
            } else {
                return "Rebuild exploratory outbound tunnel";
            }
        } else {
            StringBuffer rv = new StringBuffer(32);
            if (_pool.getSettings().isInbound())
                rv.append("Rebuild inbound client tunnel for ");
            else
                rv.append("Rebuild outbound client tunnel for ");
            if (_pool.getSettings().getDestinationNickname() != null)
                rv.append(_pool.getSettings().getDestinationNickname());
            else
                rv.append(_pool.getSettings().getDestination().toBase64().substring(0,4));
            return rv.toString();
        }
    }
    public void runJob() {
        if (_pool.getSettings().isExploratory())
            _pool.refreshBuilders(4, 4);
        else
            _pool.refreshBuilders(1, 4);
    }
}
