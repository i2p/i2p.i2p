package net.i2p.router.tunnelmanager;

import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.JobImpl;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Manage the process of requesting a lease set as necessary for a client based
 * on the contents of the tunnel pool.  Request a new lease set when:
 *  - # safe inbound tunnels meets or exceeds the client's minimum and 
 *     - no current leaseSet exists
 *   or
 *     - one of the tunnels in the current leaseSet has expired
 *   or 
 *     - it has been N minutes since the current leaseSet was created
 *       (where N is based off the clientSettings.getInboundDuration)
 *
 */
class ClientLeaseSetManagerJob extends JobImpl {
    private final static Log _log = new Log(ClientLeaseSetManagerJob.class);
    private ClientTunnelPool _pool;
    private LeaseSet _currentLeaseSet;
    private long _lastCreated;
    private boolean _forceRequestLease;
    
    /**
     * Recheck the set every 15 seconds 
     * todo: this should probably be updated dynamically based on expiration dates / etc.
     *
     */
    private final static long RECHECK_DELAY = 15*1000;
    /**
     * How long to wait for the client to approve or reject a leaseSet
     */
    private final static long REQUEST_LEASE_TIMEOUT = 30*1000;
    
    public ClientLeaseSetManagerJob(ClientTunnelPool pool) {
	super();
	_pool = pool;
	_currentLeaseSet = null;
	_lastCreated = -1;
    }
    
    public void forceRequestLease() { _forceRequestLease = true; }
    
    public String getName() { return "Manage Client Lease Set"; }
    public void runJob() {
	
	if (_pool.isStopped()) {
	    if ( (_pool.getInactiveInboundTunnelIds().size() <= 0) &&
		 (_pool.getInboundTunnelIds().size() <= 0) ) {
		if (_log.shouldLog(Log.INFO))
		    _log.info("No more tunnels and the client has stopped, so no need to manage the leaseSet any more for " + _pool.getDestination().calculateHash());
		return;
	    } else {
		if (_log.shouldLog(Log.INFO))
		    _log.info("Client " + _pool.getDestination().calculateHash() + " is stopped, but they still have some tunnels, so don't stop maintaining the leaseSet");
		requeue(RECHECK_DELAY);
		return;
	    }
	}
	
	int available = _pool.getSafePoolSize();
	if (available >= _pool.getClientSettings().getNumInboundTunnels()) {
	    if (_forceRequestLease) {
		_log.info("Forced to request a new lease (reconnected client perhaps?)");
		_forceRequestLease = false;
		requestNewLeaseSet();
	    } else if (_currentLeaseSet == null) {
		_log.info("No leaseSet is known - request a new one");
		requestNewLeaseSet();
	    } else if (tunnelsChanged()) {
		_log.info("Tunnels changed from the old leaseSet - request a new one: [pool = " + _pool.getInboundTunnelIds() + " old leaseSet: " + _currentLeaseSet);
		requestNewLeaseSet();
	    } else if (Clock.getInstance().now() > _lastCreated + _pool.getClientSettings().getInboundDuration()) {
		_log.info("We've exceeded the client's requested duration (limit = " + new Date(_lastCreated + _pool.getClientSettings().getInboundDuration()) + " / " + _pool.getClientSettings().getInboundDuration() + ") - request a new leaseSet");
		requestNewLeaseSet();
	    } else {
		_log.debug("The current LeaseSet is fine, noop");
	    }
	} else {
	    _log.warn("Insufficient safe inbound tunnels exist for the client (" + available + " available, " + _pool.getClientSettings().getNumInboundTunnels() + " required) - no leaseSet requested");
	}
	requeue(RECHECK_DELAY);
    }
    /**
     * Determine if the tunnels in the current leaseSet are the same as the 
     * currently available free tunnels
     * 
     * @return true if the tunnels are /not/ the same, else true if they are
     */
    private boolean tunnelsChanged() {
	long furthestInFuture = 0;
	Set currentIds = new HashSet(_currentLeaseSet.getLeaseCount());
	for (int i = 0; i < _currentLeaseSet.getLeaseCount(); i++) {
	    Lease lease = (Lease)_currentLeaseSet.getLease(i);
	    currentIds.add(lease.getTunnelId());
	    if (lease.getEndDate().getTime() > furthestInFuture)
		furthestInFuture = lease.getEndDate().getTime();
	}
	Set avail = _pool.getInboundTunnelIds();
	avail.removeAll(currentIds);
	// check to see if newer ones exist in the available pool
	for (Iterator iter = avail.iterator(); iter.hasNext(); ) { 
	    TunnelId id = (TunnelId)iter.next();
	    TunnelInfo info = _pool.getInboundTunnel(id);
	    // we need to check this in case the tunnel was deleted since 6 lines up
	    if ( (id != null) && (info != null) && (info.getSettings() != null) ) {
		// if something available but not in the currently published lease will be
		// around longer than any of the published leases, we want that tunnel to
		// be added to our published lease
		if (info.getSettings().getExpiration() > furthestInFuture) {
		    _log.debug("Tunnel " + id.getTunnelId() + " expires " + (info.getSettings().getExpiration()-furthestInFuture) + "ms after any of the existing ones do");
		    return true;
		}
	    }
	} 
	_log.debug("None of the available tunnels expire after the existing lease set's tunnels");
	return false;
    }
    
    /**
     * Request a new leaseSet based off the currently available safe tunnels
     */
    private void requestNewLeaseSet() {
	LeaseSet proposed = buildNewLeaseSet();
	ClientManagerFacade.getInstance().requestLeaseSet(_pool.getDestination(), proposed, REQUEST_LEASE_TIMEOUT, new LeaseSetCreatedJob(), null);
    }
    
    /**
     * Create a new proposed leaseSet with all inbound tunnels
     */
    private LeaseSet buildNewLeaseSet() {
	LeaseSet ls = new LeaseSet();
	TreeMap tunnels = new TreeMap();
	long now = Clock.getInstance().now();
	for (Iterator iter = _pool.getInboundTunnelIds().iterator(); iter.hasNext(); ) {
	    TunnelId id = (TunnelId)iter.next();
	    TunnelInfo info = _pool.getInboundTunnel(id);
	    
	    if (!info.getIsReady())
		continue;
	    long exp = info.getSettings().getExpiration();
	    if (now + RECHECK_DELAY + REQUEST_LEASE_TIMEOUT > exp)
		continue;
	    RouterInfo ri = NetworkDatabaseFacade.getInstance().lookupRouterInfoLocally(info.getThisHop());
	    if (ri == null)
		continue;
	    
	    Lease lease = new Lease();
	    lease.setEndDate(new Date(exp));
	    lease.setRouterIdentity(ri.getIdentity());
	    lease.setTunnelId(id);
	    tunnels.put(new Long(0-exp), lease);
	}
	
	// now pick the N tunnels with the longest time remaining (n = # tunnels the client requested)
	// place tunnels.size() - N into the inactive pool
	int selected = 0;
	int wanted = _pool.getClientSettings().getNumInboundTunnels();
	for (Iterator iter = tunnels.values().iterator(); iter.hasNext(); ) {
	    Lease lease = (Lease)iter.next();
	    if (selected < wanted) {
		ls.addLease(lease);
		selected++;
	    } else {
		_pool.moveToInactive(lease.getTunnelId());
	    }
	}
	ls.setDestination(_pool.getDestination());
	return ls;
    }
    
    private class LeaseSetCreatedJob extends JobImpl {
	public LeaseSetCreatedJob() {
	    super();
	}
	public String getName() { return "LeaseSet created"; }
	public void runJob() { 
	    LeaseSet ls = NetworkDatabaseFacade.getInstance().lookupLeaseSetLocally(_pool.getDestination().calculateHash());
	    if (ls != null) {
		_log.info("New leaseSet completely created");
		_lastCreated = Clock.getInstance().now();
		_currentLeaseSet = ls;
	    } else {
		_log.error("New lease set created, but not found locally?  wtf?!");
	    }
	}
    }
}
