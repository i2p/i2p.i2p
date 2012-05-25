package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 *
 */
public class FloodfillNetworkDatabaseFacade extends KademliaNetworkDatabaseFacade {
    public static final char CAPACITY_FLOODFILL = 'f';
    private final Map _activeFloodQueries;
    private boolean _floodfillEnabled;
    /** for testing, see isFloodfill() below */
    private static String _alwaysQuery;
    
    public FloodfillNetworkDatabaseFacade(RouterContext context) {
        super(context);
        _activeFloodQueries = new HashMap();
         _floodfillEnabled = false;
        _alwaysQuery = _context.getProperty("netDb.alwaysQuery");

        _context.statManager().createRateStat("netDb.successTime", "How long a successful search takes", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.failedTime", "How long a failed search takes", "NetworkDatabase", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.failedAttemptedPeers", "How many peers we sent a search to when the search fails", "NetworkDatabase", new long[] { 60*1000l, 10*60*1000l });
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
    }

    @Override
    protected void createHandlers() {
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseLookupMessage.MESSAGE_TYPE, new FloodfillDatabaseLookupMessageHandler(_context));
        _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseStoreMessage.MESSAGE_TYPE, new FloodfillDatabaseStoreMessageHandler(_context, this));
    }
    
    private static final long PUBLISH_TIMEOUT = 30*1000;
    
    /**
     * @throws IllegalArgumentException if the local router info is invalid
     */
    @Override
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        if (localRouterInfo == null) throw new IllegalArgumentException("wtf, null localRouterInfo?");
        if (_context.router().isHidden()) return; // DE-nied!
        super.publish(localRouterInfo);
        sendStore(localRouterInfo.getIdentity().calculateHash(), localRouterInfo, null, null, PUBLISH_TIMEOUT, null);
    }
    
    @Override
    public void sendStore(Hash key, DataStructure ds, Job onSuccess, Job onFailure, long sendTimeout, Set toIgnore) {
        // if we are a part of the floodfill netDb, don't send out our own leaseSets as part 
        // of the flooding - instead, send them to a random floodfill peer so *they* can flood 'em out.
        // perhaps statistically adjust this so we are the source every 1/N times... or something.
        if (floodfillEnabled() && (ds instanceof RouterInfo)) {
            flood(ds);
            if (onSuccess != null) 
                _context.jobQueue().addJob(onSuccess);
        } else {
            _context.jobQueue().addJob(new FloodfillStoreJob(_context, this, key, ds, onSuccess, onFailure, sendTimeout, toIgnore));
        }
    }

    public void flood(DataStructure ds) {
        FloodfillPeerSelector sel = (FloodfillPeerSelector)getPeerSelector();
        List peers = sel.selectFloodfillParticipants(getKBuckets());
        int flooded = 0;
        for (int i = 0; i < peers.size(); i++) {
            Hash peer = (Hash)peers.get(i);
            RouterInfo target = lookupRouterInfoLocally(peer);
            if ( (target == null) || (_context.shitlist().isShitlisted(peer)) )
                continue;
            if (peer.equals(_context.routerHash()))
                continue;
            DatabaseStoreMessage msg = new DatabaseStoreMessage(_context);
            if (ds instanceof LeaseSet) {
                msg.setKey(((LeaseSet)ds).getDestination().calculateHash());
                msg.setLeaseSet((LeaseSet)ds);
            } else {
                msg.setKey(((RouterInfo)ds).getIdentity().calculateHash());
                msg.setRouterInfo((RouterInfo)ds);
            }
            msg.setReplyGateway(null);
            msg.setReplyToken(0);
            msg.setReplyTunnel(null);
            OutNetMessage m = new OutNetMessage(_context);
            m.setMessage(msg);
            m.setOnFailedReplyJob(null);
            m.setPriority(FLOOD_PRIORITY);
            m.setTarget(target);
            m.setExpiration(_context.clock().now()+FLOOD_TIMEOUT);
            _context.commSystem().processMessage(m);
            flooded++;
            if (_log.shouldLog(Log.INFO))
                _log.info("Flooding the entry for " + msg.getKey().toBase64() + " to " + peer.toBase64());
        }
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Flooded the to " + flooded + " peers");
    }

    private static final int FLOOD_PRIORITY = 200;
    private static final int FLOOD_TIMEOUT = 30*1000;
    
    @Override
    protected PeerSelector createPeerSelector() { return new FloodfillPeerSelector(_context); }
    
    public void setFloodfillEnabled(boolean yes) { _floodfillEnabled = yes; }
    public boolean floodfillEnabled() { return _floodfillEnabled; }
    public static boolean floodfillEnabled(RouterContext ctx) {
        return ((FloodfillNetworkDatabaseFacade)ctx.netDb()).floodfillEnabled();
    }
    
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
        if ( (caps != null) && (caps.indexOf(FloodfillNetworkDatabaseFacade.CAPACITY_FLOODFILL) != -1) )
            return true;
        else
            return false;
    }

    public List getKnownRouterData() {
        List rv = new ArrayList();
        DataStore ds = getDataStore();
        if (ds != null) {
            Set keys = ds.getKeys();
            if (keys != null) {
                for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
                    Object o = getDataStore().get((Hash)iter.next());
                    if (o instanceof RouterInfo)
                        rv.add(o);
                }
            }
        }
        return rv;
    }
    
    /**
     * Begin a kademlia style search for the key specified, which can take up to timeoutMs and
     * will fire the appropriate jobs on success or timeout (or if the kademlia search completes
     * without any match)
     *
     */
    @Override
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease) {
        //if (true) return super.search(key, onFindJob, onFailedLookupJob, timeoutMs, isLease);
        if (key == null) throw new IllegalArgumentException("searchin for nothin, eh?");
        boolean isNew = false;
        FloodSearchJob searchJob = null;
        synchronized (_activeFloodQueries) {
            searchJob = (FloodSearchJob)_activeFloodQueries.get(key);
            if (searchJob == null) {
                //if (SearchJob.onlyQueryFloodfillPeers(_context)) {
                    searchJob = new FloodOnlySearchJob(_context, this, key, onFindJob, onFailedLookupJob, (int)timeoutMs, isLease);
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
     */
    void searchFull(Hash key, List onFind, List onFailed, long timeoutMs, boolean isLease) {
        synchronized (_activeFloodQueries) { _activeFloodQueries.remove(key); }
        
        Job find = null;
        Job fail = null;
        if (onFind != null) {
            synchronized (onFind) {
                if (onFind.size() > 0)
                    find = (Job)onFind.remove(0);
            } 
        }
        if (onFailed != null) {
            synchronized (onFailed) {
                if (onFailed.size() > 0)
                    fail = (Job)onFailed.remove(0);
            }
        }
        SearchJob job = super.search(key, find, fail, timeoutMs, isLease);
        if (job != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Floodfill search timed out for " + key.toBase64() + ", falling back on normal search (#" 
                          + job.getJobId() + ") with " + timeoutMs + " remaining");
            long expiration = timeoutMs + _context.clock().now();
            List removed = null;
            if (onFind != null) {
                synchronized (onFind) {
                    removed = new ArrayList(onFind);
                    onFind.clear();
                }
                for (int i = 0; i < removed.size(); i++)
                    job.addDeferred((Job)removed.get(i), null, expiration, isLease);
                removed = null;
            }
            if (onFailed != null) {
                synchronized (onFailed) {
                    removed = new ArrayList(onFailed);
                    onFailed.clear();
                }
                for (int i = 0; i < removed.size(); i++)
                    job.addDeferred(null, (Job)removed.get(i), expiration, isLease);
                removed = null;
            }
        }
    }
    void complete(Hash key) {
        synchronized (_activeFloodQueries) { _activeFloodQueries.remove(key); }
    }
    
    /** list of the Hashes of currently known floodfill peers;
      * Returned list will not include our own hash.
      */
    public List getFloodfillPeers() {
        FloodfillPeerSelector sel = (FloodfillPeerSelector)getPeerSelector();
        return sel.selectFloodfillParticipants(getKBuckets());
    }
    
    /** NTCP cons drop quickly but SSU takes a while, so it's prudent to keep this
     *  a little higher than 1 or 2. */
    protected final static int MIN_ACTIVE_PEERS = 5;

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

        if (_context.jobQueue().getMaxLag() > 500) {
            // don't try to overload ourselves (e.g. failing 3000 router refs at
            // once, and then firing off 3000 netDb lookup tasks)
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
        private Hash _peer;
        private RouterInfo _info;
    
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
        private Hash _peer;
        private RouterInfo _info;
    
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

/**
 * Try sending a search to some floodfill peers, but if we don't get a successful
 * match within half the allowed lookup time, give up and start querying through
 * the normal (kademlia) channels.  This should cut down on spurious lookups caused
 * by simple delays in responses from floodfill peers
 *
 */
class FloodSearchJob extends JobImpl {
    private Log _log;
    private FloodfillNetworkDatabaseFacade _facade;
    private Hash _key;
    private final List _onFind;
    private final List _onFailed;
    private long _expiration;
    private int _timeoutMs;
    private long _origExpiration;
    private boolean _isLease;
    private volatile int _lookupsRemaining;
    private volatile boolean _dead;
    public FloodSearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key, Job onFind, Job onFailed, int timeoutMs, boolean isLease) {
        super(ctx);
        _log = ctx.logManager().getLog(FloodSearchJob.class);
        _facade = facade;
        _key = key;
        _onFind = new ArrayList();
        _onFind.add(onFind);
        _onFailed = new ArrayList();
        _onFailed.add(onFailed);
        int timeout = -1;
        timeout = timeoutMs / FLOOD_SEARCH_TIME_FACTOR;
        if (timeout < timeoutMs)
            timeout = timeoutMs;
        _timeoutMs = timeout;
        _expiration = timeout + ctx.clock().now();
        _origExpiration = timeoutMs + ctx.clock().now();
        _isLease = isLease;
        _lookupsRemaining = 0;
        _dead = false;
    }
    void addDeferred(Job onFind, Job onFailed, long timeoutMs, boolean isLease) {
        if (_dead) {
            getContext().jobQueue().addJob(onFailed);
        } else {
            if (onFind != null) synchronized (_onFind) { _onFind.add(onFind); }
            if (onFailed != null) synchronized (_onFailed) { _onFailed.add(onFailed); }
        }
    }
    public long getExpiration() { return _expiration; }
    private static final int CONCURRENT_SEARCHES = 2;
    private static final int FLOOD_SEARCH_TIME_FACTOR = 2;
    private static final int FLOOD_SEARCH_TIME_MIN = 30*1000;
    public void runJob() {
        // pick some floodfill peers and send out the searches
        List floodfillPeers = _facade.getFloodfillPeers();
        FloodLookupSelector replySelector = new FloodLookupSelector(getContext(), this);
        ReplyJob onReply = new FloodLookupMatchJob(getContext(), this);
        Job onTimeout = new FloodLookupTimeoutJob(getContext(), this);
        OutNetMessage out = getContext().messageRegistry().registerPending(replySelector, onReply, onTimeout, _timeoutMs);

        for (int i = 0; _lookupsRemaining < CONCURRENT_SEARCHES && i < floodfillPeers.size(); i++) {
            Hash peer = (Hash)floodfillPeers.get(i);
            if (peer.equals(getContext().routerHash()))
                continue;
            
            DatabaseLookupMessage dlm = new DatabaseLookupMessage(getContext(), true);
            TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundTunnel();
            TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundTunnel();
            if ( (replyTunnel == null) || (outTunnel == null) ) {
                _dead = true;
                List removed = null;
                synchronized (_onFailed) {
                    removed = new ArrayList(_onFailed);
                    _onFailed.clear();
                }
                while (removed.size() > 0)
                    getContext().jobQueue().addJob((Job)removed.remove(0));
                getContext().messageRegistry().unregisterPending(out);
                return;
            }
            dlm.setFrom(replyTunnel.getPeer(0));
            dlm.setMessageExpiration(getContext().clock().now()+10*1000);
            dlm.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
            dlm.setSearchKey(_key);
            
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " to " + peer.toBase64());
            getContext().tunnelDispatcher().dispatchOutbound(dlm, outTunnel.getSendTunnelId(0), peer);
            _lookupsRemaining++;
        }
        
        if (_lookupsRemaining <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " had no peers to send to");
            // no floodfill peers, go to the normal ones
            getContext().messageRegistry().unregisterPending(out);
            _facade.searchFull(_key, _onFind, _onFailed, _timeoutMs*FLOOD_SEARCH_TIME_FACTOR, _isLease);
        }
    }
    public String getName() { return "NetDb search (phase 1)"; }
    
    Hash getKey() { return _key; }
    void decrementRemaining() { _lookupsRemaining--; }
    int getLookupsRemaining() { return _lookupsRemaining; }
    
    void failed() {
        if (_dead) return;
        _dead = true;
        int timeRemaining = (int)(_origExpiration - getContext().clock().now());
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " failed with " + timeRemaining);
        if (timeRemaining > 0) {
            _facade.searchFull(_key, _onFind, _onFailed, timeRemaining, _isLease);
        } else {
            List removed = null;
            synchronized (_onFailed) {
                removed = new ArrayList(_onFailed);
                _onFailed.clear();
            }
            while (removed.size() > 0)
                getContext().jobQueue().addJob((Job)removed.remove(0));
        }
    }
    void success() {
        if (_dead) return;
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " successful");
        _dead = true;
        _facade.complete(_key);
        List removed = null;
        synchronized (_onFind) {
            removed = new ArrayList(_onFind);
            _onFind.clear();
        }
        while (removed.size() > 0)
            getContext().jobQueue().addJob((Job)removed.remove(0));
    }
}

class FloodLookupTimeoutJob extends JobImpl {
    private FloodSearchJob _search;
    public FloodLookupTimeoutJob(RouterContext ctx, FloodSearchJob job) {
        super(ctx);
        _search = job;
    }
    public void runJob() {
        _search.decrementRemaining();
        if (_search.getLookupsRemaining() <= 0)
            _search.failed(); 
    }
    public String getName() { return "NetDb search (phase 1) timeout"; }
}

class FloodLookupMatchJob extends JobImpl implements ReplyJob {
    private Log _log;
    private FloodSearchJob _search;
    public FloodLookupMatchJob(RouterContext ctx, FloodSearchJob job) {
        super(ctx);
        _log = ctx.logManager().getLog(FloodLookupMatchJob.class);
        _search = job;
    }
    public void runJob() { 
        if ( (getContext().netDb().lookupLeaseSetLocally(_search.getKey()) != null) ||
             (getContext().netDb().lookupRouterInfoLocally(_search.getKey()) != null) ) {
            _search.success();
        } else {
            int remaining = _search.getLookupsRemaining();
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + "/" + _search.getJobId() + ": got a reply looking for " 
                          + _search.getKey().toBase64() + ", with " + remaining + " outstanding searches");
            // netDb reply pointing us at other people
            if (remaining <= 0)
                _search.failed();
        }
    }
    public String getName() { return "NetDb search (phase 1) match"; }
    public void setMessage(I2NPMessage message) {}
}

class FloodLookupSelector implements MessageSelector {
    private RouterContext _context;
    private FloodSearchJob _search;
    public FloodLookupSelector(RouterContext ctx, FloodSearchJob search) {
        _context = ctx;
        _search = search;
    }
    public boolean continueMatching() { return _search.getLookupsRemaining() > 0; }
    public long getExpiration() { return _search.getExpiration(); }
    public boolean isMatch(I2NPMessage message) {
        if (message == null) return false;
        if (message instanceof DatabaseStoreMessage) {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
            // is it worth making sure the reply came in on the right tunnel?
            if (_search.getKey().equals(dsm.getKey())) {
                _search.decrementRemaining();
                return true;
            }
        } else if (message instanceof DatabaseSearchReplyMessage) {
            DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
            if (_search.getKey().equals(dsrm.getSearchKey())) {
                _search.decrementRemaining();
                return true;
            }
        }
        return false;
    }   
}
