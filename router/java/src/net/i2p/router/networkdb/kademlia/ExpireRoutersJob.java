package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collections;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Go through the routing table pick routers that are
 * is out of date, but don't expire routers we're actively connected to.
 *
 * We could in the future use profile data, netdb total size, a Kademlia XOR distance,
 * or other criteria to minimize netdb size, but for now we just use _facade's
 * validate(), which is a sliding expriation based on netdb size.
 *
 * @deprecated unused - see comments in KNDF
 */
class ExpireRoutersJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    
    /** rerun fairly often, so the fails don't queue up too many netdb searches at once */
    private final static long RERUN_DELAY_MS = 120*1000;
    
    public ExpireRoutersJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireRoutersJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Expire Routers Job"; }

    public void runJob() {
        // this always returns an empty set (see below)
        Set<Hash> toExpire = selectKeysToExpire();
        if (_log.shouldLog(Log.INFO))
            _log.info("Routers to expire (drop and try to refetch): " + toExpire);
        for (Hash key : toExpire) {
            _facade.fail(key);
        }
        _facade.queueForExploration(toExpire);
        
        requeue(RERUN_DELAY_MS);
    }
    
    
    /**
     * Run through all of the known peers and pick ones that have really old
     * routerInfo publish dates, excluding ones that we are connected to,
     * so that they can be failed & queued for searching
     *
     * @return nothing for now
     */
    private Set<Hash> selectKeysToExpire() {
        for (Hash key : _facade.getAllRouters()) {
            // Don't expire anybody we are connected to
            if (!getContext().commSystem().isEstablished(key)) {
                // This does a _facade.validate() and fail() which is sufficient...
                // no need to impose our own expiration here.
                // One issue is this will queue a ton of floodfill queries the first time it is run
                // after the 1h router startup grace period.
                _facade.lookupRouterInfoLocally(key);
            }
        }
        
        // let _facade do all the work for now
        return Collections.EMPTY_SET;
    }
}
