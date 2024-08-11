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
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;
import net.i2p.data.BlindData;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.KeyCertificate;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.RejectTrimmer;
import net.i2p.kademlia.SelectionCollector;
import net.i2p.router.Banlist;
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
import net.i2p.util.SystemVersion;

/**
 * Kademlia based version of the network database.
 * Never instantiated directly; see FloodfillNetworkDatabaseFacade.
 */
public abstract class KademliaNetworkDatabaseFacade extends NetworkDatabaseFacade {
    protected final Log _log;
    private KBucketSet<Hash> _kb; // peer hashes sorted into kbuckets, but within kbuckets, unsorted
    private DataStore _ds; // hash to DataStructure mapping, persisted when necessary
    /** where the data store is pushing the data */
    private String _dbDir;
    // set of Hash objects that we should search on (to fill up a bucket, not to get data)
    private final Set<Hash> _exploreKeys;
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
    private final BlindCache _blindCache;
    private final Hash _dbid;
    private final Job _elj, _erj;

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
     * limits for accepting a dbStore of a router (unless we don't 
     * know anyone or just started up) -- see validate() below
     */
    private final static long ROUTER_INFO_EXPIRATION = 27*60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_MIN = 60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_SHORT = 75*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_FLOODFILL = 60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_INTRODUCED = 54*60*1000l;
    
    /**
     * Don't let leaseSets go too far into the future 
     */
    private static final long MAX_LEASE_FUTURE = 15*60*1000;
    private static final long MAX_META_LEASE_FUTURE = 65535*1000;
    
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

    public KademliaNetworkDatabaseFacade(RouterContext context, Hash dbid) {
        _context = context;
        _dbid = dbid;
        _log = _context.logManager().getLog(getClass());
        _networkID = context.router().getNetworkID();
        _publishingLeaseSets = new HashMap<Hash, RepublishLeaseSetJob>(8);
        _activeRequests = new HashMap<Hash, SearchJob>(8);
        if (isClientDb()) {
            _reseedChecker = null;
            _blindCache = null;
            _exploreKeys = null;
            _erj = null;
            _peerSelector = ((KademliaNetworkDatabaseFacade) context.netDb()).getPeerSelector();
        } else {
            _reseedChecker = new ReseedChecker(context);
            _blindCache = new BlindCache(context);
            _exploreKeys = new ConcurrentHashSet<Hash>(64);
            // We don't have a comm system here to check for ctx.commSystem().isDummy()
            // we'll check before starting in startup()
            _erj = new ExpireRoutersJob(_context, this);
            _peerSelector = createPeerSelector();
        }
        _elj = new ExpireLeasesJob(_context, this);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Created KademliaNetworkDatabaseFacade for id: " + dbid);
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

    /**
     *  Only for main DB
     */
    protected PeerSelector createPeerSelector() {
       if (isClientDb())
           throw new IllegalStateException();
       return new FloodfillPeerSelector(_context);
    }

    /**
     *  @return the main DB's peer selector. Client DBs do not have their own.
     */
    public PeerSelector getPeerSelector() { return _peerSelector; }
    
    /** @since 0.9 */
    @Override
    public ReseedChecker reseedChecker() {
        if (isClientDb())
            return null;
        return _reseedChecker;
    }

    /**
     * We still always use a single blind cache in the main Db(for now),
     * see issue #421 on i2pgit.org/i2p-hackers/i2p.i2p for details.
     * This checks if we're the main DB already and returns our blind
     * cache if we are. If not, it looks up the main Db and gets it.
     * 
     * @return non-null
     * @since 0.9.61
     */
    protected BlindCache blindCache() {
        if (!isClientDb())
            return _blindCache;
        return ((FloodfillNetworkDatabaseFacade) _context.netDb()).blindCache();
    }

    /**
     *  @return the main DB's KBucketSet. Client DBs do not have their own.
     */
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
        if (!_initialized || isClientDb())
            return Collections.emptySet();
        return Collections.unmodifiableSet(_exploreKeys);
    }
    
    public void removeFromExploreKeys(Collection<Hash> toRemove) {
        if (!_initialized || isClientDb())
            return;
        _exploreKeys.removeAll(toRemove);
        _context.statManager().addRateData("netDb.exploreKeySet", _exploreKeys.size());
    }

    public void queueForExploration(Collection<Hash> keys) {
        if (!_initialized || isClientDb())
            return;
        for (Iterator<Hash> iter = keys.iterator(); iter.hasNext() && _exploreKeys.size() < MAX_EXPLORE_QUEUE; ) {
            _exploreKeys.add(iter.next());
        }
        _context.statManager().addRateData("netDb.exploreKeySet", _exploreKeys.size());
    }
    
    /**
     *  Cannot be restarted.
     */
    public synchronized void shutdown() {
        if (_log.shouldWarn())
            _log.warn("DB shutdown " + this);
        _initialized = false;
        if (!_context.commSystem().isDummy() && !isClientDb() &&
            _context.router().getUptime() > ROUTER_INFO_EXPIRATION_FLOODFILL + 10*60*1000 + 60*1000) {
            // expire inline before saving RIs in _ds.stop()
            Job erj = new ExpireRoutersJob(_context, this);
            erj.runJob();
        }
        _context.jobQueue().removeJob(_elj);
        if (_erj != null)
            _context.jobQueue().removeJob(_erj);
        if (_kb != null && !isClientDb())
            _kb.clear();
        if (_ds != null)
            _ds.stop();
        if (_exploreKeys != null)
            _exploreKeys.clear();
        if (_negativeCache != null)
            _negativeCache.stop();
        if (!isClientDb())
            blindCache().shutdown();
    }
    
    /**
     *  Unsupported, do not use
     *
     *  @throws UnsupportedOperationException always
     *  @deprecated
     */
    @Deprecated
    public synchronized void restart() {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public void rescan() {
        if (isInitialized())
           _ds.rescan();
    }

    /**
     * For the main DB only.
     * Sub DBs are not persisted and must not access this directory.
     *
     * @return null before startup() is called; non-null thereafter, even for subdbs.
     */
    String getDbDir() { return _dbDir; }

    /**
     * Check if the database is a client DB.
     *
     * @return  true if the database is a client DB, false otherwise
     * @since 0.9.61
     */
    public boolean isClientDb() {
        // This is a null check in disguise, don't use .equals() here.
        // FNDS.MAIN_DBID is always null. and if _dbid is also null it is not a client Db
        if (_dbid == FloodfillNetworkDatabaseSegmentor.MAIN_DBID)
            return false;
        return true;
    }

    public synchronized void startup() {
        if (_log.shouldInfo())
            _log.info("Starting up the " + this);
        RouterInfo ri = _context.router().getRouterInfo();
        String dbDir = _context.getProperty(PROP_DB_DIR, DEFAULT_DB_DIR);
        if (isClientDb())
            _kb = ((FloodfillNetworkDatabaseFacade) _context.netDb()).getKBuckets();
        else
            _kb = new KBucketSet<Hash>(_context, ri.getIdentity().getHash(),
                                   BUCKET_SIZE, KAD_B, new RejectTrimmer<Hash>());
        try {
            if (!isClientDb()) {
                _ds = new PersistentDataStore(_context, dbDir, this);
            } else {
                _ds = new TransientDataStore(_context);
            }
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to initialize netdb storage", ioe);
        }
        _dbDir = dbDir;
        _negativeCache = new NegativeLookupCache(_context);
        if (!isClientDb())
            blindCache().startup();
        
        createHandlers();
        
        _initialized = true;
        _started = System.currentTimeMillis();
        
        // expire old leases
        long now = _context.clock().now();
        _elj.getTiming().setStartAfter(now + 11*60*1000);
        _context.jobQueue().addJob(_elj);
        
        //// expire some routers
        // Don't run until after RefreshRoutersJob has run, and after validate() will return invalid for old routers.
        if (!isClientDb() && !_context.commSystem().isDummy()) {
            boolean isFF = _context.getBooleanProperty(FloodfillMonitorJob.PROP_FLOODFILL_PARTICIPANT);
            long down = _context.router().getEstimatedDowntime();
            long delay = (down == 0 || (!isFF && down > 30*60*1000) || (isFF && down > 24*60*60*1000)) ?
                         ROUTER_INFO_EXPIRATION_FLOODFILL + 10*60*1000 :
                         10*60*1000;
            _erj.getTiming().setStartAfter(now + delay);
            _context.jobQueue().addJob(_erj);
        }
        
        if (!QUIET) {
            if (!isClientDb()) {
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
                _exploreJob.getTiming().setStartAfter(now + EXPLORE_JOB_DELAY);
                _context.jobQueue().addJob(_exploreJob);
            }
        } else {
            _log.warn("Operating in quiet mode - not exploring or pushing data proactively, simply reactively");
            _log.warn("This should NOT be used in production");
        }
        if (!isClientDb()) {
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
    }
    
    /** unused, see override */
    protected void createHandlers() {}
    
    /**
     * Get the routers closest to that key in response to a remote lookup
     * Only used by ../HDLMJ
     * Set MAY INCLUDE our own router - add to peersToIgnore if you don't want
     *
     * @param key the real key, NOT the routing key
     * @param peersToIgnore can be null
     */
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore) {
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return Collections.emptySet();
        }
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
    
    /**
     *  Get the hashes for all known routers
     *
     *  @return empty set if this is a client DB
     */
    public Set<Hash> getAllRouters() {
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return Collections.emptySet();
        }
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
     *
     *  @return 0 if this is a client DB
     */
    @Override
    public int getKnownRouters() { 
/****
        if (_kb == null) return 0;
        CountRouters count = new CountRouters();
        _kb.getAll(count);
        return count.size();
****/
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return 0;
        }
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
            if (ds.isLeaseSet() &&
                ((LeaseSet)ds).getReceivedAsPublished())
                rv++;
        }
        return rv;
    }

    /**
     *  The KBucketSet contains RIs only.
     */
    protected int getKBucketSetSize() {  
        if (_kb == null) return 0;
        return _kb.size();
    }
    
    /**
     *  @param spk unblinded key
     *  @return BlindData or null
     *  @since 0.9.40
     */
    @Override
    public BlindData getBlindData(SigningPublicKey spk) {
        return blindCache().getData(spk);
    }
    
    /**
     *  @param bd new BlindData to put in the cache
     *  @since 0.9.40
     */
    @Override
    public void setBlindData(BlindData bd) {
        if (_log.shouldWarn())
            _log.warn("Adding to blind cache: " + bd);
        blindCache().addToCache(bd);
    }

    /**
     *  For console ConfigKeyringHelper
     *  @since 0.9.41
     */
    @Override
    public List<BlindData> getBlindData() {
        return blindCache().getData();
    }

    /**
     *  For console ConfigKeyringHelper
     *  @param spk the unblinded public key
     *  @return true if removed
     *  @since 0.9.41
     */
    @Override
    public boolean removeBlindData(SigningPublicKey spk) {
        return blindCache().removeBlindData(spk);
    }

    /**
     *  Notify the netDB that the routing key changed at midnight UTC
     *
     *  @since 0.9.50
     */
    @Override
    public void routingKeyChanged() {
        blindCache().rollover();
        if (_log.shouldInfo())
            _log.info("UTC rollover, blind cache updated");
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
        int type = rv.getType();
        if (DatabaseEntry.isLeaseSet(type)) {
            LeaseSet ls = (LeaseSet)rv;
            if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                return rv;
            } else {
                key = blindCache().getHash(key);
                fail(key);
            }
        } else if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
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
     *  @since 0.9.9, public since 0.9.38
     */
    public DatabaseEntry lookupLocallyWithoutValidation(Hash key) {
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
            if (_log.shouldInfo())
                _log.info("Negative cached, not searching LS: " + key);
            if (onFailedLookupJob != null)
                _context.jobQueue().addJob(onFailedLookupJob);
        } else {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("leaseSet not found locally, running search");
            key = blindCache().getHash(key);
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
        key = blindCache().getHash(key);
        if (isNegativeCached(key))
            return;
        search(key, null, null, 20*1000, true, fromLocalDest);
    }
    
    /**
     *  Unconditionally lookup using the client's tunnels.
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @param onFindJob may be null
     *  @param onFailedLookupJob may be null
     *  @since 0.9.47
     */
    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob,
                                       long timeoutMs, Hash fromLocalDest) {
        if (!_initialized) return;
        key = blindCache().getHash(key);
        if (isNegativeCached(key))
            return;
        search(key, onFindJob, onFailedLookupJob, timeoutMs, true, fromLocalDest);
    }

    /**
     *  Use lookupDestination() if you don't need the LS or don't need it validated.
     */
    public LeaseSet lookupLeaseSetLocally(Hash key) {
        if (!_initialized) return null;
        DatabaseEntry ds = _ds.get(key);
        if (ds != null) {
            if (ds.isLeaseSet()) {
                LeaseSet ls = (LeaseSet)ds;
                if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    return ls;
                } else {
                    key = blindCache().getHash(key);
                    fail(key);
                    // this was an interesting key, so either refetch it or simply explore with it
                    if (_exploreKeys != null)
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
            if (_log.shouldInfo())
                _log.info("Negative cached, not searching dest: " + key);
            _context.jobQueue().addJob(onFinishedJob);
        } else {
            key = blindCache().getHash(key);
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
            if (ds.isLeaseSet()) {
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
        } else if (_context.banlist().isBanlisted(key)) {
            if (onFailedLookupJob != null)
                _context.jobQueue().addJob(onFailedLookupJob);
        } else if (isNegativeCached(key)) {
            if (_log.shouldInfo())
                _log.info("Negative cached, not searching RI: " + key);
            if (onFailedLookupJob != null)
                _context.jobQueue().addJob(onFailedLookupJob);
        } else {
            search(key, onFindJob, onFailedLookupJob, timeoutMs, false);
        }
    }
    
    /**
     * This will return immediately with the result or null.
     * However, this may still fire off a lookup if the RI is present but expired (and will return null).
     * This may result in deadlocks.
     * For true local only, use lookupLocallyWithoutValidation()
     *
     * @return null always for client dbs
     */
    public RouterInfo lookupRouterInfoLocally(Hash key) {
        if (!_initialized) return null;
        // Client netDb shouldn't have RI, search for RI in the floodfill netDb.
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return null;
        }
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
        if (!_initialized) {
            if (_log.shouldWarn())
                _log.warn("publish() before initialized: " + localLeaseSet, new Exception("I did it"));
            return;
        }
        Hash h = localLeaseSet.getHash();
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
        
        RepublishLeaseSetJob j;
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
     *         or if this is a client DB
     */
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        if (isClientDb())
            throw new IllegalArgumentException("RI publish to client DB");
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
     * Determine whether this leaseSet will be accepted as valid and current
     * given what we know now.
     *
     * Unlike for RouterInfos, this is only called once, when stored.
     * After that, LeaseSet.isCurrent() is used.
     *
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return reason why the entry is not valid, or null if it is valid
     */
    public String validate(Hash key, LeaseSet leaseSet) throws UnsupportedCryptoException {
        if (!key.equals(leaseSet.getHash())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid store attempt! key does not match leaseSet.destination!  key = "
                          + key.toBase32() + ", leaseSet = " + leaseSet);
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
        long earliest;
        long latest;
        int type = leaseSet.getType();
        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            // we'll assume it's not an encrypted meta, for now
            earliest = ls2.getPublished();
            latest = ls2.getExpires();
        } else if (type == DatabaseEntry.KEY_TYPE_META_LS2) {
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            // TODO this isn't right, and must adjust limits below also
            earliest = Math.min(ls2.getEarliestLeaseDate(), ls2.getPublished());
            latest = Math.min(ls2.getLatestLeaseDate(), ls2.getExpires());
        } else {
            earliest = leaseSet.getEarliestLeaseDate();
            latest = leaseSet.getLatestLeaseDate();
        }
        long now = _context.clock().now();
        if (earliest <= now - 10*60*1000L ||
            // same as the isCurrent(Router.CLOCK_FUDGE_FACTOR) test in
            // lookupLeaseSetLocally()
            latest <= now - Router.CLOCK_FUDGE_FACTOR) {
            long age = now - earliest;
            Destination dest = leaseSet.getDestination();
            String id = dest != null ? dest.toBase32() : leaseSet.getHash().toBase32();
            if (_log.shouldWarn())
                _log.warn("Old leaseSet!  not storing it: " 
                          + id
                          + " first exp. " + new Date(earliest)
                          + " last exp. " + new Date(latest) + '\n' + leaseSet,
                          new Exception("Rejecting store"));
            // i2pd bug?
            // So we don't immediately go try to fetch it for a reply
            if (leaseSet.getLeaseCount() == 0) {
                for (int i = 0; i < NegativeLookupCache.MAX_FAILS; i++) {
                     lookupFailed(key);
                }
            }
            return "Expired leaseSet for " + id
                   + " expired " + DataHelper.formatDuration(age) + " ago";
        }
        if (latest > now + (Router.CLOCK_FUDGE_FACTOR + MAX_LEASE_FUTURE) &&
            (leaseSet.getType() != DatabaseEntry.KEY_TYPE_META_LS2 ||
             latest > now + (Router.CLOCK_FUDGE_FACTOR + MAX_META_LEASE_FUTURE))) {
            long age = latest - now;
            // let's not make this an error, it happens when peers have bad clocks
            Destination dest = leaseSet.getDestination();
            String id = dest != null ? dest.toBase32() : leaseSet.getHash().toBase32();
            if (_log.shouldLog(Log.WARN))
                _log.warn("LeaseSet expires too far in the future: " 
                          + id
                          + " expires " + DataHelper.formatDuration(age) + " from now");
            return "Future expiring leaseSet for " + id
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
        
        LeaseSet rv;
        try {
            rv = (LeaseSet)_ds.get(key);
            if (rv != null && rv.getEarliestLeaseDate() >= leaseSet.getEarliestLeaseDate()) {
                if (_log.shouldDebug())
                    _log.debug("Not storing older " + key);
                // if it hasn't changed, no need to do anything
                // except copy over the flags
                Hash to = leaseSet.getReceivedBy();
                if (to != null) {
                    rv.setReceivedBy(to);
                } else if (leaseSet.getReceivedAsReply()) {
                    rv.setReceivedAsReply();
                }
                if (leaseSet.getReceivedAsPublished()) {
                    rv.setReceivedAsPublished();
                }
                return rv;
            }
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException("Attempt to replace RI with " + leaseSet);
        }
        
        // spoof / hash collision detection
        // todo allow non-exp to overwrite exp
        if (rv != null) {
            Destination d1 = leaseSet.getDestination();
            Destination d2 = rv.getDestination();
            if (d1 != null && d2 != null && !d1.equals(d2))
                throw new IllegalArgumentException("LS Hash collision");
        }

        EncryptedLeaseSet encls = null;
        int type = leaseSet.getType();
        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            // set dest or key before validate() calls verifySignature() which
            // will do the decryption
            encls = (EncryptedLeaseSet) leaseSet;
            BlindData bd = blindCache().getReverseData(leaseSet.getSigningKey());
            if (bd != null) {
                if (_log.shouldWarn())
                    _log.warn("Found blind data for encls: " + bd);
                // secret must be set before destination
                String secret = bd.getSecret();
                if (secret != null)
                    encls.setSecret(secret);
                Destination dest = bd.getDestination();
                if (dest != null) {
                    encls.setDestination(dest);
                } else {
                    encls.setSigningKey(bd.getUnblindedPubKey());
                }
                // per-client auth
                if (bd.getAuthType() != BlindData.AUTH_NONE)
                    encls.setClientPrivateKey(bd.getAuthPrivKey());
            } else {
                // if we created it, there's no blind data, but it's still decrypted
                if (encls.getDecryptedLeaseSet() == null && _log.shouldWarn())
                    _log.warn("No blind data found for encls: " + leaseSet);
            }
        }


        String err = validate(key, leaseSet);
        if (err != null)
            throw new IllegalArgumentException("Invalid store attempt - " + err);

        if (_log.shouldDebug())
            _log.debug("Storing LS to the data store...");
        _ds.put(key, leaseSet);
        
        if (encls != null) {
            // we now have decrypted it, store it as well
            LeaseSet decls = encls.getDecryptedLeaseSet();
            if (decls != null) {
                if (_log.shouldWarn())
                    _log.warn("Successfully decrypted encls: " + decls);
                // recursion
                Destination dest = decls.getDestination();
                store(dest.getHash(), decls);
                blindCache().setBlinded(dest);
            }
        } else if (type == DatabaseEntry.KEY_TYPE_LS2 || type == DatabaseEntry.KEY_TYPE_META_LS2) {
             // if it came in via garlic
             LeaseSet2 ls2 = (LeaseSet2) leaseSet;
             if (ls2.isBlindedWhenPublished()) {
                 Destination dest = leaseSet.getDestination();
                 if (dest != null)
                    blindCache().setBlinded(dest, null, null);
            }
        }

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
        int id = routerInfo.getNetworkId();
        if (id != _networkID){
            if (id == -1) {
                // old i2pd bug, possibly at startup, don't ban forever
                _context.banlist().banlistRouter(key, "No network specified", null, null, _context.clock().now() + Banlist.BANLIST_DURATION_NO_NETWORK);
            } else {
                _context.banlist().banlistRouterForever(key, "Not in our network: " + id);
            }
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not in our network: " + routerInfo, new Exception());
            return "Not in our network";
        }
        FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
        if (fkc != null) {
            FamilyKeyCrypto.Result r = fkc.verify(routerInfo);
            switch (r) {
                case BAD_KEY:
                case INVALID_SIG:
                    Hash h = routerInfo.getHash();
                    // never fail our own router, that would cause a restart and rekey
                    if (h.equals(_context.routerHash()))
                        break;
                    return "Bad family " + r + ' ' + h;

                case NO_SIG:
                    // Routers older than 0.9.54 that added a family and haven't restarted
                    break;

                case BAD_SIG:
                    // To be investigated
                    break;
            }
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
        if (_context.commSystem().isDummy())
            return null;
        long now = _context.clock().now();
        boolean upLongEnough = _context.router().getUptime() > 60*60*1000;
        if (!upLongEnough && !SystemVersion.isAndroid()) {
            long down = _context.router().getEstimatedDowntime();
            upLongEnough = down > 0 && down < 10*60*60*1000L;
        }
        // Once we're over MIN_ROUTERS routers, reduce the expiration time down from the default,
        // as a crude way of limiting memory usage.
        // i.e. at 2*MIN_ROUTERS routers the expiration time will be about half the default, etc.
        // And if we're floodfill, we can keep the expiration really short, since
        // we are always getting the latest published to us.
        // As the net grows this won't be sufficient, and we'll have to implement
        // flushing some from memory, while keeping all on disk.
        long adjustedExpiration;
        if (floodfillEnabled() || (_ds != null && _ds.size() > 5000))
            adjustedExpiration = ROUTER_INFO_EXPIRATION_FLOODFILL;
        else
            adjustedExpiration = Math.min(ROUTER_INFO_EXPIRATION,
                                          ROUTER_INFO_EXPIRATION_MIN +
                                          ((ROUTER_INFO_EXPIRATION - ROUTER_INFO_EXPIRATION_MIN) * MIN_ROUTERS / (_kb.size() + 1)));

        if (upLongEnough && !routerInfo.isCurrent(adjustedExpiration)) {
            long age = now - routerInfo.getPublished();
            int existing = _kb.size();
            if (existing >= MIN_REMAINING_ROUTERS) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Expired RI " + routerInfo.getIdentity().getHash(), new Exception());
                return "RI expired " + DataHelper.formatDuration(age) + " ago";
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Even though the peer is old, we have only " + existing
                              + " peers left " + routerInfo);
            }
        }
        long age = now - routerInfo.getPublished();
        if (age < 0 - 2*Router.CLOCK_FUDGE_FACTOR) {
            String skewString = DataHelper.formatDuration(0 - age);
            if (_log.shouldLog(Log.INFO))
                _log.info("RI " + routerInfo.getIdentity().getHash() + " published in the future?! [" 
                          + skewString + ']', new Exception());
            if (upLongEnough && _context.commSystem().countActivePeers() >= 50) {
                // we can be fairly confident that his clock is in the future,
                // not that ours is in the past, so ban him for a while
                _context.banlist().banlistRouter(routerInfo.getHash(), "Excessive clock skew: {0}", skewString, null, now + 60*60*1000);
            }
            return "RI published " + skewString + " in the future?!";
        }
        if (!routerInfo.isCurrent(ROUTER_INFO_EXPIRATION_INTRODUCED)) {
            if (routerInfo.getAddresses().isEmpty())
                return "Old RI with no addresses";
            // This should cover the introducers case below too
            // And even better, catches the case where the router is unreachable but knows no introducers
            if (routerInfo.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0)
                return "Old RI and thinks it is unreachable";
            // Just check all the addresses, faster than getting just the SSU ones
            for (RouterAddress ra : routerInfo.getAddresses()) {
                // Introducers change often, introducee will ping introducer for 2 hours
                if (ra.getOption("itag0") != null)
                    return "Old RI with SSU Introducers";
            }
        }
        if (upLongEnough && age > 2*24*60*60*1000l) {
            return "RI published " + DataHelper.formatDuration(age) + " ago";
        }
        if (upLongEnough && !routerInfo.isCurrent(ROUTER_INFO_EXPIRATION_SHORT)) {
            if (routerInfo.getTargetAddresses("NTCP", "NTCP2").isEmpty())
                return "RI published > 75m ago, SSU only without introducers";
        }
        for (RouterAddress ra : routerInfo.getTargetAddresses("NTCP2")) {
            String i = ra.getOption("i");
            if (i != null && i.length() != 24) {
                _context.banlist().banlistRouter(routerInfo.getIdentity().calculateHash(), "Bad address", null, null, now + 15*60*1000L);
                return "Bad NTCP2 address";
            }
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
        if (isClientDb())
            throw new IllegalArgumentException("RI store to client DB");
        
        RouterInfo rv;
        try {
            rv = (RouterInfo)_ds.get(key, persist);
            if (rv != null && rv.getPublished() >= routerInfo.getPublished()) {
                if (_log.shouldDebug())
                    _log.debug("Not storing older " + key);
                // quick check without calling validate()
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
            throw new IllegalArgumentException("Invalid store attempt of RI " + key.toBase64() + " - " + err);
        
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
            int etype = entry.getType();
            if (DatabaseEntry.isLeaseSet(etype)) {
                LeaseSet ls = (LeaseSet) entry;
                Destination d = ls.getDestination();
                // will be null for encrypted LS
                if (d != null) {
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
                }
            } else if (etype == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
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
            if (_kb != null)
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
        if (!isClientDb()) {
            _ds.remove(dbEntry, false);
        } else {
            // if this happens it's because we're a TransientDataStore instead,
            // so just call remove without the persist option.
            _ds.remove(dbEntry);
        }
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
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return;
        }
        _context.peerManager().removeCapabilities(peer);
        _negativeCache.cache(peer);
        _kb.remove(peer);
        //if (removed) {
        //    if (_log.shouldLog(Log.INFO))
        //        _log.info("Removed kbucket entry for " + peer);
        //}
        
        _ds.remove(peer);
    }
    
    public void unpublish(LeaseSet localLeaseSet) {
        if (!_initialized) return;
        Hash h = localLeaseSet.getHash();
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
            if (o.isLeaseSet())
                leases.add((LeaseSet)o);
        }
        return leases;
    }

    /**
     *  Public for NetDbRenderer in routerconsole
     *
     *  @return empty set if this is a client DB
     */
    @Override
    public Set<RouterInfo> getRouters() {
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return Collections.emptySet();
        }
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
    private static final int MAX_PER_PEER_TIMEOUT = 5100;
    private static final int TIMEOUT_MULTIPLIER = 3;

    /** todo: does this need more tuning? */
    public int getPeerTimeout(Hash peer) {
        PeerProfile prof = _context.profileOrganizer().getProfile(peer);
        double responseTime = MAX_PER_PEER_TIMEOUT;
        if (prof != null && prof.getIsExpandedDB()) {
            responseTime = prof.getDbResponseTime().getRate(60*60*1000L).getAvgOrLifetimeAvg();
            // if 0 then there is no data, set to max.
            if (responseTime <= 0 || responseTime > MAX_PER_PEER_TIMEOUT)
                responseTime = MAX_PER_PEER_TIMEOUT;
            else if (responseTime < MIN_PER_PEER_TIMEOUT)
                responseTime = MIN_PER_PEER_TIMEOUT;
        }
        return TIMEOUT_MULTIPLIER * (int)responseTime;  // give it up to 3x the average response time
    }

    /**
     * See implementation in FNDF
     *
     * @param key the DatabaseEntry hash
     * @param onSuccess may be null, always called if we are ff and ds is an RI
     * @param onFailure may be null, ignored if we are ff and ds is an RI
     * @param sendTimeout ignored if we are ff and ds is an RI
     * @param toIgnore may be null, if non-null, all attempted and skipped targets will be added as of 0.9.53,
     *                 unused if we are ff and ds is an RI
     */
    abstract void sendStore(Hash key, DatabaseEntry ds, Job onSuccess, Job onFailure, long sendTimeout, Set<Hash> toIgnore);

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
        if (_kb == null)
            return;
        out.write(_kb.toString().replace("\n", "<br>\n"));
    }

    /**
     * @since 0.9.61
     */
    @Override
    public String toString() {
        if (!isClientDb())
            return "Main NetDB";
        return "Client NetDB " + _dbid.toBase64();
    }
}
