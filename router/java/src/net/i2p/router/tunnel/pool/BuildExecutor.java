package net.i2p.router.tunnel.pool;

import java.util.*;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;
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
    private RouterContext _context;
    private Log _log;
    private TunnelPoolManager _manager;
    /** list of TunnelCreatorConfig elements of tunnels currently being built */
    private List _currentlyBuilding;
    private boolean _isRunning;
    
    public BuildExecutor(RouterContext ctx, TunnelPoolManager mgr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = mgr;
        _currentlyBuilding = new ArrayList(10);
        _context.statManager().createRateStat("tunnel.concurrentBuilds", "How many builds are going at once", "Tunnels", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("tunnel.concurrentBuildsLagged", "How many builds are going at once when we reject further builds, due to job lag (period is lag)", "Tunnels", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
    }
    
    private int allowed() {
        StringBuffer buf = null;
        if (_log.shouldLog(Log.DEBUG)) {
            buf = new StringBuffer(128);
            buf.append("Allowed: ");
        }
        int allowed = 20;
        String prop = _context.getProperty("router.tunnelConcurrentBuilds");
        if (prop != null)
            try { allowed = Integer.valueOf(prop).intValue(); } catch (NumberFormatException nfe) {}

        int concurrent = 0;
        synchronized (_currentlyBuilding) {
            concurrent = _currentlyBuilding.size();
            allowed -= concurrent;
            if (buf != null)
                buf.append(allowed).append(" ").append(_currentlyBuilding.toString());
        }
        if (buf != null)
            _log.debug(buf.toString());
        
        _context.statManager().addRateData("tunnel.concurrentBuilds", concurrent, 0);
        
        long lag = _context.jobQueue().getMaxLag();
        if ( (lag > 2000) && (_context.router().getUptime() > 5*60*1000) ) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Too lagged [" + lag + "], don't allow building");
            _context.statManager().addRateData("tunnel.concurrentBuildsLagged", concurrent, lag);
            return 0; // if we have a job heavily blocking our jobqueue, ssllloowww dddooowwwnnn
        }
        //if (isOverloaded()) 
        //    return 0;

        return allowed;
    }
    
    public void run() {
        _isRunning = true;
        List wanted = new ArrayList(8);
        List pools = new ArrayList(8);
        
        while (!_manager.isShutdown()) {
            try {
                _manager.listPools(pools);
                for (int i = 0; i < pools.size(); i++) {
                    TunnelPool pool = (TunnelPool)pools.get(i);
                    int howMany = pool.countHowManyToBuild();
                    for (int j = 0; j < howMany; j++)
                        wanted.add(pool);
                }

                int allowed = allowed();
                
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Allowed: " + allowed + " wanted: " + wanted);

                // zero hop ones can run inline
                allowed = buildZeroHopTunnels(wanted, allowed);
                
                if ( (allowed > 0) && (wanted.size() > 0) ) {
                    Collections.shuffle(wanted, _context.random());
                    for (int i = 0; (i < allowed) && (wanted.size() > 0); i++) {
                        TunnelPool pool = (TunnelPool)wanted.remove(0);
                        //if (pool.countWantedTunnels() <= 0)
                        //    continue;
                        PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                        if (cfg != null) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Configuring new tunnel " + i + " for " + pool + ": " + cfg);
                            synchronized (_currentlyBuilding) {
                                _currentlyBuilding.add(cfg);
                            }
                            buildTunnel(pool, cfg);
                            if (cfg.getLength() <= 1)
                                i--; //0hop, we can keep going, as there's no worry about throttling
                        } else {
                            i--;
                        }
                    }
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Nothin' doin, wait for a while");
                    try {
                        synchronized (_currentlyBuilding) {
                            if (allowed <= 0)
                                _currentlyBuilding.wait(_context.random().nextInt(5*1000));
                            else // wanted <= 0
                                _currentlyBuilding.wait(_context.random().nextInt(30*1000));
                        }
                    } catch (InterruptedException ie) {
                        // someone wanted to build something
                    }
                }

                wanted.clear();
                pools.clear();
            } catch (Exception e) {
                if (_log.shouldLog(Log.CRIT))
                    _log.log(Log.CRIT, "B0rked in the tunnel builder", e);
            }
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Done building");
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
        // old style here, replace with the new crypto stuff later
        CompleteJob onCreated = new CompleteJob(_context, cfg, new SuccessJob(_context, cfg, pool), pool);
        CompleteJob onFailed = new CompleteJob(_context, cfg, null, pool);
        RequestTunnelJob j = new RequestTunnelJob(_context, cfg, onCreated, onFailed, cfg.getLength()-1, false, cfg.getDestination()==null);
        if (cfg.getLength() <= 1) // length == 1 ==> hops = 0, so do it inline (as its immediate)
            j.runJob();
        else
            j.runJob(); // always inline, as this is on its own thread so it can block
            //_context.jobQueue().addJob(j);
    }
    
    public void buildComplete(PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Build complete for " + cfg);
        pool.buildComplete(cfg);
        synchronized (_currentlyBuilding) { 
            _currentlyBuilding.remove(cfg);
            _currentlyBuilding.notifyAll();
        }
    }
    
    public void repoll() { 
        synchronized (_currentlyBuilding) { _currentlyBuilding.notifyAll(); }
    }
    
    
    private class CompleteJob extends JobImpl {
        private PooledTunnelCreatorConfig _cfg;
        private TunnelPool _pool;
        private Job _onRun;
        public CompleteJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, Job onRun, TunnelPool pool) {
            super(ctx);
            _cfg = cfg;
            _onRun = onRun;
            _pool = pool;
        }
        public String getName() { return "Tunnel create complete"; }
        public void runJob() {
            if (_onRun != null)
                _onRun.runJob();
                //getContext().jobQueue().addJob(_onRun);
            buildComplete(_cfg, _pool);
        }
    }
    private class SuccessJob extends JobImpl {
        private PooledTunnelCreatorConfig _cfg;
        private TunnelPool _pool;
        public SuccessJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
            super(ctx);
            _cfg = cfg;
            _pool = pool;
        }
        public String getName() { return "Tunnel create successful"; }
        public void runJob() {
            _log.debug("Created successfully: " + _cfg);
            if (_cfg.isInbound()) {
                getContext().tunnelDispatcher().joinInbound(_cfg);
            } else {
                getContext().tunnelDispatcher().joinOutbound(_cfg);
            }

            _pool.addTunnel(_cfg);
            _pool.getManager().buildComplete(_cfg);
            TestJob testJob = (_cfg.getLength() > 1 ? new TestJob(getContext(), _cfg, _pool) : null);
            //RebuildJob rebuildJob = new RebuildJob(getContext(), _cfg, _pool);
            ExpireJob expireJob = new ExpireJob(getContext(), _cfg, _pool);
            _cfg.setTunnelPool(_pool);
            _cfg.setTestJob(testJob);
            //_cfg.setRebuildJob(rebuildJob);
            _cfg.setExpireJob(expireJob);
            if (_cfg.getLength() > 1) // no need to test 0 hop tunnels
                getContext().jobQueue().addJob(testJob);
            //getContext().jobQueue().addJob(rebuildJob); // always try to rebuild (ignored if too many)
            getContext().jobQueue().addJob(expireJob);
        }
    }
}
