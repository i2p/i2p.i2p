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
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Log;

/**
 * Search for a particular key iteratively until we either find a value or we 
 * run out of peers
 *
 */
class SearchJob extends JobImpl {
    private Log _log;
    protected KademliaNetworkDatabaseFacade _facade;
    private SearchState _state;
    private Job _onSuccess;
    private Job _onFailure;
    private long _expiration;
    private long _timeoutMs;
    private boolean _keepStats;
    private boolean _isLease;
    private Job _pendingRequeueJob;
    private PeerSelector _peerSelector;
    
    private static final int SEARCH_BREDTH = 3; // 3 peers at a time 
    private static final int SEARCH_PRIORITY = 400; // large because the search is probably for a real search
    
    /**
     * How long will we give each peer to reply to our search? 
     *
     */
    private static final int PER_PEER_TIMEOUT = 10*1000;
    
    /** 
     * give ourselves 30 seconds to send out the value found to the closest 
     * peers /after/ we get a successful match.  If this fails, no biggie, but
     * this'll help heal the network so subsequent searches will find the data.
     *
     */
    private static final long RESEND_TIMEOUT = 30*1000;
    
    /**
     * Create a new search for the routingKey specified
     * 
     */
    public SearchJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, Job onSuccess, Job onFailure, long timeoutMs, boolean keepStats, boolean isLease) {
        super(context);
        if ( (key == null) || (key.getData() == null) ) 
            throw new IllegalArgumentException("Search for null key?  wtf");
        _log = getContext().logManager().getLog(SearchJob.class);
        _facade = facade;
        _state = new SearchState(getContext(), key);
        _onSuccess = onSuccess;
        _onFailure = onFailure;
        _timeoutMs = timeoutMs;
        _keepStats = keepStats;
        _isLease = isLease;
        _peerSelector = new PeerSelector(getContext());
        _expiration = getContext().clock().now() + timeoutMs;
        getContext().statManager().createRateStat("netDb.successTime", "How long a successful search takes", "Network Database", new long[] { 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.failedTime", "How long a failed search takes", "Network Database", new long[] { 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.successPeers", "How many peers are contacted in a successful search", "Network Database", new long[] { 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.failedPeers", "How many peers fail to respond to a lookup?", "Network Database", new long[] { 60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.searchCount", "Overall number of searches sent", "Network Database", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        getContext().statManager().createRateStat("netDb.searchMessageCount", "Overall number of mesages for all searches sent", "Network Database", new long[] { 5*60*1000l, 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Search (" + getClass().getName() + " for " + key.toBase64(), new Exception("Search enqueued by"));
    }

    public void runJob() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Searching for " + _state.getTarget()); // , getAddedBy());
        getContext().statManager().addRateData("netDb.searchCount", 1, 0);
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
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Already completed");
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
        return getContext().clock().now() >= _expiration;
    }

    /** max # of concurrent searches */
    protected int getBredth() { return SEARCH_BREDTH; }
    
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
        int toCheck = getBredth() - _state.getPending().size();
        if (toCheck <= 0) {
            // too many already pending
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Too many searches already pending (pending: " 
                          + _state.getPending().size() + " max: " + getBredth() + ")");
            requeuePending();
            return;
        } 
        List closestHashes = getClosestRouters(_state.getTarget(), toCheck, _state.getAttempted());
        if ( (closestHashes == null) || (closestHashes.size() <= 0) ) {
            if (_state.getPending().size() <= 0) {
                // we tried to find some peers, but there weren't any and no one else is going to answer
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": No peers left, and none pending!  Already searched: " 
                              + _state.getAttempted().size() + " failed: " + _state.getFailed().size());
                fail();
            } else {
                // no more to try, but we might get data or close peers from some outstanding requests
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": No peers left, but some are pending!  Pending: " 
                              + _state.getPending().size() + " attempted: " + _state.getAttempted().size() 
                              + " failed: " + _state.getFailed().size());
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
                                  + peer + " : " + (ds == null ? "null" : ds.getClass().getName()));
                } else {
                    sendSearch((RouterInfo)ds);
                }
            }
        }
    }
    
    private void requeuePending() {
        if (_pendingRequeueJob == null)
            _pendingRequeueJob = new RequeuePending();
        long now = getContext().clock().now();
        if (_pendingRequeueJob.getTiming().getStartAfter() < now)
            _pendingRequeueJob.getTiming().setStartAfter(now+5*1000);
        getContext().jobQueue().addJob(_pendingRequeueJob);
    }

    private class RequeuePending extends JobImpl {
        public RequeuePending() {
            super(SearchJob.this.getContext());
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
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(key);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Current routing key for " + key + ": " + rkey);
        return _peerSelector.selectNearestExplicit(rkey, numClosest, alreadyChecked, _facade.getKBuckets());
    }
    
    /**
     * Send a search to the given peer
     *
     */
    protected void sendSearch(RouterInfo router) {
        if (router.getIdentity().equals(getContext().router().getRouterInfo().getIdentity())) {
            // don't search ourselves
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Dont send search to ourselves - why did we try?");
            return;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Send search to " + router.getIdentity().getHash().toBase64());
        }

        getContext().statManager().addRateData("netDb.searchMessageCount", 1, 0);

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
            getContext().jobQueue().addJob(new FailedJob(router));
            return;
        }
	
        TunnelInfo inTunnel = getContext().tunnelManager().getTunnelInfo(inTunnelId);
        RouterInfo inGateway = getContext().netDb().lookupRouterInfoLocally(inTunnel.getThisHop());
        if (inGateway == null) {
            _log.error("We can't find the gateway to our inbound tunnel?! wtf");
            getContext().jobQueue().addJob(new FailedJob(router));
            return;
        }
	
        long expiration = getContext().clock().now() + PER_PEER_TIMEOUT; // getTimeoutMs();

        DatabaseLookupMessage msg = buildMessage(inTunnelId, inGateway, expiration);	
	
        TunnelId outTunnelId = getOutboundTunnelId();
        if (outTunnelId == null) {
            _log.error("No tunnels to send search out through!  wtf!");
            getContext().jobQueue().addJob(new FailedJob(router));
            return;
        }
	
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Sending leaseSet search to " + router.getIdentity().getHash().toBase64() 
                       + " for " + msg.getSearchKey().toBase64() + " w/ replies through [" 
                       + msg.getFrom().toBase64() + "] via tunnel [" 
                       + msg.getReplyTunnel() + "]");

        SearchMessageSelector sel = new SearchMessageSelector(getContext(), router, _expiration, _state);
        SearchUpdateReplyFoundJob reply = new SearchUpdateReplyFoundJob(getContext(), router, _state, _facade, this);
        SendTunnelMessageJob j = new SendTunnelMessageJob(getContext(), msg, outTunnelId, router.getIdentity().getHash(), 
                                                          null, null, reply, new FailedJob(router), sel, 
                                                          PER_PEER_TIMEOUT, SEARCH_PRIORITY);
        getContext().jobQueue().addJob(j);
    }
    
    /** we're searching for a router, so we can just send direct */
    protected void sendRouterSearch(RouterInfo router) {
        long expiration = getContext().clock().now() + PER_PEER_TIMEOUT; // getTimeoutMs();

        DatabaseLookupMessage msg = buildMessage(expiration);

        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Sending router search to " + router.getIdentity().getHash().toBase64() 
                      + " for " + msg.getSearchKey().toBase64() + " w/ replies to us [" 
                      + msg.getFrom().toBase64() + "]");
        SearchMessageSelector sel = new SearchMessageSelector(getContext(), router, _expiration, _state);
        SearchUpdateReplyFoundJob reply = new SearchUpdateReplyFoundJob(getContext(), router, _state, _facade, this);
        SendMessageDirectJob j = new SendMessageDirectJob(getContext(), msg, router.getIdentity().getHash(), 
                                                          reply, new FailedJob(router), sel, PER_PEER_TIMEOUT, SEARCH_PRIORITY);
        getContext().jobQueue().addJob(j);
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
        List tunnelIds = getContext().tunnelManager().selectOutboundTunnelIds(crit);
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
        List tunnelIds = getContext().tunnelManager().selectInboundTunnelIds(crit);
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
        DatabaseLookupMessage msg = new DatabaseLookupMessage(getContext());
        msg.setSearchKey(_state.getTarget());
        msg.setFrom(replyGateway.getIdentity().getHash());
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
        DatabaseLookupMessage msg = new DatabaseLookupMessage(getContext());
        msg.setSearchKey(_state.getTarget());
        msg.setFrom(getContext().routerHash());
        msg.setDontIncludePeers(_state.getAttempted());
        msg.setMessageExpiration(new Date(expiration));
        msg.setReplyTunnel(null);
        return msg;
    }
    
    void replyFound(DatabaseSearchReplyMessage message, Hash peer) {
        long duration = _state.replyFound(peer);
        // this processing can take a while, so split 'er up
        getContext().jobQueue().addJob(new SearchReplyJob((DatabaseSearchReplyMessage)message, peer, duration));
    }
    
    /**
     * We've gotten a search reply that contained the specified
     * number of peers that we didn't know about before.
     *
     */
    protected void newPeersFound(int numNewPeers) {
        // noop
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
            super(SearchJob.this.getContext());
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
                getContext().profileManager().dbLookupReply(_peer, _newPeers, _seenPeers, 
                                                        _invalidPeers, _duplicatePeers, _duration);
                if (_newPeers > 0)
                    newPeersFound(_newPeers);
            } else {
                Hash peer = _msg.getReply(_curIndex);
                
                RouterInfo info = getContext().netDb().lookupRouterInfoLocally(peer);
                if (info == null) {
                    // hmm, perhaps don't always send a lookup for this...
                    // but for now, wtf, why not.  we may even want to adjust it so that 
                    // we penalize or benefit peers who send us that which we can or
                    // cannot lookup
                    getContext().netDb().lookupRouterInfo(peer, null, null, _timeoutMs);
                }
            
                if (_state.wasAttempted(peer)) {
                    _duplicatePeers++;
                } 
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getJobId() + ": dbSearchReply received on search referencing router " 
                              + peer);
                if (_facade.getKBuckets().add(peer))
                    _newPeers++;
                else
                    _seenPeers++;
                
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
            super(SearchJob.this.getContext());
            _penalizePeer = penalizePeer;
            _peer = peer.getIdentity().getHash();
        }
        public void runJob() {
            _state.replyTimeout(_peer);
            if (_penalizePeer) { 
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Penalizing peer for timeout on search: " + _peer.toBase64());
                getContext().profileManager().dbLookupFailed(_peer);
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("NOT (!!) Penalizing peer for timeout on search: " + _peer.toBase64());
            }
            getContext().statManager().addRateData("netDb.failedPeers", 1, 0);
            searchNext();
        }
        public String getName() { return "Kademlia Search Failed"; }
    }
    
    /**
     * Search was totally successful
     */
    private void succeed() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Succeeded search for key " + _state.getTarget() 
                      + " after querying " + _state.getAttempted().size());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of successful search: " + _state);
	
        if (_keepStats) {
            long time = getContext().clock().now() - _state.getWhenStarted();
            getContext().statManager().addRateData("netDb.successTime", time, 0);
            getContext().statManager().addRateData("netDb.successPeers", _state.getAttempted().size(), time);
        }
        if (_onSuccess != null)
            getContext().jobQueue().addJob(_onSuccess);
        
        resend();
    }
    
    /**
     * After we get the data we were searching for, rebroadcast it to the peers
     * we would query first if we were to search for it again (healing the network).
     *
     */
    private void resend() {
        DataStructure ds = _facade.lookupLeaseSetLocally(_state.getTarget());
        if (ds == null)
            ds = _facade.lookupRouterInfoLocally(_state.getTarget());
        if (ds != null)
            getContext().jobQueue().addJob(new StoreJob(getContext(), _facade, _state.getTarget(), 
                                                    ds, null, null, RESEND_TIMEOUT,
                                                    _state.getSuccessful()));
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
            long time = getContext().clock().now() - _state.getWhenStarted();
            getContext().statManager().addRateData("netDb.failedTime", time, 0);
        }
        if (_onFailure != null)
            getContext().jobQueue().addJob(_onFailure);
    }

    public String getName() { return "Kademlia NetDb Search"; }
}
