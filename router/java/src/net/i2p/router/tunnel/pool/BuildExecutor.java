package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
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
 * Note that 10 minute tunnel expiration is hardcoded in here.
 */
class BuildExecutor implements Runnable {
    private final ArrayList<Long> _recentBuildIds = new ArrayList(100);
    private final RouterContext _context;
    private final Log _log;
    private final TunnelPoolManager _manager;
    /** list of TunnelCreatorConfig elements of tunnels currently being built */
    private final Object _currentlyBuilding;
    /** indexed by ptcc.getReplyMessageId() */
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _currentlyBuildingMap;
    /** indexed by ptcc.getReplyMessageId() */
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _recentlyBuildingMap;
    private boolean _isRunning;
    private final BuildHandler _handler;
    private boolean _repoll;
    private static final int MAX_CONCURRENT_BUILDS = 10;
    /** accept replies up to a minute after we gave up on them */
    private static final long GRACE_PERIOD = 60*1000;

    public BuildExecutor(RouterContext ctx, TunnelPoolManager mgr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = mgr;
        _currentlyBuilding = new Object();
        _currentlyBuildingMap = new ConcurrentHashMap(MAX_CONCURRENT_BUILDS);
        _recentlyBuildingMap = new ConcurrentHashMap(4 * MAX_CONCURRENT_BUILDS);
        _context.statManager().createRateStat("tunnel.concurrentBuilds", "How many builds are going at once", "Tunnels", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.concurrentBuildsLagged", "How many builds are going at once when we reject further builds, due to job lag (period is lag)", "Tunnels", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.buildExploratoryExpire", "No response to our build request", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.buildClientExpire", "No response to our build request", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.buildExploratorySuccess", "Response time for success (ms)", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.buildClientSuccess", "Response time for success (ms)", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.buildExploratoryReject", "Response time for rejection (ms)", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.buildClientReject", "Response time for rejection (ms)", "Tunnels", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.buildRequestTime", "Time to build a tunnel request (ms)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildRequestZeroHopTime", "How long it takes to build a zero hop tunnel", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.pendingRemaining", "How many inbound requests are pending after a pass (period is how long the pass takes)?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildFailFirstHop", "How often we fail to build a OB tunnel because we can't contact the first hop", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildReplySlow", "Build reply late, but not too late", "Tunnels", new long[] { 10*60*1000 });

        // Get stat manager, get recognized bandwidth tiers
        StatManager statMgr = _context.statManager();
        String bwTiers = RouterInfo.BW_CAPABILITY_CHARS;
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
        if (allowed > MAX_CONCURRENT_BUILDS) allowed = MAX_CONCURRENT_BUILDS; // Never go beyond 10, that is uncharted territory (old limit was 5)
        allowed = _context.getProperty("router.tunnelConcurrentBuilds", allowed);

        // expire any REALLY old requests
        long expireBefore = _context.clock().now() + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT - GRACE_PERIOD;
        for (Iterator<PooledTunnelCreatorConfig> iter = _recentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            if (cfg.getExpiration() <= expireBefore) {
                iter.remove();
            }
        }

        // expire any old requests
        List<PooledTunnelCreatorConfig> expired = null;
        int concurrent = 0;
        // Todo: Make expiration variable
        expireBefore = _context.clock().now() + 10*60*1000 - BuildRequestor.REQUEST_TIMEOUT;
        for (Iterator<PooledTunnelCreatorConfig> iter = _currentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            if (cfg.getExpiration() <= expireBefore) {
                // save them for another minute
                _recentlyBuildingMap.putIfAbsent(Long.valueOf(cfg.getReplyMessageId()), cfg);
                iter.remove();
                if (expired == null)
                    expired = new ArrayList();
                expired.add(cfg);
            }
        }
        concurrent = _currentlyBuildingMap.size();
        allowed -= concurrent;
        
        if (expired != null) {
            for (int i = 0; i < expired.size(); i++) {
                PooledTunnelCreatorConfig cfg = expired.get(i);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Timed out waiting for reply asking for " + cfg);

                // Iterate through peers in the tunnel, get their bandwidth tiers,
                // record for each that a peer of the given tier expired
                // Also note the fact that this tunnel request timed out in the peers' profiles.
                for (int iPeer = 0; iPeer < cfg.getLength(); iPeer++) {
                    // Look up peer
                    Hash peer = cfg.getPeer(iPeer);
                    // Avoid recording ourselves
                    if (peer.equals(_context.routerHash()))
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
        List<TunnelPool> wanted = new ArrayList(MAX_CONCURRENT_BUILDS);
        List<TunnelPool> pools = new ArrayList(8);
        
        int pendingRemaining = 0;
        
        //long loopBegin = 0;
        //long afterBuildZeroHop = 0;
        long afterBuildReal = 0;
        long afterHandleInbound = 0;
                
        while (!_manager.isShutdown()){
            //loopBegin = System.currentTimeMillis();
            try {
                _repoll = pendingRemaining > 0; // resets repoll to false unless there are inbound requeusts pending
                _manager.listPools(pools);
                for (int i = 0; i < pools.size(); i++) {
                    TunnelPool pool = pools.get(i);
                    if (!pool.isAlive())
                        continue;
                    int howMany = pool.countHowManyToBuild();
                    for (int j = 0; j < howMany; j++)
                        wanted.add(pool);
                }

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
                            try {
                                _currentlyBuilding.wait(1*1000+_context.random().nextInt(1*1000));
                            } catch (InterruptedException ie) {}
                        }
                    }
                } else {
                    if ( (allowed > 0) && (!wanted.isEmpty()) ) {
                        Collections.shuffle(wanted, _context.random());
                        Collections.sort(wanted, new TunnelPoolComparator());

                        // force the loops to be short, since 3 consecutive tunnel build requests can take
                        // a long, long time
                        if (allowed > 2)
                            allowed = 2;
                        
                        for (int i = 0; (i < allowed) && (!wanted.isEmpty()); i++) {
                            TunnelPool pool = wanted.remove(0);
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
                                buildTunnel(pool, cfg);
                                realBuilt++;
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
            } catch (RuntimeException e) {
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "B0rked in the tunnel builder", e);
            }
        }
        
        if (_log.shouldLog(Log.WARN))
            _log.warn("Done building");
        _isRunning = false;
    }
    
    /**
     *  Prioritize the pools for building
     *  #1: Exploratory
     *  #2: Pools without tunnels
     *  #3: Everybody else
     *
     *  This prevents a large number of client pools from starving the exploratory pool.
     *
     */
    private static class TunnelPoolComparator implements Comparator<TunnelPool> {
        public int compare(TunnelPool tpl, TunnelPool tpr) {
            if (tpl.getSettings().isExploratory() && !tpr.getSettings().isExploratory())
                return -1;
            if (tpr.getSettings().isExploratory() && !tpl.getSettings().isExploratory())
                return 1;
            if (tpl.getTunnelCount() <= 0 && tpr.getTunnelCount() > 0)
                return -1;
            if (tpr.getTunnelCount() <= 0 && tpl.getTunnelCount() > 0)
                return 1;
            return 0;
        }
    }

    /**
     * iterate over the 0hop tunnels, running them all inline regardless of how many are allowed
     * @return number of tunnels allowed after processing these zero hop tunnels (almost always the same as before)
     */
    private int buildZeroHopTunnels(List<TunnelPool> wanted, int allowed) {
        for (int i = 0; i < wanted.size(); i++) {
            TunnelPool pool = wanted.get(0);
            if (pool.getSettings().getLength() == 0) {
                PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                if (cfg != null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Configuring short tunnel " + i + " for " + pool + ": " + cfg);
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
        if (cfg.getLength() > 1) {
            // should we allow an ID of 0?
            cfg.setReplyMessageId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            if (addToBuilding(cfg)) {
                _log.error("Dup reply ID: " + cfg.getReplyMessageId());
                // fail
                return;
            }
        }
        BuildRequestor.request(_context, pool, cfg, this);
        long buildTime = System.currentTimeMillis() - beforeBuild;
        if (cfg.getLength() <= 1)
            _context.statManager().addRateData("tunnel.buildRequestZeroHopTime", buildTime, buildTime);
        else
            _context.statManager().addRateData("tunnel.buildRequestTime", buildTime, buildTime);
        long id = cfg.getReplyMessageId();
        if (id > 0) {
            synchronized (_recentBuildIds) { 
                // every so often, shrink the list semi-efficiently
                if (_recentBuildIds.size() > 98) {
                    for (int i = 0; i < 32; i++)
                        _recentBuildIds.remove(0);
                }
                _recentBuildIds.add(Long.valueOf(id));
            }
        }
    }
    
    public void buildComplete(PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Build complete for " + cfg);
        pool.buildComplete(cfg);
        if (cfg.getLength() > 1)
            removeFromBuilding(cfg.getReplyMessageId());
        synchronized (_currentlyBuilding) { 
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
            return _recentBuildIds.contains(Long.valueOf(replyId));
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
    
    /**
     *  Only do this for non-fallback tunnels.
     *  @return true if refused because of a duplicate key
     *  @since 0.7.12
     */
    private boolean addToBuilding(PooledTunnelCreatorConfig cfg) {
        //_log.error("Adding ID: " + cfg.getReplyMessageId() + "; size was: " + _currentlyBuildingMap.size());
        return _currentlyBuildingMap.putIfAbsent(Long.valueOf(cfg.getReplyMessageId()), cfg) != null;
    }

    /**
     *  This returns the PTCC up to a minute after it 'expired', thus allowing us to
     *  still use a tunnel if it was accepted, and to update peer stats.
     *  This means that manager.buildComplete() could be called more than once, and
     *  a build can be failed or successful after it was timed out,
     *  which will affect the stats and profiles.
     *  But that's ok. A peer that rejects slowly gets penalized twice, for example.
     *
     *  @return ptcc or null
     *  @since 0.7.12
     */
    PooledTunnelCreatorConfig removeFromBuilding(long id) {
        //_log.error("Removing ID: " + id + "; size was: " + _currentlyBuildingMap.size());
        Long key = Long.valueOf(id);
        PooledTunnelCreatorConfig rv = _currentlyBuildingMap.remove(key);
        if (rv != null)
            return rv;
        rv = _recentlyBuildingMap.remove(key);
        if (rv != null) {
            long requestedOn = rv.getExpiration() - 10*60*1000;
            long rtt = _context.clock().now() - requestedOn;
            _context.statManager().addRateData("tunnel.buildReplySlow", rtt, 0);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Got reply late (rtt = " + rtt + ") for: " + rv);
        }
        return rv;
    }

    public int getInboundBuildQueueSize() { return _handler.getInboundBuildQueueSize(); }
}
