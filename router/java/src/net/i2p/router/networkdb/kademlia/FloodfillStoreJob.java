package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;

class FloodfillStoreJob extends StoreJob {    
    private FloodfillNetworkDatabaseFacade _facade;
    /**
     * Create a new search for the routingKey specified
     * 
     */
    public FloodfillStoreJob(RouterContext context, FloodfillNetworkDatabaseFacade facade, Hash key, DataStructure data, Job onSuccess, Job onFailure, long timeoutMs) {
        this(context, facade, key, data, onSuccess, onFailure, timeoutMs, null);
    }
    
    /**
     * @param toSkip set of peer hashes of people we dont want to send the data to (e.g. we
     *               already know they have it).  This can be null.
     */
    public FloodfillStoreJob(RouterContext context, FloodfillNetworkDatabaseFacade facade, Hash key, DataStructure data, Job onSuccess, Job onFailure, long timeoutMs, Set toSkip) {
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
            // Get the time stamp from the data we sent, so the Verify job can meke sure that
            // it finds something stamped with that time or newer.
            long published = 0;
            boolean isRouterInfo = false;
            DataStructure data = _state.getData();
            if (data instanceof RouterInfo) {
                published = ((RouterInfo) data).getPublished();
                isRouterInfo = true;
            } else if (data instanceof LeaseSet) {
                published = ((LeaseSet) data).getEarliestLeaseDate();
            }
            getContext().jobQueue().addJob(new FloodfillVerifyStoreJob(getContext(), _state.getTarget(),
                                                                       published, isRouterInfo, _facade));
        }
    }
    
    @Override
    public String getName() { return "Floodfill netDb store"; }
}
