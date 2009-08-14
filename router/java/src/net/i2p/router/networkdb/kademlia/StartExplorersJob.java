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

import net.i2p.data.Hash;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Fire off search jobs for random keys from the explore pool, up to MAX_PER_RUN
 * at a time.
 * If the explore pool is empty, just search for a random key.
 *
 */
class StartExplorersJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    
    /** don't explore more than 1 bucket at a time */
    private static final int MAX_PER_RUN = 1;
    /** dont explore the network more often than this */
    private static final int MIN_RERUN_DELAY_MS = 5*60*1000;
    /** explore the network at least once every thirty minutes */
    private static final int MAX_RERUN_DELAY_MS = 30*60*1000;
    
    public StartExplorersJob(RouterContext context, KademliaNetworkDatabaseFacade facade) {
        super(context);
        _log = context.logManager().getLog(StartExplorersJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Start Explorers Job"; }
    public void runJob() {
        if (! (((FloodfillNetworkDatabaseFacade)_facade).floodfillEnabled() ||
               getContext().router().gracefulShutdownInProgress())) {
            Set toExplore = selectKeysToExplore();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Keys to explore during this run: " + toExplore);
            _facade.removeFromExploreKeys(toExplore);
            for (Iterator iter = toExplore.iterator(); iter.hasNext(); ) {
                Hash key = (Hash)iter.next();
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
    
    /** how long should we wait before exploring? */
    private long getNextRunDelay() {
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
    private Set selectKeysToExplore() {
        Set queued = _facade.getExploreKeys();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Keys waiting for exploration: " + queued.size());
        Set rv = new HashSet(MAX_PER_RUN);
        for (Iterator iter = queued.iterator(); iter.hasNext(); ) {
            if (rv.size() >= MAX_PER_RUN) break;
            rv.add(iter.next());
        }
        for (int i = rv.size(); i < MAX_PER_RUN; i++) {
            byte hash[] = new byte[Hash.HASH_LENGTH];
            getContext().random().nextBytes(hash);
            Hash key = new Hash(hash);
            rv.add(key);
        }
        return rv;
    }
}
