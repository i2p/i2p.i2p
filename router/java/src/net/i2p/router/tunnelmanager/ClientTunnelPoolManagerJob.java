package net.i2p.router.tunnelmanager;

import java.util.Iterator;
import java.util.TreeMap;

import net.i2p.data.TunnelId;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;
import net.i2p.util.Clock;

/**
 * refill the client tunnel pool as necessary, either from the TunnelPool's free
 * inbound set or by requesting custom tunnels via the RequestInboundTunnelJob.
 *
 */
class ClientTunnelPoolManagerJob extends JobImpl {
    private final static Log _log = new Log(ClientTunnelPoolManagerJob.class);
    private ClientTunnelPool _clientPool;
    private TunnelPool _tunnelPool;
    
    /** check the pool every 30 seconds to make sure it has enough tunnels */
    private final static long POOL_CHECK_DELAY = 30*1000;
    
    public ClientTunnelPoolManagerJob(TunnelPool pool, ClientTunnelPool clientPool) {
	super();
	_clientPool = clientPool;
	_tunnelPool = pool;
    }
    public String getName() { return "Manage Client Tunnel Pool"; }
    public void runJob() {
	try {
	    if (_clientPool.isStopped()) {
		if (ClientManagerFacade.getInstance().isLocal(_clientPool.getDestination())) {
		    // it was stopped, but they've reconnected, so boot 'er up again
		    if (_log.shouldLog(Log.INFO))
			_log.info("Client " + _clientPool.getDestination().calculateHash().toBase64() + " was stopped, but reconnected!  restarting it");
		    _clientPool.startPool();
		    // we return directly, since it'll queue up jobs again, etc
		    return;
		} else {
		    // not currently connected - check to see whether all of the tunnels have expired
		    if ((_clientPool.getInactiveInboundTunnelIds().size() > 0) ||
		        (_clientPool.getInboundTunnelIds().size() > 0) ) {
			// there are tunnels left, requeue until later (in case the client reconnects
			if (_log.shouldLog(Log.DEBUG))
			    _log.debug("There are tunnels left, though the client is still disconnected: " + _clientPool.getDestination().calculateHash());
			requeue(POOL_CHECK_DELAY);
			return;
		    } else {
			// no tunnels left and the client is still disconnected, screw the pool
			if (_log.shouldLog(Log.INFO))
			    _log.info("No more tunnels left and the client has disconnected: " + _clientPool.getDestination().calculateHash());
			_tunnelPool.removeClientPool(_clientPool.getDestination());
			return;
		    }
		}
	    }
	    
	    if (!ClientManagerFacade.getInstance().isLocal(_clientPool.getDestination())) {
		_log.info("Client " + _clientPool.getDestination().calculateHash() + " is no longer connected, stop the pool");
		_clientPool.stopPool();
		requeue(POOL_CHECK_DELAY);
		return;
	    }
	    int requestedPoolSize = _clientPool.getClientSettings().getNumInboundTunnels();
	    int safePoolSize = _clientPool.getSafePoolSize(POOL_CHECK_DELAY);
	    if (safePoolSize < requestedPoolSize) {
		requestMoreTunnels(requestedPoolSize-safePoolSize);
	    }
	} catch (Exception t) {
	    _log.log(Log.CRIT, "Unhandled exception managing the client tunnel pool", t);
	}
	requeue(POOL_CHECK_DELAY);
    }
    
    /**
     * Request num more inbound tunnels - either from the free pool or custom built ones
     *
     */
    private void requestMoreTunnels(int numTunnels) {
	int allocated = 0;
	TreeMap goodEnoughTunnels = new TreeMap();
	int maxLength = _tunnelPool.getLongestTunnelLength();
	for (Iterator iter = _tunnelPool.getFreeTunnels().iterator(); iter.hasNext() && allocated < numTunnels; ) {
	    TunnelId id = (TunnelId)iter.next();
	    TunnelInfo info = _tunnelPool.getFreeTunnel(id);
	    if (info != null) {
		if (isGoodEnough(info, maxLength)) {
		    goodEnoughTunnels.put(new Long(0 - info.getSettings().getExpiration()), id);
		}
	    }
	}
	
	// good enough tunnels, ordered with the longest from now duration first
	for (Iterator iter = goodEnoughTunnels.values().iterator(); iter.hasNext() && allocated < numTunnels; ) {
	    TunnelId id = (TunnelId)iter.next();
	    TunnelInfo info = _tunnelPool.getTunnelInfo(id);
	    if (info.getLength() < _clientPool.getClientSettings().getDepthInbound()) {
		// this aint good 'nuff...
		continue;
	    }
	    boolean ok = _tunnelPool.allocateTunnel(id, _clientPool.getDestination());
	    if (ok) {
		allocated++;
	    }
	}
	
	if (allocated < numTunnels) {
	    requestCustomTunnels(numTunnels - allocated);
	} else {
	    _log.debug("Sufficient tunnels exist in the client pool for " + _clientPool.getDestination().calculateHash() + " w3wt");
	    // done!  w00t
	}
    }
    
    /**
     * Determine if the tunnel will meet the client's requirements.  
     *
     */
    private boolean isGoodEnough(TunnelInfo info, int max) {
	if (!info.getIsReady()) {
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Refusing tunnel " + info.getTunnelId() + " because it isn't ready");
	    return false;
	}

	long expireAfter = Clock.getInstance().now() + POOL_CHECK_DELAY + _tunnelPool.getTunnelCreationTimeout()*2;
	if (info.getSettings().getExpiration() <= expireAfter) {
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("Refusing tunnel " + info.getTunnelId() + " because it is going to expire soon");
	    return false;
	}

	int length = info.getLength();
	if (_clientPool.getClientSettings().getEnforceStrictMinimumLength()) {
	    if (length < _clientPool.getClientSettings().getDepthInbound()) {
		// we will require at least the client's length, but they dont meet it
		if (_log.shouldLog(Log.DEBUG))
		    _log.debug("Refusing tunnel " + info.getTunnelId() + " because it is too short (length = " + length + 
		               ", wanted = " + _clientPool.getClientSettings().getDepthInbound() + ")");
		return false;
	    } else {
		// its long enough.  w00t
	    }
	} else {
	    if (length < _clientPool.getClientSettings().getDepthInbound() && (length < max)) {
		// while we will still strive to meet the client's needs, we will be satisfied with
		// the best we have on hand (which may be less that their requested length)
		// this tunnel however meets neither criteria
		if (_log.shouldLog(Log.DEBUG))
		    _log.debug("Refusing tunnel " + info.getTunnelId() + " because it is too short (length = " + length + 
		               ", wanted = " + _clientPool.getClientSettings().getDepthInbound() + ")");
		return false;
	    } else {
		// either its long enough, or its the longest we have.
		// if we want to be strict, specify tunnels.enforceStrictMinimumLength either in the JVM environment
		// via -Dtunnels.enforceStrictMinimumLength=true or in the router.config 
		// (tunnels.enforceStrictMinimumLength=true)
	    }
	}
	
	if (info.getDestination() != null) {
	    if (!_clientPool.getDestination().equals(info.getDestination())) {
		if (_log.shouldLog(Log.INFO))
		    _log.info("Refusing tunnel " + info.getTunnelId() + " because it was requested specifically for another destination [" + info.getDestination().calculateHash() + "]");
		return false;
	    }
	}
	
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("Accepting tunnel " + info.getTunnelId());
	return true;
    }
    
    /**
     * Request numTunnels more tunnels (the free pool doesnt have enough satisfactory ones).
     * This fires off a series of RequestCustomTunnelJobs
     */
    private void requestCustomTunnels(int numTunnels) {
	for (int i = 0; i < numTunnels; i++) {
	    JobQueue.getInstance().addJob(new RequestCustomTunnelJob());
	}
    }
    
    /**
     * Request a new tunnel specifically to the client's requirements, marked as for them so other
     * ClientTunnelPool's wont take it
     *
     */
    private class RequestCustomTunnelJob extends JobImpl {
	public String getName() { return "Request Custom Client Tunnel"; }
	public void runJob() {
	    TunnelInfo tunnelGateway = TunnelBuilder.getInstance().configureInboundTunnel(_clientPool.getDestination(), _clientPool.getClientSettings());
	    RequestTunnelJob reqJob = new RequestTunnelJob(_tunnelPool, tunnelGateway, true, _tunnelPool.getTunnelCreationTimeout());
	    JobQueue.getInstance().addJob(reqJob);
	}
    }
}
