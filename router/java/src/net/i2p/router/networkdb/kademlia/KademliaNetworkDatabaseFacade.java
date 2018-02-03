package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.KeyCertificate;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.RejectTrimmer;
import net.i2p.kademlia.SelectionCollector;
import net.i2p.router.Job;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.networkdb.PublishLocalRouterInfoJob;
import net.i2p.router.networkdb.reseed.ReseedChecker;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 * Kademlia based version of the network database.
 * Never instantiated directly; see FloodfillNetworkDatabaseFacade.
 */
public class KademliaNetworkDatabaseFacade extends NetworkDatabaseFacade {
    protected final Log _log;
    private KBucketSet<Hash> _kb; // peer hashes sorted into kbuckets, but within kbuckets, unsorted
    private DataStore _ds; // hash to DataStructure mapping, persisted when necessary
    /** where the data store is pushing the data */
    private String _dbDir;
    // set of Hash objects that we should search on (to fill up a bucket, not to get data)
    private final Set<Hash> _exploreKeys = new ConcurrentHashSet<Hash>(64);
    private boolean _initialized;
    /** Clock independent time of when we started up */
    private long _started;
    private StartExplorersJob _exploreJob;
    /** when was the last time an exploration found something new? */
    private long _lastExploreNew;
    protected final PeerSelector _peerSelector;
    protected final RouterContext _context;
    private final ReseedChecker _reseedChecker;
    private volatile long _lastRIPublishTime;
    private NegativeLookupCache _negativeCache;
    protected final int _networkID;

    /** 
     * Map of Hash to RepublishLeaseSetJob for leases we'realready managing.
     * This is added to when we create a new RepublishLeaseSetJob, and the values are 
     * removed when the job decides to stop running.
     *
     */
    private final Map<Hash, RepublishLeaseSetJob> _publishingLeaseSets;
    
    /** 
     * Hash of the key currently being searched for, pointing the SearchJob that
     * is currently operating.  Subsequent requests for that same key are simply
     * added on to the list of jobs fired on success/failure
     *
     */
    private final Map<Hash, SearchJob> _activeRequests;
    
    /**
     * The search for the given key is no longer active
     *
     */
    void searchComplete(Hash key) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("search Complete: " + key);
        synchronized (_activeRequests) {
            _activeRequests.remove(key);
        }
    }
    
    /**
     * for the 10 minutes after startup, don't fail db entries so that if we were
     * offline for a while, we'll have a chance of finding some live peers with the
     * previous references
     */
    protected final static long DONT_FAIL_PERIOD = 10*60*1000;
    
    /** don't probe or broadcast data, just respond and search when explicitly needed */
    private static final boolean QUIET = false;
    
    public final static String PROP_DB_DIR = "router.networkDatabase.dbDir";
    public final static String DEFAULT_DB_DIR = "netDb";
    
    /** Reseed if below this.
     *  @since 0.9.4
     */
    static final int MIN_RESEED = ReseedChecker.MINIMUM;

    /** if we have less than this many routers left, don't drop any more,
     *  even if they're failing or doing bad stuff.
     *  As of 0.9.4, we make this LOWER than the min for reseeding, so
     *  a reseed will be forced if necessary.
     */
    protected final static int MIN_REMAINING_ROUTERS = MIN_RESEED - 10;
    
    /** 
     * limits for accepting a dbDtore of a router (unless we dont 
     * know anyone or just started up) -- see validate() below
     */
    private final static long ROUTER_INFO_EXPIRATION = 27*60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_MIN = 90*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_SHORT = 75*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_FLOODFILL = 60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_INTRODUCED = 45*60*1000l;
    
    private final static long EXPLORE_JOB_DELAY = 10*60*1000l;

    /** this needs to be long enough to give us time to start up,
        but less than 20m (when we start accepting tunnels and could be a IBGW)
        Actually no, we need this soon if we are a new router or
        other routers have forgotten about us, else
        we can't build IB exploratory tunnels.
        Unused.
     */
    protected final static long PUBLISH_JOB_DELAY = 5*60*1000l;

    static final int MAX_EXPLORE_QUEUE = 128;

    /**
     *  kad K
     *  Was 500 in old implementation but that was with B ~= -8!
     */
    private static final int BUCKET_SIZE = 24;
    private static final int KAD_B = 4;

    public KademliaNetworkDatabaseFacade(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(getClass());
        _networkID = context.router().getNetworkID();
        _peerSelector = createPeerSelector();
        _publishingLeaseSets = new HashMap<Hash, RepublishLeaseSetJob>(8);
        _activeRequests = new HashMap<Hash, SearchJob>(8);
        _reseedChecker = new ReseedChecker(context);
        context.statManager().createRateStat("netDb.lookupDeferred", "how many lookups are deferred?", "NetworkDatabase", new long[] { 60*60*1000 });
        context.statManager().createRateStat("netDb.exploreKeySet", "how many keys are queued for exploration?", "NetworkDatabase", new long[] { 60*60*1000 });
        context.statManager().createRateStat("netDb.negativeCache", "Aborted lookup, already cached", "NetworkDatabase", new long[] { 60*60*1000l });
        // following are for StoreJob
        context.statManager().createRateStat("netDb.storeRouterInfoSent", "How many routerInfo store messages have we sent?", "NetworkDatabase", new long[] { 60*60*1000l });
        context.statManager().createRateStat("netDb.storeLeaseSetSent", "How many leaseSet store messages have we sent?", "NetworkDatabase", new long[] { 60*60*1000l });
        context.statManager().createRateStat("netDb.storePeers", "How many peers each netDb must be sent to before success?", "NetworkDatabase", new long[] { 60*60*1000l });
        context.statManager().createRateStat("netDb.storeFailedPeers", "How many peers each netDb must be sent to before failing completely?", "NetworkDatabase", new long[] { 60*60*1000l });
        context.statManager().createRateStat("netDb.ackTime", "How long does it take for a peer to ack a netDb store?", "NetworkDatabase", new long[] { 60*60*1000l });
        context.statManager().createRateStat("netDb.replyTimeout", "How long after a netDb send does the timeout expire (when the peer doesn't reply in time)?", "NetworkDatabase", new long[] { 60*60*1000l });
        // following is for RepublishLeaseSetJob
        context.statManager().createRateStat("netDb.republishLeaseSetCount", "How often we republish a leaseSet?", "NetworkDatabase", new long[] { 60*60*1000l });
        // following is for DatabaseStoreMessage
        context.statManager().createRateStat("netDb.DSMAllZeros", "Store with zero key", "NetworkDatabase", new long[] { 60*60*1000l });
        // following is for HandleDatabaseLookupMessageJob
        context.statManager().createRateStat("netDb.DLMAllZeros", "Lookup with zero key", "NetworkDatabase", new long[] { 60*60*1000l });
    }
    
    @Override
    public boolean isInitialized() {
        return _initialized && _ds != null && _ds.isInitialized();
    }

    protected PeerSelector createPeerSelector() { return new PeerSelector(_context); }
    public PeerSelector getPeerSelector() { return _peerSelector; }
    
    /** @since 0.9 */
    @Override
    public ReseedChecker reseedChecker() {
        return _reseedChecker;
    }

    KBucketSet<Hash> getKBuckets() { return _kb; }
    DataStore getDataStore() { return _ds; }
    
    long getLastExploreNewDate() { return _lastExploreNew; }
    void setLastExploreNewDate(long when) { 
        _lastExploreNew = when; 
        if (_exploreJob != null)
            _exploreJob.updateExploreSchedule();
    }
    
    /** @return unmodifiable set */
    public Set<Hash> getExploreKeys() {
        if (!_initialized)
            return Collections.emptySet();
        return Collections.unmodifiableSet(_exploreKeys);
    }
    
    public void removeFromExploreKeys(Collection<Hash> toRemove) {
        if (!_initialized) return;
        _exploreKeys.removeAll(toRemove);
        _context.statManager().addRateData("netDb.exploreKeySet", _exploreKeys.size());
    }

    public void queueForExploration(Collection<Hash> keys) {
        if (!_initialized) return;
        for (Iterator<Hash> iter = keys.iterator(); iter.hasNext() && _exploreKeys.size() < MAX_EXPLORE_QUEUE; ) {
            _exploreKeys.add(iter.next());
        }
        _context.statManager().addRateData("netDb.exploreKeySet", _exploreKeys.size());
    }
    
    public synchronized void shutdown() {
        _initialized = false;
        if (_kb != null)
            _kb.clear();
        // don't null out _kb, it can cause NPEs in concurrent operations
        //_kb = null;
        if (_ds != null)
            _ds.stop();
        // don't null out _ds, it can cause NPEs in concurrent operations
        //_ds = null;
        _exploreKeys.clear(); // hope this doesn't cause an explosion, it shouldn't.
        // _exploreKeys = null;
        _negativeCache.clear();
    }
    
    public synchronized void restart() {
        _dbDir = _context.router().getConfigSetting(PROP_DB_DIR);
        if (_dbDir == null) {
            _log.info("No DB dir specified [" + PROP_DB_DIR + "], using [" + DEFAULT_DB_DIR + "]");
            _dbDir = DEFAULT_DB_DIR;
        }
        _ds.restart();
        _exploreKeys.clear();

        _initialized = true;
        
        RouterInfo ri = _context.router().getRouterInfo();
        publish(ri);
    }
    
    @Override
    public void rescan() {
        if (isInitialized())
           _ds.rescan();
    }

    String getDbDir() { return _dbDir; }
    
    public synchronized void startup() {
        _log.info("Starting up the kademlia network database");
        RouterInfo ri = _context.router().getRouterInfo();
        String dbDir = _context.getProperty(PROP_DB_DIR, DEFAULT_DB_DIR);
        _kb = new KBucketSet<Hash>(_context, ri.getIdentity().getHash(),
                                   BUCKET_SIZE, KAD_B, new RejectTrimmer<Hash>());
        try {
            _ds = new PersistentDataStore(_context, dbDir, this);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to initialize netdb storage", ioe);
        }
        //_ds = new TransientDataStore();
//        _exploreKeys = new HashSet(64);
        _dbDir = dbDir;
        _negativeCache = new NegativeLookupCache(_context);
        
        createHandlers();
        
        _initialized = true;
        _started = System.currentTimeMillis();
        
        // expire old leases
        Job elj = new ExpireLeasesJob(_context, this);
        elj.getTiming().setStartAfter(_context.clock().now() + 2*60*1000);
        _context.jobQueue().addJob(elj);
        
        //// expire some routers
        // Don't run until after RefreshRoutersJob has run, and after validate() will return invalid for old routers.
        if (!_context.commSystem().isDummy()) {
            Job erj = new ExpireRoutersJob(_context, this);
            erj.getTiming().setStartAfter(_context.clock().now() + ROUTER_INFO_EXPIRATION_FLOODFILL + 10*60*1000);
            _context.jobQueue().addJob(erj);
        }
        
        if (!QUIET) {
            // fill the search queue with random keys in buckets that are too small
            // Disabled since KBucketImpl.generateRandomKey() is b0rked,
            // and anyway, we want to search for a completely random key,
            // not a random key for a particular kbucket.
            // _context.jobQueue().addJob(new ExploreKeySelectorJob(_context, this));
            if (_exploreJob == null)
                _exploreJob = new StartExplorersJob(_context, this);
            // fire off a group of searches from the explore pool
            // Don't start it right away, so we don't send searches for random keys
            // out our 0-hop exploratory tunnels (generating direct connections to
            // one or more floodfill peers within seconds of startup).
            // We're trying to minimize the ff connections to lessen the load on the 
            // floodfills, and in any case let's try to build some real expl. tunnels first.
            // No rush, it only runs every 30m.
            _exploreJob.getTiming().setStartAfter(_context.clock().now() + EXPLORE_JOB_DELAY);
            _context.jobQueue().addJob(_exploreJob);
        } else {
            _log.warn("Operating in quiet mode - not exploring or pushing data proactively, simply reactively");
            _log.warn("This should NOT be used in production");
        }
        // periodically update and resign the router's 'published date', which basically
        // serves as a version
        Job plrij = new PublishLocalRouterInfoJob(_context);
        // do not delay this, as this creates the RI too, and we need a good local routerinfo right away
        //plrij.getTiming().setStartAfter(_context.clock().now() + PUBLISH_JOB_DELAY);
        _context.jobQueue().addJob(plrij);

        // plrij calls publish() for us
        //try {
        //    publish(ri);
        //} catch (IllegalArgumentException iae) {
        //    _context.router().rebuildRouterInfo(true);
        //    //_log.log(Log.CRIT, "Our local router info is b0rked, clearing from scratch", iae);
        //    //_context.router().rebuildNewIdentity();
        //}
    }
    
    /** unused, see override */
    protected void createHandlers() {
        //_context.inNetMessagePool().registerHandlerJobBuilder(DatabaseLookupMessage.MESSAGE_TYPE, new DatabaseLookupMessageHandler(_context));
        //_context.inNetMessagePool().registerHandlerJobBuilder(DatabaseStoreMessage.MESSAGE_TYPE, new DatabaseStoreMessageHandler(_context));
    }
    
    /**
     * Get the routers closest to that key in response to a remote lookup
     * Only used by ../HDLMJ
     * Set MAY INCLUDE our own router - add to peersToIgnore if you don't want
     *
     * @param key the real key, NOT the routing key
     * @param peersToIgnore can be null
     */
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore) {
        if (!_initialized) return Collections.emptySet();
        return new HashSet<Hash>(_peerSelector.selectNearest(key, maxNumRouters, peersToIgnore, _kb));
    }
    
/*****
    private Set<RouterInfo> getRouters(Collection hashes) {
        if (!_initialized) return null;
        Set rv = new HashSet(hashes.size());
        for (Iterator iter = hashes.iterator(); iter.hasNext(); ) {
            Hash rhash = (Hash)iter.next();
            DataStructure ds = _ds.get(rhash);
            if (ds == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Selected hash " + rhash.toBase64() + " is not stored locally");
            } else if ( !(ds instanceof RouterInfo) ) {
                // leaseSet
            } else {
                rv.add(ds);
            }
        }
        return rv;
    }
*****/
    
    /** get the hashes for all known routers */
    public Set<Hash> getAllRouters() {
        if (!_initialized) return Collections.emptySet();
        Set<Map.Entry<Hash, DatabaseEntry>> entries = _ds.getMapEntries();
        Set<Hash> rv = new HashSet<Hash>(entries.size());
        for (Map.Entry<Hash, DatabaseEntry> entry : entries) {
            if (entry.getValue().getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                rv.add(entry.getKey());
            }
        }
        return rv;
    }
    
    /**
     *  This used to return the number of routers that were in
     *  both the kbuckets AND the data store, which was fine when the kbuckets held everything.
     *  But now that is probably not what you want.
     *  Just return the count in the data store.
     */
    @Override
    public int getKnownRouters() { 
/****
        if (_kb == null) return 0;
        CountRouters count = new CountRouters();
        _kb.getAll(count);
        return count.size();
****/
        if (_ds == null) return 0;
        int rv = 0;
        for (DatabaseEntry ds : _ds.getEntries()) {
            if (ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
                rv++;
        }
        return rv;
    }
    
/****
    private class CountRouters implements SelectionCollector<Hash> {
        private int _count;
        public int size() { return _count; }
        public void add(Hash entry) {
            if (_ds == null) return;
            DatabaseEntry o = _ds.get(entry);
            if (o != null && o.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
                _count++;
        }
    }
****/
    
    /**
     *  This is only used by StatisticsManager to publish
     *  the count if we are floodfill.
     *  So to hide a clue that a popular eepsite is hosted
     *  on a floodfill router, only count leasesets that
     *  are "received as published", as of 0.7.14
     */
    @Override
    public int getKnownLeaseSets() {  
        if (_ds == null) return 0;
        //return _ds.countLeaseSets();
        int rv = 0;
        for (DatabaseEntry ds : _ds.getEntries()) {
            if (ds.getType() == DatabaseEntry.KEY_TYPE_LEASESET &&
                ((LeaseSet)ds).getReceivedAsPublished())
                rv++;
        }
        return rv;
    }

    /**
     *  This is fast and doesn't use synchronization,
     *  but it includes both routerinfos and leasesets.
     *  Use it to avoid deadlocks.
     *  No - not true - the KBS contains RIs only.
     */
    protected int getKBucketSetSize() {  
        if (_kb == null) return 0;
        return _kb.size();
    }
    
    /**
     *  @return RouterInfo, LeaseSet, or null, validated
     *  @since 0.8.3
     */
    public DatabaseEntry lookupLocally(Hash key) {
        if (!_initialized)
            return null;
        DatabaseEntry rv = _ds.get(key);
        if (rv == null)
            return null;
        if (rv.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
            LeaseSet ls = (LeaseSet)rv;
            if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR))
                return rv;
            else
                fail(key);
        } else if (rv.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            try {
                if (validate((RouterInfo)rv) == null)
                    return rv;
            } catch (IllegalArgumentException iae) {}
            fail(key);
        }
        return null;
    }
    
    /**
     *  Not for use without validation
     *  @return RouterInfo, LeaseSet, or null, NOT validated
     *  @since 0.9.9
     */
    DatabaseEntry lookupLocallyWithoutValidation(Hash key) {
        if (!_initialized)
            return null;
        return _ds.get(key);
    }

    /**
     *  Lookup using exploratory tunnels.
     *  Use lookupDestination() if you don't need the LS or don't need it validated.
     */
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        lookupLeaseSet(key, onFindJob, onFailedLookupJob, timeoutMs, null);
    }

    /**
     *  Lookup using the client's tunnels
     *  Use lookupDestination() if you don't need the LS or don't need it validated.
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.10
     */
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob,
                               long timeoutMs, Hash fromLocalDest) {
        if (!_initialized) return;
        LeaseSet ls = lookupLeaseSetLocally(key);
        if (ls != null) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("leaseSet found locally, firing " + onFindJob);
            if (onFindJob != null)
                _context.jobQueue().addJob(onFindJob);
        } else if (isNegativeCached(key)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Negative cached, not searching LS: " + key);
            if (onFailedLookupJob != null)
                _context.jobQueue().addJob(onFailedLookupJob);
        } else {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("leaseSet not found locally, running search");
            search(key, onFindJob, onFailedLookupJob, timeoutMs, true, fromLocalDest);
        }
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("after lookupLeaseSet");
    }
    
    /**
     *  Unconditionally lookup using the client's tunnels.
     *  No success or failed jobs, no local lookup, no checks.
     *  Use this to refresh a leaseset before expiration.
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.25
     */
    public void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest) {
        if (!_initialized) return;
        search(key, null, null, 20*1000, true, fromLocalDest);
    }

    /**
     *  Use lookupDestination() if you don't need the LS or don't need it validated.
     */
    public LeaseSet lookupLeaseSetLocally(Hash key) {
        if (!_initialized) return null;
        DatabaseEntry ds = _ds.get(key);
        if (ds != null) {
            if (ds.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
                LeaseSet ls = (LeaseSet)ds;
                if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    return ls;
                } else {
                    fail(key);
                    // this was an interesting key, so either refetch it or simply explore with it
                    _exploreKeys.add(key);
                    return null;
                }
            } else {
                //_log.debug("Looking for a lease set [" + key + "] but it ISN'T a leaseSet! " + ds, new Exception("Who thought that router was a lease?"));
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     *  Lookup using the client's tunnels
     *  Succeeds even if LS validation and store fails due to unsupported sig type, expired, etc.
     *
     *  Note that there are not separate success and fail jobs. Caller must call
     *  lookupDestinationLocally() in the job to determine success.
     *
     *  @param onFinishedJob non-null
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.16
     */
    public void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest) {
        if (!_initialized) return;
        Destination d = lookupDestinationLocally(key);
        if (d != null) {
            _context.jobQueue().addJob(onFinishedJob);
        } else if (isNegativeCached(key)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Negative cached, not searching dest: " + key);
        } else {
            search(key, onFinishedJob, onFinishedJob, timeoutMs, true, fromLocalDest);
        }
    }

    /**
     *  Lookup locally in netDB and in badDest cache
     *  Succeeds even if LS validation fails due to unsupported sig type, expired, etc.
     *
     *  @since 0.9.16
     */
    public Destination lookupDestinationLocally(Hash key) {
        if (!_initialized) return null;
        DatabaseEntry ds = _ds.get(key);
        if (ds != null) {
            if (ds.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
                LeaseSet ls = (LeaseSet)ds;
                return ls.getDestination();
            }
        } else {
            return _negativeCache.getBadDest(key);
        }
        return null;
    }
    
    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        if (!_initialized) return;
        RouterInfo ri = lookupRouterInfoLocally(key);
        if (ri != null) {
            if (onFindJob != null)
                _context.jobQueue().addJob(onFindJob);
        } else if (_context.banlist().isBanlistedForever(key)) {
            if (onFailedLookupJob != null)
                _context.jobQueue().addJob(onFailedLookupJob);
        } else if (isNegativeCached(key)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Negative cached, not searching RI: " + key);
        } else {
            search(key, onFindJob, onFailedLookupJob, timeoutMs, false);
        }
    }
    
    public RouterInfo lookupRouterInfoLocally(Hash key) {
        if (!_initialized) return null;
        DatabaseEntry ds = _ds.get(key);
        if (ds != null) {
            if (ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                // more aggressive than perhaps is necessary, but makes sure we
                // drop old references that we had accepted on startup (since 
                // startup allows some lax rules).
                boolean valid = true;
                try {
                    valid = (null == validate((RouterInfo)ds));
                } catch (IllegalArgumentException iae) {
                    valid = false;
                }
                if (!valid) {
                    fail(key);
                    return null;
                }
                return (RouterInfo)ds;
            } else {
                //_log.debug("Looking for a router [" + key + "] but it ISN'T a RouterInfo! " + ds, new Exception("Who thought that lease was a router?"));
                return null;
            }
        } else {
            return null;
        }
    }
    
    private static final long PUBLISH_DELAY = 3*1000;

    /**
     * @throws IllegalArgumentException if the leaseSet is not valid
     */
    public void publish(LeaseSet localLeaseSet) throws IllegalArgumentException {
        if (!_initialized) return;
        Hash h = localLeaseSet.getDestination().calculateHash();
        try {
            store(h, localLeaseSet);
        } catch (IllegalArgumentException iae) {
            _log.error("locally published leaseSet is not valid?", iae);
            throw iae;
        }
        if (!_context.clientManager().shouldPublishLeaseSet(h))
            return;
        // If we're exiting, don't publish.
        // If we're restarting, keep publishing to minimize the downtime.
        if (_context.router().gracefulShutdownInProgress()) {
            int code = _context.router().scheduledGracefulExitCode();
            if (code == Router.EXIT_GRACEFUL || code == Router.EXIT_HARD)
                return;
        }
        
        RepublishLeaseSetJob j = null;
        synchronized (_publishingLeaseSets) {
            j = _publishingLeaseSets.get(h);
            if (j == null) {
                j = new RepublishLeaseSetJob(_context, this, h);
                _publishingLeaseSets.put(h, j);
            }
        }
        // Don't spam the floodfills. In addition, always delay a few seconds since there may
        // be another leaseset change coming along momentarily.
        long nextTime = Math.max(j.lastPublished() + RepublishLeaseSetJob.REPUBLISH_LEASESET_TIMEOUT, _context.clock().now() + PUBLISH_DELAY);
        // remove first since queue is a TreeSet now...
        _context.jobQueue().removeJob(j);
        j.getTiming().setStartAfter(nextTime);
        if (_log.shouldLog(Log.INFO))
            _log.info("Queueing to publish at " + (new Date(nextTime)) + ' ' + localLeaseSet);
        _context.jobQueue().addJob(j);
    }
    
    void stopPublishing(Hash target) {
        synchronized (_publishingLeaseSets) {
            _publishingLeaseSets.remove(target);
        }
    }
    
    /**
     * Stores to local db only.
     * Overridden in FNDF to actually send to the floodfills.
     * @throws IllegalArgumentException if the local router info is invalid
     */
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        if (!_initialized) return;
        if (_context.router().gracefulShutdownInProgress())
            return;
        // This isn't really used for anything
        // writeMyInfo(localRouterInfo);
        if (_context.router().isHidden()) return; // DE-nied!
        Hash h = localRouterInfo.getIdentity().getHash();
        store(h, localRouterInfo);
    }

    /**
     *  Set the last time we successfully published our RI.
     *  @since 0.9.9
     */
    void routerInfoPublishSuccessful() {
        _lastRIPublishTime = _context.clock().now();
    }

    /**
     *  The last time we successfully published our RI.
     *  @since 0.9.9
     */
    @Override
    public long getLastRouterInfoPublishTime() {
        return _lastRIPublishTime;
    }

    /**
     * Persist the local router's info (as updated) into netDb/my.info, since
     * ./router.info isn't always updated.  This also allows external applications
     * to easily pick out which router a netDb directory is rooted off, which is handy
     * for getting the freshest data.
     *
     */
/***
    private final void writeMyInfo(RouterInfo info) {
        FileOutputStream fos = null;
        try {
            File dbDir = new File(_dbDir);
            if (!dbDir.exists())
                dbDir.mkdirs();
            fos = new FileOutputStream(new File(dbDir, "my.info"));
            info.writeBytes(fos);
            fos.close();
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to persist my.info?!", ioe);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error persisting my.info - our structure isn't valid?!", dfe);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
***/
    
    /**
     * Don't let leaseSets go 20 minutes into the future 
     */
    static final long MAX_LEASE_FUTURE = 20*60*1000;
    
    /**
     * Determine whether this leaseSet will be accepted as valid and current
     * given what we know now.
     *
     * Unlike for RouterInfos, this is only called once, when stored.
     * After that, LeaseSet.isCurrent() is used.
     *
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return reason why the entry is not valid, or null if it is valid
     */
    private String validate(Hash key, LeaseSet leaseSet) throws UnsupportedCryptoException {
        if (!key.equals(leaseSet.getDestination().calculateHash())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid store attempt! key does not match leaseSet.destination!  key = "
                          + key + ", leaseSet = " + leaseSet);
            return "Key does not match leaseSet.destination - " + key.toBase64();
        }
        // todo experimental sig types
        if (!leaseSet.verifySignature()) {
            // throws UnsupportedCryptoException
            processStoreFailure(key, leaseSet);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid leaseSet signature! " + leaseSet);
            return "Invalid leaseSet signature on " + key;
        }
        long earliest = leaseSet.getEarliestLeaseDate();
        long latest = leaseSet.getLatestLeaseDate();
        long now = _context.clock().now();
        if (earliest <= now - 10*60*1000L ||
            // same as the isCurrent(Router.CLOCK_FUDGE_FACTOR) test in
            // lookupLeaseSetLocally()
            latest <= now - Router.CLOCK_FUDGE_FACTOR) {
            long age = now - earliest;
            if (_log.shouldLog(Log.WARN))
                _log.warn("Old leaseSet!  not storing it: " 
                          + leaseSet.getDestination().toBase32()
                          + " first exp. " + new Date(earliest)
                          + " last exp. " + new Date(latest),
                          new Exception("Rejecting store"));
            return "Expired leaseSet for " + leaseSet.getDestination().toBase32()
                   + " expired " + DataHelper.formatDuration(age) + " ago";
        }
        if (latest > now + (Router.CLOCK_FUDGE_FACTOR + MAX_LEASE_FUTURE)) {
            long age = latest - now;
            // let's not make this an error, it happens when peers have bad clocks
            if (_log.shouldLog(Log.WARN))
                _log.warn("LeaseSet expires too far in the future: " 
                          + leaseSet.getDestination().toBase32()
                          + " expires " + DataHelper.formatDuration(age) + " from now");
            return "Future expiring leaseSet for " + leaseSet.getDestination().toBase32()
                   + " expiring in " + DataHelper.formatDuration(age);
        }
        return null;
    }
    
    /**
     * Store the leaseSet.
     *
     * If the store fails due to unsupported crypto, it will negative cache
     * the hash until restart.
     *
     * @throws IllegalArgumentException if the leaseSet is not valid
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return previous entry or null
     */
    public LeaseSet store(Hash key, LeaseSet leaseSet) throws IllegalArgumentException {
        if (!_initialized) return null;
        
        LeaseSet rv = null;
        try {
            rv = (LeaseSet)_ds.get(key);
            if ( (rv != null) && (rv.equals(leaseSet)) ) {
                // if it hasn't changed, no need to do anything
                return rv;
            }
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException("Attempt to replace RI with " + leaseSet);
        }
        
        // spoof / hash collision detection
        // todo allow non-exp to overwrite exp
        if (rv != null && !leaseSet.getDestination().equals(rv.getDestination()))
            throw new IllegalArgumentException("LS Hash collision");

        String err = validate(key, leaseSet);
        if (err != null)
            throw new IllegalArgumentException("Invalid store attempt - " + err);
        
        _ds.put(key, leaseSet);
        
        // Iterate through the old failure / success count, copying over the old
        // values (if any tunnels overlap between leaseSets).  no need to be
        // ueberthreadsafe fascists here, since these values are just heuristics
      /****** unused
        if (rv != null) {
            for (int i = 0; i < rv.getLeaseCount(); i++) {
                Lease old = rv.getLease(i);
                for (int j = 0; j < leaseSet.getLeaseCount(); j++) {
                    Lease cur = leaseSet.getLease(j);
                    if (cur.getTunnelId().getTunnelId() == old.getTunnelId().getTunnelId()) {
                        cur.setNumFailure(old.getNumFailure());
                        cur.setNumSuccess(old.getNumSuccess());
                        break;
                    }
                }
            }
        }
       *******/
        
        return rv;
    }
    
    private static final int MIN_ROUTERS = 90;

    /**
     * Determine whether this routerInfo will be accepted as valid and current
     * given what we know now.
     *
     * Call this only on first store, to check the key and signature once
     *
     * If the store fails due to unsupported crypto, it will banlist
     * the router hash until restart and then throw UnsupportedCrytpoException.
     *
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return reason why the entry is not valid, or null if it is valid
     */
    private String validate(Hash key, RouterInfo routerInfo) throws IllegalArgumentException {
        if (!key.equals(routerInfo.getIdentity().getHash())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid store attempt! key does not match routerInfo.identity!  key = " + key + ", router = " + routerInfo);
            return "Key does not match routerInfo.identity";
        }
        // todo experimental sig types
        if (!routerInfo.isValid()) {
            // throws UnsupportedCryptoException
            processStoreFailure(key, routerInfo);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid routerInfo signature!  forged router structure!  router = " + routerInfo);
            return "Invalid routerInfo signature";
        }
        if (routerInfo.getNetworkId() != _networkID){
            _context.banlist().banlistRouter(key, "Not in our network");
            if (_log.shouldLog(Log.WARN))
                _log.warn("Bad network: " + routerInfo);
            return "Not in our network";
        }
        FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
        if (fkc != null) {
            boolean validFamily = fkc.verify(routerInfo);
            if (!validFamily) {
                if (_log.shouldWarn())
                    _log.warn("Bad family sig: " + routerInfo.getHash());
            }
            // todo store in RI
        }
        return validate(routerInfo);
    }

    /**
     * Determine whether this routerInfo will be accepted as valid and current
     * given what we know now.
     *
     * Call this before each use, to check expiration
     *
     * @return reason why the entry is not valid, or null if it is valid
     * @since 0.9.7
     */
    String validate(RouterInfo routerInfo) throws IllegalArgumentException {
        long now = _context.clock().now();
        boolean upLongEnough = _context.router().getUptime() > 60*60*1000;
        // Once we're over MIN_ROUTERS routers, reduce the expiration time down from the default,
        // as a crude way of limiting memory usage.
        // i.e. at 2*MIN_ROUTERS routers the expiration time will be about half the default, etc.
        // And if we're floodfill, we can keep the expiration really short, since
        // we are always getting the latest published to us.
        // As the net grows this won't be sufficient, and we'll have to implement
        // flushing some from memory, while keeping all on disk.
        long adjustedExpiration;
        if (floodfillEnabled())
            adjustedExpiration = ROUTER_INFO_EXPIRATION_FLOODFILL;
        else
            // _kb.size() includes leasesets but that's ok
            adjustedExpiration = Math.min(ROUTER_INFO_EXPIRATION,
                                          ROUTER_INFO_EXPIRATION_MIN +
                                          ((ROUTER_INFO_EXPIRATION - ROUTER_INFO_EXPIRATION_MIN) * MIN_ROUTERS / (_kb.size() + 1)));

        if (upLongEnough && !routerInfo.isCurrent(adjustedExpiration)) {
            long age = _context.clock().now() - routerInfo.getPublished();
            int existing = _kb.size();
            if (existing >= MIN_REMAINING_ROUTERS) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Expired RI " + routerInfo.getIdentity().getHash(), new Exception());
                return "Peer expired " + DataHelper.formatDuration(age) + " ago";
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Even though the peer is old, we have only " + existing
                              + " peers left " + routerInfo);
            }
        }
        if (routerInfo.getPublished() > now + 2*Router.CLOCK_FUDGE_FACTOR) {
            long age = routerInfo.getPublished() - _context.clock().now();
            if (_log.shouldLog(Log.INFO))
                _log.info("Peer " + routerInfo.getIdentity().getHash() + " published their routerInfo in the future?! [" 
                          + new Date(routerInfo.getPublished()) + "]", new Exception());
            return "Peer published " + DataHelper.formatDuration(age) + " in the future?!";
        }
        if (!routerInfo.isCurrent(ROUTER_INFO_EXPIRATION_INTRODUCED)) {
            if (routerInfo.getAddresses().isEmpty())
                return "Old peer with no addresses";
            // This should cover the introducers case below too
            // And even better, catches the case where the router is unreachable but knows no introducers
            if (routerInfo.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0)
                return "Old peer and thinks it is unreachable";
            // Just check all the addresses, faster than getting just the SSU ones
            for (RouterAddress ra : routerInfo.getAddresses()) {
                // Introducers change often, introducee will ping introducer for 2 hours
                if (ra.getOption("ihost0") != null)
                    return "Old peer with SSU Introducers";
            }
        }
        if (upLongEnough && (routerInfo.getPublished() < now - 2*24*60*60*1000l) ) {
            long age = _context.clock().now() - routerInfo.getPublished();
            return "Peer published " + DataHelper.formatDuration(age) + " ago";
        }
        if (upLongEnough && !routerInfo.isCurrent(ROUTER_INFO_EXPIRATION_SHORT)) {
            if (routerInfo.getTargetAddress("NTCP") == null)
                return "Peer published > 75m ago, SSU only without introducers";
        }
        return null;
    }
    
    /**
     * Store the routerInfo.
     *
     * If the store fails due to unsupported crypto, it will banlist
     * the router hash until restart and then throw UnsupportedCrytpoException.
     *
     * @throws IllegalArgumentException if the routerInfo is not valid
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return previous entry or null
     */
    public RouterInfo store(Hash key, RouterInfo routerInfo) throws IllegalArgumentException {
        return store(key, routerInfo, true);
    }

    /**
     * Store the routerInfo.
     *
     * If the store fails due to unsupported crypto, it will banlist
     * the router hash until restart and then throw UnsupportedCrytpoException.
     *
     * @throws IllegalArgumentException if the routerInfo is not valid
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return previous entry or null
     */
    RouterInfo store(Hash key, RouterInfo routerInfo, boolean persist) throws IllegalArgumentException {
        if (!_initialized) return null;
        
        RouterInfo rv = null;
        try {
            rv = (RouterInfo)_ds.get(key, persist);
            if ( (rv != null) && (rv.equals(routerInfo)) ) {
                // no need to validate
                return rv;
            }
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException("Attempt to replace LS with " + routerInfo);
        }
        
        // spoof / hash collision detection
        // todo allow non-exp to overwrite exp
        if (rv != null && !routerInfo.getIdentity().equals(rv.getIdentity()))
            throw new IllegalArgumentException("RI Hash collision");

        String err = validate(key, routerInfo);
        if (err != null)
            throw new IllegalArgumentException("Invalid store attempt - " + err);
        
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("RouterInfo " + key.toBase64() + " is stored with "
        //               + routerInfo.getOptionsMap().size() + " options on "
        //               + new Date(routerInfo.getPublished()));
    
        _context.peerManager().setCapabilities(key, routerInfo.getCapabilities());
        _ds.put(key, routerInfo, persist);
        if (rv == null)
            _kb.add(key);
        return rv;
    }

    /**
     *  If the validate fails, call this
     *  to determine if it was because of unsupported crypto.
     *
     *  If so, this will banlist-forever the router hash or permanently negative cache the dest hash,
     *  and then throw the exception. Otherwise it does nothing.
     *
     *  @throws UnsupportedCryptoException if that's why it failed.
     *  @since 0.9.16
     */
    private void processStoreFailure(Hash h, DatabaseEntry entry) throws UnsupportedCryptoException {
        if (entry.getHash().equals(h)) {
            if (entry.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
                LeaseSet ls = (LeaseSet) entry;
                Destination d = ls.getDestination();
                Certificate c = d.getCertificate();
                if (c.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
                    try {
                        KeyCertificate kc = c.toKeyCertificate();
                        SigType type = kc.getSigType();
                        if (type == null || !type.isAvailable() || type.getBaseAlgorithm() == SigAlgo.RSA) {
                            failPermanently(d);
                            String stype = (type != null) ? type.toString() : Integer.toString(kc.getSigTypeCode());
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Unsupported sig type " + stype + " for destination " + h);
                            throw new UnsupportedCryptoException("Sig type " + stype);
                        }
                    } catch (DataFormatException dfe) {}
                }
            } else if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                RouterInfo ri = (RouterInfo) entry;
                RouterIdentity id = ri.getIdentity();
                Certificate c = id.getCertificate();
                if (c.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
                    try {
                        KeyCertificate kc = c.toKeyCertificate();
                        SigType type = kc.getSigType();
                        if (type == null || !type.isAvailable()) {
                            String stype = (type != null) ? type.toString() : Integer.toString(kc.getSigTypeCode());
                            _context.banlist().banlistRouterForever(h, "Unsupported signature type " + stype);
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Unsupported sig type " + stype + " for router " + h);
                            throw new UnsupportedCryptoException("Sig type " + stype);
                        }
                    } catch (DataFormatException dfe) {}
                }
            }
        }
        if (_log.shouldLog(Log.WARN))
            _log.warn("Verify fail, cause unknown: " + entry);
    }

    
    /**
     *   Final remove for a leaseset.
     *   For a router info, will look up in the network before dropping.
     */
    public void fail(Hash dbEntry) {
        if (!_initialized) return;
        DatabaseEntry o = _ds.get(dbEntry);
        if (o == null) {
            // if we dont know the key, lets make sure it isn't a now-dead peer
            _kb.remove(dbEntry);
            _context.peerManager().removeCapabilities(dbEntry);
            return;
        }

        if (o.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            lookupBeforeDropping(dbEntry, (RouterInfo)o);
            return;
        }

        // we always drop leaseSets that are failed [timed out],
        // regardless of how many routers we have.  this is called on a lease if
        // it has expired *or* its tunnels are failing and we want to see if there
        // are any updates
        if (_log.shouldLog(Log.INFO))
            _log.info("Dropping a lease: " + dbEntry);
        _ds.remove(dbEntry, false);
    }
    
    /** don't use directly - see F.N.D.F. override */
    protected void lookupBeforeDropping(Hash peer, RouterInfo info) {
        //bah, humbug.
        dropAfterLookupFailed(peer);
    }

    /**
     *  Final remove for a router info.
     *  Do NOT use for leasesets.
     */
    void dropAfterLookupFailed(Hash peer) {
        _context.peerManager().removeCapabilities(peer);
        _kb.remove(peer);
        //if (removed) {
        //    if (_log.shouldLog(Log.INFO))
        //        _log.info("Removed kbucket entry for " + peer);
        //}
        
        _ds.remove(peer);
    }
    
    public void unpublish(LeaseSet localLeaseSet) {
        if (!_initialized) return;
        Hash h = localLeaseSet.getDestination().calculateHash();
        DatabaseEntry data = _ds.remove(h);
        
        if (data == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unpublished a lease we don't know...: " + localLeaseSet);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Unpublished a lease: " + h);
        }
        // now update it if we can to remove any leases
    }
    
    /**
     * Begin a kademlia style search for the key specified, which can take up to timeoutMs and
     * will fire the appropriate jobs on success or timeout (or if the kademlia search completes
     * without any match)
     *
     * Unused - called only by FNDF.searchFull() from FloodSearchJob which is overridden - don't use this.
     *
     * @throws UnsupportedOperationException always
     */
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease) {
        throw new UnsupportedOperationException();
/****
        if (!_initialized) return null;
        boolean isNew = true;
        SearchJob searchJob = null;
        synchronized (_activeRequests) {
            searchJob = _activeRequests.get(key);
            if (searchJob == null) {
                searchJob = new SearchJob(_context, this, key, onFindJob, onFailedLookupJob, 
                                         timeoutMs, true, isLease);
                _activeRequests.put(key, searchJob);
            } else {
                isNew = false;
            }
        }
        if (isNew) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("this is the first search for that key, fire off the SearchJob");
            _context.jobQueue().addJob(searchJob);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Deferring search for " + key.toBase64() + " with " + onFindJob);
            int deferred = searchJob.addDeferred(onFindJob, onFailedLookupJob, timeoutMs, isLease);
            _context.statManager().addRateData("netDb.lookupDeferred", deferred, searchJob.getExpiration()-_context.clock().now());
        }
        return searchJob;
****/
    }
    
    /**
     * Unused - see FNDF
     * @throws UnsupportedOperationException always
     * @since 0.9.10
     */
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease,
                     Hash fromLocalDest) {
        throw new UnsupportedOperationException();
    }
    
    /** public for NetDbRenderer in routerconsole */
    @Override
    public Set<LeaseSet> getLeases() {
        if (!_initialized) return null;
        Set<LeaseSet> leases = new HashSet<LeaseSet>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.getType() == DatabaseEntry.KEY_TYPE_LEASESET)
                leases.add((LeaseSet)o);
        }
        return leases;
    }

    /** public for NetDbRenderer in routerconsole */
    @Override
    public Set<RouterInfo> getRouters() {
        if (!_initialized) return null;
        Set<RouterInfo> routers = new HashSet<RouterInfo>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
                routers.add((RouterInfo)o);
        }
        return routers;
    }

    /** smallest allowed period */
    private static final int MIN_PER_PEER_TIMEOUT = 2*1000;
    /**
     *  We want FNDF.PUBLISH_TIMEOUT and RepublishLeaseSetJob.REPUBLISH_LEASESET_TIMEOUT
     *  to be greater than MAX_PER_PEER_TIMEOUT * TIMEOUT_MULTIPLIER by a factor of at least
     *  3 or 4, to allow at least that many peers to be attempted for a store.
     */
    private static final int MAX_PER_PEER_TIMEOUT = 7*1000;
    private static final int TIMEOUT_MULTIPLIER = 3;

    /** todo: does this need more tuning? */
    public int getPeerTimeout(Hash peer) {
        PeerProfile prof = _context.profileOrganizer().getProfile(peer);
        double responseTime = MAX_PER_PEER_TIMEOUT;
        if (prof != null && prof.getIsExpandedDB()) {
            responseTime = prof.getDbResponseTime().getRate(24*60*60*1000l).getAverageValue();
            // if 0 then there is no data, set to max.
            if (responseTime <= 0 || responseTime > MAX_PER_PEER_TIMEOUT)
                responseTime = MAX_PER_PEER_TIMEOUT;
            else if (responseTime < MIN_PER_PEER_TIMEOUT)
                responseTime = MIN_PER_PEER_TIMEOUT;
        }
        return TIMEOUT_MULTIPLIER * (int)responseTime;  // give it up to 3x the average response time
    }

    /** unused (overridden in FNDF) */
    public void sendStore(Hash key, DatabaseEntry ds, Job onSuccess, Job onFailure, long sendTimeout, Set<Hash> toIgnore) {
        if ( (ds == null) || (key == null) ) {
            if (onFailure != null) 
                _context.jobQueue().addJob(onFailure);
            return;
        }
        _context.jobQueue().addJob(new StoreJob(_context, this, key, ds, onSuccess, onFailure, sendTimeout, toIgnore));
    }

    /**
     *  Increment in the negative lookup cache
     *
     *  @param key for Destinations or RouterIdentities
     *  @since 0.9.4 moved from FNDF to KNDF in 0.9.16
     */
    void lookupFailed(Hash key) {
        _negativeCache.lookupFailed(key);
    }

    /**
     *  Is the key in the negative lookup cache?
     *
     *  @param key for Destinations or RouterIdentities
     *  @since 0.9.4 moved from FNDF to KNDF in 0.9.16
     */
    boolean isNegativeCached(Hash key) {
        boolean rv = _negativeCache.isCached(key);
        if (rv)
            _context.statManager().addRateData("netDb.negativeCache", 1);
        return rv;
    }

    /**
     *  Negative cache until restart
     *  @since 0.9.16
     */
    void failPermanently(Destination dest) {
        _negativeCache.failPermanently(dest);
    }

    /**
     *  Is it permanently negative cached?
     *
     *  @param key only for Destinations; for RouterIdentities, see Banlist
     *  @since 0.9.16
     */
    public boolean isNegativeCachedForever(Hash key) {
        return _negativeCache.getBadDest(key) != null;
    }

    /**
     * Debug info, HTML formatted
     * @since 0.9.10
     */
    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        out.write(_kb.toString().replace("\n", "<br>\n"));
    }
}
