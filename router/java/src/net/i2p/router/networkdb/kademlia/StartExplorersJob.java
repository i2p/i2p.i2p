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
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Fire off search jobs for random keys from the explore pool, up to MAX_PER_RUN
 * at a time.
 * If the explore pool is empty, just search for a random key.
 *
 */
class StartExplorersJob extends JobImpl {
    private final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    
    /** don't explore more than 1 bucket at a time */
    private static final int MAX_PER_RUN = 1;
    /** dont explore the network more often than this */
    private static final int MIN_RERUN_DELAY_MS = 99*1000;
    /** explore the network at least this often */
    private static final int MAX_RERUN_DELAY_MS = 15*60*1000;
    /** aggressively explore during this time - same as KNDF expiration grace period */
    private static final int STARTUP_TIME = 60*60*1000;
    /** super-aggressively explore if we have less than this many routers */
    private static final int LOW_ROUTERS = 125;
    /** aggressively explore if we have less than this many routers */
    private static final int MIN_ROUTERS = 250;
    /** explore slowly if we have more than this many routers */
    private static final int MAX_ROUTERS = 800;
    
    public StartExplorersJob(RouterContext context, KademliaNetworkDatabaseFacade facade) {
        super(context);
        _log = context.logManager().getLog(StartExplorersJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Start Explorers Job"; }
    public void runJob() {
        if (! (((FloodfillNetworkDatabaseFacade)_facade).floodfillEnabled() ||
               getContext().router().gracefulShutdownInProgress())) {
            int num = MAX_PER_RUN;
            if (_facade.getDataStore().size() < LOW_ROUTERS)
                num *= 3;
            if (getContext().router().getUptime() < STARTUP_TIME)
                num *= 3;
            Set<Hash> toExplore = selectKeysToExplore(num);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Keys to explore during this run: " + toExplore);
            _facade.removeFromExploreKeys(toExplore);
            for (Hash key : toExplore) {
                getContext().jobQueue().addJob(new ExploreJob(getContext(), _facade, key));
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
        long delay = getNextRunDelay();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Updating exploration schedule with a delay of " + delay);
        getTiming().setStartAfter(getContext().clock().now() + delay);        
    }
    
    /**
     *  How long should we wait before exploring?
     *  We wait as long as it's been since we were last successful,
     *  with exceptions.
     */
    private long getNextRunDelay() {
        // we don't explore if floodfill
        if (((FloodfillNetworkDatabaseFacade)_facade).floodfillEnabled())
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
        Set<Hash> rv = new HashSet(num);
        for (Hash key : queued) {
            if (rv.size() >= num) break;
            rv.add(key);
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
