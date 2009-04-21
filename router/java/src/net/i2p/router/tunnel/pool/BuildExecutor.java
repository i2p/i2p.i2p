package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;

/**
 * Single threaded controller of the tunnel creation process, spanning all tunnel pools.
 * Essentially, this loops across the pools, sees which want to build tunnels, and fires 
 * off the necessary activities if the load allows.  If nothing wants to build any tunnels,
 * it waits for a short period before looping again (or until it is told that something
 * changed, such as a tunnel failed, new client started up, or tunnel creation was aborted).
 *
 */
class BuildExecutor implements Runnable {
    private final List _recentBuildIds = new ArrayList(100);
    private RouterContext _context;
    private Log _log;
    private TunnelPoolManager _manager;
    /** list of TunnelCreatorConfig elements of tunnels currently being built */
    private final List _currentlyBuilding;
    private boolean _isRunning;
    private BuildHandler _handler;
    private boolean _repoll;

    public BuildExecutor(RouterContext ctx, TunnelPoolManager mgr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = mgr;
        _currentlyBuilding = new ArrayList(10);
        _context.statManager().createRateStat("tunnel.concurrentBuilds", "How many builds are going at once", "Tunnels", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.concurrentBuildsLagged", "How many builds are going at once when we reject further builds, due to job lag (period is lag)", "Tunnels", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.buildExploratoryExpire", "How often an exploratory tunnel times out during creation", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.buildClientExpire", "How often a client tunnel times out during creation", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.buildExploratorySuccess", "Response time for success", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.buildClientSuccess", "Response time for success", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.buildExploratoryReject", "Response time for rejection", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.buildClientReject", "Response time for rejection", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.buildRequestTime", "How long it takes to build a tunnel request", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildRequestZeroHopTime", "How long it takes to build a zero hop tunnel", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.pendingRemaining", "How many inbound requests are pending after a pass (period is how long the pass takes)?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildFailFirstHop", "How often we fail to build a OB tunnel because we can't contact the first hop", "Tunnels", new long[] { 60*1000, 10*60*1000 });

        // Get stat manager, get recognized bandwidth tiers
        StatManager statMgr = _context.statManager();
        String bwTiers = _context.router().getRouterInfo().BW_CAPABILITY_CHARS; // LINT -- Accessing static field "BW_CAPABILITY_CHARS"
        // For each bandwidth tier, create tunnel build agree/reject/expire stats
        for (int i = 0; i < bwTiers.length(); i++) {
            String bwTier = String.valueOf(bwTiers.charAt(i));
            statMgr.createRateStat("tunnel.tierAgree" + bwTier, "Agreed joins from " + bwTier, "Tunnels", new long[] { 60*1000, 10*60*1000 });
            statMgr.createRateStat("tunnel.tierReject" + bwTier, "Rejected joins from "+ bwTier, "Tunnels", new long[] { 60*1000, 10*60*1000 });
            statMgr.createRateStat("tunnel.tierExpire" + bwTier, "Expired joins from "+ bwTier, "Tunnels", new long[] { 60*1000, 10*60*1000 });
        }
        // For caution, also create stats for unknown
        statMgr.createRateStat("tunnel.tierAgreeUnknown", "Agreed joins from unknown", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        statMgr.createRateStat("tunnel.tierRejectUnknown", "Rejected joins from unknown", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        statMgr.createRateStat("tunnel.tierExpireUnknown", "Expired joins from unknown", "Tunnels", new long[] { 60*1000, 10*60*1000 });

        _repoll = false;
        _handler = new BuildHandler(ctx, this);
    }
    
    private int allowed() {
        int maxKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        int allowed = maxKBps / 6; // Max. 1 concurrent build per 6 KB/s outbound
        if (allowed < 2) allowed = 2; // Never choke below 2 builds (but congestion may)
        if (allowed > 10) allowed = 10; // Never go beyond 10, that is uncharted territory (old limit was 5)
        allowed = _context.getProperty("router.tunnelConcurrentBuilds", allowed);

        List expired = null;
        int concurrent = 0;
        long expireBefore = _context.clock().now() + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT;
        synchronized (_currentlyBuilding) {
            // expire any old requests
            for (int i = 0; i < _currentlyBuilding.size(); i++) {
                TunnelCreatorConfig cfg = (TunnelCreatorConfig)_currentlyBuilding.get(i);
                if (cfg.getExpiration() <= expireBefore) {
                    _currentlyBuilding.remove(i);
                    i--;
                    if (expired == null)
                        expired = new ArrayList();
                    expired.add(cfg);
                }
            }
            concurrent = _currentlyBuilding.size();
            allowed -= concurrent;
        }
        
        if (expired != null) {
            for (int i = 0; i < expired.size(); i++) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig)expired.get(i);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Timed out waiting for reply asking for " + cfg);

                // Iterate through peers in the tunnel, get their bandwidth tiers,
                // record for each that a peer of the given tier expired
                // Also note the fact that this tunnel request timed out in the peers' profiles.
                for (int iPeer = 0; iPeer < cfg.getLength(); iPeer++) {
                    // Look up peer
                    Hash peer = cfg.getPeer(iPeer);
                    // Avoid recording ourselves
                    if (peer.toBase64().equals(_context.routerHash().toBase64()))
                        continue;
                    // Look up routerInfo
                    RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
                    // Default and detect bandwidth tier
                    String bwTier = "Unknown";
                    if (ri != null) bwTier = ri.getBandwidthTier(); // Returns "Unknown" if none recognized
                    // Record that a peer of the given tier expired
                    _context.statManager().addRateData("tunnel.tierExpire" + bwTier, 1, 0);
                    didNotReply(cfg.getReplyMessageId(), peer);
                    // Blame everybody since we don't know whose fault it is.
                    // (it could be our exploratory tunnel's fault too...)
                    _context.profileManager().tunnelTimedOut(peer);
                }

                TunnelPool pool = cfg.getTunnelPool();
                if (pool != null)
                    pool.buildComplete(cfg);
                if (cfg.getDestination() == null)
                    _context.statManager().addRateData("tunnel.buildExploratoryExpire", 1, 0);
                else
                    _context.statManager().addRateData("tunnel.buildClientExpire", 1, 0);
            }
        }
        
        _context.statManager().addRateData("tunnel.concurrentBuilds", concurrent, 0);
        
        long lag = _context.jobQueue().getMaxLag();
        if ( (lag > 2000) && (_context.router().getUptime() > 5*60*1000) ) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too lagged [" + lag + "], don't allow building");
            _context.statManager().addRateData("tunnel.concurrentBuildsLagged", concurrent, lag);
            return 0; // if we have a job heavily blocking our jobqueue, ssllloowww dddooowwwnnn
        }
        
        // Trim the number of allowed tunnels for overload,
        // initiate a tunnel drop on severe overload
        // tunnel building is high priority, don't do this
        // allowed = trimForOverload(allowed,concurrent);

        return allowed;
    }


    // Estimated cost of tunnel build attempt, bytes
    // private static final int BUILD_BANDWIDTH_ESTIMATE_BYTES = 5*1024;

    /**
     * Don't even try to build tunnels if we're saturated
     */
/*
    private int trimForOverload(int allowed, int concurrent) {

        // dont include the inbound rates when throttling tunnel building, since
        // that'd expose a pretty trivial attack.
        int used1s = _context.router().get1sRate(true); // Avoid reliance on the 1s rate, too volatile
        int used15s = _context.router().get15sRate(true);
        int used1m = _context.router().get1mRate(true); // Avoid reliance on the 1m rate, too slow

        int maxKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        int maxBps = maxKBps * 1024;
        int overBuildLimit = maxBps - BUILD_BANDWIDTH_ESTIMATE_BYTES; // Beyond this, refrain from building
        int nearBuildLimit = maxBps - (2*BUILD_BANDWIDTH_ESTIMATE_BYTES); // Beyond this, consider it close

        // Detect any fresh overload which could set back tunnel building
        if (Math.max(used1s,used15s) > overBuildLimit) {

            if (_log.shouldLog(Log.WARN))
                _log.warn("Overloaded, trouble building tunnels (maxKBps=" + maxKBps +
                           ", 1s=" + used1s + ", 15s=" + used15s + ", 1m=" + used1m + ")");

            // Detect serious overload
            if (((used1s > maxBps) && (used1s > used15s) && (used15s > nearBuildLimit)) ||
                ((used1s > maxBps) && (used15s > overBuildLimit)) ||
                ((used1s > overBuildLimit) && (used15s > overBuildLimit))) {

                if (_log.shouldLog(Log.WARN))
                    _log.warn("Serious overload, allow building 0.");

               // If so configured, drop biggest participating tunnel
               if (Boolean.valueOf(_context.getProperty("router.dropTunnelsOnOverload","false")).booleanValue() == true) {
                   if (_log.shouldLog(Log.WARN))
                       _log.warn("Requesting drop of biggest participating tunnel.");
                   _context.tunnelDispatcher().dropBiggestParticipating();
               }
               return(0);
            } else {
                // Mild overload, check if we already build tunnels
                if (concurrent == 0) {
                    // We aren't building, allow 1
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Mild overload, allow building 1.");
                    return(1);
                } else {
                    // Already building, allow 0
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Mild overload but already building " + concurrent + ", so allow 0.");
                    return(0);
                }
            }
        }
        // No overload, allow as requested
        return(allowed);
    }
*/

    /** Set 1.5 * LOOP_TIME < BuildRequestor.REQUEST_TIMEOUT/4 - margin */
    private static final int LOOP_TIME = 1000;

    public void run() {
        _isRunning = true;
        List wanted = new ArrayList(8);
        List pools = new ArrayList(8);
        
        int pendingRemaining = 0;
        
        //long loopBegin = 0;
        //long beforeHandleInboundReplies = 0;
        //long afterHandleInboundReplies = 0;
        //long afterBuildZeroHop = 0;
        long afterBuildReal = 0;
        long afterHandleInbound = 0;
                
        while (!_manager.isShutdown()){
            //loopBegin = System.currentTimeMillis();
            try {
                _repoll = pendingRemaining > 0; // resets repoll to false unless there are inbound requeusts pending
                _manager.listPools(pools);
                for (int i = 0; i < pools.size(); i++) {
                    TunnelPool pool = (TunnelPool)pools.get(i);
                    if (!pool.isAlive())
                        continue;
                    int howMany = pool.countHowManyToBuild();
                    for (int j = 0; j < howMany; j++)
                        wanted.add(pool);
                }

                //beforeHandleInboundReplies = System.currentTimeMillis();
                _handler.handleInboundReplies();
                //afterHandleInboundReplies = System.currentTimeMillis();
                
                // allowed() also expires timed out requests (for new style requests)
                int allowed = allowed();
                
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Allowed: " + allowed + " wanted: " + wanted);

                // zero hop ones can run inline
                allowed = buildZeroHopTunnels(wanted, allowed);
                //afterBuildZeroHop = System.currentTimeMillis();
                
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Zero hops built, Allowed: " + allowed + " wanted: " + wanted);

                int realBuilt = 0;
                TunnelManagerFacade mgr = _context.tunnelManager();
                if ( (mgr == null) || (mgr.selectInboundTunnel() == null) || (mgr.selectOutboundTunnel() == null) ) {
                    // we don't have either inbound or outbound tunnels, so don't bother trying to build
                    // non-zero-hop tunnels
                    synchronized (_currentlyBuilding) {
                        if (!_repoll) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("No tunnel to build with (allowed=" + allowed + ", wanted=" + wanted.size() + ", pending=" + pendingRemaining + "), wait for a while");
                            _currentlyBuilding.wait(1*1000+_context.random().nextInt(1*1000));
                        }
                    }
                } else {
                    if ( (allowed > 0) && (wanted.size() > 0) ) {
                        Collections.shuffle(wanted, _context.random());
                        
                        // force the loops to be short, since 3 consecutive tunnel build requests can take
                        // a long, long time
                        if (allowed > 2)
                            allowed = 2;
                        
                        for (int i = 0; (i < allowed) && (wanted.size() > 0); i++) {
                            TunnelPool pool = (TunnelPool)wanted.remove(0);
                            //if (pool.countWantedTunnels() <= 0)
                            //    continue;
                            PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                            if (cfg != null) {
                                // 0hops are taken care of above, these are nonstandard 0hops
                                if (cfg.getLength() <= 1 && !pool.needFallback()) {
                                    if (_log.shouldLog(Log.DEBUG))
                                        _log.debug("We don't need more fallbacks for " + pool);
                                    i--; //0hop, we can keep going, as there's no worry about throttling
                                    pool.buildComplete(cfg);
                                    continue;
                                }
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("Configuring new tunnel " + i + " for " + pool + ": " + cfg);
                                synchronized (_currentlyBuilding) {
                                    _currentlyBuilding.add(cfg);
                                }
                                buildTunnel(pool, cfg);
                                realBuilt++;
                                
                                // we want replies to go to the top of the queue
                                _handler.handleInboundReplies();
                            } else {
                                i--;
                            }
                        }
                    }
                        // wait whether we built tunnels or not
                        try {
                            synchronized (_currentlyBuilding) {
                                if (!_repoll) {
                                    //if (_log.shouldLog(Log.DEBUG))
                                    //    _log.debug("Nothin' doin (allowed=" + allowed + ", wanted=" + wanted.size() + ", pending=" + pendingRemaining + "), wait for a while");
                                    //if (allowed <= 0)
                                        _currentlyBuilding.wait((LOOP_TIME/2) + _context.random().nextInt(LOOP_TIME));
                                    //else // wanted <= 0
                                    //    _currentlyBuilding.wait(_context.random().nextInt(30*1000));
                                }
                            }
                        } catch (InterruptedException ie) {
                            // someone wanted to build something
                        }
                }
                
                afterBuildReal = System.currentTimeMillis();
                
                pendingRemaining = _handler.handleInboundRequests();
                afterHandleInbound = System.currentTimeMillis();
                
                if (pendingRemaining > 0)
                    _context.statManager().addRateData("tunnel.pendingRemaining", pendingRemaining, afterHandleInbound-afterBuildReal);

                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("build loop complete, tot=" + (afterHandleInbound-loopBegin) + 
                //              " inReply=" + (afterHandleInboundReplies-beforeHandleInboundReplies) +
                //              " zeroHop=" + (afterBuildZeroHop-afterHandleInboundReplies) +
                //              " real=" + (afterBuildReal-afterBuildZeroHop) +
                //              " in=" + (afterHandleInbound-afterBuildReal) + 
                //              " built=" + realBuilt +
                //              " pending=" + pendingRemaining);
                
                wanted.clear();
                pools.clear();
            } catch (Exception e) {
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "B0rked in the tunnel builder", e);
            }
        }
        
        if (_log.shouldLog(Log.WARN))
            _log.warn("Done building");
        _isRunning = false;
    }
    
    /**
     * iterate over the 0hop tunnels, running them all inline regardless of how many are allowed
     * @return number of tunnels allowed after processing these zero hop tunnels (almost always the same as before)
     */
    private int buildZeroHopTunnels(List wanted, int allowed) {
        for (int i = 0; i < wanted.size(); i++) {
            TunnelPool pool = (TunnelPool)wanted.get(0);
            if (pool.getSettings().getLength() == 0) {
                PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                if (cfg != null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Configuring short tunnel " + i + " for " + pool + ": " + cfg);
                    synchronized (_currentlyBuilding) {
                        _currentlyBuilding.add(cfg);
                    }
                    buildTunnel(pool, cfg);
                    if (cfg.getLength() > 1) {
                        allowed--; // oops... shouldn't have done that, but hey, its not that bad...
                    }
                    wanted.remove(i);
                    i--;
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Configured a null tunnel");
                }
            }
        }
        return allowed;
    }
    
    public boolean isRunning() { return _isRunning; }
    
    void buildTunnel(TunnelPool pool, PooledTunnelCreatorConfig cfg) {
        long beforeBuild = System.currentTimeMillis();
        BuildRequestor.request(_context, pool, cfg, this);
        long buildTime = System.currentTimeMillis() - beforeBuild;
        if (cfg.getLength() <= 1)
            _context.statManager().addRateData("tunnel.buildRequestZeroHopTime", buildTime, buildTime);
        else
            _context.statManager().addRateData("tunnel.buildRequestTime", buildTime, buildTime);
        long id = cfg.getReplyMessageId();
        if (id > 0) {
            synchronized (_recentBuildIds) { 
                while (_recentBuildIds.size() > 64)
                    _recentBuildIds.remove(0);
                _recentBuildIds.add(new Long(id));
            }
        }
    }
    
    public void buildComplete(PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Build complete for " + cfg);
        pool.buildComplete(cfg);
        synchronized (_currentlyBuilding) { 
            _currentlyBuilding.remove(cfg);
            _currentlyBuilding.notifyAll();
        }
        
        long expireBefore = _context.clock().now() + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT;
        if (cfg.getExpiration() <= expireBefore) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Build complete for expired tunnel: " + cfg);
        }
    }
    
    public boolean wasRecentlyBuilding(long replyId) {
        synchronized (_recentBuildIds) {
            return _recentBuildIds.contains(new Long(replyId));
        }
    }
    
    public void buildSuccessful(PooledTunnelCreatorConfig cfg) {
        _manager.buildComplete(cfg);
    }
    
    public void repoll() { 
        synchronized (_currentlyBuilding) { 
            _repoll = true;
            _currentlyBuilding.notifyAll(); 
        }
    }
    
    private void didNotReply(long tunnel, Hash peer) {
        if (_log.shouldLog(Log.INFO))
            _log.info(tunnel + ": Peer " + peer.toBase64() + " did not reply to the tunnel join request");
    }
    
    List locked_getCurrentlyBuilding() { return _currentlyBuilding; }
    public int getInboundBuildQueueSize() { return _handler.getInboundBuildQueueSize(); }
}
