package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.RouterInfo;
import net.i2p.data.RoutingKeyGenerator;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.ProfileManager;
import net.i2p.router.Router;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

/**
 * Search for a particular key iteratively until we either find a value or we 
 * run out of peers
 *
 */
class SearchJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    private SearchState _state;
    private Job _onSuccess;
    private Job _onFailure;
    private long _expiration;
    private long _timeoutMs;
    private boolean _keepStats;
    private boolean _isLease;
    private Job _pendingRequeueJob;
    private PeerSelector _peerSelector;
    
    public final static int SEARCH_BREDTH = 3; // 3 peers at a time 
    public final static int SEARCH_PRIORITY = 400; // large because the search is probably for a real search
    
    private static final long PER_PEER_TIMEOUT = 30*1000;
    
    /**
     * Create a new search for the routingKey specified
     * 
     */
    public SearchJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, Job onSuccess, Job onFailure, long timeoutMs, boolean keepStats, boolean isLease) {
        super(context);
        if ( (key == null) || (key.getData() == null) ) throw new IllegalArgumentException("Search for null key?  wtf");
        _log = _context.logManager().getLog(SearchJob.class);
        _facade = facade;
        _state = new SearchState(_context, key);
        _onSuccess = onSuccess;
        _onFailure = onFailure;
        _timeoutMs = timeoutMs;
        _keepStats = keepStats;
        _isLease = isLease;
        _peerSelector = new PeerSelector(_context);
        _expiration = _context.clock().now() + timeoutMs;
        _context.statManager().createRateStat("netDb.successTime", "How long a successful search takes", "Network Database", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.failedTime", "How long a failed search takes", "Network Database", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.successPeers", "How many peers are contacted in a successful search", "Network Database", new long[] { 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.failedPeers", "How many peers are contacted in a failed search", "Network Database", new long[] { 60*60*1000l, 24*60*60*1000l });
    }

    public void runJob() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Searching for " + _state.getTarget()); // , getAddedBy());
        searchNext();
    }

    protected SearchState getState() { return _state; }
    protected KademliaNetworkDatabaseFacade getFacade() { return _facade; }
    protected long getExpiration() { return _expiration; }
    protected long getTimeoutMs() { return _timeoutMs; }
    
    /**
     * Send the next search, or stop if its completed
     */
    protected void searchNext() {
        if (_state.completed()) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Already completed");
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Searching: " + _state);
        if (isLocal()) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Key found locally");
            _state.complete(true);
            succeed();
        } else if (isExpired()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Key search expired");
            _state.complete(true);
            fail();
        } else {
            //_log.debug("Continuing search");
            continueSearch();
        }
    }

    /**
     * True if the data is already locally stored
     *
     */
    private boolean isLocal() { return _facade.getDataStore().isKnown(_state.getTarget()); }

    private boolean isExpired() { 
        return _context.clock().now() >= _expiration;
    }

    /**
     * Send a series of searches to the next available peers as selected by
     * the routing table, but making sure no more than SEARCH_BREDTH are outstanding
     * at any time
     *
     */
    protected void continueSearch() { 
        if (_state.completed()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Search already completed", new Exception("already completed"));
            return;
        }
        int toCheck = SEARCH_BREDTH - _state.getPending().size();
        if (toCheck <= 0) {
            // too many already pending
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Too many searches already pending (pending: " 
                          + _state.getPending().size() + " max: " + SEARCH_BREDTH + ")", 
                          new Exception("too many pending"));
            requeuePending();
            return;
        } 
        List closestHashes = getClosestRouters(_state.getTarget(), toCheck, _state.getAttempted());
        if ( (closestHashes == null) || (closestHashes.size() <= 0) ) {
            if (_state.getPending().size() <= 0) {
                // we tried to find some peers, but there weren't any and no one else is going to answer
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": No peers left, and none pending!  Already searched: " 
                              + _state.getAttempted().size() + " failed: " + _state.getFailed().size(), 
                              new Exception("none left"));
                fail();
            } else {
                // no more to try, but we might get data or close peers from some outstanding requests
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": No peers left, but some are pending!  Pending: " 
                              + _state.getPending().size() + " attempted: " + _state.getAttempted().size() 
                              + " failed: " + _state.getFailed().size(), 
                              new Exception("none left, but pending"));
                requeuePending();
                return;
            }
        } else {
            _state.addPending(closestHashes);
            for (Iterator iter = closestHashes.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                DataStructure ds = _facade.getDataStore().get(peer);
                if ( (ds == null) || !(ds instanceof RouterInfo) ) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getJobId() + ": Error selecting closest hash that wasnt a router! " 
                                  + peer + " : " + ds);
                } else {
                    sendSearch((RouterInfo)ds);
                }
            }
        }
    }
    
    private void requeuePending() {
        if (_pendingRequeueJob == null)
            _pendingRequeueJob = new RequeuePending();
        long now = _context.clock().now();
        if (_pendingRequeueJob.getTiming().getStartAfter() < now)
            _pendingRequeueJob.getTiming().setStartAfter(now+5*1000);
        _context.jobQueue().addJob(_pendingRequeueJob);
    }

    private class RequeuePending extends JobImpl {
        public RequeuePending() {
            super(SearchJob.this._context);
        }
        public String getName() { return "Requeue search with pending"; }
        public void runJob() { searchNext(); }
    }
    
    /**
     * Set of Hash structures for routers we want to check next.  This is the 'interesting' part of
     * the algorithm.  But to keep you on your toes, we've refactored it to the PeerSelector.selectNearestExplicit  
     *
     * @return ordered list of Hash objects
     */
    private List getClosestRouters(Hash key, int numClosest, Set alreadyChecked) {
        Hash rkey = _context.routingKeyGenerator().getRoutingKey(key);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Current routing key for " + key + ": " + rkey);
        return _peerSelector.selectNearestExplicit(rkey, numClosest, alreadyChecked, _facade.getKBuckets());
    }
    
    /**
     * Send a search to the given peer
     *
     */
    protected void sendSearch(RouterInfo router) {
        if (router.getIdentity().equals(_context.router().getRouterInfo().getIdentity())) {
            // don't search ourselves
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Dont send search to ourselves - why did we try?");
            return;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Send search to " + router);
        }

        if (_isLease || false) // moo
            sendLeaseSearch(router);
        else
            sendRouterSearch(router);
    }
    
    
    /** 
     * we're (probably) searching for a LeaseSet, so to be (overly) cautious, we're sending 
     * the request out through a tunnel w/ reply back through another tunnel.
     *
     */
    protected void sendLeaseSearch(RouterInfo router) {
        TunnelId inTunnelId = getInboundTunnelId(); 
        if (inTunnelId == null) {
            _log.error("No tunnels to get search replies through!  wtf!");
            _context.jobQueue().addJob(new FailedJob(router));
            return;
        }
	
        TunnelInfo inTunnel = _context.tunnelManager().getTunnelInfo(inTunnelId);
        RouterInfo inGateway = _context.netDb().lookupRouterInfoLocally(inTunnel.getThisHop());
        if (inGateway == null) {
            _log.error("We can't find the gateway to our inbound tunnel?! wtf");
            _context.jobQueue().addJob(new FailedJob(router));
            return;
        }
	
        long expiration = _context.clock().now() + PER_PEER_TIMEOUT; // getTimeoutMs();

        DatabaseLookupMessage msg = buildMessage(inTunnelId, inGateway, expiration);	
	
        TunnelId outTunnelId = getOutboundTunnelId();
        if (outTunnelId == null) {
            _log.error("No tunnels to send search out through!  wtf!");
            _context.jobQueue().addJob(new FailedJob(router));
            return;
        }
	
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Sending leaseSet search to " + router.getIdentity().getHash().toBase64() 
                       + " for " + msg.getSearchKey().toBase64() + " w/ replies through [" 
                       + msg.getFrom().getIdentity().getHash().toBase64() + "] via tunnel [" 
                       + msg.getReplyTunnel() + "]");

        SearchMessageSelector sel = new SearchMessageSelector(_context, router, _expiration, _state);
        long timeoutMs = PER_PEER_TIMEOUT; // getTimeoutMs();
        SearchUpdateReplyFoundJob reply = new SearchUpdateReplyFoundJob(_context, router, _state, _facade, this);
        SendTunnelMessageJob j = new SendTunnelMessageJob(_context, msg, outTunnelId, router.getIdentity().getHash(), 
                                                          null, null, reply, new FailedJob(router), sel, 
                                                          timeoutMs, SEARCH_PRIORITY);
        _context.jobQueue().addJob(j);
    }
    
    /** we're searching for a router, so we can just send direct */
    protected void sendRouterSearch(RouterInfo router) {
        long expiration = _context.clock().now() + PER_PEER_TIMEOUT; // getTimeoutMs();

        DatabaseLookupMessage msg = buildMessage(expiration);

        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Sending router search to " + router.getIdentity().getHash().toBase64() 
                      + " for " + msg.getSearchKey().toBase64() + " w/ replies to us [" 
                      + msg.getFrom().getIdentity().getHash().toBase64() + "]");
        SearchMessageSelector sel = new SearchMessageSelector(_context, router, _expiration, _state);
        long timeoutMs = PER_PEER_TIMEOUT; 
        SearchUpdateReplyFoundJob reply = new SearchUpdateReplyFoundJob(_context, router, _state, _facade, this);
        SendMessageDirectJob j = new SendMessageDirectJob(_context, msg, router.getIdentity().getHash(), 
                                                          reply, new FailedJob(router), sel, expiration, SEARCH_PRIORITY);
        _context.jobQueue().addJob(j);
    }
    
    /** 
     * what tunnel will we send the search out through? 
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelId getOutboundTunnelId() {
        TunnelSelectionCriteria crit = new TunnelSelectionCriteria();
        crit.setMaximumTunnelsRequired(1);
        crit.setMinimumTunnelsRequired(1);
        List tunnelIds = _context.tunnelManager().selectOutboundTunnelIds(crit);
        if (tunnelIds.size() <= 0) {
            return null;
        }
	
        return (TunnelId)tunnelIds.get(0);
    }
    
    /**
     * what tunnel will we get replies through?
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelId getInboundTunnelId() {
        TunnelSelectionCriteria crit = new TunnelSelectionCriteria();
        crit.setMaximumTunnelsRequired(1);
        crit.setMinimumTunnelsRequired(1);
        List tunnelIds = _context.tunnelManager().selectInboundTunnelIds(crit);
        if (tunnelIds.size() <= 0) {
            return null;
        }
        return (TunnelId)tunnelIds.get(0);
    }

    /**
     * Build the database search message 
     *
     * @param replyTunnelId tunnel to receive replies through
     * @param replyGateway gateway for the reply tunnel
     * @param expiration when the search should stop 
     */
    protected DatabaseLookupMessage buildMessage(TunnelId replyTunnelId, RouterInfo replyGateway, long expiration) {
        DatabaseLookupMessage msg = new DatabaseLookupMessage(_context);
        msg.setSearchKey(_state.getTarget());
        msg.setFrom(replyGateway);
        msg.setDontIncludePeers(_state.getAttempted());
        msg.setMessageExpiration(new Date(expiration));
        msg.setReplyTunnel(replyTunnelId);
        return msg;
    }
    
    /**
     * We're looking for a router, so lets build the lookup message (no need to tunnel route either, so just have
     * replies sent back to us directly)
     *
     */
    protected DatabaseLookupMessage buildMessage(long expiration) {
        DatabaseLookupMessage msg = new DatabaseLookupMessage(_context);
        msg.setSearchKey(_state.getTarget());
        msg.setFrom(_context.router().getRouterInfo());
        msg.setDontIncludePeers(_state.getAttempted());
        msg.setMessageExpiration(new Date(expiration));
        msg.setReplyTunnel(null);
        return msg;
    }
    
    void replyFound(DatabaseSearchReplyMessage message, Hash peer) {
        long duration = _state.replyFound(peer);
        // this processing can take a while, so split 'er up
        _context.jobQueue().addJob(new SearchReplyJob((DatabaseSearchReplyMessage)message, peer, duration));
    }
    
    private final class SearchReplyJob extends JobImpl {
        private DatabaseSearchReplyMessage _msg;
        private Hash _peer;
        private int _curIndex;
        private int _invalidPeers;
        private int _seenPeers;
        private int _newPeers;
        private int _duplicatePeers;
        private long _duration;
        public SearchReplyJob(DatabaseSearchReplyMessage message, Hash peer, long duration) {
            super(SearchJob.this._context);
            _msg = message;
            _peer = peer;
            _curIndex = 0;
            _invalidPeers = 0;
            _seenPeers = 0;
            _newPeers = 0;
            _duplicatePeers = 0;
        }
        public String getName() { return "Process Reply for Kademlia Search"; }
        public void runJob() {
            if (_curIndex >= _msg.getNumReplies()) {
                _context.profileManager().dbLookupReply(_peer, _newPeers, _seenPeers, 
                                                        _invalidPeers, _duplicatePeers, _duration);
            } else {
                RouterInfo ri = _msg.getReply(_curIndex);
                if (ri.isValid()) {
                    if (_state.wasAttempted(ri.getIdentity().getHash())) {
                        _duplicatePeers++;
                    } 
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": dbSearchReply received on search containing router " 
                                  + ri.getIdentity().getHash() + " with publishDate of " 
                                  + new Date(ri.getPublished()));
                    _facade.store(ri.getIdentity().getHash(), ri);
                    if (_facade.getKBuckets().add(ri.getIdentity().getHash())) 
                        _newPeers++;
                    else
                        _seenPeers++;
                } else {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error(getJobId() + ": Received an invalid peer from " + _peer + ": " 
                                   + ri, new Exception("Invalid peer"));
                    _invalidPeers++;
                }
                _curIndex++;
                requeue(0);
            }
        }
    }

    /**
     * Called when a particular peer failed to respond before the timeout was 
     * reached, or if the peer could not be contacted at all.
     *
     */
    protected class FailedJob extends JobImpl {
        private Hash _peer;
        private boolean _penalizePeer;
        public FailedJob(RouterInfo peer) {
            this(peer, true);
        }
        /**
         * Allow the choice as to whether failed searches should count against
         * the peer (such as if we search for a random key)
         *
         */
        public FailedJob(RouterInfo peer, boolean penalizePeer) {
            super(SearchJob.this._context);
            _penalizePeer = penalizePeer;
            _peer = peer.getIdentity().getHash();
        }
        public void runJob() {
            _state.replyTimeout(_peer);
            if (_penalizePeer) { 
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Penalizing peer for timeout on search: " + _peer.toBase64());
                _context.profileManager().dbLookupFailed(_peer);
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("NOT (!!) Penalizing peer for timeout on search: " + _peer.toBase64());
            }
            searchNext();
        }
        public String getName() { return "Kademlia Search Failed"; }
    }
    
    /**
     * Search was totally successful
     */
    protected void succeed() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Succeeded search for key " + _state.getTarget());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of successful search: " + _state);
	
        if (_keepStats) {
            long time = _context.clock().now() - _state.getWhenStarted();
            _context.statManager().addRateData("netDb.successTime", time, 0);
            _context.statManager().addRateData("netDb.successPeers", _state.getAttempted().size(), time);
        }
        if (_onSuccess != null)
            _context.jobQueue().addJob(_onSuccess);
    }

    /**
     * Search totally failed
     */
    protected void fail() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Failed search for key " + _state.getTarget());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of failed search: " + _state);
            
        if (_keepStats) {
            long time = _context.clock().now() - _state.getWhenStarted();
            _context.statManager().addRateData("netDb.failedTime", time, 0);
            _context.statManager().addRateData("netDb.failedPeers", _state.getAttempted().size(), time);
        }
        if (_onFailure != null)
            _context.jobQueue().addJob(_onFailure);
    }

    public String getName() { return "Kademlia NetDb Search"; }
}
