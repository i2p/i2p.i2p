package net.i2p.router.tunnel.pool;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Log;

/**
 * The tunnel is fully built, so now add it to our handler, to the pool, and
 * build the necessary test and rebuilding jobs.
 *
 */
class OnCreatedJob extends JobImpl {
    private Log _log;
    private TunnelPool _pool;
    private PooledTunnelCreatorConfig _cfg;
    
    public OnCreatedJob(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg) {
        super(ctx);
        _log = ctx.logManager().getLog(OnCreatedJob.class);
        _pool = pool;
        _cfg = cfg;
    }
    public String getName() { return "Tunnel built"; }
    public void runJob() {
        _log.debug("Created successfully: " + _cfg);
        if (_cfg.isInbound()) {
            getContext().tunnelDispatcher().joinInbound(_cfg);
        } else {
            getContext().tunnelDispatcher().joinOutbound(_cfg);
        }
        
        _pool.getManager().buildComplete();
        _pool.addTunnel(_cfg);
        TestJob testJob = (_cfg.getLength() > 1 ? new TestJob(getContext(), _cfg, _pool) : null);
        RebuildJob rebuildJob = new RebuildJob(getContext(), _cfg, _pool);
        ExpireJob expireJob = new ExpireJob(getContext(), _cfg, _pool);
        _cfg.setTunnelPool(_pool);
        _cfg.setTestJob(testJob);
        _cfg.setRebuildJob(rebuildJob);
        _cfg.setExpireJob(expireJob);
        if (_cfg.getLength() > 1) // no need to test 0 hop tunnels
            getContext().jobQueue().addJob(testJob);
        getContext().jobQueue().addJob(rebuildJob); // always try to rebuild (ignored if too many)
        getContext().jobQueue().addJob(expireJob);
    }
}