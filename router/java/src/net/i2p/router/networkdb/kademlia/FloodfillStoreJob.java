package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.NoSuchElementException;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  This extends StoreJob to fire off a FloodfillVerifyStoreJob after success.
 *
 */
class FloodfillStoreJob extends StoreJob {    
    private final FloodfillNetworkDatabaseFacade _facade;

    /**
     * Send a data structure to the floodfills
     * 
     */
    public FloodfillStoreJob(RouterContext context, FloodfillNetworkDatabaseFacade facade, Hash key, DatabaseEntry data, Job onSuccess, Job onFailure, long timeoutMs) {
        this(context, facade, key, data, onSuccess, onFailure, timeoutMs, null);
    }
    
    /**
     * @param toSkip set of peer hashes of people we dont want to send the data to (e.g. we
     *               already know they have it).  This can be null.
     */
    public FloodfillStoreJob(RouterContext context, FloodfillNetworkDatabaseFacade facade, Hash key, DatabaseEntry data, Job onSuccess, Job onFailure, long timeoutMs, Set<Hash> toSkip) {
        super(context, facade, key, data, onSuccess, onFailure, timeoutMs, toSkip);
        _facade = facade;
    }

    @Override
    protected int getParallelization() { return 1; }

    @Override
    protected int getRedundancy() { return 1; }

    /**
     * Send was totally successful
     */
    @Override
    protected void succeed() {
        super.succeed();

        if (_state != null) {
            if (_facade.isVerifyInProgress(_state.getTarget())) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Skipping verify, one already in progress for: " + _state.getTarget());
                return;
            }
            // Get the time stamp from the data we sent, so the Verify job can meke sure that
            // it finds something stamped with that time or newer.
            DatabaseEntry data = _state.getData();
            boolean isRouterInfo = data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO;
            long published = data.getDate();

            // we should always have exactly one successful entry
            Hash sentTo = null;
            try {
                sentTo = _state.getSuccessful().iterator().next();
            } catch (NoSuchElementException nsee) {}
            getContext().jobQueue().addJob(new FloodfillVerifyStoreJob(getContext(), _state.getTarget(),
                                                                       published, isRouterInfo, sentTo, _facade));
        }
    }
    
    @Override
    public String getName() { return "Floodfill netDb store"; }
}
