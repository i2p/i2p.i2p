package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
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
    public void buildTunnel(RouterContext ctx, TunnelPool pool) {
        buildTunnel(ctx, pool, false);
    }
    public void buildTunnel(RouterContext ctx, TunnelPool pool, boolean zeroHop) {
        if (!pool.isAlive()) return;
        // this is probably overkill (ya think?)
        pool.refreshSettings();
        
        PooledTunnelCreatorConfig cfg = configTunnel(ctx, pool, zeroHop);
        if (cfg == null) {
            RetryJob j = new RetryJob(ctx, pool);
            j.getTiming().setStartAfter(ctx.clock().now() + ctx.random().nextInt(30*1000));
            ctx.jobQueue().addJob(j);
            return;
        }
        OnCreatedJob onCreated = new OnCreatedJob(ctx, pool, cfg);
        RetryJob onFailed= (zeroHop ? null : new RetryJob(ctx, pool));
        // queue up a job to request the endpoint to join the tunnel, which then
        // requeues up another job for earlier hops, etc, until it reaches the 
        // gateway.  after the gateway is confirmed, onCreated is fired
        RequestTunnelJob req = new RequestTunnelJob(ctx, cfg, onCreated, onFailed, cfg.getLength()-1, zeroHop, pool.getSettings().isExploratory());
        if (zeroHop || (cfg.getLength() <= 1) ) // lets get it done inline, as we /need/ it asap
            req.runJob();
        else
            ctx.jobQueue().addJob(req);
    }
    
    private PooledTunnelCreatorConfig configTunnel(RouterContext ctx, TunnelPool pool, boolean zeroHop) {
        Log log = ctx.logManager().getLog(TunnelBuilder.class);
        TunnelPoolSettings settings = pool.getSettings();
        long expiration = ctx.clock().now() + settings.getDuration();
        List peers = null;
        
        if (zeroHop) {
            peers = new ArrayList(1);
            peers.add(ctx.routerHash());
            if (log.shouldLog(Log.WARN))
                log.warn("Building failsafe tunnel for " + pool);
        } else {
            peers = pool.getSelector().selectPeers(ctx, settings);
        }
        if ( (peers == null) || (peers.size() <= 0) ) {
            // no inbound or outbound tunnels to send the request through, and 
            // the pool is refusing 0 hop tunnels
            if (peers == null) {
                if (log.shouldLog(Log.ERROR))
                    log.error("No peers to put in the new tunnel! selectPeers returned null!  boo, hiss!  fake=" + zeroHop);
            } else {
                if (log.shouldLog(Log.ERROR))
                    log.error("No peers to put in the new tunnel! selectPeers returned an empty list?!  fake=" + zeroHop);
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
        public RetryJob(RouterContext ctx, TunnelPool pool) {
            super(ctx);
            _pool = pool;
        }
        public String getName() { return "Tunnel create failed"; }
        public void runJob() {
            // yikes, nothing left, lets get some backup (if we're allowed)
            _pool.refreshBuilders();
        }
    }
}
