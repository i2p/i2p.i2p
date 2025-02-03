package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Periodically search through all leases to find expired ones, failing those
 * keys and firing up a new search for each (in case we want it later, might as
 * well preemptively fetch it)
 *
 */
class ExpireLeasesJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    
    private final static long RERUN_DELAY_MS = 1*60*1000;
    private static final int LIMIT_LEASES_FF = 1250;
    private static final int LIMIT_LEASES_CLIENT = SystemVersion.isSlow() ? 300 : 750;
    
    public ExpireLeasesJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireLeasesJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Expire Lease Sets Job"; }

    public void runJob() {
        List<Hash> toExpire = selectKeysToExpire();
        if (!toExpire.isEmpty()) {
            for (Hash key : toExpire) {
                _facade.fail(key);
            }
            if (_log.shouldInfo())
                _log.info(_facade + " Leases expired: " + toExpire.size());
        }
        requeue(RERUN_DELAY_MS);
    }
    
    /**
     * Run through the entire data store, finding all expired leaseSets (ones that
     * don't have any leases that haven't yet passed, even with the CLOCK_FUDGE_FACTOR)
     *
     */
    private List<Hash> selectKeysToExpire() {
        RouterContext ctx = getContext();
        boolean isClient = _facade.isClientDb();
        boolean isFFDB = _facade.floodfillEnabled() && !isClient;
        Set<Map.Entry<Hash, DatabaseEntry>> entries =  _facade.getDataStore().getMapEntries();
        // clientdb only has leasesets
        List<LeaseSet> current = new ArrayList<LeaseSet>(isFFDB ? 512 : (isClient ? entries.size() : 128));
        List<Hash> toExpire = new ArrayList<Hash>(Math.min(entries.size(), 128));
        int sz = 0;
        for (Map.Entry<Hash, DatabaseEntry> entry : entries) {
            DatabaseEntry obj = entry.getValue();
            if (obj.isLeaseSet()) {
                LeaseSet ls = (LeaseSet)obj;
                Hash h = entry.getKey();
                boolean isLocal = ctx.clientManager().isLocal(h);
                if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    toExpire.add(h);
                    if (isLocal)
                        _log.logAlways(Log.WARN, "Expired local leaseset " + h.toBase32());
                } else if (!isLocal) {
                    // do not aggressive expire RAR LS but still count them
                    sz++;
                    if (!ls.getReceivedAsReply())
                        current.add(ls);
                }
            }
        }
        int origsz = sz;
        int limit = isFFDB ? LIMIT_LEASES_FF : LIMIT_LEASES_CLIENT;
        if (sz > limit) {
            // aggressive drop strategy
            if (isFFDB) {
                RouterKeyGenerator gen = ctx.routerKeyGenerator();
                byte[] ourRKey = ctx.routerHash().getData();
                for (LeaseSet ls : current) {
                    Hash h = ls.getHash();
                    // don't drop very close to us
                    byte[] rkey = gen.getRoutingKey(h).getData();
                    int distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                    ((rkey[1] ^ ourRKey[1]) & 0xff);
                    // they have to be within 1/256 of the keyspace
                    if (distance >= 256) {
                         toExpire.add(h);
                         if (--sz <= limit)
                             break;
                    }
                }
            } else {
                Collections.sort(current, new LeaseSetComparator());
                for (LeaseSet ls : current) {
                     toExpire.add(ls.getHash());
                     //if (_log.shouldInfo())
                     //    _log.info("Aggressive LS expire for " + _facade + '\n' + ls);
                     if (--sz <= limit)
                         break;
                }
            }
            int exp = origsz - sz;
            if (exp > 0 &&  _log.shouldWarn())
                _log.warn("Aggressive LS expire for " + _facade + " removed " + exp +
                          " leasesets, limit " + limit + ", size now " + sz);
        }
        return toExpire;
    }

    /**
     *  Oldest first
     *  @since 0.9.65
     */
    private static class LeaseSetComparator implements Comparator<LeaseSet> {
         public int compare(LeaseSet l, LeaseSet r) {
             long dl = l.getLatestLeaseDate();
             long dr = r.getLatestLeaseDate();
             if (dl < dr) return -1;
             if (dl > dr) return 1;
             return 0;
        }
    }
}
