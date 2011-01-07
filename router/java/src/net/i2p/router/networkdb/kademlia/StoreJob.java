package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

class StoreJob extends JobImpl {
    protected Log _log;
    private KademliaNetworkDatabaseFacade _facade;
    protected StoreState _state;
    private Job _onSuccess;
    private Job _onFailure;
    private long _timeoutMs;
    private long _expiration;
    private PeerSelector _peerSelector;

    private final static int PARALLELIZATION = 4; // how many sent at a time
    private final static int REDUNDANCY = 4; // we want the data sent to 6 peers
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
    
    /**
     * Send a data structure to the floodfills
     * 
     */
    public StoreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, 
                    DatabaseEntry data, Job onSuccess, Job onFailure, long timeoutMs) {
        this(context, facade, key, data, onSuccess, onFailure, timeoutMs, null);
    }
    
    /**
     * @param toSkip set of peer hashes of people we dont want to send the data to (e.g. we
     *               already know they have it).  This can be null.
     */
    public StoreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, 
                    DatabaseEntry data, Job onSuccess, Job onFailure, long timeoutMs, Set<Hash> toSkip) {
        super(context);
        _log = context.logManager().getLog(StoreJob.class);
        _facade = facade;
        _state = new StoreState(getContext(), key, data, toSkip);
        _onSuccess = onSuccess;
        _onFailure = onFailure;
        _timeoutMs = timeoutMs;
        _expiration = context.clock().now() + timeoutMs;
        _peerSelector = facade.getPeerSelector();
    }

    public String getName() { return "Kademlia NetDb Store";}
    public void runJob() {
        sendNext();
    }

    private boolean isExpired() { 
        return getContext().clock().now() >= _expiration;
    }
    
    private static final int MAX_PEERS_SENT = 10;

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
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Expired: " + _timeoutMs);
            fail();
        } else if (_state.getAttempted().size() > MAX_PEERS_SENT) {
            _state.complete(true);
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Max sent");
            fail();
        } else {
            //if (_log.shouldLog(Log.INFO))
            //    _log.info(getJobId() + ": Sending: " + _state);
            continueSending();
        }
    }
    
    protected int getParallelization() { return PARALLELIZATION; }
    protected int getRedundancy() { return REDUNDANCY; }

    /**
     * Send a series of searches to the next available peers as selected by
     * the routing table, but making sure no more than PARALLELIZATION are outstanding
     * at any time
     *
     */
    private void continueSending() { 
        if (_state.completed()) return;
        int toCheck = getParallelization() - _state.getPending().size();
        if (toCheck <= 0) {
            // too many already pending
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Too many store messages pending");
            return;
        } 
        if (toCheck > getParallelization())
            toCheck = getParallelization();

        // We are going to send the RouterInfo directly, rather than through a lease,
        // so select a floodfill peer we are already connected to.
        // This will help minimize active connections for floodfill peers and allow
        // the network to scale.
        // Perhaps the ultimate solution is to send RouterInfos through a lease also.
        List<Hash> closestHashes;
        //if (_state.getData() instanceof RouterInfo) 
        //    closestHashes = getMostReliableRouters(_state.getTarget(), toCheck, _state.getAttempted());
        //else
        //    closestHashes = getClosestRouters(_state.getTarget(), toCheck, _state.getAttempted());
        closestHashes = getClosestFloodfillRouters(_state.getTarget(), toCheck, _state.getAttempted());
        if ( (closestHashes == null) || (closestHashes.isEmpty()) ) {
            if (_state.getPending().isEmpty()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": No more peers left and none pending");
                fail();
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": No more peers left but some are pending, so keep waiting");
                return;
            }
        } else {
            //_state.addPending(closestHashes);
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Continue sending key " + _state.getTarget() + " after " + _state.getAttempted().size() + " tries to " + closestHashes);
            for (Iterator<Hash> iter = closestHashes.iterator(); iter.hasNext(); ) {
                Hash peer = iter.next();
                DatabaseEntry ds = _facade.getDataStore().get(peer);
                if ( (ds == null) || !(ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) ) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": Error selecting closest hash that wasnt a router! " + peer + " : " + ds);
                    _state.addSkipped(peer);
                } else {
                    int peerTimeout = _facade.getPeerTimeout(peer);

                    //PeerProfile prof = getContext().profileOrganizer().getProfile(peer);
                    //if (prof != null && prof.getIsExpandedDB()) {
                    //    RateStat failing = prof.getDBHistory().getFailedLookupRate();
                    //    Rate failed = failing.getRate(60*60*1000);
                    //}

                    //long failedCount = failed.getCurrentEventCount()+failed.getLastEventCount();
                    //if (failedCount > 10) {
                    //    _state.addSkipped(peer);
                    //    continue;
                    //}
                    //
                    //if (failed.getCurrentEventCount() + failed.getLastEventCount() > avg) {
                    //    _state.addSkipped(peer);
                    //}
                    
                    // we don't want to filter out peers based on our local shitlist, as that opens an avenue for
                    // manipulation (since a peer can get us to shitlist them by, well, being shitty, and that
                    // in turn would let them assume that a netDb store received didn't come from us)
                    //if (getContext().shitlist().isShitlisted(((RouterInfo)ds).getIdentity().calculateHash())) {
                    //    _state.addSkipped(peer);
                    //} else {
                    //
                    // ERR: see hidden mode comments in HandleDatabaseLookupMessageJob
                    // // Do not store to hidden nodes
                    // if (!((RouterInfo)ds).isHidden()) {
                        _state.addPending(peer);
                        sendStore((RouterInfo)ds, peerTimeout);
                    //}
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
/*****
    private List<Hash> getClosestRouters(Hash key, int numClosest, Set<Hash> alreadyChecked) {
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(key);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(getJobId() + ": Current routing key for " + key + ": " + rkey);

        KBucketSet ks = _facade.getKBuckets();
        if (ks == null) return new ArrayList();
        return _peerSelector.selectNearestExplicit(rkey, numClosest, alreadyChecked, ks);
    }
*****/

    /** used for routerinfo stores, prefers those already connected */
/*****
    private List<Hash> getMostReliableRouters(Hash key, int numClosest, Set<Hash> alreadyChecked) {
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(key);
        KBucketSet ks = _facade.getKBuckets();
        if (ks == null) return new ArrayList();
        return _peerSelector.selectMostReliablePeers(rkey, numClosest, alreadyChecked, ks);
    }
*****/

    private List<Hash> getClosestFloodfillRouters(Hash key, int numClosest, Set<Hash> alreadyChecked) {
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(key);
        KBucketSet ks = _facade.getKBuckets();
        if (ks == null) return new ArrayList();
        return ((FloodfillPeerSelector)_peerSelector).selectFloodfillParticipants(rkey, numClosest, alreadyChecked, ks);
    }

    /** limit expiration for direct sends */
    private static final int MAX_DIRECT_EXPIRATION = 15*1000;

    /**
     * Send a store to the given peer, including a reply 
     * DeliveryStatusMessage so we know it got there
     *
     */
    private void sendStore(RouterInfo router, int responseTime) {
        if (!_state.getTarget().equals(_state.getData().getHash())) {
            _log.error("Hash mismatch StoreJob");
            return;
        }
        DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
        if (_state.getData().getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            if (responseTime > MAX_DIRECT_EXPIRATION)
                responseTime = MAX_DIRECT_EXPIRATION;
        } else if (_state.getData().getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
        } else {
            throw new IllegalArgumentException("Storing an unknown data type! " + _state.getData());
        }
        msg.setEntry(_state.getData());
        msg.setMessageExpiration(getContext().clock().now() + _timeoutMs);

        if (router.getIdentity().equals(getContext().router().getRouterInfo().getIdentity())) {
            // don't send it to ourselves
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Dont send store to ourselves - why did we try?");
            return;
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Send store timeout is " + responseTime);

        sendStore(msg, router, getContext().clock().now() + responseTime);
    }
    
    /**
     * Send a store to the given peer, including a reply 
     * DeliveryStatusMessage so we know it got there
     *
     */
    private void sendStore(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        if (msg.getEntry().getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
            getContext().statManager().addRateData("netDb.storeLeaseSetSent", 1, 0);
            // if it is an encrypted leaseset...
            if (getContext().keyRing().get(msg.getKey()) != null)
                sendStoreThroughGarlic(msg, peer, expiration);
            else
                sendStoreThroughClient(msg, peer, expiration);
        } else {
            getContext().statManager().addRateData("netDb.storeRouterInfoSent", 1, 0);
            sendDirect(msg, peer, expiration);
        }
    }

    /**
     * Send directly,
     * with the reply to come back directly.
     *
     */
    private void sendDirect(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        long token = getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        msg.setReplyToken(token);
        msg.setReplyGateway(getContext().routerHash());

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send(dbStore) w/ token expected " + token);
        
        _state.addPending(peer.getIdentity().getHash());
        
        SendSuccessJob onReply = new SendSuccessJob(getContext(), peer);
        FailedJob onFail = new FailedJob(getContext(), peer, getContext().clock().now());
        StoreMessageSelector selector = new StoreMessageSelector(getContext(), getJobId(), peer, token, expiration);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending store directly to " + peer.getIdentity().getHash());
        OutNetMessage m = new OutNetMessage(getContext());
        m.setExpiration(expiration);
        m.setMessage(msg);
        m.setOnFailedReplyJob(onFail);
        m.setOnFailedSendJob(onFail);
        m.setOnReplyJob(onReply);
        m.setPriority(STORE_PRIORITY);
        m.setReplySelector(selector);
        m.setTarget(peer);
        getContext().messageRegistry().registerPending(m);
        getContext().commSystem().processMessage(m);
    }
    
    /**
     * This is misnamed, it means sending it out through an exploratory tunnel,
     * with the reply to come back through an exploratory tunnel.
     * There is no garlic encryption added.
     *
     */
    private void sendStoreThroughGarlic(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        long token = getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        
        TunnelInfo replyTunnel = selectInboundTunnel();
        if (replyTunnel == null) {
            _log.warn("No reply inbound tunnels available!");
            return;
        }
        TunnelId replyTunnelId = replyTunnel.getReceiveTunnelId(0);
        msg.setReplyToken(token);
        msg.setReplyTunnel(replyTunnelId);
        msg.setReplyGateway(replyTunnel.getPeer(0));

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send(dbStore) w/ token expected " + token);
        
        _state.addPending(peer.getIdentity().getHash());
        
        TunnelInfo outTunnel = selectOutboundTunnel();
        if (outTunnel != null) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug(getJobId() + ": Sending tunnel message out " + outTunnelId + " to " 
            //               + peer.getIdentity().getHash().toBase64());
            //TunnelId targetTunnelId = null; // not needed
            //Job onSend = null; // not wanted
        
            SendSuccessJob onReply = new SendSuccessJob(getContext(), peer, outTunnel, msg.getMessageSize());
            FailedJob onFail = new FailedJob(getContext(), peer, getContext().clock().now());
            StoreMessageSelector selector = new StoreMessageSelector(getContext(), getJobId(), peer, token, expiration);
    
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("sending store to " + peer.getIdentity().getHash() + " through " + outTunnel + ": " + msg);
            getContext().messageRegistry().registerPending(selector, onReply, onFail, (int)(expiration - getContext().clock().now()));
            getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0), null, peer.getIdentity().getHash());
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnels to send a dbStore out!");
            fail();
        }
    }
    
    private TunnelInfo selectOutboundTunnel() {
        return getContext().tunnelManager().selectOutboundTunnel();
    }
 
    private TunnelInfo selectInboundTunnel() {
        return getContext().tunnelManager().selectInboundTunnel();
    }
 
    /**
     * Send a leaseset store message out the client tunnel,
     * with the reply to come back through a client tunnel.
     * Stores are garlic encrypted to hide the identity from the OBEP.
     *
     * This makes it harder for an exploratory OBEP or IBGW to correlate it
     * with one or more destinations. Since we are publishing the leaseset,
     * it's easy to find out that an IB tunnel belongs to this dest, and
     * it isn't much harder to do the same for an OB tunnel.
     *
     * As a side benefit, client tunnels should be faster and more reliable than
     * exploratory tunnels.
     *
     * @param msg must contain a leaseset
     * @since 0.7.10
     */
    private void sendStoreThroughClient(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        long token = getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        Hash client = msg.getKey();

        TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundTunnel(client);
        if (replyTunnel == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No reply inbound tunnels available!");
            fail();
            return;
        }
        TunnelId replyTunnelId = replyTunnel.getReceiveTunnelId(0);
        msg.setReplyToken(token);
        msg.setReplyTunnel(replyTunnelId);
        msg.setReplyGateway(replyTunnel.getPeer(0));

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send(dbStore) w/ token expected " + token);

        Hash to = peer.getIdentity().getHash();
        TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundTunnel(client);
        if (outTunnel != null) {
            I2NPMessage sent;
            boolean shouldEncrypt = supportsEncryption(peer);
            if (shouldEncrypt) {
                // garlic encrypt
                MessageWrapper.WrappedMessage wm = MessageWrapper.wrap(getContext(), msg, client, peer);
                if (wm == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Fail garlic encrypting from: " + client);
                    fail();
                    return;
                }
                sent = wm.getMessage();
                _state.addPending(to, wm);
            } else {
                sent = msg;
                _state.addPending(to);
                // now that almost all floodfills are at 0.7.10,
                // just refuse to store unencrypted to older ones.
                _state.replyTimeout(to);
                getContext().jobQueue().addJob(new WaitJob(getContext()));
                return;
            }

            SendSuccessJob onReply = new SendSuccessJob(getContext(), peer, outTunnel, sent.getMessageSize());
            FailedJob onFail = new FailedJob(getContext(), peer, getContext().clock().now());
            StoreMessageSelector selector = new StoreMessageSelector(getContext(), getJobId(), peer, token, expiration);
    
            if (_log.shouldLog(Log.DEBUG)) {
                if (shouldEncrypt)
                    _log.debug("sending encrypted store to " + peer.getIdentity().getHash() + " through " + outTunnel + ": " + sent);
                else
                    _log.debug("sending store to " + peer.getIdentity().getHash() + " through " + outTunnel + ": " + sent);
                //_log.debug("Expiration is " + new Date(sent.getMessageExpiration()));
            }
            getContext().messageRegistry().registerPending(selector, onReply, onFail, (int)(expiration - getContext().clock().now()));
            getContext().tunnelDispatcher().dispatchOutbound(sent, outTunnel.getSendTunnelId(0), null, to);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnels to send a dbStore out - delaying...");
            // continueSending() above did an addPending() so remove it here.
            // This means we will skip the peer next time, can't be helped for now
            // without modding StoreState
            _state.replyTimeout(to);
            Job waiter = new WaitJob(getContext());
            waiter.getTiming().setStartAfter(getContext().clock().now() + 3*1000);
            getContext().jobQueue().addJob(waiter);
            //fail();
        }
    }
    
    /**
     * Called to wait a little while
     * @since 0.7.10
     */
    private class WaitJob extends JobImpl {
        public WaitJob(RouterContext enclosingContext) {
            super(enclosingContext);
        }
        public void runJob() {
            sendNext();
        }
        public String getName() { return "Kademlia Store Send Delay"; }
    }

    private static final String MIN_ENCRYPTION_VERSION = "0.7.10";

    /**
     * *sigh*
     * sadly due to a bug in HandleFloodfillDatabaseStoreMessageJob, where
     * a floodfill would not flood anything that arrived garlic-wrapped
     * @since 0.7.10
     */
    private static boolean supportsEncryption(RouterInfo ri) {
        String v = ri.getOption("router.version");
        if (v == null)
            return false;
        return (new VersionComparator()).compare(v, MIN_ENCRYPTION_VERSION) >= 0;
    }

    /**
     * Called after sending a dbStore to a peer successfully, 
     * marking the store as successful
     *
     */
    private class SendSuccessJob extends JobImpl implements ReplyJob {
        private RouterInfo _peer;
        private TunnelInfo _sendThrough;
        private int _msgSize;
        
        public SendSuccessJob(RouterContext enclosingContext, RouterInfo peer) {
            this(enclosingContext, peer, null, 0);
        }
        public SendSuccessJob(RouterContext enclosingContext, RouterInfo peer, TunnelInfo sendThrough, int size) {
            super(enclosingContext);
            _peer = peer;
            _sendThrough = sendThrough;
            if (size <= 0)
                _msgSize = 0;
            else
                _msgSize = ((size + 1023) / 1024) * 1024;
        }

        public String getName() { return "Kademlia Store Send Success"; }
        public void runJob() {
            Hash hash = _peer.getIdentity().getHash();
            MessageWrapper.WrappedMessage wm = _state.getPendingMessage(hash);
            if (wm != null)
                wm.acked();
            long howLong = _state.confirmed(hash);

            if (_log.shouldLog(Log.INFO))
                _log.info(StoreJob.this.getJobId() + ": Marking store of " + _state.getTarget() 
                          + " to " + hash.toBase64() + " successful after " + howLong);
            getContext().profileManager().dbStoreSent(hash, howLong);
            getContext().statManager().addRateData("netDb.ackTime", howLong, howLong);

            if ( (_sendThrough != null) && (_msgSize > 0) ) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("sent a " + _msgSize + "byte netDb message through tunnel " + _sendThrough + " after " + howLong);
                for (int i = 0; i < _sendThrough.getLength(); i++)
                    getContext().profileManager().tunnelDataPushed(_sendThrough.getPeer(i), howLong, _msgSize);
                _sendThrough.incrementVerifiedBytesTransferred(_msgSize);
            }
            
            if (_state.getCompleteCount() >= getRedundancy()) {
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
        private long _sendOn;

        public FailedJob(RouterContext enclosingContext, RouterInfo peer, long sendOn) {
            super(enclosingContext);
            _peer = peer;
            _sendOn = sendOn;
        }
        public void runJob() {
            Hash hash = _peer.getIdentity().getHash();
            if (_log.shouldLog(Log.INFO))
                _log.info(StoreJob.this.getJobId() + ": Peer " + hash.toBase64() 
                          + " timed out sending " + _state.getTarget());

            MessageWrapper.WrappedMessage wm = _state.getPendingMessage(hash);
            if (wm != null)
                wm.fail();
            _state.replyTimeout(hash);

            getContext().profileManager().dbStoreFailed(hash);
            getContext().statManager().addRateData("netDb.replyTimeout", getContext().clock().now() - _sendOn, 0);
            
            sendNext();
        }
        public String getName() { return "Kademlia Store Send Failed"; }
    }

    /**
     * Send was totally successful
     */
    protected void succeed() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Succeeded sending key " + _state.getTarget());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of successful send: " + _state);
        if (_onSuccess != null)
            getContext().jobQueue().addJob(_onSuccess);
        _state.complete(true);
        getContext().statManager().addRateData("netDb.storePeers", _state.getAttempted().size(), _state.getWhenCompleted()-_state.getWhenStarted());
    }

    /**
     * Send totally failed
     */
    protected void fail() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Failed sending key " + _state.getTarget());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": State of failed send: " + _state, new Exception("Who failed me?"));
        if (_onFailure != null)
            getContext().jobQueue().addJob(_onFailure);
        _state.complete(true);
        getContext().statManager().addRateData("netDb.storeFailedPeers", _state.getAttempted().size(), _state.getWhenCompleted()-_state.getWhenStarted());
    }
}
