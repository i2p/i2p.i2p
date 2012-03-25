package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 *  The network database
 */
public class FloodfillNetworkDatabaseFacade extends KademliaNetworkDatabaseFacade {
    public static final char CAPABILITY_FLOODFILL = 'f';
    private final Map<Hash, FloodSearchJob> _activeFloodQueries;
    private boolean _floodfillEnabled;
    /** for testing, see isFloodfill() below */
    private static String _alwaysQuery;
    private final Set<Hash> _verifiesInProgress;
    private FloodThrottler _floodThrottler;
    private LookupThrottler _lookupThrottler;
    
    public FloodfillNetworkDatabaseFacade(RouterContext context) {
        super(context);
        _activeFloodQueries = new HashMap();
         _verifiesInProgress = new ConcurrentHashSet(8);
        _alwaysQuery = _context.getProperty("netDb.alwaysQuery");

        _context.statManager().createRequiredRateStat("netDb.successTime", "Time for successful lookup (ms)", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.failedTime", "How long a failed search takes", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.retries", "How many additional queries for an iterative search", "NetworkDatabase", new long[] { 60*60*1000l });
        _context.statManager().createRateStat("netDb.failedAttemptedPeers", "How many peers we sent a search to when the search fails", "NetworkDatabase", new long[] { 10*60*1000l });
        _context.statManager().createRateStat("netDb.successPeers", "How many peers are contacted in a successful search", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.failedPeers", "How many peers fail to respond to a lookup?", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.searchCount", "Overall number of searches sent", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.searchMessageCount", "Overall number of mesages for all searches sent", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.searchReplyValidated", "How many search replies we get that we are able to validate (fetch)", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.searchReplyNotValidated", "How many search replies we get that we are NOT able to validate (fetch)", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.searchReplyValidationSkipped", "How many search replies we get from unreliable peers that we skip?", "NetworkDatabase", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.republishQuantity", "How many peers do we need to send a found leaseSet to?", "NetworkDatabase", new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
    }

    @Override
    public void startup() {
        super.startup();
        _context.jobQueue().addJob(new FloodfillMonitorJob(_context, this));
        _lookupThrottler = new LookupThrottler();

        // refresh old routers
        Job rrj = new RefreshRoutersJob(_context, this);
        rrj.getTiming().setStartAfter(_context.clock().now() + 5*60*1000);
        _context.jobQueue().addJob(rrj);
    }

    @Override
    protected void createHandlers() {
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseLookupMessage.MESSAGE_TYPE, new FloodfillDatabaseLookupMessageHandler(_context, this));
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseStoreMessage.MESSAGE_TYPE, new FloodfillDatabaseStoreMessageHandler(_context, this));
    }
    
    /**
     *  If we are floodfill, turn it off and tell everybody.
     *  @since 0.8.9
     */
    @Override
    public void shutdown() {
        if (_floodfillEnabled) {
            // turn off to build a new RI...
            _floodfillEnabled = false;
            // true -> publish inline
            // but job queue is already shut down, so sendStore() called by rebuildRouterInfo() won't work...
            _context.router().rebuildRouterInfo(true);
            // ...so force a flood here
            RouterInfo local = _context.router().getRouterInfo();
            if (local != null && _context.router().getUptime() > PUBLISH_JOB_DELAY) {
                flood(local);
                // let the messages get out...
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {}
            }
        }
        super.shutdown();
    }

    /**
     *  This maybe could be shorter than RepublishLeaseSetJob.REPUBLISH_LEASESET_TIMEOUT,
     *  because we are sending direct, but unresponsive floodfills may take a while due to timeouts.
     */
    static final long PUBLISH_TIMEOUT = 90*1000;
    
    /**
     * Send our RI to the closest floodfill.
     * @throws IllegalArgumentException if the local router info is invalid
     */
    @Override
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        if (localRouterInfo == null) throw new IllegalArgumentException("wtf, null localRouterInfo?");
        // should this be after super? why not publish locally?
        if (_context.router().isHidden()) return; // DE-nied!
        super.publish(localRouterInfo);
        // wait until we've read in the RI's so we can find the closest floodfill
        if (!isInitialized())
            return;
        // no use sending if we have no addresses
        // (unless maybe we used to have addresses? not worth it
        if (localRouterInfo.getAddresses().isEmpty())
            return;
        _log.info("Publishing our RI");
        // Don't delay, helps IB tunnel builds
        //if (_context.router().getUptime() > PUBLISH_JOB_DELAY)
            sendStore(localRouterInfo.getIdentity().calculateHash(), localRouterInfo, null, null, PUBLISH_TIMEOUT, null);
    }
    
    @Override
    public void sendStore(Hash key, DatabaseEntry ds, Job onSuccess, Job onFailure, long sendTimeout, Set toIgnore) {
        // if we are a part of the floodfill netDb, don't send out our own leaseSets as part 
        // of the flooding - instead, send them to a random floodfill peer so *they* can flood 'em out.
        // perhaps statistically adjust this so we are the source every 1/N times... or something.
        if (floodfillEnabled() && (ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)) {
            flood(ds);
            if (onSuccess != null) 
                _context.jobQueue().addJob(onSuccess);
        } else {
            _context.jobQueue().addJob(new FloodfillStoreJob(_context, this, key, ds, onSuccess, onFailure, sendTimeout, toIgnore));
        }
    }

    /**
     *  Increments and tests.
     *  @since 0.7.11
     */
    boolean shouldThrottleFlood(Hash key) {
        return _floodThrottler != null && _floodThrottler.shouldThrottle(key);
    }

    /**
     *  Increments and tests.
     *  @since 0.7.11
     */
    boolean shouldThrottleLookup(Hash from, TunnelId id) {
        return _lookupThrottler.shouldThrottle(from, id);
    }

    private static final int MAX_TO_FLOOD = 7;

    /**
     *  Send to a subset of all floodfill peers.
     *  We do this to implement Kademlia within the floodfills, i.e.
     *  we flood to those closest to the key.
     */
    public void flood(DatabaseEntry ds) {
        Hash key = ds.getHash();
        Hash rkey = _context.routingKeyGenerator().getRoutingKey(key);
        FloodfillPeerSelector sel = (FloodfillPeerSelector)getPeerSelector();
        List peers = sel.selectFloodfillParticipants(rkey, MAX_TO_FLOOD, getKBuckets());
        int flooded = 0;
        for (int i = 0; i < peers.size(); i++) {
            Hash peer = (Hash)peers.get(i);
            RouterInfo target = lookupRouterInfoLocally(peer);
            if ( (target == null) || (_context.shitlist().isShitlisted(peer)) )
                continue;
            // Don't flood a RI back to itself
            // Not necessary, a ff will do its own flooding (reply token == 0)
            //if (peer.equals(target.getIdentity().getHash()))
            //    continue;
            if (peer.equals(_context.routerHash()))
                continue;
            DatabaseStoreMessage msg = new DatabaseStoreMessage(_context);
            msg.setEntry(ds);
            msg.setReplyGateway(null);
            msg.setReplyToken(0);
            msg.setReplyTunnel(null);
            OutNetMessage m = new OutNetMessage(_context);
            m.setMessage(msg);
            m.setOnFailedReplyJob(null);
            m.setPriority(FLOOD_PRIORITY);
            m.setTarget(target);
            m.setExpiration(_context.clock().now()+FLOOD_TIMEOUT);
            // note send failure but don't give credit on success
            // might need to change this
            Job floodFail = new FloodFailedJob(_context, peer);
            m.setOnFailedSendJob(floodFail);
            _context.commSystem().processMessage(m);
            flooded++;
            if (_log.shouldLog(Log.INFO))
                _log.info("Flooding the entry for " + key.toBase64() + " to " + peer.toBase64());
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Flooded the data to " + flooded + " of " + peers.size() + " peers");
    }

    /** note in the profile that the store failed */
    private static class FloodFailedJob extends JobImpl {
        private Hash _peer;
    
        public FloodFailedJob(RouterContext ctx, Hash peer) {
            super(ctx);
            _peer = peer;
        }
        public String getName() { return "Flood failed"; }
        public void runJob() {
            getContext().profileManager().dbStoreFailed(_peer);
        }
    }

    private static final int FLOOD_PRIORITY = 200;
    private static final int FLOOD_TIMEOUT = 30*1000;
    
    @Override
    protected PeerSelector createPeerSelector() { return new FloodfillPeerSelector(_context); }
    
    synchronized void setFloodfillEnabled(boolean yes) {
        _floodfillEnabled = yes;
        if (yes && _floodThrottler == null) {
            _floodThrottler = new FloodThrottler();
            _context.statManager().createRateStat("netDb.floodThrottled", "How often do we decline to flood?", "NetworkDatabase", new long[] { 60*60*1000l });
            // following are for HFDSMJ
            _context.statManager().createRateStat("netDb.storeFloodNew", "How long it takes to flood out a newly received entry?", "NetworkDatabase", new long[] { 60*60*1000l });
            _context.statManager().createRateStat("netDb.storeFloodOld", "How often we receive an old entry?", "NetworkDatabase", new long[] { 60*60*1000l });
        }
    }

    public boolean floodfillEnabled() { return _floodfillEnabled; }
    public static boolean floodfillEnabled(RouterContext ctx) {
        return ((FloodfillNetworkDatabaseFacade)ctx.netDb()).floodfillEnabled();
    }
    
    /**
     *  @param peer may be null, returns false if null
     */
    public static boolean isFloodfill(RouterInfo peer) {
        if (peer == null) return false;
        // For testing or local networks... we will
        // pretend that the specified router is floodfill.
        // Must be set at startup since it's static.
        // In that router, set netDb.floodfillOnly=false.
        // Warning - experts only!
        if (_alwaysQuery != null) {
            Hash aq = new Hash();
            try {
                aq.fromBase64(_alwaysQuery);
                if (aq.equals(peer.getIdentity().getHash()))
                    return true;
            } catch (DataFormatException dfe) {}
        }
        String caps = peer.getCapabilities();
        return caps.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0;
    }

    public List<RouterInfo> getKnownRouterData() {
        List<RouterInfo> rv = new ArrayList();
        DataStore ds = getDataStore();
        if (ds != null) {
            for (DatabaseEntry o : ds.getEntries()) {
                if (o.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
                    rv.add((RouterInfo)o);
            }
        }
        return rv;
    }
    
    /**
     * Begin a kademlia style search for the key specified, which can take up to timeoutMs and
     * will fire the appropriate jobs on success or timeout (or if the kademlia search completes
     * without any match)
     *
     * @return null always
     */
    @Override
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease) {
        //if (true) return super.search(key, onFindJob, onFailedLookupJob, timeoutMs, isLease);
        if (key == null) throw new IllegalArgumentException("searchin for nothin, eh?");
        boolean isNew = false;
        FloodSearchJob searchJob = null;
        synchronized (_activeFloodQueries) {
            searchJob = _activeFloodQueries.get(key);
            if (searchJob == null) {
                //if (SearchJob.onlyQueryFloodfillPeers(_context)) {
                    //searchJob = new FloodOnlySearchJob(_context, this, key, onFindJob, onFailedLookupJob, (int)timeoutMs, isLease);
                    searchJob = new IterativeSearchJob(_context, this, key, onFindJob, onFailedLookupJob, (int)timeoutMs, isLease);
                //} else {
                //    searchJob = new FloodSearchJob(_context, this, key, onFindJob, onFailedLookupJob, (int)timeoutMs, isLease);
                //}
                _activeFloodQueries.put(key, searchJob);
                isNew = true;
            }
        }
        
        if (isNew) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("this is the first search for that key, fire off the FloodSearchJob");
            _context.jobQueue().addJob(searchJob);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Deferring flood search for " + key.toBase64() + " with " + onFindJob);
            searchJob.addDeferred(onFindJob, onFailedLookupJob, timeoutMs, isLease);
            _context.statManager().addRateData("netDb.lookupLeaseSetDeferred", 1, searchJob.getExpiration()-_context.clock().now());
        }
        return null;
    }
    
    /**
     * Ok, the initial set of searches to the floodfill peers timed out, lets fall back on the
     * wider kademlia-style searches
     *
     * Unused - called only by FloodSearchJob which is overridden - don't use this.
     */
/*****
    void searchFull(Hash key, List<Job> onFind, List<Job> onFailed, long timeoutMs, boolean isLease) {
        synchronized (_activeFloodQueries) { _activeFloodQueries.remove(key); }
        
        Job find = null;
        Job fail = null;
        if (onFind != null) {
            synchronized (onFind) {
                if (!onFind.isEmpty())
                    find = onFind.remove(0);
            } 
        }
        if (onFailed != null) {
            synchronized (onFailed) {
                if (!onFailed.isEmpty())
                    fail = onFailed.remove(0);
            }
        }
        SearchJob job = super.search(key, find, fail, timeoutMs, isLease);
        if (job != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Floodfill search timed out for " + key.toBase64() + ", falling back on normal search (#" 
                          + job.getJobId() + ") with " + timeoutMs + " remaining");
            long expiration = timeoutMs + _context.clock().now();
            List<Job> removed = null;
            if (onFind != null) {
                synchronized (onFind) {
                    removed = new ArrayList(onFind);
                    onFind.clear();
                }
                for (int i = 0; i < removed.size(); i++)
                    job.addDeferred(removed.get(i), null, expiration, isLease);
                removed = null;
            }
            if (onFailed != null) {
                synchronized (onFailed) {
                    removed = new ArrayList(onFailed);
                    onFailed.clear();
                }
                for (int i = 0; i < removed.size(); i++)
                    job.addDeferred(null, removed.get(i), expiration, isLease);
                removed = null;
            }
        }
    }
*****/

    /**
     *  Must be called by the search job queued by search() on success or failure
     */
    void complete(Hash key) {
        synchronized (_activeFloodQueries) { _activeFloodQueries.remove(key); }
    }
    
    /** list of the Hashes of currently known floodfill peers;
      * Returned list will not include our own hash.
      *  List is not sorted and not shuffled.
      */
    public List<Hash> getFloodfillPeers() {
        FloodfillPeerSelector sel = (FloodfillPeerSelector)getPeerSelector();
        return sel.selectFloodfillParticipants(getKBuckets());
    }
    
    /** @since 0.7.10 */
    boolean isVerifyInProgress(Hash h) {
        return _verifiesInProgress.contains(h);
    }

    /** @since 0.7.10 */
    void verifyStarted(Hash h) {
        _verifiesInProgress.add(h);
    }

    /** @since 0.7.10 */
    void verifyFinished(Hash h) {
        _verifiesInProgress.remove(h);
    }

    /** NTCP cons drop quickly but SSU takes a while, so it's prudent to keep this
     *  a little higher than 1 or 2. */
    protected final static int MIN_ACTIVE_PEERS = 5;

    /** @since 0.8.7 */
    private static final int MAX_DB_BEFORE_SKIPPING_SEARCH;
    static {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 128*1024*1024l;
        // 250 for every 32 MB, min of 250, max of 1250
        MAX_DB_BEFORE_SKIPPING_SEARCH = (int) Math.max(250l, Math.min(1250l, maxMemory / ((32 * 1024 * 1024l) / 250)));
    }

    /** 
      * Search for a newer router info, drop it from the db if the search fails,
      * unless just started up or have bigger problems.
      */
    @Override
    protected void lookupBeforeDropping(Hash peer, RouterInfo info) {
        // following are some special situations, we don't want to
        // drop the peer in these cases
        // yikes don't do this - stack overflow //  getFloodfillPeers().size() == 0 ||
        // yikes2 don't do this either - deadlock! // getKnownRouters() < MIN_REMAINING_ROUTERS ||
        if (info.getNetworkId() == Router.NETWORK_ID &&
            (getKBucketSetSize() < MIN_REMAINING_ROUTERS ||
             _context.router().getUptime() < DONT_FAIL_PERIOD ||
             _context.commSystem().countActivePeers() <= MIN_ACTIVE_PEERS)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not failing " + peer.toBase64() + " as we are just starting up or have problems");
            return;
        }

        // should we skip the search?
        if (_floodfillEnabled ||
            _context.jobQueue().getMaxLag() > 500 ||
            getKBucketSetSize() > MAX_DB_BEFORE_SKIPPING_SEARCH) {
            // don't try to overload ourselves (e.g. failing 3000 router refs at
            // once, and then firing off 3000 netDb lookup tasks)
            // Also don't queue a search if we have plenty of routerinfos
            // (KBucketSetSize() includes leasesets but avoids locking)
            super.lookupBeforeDropping(peer, info);
            return; 
        }
        // this sends out the search to the floodfill peers even if we already have the
        // entry locally, firing no job if it gets a reply with an updated value (meaning
        // we shouldn't drop them but instead use the new data), or if they all time out,
        // firing the dropLookupFailedJob, which actually removes out local reference
        search(peer, new DropLookupFoundJob(_context, peer, info), new DropLookupFailedJob(_context, peer, info), 10*1000, false);
    }
    
    private class DropLookupFailedJob extends JobImpl {
        private final Hash _peer;
        private final RouterInfo _info;
    
        public DropLookupFailedJob(RouterContext ctx, Hash peer, RouterInfo info) {
            super(ctx);
            _peer = peer;
            _info = info;
        }
        public String getName() { return "Lookup on failure of netDb peer timed out"; }
        public void runJob() {
            dropAfterLookupFailed(_peer, _info);
        }
    }

    private class DropLookupFoundJob extends JobImpl {
        private final Hash _peer;
        private final RouterInfo _info;
    
        public DropLookupFoundJob(RouterContext ctx, Hash peer, RouterInfo info) {
            super(ctx);
            _peer = peer;
            _info = info;
        }
        public String getName() { return "Lookup on failure of netDb peer matched"; }
        public void runJob() {
            RouterInfo updated = lookupRouterInfoLocally(_peer);
            if ( (updated != null) && (updated.getPublished() > _info.getPublished()) ) {
                // great, a legitimate update
            } else {
                // they just sent us what we already had.  kill 'em both
                dropAfterLookupFailed(_peer, _info);
            }
        }
    }
}
