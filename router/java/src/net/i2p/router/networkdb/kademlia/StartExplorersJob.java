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
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Fire off search jobs for random keys from the explore pool, up to MAX_PER_RUN
 * at a time.
 * If the explore pool is empty, just search for a random key.
 *
 * For hidden mode routers, this is the primary mechanism for staying integrated.
 * The goal is to keep known router count above LOW_ROUTERS and
 * the known floodfill count above LOW_FFS.
 */
class StartExplorersJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    
    /** don't explore more than 1 bucket at a time */
    private static final int MAX_PER_RUN = 1;
    /** dont explore the network more often than this */
    private static final int MIN_RERUN_DELAY_MS = 55*1000;
    /** explore the network at least this often */
    private static final int MAX_RERUN_DELAY_MS = 15*60*1000;
    /** aggressively explore during this time - same as KNDF expiration grace period */
    private static final int STARTUP_TIME = 60*60*1000;
    /** super-aggressively explore if we have less than this many routers.
        The goal here is to avoid reseeding.
     */
    /** very aggressively explore if we have less than this many routers */
    private static final int MIN_ROUTERS = 3 * KademliaNetworkDatabaseFacade.MIN_RESEED;
    /** aggressively explore if we have less than this many routers */
    private static final int LOW_ROUTERS = 2 * MIN_ROUTERS;
    /** explore slowly if we have more than this many routers */
    private static final int MAX_ROUTERS = 2 * LOW_ROUTERS;
    private static final int MIN_FFS = 50;
    static final int LOW_FFS = 2 * MIN_FFS;

    private static final long MAX_LAG = 100;
    private static final long MAX_MSG_DELAY = 1500;
    
    public StartExplorersJob(RouterContext context, KademliaNetworkDatabaseFacade facade) {
        super(context);
        _log = context.logManager().getLog(StartExplorersJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Start Explorers Job"; }

    public void runJob() {
        if (! (_facade.floodfillEnabled() ||
               getContext().jobQueue().getMaxLag() > MAX_LAG ||
               getContext().throttle().getMessageDelay() > MAX_MSG_DELAY ||
               // message delay limit also?
               getContext().router().gracefulShutdownInProgress())) {
            int num = MAX_PER_RUN;
            int count = _facade.getDataStore().size();
            if (count < MIN_ROUTERS)
                num *= 15;  // at less than 3x MIN_RESEED, explore extremely aggressively
            else if (count < LOW_ROUTERS)
                num *= 10;  // 3x was not sufficient to keep hidden routers from losing peers
            if (getContext().router().getUptime() < STARTUP_TIME ||
                getContext().router().isHidden())
                num *= 2;
            Set<Hash> toExplore = selectKeysToExplore(num);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Keys to explore during this run: " + toExplore + ", wanted " + num + ", got " + toExplore.size());
            _facade.removeFromExploreKeys(toExplore);
            long delay = 0;

            // If we're below about 30 ffs, standard exploration stops working well.
            // A non-exploratory "exploration" finds us floodfills quickly.
            // This is vital when in hidden mode, where this is our primary method
            // of maintaining sufficient peers and avoiding repeated reseeding.
            int ffs = getContext().peerManager().countPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
            boolean needffs = ffs < MIN_FFS;
            boolean lowffs = ffs < LOW_FFS;
            for (Hash key : toExplore) {
                // Last param false means get floodfills (non-explore)
                // This is very effective so we don't need to do it often
                boolean realexpl = !((needffs && getContext().random().nextInt(2) == 0) ||
                                    (lowffs && getContext().random().nextInt(4) == 0));
                ExploreJob j = new ExploreJob(getContext(), _facade, key, realexpl);
                if (delay > 0)
                    j.getTiming().setStartAfter(getContext().clock().now() + delay);
                getContext().jobQueue().addJob(j);
                // spread them out
                delay += 1000 + getContext().random().nextInt(512);
            }
        }
        long delay = getNextRunDelay();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Reenqueueing the exploration with a delay of " + delay);
        requeue(delay);
    }
    
    /** 
     * the exploration has found some new peers - update the schedule so that 
     * we'll explore appropriately.
     */
    public void updateExploreSchedule() {
        // This is playing havoc with the JobQueue and putting this job out-of-order
        // since we switched to a TreeSet,
        // so just let runJob() above do the scheduling.
        //long delay = getNextRunDelay();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Updating exploration schedule with a delay of " + delay);
        //requeue(delay);        
    }
    
    /**
     *  How long should we wait before exploring?
     *  We wait as long as it's been since we were last successful,
     *  with exceptions.
     */
    private long getNextRunDelay() {
        // we don't explore if floodfill
        if (_facade.floodfillEnabled())
            return MAX_RERUN_DELAY_MS;

        // If we don't know too many peers, or just started, explore aggressively
        // Also if hidden or K, as nobody will be connecting to us
        // Use DataStore.size() which includes leasesets because it's faster
        if (getContext().router().getUptime() < STARTUP_TIME ||
            _facade.getDataStore().size() < MIN_ROUTERS ||
            getContext().router().isHidden())
            return MIN_RERUN_DELAY_MS;
        RouterInfo ri = getContext().router().getRouterInfo();
        if (ri != null && ri.getCapabilities().contains("" + Router.CAPABILITY_BW12))
            return MIN_RERUN_DELAY_MS;
        if (_facade.getDataStore().size() > MAX_ROUTERS)
            return MAX_RERUN_DELAY_MS;

        long delay = getContext().clock().now() - _facade.getLastExploreNewDate();
        if (delay < MIN_RERUN_DELAY_MS) 
            return MIN_RERUN_DELAY_MS;
        else if (delay > MAX_RERUN_DELAY_MS)
            return MAX_RERUN_DELAY_MS;
        else
            return delay;
    }
    
    /**
     * Run through the explore pool and pick out some values
     *
     * Nope, ExploreKeySelectorJob is disabled, so the explore pool
     * may be empty. In that case, generate random keys.
     */
    private Set<Hash> selectKeysToExplore(int num) {
        Set<Hash> queued = _facade.getExploreKeys();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Keys waiting for exploration: " + queued.size());
        Set<Hash> rv = new HashSet<Hash>(num);
        for (Hash key : queued) {
            rv.add(key);
            if (rv.size() >= num) break;
        }
        for (int i = rv.size(); i < num; i++) {
            byte hash[] = new byte[Hash.HASH_LENGTH];
            getContext().random().nextBytes(hash);
            Hash key = new Hash(hash);
            rv.add(key);
        }
        return rv;
    }
}
