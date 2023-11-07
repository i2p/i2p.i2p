package net.i2p.router.networkdb.kademlia;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
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
 * As of 0.9.45, periodically rerun, to maintain a minimum number of
 * floodfills, primarily for hidden mode. StartExplorersJob will get us
 * to about 100 ffs and maintain that for a while, but they will eventually
 * start to expire. Use this to get us to 300 or more. Each pass of this
 * will gain us about 150 ffs. If we have more than 300 ffs, we just
 * requeue to check later. Otherwise this will grow our netdb
 * almost unbounded, as it prevents most normal expiration.
 *
 * @since 0.8.8
 */
class RefreshRoutersJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;
    private List<Hash> _routers;
    private boolean _wasRun;
    
    /** rerun fairly often. 1000 routers in 50 minutes
     *  Don't go faster as this overloads the expl. OBEP / IBGW
     */
    private final static long RERUN_DELAY_MS = 2500;
    private final static long EXPIRE = 2*60*60*1000;
    private final static long NEW_LOOP_DELAY = 37*60*1000;
    private static final int ENOUGH_FFS = 3 * StartExplorersJob.LOW_FFS;
    
    public RefreshRoutersJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(RefreshRoutersJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Refresh Routers Job"; }

    public void runJob() {
        if (_facade.isInitialized()) {
            if (_routers == null) {
                if (_wasRun) {
                    int ffs = getContext().peerManager().countPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
                    if (ffs >= ENOUGH_FFS) {
                        requeue(NEW_LOOP_DELAY);
                        return;
                    }
                } else {
                    _wasRun = true;
                }
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
                // despite best efforts in StartExplorersJob,
                // hidden mode routers have trouble keeping peers
                // but we'll do this for everybody just in case
                _routers = null;
                requeue(NEW_LOOP_DELAY);
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
        requeue(RERUN_DELAY_MS + getContext().random().nextInt(1024));
    }
}
