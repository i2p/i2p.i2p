package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;

import net.i2p.router.ClientManagerFacade;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;

import net.i2p.util.Log;
import net.i2p.util.Clock;

import java.util.Set;
import java.util.HashSet;

/**
 * Run periodically for each locally created leaseSet to cause it to be republished
 * if the client is still connected.
 *
 */
public class RepublishLeaseSetJob extends JobImpl {
    private final static Log _log = new Log(RepublishLeaseSetJob.class);
    private final static long REPUBLISH_LEASESET_DELAY = 60*1000; // 5 mins
    private Hash _dest;
    private KademliaNetworkDatabaseFacade _facade;
    /** 
     * maintain a set of dest hashes that we're already publishing, 
     * so we don't go overboard.  This is clunky, so if it gets any more
     * complicated this will go into a 'manager' function rather than part of
     * a job.
     */
    private final static Set _pending = new HashSet(16);
    
    public static boolean alreadyRepublishing(Hash dest) {
	synchronized (_pending) {
	    return _pending.contains(dest);
	}
    }
    
    public RepublishLeaseSetJob(KademliaNetworkDatabaseFacade facade, Hash destHash) {
	super();
	_facade = facade;
	_dest = destHash;
	synchronized (_pending) {
	    _pending.add(destHash);
	}
	getTiming().setStartAfter(Clock.getInstance().now()+REPUBLISH_LEASESET_DELAY);
    }
    public String getName() { return "Republish a local leaseSet"; }
    public void runJob() {
	if (ClientManagerFacade.getInstance().isLocal(_dest)) {
	    LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
	    if (ls != null) {
		_log.warn("Client " + _dest + " is local, so we're republishing it");
		if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
		    _log.warn("Not publishing a LOCAL lease that isn't current - " + _dest, new Exception("Publish expired LOCAL lease?"));
		} else {
		    JobQueue.getInstance().addJob(new StoreJob(_facade, _dest, ls, null, null, REPUBLISH_LEASESET_DELAY));
		}
	    } else {
		_log.warn("Client " + _dest + " is local, but we can't find a valid LeaseSet?  perhaps its being rebuilt?");
	    }
	    requeue(REPUBLISH_LEASESET_DELAY);
	} else {
	    _log.info("Client " + _dest + " is no longer local, so no more republishing their leaseSet");
	    synchronized (_pending) {
		_pending.remove(_dest);
	    }
	}
    }
}
