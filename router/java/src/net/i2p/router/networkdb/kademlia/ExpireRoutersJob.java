package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Map;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Go through the routing table pick routers that are
 * is out of date, but don't expire routers we're actively connected to.
 *
 * We could in the future use profile data, netdb total size, a Kademlia XOR distance,
 * or other criteria to minimize netdb size, but for now we just use _facade's
 * validate(), which is a sliding expriation based on netdb size.
 *
 */
class ExpireRoutersJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    
    /** rerun fairly often, so the fails don't queue up too many netdb searches at once */
    private final static long RERUN_DELAY_MS = 5*60*1000;
    private static final int LIMIT_ROUTERS = SystemVersion.isSlow() ? 1000 : 4000;
    
    public ExpireRoutersJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(ExpireRoutersJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Expire Routers Job"; }

    public void runJob() {
        if (getContext().commSystem().getStatus() != Status.DISCONNECTED) {
            int removed = expireKeys();
            if (_log.shouldLog(Log.INFO))
                _log.info("(dbid: " + _facade
                          + "; db size: " + _facade.getKnownRouters()
                          + ") Routers expired: " + removed);
        }
        // TODO adjust frequency based on number removed
        requeue(RERUN_DELAY_MS);
    }
    
    
    /**
     * Run through all of the known peers and pick ones that have really old
     * routerInfo publish dates, excluding ones that we are connected to,
     * so that they can be failed
     *
     * @return number removed
     */
    private int expireKeys() {
        // go through the database directly for efficiency
        Set<Map.Entry<Hash, DatabaseEntry>> entries = _facade.getDataStore().getMapEntries();
        int count = entries.size();
        if (count < 150)
            return 0;
        RouterKeyGenerator gen = getContext().routerKeyGenerator();
        long now = getContext().clock().now();
        long cutoff = now - 30*60*1000;
        // for U routers
        long ucutoff = now - 15*60*1000;
        boolean almostMidnight = gen.getTimeTillMidnight() < FloodfillNetworkDatabaseFacade.NEXT_RKEY_RI_ADVANCE_TIME - 30*60*1000;
        Hash us = getContext().routerHash();
        boolean isFF = _facade.floodfillEnabled();
        byte[] ourRKey = isFF ? us.getData() : null;
        // chance in 128
        int pdrop = Math.max(10, Math.min(80, (128 * count / LIMIT_ROUTERS) - 128));
        int removed = 0;
        if (_log.shouldLog(Log.INFO))
            _log.info("Expiring routers, count = " + count + " drop probability " +
                      (count > LIMIT_ROUTERS ? pdrop * 100 / 128 : 0) + '%');
        for (Map.Entry<Hash, DatabaseEntry> entry : entries) {
            DatabaseEntry e = entry.getValue();
            if (e.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                count--;
                continue;
            }
            Hash key = entry.getKey();
            if (key.equals(us))
                continue;
            // Don't expire anybody we are connected to
            if (getContext().commSystem().isEstablished(key))
                continue;
            if (count > LIMIT_ROUTERS) {
                // aggressive drop strategy
                long pub = e.getDate();
                if (pub < cutoff ||
                    (pub < ucutoff && ((RouterInfo) e).getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0)) {
                    if (isFF) {
                        // don't drop very close to us
                        byte[] rkey = gen.getRoutingKey(key).getData();
                        int distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                        ((rkey[1] ^ ourRKey[1]) & 0xff);
                        // they have to be within 1/256 of the keyspace
                        if (distance < 256)
                            continue;
                        if (almostMidnight) {
                            // almost midnight, recheck with tomorrow's keys
                            rkey = gen.getNextRoutingKey(key).getData();
                            distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                        ((rkey[1] ^ ourRKey[1]) & 0xff);
                            if (distance < 256)
                                continue;
                        }
                    }
                    if (getContext().random().nextInt(128) < pdrop) {
                        _facade.dropAfterLookupFailed(key);
                        removed++;
                    }
                }
            } else {
                // normal drop strategy
                try {
                    if (_facade.validate((RouterInfo) e) != null) {
                        _facade.dropAfterLookupFailed(key);
                        removed++;
                    }
                } catch (IllegalArgumentException iae) {
                    _facade.dropAfterLookupFailed(key);
                    removed++;
                }
            }
        }
        return removed;
    }
}
