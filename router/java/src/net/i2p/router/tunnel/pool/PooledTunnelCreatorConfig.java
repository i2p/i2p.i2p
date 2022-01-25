package net.i2p.router.tunnel.pool;

import java.util.Properties;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 *  Data about a tunnel we created
 */
public class PooledTunnelCreatorConfig extends TunnelCreatorConfig {
    private final TunnelPool _pool;
    // we don't store the config, that leads to OOM
    private TunnelId _pairedGW;

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
     * We failed to contact the first hop for an outbound tunnel,
     * so immediately stop using it.
     * For outbound non-zero-hop tunnels only.
     *
     * @since 0.9.53
     */
    public void tunnelFailedFirstHop() {
        if (isInbound() || getLength() <= 1)
            return;
        tunnelFailedCompletely();
        _pool.tunnelFailed(this, getPeer(1));
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

    /**
     *  The ID of the gateway of the paired tunnel used to send/receive the build request
     *
     *  @param gw for paired inbound, the GW rcv tunnel ID; for paired outbound, the GW send tunnel ID.
     *  @since 0.9.53
     */
    public void setPairedGW(TunnelId gw) { _pairedGW = gw; }


    /**
     *  The ID of the gateway of the paired tunnel used to send/receive the build request
     *
     *  @return for paired inbound, the GW rcv tunnel ID; for paired outbound, the GW send tunnel ID.
     *          null if not previously set
     *  @since 0.9.53
     */
    public TunnelId getPairedGW() { return _pairedGW; }
}
