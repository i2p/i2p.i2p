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
    private Object _buildToken;
    private PooledTunnelCreatorConfig _cfg;
    private boolean _fake;
    
    public OnCreatedJob(RouterContext ctx, TunnelPool pool, PooledTunnelCreatorConfig cfg, boolean fake, Object buildToken) {
        super(ctx);
        _log = ctx.logManager().getLog(OnCreatedJob.class);
        _pool = pool;
        _cfg = cfg;
        _fake = fake;
        _buildToken = buildToken;
    }
    public String getName() { return "Tunnel built"; }
    public void runJob() {
        _log.debug("Created successfully: " + _cfg);
        if (_cfg.isInbound()) {
            getContext().tunnelDispatcher().joinInbound(_cfg);
        } else {
            getContext().tunnelDispatcher().joinOutbound(_cfg);
        }
        _pool.addTunnel(_cfg);
        TestJob testJob = (_cfg.getLength() > 1 ? new TestJob(getContext(), _cfg, _pool, _buildToken) : null);
        RebuildJob rebuildJob = (_fake ? null : new RebuildJob(getContext(), _cfg, _pool, _buildToken));
        ExpireJob expireJob = new ExpireJob(getContext(), _cfg, _pool, _buildToken);
        _cfg.setTunnelPool(_pool);
        _cfg.setTestJob(testJob);
        _cfg.setRebuildJob(rebuildJob);
        _cfg.setExpireJob(expireJob);
        if (_cfg.getLength() > 1) // no need to test 0 hop tunnels
            getContext().jobQueue().addJob(testJob);
        if (!_fake) // if we built a 0 hop tunnel in response to a failure, don't rebuild
            getContext().jobQueue().addJob(rebuildJob);
        getContext().jobQueue().addJob(expireJob);
    }
}