package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.router.RouterContext;
import net.i2p.router.JobImpl;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.router.tunnel.TunnelGateway;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Log;

/**
 *
 */
public class TunnelBuilder {
    /**
     * Build a new tunnel per the pool's wishes (using the preferred length,
     * peers, ordering, etc).  After the tunnel is built, it is added to the 
     * pool as well as the dispatcher, and the necessary test and maintenance
     * jobs are built.  This call does not block.
     *
     */
    public void buildTunnel(RouterContext ctx, TunnelPool pool, Object poolToken) {
        buildTunnel(ctx, pool, false, poolToken);
    }
    public void buildTunnel(RouterContext ctx, TunnelPool pool, boolean fake, Object poolToken) {
        if (!pool.keepBuilding(poolToken))
            return;
        
        // this is probably overkill (ya think?)
        pool.refreshSettings();
        
        PooledTunnelCreatorConfig cfg = configTunnel(ctx, pool, fake);
        if ( (cfg == null) && (!fake) ) {
            RetryJob j = new RetryJob(ctx, pool, poolToken);
            j.getTiming().setStartAfter(ctx.clock().now() + ctx.random().nextInt(30*1000));
            ctx.jobQueue().addJob(j);
            return;
        }
        OnCreatedJob onCreated = new OnCreatedJob(ctx, pool, cfg, fake, poolToken);
        RetryJob onFailed= (fake ? null : new RetryJob(ctx, pool, poolToken));
        // queue up a job to request the endpoint to join the tunnel, which then
        // requeues up another job for earlier hops, etc, until it reaches the 
        // gateway.  after the gateway is confirmed, onCreated is fired
        RequestTunnelJob req = new RequestTunnelJob(ctx, cfg, onCreated, onFailed, cfg.getLength()-1, fake);
        if (fake) // lets get it done inline, as we /need/ it asap
            req.runJob();
        else
            ctx.jobQueue().addJob(req);
    }
    
    private PooledTunnelCreatorConfig configTunnel(RouterContext ctx, TunnelPool pool, boolean fake) {
        Log log = ctx.logManager().getLog(TunnelBuilder.class);
        TunnelPoolSettings settings = pool.getSettings();
        long expiration = ctx.clock().now() + settings.getDuration();
        List peers = null;
        if (fake) {
            peers = new ArrayList(1);
            peers.add(ctx.routerHash());
        } else {
            peers = pool.getSelector().selectPeers(ctx, settings);
        }
        if ( (peers == null) || (peers.size() <= 0) ) {
            // no inbound or outbound tunnels to send the request through, and 
            // the pool is refusing 0 hop tunnels
            if (peers == null) {
                if (log.shouldLog(Log.ERROR))
                    log.error("No peers to put in the new tunnel! selectPeers returned null!  boo, hiss!  fake=" + fake);
            } else {
                if (log.shouldLog(Log.ERROR))
                    log.error("No peers to put in the new tunnel! selectPeers returned an empty list?!  fake=" + fake);
            }
            return null;
        }
        
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(ctx, peers.size(), settings.isInbound(), settings.getDestination());
        // peers[] is ordered endpoint first, but cfg.getPeer() is ordered gateway first
        for (int i = 0; i < peers.size(); i++) {
            int j = peers.size() - 1 - i;
            cfg.setPeer(j, (Hash)peers.get(i));
            HopConfig hop = cfg.getConfig(j);
            hop.setExpiration(expiration);
            hop.setIVKey(ctx.keyGenerator().generateSessionKey());
            hop.setLayerKey(ctx.keyGenerator().generateSessionKey());
            // tunnelIds will be updated during building, and as the creator, we
            // don't need to worry about prev/next hop
        }
        cfg.setExpiration(expiration);
        
        Log l = ctx.logManager().getLog(TunnelBuilder.class);
        if (l.shouldLog(Log.DEBUG))
            l.debug("Config contains " + peers + ": " + cfg);
        return cfg;
    }

    /** 
     * If the building fails, try, try again.
     *
     */
    private class RetryJob extends JobImpl {
        private TunnelPool _pool;
        private Object _buildToken;
        public RetryJob(RouterContext ctx, TunnelPool pool, Object buildToken) {
            super(ctx);
            _pool = pool;
            _buildToken = buildToken;
        }
        public String getName() { return "tunnel create failed"; }
        public void runJob() {
            // yikes, nothing left, lets get some backup (if we're allowed)
            if ( (_pool.selectTunnel() == null) && (_pool.getSettings().getAllowZeroHop()) )
                _pool.buildFake();
            
            buildTunnel(getContext(), _pool, _buildToken);
        }
    }
}
