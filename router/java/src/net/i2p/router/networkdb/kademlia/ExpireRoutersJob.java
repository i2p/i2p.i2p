package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Go through the routing table pick routers that are performing poorly or
 * is out of date, but don't expire routers we're actively tunneling through.
 * If a peer is performing worse than some threshold (via profile.rankLiveliness)
 * drop it and don't ask any questions.  If a peer isn't ranked really poorly, but
 * we just haven't heard from it in a while, drop it and add it to the set of
 * keys we want the netDb to explore.
 *
 */
class ExpireRoutersJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    
    private final static long RERUN_DELAY_MS = 120*1000;
    /**
     * If a routerInfo structure isn't updated within an hour, drop it
     * and search for a later version.  This value should be large enough
     * to deal with the Router.CLOCK_FUDGE_FACTOR.
     */
    public final static long EXPIRE_DELAY = 60*60*1000;
    
    public ExpireRoutersJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireRoutersJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Expire Routers Job"; }
    public void runJob() {
        Set toExpire = selectKeysToExpire();
        _log.info("Routers to expire (drop and try to refetch): " + toExpire);
        for (Iterator iter = toExpire.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            _facade.fail(key);
        }
        _facade.queueForExploration(toExpire);
        
        requeue(RERUN_DELAY_MS);
    }
    
    
    /**
     * Run through all of the known peers and pick ones that have really old
     * routerInfo publish dates, excluding ones that are in use by some tunnels,
     * so that they can be failed & queued for searching
     *
     */
    private Set selectKeysToExpire() {
        Set possible = getNotInUse();
        Set expiring = new HashSet(16);
        long earliestPublishDate = getContext().clock().now() - EXPIRE_DELAY;
        
        for (Iterator iter = possible.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            RouterInfo ri = _facade.lookupRouterInfoLocally(key);
            if (ri != null) {
                if (!ri.isCurrent(EXPIRE_DELAY)) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Expiring RouterInfo for " + key.toBase64() + " [published on " + new Date(ri.getPublished()) + "]");
                    expiring.add(key);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Not expiring routerInfo for " + key.toBase64() + " [published on " + new Date(ri.getPublished()) + "]");
                }
            }
        }
        
        return expiring;
    }
    
    /** all peers not in use by tunnels */
    private Set getNotInUse() {
        Set possible = new HashSet(16);
        for (Iterator iter = _facade.getAllRouters().iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            if (!getContext().tunnelManager().isInUse(peer)) {
                possible.add(peer);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Peer is in use: " + peer.toBase64());
            }
        }
        return possible;
    }
}
