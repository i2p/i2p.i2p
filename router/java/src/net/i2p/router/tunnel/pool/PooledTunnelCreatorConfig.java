package net.i2p.router.tunnel.pool;

import java.util.Properties;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 *  Data about a tunnel we created
 */
class PooledTunnelCreatorConfig extends TunnelCreatorConfig {
    private final TunnelPool _pool;

    /**
     *  Creates a new instance of PooledTunnelCreatorConfig
     *
     *  @param destination may be null
     *  @param pool non-null
     */
    public PooledTunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound,
                                     Hash destination, TunnelPool pool) {
        super(ctx, length, isInbound, destination);
        _pool = pool; 
    }
    
    /** called from TestJob */
    public void testJobSuccessful(int ms) {
        testSuccessful(ms);
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
        }
        return rv;
    }
    
    /**
     *  @return non-null
     */
    @Override
    public Properties getOptions() {
        return _pool.getSettings().getUnknownOptions();
    }
    
    /**
     *  @return non-null
     */
    public TunnelPool getTunnelPool() { return _pool; }
}
