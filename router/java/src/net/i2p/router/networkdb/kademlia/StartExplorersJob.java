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
import net.i2p.router.JobQueue;
import net.i2p.util.Log;

/**
 * Fire off search jobs for random keys from the explore pool, up to MAX_PER_RUN
 * at a time.  
 *
 */
class StartExplorersJob extends JobImpl {
    private final static Log _log = new Log(StartExplorersJob.class);
    private KademliaNetworkDatabaseFacade _facade;

    private final static long RERUN_DELAY_MS = 3*60*1000; // every 3 minutes, explore MAX_PER_RUN keys
    private final static int MAX_PER_RUN = 3; // don't explore more than 1 bucket at a time
    
    public StartExplorersJob(KademliaNetworkDatabaseFacade facade) {
	super();
	_facade = facade;
    }

    public String getName() { return "Start Explorers Job"; }
    public void runJob() { 
	Set toExplore = selectKeysToExplore();
	_log.debug("Keys to explore during this run: " + toExplore);
	_facade.removeFromExploreKeys(toExplore);
	for (Iterator iter = toExplore.iterator(); iter.hasNext(); ) {
	    Hash key = (Hash)iter.next();
	    //_log.info("Starting explorer for " + key, new Exception("Exploring!"));
	    JobQueue.getInstance().addJob(new ExploreJob(_facade, key));
	}
	requeue(RERUN_DELAY_MS);
    }

    /**
     * Run through the explore pool and pick out some values
     *
     */
    private Set selectKeysToExplore() {
	Set queued = _facade.getExploreKeys();
	if (queued.size() <= MAX_PER_RUN)
	    return queued;
	Set rv = new HashSet(MAX_PER_RUN);
	for (Iterator iter = queued.iterator(); iter.hasNext(); ) {
	    if (rv.size() >= MAX_PER_RUN) break;
	    rv.add(iter.next());
	}
	return rv;
    }
}
