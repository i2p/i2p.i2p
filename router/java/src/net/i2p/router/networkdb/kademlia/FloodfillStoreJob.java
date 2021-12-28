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

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  This extends StoreJob to fire off a FloodfillVerifyStoreJob after success.
 *
 *  Stores through this class always request a reply.
 *
 */
class FloodfillStoreJob extends StoreJob {    
    private final FloodfillNetworkDatabaseFacade _facade;

    private static final String PROP_RI_VERIFY = "router.verifyRouterInfoStore";
    private static final long RI_VERIFY_STARTUP_TIME = 3*60*60*1000L;

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
    public FloodfillStoreJob(RouterContext context, FloodfillNetworkDatabaseFacade facade, Hash key, DatabaseEntry data,
                             Job onSuccess, Job onFailure, long timeoutMs, Set<Hash> toSkip) {
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

        final boolean shouldLog = _log.shouldInfo();
        final Hash key = _state.getTarget();

            if (_facade.isVerifyInProgress(key)) {
                if (shouldLog)
                    _log.info("Skipping verify, one already in progress for: " + key);
                return;
            }
            RouterContext ctx = getContext();
            if (ctx.router().gracefulShutdownInProgress()) {
                if (shouldLog)
                    _log.info("Skipping verify, shutdown in progress for: " + key);
                return;
            }
            // Get the time stamp from the data we sent, so the Verify job can meke sure that
            // it finds something stamped with that time or newer.
            DatabaseEntry data = _state.getData();
            final int type = data.getType();
            final boolean isRouterInfo = type == DatabaseEntry.KEY_TYPE_ROUTERINFO;
            // default false since 0.9.7.1
            // verify for a while after startup until we've vetted the floodfills
            if (isRouterInfo && !ctx.getBooleanProperty(PROP_RI_VERIFY) &&
                ctx.router().getUptime() > RI_VERIFY_STARTUP_TIME) {
                _facade.routerInfoPublishSuccessful();
                return;
            }

            final boolean isls2 = data.isLeaseSet() && type != DatabaseEntry.KEY_TYPE_LEASESET;
            long published;
            if (isls2) {
                LeaseSet2 ls2 = (LeaseSet2) data;
                published = ls2.getPublished();
            } else {
                published = data.getDate();
            }
            // we should always have exactly one successful entry
            Hash sentTo = _state.getSuccessful();
            Hash client;
            if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                // get the real client hash
                client = ((LeaseSet)data).getDestination().calculateHash();
            } else {
                client = key;
            }
            Job fvsj = new FloodfillVerifyStoreJob(ctx, key, client,
                                                   published, type,
                                                   sentTo, _state.getAttempted(), _facade);
            if (shouldLog)
                _log.info(getJobId() + ": Succeeded sending key " + key +
                          ", queueing verify job " + fvsj.getJobId());
            ctx.jobQueue().addJob(fvsj);
    }
    
    @Override
    public String getName() { return "Floodfill netDb store"; }
}
