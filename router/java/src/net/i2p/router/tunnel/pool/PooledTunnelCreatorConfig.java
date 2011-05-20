package net.i2p.router.tunnel.pool;

import java.util.Properties;

import net.i2p.data.Hash;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Log;

/**
 *
 */
class PooledTunnelCreatorConfig extends TunnelCreatorConfig {
    private TunnelPool _pool;
    private TestJob _testJob;
    // private Job _expireJob;
    // private TunnelInfo _pairedTunnel;
    private boolean _live;
    
    /** Creates a new instance of PooledTunnelCreatorConfig */
    
    public PooledTunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound) {
        this(ctx, length, isInbound, null);
    }
    public PooledTunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound, Hash destination) {
        super(ctx, length, isInbound, destination);
    }
    
    // calls TestJob
    @Override
    public void testSuccessful(int ms) {
        if (_testJob != null)
            _testJob.testSuccessful(ms);
        super.testSuccessful(ms);
        _live = true;
    }
    
    // called from TestJob
    public void testJobSuccessful(int ms) {
        super.testSuccessful(ms);
        _live = true;
    }
    
    /**
     * The tunnel failed a test, so (maybe) stop using it
     */
    @Override
    public boolean tunnelFailed() {
        boolean rv = super.tunnelFailed();
        if (!rv) {
            // remove us from the pool (but not the dispatcher) so that we aren't 
            // selected again.  _expireJob is left to do its thing, in case there
            // are any straggling messages coming down the tunnel
            //
            // Todo: Maybe delay or prevent failing if we are near tunnel build capacity,
            // to prevent collapse (loss of all tunnels)
            _pool.tunnelFailed(this);
            if (_testJob != null) // just in case...
                _context.jobQueue().removeJob(_testJob);
        }
        return rv;
    }
    
    @Override
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
    

    /** @deprecated unused, which makes _testJob unused - why is it here */
    void setTestJob(TestJob job) { _testJob = job; }
    /** does nothing, to be deprecated */
    public void setExpireJob(Job job) { /* _expireJob = job; */ }
    
    /**
     * @deprecated Fix memory leaks caused by references if you need to use pairedTunnel
     */
    public void setPairedTunnel(TunnelInfo tunnel) { /* _pairedTunnel = tunnel; */}
    // public TunnelInfo getPairedTunnel() { return _pairedTunnel; }
}
