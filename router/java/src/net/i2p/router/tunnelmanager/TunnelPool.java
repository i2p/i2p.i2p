package net.i2p.router.tunnelmanager;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Store the data for free inbound, outbound, and client pooled tunnels, and serve
 * as the central coordination point
 *
 */
class TunnelPool {
    private Log _log;
    private RouterContext _context;
    /** TunnelId --> TunnelInfo of outbound tunnels */
    private Map _outboundTunnels;
    /** TunnelId --> TunnelInfo of free inbound tunnels */
    private Map _freeInboundTunnels; 
    /** Destination --> ClientTunnelPool */
    private Map _clientPools;
    /** TunnelId --> TunnelInfo structures of non-local tunnels we're participating in */
    private Map _participatingTunnels; 
    /** TunnelId --> TunnelInfo of tunnels being built (but not ready yet) */
    private Map _pendingTunnels;
    /** defines pool settings: # inbound / outbound, length, etc */
    private ClientTunnelSettings _poolSettings;
    private TunnelPoolPersistenceHelper _persistenceHelper;
    /** how long will each tunnel create take? */
    private long _tunnelCreationTimeout;
    /** how many clients should we stock the pool in support of */
    private int _targetClients;
    /** active or has it been shutdown? */
    private boolean _isLive;
    private TunnelBuilder _tunnelBuilder;
    
    /** write out the current state every 60 seconds */
    private final static long WRITE_POOL_DELAY = 60*1000; 
    
    /** allow the tunnel create timeout to be overridden, default is 60 seconds [but really slow computers should be larger] */
    public final static String TUNNEL_CREATION_TIMEOUT_PARAM = "tunnel.creationTimeoutMs";
    public final static long TUNNEL_CREATION_TIMEOUT_DEFAULT = 60*1000;
    
    public final static String TARGET_CLIENTS_PARAM = "router.targetClients";
    public final static int TARGET_CLIENTS_DEFAULT = 3;

    public TunnelPool(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPool.class);

        _context.statManager().createFrequencyStat("tunnel.failFrequency", "How often do tunnels prematurely fail (after being successfully built)?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tunnel.failAfterTime", "How long do tunnels that fail prematurely last before failing?", "Tunnels", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });

        _isLive = true;
        _persistenceHelper = new TunnelPoolPersistenceHelper(_context);
        _tunnelBuilder = new TunnelBuilder(_context);
    }
    
    /**
     * If the tunnel is known in any way, fetch it, else return null
     *
     */
    public TunnelInfo getTunnelInfo(TunnelId id) {
        if (!_isLive) {
            if (_log.shouldLog(Log.ERROR))
                _log.error(toString() + ": Not live, unable to search for tunnel " + id);
            return null;
        }
        if (id == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error(toString() + ": Id requested is null");
            return null;
        }
        boolean typeKnown = id.getType() != TunnelId.TYPE_UNSPECIFIED;

        if ( (!typeKnown) || (id.getType() == TunnelId.TYPE_PARTICIPANT) ) {
            synchronized (_participatingTunnels) {
                if (_participatingTunnels.containsKey(id)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(toString() + ": Found tunnel " + id + " as a participant");
                    return (TunnelInfo)_participatingTunnels.get(id);
                }
            }
        }
        if ( (!typeKnown) || (id.getType() == TunnelId.TYPE_OUTBOUND) ) {
            synchronized (_outboundTunnels) {
                if (_outboundTunnels.containsKey(id)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(toString() + ": Found tunnel " + id + " as outbound");
                    return (TunnelInfo)_outboundTunnels.get(id);
                }
            }
        }
        if ( (!typeKnown) || (id.getType() == TunnelId.TYPE_INBOUND) ) {
            synchronized (_freeInboundTunnels) {
                if (_freeInboundTunnels.containsKey(id)) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(toString() + ": Found tunnel " + id + " as a free inbound");
                    return (TunnelInfo)_freeInboundTunnels.get(id);
                }
            }
        }
        synchronized (_pendingTunnels) {
            if (_pendingTunnels.containsKey(id)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(toString() + ": Found tunnel " + id + " as a pending tunnel");
                return (TunnelInfo)_pendingTunnels.get(id);
            }
        }

        if ( (!typeKnown) || (id.getType() == TunnelId.TYPE_INBOUND) ) {
            synchronized (_clientPools) {
                for (Iterator iter = _clientPools.values().iterator(); iter.hasNext(); ) {
                    ClientTunnelPool pool = (ClientTunnelPool)iter.next();
                    if (pool.isInboundTunnel(id)) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(toString() + ": Found tunnel " + id + " as an inbound tunnel for the client " + pool.getDestination().calculateHash().toBase64());
                        return pool.getInboundTunnel(id);
                    } else if (pool.isInactiveInboundTunnel(id)) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(toString() + ": Found tunnel " + id + " as an inactive inbound tunnel for the client " + pool.getDestination().calculateHash().toBase64());
                        return pool.getInactiveInboundTunnel(id);
                    }
                }
            }
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": Did NOT find the tunnel " + id);
        return null;
    }
    
    /**
     * Get the tunnelId of all tunnels we are managing (not ones we are merely 
     * participating in)
     *
     */
    public Set getManagedTunnelIds() {
        if (!_isLive) return null;
        Set ids = new HashSet(64);
        synchronized (_outboundTunnels) {
            ids.addAll(_outboundTunnels.keySet());
        }
        synchronized (_freeInboundTunnels) {
            ids.addAll(_freeInboundTunnels.keySet());
        }
        synchronized (_clientPools) {
            for (Iterator iter = _clientPools.values().iterator(); iter.hasNext(); ) {
                ClientTunnelPool pool = (ClientTunnelPool)iter.next();
                ids.addAll(pool.getInboundTunnelIds());
            }
        }
        return ids;
    }
    
    /**
     * Allocate a free tunnel for use by the destination 
     *
     * @return true if the tunnel was allocated successfully, false if an error occurred
     */
    public boolean allocateTunnel(TunnelId id, Destination dest) {
        if (!_isLive) return false;
        ClientTunnelPool pool = getClientPool(dest);
        if (pool == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error allocating tunnel " + id + " to " + dest + ": no pool for the client known");
            return false;
        }
        TunnelInfo tunnel = removeFreeTunnel(id);
        if (tunnel == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error allocating tunnel " + id + " to " + dest + ": tunnel is no longer free?");
            return false;
        }

        TunnelInfo t = tunnel;
        while (t != null) {
            t.setDestination(dest);
            t = t.getNextHopInfo();
        }

        pool.addInboundTunnel(tunnel);
        return true;
    }

    /**
     * Set of tunnelIds for outbound tunnels
     */
    public Set getOutboundTunnels() { 
        if (!_isLive) return null;
        synchronized (_outboundTunnels) {
            return new HashSet(_outboundTunnels.keySet());
        }
    }
    public int getOutboundTunnelCount() {
        if (!_isLive) return 0;
        synchronized (_outboundTunnels) {
            return _outboundTunnels.size();
        }
    }
    public TunnelInfo getOutboundTunnel(TunnelId id) {
        if (!_isLive) return null;
        synchronized (_outboundTunnels) {
            return (TunnelInfo)_outboundTunnels.get(id);
        }
    }
    public void addOutboundTunnel(TunnelInfo tunnel) {
        if (!_isLive) return;
        if (_log.shouldLog(Log.DEBUG)) _log.debug(toString() + ": Add outbound tunnel " + tunnel.getTunnelId());
        _context.messageHistory().tunnelJoined("outbound", tunnel);
        synchronized (_outboundTunnels) {
            _outboundTunnels.put(tunnel.getTunnelId(), tunnel);
        }
        synchronized (_pendingTunnels) {
            _pendingTunnels.remove(tunnel.getTunnelId());
        }
    }
    public void removeOutboundTunnel(TunnelId id) {
        if (!_isLive) return;
        if (_log.shouldLog(Log.DEBUG)) _log.debug(toString() + ": Removing outbound tunnel " + id);
        int remaining = 0;
        synchronized (_outboundTunnels) {
            _outboundTunnels.remove(id);
            remaining = _outboundTunnels.size();
        }
        if (remaining <= 0) {
            buildFakeTunnels();
        }
    }
    
    /**
     * Set of tunnelIds that this router has available for consumption
     */
    public Set getFreeTunnels() { 
        if (!_isLive) return null;
        synchronized (_freeInboundTunnels) {
            return new HashSet(_freeInboundTunnels.keySet());
        }
    }
    public int getFreeTunnelCount() {
        if (!_isLive) return 0;
        synchronized (_freeInboundTunnels) {
            return _freeInboundTunnels.size();
        }
    }
    public TunnelInfo getFreeTunnel(TunnelId id) {
        if (!_isLive) return null;
        synchronized (_freeInboundTunnels) {
            return (TunnelInfo)_freeInboundTunnels.get(id);
        }
    }
    public void addFreeTunnel(TunnelInfo tunnel) {
        if (!_isLive) return;
        if (_log.shouldLog(Log.DEBUG)) _log.debug(toString() + ": Add free inbound tunnel " + tunnel.getTunnelId());
        _context.messageHistory().tunnelJoined("free inbound", tunnel);
        synchronized (_freeInboundTunnels) {
            _freeInboundTunnels.put(tunnel.getTunnelId(), tunnel);
        }
        synchronized (_pendingTunnels) {
            _pendingTunnels.remove(tunnel.getTunnelId());
        }
        if (tunnel.getDestination() != null) {
            // this one was custom built, so tack 'er on directly
            allocateTunnel(tunnel.getTunnelId(), tunnel.getDestination());
        }
    }
    public TunnelInfo removeFreeTunnel(TunnelId id) {
        if (!_isLive) return null;
        if (_log.shouldLog(Log.DEBUG)) _log.debug(toString() + ": Removing free inbound tunnel " + id);
        int remaining = 0;
        TunnelInfo rv = null;
        synchronized (_freeInboundTunnels) {
            rv = (TunnelInfo)_freeInboundTunnels.remove(id);
            remaining = _freeInboundTunnels.size();
        }
        if (remaining <= 0)
            buildFakeTunnels();
        return rv;
    }

    /** 
     * set of tunnelIds that this router is participating in (but not managing)
     */
    public Set getParticipatingTunnels() {
        if (!_isLive) return null;
        synchronized (_participatingTunnels) {
            return new HashSet(_participatingTunnels.keySet());
        }
    }
    public int getParticipatingTunnelCount() { 
        if (!_isLive) return 0;
        synchronized (_participatingTunnels) {
            return _participatingTunnels.size();
        }
    }
    public TunnelInfo getParticipatingTunnel(TunnelId id) {
        if (!_isLive) return null;
        synchronized (_participatingTunnels) {
            return (TunnelInfo)_participatingTunnels.get(id);
        }
    }
    
    public boolean addParticipatingTunnel(TunnelInfo tunnel) {
        if (!_isLive) return false;
        if (_log.shouldLog(Log.DEBUG)) _log.debug(toString() + ": Add participating tunnel " + tunnel.getTunnelId());
        _context.messageHistory().tunnelJoined("participant", tunnel);
        synchronized (_participatingTunnels) {
            if (_participatingTunnels.containsKey(tunnel.getTunnelId())) {
                return false;
            } else {
                _participatingTunnels.put(tunnel.getTunnelId(), tunnel);
                tunnel.setIsReady(true);
                return true;
            }
        }
    }
    
    public TunnelInfo removeParticipatingTunnel(TunnelId id) {
        if (!_isLive) return null;
        if (_log.shouldLog(Log.DEBUG)) _log.debug(toString() + ": Removing participating tunnel " + id);
        synchronized (_participatingTunnels) {
            return (TunnelInfo)_participatingTunnels.remove(id);
        }
    }
    
    /**
     * Set of Destinations for clients currently being managed
     *
     */
    public Set getClientPools() {
        if (!_isLive) return null;
        synchronized (_clientPools) {
            return new HashSet(_clientPools.keySet());
        }
    }
    
    /** 
     * Create and start up a client pool for the destination
     *
     */
    public void createClientPool(Destination dest, ClientTunnelSettings settings) {
        if (!_isLive) return;
        ClientTunnelPool pool = null;
        synchronized (_clientPools) {
            if (_clientPools.containsKey(dest)) {
                pool = (ClientTunnelPool)_clientPools.get(dest);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Reusing an existing client tunnel pool for " + dest.calculateHash());
            } else {
                pool = new ClientTunnelPool(_context, dest, settings, this);
                if (_log.shouldLog(Log.INFO))
                    _log.info("New client tunnel pool created for " + dest.calculateHash());
                _clientPools.put(dest, pool);
            }
        }
        pool.startPool();
    }
    
    ClientTunnelPool addClientPool(ClientTunnelPool pool) {
        if (!_isLive) return null;
        ClientTunnelPool old = null;

        if (_log.shouldLog(Log.INFO))
            _log.info("Client tunnel pool added for " + pool.getDestination().calculateHash());

        synchronized (_clientPools) {
            old = (ClientTunnelPool)_clientPools.put(pool.getDestination(), pool);
        }
        return old;
    }
    public ClientTunnelPool getClientPool(Destination dest) {
        if (!_isLive) return null;
        synchronized (_clientPools) {
            return (ClientTunnelPool)_clientPools.get(dest);
        }
    }
    
    public void removeClientPool(Destination dest) {
        if (!_isLive) return;
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Removing client tunnel pool for " + dest.calculateHash());
        ClientTunnelPool pool = null;
        synchronized (_clientPools) {
            pool = (ClientTunnelPool)_clientPools.remove(dest);
        }
        if (pool != null)
            pool.stopPool();
    }
    
    public Set getPendingTunnels() { 
        if (!_isLive) return null;
        synchronized (_pendingTunnels) {
            return new HashSet(_pendingTunnels.keySet());
        }
    }
    public TunnelInfo getPendingTunnel(TunnelId id) {
        if (!_isLive) return null;
        synchronized (_pendingTunnels) {
            return (TunnelInfo)_pendingTunnels.get(id);
        }
    }    
    public void addPendingTunnel(TunnelInfo info) {
        if (!_isLive) return;
        _context.messageHistory().tunnelJoined("pending", info);
        synchronized (_pendingTunnels) {
            _pendingTunnels.put(info.getTunnelId(), info);
        }
    }
    public void removePendingTunnel(TunnelId id) {
        if (!_isLive) return;
        if (_log.shouldLog(Log.DEBUG)) _log.debug(toString() + ": Removing pending tunnel " + id);
        synchronized (_pendingTunnels) {
            _pendingTunnels.remove(id);
        }
    }
    
    /** fetch the settings for the pool (tunnel settings and quantities) */
    public ClientTunnelSettings getPoolSettings() { return _poolSettings; }
    public void setPoolSettings(ClientTunnelSettings settings) { _poolSettings = settings; }
    
    /** how many clients the router should expect to handle at once (so it can build sufficient tunnels */
    public int getTargetClients() { return _targetClients; }
    public void setTargetClients(int numConcurrentClients) { _targetClients = numConcurrentClients; }
    
    /** max time for any tunnel creation to take (in milliseconds) */ 
    public long getTunnelCreationTimeout() { return _tunnelCreationTimeout; }
    public void setTunnelCreationTimeout(long timeout) { _tunnelCreationTimeout = timeout; }
    
    /** determine the number of hops in the longest tunnel we have */
    public int getLongestTunnelLength() {
        int max = 0;
        synchronized (_freeInboundTunnels) {
            for (Iterator iter = _freeInboundTunnels.values().iterator(); iter.hasNext(); ) {
                TunnelInfo info = (TunnelInfo)iter.next();
                int len = info.getLength();
                if (len > max)
                    max = len;
            }
        }
        return max;
    }
    
    /**
     * Shit has hit the fan, so lets build a pair of failsafe 0-hop tunnels - one inbound, 
     * and one outbound.  This method blocks until those tunnels are built, and does not 
     * make use of the JobQueue.
     *
     */
    public void buildFakeTunnels() {
        if (getFreeValidTunnelCount() < 3) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running low on valid inbound tunnels, building another");
            TunnelInfo inTunnelGateway = _tunnelBuilder.configureInboundTunnel(null, getPoolSettings(), true);
            RequestTunnelJob inReqJob = new RequestTunnelJob(_context, this, inTunnelGateway, true, getTunnelCreationTimeout());
            inReqJob.runJob();
        }
        if (getOutboundValidTunnelCount() < 3) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running low on valid outbound tunnels, building another");
            TunnelInfo outTunnelGateway = _tunnelBuilder.configureOutboundTunnel(getPoolSettings(), true);
            RequestTunnelJob outReqJob = new RequestTunnelJob(_context, this, outTunnelGateway, false, getTunnelCreationTimeout());
            outReqJob.runJob();
        }
    }
    
    private int getFreeValidTunnelCount() {
        int found = 0;
        Set ids = getFreeTunnels();
        long mustExpireAfter = _context.clock().now();

        for (Iterator iter = ids.iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = getFreeTunnel(id);
            if ( (info != null) && (info.getIsReady()) ) {
                if (info.getSettings().getExpiration() > mustExpireAfter) {
                    if (info.getDestination() == null) {
                        found++;
                    }
                }
            }
        }
        return found;
    }
    
    private int getOutboundValidTunnelCount() {
        int found = 0;
        Set ids = getOutboundTunnels();
        long mustExpireAfter = _context.clock().now();

        for (Iterator iter = ids.iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = getOutboundTunnel(id);
            if ( (info != null) && (info.getIsReady()) ) {
                if (info.getSettings().getExpiration() > mustExpireAfter) {
                    found++;
                }
            }
        }
        return found;
    }
    
    public void tunnelFailed(TunnelId id) {
        if (!_isLive) return;
        if (_log.shouldLog(Log.INFO)) 
            _log.info("Tunnel " + id + " marked as not ready, since it /failed/", new Exception("Failed tunnel"));
        TunnelInfo info = getTunnelInfo(id);
        if (info == null)
            return;
        _context.messageHistory().tunnelFailed(info.getTunnelId());
        info.setIsReady(false);
        Hash us = _context.routerHash();
        long lifetime = _context.clock().now() - info.getCreated();
        while (info != null) {
            if (!info.getThisHop().equals(us)) {
                _context.profileManager().tunnelFailed(info.getThisHop());
            }
            info = info.getNextHopInfo();
        }
        _context.statManager().addRateData("tunnel.failAfterTime", lifetime, lifetime);
        _context.statManager().updateFrequency("tunnel.failFrequency");
        buildFakeTunnels();
    }
    
    public void startup() {
        if (_log.shouldLog(Log.INFO)) _log.info("Starting up tunnel pool");
        _isLive = true;
        _outboundTunnels = new HashMap(16);
        _freeInboundTunnels = new HashMap(16);
        _clientPools = new HashMap(8);
        _participatingTunnels = new HashMap(64);
        _pendingTunnels = new HashMap(8);
        _poolSettings = createPoolSettings();
        _persistenceHelper.loadPool(this);
        _tunnelCreationTimeout = -1;
        try {
            String str = _context.router().getConfigSetting(TUNNEL_CREATION_TIMEOUT_PARAM);
            _tunnelCreationTimeout = Long.parseLong(str);
        } catch (Throwable t) {
            _tunnelCreationTimeout = TUNNEL_CREATION_TIMEOUT_DEFAULT;
        }
        _targetClients = TARGET_CLIENTS_DEFAULT;
        try {
            String str = _context.router().getConfigSetting(TARGET_CLIENTS_PARAM);
            _targetClients = Integer.parseInt(str);
        } catch (Throwable t) {
            _targetClients = TARGET_CLIENTS_DEFAULT;
        }
        buildFakeTunnels();
        _context.jobQueue().addJob(new WritePoolJob());
        _context.jobQueue().addJob(new TunnelPoolManagerJob(_context, this));
        _context.jobQueue().addJob(new TunnelPoolExpirationJob(_context, this));
    }
    
    public void shutdown() {
        if (_log.shouldLog(Log.INFO)) _log.info("Shutting down tunnel pool");
        if (_persistenceHelper != null)
            _persistenceHelper.writePool(this);
        _isLive = false; // the subjobs [should] check getIsLive() on each run 
        _outboundTunnels = null;
        _freeInboundTunnels = null;
        _clientPools = null;
        _participatingTunnels = null;
        _poolSettings = null;
        _persistenceHelper = null;
        _tunnelCreationTimeout = -1;
    }
    
    public boolean isLive() { return _isLive; }
    
    private ClientTunnelSettings createPoolSettings() {
        ClientTunnelSettings settings = new ClientTunnelSettings();
        settings.readFromProperties(_context.router().getConfigMap());
        return settings;
    }
 
    public String renderStatusHTML() {
        if (!_isLive) return "";
        StringBuffer buf = new StringBuffer();
        buf.append("<h2>Tunnel Pool</h2>\n");
        renderTunnels(buf, "Free inbound tunnels", getFreeTunnels());
        renderTunnels(buf, "Outbound tunnels", getOutboundTunnels());
        renderTunnels(buf, "Participating tunnels", getParticipatingTunnels());
        for (Iterator iter = getClientPools().iterator(); iter.hasNext(); ) {
            Destination dest = (Destination)iter.next();
            ClientTunnelPool pool = getClientPool(dest);
            renderTunnels(buf, "Inbound tunnels for " + dest.calculateHash() + " - (still connected? " + (!pool.isStopped()) + ")", pool.getInboundTunnelIds());
        }
        return buf.toString();
    }
    
    private void renderTunnels(StringBuffer buf, String msg, Set tunnelIds) {
        buf.append("<b>").append(msg).append(":</b> <i>(").append(tunnelIds.size()).append(" tunnels)</i><ul>\n");
        for (Iterator iter = tunnelIds.iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo tunnel = getTunnelInfo(id);
            renderTunnel(buf, id, tunnel);
        }
        buf.append("</ul>\n");
    }
    
    private final static void renderTunnel(StringBuffer buf, TunnelId id, TunnelInfo tunnel) {
        if (tunnel == null) {
            buf.append("<li>Tunnel: ").append(id.getTunnelId()).append(" is not known</li>\n");
        } else {
            buf.append("<li>Tunnel: ").append(tunnel.getTunnelId()).append("</li><pre>");
            buf.append("\n\tStyle: ").append(getStyle(id));
            buf.append("\n\tReady? ").append(tunnel.getIsReady());
            buf.append("\n\tDest? ").append(getDestination(tunnel));
            if (tunnel.getSettings() != null)
                buf.append("\n\tExpiration: ").append(new Date(tunnel.getSettings().getExpiration()));
            else
                buf.append("\n\tExpiration: none");
                
            buf.append("\n\tStart router: ").append(tunnel.getThisHop().toBase64()).append("\n");
            TunnelInfo t = tunnel.getNextHopInfo();
            if (t != null) {
                int hop = 1;
                while (t != null) {
                    buf.append("\tHop ").append(hop).append(": ").append(t.getThisHop().toBase64()).append("\n");
                    t = t.getNextHopInfo();
                    hop++;
                }
            } else {
                if (tunnel.getNextHop() != null)
                    buf.append("\tNext: ").append(tunnel.getNextHop().toBase64()).append("\n");
            }

            buf.append("\n</pre>");
        }
    }
    
    private final static String getStyle(TunnelId id) {
        switch (id.getType()) {
            case TunnelId.TYPE_INBOUND:
                return "Inbound";
            case TunnelId.TYPE_OUTBOUND:
                return "Outbound";
            case TunnelId.TYPE_PARTICIPANT:
                return "Participant";
            case TunnelId.TYPE_UNSPECIFIED:
                return "Unspecified";
            default:
                return "Other! - " + id.getType();
        }
    }
    
    private final static String getDestination(TunnelInfo info) {
        while (info != null) {
            if (info.getDestination() != null)
                return info.getDestination().calculateHash().toString();
            else
                info = info.getNextHopInfo();
        }
        return "none";
    }
    
    /**
     * This job instructs the troops to invade mars with a spork.
     */
    private class WritePoolJob extends JobImpl {
        public WritePoolJob() {
            super(TunnelPool.this._context);
            getTiming().setStartAfter(TunnelPool.this._context.clock().now() + WRITE_POOL_DELAY);
        }
        public String getName() { return "Write Out Tunnel Pool"; }
        public void runJob() {
            if (!isLive())
                return;
            _persistenceHelper.writePool(TunnelPool.this);
            requeue(WRITE_POOL_DELAY);
        }
    }
}
