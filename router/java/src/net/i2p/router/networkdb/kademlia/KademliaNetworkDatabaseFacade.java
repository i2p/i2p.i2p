package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.Job;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.DatabaseLookupMessageHandler;
import net.i2p.router.networkdb.DatabaseStoreMessageHandler;
import net.i2p.router.networkdb.PublishLocalRouterInfoJob;
import net.i2p.util.Log;

/**
 * Kademlia based version of the network database
 *
 */
public class KademliaNetworkDatabaseFacade extends NetworkDatabaseFacade {
    private Log _log;
    private KBucketSet _kb; // peer hashes sorted into kbuckets, but within kbuckets, unsorted
    private DataStore _ds; // hash to DataStructure mapping, persisted when necessary
    /** where the data store is pushing the data */
    private String _dbDir;
    private Set _explicitSendKeys; // set of Hash objects that should be published ASAP
    private Set _passiveSendKeys; // set of Hash objects that should be published when there's time
    private Set _exploreKeys; // set of Hash objects that we should search on (to fill up a bucket, not to get data)
    private Map _lastSent; // Hash to Long (date last sent, or <= 0 for never)
    private boolean _initialized;
    /** Clock independent time of when we started up */
    private long _started;
    private StartExplorersJob _exploreJob;
    private HarvesterJob _harvestJob;
    /** when was the last time an exploration found something new? */
    private long _lastExploreNew;
    private PeerSelector _peerSelector;
    private RouterContext _context;
    /** 
     * set of Hash objects of leases we're already managing (via RepublishLeaseSetJob).
     * This is added to when we create a new RepublishLeaseSetJob, and the values are 
     * removed when the job decides to stop running.
     *
     */
    private Set _publishingLeaseSets;
    
    /**
     * List of keys that we've recently done full searches on and failed.
     *
     */
    private Set _badKeys;
    /**
     * Mapping when (Long) to Hash for keys that are in the _badKeys list.
     *
     */
    private Map _badKeyDates;
    
    /**
     * for the 10 minutes after startup, don't fail db entries so that if we were
     * offline for a while, we'll have a chance of finding some live peers with the
     * previous references
     */
    private final static long DONT_FAIL_PERIOD = 10*60*1000;
    
    /** don't probe or broadcast data, just respond and search when explicitly needed */
    private boolean _quiet = false;
    
    public final static String PROP_DB_DIR = "router.networkDatabase.dbDir";
    public final static String DEFAULT_DB_DIR = "netDb";
    
    /** if we have less than 5 routers left, don't drop any more, even if they're failing or doing bad shit */
    private final static int MIN_REMAINING_ROUTERS = 5;
    
    public KademliaNetworkDatabaseFacade(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(KademliaNetworkDatabaseFacade.class);
        _initialized = false;
        _peerSelector = new PeerSelector(_context);
        _publishingLeaseSets = new HashSet(8);
        _lastExploreNew = 0;
        _badKeys = new HashSet();
        _badKeyDates = new TreeMap();
    }
    
    /** 
     * if we do a full search for a key and still fail, don't try again 
     * for another 5 minutes 
     */
    private static final long SHITLIST_TIME = 5*60*1000;
    
    /**
     * Are we currently avoiding this key?
     *
     */
    boolean isShitlisted(Hash key) {
        synchronized (_badKeys) {
            locked_cleanupShitlist();
            boolean isShitlisted = _badKeys.contains(key);
            if (!isShitlisted) return false;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Key " + key.toBase64() + " is shitlisted");
            return true;
        }
    }
    /**
     * For some reason, we don't want to try any remote lookups for this key 
     * anytime soon - for instance, we may have just failed to find it after a
     * full netDb search.  
     *
     */
    void shitlist(Hash key) {
        synchronized (_badKeys) {
            locked_cleanupShitlist();
            boolean wasNew = _badKeys.add(key);
            if (wasNew) {
                long when = _context.clock().now();
                while (_badKeyDates.containsKey(new Long(when)))
                    when++;
                _badKeyDates.put(new Long(when), key);
                _log.info("Shitlist " + key.toBase64() + " - new shitlist");
            } else {
                _log.info("Shitlist " + key.toBase64() + " - already shitlisted");
            }
        }
    }
    private void locked_cleanupShitlist() {
        List old = null;
        long keepAfter = _context.clock().now() - SHITLIST_TIME;
        for (Iterator iter = _badKeyDates.keySet().iterator(); iter.hasNext(); ) {
            Long when = (Long)iter.next();
            Hash key = (Hash)_badKeyDates.get(when);
            if (when.longValue() < keepAfter) {
                if (old == null)
                    old = new ArrayList(4);
                old.add(when);
            } else {
                // ordered
                break;
            }
        }
        if (old != null) {
            for (int i = 0; i < old.size(); i++) {
                Long when = (Long)old.get(i);
                Hash key = (Hash)_badKeyDates.remove(when);
                _badKeys.remove(key);
            }
        }
        
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Cleaning up shitlist: " + _badKeys.size() + " remain after removing " 
                       + (old != null ? old.size() : 0));
    }
    
    KBucketSet getKBuckets() { return _kb; }
    DataStore getDataStore() { return _ds; }
    
    long getLastExploreNewDate() { return _lastExploreNew; }
    void setLastExploreNewDate(long when) { 
        _lastExploreNew = when; 
        if (_exploreJob != null)
            _exploreJob.updateExploreSchedule();
    }
    
    public Set getExplicitSendKeys() {
        if (!_initialized) return null;
        synchronized (_explicitSendKeys) {
            return new HashSet(_explicitSendKeys);
        }
    }
    public Set getPassivelySendKeys() {
        if (!_initialized) return null;
        synchronized (_passiveSendKeys) {
            return new HashSet(_passiveSendKeys);
        }
    }
    public void removeFromExplicitSend(Set toRemove) {
        if (!_initialized) return;
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.removeAll(toRemove);
        }
    }
    public void removeFromPassiveSend(Set toRemove) {
        if (!_initialized) return;
        synchronized (_passiveSendKeys) {
            _passiveSendKeys.removeAll(toRemove);
        }
    }
    public void queueForPublishing(Set toSend) {
        if (!_initialized) return;
        synchronized (_passiveSendKeys) {
            _passiveSendKeys.addAll(toSend);
        }
    }
    
    public Long getLastSent(Hash key) {
        if (!_initialized) return null;
        synchronized (_lastSent) {
            if (!_lastSent.containsKey(key))
                _lastSent.put(key, new Long(0));
            return (Long)_lastSent.get(key);
        }
    }
    
    public void noteKeySent(Hash key) {
        if (!_initialized) return;
        synchronized (_lastSent) {
            _lastSent.put(key, new Long(_context.clock().now()));
        }
    }
    
    public Set getExploreKeys() {
        if (!_initialized) return null;
        synchronized (_exploreKeys) {
            return new HashSet(_exploreKeys);
        }
    }
    
    public void removeFromExploreKeys(Set toRemove) {
        if (!_initialized) return;
        synchronized (_exploreKeys) {
            _exploreKeys.removeAll(toRemove);
        }
    }
    public void queueForExploration(Set keys) {
        if (!_initialized) return;
        synchronized (_exploreKeys) {
            _exploreKeys.addAll(keys);
        }
    }
    
    public void shutdown() {
        _initialized = false;
        _kb = null;
        _ds = null;
        _explicitSendKeys = null;
        _passiveSendKeys = null;
        _exploreKeys = null;
        _lastSent = null;
    }
    
    public void restart() {
        _dbDir = _context.router().getConfigSetting(PROP_DB_DIR);
        if (_dbDir == null) {
            _log.info("No DB dir specified [" + PROP_DB_DIR + "], using [" + DEFAULT_DB_DIR + "]");
            _dbDir = DEFAULT_DB_DIR;
        }
        _ds.restart();
        synchronized (_explicitSendKeys) { _explicitSendKeys.clear(); }
        synchronized (_exploreKeys) { _exploreKeys.clear(); }
        synchronized (_passiveSendKeys) { _passiveSendKeys.clear(); }

        _initialized = true;
        
        RouterInfo ri = _context.router().getRouterInfo();
        publish(ri);
    }
    
    String getDbDir() { return _dbDir; }
    
    public void startup() {
        _log.info("Starting up the kademlia network database");
        RouterInfo ri = _context.router().getRouterInfo();
        String dbDir = _context.router().getConfigSetting(PROP_DB_DIR);
        if (dbDir == null) {
            _log.info("No DB dir specified [" + PROP_DB_DIR + "], using [" + DEFAULT_DB_DIR + "]");
            dbDir = DEFAULT_DB_DIR;
        }
        
        _kb = new KBucketSet(_context, ri.getIdentity().getHash());
        _ds = new PersistentDataStore(_context, dbDir, this);
        //_ds = new TransientDataStore();
        _explicitSendKeys = new HashSet(64);
        _passiveSendKeys = new HashSet(64);
        _exploreKeys = new HashSet(64);
        _lastSent = new HashMap(1024);
        _dbDir = dbDir;
        
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseLookupMessage.MESSAGE_TYPE, new DatabaseLookupMessageHandler(_context));
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseStoreMessage.MESSAGE_TYPE, new DatabaseStoreMessageHandler(_context));
        
        _initialized = true;
        _started = System.currentTimeMillis();
        
        // read the queues and publish appropriately
        _context.jobQueue().addJob(new DataPublisherJob(_context, this));
        // expire old leases
        _context.jobQueue().addJob(new ExpireLeasesJob(_context, this));
        // expire some routers in overly full kbuckets
        _context.jobQueue().addJob(new ExpireRoutersJob(_context, this));
        if (!_quiet) {
            // fill the passive queue periodically
            _context.jobQueue().addJob(new DataRepublishingSelectorJob(_context, this));
            // fill the search queue with random keys in buckets that are too small
            _context.jobQueue().addJob(new ExploreKeySelectorJob(_context, this));
            if (_exploreJob == null)
                _exploreJob = new StartExplorersJob(_context, this);
            // fire off a group of searches from the explore pool
            _context.jobQueue().addJob(_exploreJob);
            // if configured to do so, periodically try to get newer routerInfo stats
            if (_harvestJob == null)
                _harvestJob = new HarvesterJob(_context, this);
            _context.jobQueue().addJob(_harvestJob);
        } else {
            _log.warn("Operating in quiet mode - not exploring or pushing data proactively, simply reactively");
            _log.warn("This should NOT be used in production");
        }
        // periodically update and resign the router's 'published date', which basically
        // serves as a version
        _context.jobQueue().addJob(new PublishLocalRouterInfoJob(_context));
        publish(ri);
    }
    
    /**
     * Get the routers closest to that key in response to a remote lookup
     */
    public Set findNearestRouters(Hash key, int maxNumRouters, Set peersToIgnore) {
        if (!_initialized) return null;
        return getRouters(_peerSelector.selectNearest(key, maxNumRouters, peersToIgnore, _kb));
    }
    
    private Set getRouters(Collection hashes) {
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
    
    /** get the hashes for all known routers */
    Set getAllRouters() {
        if (!_initialized) return new HashSet(0);
        Set keys = _ds.getKeys();
        Set rv = new HashSet(keys.size());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("getAllRouters(): # keys in the datastore: " + keys.size());
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            
            DataStructure ds = _ds.get(key);
            if (ds == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Selected hash " + key.toBase64() + " is not stored locally");
            } else if ( !(ds instanceof RouterInfo) ) {
                // leaseSet 
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("getAllRouters(): key is router: " + key.toBase64());
                rv.add(key);
            }
        }
        return rv;
    }
    
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        if (!_initialized) return;
        LeaseSet ls = lookupLeaseSetLocally(key);
        if (ls != null) {
            if (onFindJob != null)
                _context.jobQueue().addJob(onFindJob);
        } else {
            search(key, onFindJob, onFailedLookupJob, timeoutMs, true);
        }
    }
    
    public LeaseSet lookupLeaseSetLocally(Hash key) {
        if (!_initialized) return null;
        if (_ds.isKnown(key)) {
            DataStructure ds = _ds.get(key);
            if (ds instanceof LeaseSet) {
                LeaseSet ls = (LeaseSet)ds;
                if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    return ls;
                } else {
                    fail(key);
                    // this was an interesting key, so either refetch it or simply explore with it
                    synchronized (_exploreKeys) {
                        _exploreKeys.add(key);
                    }
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
    
    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        if (!_initialized) return;
        RouterInfo ri = lookupRouterInfoLocally(key);
        if (ri != null) {
            if (onFindJob != null)
                _context.jobQueue().addJob(onFindJob);
        } else {
            search(key, onFindJob, onFailedLookupJob, timeoutMs, false);
        }
    }
    
    public RouterInfo lookupRouterInfoLocally(Hash key) {
        if (!_initialized) return null;
        if (_ds.isKnown(key)) {
            DataStructure ds = _ds.get(key);
            if (ds instanceof RouterInfo)
                return (RouterInfo)ds;
            else {
                //_log.debug("Looking for a router [" + key + "] but it ISN'T a RouterInfo! " + ds, new Exception("Who thought that lease was a router?"));
                return null;
            }
        } else {
            return null;
        }
    }
    
    public void publish(LeaseSet localLeaseSet) {
        if (!_initialized) return;
        Hash h = localLeaseSet.getDestination().calculateHash();
        store(h, localLeaseSet);
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.add(h);
        }
        Job j = null;
        synchronized (_publishingLeaseSets) {
            boolean isNew = _publishingLeaseSets.add(h);
            if (isNew)
                j = new RepublishLeaseSetJob(_context, this, h);
        }
        if (j != null)
            _context.jobQueue().addJob(j);
    }
    
    void stopPublishing(Hash target) {
        synchronized (_publishingLeaseSets) {
            _publishingLeaseSets.remove(target);
        }
    }
    
    public void publish(RouterInfo localRouterInfo) {
        if (!_initialized) return;
        Hash h = localRouterInfo.getIdentity().getHash();
        store(h, localRouterInfo);
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.add(h);
        }
        writeMyInfo(localRouterInfo);
    }

    /**
     * Persist the local router's info (as updated) into netDb/my.info, since
     * ./router.info isn't always updated.  This also allows external applications
     * to easily pick out which router a netDb directory is rooted off, which is handy
     * for getting the freshest data.
     *
     */
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
    
    /** I don't think it'll ever make sense to have a lease last for a full day */
    private static final long MAX_LEASE_FUTURE = 24*60*60*1000;
    
    public LeaseSet store(Hash key, LeaseSet leaseSet) {
        long start = _context.clock().now();
        if (!_initialized) return null;
        if (!key.equals(leaseSet.getDestination().calculateHash())) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid store attempt! key does not match leaseSet.destination!  key = "
                + key + ", leaseSet = " + leaseSet);
            return null;
        } else if (!leaseSet.verifySignature()) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid leaseSet signature!  leaseSet = " + leaseSet);
            return null;
        } else if (leaseSet.getEarliestLeaseDate() <= _context.clock().now()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Old leaseSet!  not storing it: " 
                          + leaseSet.getDestination().calculateHash().toBase64() 
                          + " expires on " + new Date(leaseSet.getEarliestLeaseDate()), new Exception("Rejecting store"));
            return null;
        } else if (leaseSet.getEarliestLeaseDate() > _context.clock().now() + MAX_LEASE_FUTURE) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("LeaseSet to expire too far in the future: " 
                          + leaseSet.getDestination().calculateHash().toBase64() 
                          + " expires on " + new Date(leaseSet.getEarliestLeaseDate()), new Exception("Rejecting store"));
            return null;
        }
        
        LeaseSet rv = null;
        if (_ds.isKnown(key))
            rv = (LeaseSet)_ds.get(key);
        _ds.put(key, leaseSet);
        synchronized (_lastSent) {
            if (!_lastSent.containsKey(key))
                _lastSent.put(key, new Long(0));
        }
        
        // Iterate through the old failure / success count, copying over the old
        // values (if any tunnels overlap between leaseSets).  no need to be
        // ueberthreadsafe fascists here, since these values are just heuristics
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
        
        long end = _context.clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Store leaseSet took [" + (end-start) + "ms]");
        return rv;
    }
    
    public RouterInfo store(Hash key, RouterInfo routerInfo) {
        long start = _context.clock().now();
        if (!_initialized) return null;
        if (!key.equals(routerInfo.getIdentity().getHash())) {
            _log.error("Invalid store attempt! key does not match routerInfo.identity!  key = " + key + ", router = " + routerInfo);
            return null;
        } else if (!routerInfo.isValid()) {
            _log.error("Invalid routerInfo signature!  forged router structure!  router = " + routerInfo);
            return null;
        } else if (!routerInfo.isCurrent(ExpireRoutersJob.EXPIRE_DELAY)) {
            int existing = _kb.size();
            if (existing >= MIN_REMAINING_ROUTERS) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Not storing expired router for " + key.toBase64(), new Exception("Rejecting store"));
                return null;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Even though the peer is old, we have only " + existing
                    + " peers left (curPeer: " + key.toBase64() + " published on "
                    + new Date(routerInfo.getPublished()));
            }
        } else if (routerInfo.getPublished() > start + Router.CLOCK_FUDGE_FACTOR) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Peer " + key.toBase64() + " published their routerInfo in the future?! [" 
                          + new Date(routerInfo.getPublished()) + "]", new Exception("Rejecting store"));
            return null;
        }
        
        RouterInfo rv = null;
        if (_ds.isKnown(key))
            rv = (RouterInfo)_ds.get(key);
        
        if (_log.shouldLog(Log.INFO))
            _log.info("RouterInfo " + key.toBase64() + " is stored with "
            + routerInfo.getOptions().size() + " options on "
            + new Date(routerInfo.getPublished()));
        
        _ds.put(key, routerInfo);
        synchronized (_lastSent) {
            if (!_lastSent.containsKey(key))
                _lastSent.put(key, new Long(0));
        }
        _kb.add(key);
        long end = _context.clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Store routerInfo took [" + (end-start) + "ms]");
        return rv;
    }
    
    public void fail(Hash dbEntry) {
        if (!_initialized) return;
        boolean isRouterInfo = false;
        Object o = _ds.get(dbEntry);
        if (o instanceof RouterInfo)
            isRouterInfo = true;
        
        if (isRouterInfo) {
            int remaining = _kb.size();
            if (remaining < MIN_REMAINING_ROUTERS) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Not removing " + dbEntry + " because we have so few routers left ("
                              + remaining + ") - perhaps a reseed is necessary?");
                return;
            }
            if (System.currentTimeMillis() < _started + DONT_FAIL_PERIOD) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not failing the key " + dbEntry.toBase64()
                              + " since we've just started up and don't want to drop /everyone/");
                return;
            }
            
            boolean removed = _kb.remove(dbEntry);
            if (removed) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Removed kbucket entry for " + dbEntry);
            }
        } else {
            // we always drop leaseSets that are failed [timed out],
            // regardless of how many routers we have.  this is called on a lease if
            // it has expired *or* its tunnels are failing and we want to see if there
            // are any updates
            if (_log.shouldLog(Log.INFO))
                _log.info("Dropping a lease: " + dbEntry);
        }
        
        _ds.remove(dbEntry);
        synchronized (_lastSent) {
            _lastSent.remove(dbEntry);
        }
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.remove(dbEntry);
        }
        synchronized (_passiveSendKeys) {
            _passiveSendKeys.remove(dbEntry);
        }
    }
    
    public void unpublish(LeaseSet localLeaseSet) {
        if (!_initialized) return;
        Hash h = localLeaseSet.getDestination().calculateHash();
        DataStructure data = _ds.remove(h);
        synchronized (_lastSent) {
            _lastSent.remove(h);
        }
        synchronized (_explicitSendKeys) {
            _explicitSendKeys.remove(h);
        }
        synchronized (_passiveSendKeys) {
            _passiveSendKeys.remove(h);
        }
        
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
     */
    private void search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease) {
        if (!_initialized) return;
        if (isShitlisted(key)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not searching for a shitlisted key [" + key.toBase64() + "]");
            if (onFailedLookupJob != null)
                _context.jobQueue().addJob(onFailedLookupJob);
            return;
        }
        // all searching is indirect (through tunnels) now
        _context.jobQueue().addJob(new SearchJob(_context, this, key, onFindJob, onFailedLookupJob, timeoutMs, true, isLease));
    }
    
    private Set getLeases() {
        if (!_initialized) return null;
        Set leases = new HashSet();
        Set keys = getDataStore().getKeys();
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            Object o = getDataStore().get(key);
            if (o instanceof LeaseSet)
                leases.add(o);
        }
        return leases;
    }
    private Set getRouters() {
        if (!_initialized) return null;
        Set routers = new HashSet();
        Set keys = getDataStore().getKeys();
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            Hash key = (Hash)iter.next();
            Object o = getDataStore().get(key);
            if (o instanceof RouterInfo)
                routers.add(o);
        }
        return routers;
    }
    
    public void renderStatusHTML(OutputStream out) throws IOException {
        StringBuffer buf = new StringBuffer(10*1024);
        buf.append("<h2>Kademlia Network DB Contents</h2>\n");
        if (!_initialized) {
            buf.append("<i>Not initialized</i>\n");
            out.write(buf.toString().getBytes());
            return;
        }
        Set leases = getLeases();
        buf.append("<h3>Leases</h3>\n");
        out.write(buf.toString().getBytes());
        buf.setLength(0);
        long now = _context.clock().now();
        for (Iterator iter = leases.iterator(); iter.hasNext(); ) {
            LeaseSet ls = (LeaseSet)iter.next();
            Hash key = ls.getDestination().calculateHash();
            buf.append("<b>LeaseSet: ").append(key.toBase64()).append("</b><br />\n");
            long exp = ls.getEarliestLeaseDate()-now;
            if (exp > 0)
                buf.append("Earliest expiration date in: <i>").append(DataHelper.formatDuration(exp)).append("</i><br />\n");
            else
                buf.append("Earliest expiration date was: <i>").append(DataHelper.formatDuration(0-exp)).append(" ago</i><br />\n");
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                buf.append("Lease ").append(i).append(": gateway <i>");
                buf.append(ls.getLease(i).getRouterIdentity().getHash().toBase64().substring(0,6));
                buf.append("</i> tunnelId <i>").append(ls.getLease(i).getTunnelId().getTunnelId()).append("</i><br />\n");
            }
            buf.append("<hr />\n");
            out.write(buf.toString().getBytes());
            buf.setLength(0);
        }
        
        Hash us = _context.routerHash();
        Set routers = getRouters();
        out.write("<h3>Routers</h3>\n".getBytes());
        
        RouterInfo ourInfo = _context.router().getRouterInfo();
        renderRouterInfo(buf, ourInfo, true);
        out.write(buf.toString().getBytes());
        buf.setLength(0);
        
        /* coreVersion to Map of routerVersion to Integer */
        Map versions = new TreeMap();
        
        for (Iterator iter = routers.iterator(); iter.hasNext(); ) {
            RouterInfo ri = (RouterInfo)iter.next();
            Hash key = ri.getIdentity().getHash();
            boolean isUs = key.equals(us);
            if (!isUs) {
                renderRouterInfo(buf, ri, false);
                out.write(buf.toString().getBytes());
                buf.setLength(0);
                String coreVersion = ri.getOptions().getProperty("coreVersion");
                String routerVersion = ri.getOptions().getProperty("router.version");
                if ( (coreVersion != null) && (routerVersion != null) ) {
                    Map routerVersions = (Map)versions.get(coreVersion);
                    if (routerVersions == null) {
                        routerVersions = new TreeMap();
                        versions.put(coreVersion, routerVersions);
                    }
                    Integer val = (Integer)routerVersions.get(routerVersion);
                    if (val == null)
                        routerVersions.put(routerVersion, new Integer(1));
                    else
                        routerVersions.put(routerVersion, new Integer(val.intValue() + 1));
                }
            }
        }
            
        if (versions.size() > 0) {
            buf.append("<table border=\"1\">\n");
            buf.append("<tr><td><b>Core version</b></td><td><b>Router version</b></td><td><b>Number</b></td></tr>\n");
            for (Iterator iter = versions.keySet().iterator(); iter.hasNext(); ) {
                String coreVersion = (String)iter.next();
                Map routerVersions = (Map)versions.get(coreVersion);
                for (Iterator routerIter = routerVersions.keySet().iterator(); routerIter.hasNext(); ) {
                    String routerVersion = (String)routerIter.next();
                    Integer num = (Integer)routerVersions.get(routerVersion);
                    buf.append("<tr><td>").append(DataHelper.stripHTML(coreVersion));
                    buf.append("</td><td>").append(DataHelper.stripHTML(routerVersion));
                    buf.append("</td><td>").append(num.intValue()).append("</td></tr>\n");
                }
            }
            buf.append("</table>\n");
        }
        out.write(buf.toString().getBytes());
    }
    
    private void renderRouterInfo(StringBuffer buf, RouterInfo info, boolean isUs) {
        if (isUs) {
            buf.append("<b>Our info: </b><br />\n");
        } else {
            String hash = info.getIdentity().getHash().toBase64();
            buf.append("<a name=\"").append(hash.substring(0, 6)).append("\" />");
            buf.append("<b>Peer info for:</b> ").append(hash).append("<br />\n");
        }
        
        long age = _context.clock().now() - info.getPublished();
        if (age > 0)
            buf.append("Published: <i>").append(DataHelper.formatDuration(age)).append(" ago</i><br />\n");
        else
            buf.append("Published: <i>in ").append(DataHelper.formatDuration(0-age)).append("???</i><br />\n");
        buf.append("Address(es): <i>");
        for (Iterator iter = info.getAddresses().iterator(); iter.hasNext(); ) {
            RouterAddress addr = (RouterAddress)iter.next();
            buf.append(addr.getTransportStyle()).append(": ");
            for (Iterator optIter = addr.getOptions().keySet().iterator(); optIter.hasNext(); ) {
                String name = (String)optIter.next();
                String val = addr.getOptions().getProperty(name);
                buf.append('[').append(DataHelper.stripHTML(name)).append('=').append(DataHelper.stripHTML(val)).append("] ");
            }
        }
        buf.append("</i><br />\n");
        buf.append("Stats: <br /><i><code>\n");
        for (Iterator iter = info.getOptions().keySet().iterator(); iter.hasNext(); ) {
            String key = (String)iter.next();
            String val = info.getOptions().getProperty(key);
            buf.append(DataHelper.stripHTML(key)).append(" = ").append(DataHelper.stripHTML(val)).append("<br />\n");
        }
        buf.append("</code></i><hr />\n");
    }
    
}
