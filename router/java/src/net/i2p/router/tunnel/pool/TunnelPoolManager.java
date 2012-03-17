package net.i2p.router.tunnel.pool;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.stat.RateStat;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Manage all the exploratory and client tunnel pools.
 * Run the tunnel builder and handler threads.
 */
public class TunnelPoolManager implements TunnelManagerFacade {
    private final RouterContext _context;
    private final Log _log;
    /** Hash (destination) to TunnelPool */
    private final Map<Hash, TunnelPool> _clientInboundPools;
    /** Hash (destination) to TunnelPool */
    private final Map<Hash, TunnelPool> _clientOutboundPools;
    private TunnelPool _inboundExploratory;
    private TunnelPool _outboundExploratory;
    private final BuildExecutor _executor;
    private final BuildHandler _handler;
    private boolean _isShutdown;
    private final int _numHandlerThreads;
    private static final long[] RATES = { 60*1000, 10*60*1000l, 60*60*1000l };

    private static final int MIN_KBPS_TWO_HANDLERS = 512;
    private static final int MIN_KBPS_THREE_HANDLERS = 1024;
    
    public TunnelPoolManager(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPoolManager.class);
        
        //HandlerJobBuilder builder = new HandleTunnelCreateMessageJob.Builder(ctx);
        //ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelCreateMessage.MESSAGE_TYPE, builder);
        //HandlerJobBuilder b = new TunnelMessageHandlerBuilder(ctx);
        //ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelGatewayMessage.MESSAGE_TYPE, b);
        //ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelDataMessage.MESSAGE_TYPE, b);

        _clientInboundPools = new ConcurrentHashMap(4);
        _clientOutboundPools = new ConcurrentHashMap(4);
        
        _executor = new BuildExecutor(ctx, this);
        I2PThread execThread = new I2PThread(_executor, "BuildExecutor", true);
        execThread.start();
        _handler = new BuildHandler(ctx, this, _executor);
        int numHandlerThreads;
        int share = TunnelDispatcher.getShareBandwidth(ctx);
        if (share >= MIN_KBPS_THREE_HANDLERS)
            numHandlerThreads = 3;
        else if (share >= MIN_KBPS_TWO_HANDLERS)
            numHandlerThreads = 2;
        else
            numHandlerThreads = 1;
        _numHandlerThreads = ctx.getProperty("router.buildHandlerThreads", numHandlerThreads);
        for (int i = 1; i <= _numHandlerThreads; i++) {
            I2PThread hThread = new I2PThread(_handler, "BuildHandler " + i + '/' + numHandlerThreads, true);
            hThread.start();
        }
        
        // The following are for TestJob
        ctx.statManager().createRequiredRateStat("tunnel.testFailedTime", "Time for tunnel test failure (ms)", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testExploratoryFailedTime", "How long did the failure of an exploratory tunnel take (max of 60s for full timeout)?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testFailedCompletelyTime", "How long did the complete failure take (max of 60s for full timeout)?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testExploratoryFailedCompletelyTime", "How long did the complete failure of an exploratory tunnel take (max of 60s for full timeout)?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testSuccessLength", "How long were the tunnels that passed the test?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRequiredRateStat("tunnel.testSuccessTime", "Time for tunnel test success (ms)", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testAborted", "Tunnel test could not occur, since there weren't any tunnels to test with", "Tunnels", 
                                         RATES);
    }
    
    /**
     * Pick a random inbound exploratory tunnel
     *
     * @return null if none
     */
    public TunnelInfo selectInboundTunnel() { 
        TunnelPool pool = _inboundExploratory;
        if (pool == null) return null;
        TunnelInfo info = pool.selectTunnel(); 
        if (info == null) {
            _inboundExploratory.buildFallback();
            // still can be null, but probably not
            info = _inboundExploratory.selectTunnel();
        }
        return info;
    }
    
    /**
     * Pick a random inbound tunnel from the given destination's pool
     *
     * @param destination if null, returns inbound exploratory tunnel
     * @return null if none
     */
    public TunnelInfo selectInboundTunnel(Hash destination) { 
        if (destination == null) return selectInboundTunnel();
        TunnelPool pool = _clientInboundPools.get(destination);
        if (pool != null) {
            return pool.selectTunnel();
        }
        if (_log.shouldLog(Log.ERROR))
            _log.error("Want the inbound tunnel for " + destination.calculateHash() +
                     " but there isn't a pool?");
        return null;
    }
    
    /**
     * Pick a random outbound exploratory tunnel
     *
     * @return null if none
     */
    public TunnelInfo selectOutboundTunnel() { 
        TunnelPool pool = _outboundExploratory;
        if (pool == null) return null;
        TunnelInfo info = pool.selectTunnel();
        if (info == null) {
            pool.buildFallback();
            // still can be null, but probably not
            info = pool.selectTunnel();
        }
        return info;
    }
    
    /**
     * Pick a random outbound tunnel from the given destination's pool
     *
     * @param destination if null, returns outbound exploratory tunnel
     * @return null if none
     */
    public TunnelInfo selectOutboundTunnel(Hash destination)  {
        if (destination == null) return selectOutboundTunnel();
        TunnelPool pool = _clientOutboundPools.get(destination);
        if (pool != null) {
            return pool.selectTunnel();
        }
        return null;
    }
    
    /**
     * Pick the inbound exploratory tunnel with the gateway closest to the given hash.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectInboundExploratoryTunnel(Hash closestTo) { 
        TunnelPool pool = _inboundExploratory;
        if (pool == null) return null;
        TunnelInfo info = pool.selectTunnel(); 
        if (info == null) {
            _inboundExploratory.buildFallback();
            // still can be null, but probably not
            info = _inboundExploratory.selectTunnel(closestTo);
        }
        return info;
    }
    
    /**
     * Pick the inbound tunnel with the gateway closest to the given hash
     * from the given destination's pool.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param destination if null, returns inbound exploratory tunnel
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectInboundTunnel(Hash destination, Hash closestTo) { 
        if (destination == null) return selectInboundExploratoryTunnel(closestTo);
        TunnelPool pool = _clientInboundPools.get(destination);
        if (pool != null) {
            return pool.selectTunnel(closestTo);
        }
        if (_log.shouldLog(Log.ERROR))
            _log.error("Want the inbound tunnel for " + destination.calculateHash() +
                     " but there isn't a pool?");
        return null;
    }
    
    /**
     * Pick the outbound exploratory tunnel with the endpoint closest to the given hash.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectOutboundExploratoryTunnel(Hash closestTo) { 
        TunnelPool pool = _outboundExploratory;
        if (pool == null) return null;
        TunnelInfo info = pool.selectTunnel();
        if (info == null) {
            pool.buildFallback();
            // still can be null, but probably not
            info = pool.selectTunnel(closestTo);
        }
        return info;
    }
    
    /**
     * Pick the outbound tunnel with the endpoint closest to the given hash
     * from the given destination's pool.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param destination if null, returns outbound exploratory tunnel
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectOutboundTunnel(Hash destination, Hash closestTo) {
        if (destination == null) return selectOutboundExploratoryTunnel(closestTo);
        TunnelPool pool = _clientOutboundPools.get(destination);
        if (pool != null) {
            return pool.selectTunnel(closestTo);
        }
        return null;
    }
    
    public TunnelInfo getTunnelInfo(TunnelId id) {
        TunnelInfo info = null;
        for (TunnelPool pool : _clientInboundPools.values()) {
                info = pool.getTunnel(id);
                if (info != null)
                    return info;
        }
        if (_inboundExploratory != null) {
            info = _inboundExploratory.getTunnel(id);
            if (info != null) return info;
        }
        if (_outboundExploratory != null) {
            info = _outboundExploratory.getTunnel(id);
            if (info != null) return info;
        }
        return null;
    }
    
    /** @return number of inbound exploratory tunnels */
    public int getFreeTunnelCount() { 
        if (_inboundExploratory == null)
            return 0;
        else
            return _inboundExploratory.size(); 
    }

    /** @return number of outbound exploratory tunnels */
    public int getOutboundTunnelCount() { 
        if (_outboundExploratory == null)
            return 0;
        else
            return _outboundExploratory.size(); 
    }

    public int getInboundClientTunnelCount() { 
        int count = 0;
        for (TunnelPool pool : _clientInboundPools.values()) {
            count += pool.listTunnels().size();
        }
        return count;
    }

    public int getOutboundClientTunnelCount() { 
        int count = 0;
        for (TunnelPool pool : _clientOutboundPools.values()) {
            count += pool.listTunnels().size();
        }
        return count;
    }

    /**
     *  Use to verify a tunnel pool is alive
     *  @since 0.7.11
     */
    public int getOutboundClientTunnelCount(Hash destination)  {
        TunnelPool pool = _clientOutboundPools.get(destination);
        if (pool != null)
            return pool.getTunnelCount();
        return 0;
    }
    
    public int getParticipatingCount() { return _context.tunnelDispatcher().getParticipatingCount(); }
    public long getLastParticipatingExpiration() { return _context.tunnelDispatcher().getLastParticipatingExpiration(); }
    
    /**
     *  @return (number of part. tunnels) / (estimated total number of hops in our expl.+client tunnels)
     *  100 max.
     *  We just use length setting, not variance, for speed
     *  @since 0.7.10
     */
    public double getShareRatio() {
        int part = getParticipatingCount();
        if (part <= 0)
            return 0d;
        List<TunnelPool> pools = new ArrayList();
        listPools(pools);
        int count = 0;
        for (int i = 0; i < pools.size(); i++) {
            TunnelPool pool = pools.get(i);
            count += pool.size() * pool.getSettings().getLength();
        }
        if (count <= 0)
            return 100d;
        return Math.min(part / (double) count, 100d);
    }


    public boolean isValidTunnel(Hash client, TunnelInfo tunnel) {
        if (tunnel.getExpiration() < _context.clock().now())
            return false;
        TunnelPool pool;
        if (tunnel.isInbound())
            pool = _clientInboundPools.get(client); 
        else
            pool = _clientOutboundPools.get(client); 
        if (pool == null)
            return false;
        return pool.listTunnels().contains(tunnel);
    }

    public TunnelPoolSettings getInboundSettings() { return _inboundExploratory.getSettings(); }
    public TunnelPoolSettings getOutboundSettings() { return _outboundExploratory.getSettings(); }
    public void setInboundSettings(TunnelPoolSettings settings) { _inboundExploratory.setSettings(settings); }
    public void setOutboundSettings(TunnelPoolSettings settings) { _outboundExploratory.setSettings(settings); }

    public TunnelPoolSettings getInboundSettings(Hash client) { 
        TunnelPool pool = _clientInboundPools.get(client); 
        if (pool != null)
            return pool.getSettings();
        else
            return null;
    }

    public TunnelPoolSettings getOutboundSettings(Hash client) { 
        TunnelPool pool = _clientOutboundPools.get(client); 
        if (pool != null)
            return pool.getSettings();
        else
            return null;
    }

    public void setInboundSettings(Hash client, TunnelPoolSettings settings) {
        setSettings(_clientInboundPools, client, settings);
    }
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings) {
        setSettings(_clientOutboundPools, client, settings);
    }

    private static void setSettings(Map<Hash, TunnelPool> pools, Hash client, TunnelPoolSettings settings) {
        TunnelPool pool = pools.get(client); 
        if (pool != null) {
            settings.setDestination(client); // prevent spoofing or unset dest
            pool.setSettings(settings);
        }
    }
    
    public void restart() { 
        _handler.restart();
        _executor.restart();
        shutdownExploratory();
        startup();
    }
        
    /**
     *  Used only at session startup.
     *  Do not use to change settings.
     */
    public void buildTunnels(Destination client, ClientTunnelSettings settings) {
        Hash dest = client.calculateHash();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Building tunnels for the client " + dest + ": " + settings);
        settings.getInboundSettings().setDestination(dest);
        settings.getOutboundSettings().setDestination(dest);
        TunnelPool inbound = null;
        TunnelPool outbound = null;
        // should we share the clientPeerSelector across both inbound and outbound?
        // or just one for all clients? why separate?

        boolean delayOutbound = false;
        // synch with removeTunnels() below
        synchronized (this) {
            inbound = _clientInboundPools.get(dest);
            if (inbound == null) {
                inbound = new TunnelPool(_context, this, settings.getInboundSettings(), 
                                         new ClientPeerSelector());
                _clientInboundPools.put(dest, inbound);
            } else {
                inbound.setSettings(settings.getInboundSettings());
            }
            outbound = _clientOutboundPools.get(dest);
            if (outbound == null) {
                outbound = new TunnelPool(_context, this, settings.getOutboundSettings(), 
                                          new ClientPeerSelector());
                _clientOutboundPools.put(dest, outbound);
                delayOutbound = true;
            } else {
                outbound.setSettings(settings.getOutboundSettings());
            }
        }
        inbound.startup();
        // don't delay the outbound if it already exists, as this opens up a large
        // race window with removeTunnels() below
        if (delayOutbound)
            SimpleScheduler.getInstance().addEvent(new DelayedStartup(outbound), 1000);
        else
            outbound.startup();
    }
    
    
    private static class DelayedStartup implements SimpleTimer.TimedEvent {
        private final TunnelPool pool;

        public DelayedStartup(TunnelPool p) {
            this.pool = p;
        }

        public void timeReached() {
            this.pool.startup();
        }
    }

    /**
     *  This will be called twice, once by the inbound and once by the outbound pool.
     *  Synched with buildTunnels() above.
     */
    public synchronized void removeTunnels(Hash destination) {
        if (destination == null) return;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Removing tunnels for the client " + destination);
        if (_context.clientManager().isLocal(destination)) {
            // race with buildTunnels() on restart of a client
            if (_log.shouldLog(Log.ERROR))
                _log.error("Not removing pool still registered with client manager: " + destination.toBase64(), new Exception("i did it"));
            return;
        }
        TunnelPool inbound = _clientInboundPools.remove(destination);
        TunnelPool outbound = _clientOutboundPools.remove(destination);
        if (inbound != null)
            inbound.shutdown();
        if (outbound != null)
            outbound.shutdown();
    }
    
    /** queue a recurring test job if appropriate */
    void buildComplete(PooledTunnelCreatorConfig cfg) {
        if (cfg.getLength() > 1 &&
            (!_context.router().gracefulShutdownInProgress()) &&
            !_context.getBooleanPropertyDefaultTrue("router.disableTunnelTesting")) {
            TunnelPool pool = cfg.getTunnelPool();
            if (pool == null) {
                // never seen this before, do we reallly need to bother
                // trying so hard to find his pool?
                _log.error("How does this not have a pool?  " + cfg, new Exception("baf"));
                if (cfg.getDestination() != null) {
                    if (cfg.isInbound()) {
                            pool = _clientInboundPools.get(cfg.getDestination());
                    } else {
                            pool = _clientOutboundPools.get(cfg.getDestination());
                    }
                } else {
                    if (cfg.isInbound()) {
                        pool = _inboundExploratory;
                    } else {
                        pool = _outboundExploratory;
                    }
                }
                cfg.setTunnelPool(pool);
            }
            _context.jobQueue().addJob(new TestJob(_context, cfg, pool));
        }
    }

    public void startup() { 
        _isShutdown = false;
        if (!_executor.isRunning()) {
            I2PThread t = new I2PThread(_executor, "BuildExecutor");
            t.setDaemon(true);
            t.start();
        }
        ExploratoryPeerSelector selector = new ExploratoryPeerSelector();
        
        TunnelPoolSettings inboundSettings = new TunnelPoolSettings();
        inboundSettings.setIsExploratory(true);
        inboundSettings.setIsInbound(true);
        _inboundExploratory = new TunnelPool(_context, this, inboundSettings, selector);
        _inboundExploratory.startup();
        
        TunnelPoolSettings outboundSettings = new TunnelPoolSettings();
        outboundSettings.setIsExploratory(true);
        outboundSettings.setIsInbound(false);
        _outboundExploratory = new TunnelPool(_context, this, outboundSettings, selector);
        SimpleScheduler.getInstance().addEvent(new DelayedStartup(_outboundExploratory), 3*1000);
        
        // try to build up longer tunnels
        _context.jobQueue().addJob(new BootstrapPool(_context, _inboundExploratory));
        _context.jobQueue().addJob(new BootstrapPool(_context, _outboundExploratory));
    }
    
    private static class BootstrapPool extends JobImpl {
        private TunnelPool _pool;
        public BootstrapPool(RouterContext ctx, TunnelPool pool) {
            super(ctx);
            _pool = pool;
            getTiming().setStartAfter(ctx.clock().now() + 30*1000);
        }
        public String getName() { return "Bootstrap tunnel pool"; }
        public void runJob() {
            _pool.buildFallback();
        }
    }
    
    /**
     *  Cannot be restarted
     */
    public void shutdown() { 
        _handler.shutdown(_numHandlerThreads);
        _executor.shutdown();
        shutdownExploratory();
        _isShutdown = true;
    }

    private void shutdownExploratory() {
        if (_inboundExploratory != null)
            _inboundExploratory.shutdown();
        if (_outboundExploratory != null)
            _outboundExploratory.shutdown();
    }
    
    /** list of TunnelPool instances currently in play */
    public void listPools(List<TunnelPool> out) {
        out.addAll(_clientInboundPools.values());
        out.addAll(_clientOutboundPools.values());
        if (_inboundExploratory != null)
            out.add(_inboundExploratory);
        if (_outboundExploratory != null)
            out.add(_outboundExploratory);
    }
    void tunnelFailed() { _executor.repoll(); }
    BuildExecutor getExecutor() { return _executor; }
    boolean isShutdown() { return _isShutdown; }

    public int getInboundBuildQueueSize() { return _handler.getInboundBuildQueueSize(); }
    
    /** @deprecated moved to routerconsole */
    public void renderStatusHTML(Writer out) throws IOException {
    }

    /** @return total number of non-fallback expl. + client tunnels */
    private int countTunnelsPerPeer(ObjectCounter<Hash> lc) {
        List<TunnelPool> pools = new ArrayList();
        listPools(pools);
        int tunnelCount = 0;
        for (TunnelPool tp : pools) {
            for (TunnelInfo info : tp.listTunnels()) {
                if (info.getLength() > 1) {
                    tunnelCount++;
                    for (int j = 0; j < info.getLength(); j++) {
                        Hash peer = info.getPeer(j);
                        if (!_context.routerHash().equals(peer))
                            lc.increment(peer);
                    }
                }
            }
        }
        return tunnelCount;
    }

    private static final int DEFAULT_MAX_PCT_TUNNELS = 33;
    /**
     *  For reliability reasons, don't allow a peer in more than x% of
     *  client and exploratory tunnels.
     *
     *  This also will prevent a single huge-capacity (or malicious) peer from
     *  taking all the tunnels in the network (although it would be nice to limit
     *  the % of total network tunnels to 10% or so, but that appears to be
     *  too low to set as a default here... much lower than 33% will push client
     *  tunnels out of the fast tier into high cap or beyond...)
     *
     *  Possible improvement - restrict based on count per IP, or IP block,
     *  to slightly increase costs of collusion
     *
     *  @return Set of peers that should not be allowed in another tunnel
     */
    public Set<Hash> selectPeersInTooManyTunnels() {
        ObjectCounter<Hash> lc = new ObjectCounter();
        int tunnelCount = countTunnelsPerPeer(lc);
        Set<Hash> rv = new HashSet();
        if (tunnelCount >= 4 && _context.router().getUptime() > 10*60*1000) {
            int max = _context.getProperty("router.maxTunnelPercentage", DEFAULT_MAX_PCT_TUNNELS);
            for (Hash h : lc.objects()) {
                 if (lc.count(h) > 0 && (lc.count(h) + 1) * 100 / (tunnelCount + 1) > max)
                     rv.add(h);
            }
        }
        return rv;
    }

    /** for TunnelRenderer in router console */
    public Map<Hash, TunnelPool> getInboundClientPools() {
            return new HashMap(_clientInboundPools);
    }

    /** for TunnelRenderer in router console */
    public Map<Hash, TunnelPool> getOutboundClientPools() {
            return new HashMap(_clientOutboundPools);
    }

    /** for TunnelRenderer in router console */
    public TunnelPool getInboundExploratoryPool() {
        return _inboundExploratory;
    }

    /** for TunnelRenderer in router console */
    public TunnelPool getOutboundExploratoryPool() {
        return _outboundExploratory;
    }

    /**
     *  Fail all outbound tunnels with this peer as first hop,
     *  and all inbound tunnels with this peer as the last hop,
     *  baecause we can't contact it any more.
     *  This is most likely to be triggered by an outbound tunnel.
     *
     *  @since 0.8.13
     */
    public void fail(Hash peer) {
        if (_outboundExploratory != null)
            failTunnelsWithFirstHop(_outboundExploratory, peer);
        for (TunnelPool pool : _clientOutboundPools.values()) {
            failTunnelsWithFirstHop(pool, peer);
        }
        if (_inboundExploratory != null)
            failTunnelsWithLastHop(_inboundExploratory, peer);
        for (TunnelPool pool : _clientInboundPools.values()) {
            failTunnelsWithLastHop(pool, peer);
        }
    }

    /**
     *  Fail all (outbound) tunnels with this peer as first hop (not counting us)
     *
     *  @since 0.8.13
     */
    private void failTunnelsWithFirstHop(TunnelPool pool, Hash peer) {
        for (TunnelInfo tun : pool.listTunnels()) {
            int len = tun.getLength();
            if (len > 1 && tun.getPeer(1).equals(peer)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Removing OB tunnel, first hop shitlisted: " + tun);
                pool.tunnelFailed(tun, peer);
            }
        }
    }

    /**
     *  Fail all (inbound) tunnels with this peer as last hop (not counting us)
     *
     *  @since 0.8.13
     */
    private void failTunnelsWithLastHop(TunnelPool pool, Hash peer) {
        for (TunnelInfo tun : pool.listTunnels()) {
            int len = tun.getLength();
            if (len > 1 && tun.getPeer(len - 2).equals(peer)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Removing IB tunnel, prev. hop shitlisted: " + tun);
                pool.tunnelFailed(tun, peer);
            }
        }
    }
}
