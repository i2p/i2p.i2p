package net.i2p.router.tunnel.pool;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Build a new tunnel to replace the existing one before it expires.  This job
 * should be removed (or scheduled to run immediately) if the tunnel fails.
 *
 */
class RebuildJob extends JobImpl {
    private TunnelPool _pool;
    private TunnelCreatorConfig _cfg;
    
    public RebuildJob(RouterContext ctx, TunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _pool = pool;
        _cfg = cfg;
        long rebuildOn = cfg.getExpiration() - pool.getSettings().getRebuildPeriod();
        rebuildOn -= ctx.random().nextInt(pool.getSettings().getRebuildPeriod()*2);
        getTiming().setStartAfter(rebuildOn);
    }
    public String getName() { return "Rebuild tunnel"; }
    public void runJob() {
        _pool.getBuilder().buildTunnel(getContext(), _pool);
    }
}