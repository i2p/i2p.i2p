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
import java.util.TreeMap;

import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

class DataRepublishingSelectorJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    
    private final static long RERUN_DELAY_MS = 1*60*1000;
    public final static int MAX_PASSIVE_POOL_SIZE = 10; // no need to have the pool be too big
    
    /**
     * For every bucket away from us, resend period increases by 5 minutes - so we resend
     * our own key every 5 minutes, and keys very far from us every 2.5 hours, increasing
     * linearly
     */
    public final static long RESEND_BUCKET_FACTOR = 5*60*1000;
    
    /**
     * % chance any peer not specializing in the lease's key will broadcast it on each pass
     * of this job /after/ waiting 5 minutes (one RESENT_BUCKET_FACTOR).  In other words,
     * .5% of routers will broadcast a particular unexpired lease to (say) 5 peers every
     * minute.
     *
     */
    private final static int LEASE_REBROADCAST_PROBABILITY = 5;
    /**
     * LEASE_REBROADCAST_PROBABILITY out of LEASE_REBROADCAST_PROBABILITY_SCALE chance.
     */
    private final static int LEASE_REBROADCAST_PROBABILITY_SCALE = 1000;
    
    public DataRepublishingSelectorJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(DataRepublishingSelectorJob.class);
        _facade = facade;
        getTiming().setStartAfter(ctx.clock().now()+RERUN_DELAY_MS); // not immediate...
    }
    
    public String getName() { return "Data Publisher Job"; }
    public void runJob() {
        Set toSend = selectKeysToSend();
        if (_log.shouldLog(Log.INFO))
            _log.info("Keys being queued up for publishing: " + toSend);
        _facade.queueForPublishing(toSend);
        requeue(RERUN_DELAY_MS);
    }
    
    /**
     * Run through the entire data store, ranking how much we want to send each
     * data point, and returning the ones we most want to send so that they can
     * be placed in the passive send pool (without making the passive pool greater
     * than the limit)
     *
     */
    private Set selectKeysToSend() {
        Set alreadyQueued = new HashSet(128);
        alreadyQueued.addAll(_facade.getPassivelySendKeys());
        
        int toAdd = MAX_PASSIVE_POOL_SIZE - alreadyQueued.size();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Keys we need to queue up to fill the passive send pool: " + toAdd);
        if (toAdd <= 0) return new HashSet();
        
        alreadyQueued.addAll(_facade.getExplicitSendKeys());
        
        Set keys = _facade.getDataStore().getKeys();
        keys.removeAll(alreadyQueued);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Total number of keys in the datastore: " + keys.size());
        
        TreeMap toSend = new TreeMap();
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            Long lastPublished = _facade.getLastSent(key);
            long publishRank = rankPublishNeed(key, lastPublished);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Publish rank for " + key + ": " + publishRank);
            if (publishRank > 0) {
                while (toSend.containsKey(new Long(publishRank)))
                    publishRank++;
                toSend.put(new Long(publishRank), key);
            }
        }
        Set rv = new HashSet(toAdd);
        for (Iterator iter = toSend.values().iterator(); iter.hasNext(); ) {
            if (rv.size() > toAdd) break;
            Hash key = (Hash)iter.next();
            rv.add(key);
        }
        return rv;
    }
    
    /**
     * Higher values mean we want to publish it more, and values less than or equal to zero
     * means we don't want to publish it
     *
     */
    private long rankPublishNeed(Hash key, Long lastPublished) {
        int bucket = _facade.getKBuckets().pickBucket(key);
        long sendPeriod = (bucket+1) * RESEND_BUCKET_FACTOR;
        long now = getContext().clock().now();
        if (lastPublished.longValue() < now-sendPeriod) {
            RouterInfo ri = _facade.lookupRouterInfoLocally(key);
            if (ri != null) {
                if (ri.isCurrent(2 * ExpireRoutersJob.EXPIRE_DELAY)) {
                    // last time it was sent was before the last send period
                    return KBucketSet.NUM_BUCKETS - bucket;
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Not republishing router " + key 
                                  + " since it is really old [" 
                                  + (now-ri.getPublished()) + "ms]");
                    return -2;
                }
            } else {
                LeaseSet ls = _facade.lookupLeaseSetLocally(key);
                if (ls != null) {
                    if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                        // last time it was sent was before the last send period
                        return KBucketSet.NUM_BUCKETS - bucket;
                    } else {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Not republishing leaseSet " + key 
                                      + " since it is really old [" 
                                      + (now-ls.getEarliestLeaseDate()) + "ms]");
                        return -3;
                    }
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Key " + key + " is not a leaseSet or routerInfo, definitely not publishing it");
                    return -5;
                }
            }
        } else {
            // its been published since the last period we want to publish it
            
            if (now - RESEND_BUCKET_FACTOR > lastPublished.longValue()) {
                if (_facade.lookupRouterInfoLocally(key) != null) {
                    // randomize the chance of rebroadcast for leases if we haven't
                    // sent it within 5 minutes
                    int val = getContext().random().nextInt(LEASE_REBROADCAST_PROBABILITY_SCALE);
                    if (val <= LEASE_REBROADCAST_PROBABILITY) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Randomized rebroadcast of leases tells us to send " 
                                      + key + ": " + val);
                        return 1;
                    }
                }
            }
            return -1;
        }
    }
}
