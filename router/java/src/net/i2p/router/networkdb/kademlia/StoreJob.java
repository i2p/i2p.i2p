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
import java.util.List;
import java.util.Set;

import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Log;

class StoreJob extends JobImpl {
    private Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    private StoreState _state;
    private Job _onSuccess;
    private Job _onFailure;
    private long _timeoutMs;
    private long _expiration;
    private PeerSelector _peerSelector;

    private final static int PARALLELIZATION = 1; // how many sent at a time
    private final static int REDUNDANCY = 2; // we want the data sent to 2 peers
    /**
     * additionally send to 1 outlier(s), in case all of the routers chosen in our
     * REDUNDANCY set are attacking us by accepting DbStore messages but dropping
     * the data.  
     *
     * TODO: um, honor this.  make sure we send to this many peers that aren't 
     * closest to the key.
     *
     */
    private final static int EXPLORATORY_REDUNDANCY = 1; 
    private final static int STORE_PRIORITY = 100;
    
    /** how long we allow for an ACK to take after a store */
    private final static long STORE_TIMEOUT_MS = 10*1000;

    /**
     * Create a new search for the routingKey specified
     * 
     */
    public StoreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, 
                    DataStructure data, Job onSuccess, Job onFailure, long timeoutMs) {
        this(context, facade, key, data, onSuccess, onFailure, timeoutMs, null);
    }
    
    /**
     * @param toSkip set of peer hashes of people we dont want to send the data to (e.g. we
     *               already know they have it).  This can be null.
     */
    public StoreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, 
                    DataStructure data, Job onSuccess, Job onFailure, long timeoutMs, Set toSkip) {
        super(context);
        _log = context.logManager().getLog(StoreJob.class);
        _context.statManager().createRateStat("netDb.storeSent", "How many netDb store messages have we sent?", "Network Database", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.storePeers", "How many peers each netDb must be sent to before success?", "Network Database", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("netDb.ackTime", "How long does it take for a peer to ack a netDb store?", "Network Database", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _facade = facade;
        _state = new StoreState(_context, key, data, toSkip);
        _onSuccess = onSuccess;
        _onFailure = onFailure;
        _timeoutMs = timeoutMs;
        _expiration = context.clock().now() + timeoutMs;
        _peerSelector = new PeerSelector(context);
    }

    public String getName() { return "Kademlia NetDb Store";}
    public void runJob() {
        sendNext();
    }

    private boolean isExpired() { 
        return _context.clock().now() >= _expiration;
    }

    /**
     * send the key to the next batch of peers
     */
    private void sendNext() {
        if (_state.completed()) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Already completed");
            return;
        }
        if (isExpired()) {
            _state.complete(true);
            fail();
        } else {
            //if (_log.shouldLog(Log.INFO))
            //    _log.info(getJobId() + ": Sending: " + _state);
            continueSending();
        }
    }

    /**
     * Send a series of searches to the next available peers as selected by
     * the routing table, but making sure no more than PARALLELIZATION are outstanding
     * at any time
     *
     */
    private void continueSending() { 
        if (_state.completed()) return;
        int toCheck = PARALLELIZATION - _state.getPending().size();
        if (toCheck <= 0) {
            // too many already pending
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Too many store messages pending");
            return;
        } 
        if (toCheck > PARALLELIZATION)
            toCheck = PARALLELIZATION;

        List closestHashes = getClosestRouters(_state.getTarget(), toCheck, _state.getAttempted());
        if ( (closestHashes == null) || (closestHashes.size() <= 0) ) {
            if (_state.getPending().size() <= 0) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": No more peers left and none pending");
                fail();
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": No more peers left but some are pending, so keep waiting");
                return;
            }
        } else {
            _state.addPending(closestHashes);
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Continue sending key " + _state.getTarget() + " after " + _state.getAttempted().size() + " tries to " + closestHashes);
            for (Iterator iter = closestHashes.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                DataStructure ds = _facade.getDataStore().get(peer);
                if ( (ds == null) || !(ds instanceof RouterInfo) ) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getJobId() + ": Error selecting closest hash that wasnt a router! " + peer + " : " + ds);
                } else {
                    sendStore((RouterInfo)ds);
                }
            }
        }
    }

    /**
     * Set of Hash structures for routers we want to send the data to next.  This is the 
     * 'interesting' part of the algorithm.  DBStore isn't usually as time sensitive as 
     * it is reliability sensitive, so lets delegate it off to the PeerSelector via 
     * selectNearestExplicit, which is currently O(n*log(n))
     *
     * @return ordered list of Hash objects
     */
    private List getClosestRouters(Hash key, int numClosest, Set alreadyChecked) {
        Hash rkey = _context.routingKeyGenerator().getRoutingKey(key);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(getJobId() + ": Current routing key for " + key + ": " + rkey);

        return _peerSelector.selectNearestExplicit(rkey, numClosest, alreadyChecked, _facade.getKBuckets());
    }

    /**
     * Send a store to the given peer through a garlic route, including a reply 
     * DeliveryStatusMessage so we know it got there
     *
     */
    private void sendStore(RouterInfo router) {
        DatabaseStoreMessage msg = new DatabaseStoreMessage(_context);
        msg.setKey(_state.getTarget());
        if (_state.getData() instanceof RouterInfo) 
            msg.setRouterInfo((RouterInfo)_state.getData());
        else if (_state.getData() instanceof LeaseSet) 
            msg.setLeaseSet((LeaseSet)_state.getData());
        else
            throw new IllegalArgumentException("Storing an unknown data type! " + _state.getData());
        msg.setMessageExpiration(new Date(_context.clock().now() + _timeoutMs));

        if (router.getIdentity().equals(_context.router().getRouterInfo().getIdentity())) {
            // don't send it to ourselves
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Dont send store to ourselves - why did we try?");
            return;
        } else {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug(getJobId() + ": Send store to " + router.getIdentity().getHash().toBase64());
        }

        sendStore(msg, router, _expiration);
    }
    
    private void sendStore(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        _context.statManager().addRateData("netDb.storeSent", 1, 0);
        sendStoreThroughGarlic(msg, peer, expiration);
    }

    private void sendStoreThroughGarlic(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        long token = _context.random().nextInt(Integer.MAX_VALUE);
        
        TunnelId replyTunnelId = selectInboundTunnel();
        TunnelInfo replyTunnel = _context.tunnelManager().getTunnelInfo(replyTunnelId);
        if (replyTunnel == null) {
            _log.error("No reply inbound tunnels available!");
            return;
        }
        msg.setReplyToken(token);
        msg.setReplyTunnel(replyTunnelId);
        msg.setReplyGateway(replyTunnel.getThisHop());

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send(dbStore) w/ token expected " + token);
        
        _state.addPending(peer.getIdentity().getHash());
        
        SendSuccessJob onReply = new SendSuccessJob(peer);
        FailedJob onFail = new FailedJob(peer);
        StoreMessageSelector selector = new StoreMessageSelector(_context, getJobId(), peer, token, expiration);
        
        TunnelId outTunnelId = selectOutboundTunnel();
        if (outTunnelId != null) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug(getJobId() + ": Sending tunnel message out " + outTunnelId + " to " 
            //               + peer.getIdentity().getHash().toBase64());
            TunnelId targetTunnelId = null; // not needed
            Job onSend = null; // not wanted
            SendTunnelMessageJob j = new SendTunnelMessageJob(_context, msg, outTunnelId, 
                                                              peer.getIdentity().getHash(),
                                                              targetTunnelId, onSend, onReply, 
                                                              onFail, selector, STORE_TIMEOUT_MS, 
                                                              STORE_PRIORITY);
            _context.jobQueue().addJob(j);
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No outbound tunnels to send a dbStore out!");
            fail();
        }
    }
    
    private TunnelId selectOutboundTunnel() {
        TunnelSelectionCriteria criteria = new TunnelSelectionCriteria();
        criteria.setAnonymityPriority(80);
        criteria.setLatencyPriority(50);
        criteria.setReliabilityPriority(20);
        criteria.setMaximumTunnelsRequired(1);
        criteria.setMinimumTunnelsRequired(1);
        List tunnelIds = _context.tunnelManager().selectOutboundTunnelIds(criteria);
        if (tunnelIds.size() <= 0) {
            _log.error("No outbound tunnels?!");
            return null;
        } else {
            return (TunnelId)tunnelIds.get(0);
        }
    }
 
    private TunnelId selectInboundTunnel() {
        TunnelSelectionCriteria criteria = new TunnelSelectionCriteria();
        criteria.setAnonymityPriority(80);
        criteria.setLatencyPriority(50);
        criteria.setReliabilityPriority(20);
        criteria.setMaximumTunnelsRequired(1);
        criteria.setMinimumTunnelsRequired(1);
        List tunnelIds = _context.tunnelManager().selectInboundTunnelIds(criteria);
        if (tunnelIds.size() <= 0) {
            _log.error("No inbound tunnels?!");
            return null;
        } else {
            return (TunnelId)tunnelIds.get(0);
        }
    }
 
    /**
     * Called after sending a dbStore to a peer successfully, 
     * marking the store as successful
     *
     */
    private class SendSuccessJob extends JobImpl implements ReplyJob {
        private RouterInfo _peer;
        
        public SendSuccessJob(RouterInfo peer) {
            super(StoreJob.this._context);
            _peer = peer;
        }

        public String getName() { return "Kademlia Store Send Success"; }
        public void runJob() {
            long howLong = _state.confirmed(_peer.getIdentity().getHash());
            if (_log.shouldLog(Log.INFO))
                _log.info(StoreJob.this.getJobId() + ": Marking store of " + _state.getTarget() 
                          + " to " + _peer.getIdentity().getHash().toBase64() + " successful after " + howLong);
            _context.profileManager().dbStoreSent(_peer.getIdentity().getHash(), howLong);
            _context.statManager().addRateData("netDb.ackTime", howLong, howLong);

            if (_state.getSuccessful().size() >= REDUNDANCY) {
                succeed();
            } else {
                sendNext();
            }
        }
        
        public void setMessage(I2NPMessage message) {
            // ignored, since if the selector matched it, its fine by us
        }
    }

    /**
     * Called when a particular peer failed to respond before the timeout was 
     * reached, or if the peer could not be contacted at all.
     *
     */
    private class FailedJob extends JobImpl {
        private RouterInfo _peer;

        public FailedJob(RouterInfo peer) {
            super(StoreJob.this._context);
            _peer = peer;
        }
        public void runJob() {
            if (_log.shouldLog(Log.WARN))
                _log.warn(StoreJob.this.getJobId() + ": Peer " + _peer.getIdentity().getHash().toBase64() + " timed out");
            _state.replyTimeout(_peer.getIdentity().getHash());
            _context.profileManager().dbStoreFailed(_peer.getIdentity().getHash());
            
            sendNext();
        }
        public String getName() { return "Kademlia Store Failed"; }
    }

    /**
     * Send was totally successful
     */
    private void succeed() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Succeeded sending key " + _state.getTarget());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of successful send: " + _state);
        if (_onSuccess != null)
            _context.jobQueue().addJob(_onSuccess);
        _facade.noteKeySent(_state.getTarget());
        _context.statManager().addRateData("netDb.storePeers", _state.getAttempted().size(), _state.getWhenCompleted()-_state.getWhenStarted());
    }

    /**
     * Send totally failed
     */
    private void fail() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Failed sending key " + _state.getTarget());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of failed send: " + _state, new Exception("Who failed me?"));
        if (_onFailure != null)
            _context.jobQueue().addJob(_onFailure);
    }
}