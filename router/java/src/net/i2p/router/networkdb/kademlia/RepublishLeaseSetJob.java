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
class RepublishLeaseSetJob extends JobImpl {
    private final Log _log;
    public final static long REPUBLISH_LEASESET_TIMEOUT = 60*1000;
    private final static int RETRY_DELAY = 20*1000;
    private final Hash _dest;
    private final KademliaNetworkDatabaseFacade _facade;
    /** this is actually last attempted publish */
    private long _lastPublished;
    
    public RepublishLeaseSetJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade, Hash destHash) {
        super(ctx);
        _log = ctx.logManager().getLog(RepublishLeaseSetJob.class);
        _facade = facade;
        _dest = destHash;
    }

    public String getName() { return "Republish a local leaseSet"; }

    public void runJob() {
        if (!getContext().clientManager().shouldPublishLeaseSet(_dest))
            return;
        
        try {
            if (getContext().clientManager().isLocal(_dest)) {
                LeaseSet ls = getContext().netDb().lookupLeaseSetLocally(_dest, _dest.toBase32());
                if (ls != null) {
                    if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Not publishing a LOCAL lease that isn't current - " + _dest.toBase32(), new Exception("Publish expired LOCAL lease?"));
                    } else {
                        if (_log.shouldInfo())
                            _log.info(getJobId() + ": Publishing LS for " + _dest.toBase32());
                        getContext().statManager().addRateData("netDb.republishLeaseSetCount", 1);
                        _facade.sendStore(_dest, ls, null, new OnRepublishFailure(ls), REPUBLISH_LEASESET_TIMEOUT, null);
                        _lastPublished = getContext().clock().now();
                    }
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Client " + _dest.toBase32() + " is local, but we can't find a valid LeaseSet?  perhaps its being rebuilt?");
                }
                return;
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Client " + _dest.toBase32() + " is no longer local, so no more republishing their leaseSet");
            }                
            _facade.stopPublishing(_dest);
        } catch (RuntimeException re) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Uncaught error republishing the leaseSet", re);
            _facade.stopPublishing(_dest);
            throw re;
        }
    }
    
    void requeueRepublish() {
        if (_log.shouldWarn())
            _log.warn("Failed publishing of the leaseSet for " + _dest.toBase32());
        getContext().jobQueue().removeJob(this);
        requeue(RETRY_DELAY + getContext().random().nextInt(RETRY_DELAY));
    }

    /**
     * @return last attempted publish time, or 0 if never
     */
    public long lastPublished() {
        return _lastPublished;
    }

    /** requeue */
    private class OnRepublishFailure extends JobImpl {
        private final LeaseSet _ls;

        public OnRepublishFailure(LeaseSet ls) { 
            super(RepublishLeaseSetJob.this.getContext()); 
            _ls = ls;
        }

        public String getName() { return "Publish leaseSet failed"; }

        public void runJob() {
            // Don't requeue if there's a newer LS, KNDF will have already done that
            LeaseSet ls = null;
            if (_dest != null)
                ls = getContext().netDb().lookupLeaseSetLocally(_ls.getHash(), _dest.toBase32());
            else
                getContext().netDb().lookupLeaseSetLocally(_ls.getHash(), null);
                // ^ _dest should never be null here, right? So maybe instead we return immediately?
            if (ls != null && ls.getEarliestLeaseDate() == _ls.getEarliestLeaseDate()) {
                requeueRepublish();
            } else {
                if (_log.shouldWarn())
                    _log.warn(getJobId() + ": Failed publishing LS for " + _ls.getDestination().toBase32() + " but not requeueing, there is a newer LS");
            }
        }
    }
}
