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
import java.util.List;
import java.util.Set;

import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.kademlia.KBucketSet;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.pool.ConnectChecker;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

/**
 *  Stores through this always request a reply.
 *
 *  Unused directly - see FloodfillStoreJob
 */
class StoreJob extends JobImpl {
    protected final Log _log;
    private final KademliaNetworkDatabaseFacade _facade;
    protected final StoreState _state;
    private final Job _onSuccess;
    private final Job _onFailure;
    private final long _timeoutMs;
    private final long _expiration;
    private final PeerSelector _peerSelector;
    private final ConnectChecker _connectChecker;
    private final int _connectMask;

    private final static int PARALLELIZATION = 4; // how many sent at a time
    private final static int REDUNDANCY = 4; // we want the data sent to 6 peers
    private final static int STORE_PRIORITY = OutNetMessage.PRIORITY_MY_NETDB_STORE;
    
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
        if (data.getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
            _connectChecker = null;
            _connectMask = 0;
        } else {
            _connectChecker = new ConnectChecker(context);
            RouterInfo us = context.router().getRouterInfo();
            if (us != null)
                _connectMask = _connectChecker.getOutboundMask(us);
            else
                _connectMask = ConnectChecker.ANY_V4;
        }
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
     *
     * Synchronized to enforce parallelization limits and prevent dups
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
    
    /** overridden in FSJ */
    protected int getParallelization() { return PARALLELIZATION; }

    /** overridden in FSJ */
    protected int getRedundancy() { return REDUNDANCY; }

    /**
     * Send a series of searches to the next available peers as selected by
     * the routing table, but making sure no more than PARALLELIZATION are outstanding
     * at any time
     *
     * Caller should synchronize to enforce parallelization limits and prevent dups
     */
    private synchronized void continueSending() { 
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
            int queued = 0;
            int skipped = 0;
            for (Hash peer : closestHashes) {
                DatabaseEntry ds = _facade.getDataStore().get(peer);
                if ( (ds == null) || !(ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) ) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": Error selecting closest hash that wasnt a router! " + peer + " : " + ds);
                    _state.addSkipped(peer);
                    skipped++;
                } else if (!shouldStoreTo((RouterInfo)ds)) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": Skipping old router " + peer);
                    _state.addSkipped(peer);
                    skipped++;
/****
   above shouldStoreTo() check is newer than these two checks, so we're covered

                } else if (_state.getData().getType() == DatabaseEntry.KEY_TYPE_LEASESET &&
                           !supportsCert((RouterInfo)ds,
                                         ((LeaseSet)_state.getData()).getDestination().getCertificate())) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": Skipping router that doesn't support key certs " + peer);
                    _state.addSkipped(peer);
                    skipped++;
                } else if (_state.getData().getType() == DatabaseEntry.KEY_TYPE_LEASESET &&
                           ((LeaseSet)_state.getData()).getLeaseCount() > 6 &&
                           !supportsBigLeaseSets((RouterInfo)ds)) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": Skipping router that doesn't support big leasesets " + peer);
                    _state.addSkipped(peer);
                    skipped++;
****/
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
                    
                    // we don't want to filter out peers based on our local banlist, as that opens an avenue for
                    // manipulation (since a peer can get us to banlist them, and that
                    // in turn would let them assume that a netDb store received didn't come from us)
                    //if (getContext().banlist().isBanlisted(((RouterInfo)ds).getIdentity().calculateHash())) {
                    //    _state.addSkipped(peer);
                    //} else {
                    //
                    // ERR: see hidden mode comments in HandleDatabaseLookupMessageJob
                    // // Do not store to hidden nodes
                    // if (!((RouterInfo)ds).isHidden()) {
                       if (_log.shouldLog(Log.INFO))
                           _log.info(getJobId() + ": Continue sending key " + _state.getTarget() +
                                     " after " + _state.getAttempted().size() + " tries to " + closestHashes);
                        _state.addPending(peer);
                        sendStore((RouterInfo)ds, peerTimeout);
                        queued++;
                    //}
                }
            }
            if (queued == 0 && _state.getPending().isEmpty()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": No more peers left after skipping " + skipped + " and none pending");
                // queue a job to go around again rather than recursing
                getContext().jobQueue().addJob(new WaitJob(getContext()));
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
        KBucketSet<Hash> ks = _facade.getKBuckets();
        if (ks == null) return new ArrayList<Hash>();
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
        long now = getContext().clock().now();
        msg.setMessageExpiration(now + _timeoutMs);

        if (router.getIdentity().equals(getContext().router().getRouterInfo().getIdentity())) {
            // don't send it to ourselves
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Dont send store to ourselves - why did we try?");
            return;
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Send store timeout is " + responseTime);

        sendStore(msg, router, now + responseTime);
    }
    
    /**
     * Send a store to the given peer, including a reply 
     * DeliveryStatusMessage so we know it got there
     *
     */
    private void sendStore(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        if (msg.getEntry().getType() == DatabaseEntry.KEY_TYPE_LEASESET) {
            getContext().statManager().addRateData("netDb.storeLeaseSetSent", 1);
            // if it is an encrypted leaseset...
            if (getContext().keyRing().get(msg.getKey()) != null)
                sendStoreThroughGarlic(msg, peer, expiration);
            else
                sendStoreThroughClient(msg, peer, expiration);
        } else {
            getContext().statManager().addRateData("netDb.storeRouterInfoSent", 1);
            // if we can't connect to peer directly, just send it out an exploratory tunnel
            if (_connectChecker.canConnect(_connectMask, peer))
                sendDirect(msg, peer, expiration);
            else
                sendStoreThroughGarlic(msg, peer, expiration);
        }
    }

    /**
     * Send directly,
     * with the reply to come back directly.
     *
     */
    private void sendDirect(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        long token = 1 + getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        msg.setReplyToken(token);
        msg.setReplyGateway(getContext().routerHash());

        _state.addPending(peer.getIdentity().getHash());
        
        SendSuccessJob onReply = new SendSuccessJob(getContext(), peer);
        FailedJob onFail = new FailedJob(getContext(), peer, getContext().clock().now());
        StoreMessageSelector selector = new StoreMessageSelector(getContext(), getJobId(), peer, token, expiration);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": sending store directly to " + peer.getIdentity().getHash());
        OutNetMessage m = new OutNetMessage(getContext(), msg, expiration, STORE_PRIORITY, peer);
        m.setOnFailedReplyJob(onFail);
        m.setOnFailedSendJob(onFail);
        m.setOnReplyJob(onReply);
        m.setReplySelector(selector);
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
        long token = 1 + getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        
        Hash to = peer.getIdentity().getHash();
        TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundExploratoryTunnel(to);
        if (replyTunnel == null) {
            _log.warn("No reply inbound tunnels available!");
            return;
        }
        TunnelId replyTunnelId = replyTunnel.getReceiveTunnelId(0);
        msg.setReplyToken(token);
        msg.setReplyTunnel(replyTunnelId);
        msg.setReplyGateway(replyTunnel.getPeer(0));

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send store thru expl. tunnel to " + peer.getIdentity().getHash() + "  w/ token expected " + token);
        
        _state.addPending(to);
        
        TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundExploratoryTunnel(to);
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
            getContext().messageRegistry().registerPending(selector, onReply, onFail);
            getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0), null, to);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnels to send a dbStore out!");
            fail();
        }
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
        long token = 1 + getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        Hash client = msg.getKey();

        Hash to = peer.getIdentity().getHash();
        TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundTunnel(client, to);
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

        TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundTunnel(client, to);
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
            getContext().messageRegistry().registerPending(selector, onReply, onFail);
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
        String v = ri.getVersion();
        return VersionComparator.comp(v, MIN_ENCRYPTION_VERSION) >= 0;
    }

    /**
     * Does this router understand this cert?
     * @return true if not a key cert
     * @since 0.9.12
     */
/****
    public static boolean supportsCert(RouterInfo ri, Certificate cert) {
        if (cert.getCertificateType() != Certificate.CERTIFICATE_TYPE_KEY)
            return true;
        SigType type;
        try {
            type = cert.toKeyCertificate().getSigType();
        } catch (DataFormatException dfe) {
            return false;
        }
        if (type == null)
            return false;
        String v = ri.getVersion();
        String since = type.getSupportedSince();
        return VersionComparator.comp(v, since) >= 0;
    }

    private static final String MIN_BIGLEASESET_VERSION = "0.9";
****/

    /**
     * Does he support more than 6 leasesets?
     * @since 0.9.12
     */
/****
    private static boolean supportsBigLeaseSets(RouterInfo ri) {
        String v = ri.getVersion();
        return VersionComparator.comp(v, MIN_BIGLEASESET_VERSION) >= 0;
    }
****/

    /** */
    public static final String MIN_STORE_VERSION = "0.9.28";

    /**
     * Is it too old?
     * @since 0.9.33
     */
    static boolean shouldStoreTo(RouterInfo ri) {
        String v = ri.getVersion();
        return VersionComparator.comp(v, MIN_STORE_VERSION) >= 0;
    }

    /**
     * Called after sending a dbStore to a peer successfully, 
     * marking the store as successful
     *
     */
    private class SendSuccessJob extends JobImpl implements ReplyJob {
        private final RouterInfo _peer;
        private final TunnelInfo _sendThrough;
        private final int _msgSize;
        
        /** direct */
        public SendSuccessJob(RouterContext enclosingContext, RouterInfo peer) {
            this(enclosingContext, peer, null, 0);
        }

        /** through tunnel */
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
                if (_log.shouldDebug())
                    _log.debug("sent a " + _msgSize + " byte netDb message through tunnel " + _sendThrough + " after " + howLong);
                for (int i = 0; i < _sendThrough.getLength(); i++)
                    getContext().profileManager().tunnelDataPushed(_sendThrough.getPeer(i), howLong, _msgSize);
                _sendThrough.incrementVerifiedBytesTransferred(_msgSize);
            }
            if (_sendThrough == null) {
                // advise comm system, to reduce lifetime of direct connections to floodfills
                getContext().commSystem().mayDisconnect(_peer.getHash());
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
        private final RouterInfo _peer;
        private final long _sendOn;

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
            getContext().statManager().addRateData("netDb.replyTimeout", getContext().clock().now() - _sendOn);
            
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
