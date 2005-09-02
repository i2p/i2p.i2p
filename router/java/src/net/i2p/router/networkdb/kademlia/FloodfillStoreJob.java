package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

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

    protected int getParallelization() { return 2; }
    protected int getRedundancy() { return 1; }

    /**
     * Send was totally successful
     */
    protected void succeed() {
        super.succeed();
        getContext().jobQueue().addJob(new FloodfillVerifyStoreJob(getContext(), _state.getTarget(), _facade));
    }
    
    public String getName() { return "Floodfill netDb store"; }
}