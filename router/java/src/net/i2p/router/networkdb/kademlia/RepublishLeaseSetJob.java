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
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Run periodically for each locally created leaseSet to cause it to be republished
 * if the client is still connected.
 *
 */
public class RepublishLeaseSetJob extends JobImpl {
    private Log _log;
    private final static long REPUBLISH_LEASESET_DELAY = 5*60*1000; // 5 mins
    private final static long REPUBLISH_LEASESET_TIMEOUT = 60*1000;
    private Hash _dest;
    private KademliaNetworkDatabaseFacade _facade;
    
    public RepublishLeaseSetJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade, Hash destHash) {
        super(ctx);
        _log = ctx.logManager().getLog(RepublishLeaseSetJob.class);
        _facade = facade;
        _dest = destHash;
        getTiming().setStartAfter(ctx.clock().now()+REPUBLISH_LEASESET_DELAY);
    }
    public String getName() { return "Republish a local leaseSet"; }
    public void runJob() {
        try {
            if (getContext().clientManager().isLocal(_dest)) {
                LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
                if (ls != null) {
                    _log.warn("Client " + _dest + " is local, so we're republishing it");
                    if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                        _log.warn("Not publishing a LOCAL lease that isn't current - " + _dest, new Exception("Publish expired LOCAL lease?"));
                    } else {
                        getContext().jobQueue().addJob(new StoreJob(getContext(), _facade, _dest, ls, new OnSuccess(getContext()), new OnFailure(getContext()), REPUBLISH_LEASESET_TIMEOUT));
                    }
                } else {
                    _log.warn("Client " + _dest + " is local, but we can't find a valid LeaseSet?  perhaps its being rebuilt?");
                }
                requeue(REPUBLISH_LEASESET_DELAY);
                return;
            } else {
                _log.info("Client " + _dest + " is no longer local, so no more republishing their leaseSet");
            }                
            _facade.stopPublishing(_dest);
        } catch (RuntimeException re) {
            _log.error("Uncaught error republishing the leaseSet", re);
            _facade.stopPublishing(_dest);
            throw re;
        }
    }
    
    private class OnSuccess extends JobImpl {
        public OnSuccess(RouterContext ctx) { super(ctx); }
        public String getName() { return "Publish leaseSet successful"; }
        public void runJob() { 
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("successful publishing of the leaseSet for " + _dest.toBase64());
        }
    }
    private class OnFailure extends JobImpl {
        public OnFailure(RouterContext ctx) { super(ctx); }
        public String getName() { return "Publish leaseSet failed"; }
        public void runJob() { 
            if (_log.shouldLog(Log.ERROR))
                _log.error("FAILED publishing of the leaseSet for " + _dest.toBase64());
        }
    }
}
