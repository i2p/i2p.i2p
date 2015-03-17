package net.i2p.router.networkdb.kademlia;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Go through all the routers once, after startup, and refetch their router infos.
 * This should be run once after startup (and preferably after any reseed is complete,
 * but we don't have any indication when that is).
 * This will help routers that start after being shutdown for many days or weeks,
 * as well as newly-reseeded routers, since
 * validate() in KNDF doesn't start failing and refetching until the router has been
 * up for an hour.
 * To improve integration even more, we fetch the floodfills first.
 * Ideally this should complete within the first half-hour of uptime.
 *
 * @since 0.8.8
 */
class RefreshRoutersJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;
    private List<Hash> _routers;
    
    /** rerun fairly often. 1500 routers in 50 minutes */
    private final static long RERUN_DELAY_MS = 2*1000;
    private final static long EXPIRE = 60*60*1000;
    
    public RefreshRoutersJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(RefreshRoutersJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Refresh Routers Job"; }

    public void runJob() {
        if (_facade.isInitialized()) {
            if (_routers == null) {
                // make a list of all routers, floodfill first
                _routers = _facade.getFloodfillPeers();
                int ff = _routers.size();
                Set<Hash> all = _facade.getAllRouters();
                all.removeAll(_routers);
                int non = all.size();
                _routers.addAll(all);
                if (_log.shouldLog(Log.INFO))
                    _log.info("To check: " + ff + " floodfills and " + non + " non-floodfills");
            }
            if (_routers.isEmpty()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Finished");
                return;
            }
            long expire = getContext().clock().now() - EXPIRE;
            for (Iterator<Hash> iter = _routers.iterator(); iter.hasNext(); ) {
                Hash h = iter.next();
                iter.remove();
                if (h.equals(getContext().routerHash()))
                    continue;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Checking " + h);
                RouterInfo ri = _facade.lookupRouterInfoLocally(h);
                if (ri == null)
                    continue;
                if (ri.getPublished() < expire) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Refreshing " + h);
                    _facade.search(h, null, null, 15*1000, false);
                    break;
                }
            }
        }
        requeue(RERUN_DELAY_MS);
    }
}
