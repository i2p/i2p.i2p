package net.i2p.router.tunnel.pool;

import java.util.Properties;
import net.i2p.data.Hash;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 *
 */
public class PooledTunnelCreatorConfig extends TunnelCreatorConfig {
    private TunnelPool _pool;
    private boolean _failed;
    private TestJob _testJob;
    private RebuildJob _rebuildJob;
    private Job _expireJob;
    
    /** Creates a new instance of PooledTunnelCreatorConfig */
    
    public PooledTunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound) {
        this(ctx, length, isInbound, null);
    }
    public PooledTunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound, Hash destination) {
        super(ctx, length, isInbound, destination);
        _failed = false;
        _pool = null;
    }
    
    
    public void testSuccessful(int ms) {
        if (_testJob != null) {
            _testJob.testSuccessful(ms);
        }
    }
    
    public Properties getOptions() {
        if (_pool == null) return null;
        return _pool.getSettings().getUnknownOptions();
    }
    
    /**
     * The tunnel failed, so stop using it
     */
    public void tunnelFailed() {
        _failed = true;
        // remove us from the pool (but not the dispatcher) so that we aren't 
        // selected again.  _expireJob is left to do its thing, in case there
        // are any straggling messages coming down the tunnel
        _pool.tunnelFailed(this);
        if (_rebuildJob != null) {
            // rebuild asap (_rebuildJob will be null if we were just a stopgap)
            _rebuildJob.getTiming().setStartAfter(_context.clock().now() + 10*1000);
            _context.jobQueue().addJob(_rebuildJob);
        }
        if (_testJob != null) // just in case...
            _context.jobQueue().removeJob(_testJob);
    }
    public boolean getTunnelFailed() { return _failed; }
    public void setTunnelPool(TunnelPool pool) { _pool = pool; }
    public TunnelPool getTunnelPool() { return _pool; }
    
    public void setTestJob(TestJob job) { _testJob = job; }
    public void setRebuildJob(RebuildJob job) { _rebuildJob = job; }
    public void setExpireJob(Job job) { _expireJob = job; }
}
