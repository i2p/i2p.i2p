package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Periodically search through all leases to find expired ones, failing those
 * keys and firing up a new search for each (in case we want it later, might as
 * well preemptively fetch it)
 *
 */
class ExpireLeasesJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    
    private final static long RERUN_DELAY_MS = 1*60*1000;
    
    public ExpireLeasesJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireLeasesJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Expire Lease Sets Job"; }
    public void runJob() {
        Set toExpire = selectKeysToExpire();
        _log.info("Leases to expire: " + toExpire);
        for (Iterator iter = toExpire.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            _facade.fail(key);
            //_log.info("Lease " + key + " is expiring, so lets look for it again", new Exception("Expire and search"));
            //_facade.lookupLeaseSet(key, null, null, RERUN_DELAY_MS);
        }
        //_facade.queueForExploration(toExpire); // don't do explicit searches, just explore passively
        requeue(RERUN_DELAY_MS);
    }
    
    /**
     * Run through the entire data store, finding all expired leaseSets (ones that
     * don't have any leases that haven't yet passed, even with the CLOCK_FUDGE_FACTOR)
     *
     */
    private Set selectKeysToExpire() {
        Set keys = _facade.getDataStore().getKeys();
        Set toExpire = new HashSet(128);
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            DatabaseEntry obj = _facade.getDataStore().get(key);
            if (obj.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
                LeaseSet ls = (LeaseSet)obj;
                if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR))
                    toExpire.add(key);
                else
                    _log.debug("Lease " + ls.getDestination().calculateHash() + " is current, no need to expire");
            }
        }
        return toExpire;
    }
}
