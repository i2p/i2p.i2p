package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.router.InNetMessagePool;
import net.i2p.router.Job;
import net.i2p.router.JobQueue;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.networkdb.DatabaseLookupMessageHandler;
import net.i2p.router.networkdb.DatabaseSearchReplyMessageHandler;
import net.i2p.router.networkdb.DatabaseStoreMessageHandler;
import net.i2p.router.networkdb.PublishLocalRouterInfoJob;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Kademlia based version of the network database
 *
 */
public class KademliaNetworkDatabaseFacade extends NetworkDatabaseFacade {
    private final static Log _log = new Log(KademliaNetworkDatabaseFacade.class);
    private KBucketSet _kb; // peer hashes sorted into kbuckets, but within kbuckets, unsorted
    private DataStore _ds; // hash to DataStructure mapping, persisted when necessary
    private Set _explicitSendKeys; // set of Hash objects that should be published ASAP
    private Set _passiveSendKeys; // set of Hash objects that should be published when there's time
    private Set _exploreKeys; // set of Hash objects that we should search on (to fill up a bucket, not to get data)
    private Map _lastSent; // Hash to Long (date last sent, or <= 0 for never)
    private boolean _initialized;
    /** Clock independent time of when we started up */
    private static long _started;
    
    /** 
     * for the 10 minutes after startup, don't fail db entries so that if we were
     * offline for a while, we'll have a chance of finding some live peers with the
     * previous references
     */
    private final static long DONT_FAIL_PERIOD = 10*60*1000;
    
    /** don't probe or broadcast data, just respond and search when explicitly needed */
    private static boolean _quiet = false;

    public final static String PROP_DB_DIR = "router.networkDatabase.dbDir";
    public final static String DEFAULT_DB_DIR = "netDb";
    
    /** if we have less than 5 routers left, don't drop any more, even if they're failing or doing bad shit */
    private final static int MIN_REMAINING_ROUTERS = 5;
    
    public KademliaNetworkDatabaseFacade() {
	_initialized = false;
    }
    
    KBucketSet getKBuckets() { return _kb; }
    DataStore getDataStore() { return _ds; } 
    
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
	    _lastSent.put(key, new Long(Clock.getInstance().now()));
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
    public void startup() {
	_log.info("Starting up the kademlia network database");
	RouterInfo ri = Router.getInstance().getRouterInfo();
	String dbDir = Router.getInstance().getConfigSetting(PROP_DB_DIR);
	if (dbDir == null) {
	    _log.info("No DB dir specified [" + PROP_DB_DIR + "], using [" + DEFAULT_DB_DIR + "]");
	    dbDir = DEFAULT_DB_DIR;
	}
	
	_kb = new KBucketSet(ri.getIdentity().getHash());
	_ds = new PersistentDataStore(dbDir, this);
	//_ds = new TransientDataStore();
	_explicitSendKeys = new HashSet(64);
	_passiveSendKeys = new HashSet(64);
	_exploreKeys = new HashSet(64);
	_lastSent = new HashMap(1024);
	
	InNetMessagePool.getInstance().registerHandlerJobBuilder(DatabaseLookupMessage.MESSAGE_TYPE, new DatabaseLookupMessageHandler());
	InNetMessagePool.getInstance().registerHandlerJobBuilder(DatabaseStoreMessage.MESSAGE_TYPE, new DatabaseStoreMessageHandler());
	InNetMessagePool.getInstance().registerHandlerJobBuilder(DatabaseSearchReplyMessage.MESSAGE_TYPE, new DatabaseSearchReplyMessageHandler());
	
	_initialized = true;
	_started = System.currentTimeMillis();
	
	// read the queues and publish appropriately
	JobQueue.getInstance().addJob(new DataPublisherJob(this));
	// expire old leases
	JobQueue.getInstance().addJob(new ExpireLeasesJob(this));
	// expire some routers in overly full kbuckets
	JobQueue.getInstance().addJob(new ExpireRoutersJob(this));
	if (!_quiet) {
	    // fill the passive queue periodically
	    JobQueue.getInstance().addJob(new DataRepublishingSelectorJob(this));
	    // fill the search queue with random keys in buckets that are too small
	    JobQueue.getInstance().addJob(new ExploreKeySelectorJob(this));
	    // fire off a group of searches from the explore pool
	    JobQueue.getInstance().addJob(new StartExplorersJob(this));
	} else {
	    _log.warn("Operating in quiet mode - not exploring or pushing data proactively, simply reactively");
	    _log.warn("This should NOT be used in production");
	}
	// periodically update and resign the router's 'published date', which basically
	// serves as a version
	JobQueue.getInstance().addJob(new PublishLocalRouterInfoJob());
	publish(ri);
    }
    
    /**
     * Get the routers closest to that key in response to a remote lookup
     */
    public Set findNearestRouters(Hash key, int maxNumRouters, Set peersToIgnore) {
	if (!_initialized) return null;
	return getRouters(PeerSelector.getInstance().selectNearest(key, maxNumRouters, peersToIgnore, _kb));
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
		if (_log.shouldLog(Log.WARN))
		    _log.warn("Selected router hash " + rhash.toBase64() + " is NOT a routerInfo!");
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
		if (_log.shouldLog(Log.WARN))
		    _log.warn("Selected router hash [" + key.toBase64() + "] is NOT a routerInfo!");
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
		JobQueue.getInstance().addJob(onFindJob);
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
		JobQueue.getInstance().addJob(onFindJob);
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
	if (!RepublishLeaseSetJob.alreadyRepublishing(h))
	    JobQueue.getInstance().addJob(new RepublishLeaseSetJob(this, h));
    }
    
    public void publish(RouterInfo localRouterInfo) {
	if (!_initialized) return;
	Hash h = localRouterInfo.getIdentity().getHash();
	store(h, localRouterInfo);
	synchronized (_explicitSendKeys) {
	    _explicitSendKeys.add(h);
	}
    }
    
    public LeaseSet store(Hash key, LeaseSet leaseSet) {
	long start = Clock.getInstance().now();
	if (!_initialized) return null;
	if (!key.equals(leaseSet.getDestination().calculateHash())) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Invalid store attempt! key does not match leaseSet.destination!  key = " + key + ", leaseSet = " + leaseSet);
	    return null;
	} else if (!leaseSet.verifySignature()) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Invalid leaseSet signature!  leaseSet = " + leaseSet);
	    return null;
	} else if (leaseSet.getEarliestLeaseDate() <= Clock.getInstance().now()) {
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Old leaseSet!  not storing it: " + leaseSet);
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
	// uberthreadsafe fascists here, since these values are just heuristics
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
	
	long end = Clock.getInstance().now();
	_log.debug("Store leaseSet took [" + (end-start) + "ms]");
	return rv;
    }
    
    public RouterInfo store(Hash key, RouterInfo routerInfo) {
	long start = Clock.getInstance().now();
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
		_log.info("Not storing expired router for " + key.toBase64());
		return null;
	    } else {
		_log.warn("Even though the peer is old, we have only " + existing + " peers left (curPeer: " + key.toBase64() + " published on " + new Date(routerInfo.getPublished())); 
	    }
	}
	
	RouterInfo rv = null;
	if (_ds.isKnown(key))
	    rv = (RouterInfo)_ds.get(key);

	if (_log.shouldLog(Log.INFO))
	    _log.info("RouterInfo " + key.toBase64() + " is stored with " + routerInfo.getOptions().size() + " options on " + new Date(routerInfo.getPublished()));
	
	_ds.put(key, routerInfo);
	synchronized (_lastSent) {
	    if (!_lastSent.containsKey(key))
		_lastSent.put(key, new Long(0));
	}
	_kb.add(key);
	long end = Clock.getInstance().now();
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
		_log.warn("Not removing " + dbEntry + " because we have so few routers left (" + remaining + ") - perhaps a reseed is necessary?");
		return;
	    }
	    if (System.currentTimeMillis() < _started + DONT_FAIL_PERIOD) { 
		_log.warn("Not failing the key " + dbEntry.toBase64() + " since we've just started up and don't want to drop /everyone/");
		return;
	    }
	
	    boolean removed = _kb.remove(dbEntry);
	    if (removed) {
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
	    _log.warn("Unpublished a lease we don't know...: " + localLeaseSet);
	} else {
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
	// all searching is indirect (through tunnels) now
	JobQueue.getInstance().addJob(new SearchJob(this, key, onFindJob, onFailedLookupJob, timeoutMs, true, isLease));
	
	//if (isLease)
	//    JobQueue.getInstance().addJob(new SearchLeaseSetJob(this, key, onFindJob, onFailedLookupJob, timeoutMs));
	//else
	//    JobQueue.getInstance().addJob(new SearchJob(this, key, onFindJob, onFailedLookupJob, timeoutMs));
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
    
    public String renderStatusHTML() {
	StringBuffer buf = new StringBuffer();
	buf.append("<h2>Kademlia Network DB Contents</h2>\n");
	if (!_initialized) {
	    buf.append("<i>Not initialized</i>\n");
	    return buf.toString();
	}
	Set leases = getLeases();
	buf.append("<h3>Leases</h3>\n");
	buf.append("<table border=\"1\">\n");
	for (Iterator iter = leases.iterator(); iter.hasNext(); ) {
	    LeaseSet ls = (LeaseSet)iter.next();
	    Hash key = ls.getDestination().calculateHash();
	    buf.append("<tr><td valign=\"top\" align=\"left\"><b>").append(key.toBase64()).append("</b></td>");

	    if (getLastSent(key).longValue() > 0)
		buf.append("<td valign=\"top\" align=\"left\"><b>Last sent successfully:</b> ").append(new Date(getLastSent(key).longValue())).append("</td></tr>");
	    else
		buf.append("<td valign=\"top\" align=\"left\"><b>Last sent successfully:</b> never</td></tr>");
	    buf.append("<tr><td valign=\"top\" align=\"left\" colspan=\"2\"><pre>\n").append(ls.toString()).append("</pre></td></tr>\n");
	}
	buf.append("</table>\n");
	
	Hash us = Router.getInstance().getRouterInfo().getIdentity().getHash();
	Set routers = getRouters();
	buf.append("<h3>Routers</h3>\n");
	buf.append("<table border=\"1\">\n");
	for (Iterator iter = routers.iterator(); iter.hasNext(); ) {
	    RouterInfo ri = (RouterInfo)iter.next();
	    Hash key = ri.getIdentity().getHash();
	    boolean isUs = key.equals(us);
	    if (isUs) {
		buf.append("<tr><td valign=\"top\" align=\"left\"><font color=\"red\"><b>").append(key.toBase64()).append("</b></font></td>");
		buf.append("<td valign=\"top\" align=\"left\" colspan=\"2\"><b>Last sent successfully:</b> ").append(new Date(getLastSent(key).longValue())).append("</td></tr>");
	    } else {
		buf.append("<tr><td valign=\"top\" align=\"left\"><a name=\"").append(key.toBase64().substring(0,32)).append("\"><b>").append(key.toBase64()).append("</b></a></td>");
		if (getLastSent(key).longValue() > 0)
		    buf.append("<td valign=\"top\" align=\"left\"><b>Last sent successfully:</b> ").append(new Date(getLastSent(key).longValue())).append("</td>");
		else
		    buf.append("<td valign=\"top\" align=\"left\"><b>Last sent successfully:</b> never</td>");
		buf.append("<td valign=\"top\" align=\"left\"><a href=\"/profile/").append(key.toBase64().substring(0, 32)).append("\">Profile</a></td></tr>");
	    }
	    buf.append("<tr><td valign=\"top\" align=\"left\" colspan=\"3\"><pre>\n").append(ri.toString()).append("</pre></td></tr>\n");
	}
	buf.append("</table>\n");
	
	return buf.toString();
    }
    
}
