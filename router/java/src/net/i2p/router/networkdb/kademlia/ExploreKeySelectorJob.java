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
 * Go through the kbuckets and generate random keys for routers in buckets not
 * yet full, attempting to keep a pool of keys we can explore with (at least one
 * per bucket)
 *
 * @deprecated unused, see comments in KNDF
 */
class ExploreKeySelectorJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    
    private final static long RERUN_DELAY_MS = 60*1000;
    
    public ExploreKeySelectorJob(RouterContext context, KademliaNetworkDatabaseFacade facade) {
        super(context);
        _log = context.logManager().getLog(ExploreKeySelectorJob.class);
        _facade = facade;
    }
    
    public String getName() { return "Explore Key Selector Job"; }
    public void runJob() {
        if (((FloodfillNetworkDatabaseFacade)_facade).floodfillEnabled()) {
            requeue(30*RERUN_DELAY_MS);
            return;
        }
        Set toExplore = selectKeysToExplore();
        _log.info("Filling the explorer pool with: " + toExplore);
        if (toExplore != null)
            _facade.queueForExploration(toExplore);
        requeue(RERUN_DELAY_MS);
    }
    
    /**
     * Run through all kbuckets with too few routers and generate a random key
     * for it, with a maximum number of keys limited by the exploration pool size
     *
     */
    private Set selectKeysToExplore() {
        Set alreadyQueued = _facade.getExploreKeys();
        if (alreadyQueued.size() > KBucketSet.NUM_BUCKETS) return null;
        Set toExplore = new HashSet(KBucketSet.NUM_BUCKETS - alreadyQueued.size());
        for (int i = 0; i < KBucketSet.NUM_BUCKETS; i++) {
            KBucket bucket = _facade.getKBuckets().getBucket(i);
            if (bucket.getKeyCount() < KBucketSet.BUCKET_SIZE) {
                boolean already = false;
                for (Iterator iter = alreadyQueued.iterator(); iter.hasNext(); ) {
                    Hash key = (Hash)iter.next();
                    if (bucket.shouldContain(key)) {
                        already = true;
                        _log.debug("Bucket " + i + " is already queued for exploration \t" + key);
                        break;
                    }
                }
                if (!already) {
                    // no keys are queued for exploring this still-too-small bucket yet
                    Hash key = bucket.generateRandomKey();
                    _log.debug("Bucket " + i + " is NOT queued for exploration, and it only has " + bucket.getKeyCount() + " keys, so explore with \t" + key);
                    toExplore.add(key);
                }
            } else {
                _log.debug("Bucket " + i + " already has enough keys (" + bucket.getKeyCount() + "), no need to explore further");
            }
        }
        return toExplore;
    }
    
}
