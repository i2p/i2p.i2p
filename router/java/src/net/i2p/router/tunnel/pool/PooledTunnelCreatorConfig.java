package net.i2p.router.tunnel.pool;

import java.util.*;
import net.i2p.data.Hash;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Log;

/**
 *
 */
public class PooledTunnelCreatorConfig extends TunnelCreatorConfig {
    private TunnelPool _pool;
    private TestJob _testJob;
    private Job _expireJob;
    private TunnelInfo _pairedTunnel;
    
    /** Creates a new instance of PooledTunnelCreatorConfig */
    
    public PooledTunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound) {
        this(ctx, length, isInbound, null);
    }
    public PooledTunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound, Hash destination) {
        super(ctx, length, isInbound, destination);
        _pool = null;
    }
    
    public void testSuccessful(int ms) {
        if (_testJob != null)
            _testJob.testSuccessful(ms);
        super.testSuccessful(ms);
    }
    
    /**
     * The tunnel failed, so stop using it
     */
    public boolean tunnelFailed() {
        boolean rv = super.tunnelFailed();
        if (!rv) {
            // remove us from the pool (but not the dispatcher) so that we aren't 
            // selected again.  _expireJob is left to do its thing, in case there
            // are any straggling messages coming down the tunnel
            _pool.tunnelFailed(this);
            if (_testJob != null) // just in case...
                _context.jobQueue().removeJob(_testJob);
        }
        return rv;
    }
    
    public Properties getOptions() {
        if (_pool == null) return null;
        return _pool.getSettings().getUnknownOptions();
    }
    
    public void setTunnelPool(TunnelPool pool) {
        if (pool != null) {
            _pool = pool; 
        } else {
            Log log = _context.logManager().getLog(getClass());
            log.error("Null tunnel pool?", new Exception("foo"));
        }
    }
    public TunnelPool getTunnelPool() { return _pool; }
    
    public void setTestJob(TestJob job) { _testJob = job; }
    public void setExpireJob(Job job) { _expireJob = job; }
    
    public void setPairedTunnel(TunnelInfo tunnel) { _pairedTunnel = tunnel; }
    public TunnelInfo getPairedTunnel() { return _pairedTunnel; }
}
