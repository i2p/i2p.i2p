package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Serializable;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.kademlia.KBucketSet;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.LeaseSetKeys;
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
abstract class StoreJob extends JobImpl {
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
        _peerSelector = facade.createPeerSelector();
        if (data.isLeaseSet()) {
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
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": New store job (dbid: "
                       + _facade._dbid + ") for\n"
                       + data, new Exception("I did it"));
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
        } else if (_state.getAttemptedCount() > MAX_PEERS_SENT) {
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
        int toCheck = getParallelization() - _state.getPendingCount();
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
            if (_state.getPendingCount() <= 0) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("JobId: " + getJobId()
                              + " (dbid: " + _facade._dbid
                              + "): No more peers left and none pending");
                fail();
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": No more peers left but some are pending, so keep waiting");
                return;
            }
        } else {
            int queued = 0;
            int skipped = 0;
            int type = _state.getData().getType();
            final boolean isls = DatabaseEntry.isLeaseSet(type);
            final boolean isls2 = isls && type != DatabaseEntry.KEY_TYPE_LEASESET;
            final SigType lsSigType = (isls && type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) ?
                                      _state.getData().getKeysAndCert().getSigningPublicKey().getType() :
                                      null;
            for (Hash peer : closestHashes) {
                DatabaseEntry ds;
                if (_facade.isClientDb())
                    ds = getContext().netDb().getDataStore().get(peer);
                else
                    ds = _facade.getDataStore().get(peer);
                if ( (ds == null) || !(ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) ) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("JobId: " + getJobId()
                                  + " (for dbid: " + _facade._dbid
                                  + "): Error selecting closest hash that wasnt a router! " + peer + " : " + ds);
                    _state.addSkipped(peer);
                    skipped++;
                } else if (!shouldStoreTo((RouterInfo)ds)) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": Skipping old router " + peer);
                    _state.addSkipped(peer);
                    skipped++;
                } else if ((type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2 ||
                            lsSigType == SigType.RedDSA_SHA512_Ed25519) &&
                           !shouldStoreEncLS2To((RouterInfo)ds)) {
                    if (_log.shouldInfo())
                        _log.info(getJobId() + ": Skipping router that doesn't support EncLS2/RedDSA " + peer);
                    _state.addSkipped(peer);
                    skipped++;
/*
                // same as shouldStoreTo() now
                } else if (isls2 &&
                           !shouldStoreLS2To((RouterInfo)ds)) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getJobId() + ": Skipping router that doesn't support LS2 " + peer);
                    _state.addSkipped(peer);
                    skipped++;
*/
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
                           _log.info(getJobId() + "(dbid: " + _facade._dbid
                                     + "): Continue sending key " + _state.getTarget()
                                     + " after " + _state.getAttemptedCount() + " tries to " + closestHashes);
                        _state.addPending(peer);
                        sendStore((RouterInfo)ds, peerTimeout);
                        queued++;
                    //}
                }
            }
            if (queued == 0 && _state.getPendingCount() <= 0) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + "(dbid: " + _facade._dbid
                              + "): No more peers left after skipping " + skipped + " and none pending");
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
        List<Hash> rv;
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(key);
        KBucketSet<Hash> ks = _facade.getKBuckets();
        if (ks == null) return new ArrayList<Hash>();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + "(dbid: " + _facade._dbid + "): Selecting Floodfill Participants");
        if (_facade.isClientDb()) {
            FloodfillPeerSelector ffNetDbPS = (FloodfillPeerSelector)getContext().netDb().getPeerSelector();
            rv = ffNetDbPS.selectFloodfillParticipants(rkey, numClosest, alreadyChecked, ks);
        } else {
            rv = ((FloodfillPeerSelector)_peerSelector).selectFloodfillParticipants(rkey, numClosest, alreadyChecked, ks);
        }
        return rv;
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
        if (router.getIdentity().equals(getContext().router().getRouterInfo().getIdentity())) {
            // don't send it to ourselves
            _log.error(getJobId() + ": Dont send store to ourselves - why did we try?");
            return;
        }

        DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
        int type = _state.getData().getType();
        if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            if (responseTime > MAX_DIRECT_EXPIRATION)
                responseTime = MAX_DIRECT_EXPIRATION;
        } else if (!DatabaseEntry.isLeaseSet(type)) {
            throw new IllegalArgumentException("Storing an unknown data type! " + _state.getData());
        }
        msg.setEntry(_state.getData());
        long now = getContext().clock().now();
        msg.setMessageExpiration(now + _timeoutMs);

        long token = 1 + getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        msg.setReplyToken(token);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send(dbStore) w/ token expected " + msg.getReplyToken() + " msg exp. " + _timeoutMs + " resp exp. " + responseTime);
        sendStore(msg, router, now + responseTime);
    }
    
    /**
     * Send a store to the given peer, including a reply 
     * DeliveryStatusMessage so we know it got there
     *
     */
    private void sendStore(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        RouterContext ctx = getContext();
        if (msg.getEntry().isLeaseSet()) {
            ctx.statManager().addRateData("netDb.storeLeaseSetSent", 1);
            // if it is an encrypted leaseset...
            if (_facade.isClientDb())
                sendStoreThroughClient(msg, peer, expiration);
            else if (ctx.keyRing().get(msg.getKey()) != null)
                sendStoreThroughExploratory(msg, peer, expiration);
            else if (msg.getEntry().getType() == DatabaseEntry.KEY_TYPE_META_LS2)
                sendWrappedStoreThroughExploratory(msg, peer, expiration);
            else
                sendStoreThroughClient(msg, peer, expiration);
        } else {
            ctx.statManager().addRateData("netDb.storeRouterInfoSent", 1);
            // Special handling if we're in the client context.
            // Otherwise, if we can't connect to peer directly,
            // just send it out an exploratory tunnel
            Hash h = peer.getIdentity().getHash();
            if (_facade.isClientDb()) {
                // If we're in the client netDb context, sending RI
                // should be rare.  Perhaps even forbidden.
                // For now, send it out through exploratory tunnels,
                // and issue a warning.
                sendStoreThroughExploratory(msg, peer, expiration);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("[JobId: " + getJobId() + "; dbid: " + _facade._dbid
                          +"]: Sending RI store (though exploratory tunnels) in a client context to "
                          + peer.getIdentity().getHash() + " with message " + msg);
            }
            else if (ctx.commSystem().isEstablished(h) ||
                (!ctx.commSystem().wasUnreachable(h) && _connectChecker.canConnect(_connectMask, peer)))
                sendDirect(msg, peer, expiration);
            else
                sendStoreThroughExploratory(msg, peer, expiration);
        }
    }

    /**
     * Send directly,
     * with the reply to come back directly.
     *
     */
    private void sendDirect(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        if (_facade.isClientDb()) {
            _log.error("Error! Direct DatabaseStoreMessage attempted in client context! Message: " + msg);
            return;
        }

        msg.setReplyGateway(getContext().routerHash());

        Hash to = peer.getIdentity().getHash();
        _state.addPending(to);
        
        SendSuccessJob onReply = new SendSuccessJob(getContext(), peer);
        FailedJob onFail = new FailedJob(getContext(), peer, getContext().clock().now());
        StoreMessageSelector selector = new StoreMessageSelector(getContext(), getJobId(), peer, msg.getReplyToken(), expiration);

        // ToDo: Craft a nasty error message here if the _facade is not the floodfill facade.
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": sending store directly to " + to);
        OutNetMessage m = new OutNetMessage(getContext(), msg, expiration, STORE_PRIORITY, peer);
        m.setOnFailedReplyJob(onFail);
        m.setOnFailedSendJob(onFail);
        m.setOnReplyJob(onReply);
        m.setReplySelector(selector);
        getContext().messageRegistry().registerPending(m);
        getContext().commSystem().processMessage(m);
    }
    
    /**
     * Send it out through an exploratory tunnel,
     * with the reply to come back through an exploratory tunnel.
     * There is no garlic encryption added.
     *
     * @since 0.9.41 renamed from sendStoreThroughGarlic()
     */
    private void sendStoreThroughExploratory(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        Hash to = peer.getIdentity().getHash();
        // For all tunnel selections, the first time we pick the tunnel with the far-end closest
        // to the target. After that we pick a random tunnel, or else we'd pick the
        // same tunnels every time.
        TunnelInfo replyTunnel;
        if (_state.getAttemptedCount() <= 1)
            replyTunnel = getContext().tunnelManager().selectInboundExploratoryTunnel(to);
        else
            replyTunnel = getContext().tunnelManager().selectInboundTunnel();
        if (replyTunnel == null) {
            _log.warn("No reply inbound tunnels available!");
            return;
        }
        TunnelId replyTunnelId = replyTunnel.getReceiveTunnelId(0);
        msg.setReplyTunnel(replyTunnelId);
        msg.setReplyGateway(replyTunnel.getPeer(0));

        _state.addPending(to);
        
        TunnelInfo outTunnel;
        if (_state.getAttemptedCount() <= 1)
            outTunnel = getContext().tunnelManager().selectOutboundExploratoryTunnel(to);
        else
            outTunnel = getContext().tunnelManager().selectOutboundTunnel();
        if (outTunnel != null) {
            SendSuccessJob onReply = new SendSuccessJob(getContext(), peer, outTunnel, msg.getMessageSize());
            FailedJob onFail = new FailedJob(getContext(), peer, getContext().clock().now());
            StoreMessageSelector selector = new StoreMessageSelector(getContext(), getJobId(), peer, msg.getReplyToken(), expiration);
    
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": sending store to " + to + " through " + outTunnel + ": " + msg);
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
        final RouterContext ctx = getContext();
        Hash client;
        int dstype = msg.getEntry().getType();
        if (dstype == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            // get the real client hash
            client = ((LeaseSet)msg.getEntry()).getDestination().calculateHash();
        } else {
            client = msg.getKey();
        }

        RouterIdentity ident = peer.getIdentity();
        Hash to = ident.getHash();
        // see comments in method above
        Hash replyGW;
        TunnelId replyTunnelId;
        if (dstype == DatabaseEntry.KEY_TYPE_LS2 || dstype == DatabaseEntry.KEY_TYPE_LEASESET) {
            // always pick the reply tunnel from the LS, they will be the newest,
            // probably still connected,
            // and it gives the ff flexibility to choose another one
            LeaseSet ls = (LeaseSet) msg.getEntry();
            Lease lease = pickReplyTunnel(ls, _state.getAttemptedCount(), to);
            replyGW = lease.getGateway();
            replyTunnelId = lease.getTunnelId();
        } else {
            TunnelInfo replyTunnel;
            if (_state.getAttemptedCount() <= 1)
                replyTunnel = ctx.tunnelManager().selectInboundTunnel(client, to);
            else
                replyTunnel = ctx.tunnelManager().selectInboundTunnel(client);
            if (replyTunnel == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("No reply inbound tunnels available!");
                fail();
                return;
            }
            replyTunnelId = replyTunnel.getReceiveTunnelId(0);
            replyGW = replyTunnel.getPeer(0);
        }
        msg.setReplyTunnel(replyTunnelId);
        msg.setReplyGateway(replyGW);

        TunnelInfo outTunnel;
        if (_state.getAttemptedCount() <= 1)
            outTunnel = ctx.tunnelManager().selectOutboundTunnel(client, to);
        else
            outTunnel = ctx.tunnelManager().selectOutboundTunnel(client);
        if (outTunnel != null) {
            I2NPMessage sent;
            LeaseSetKeys lsk = ctx.keyManager().getKeys(client);
            EncType type = ident.getPublicKey().getType();
            if (type == EncType.ELGAMAL_2048 &&
                (lsk == null || lsk.isSupported(EncType.ELGAMAL_2048))) {
                // garlic encrypt
                MessageWrapper.WrappedMessage wm = MessageWrapper.wrap(ctx, msg, client, peer);
                if (wm == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Fail garlic encrypting from: " + client);
                    fail();
                    return;
                }
                sent = wm.getMessage();
                _state.addPending(to, wm);
            } else if (type == EncType.ECIES_X25519 ||
                       lsk.isSupported(EncType.ECIES_X25519)) {
                // force full ElG for ECIES-only
                sent = MessageWrapper.wrap(ctx, msg, peer);
                if (sent == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Fail garlic encrypting from: " + client);
                    fail();
                    return;
                }
                _state.addPending(to);
            } else {
                // Above are the only two enc types for now, won't get here.
                // Send it unencrypted.
                sent = msg;
                _state.addPending(to);
            }
            SendSuccessJob onReply = new SendSuccessJob(ctx, peer, outTunnel, sent.getMessageSize());
            FailedJob onFail = new FailedJob(ctx, peer, ctx.clock().now());
            StoreMessageSelector selector = new StoreMessageSelector(ctx, getJobId(), peer, msg.getReplyToken(), expiration);
    
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(getJobId() + "(dbid: " + _facade._dbid
                           + "): sending encrypted store through client tunnel to " + to
                           + " through " + outTunnel + ": " + sent + " with reply to "
                           + replyGW + ' ' + replyTunnelId);
            }
            ctx.messageRegistry().registerPending(selector, onReply, onFail);
            ctx.tunnelDispatcher().dispatchOutbound(sent, outTunnel.getSendTunnelId(0), null, to);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnels to send a dbStore out - delaying...");
            // continueSending() above did an addPending() so remove it here.
            // This means we will skip the peer next time, can't be helped for now
            // without modding StoreState
            _state.replyTimeout(to);
            Job waiter = new WaitJob(ctx);
            waiter.getTiming().setStartAfter(ctx.clock().now() + 3*1000);
            ctx.jobQueue().addJob(waiter);
            //fail();
        }
    }
    
    /**
     * Pick a reply tunnel out of a LeaseSet
     *
     * @param to pick closest if attempts == 1
     * @since 0.9.53
     */
    private Lease pickReplyTunnel(LeaseSet ls, int attempts, Hash to) {
        int c = ls.getLeaseCount();
        if (c <= 0)
            throw new IllegalStateException();
        if (c == 1)
            return ls.getLease(0);
        if (attempts > 1)
            return ls.getLease(getContext().random().nextInt(c));
        // pick closest
        Lease[] leases = new Lease[c];
        for (int i = 0; i < c; i++) {
            leases[i] = ls.getLease(i);
        }
        Arrays.sort(leases, new LeaseComparator(to));
        return leases[0];
    }

    /**
     * Find the lease that is XOR-closest to a given hash
     *
     * @since 0.9.53 adapted from TunnelPool.TunnelInfoComparator
     */
    private static class LeaseComparator implements Comparator<Lease>, Serializable {
        private final byte[] _base;

        /**
         * @param target key to compare distances with
         */
        public LeaseComparator(Hash target) {
            _base = target.getData();
        }

        public int compare(Lease lhs, Lease rhs) {
            byte lhsb[] = lhs.getGateway().getData();
            byte rhsb[] = rhs.getGateway().getData();
            for (int i = 0; i < _base.length; i++) {
                int ld = (lhsb[i] ^ _base[i]) & 0xff;
                int rd = (rhsb[i] ^ _base[i]) & 0xff;
                if (ld < rd)
                    return -1;
                if (ld > rd)
                    return 1;
            }
            // latest-expiring first as a tie-breaker
            return (int) (rhs.getEndTime() - lhs.getEndTime());
        }
    }

    /**
     * Send a leaseset store message out an exploratory tunnel,
     * with the reply to come back through a exploratory tunnel.
     * Stores are garlic encrypted to hide the identity from the OBEP.
     *
     * Only for Meta LS2, for now.
     *
     * @param msg must contain a leaseset
     * @since 0.9.41
     */
    private void sendWrappedStoreThroughExploratory(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
        final RouterContext ctx = getContext();
        Hash to = peer.getIdentity().getHash();
        // see comments in method above
        TunnelInfo replyTunnel;
        if (_state.getAttemptedCount() <= 1)
            replyTunnel = ctx.tunnelManager().selectInboundExploratoryTunnel(to);
        else
            replyTunnel = ctx.tunnelManager().selectInboundTunnel();
        if (replyTunnel == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No inbound expl. tunnels for reply - delaying...");
            // continueSending() above did an addPending() so remove it here.
            // This means we will skip the peer next time, can't be helped for now
            // without modding StoreState
            _state.replyTimeout(to);
            Job waiter = new WaitJob(ctx);
            waiter.getTiming().setStartAfter(ctx.clock().now() + 3*1000);
            ctx.jobQueue().addJob(waiter);
            return;
        }
        TunnelId replyTunnelId = replyTunnel.getReceiveTunnelId(0);
        msg.setReplyTunnel(replyTunnelId);
        msg.setReplyGateway(replyTunnel.getPeer(0));

        TunnelInfo outTunnel;
        if (_state.getAttemptedCount() <= 1)
            outTunnel = ctx.tunnelManager().selectOutboundExploratoryTunnel(to);
        else
            outTunnel = ctx.tunnelManager().selectOutboundTunnel();
        if (outTunnel != null) {
            I2NPMessage sent;
            // garlic encrypt using router SKM
            EncType ptype = peer.getIdentity().getPublicKey().getType();
            EncType mtype = ctx.keyManager().getPublicKey().getType();
            if (ptype == EncType.ELGAMAL_2048 && mtype == EncType.ELGAMAL_2048) {
                MessageWrapper.WrappedMessage wm = MessageWrapper.wrap(ctx, msg, null, peer);
                if (wm == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Fail garlic encrypting");
                    fail();
                    return;
                }
                sent = wm.getMessage();
                _state.addPending(to, wm);
            } else {
                sent = MessageWrapper.wrap(ctx, msg, peer);
                _state.addPending(to);
            }
            SendSuccessJob onReply = new SendSuccessJob(ctx, peer, outTunnel, sent.getMessageSize());
            FailedJob onFail = new FailedJob(ctx, peer, ctx.clock().now());
            StoreMessageSelector selector = new StoreMessageSelector(ctx, getJobId(), peer, msg.getReplyToken(), expiration);
    
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(getJobId() + ": sending encrypted store to " + to + " through " + outTunnel + ": " + sent);
            }
            ctx.messageRegistry().registerPending(selector, onReply, onFail);
            ctx.tunnelDispatcher().dispatchOutbound(sent, outTunnel.getSendTunnelId(0), null, to);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound expl. tunnels to send a dbStore out - delaying...");
            // continueSending() above did an addPending() so remove it here.
            // This means we will skip the peer next time, can't be helped for now
            // without modding StoreState
            _state.replyTimeout(to);
            Job waiter = new WaitJob(ctx);
            waiter.getTiming().setStartAfter(ctx.clock().now() + 3*1000);
            ctx.jobQueue().addJob(waiter);
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

    /**
     * Short ECIES tunnel build messages (1.5.0)
     * @since 0.9.28
     */
    public static final String MIN_STORE_VERSION = "0.9.51";

    /**
     * Is it new enough?
     * @since 0.9.33
     */
    static boolean shouldStoreTo(RouterInfo ri) {
        String v = ri.getVersion();
        if (VersionComparator.comp(v, MIN_STORE_VERSION) < 0)
            return false;
        RouterIdentity ident = ri.getIdentity();
        if (ident.getSigningPublicKey().getType() == SigType.DSA_SHA1)
            return false;
        EncType type = ident.getPublicKey().getType();
        if (DatabaseLookupMessage.USE_ECIES_FF)
            return LeaseSetKeys.SET_BOTH.contains(type);
        return type == EncType.ELGAMAL_2048;
    }

    /** @since 0.9.38 */
    public static final String MIN_STORE_LS2_VERSION = "0.9.51";

    /**
     * Is it new enough?
     * @since 0.9.38
     */
    static boolean shouldStoreLS2To(RouterInfo ri) {
        String v = ri.getVersion();
        return VersionComparator.comp(v, MIN_STORE_LS2_VERSION) >= 0;
    }

    /**
     * Was supported in 38, but they're now sigtype 11 which wasn't added until 39
     * @since 0.9.39
     */
    public static final String MIN_STORE_ENCLS2_VERSION = "0.9.51";

    /**
     * Is it new enough?
     * @since 0.9.39
     */
    static boolean shouldStoreEncLS2To(RouterInfo ri) {
        String v = ri.getVersion();
        return VersionComparator.comp(v, MIN_STORE_ENCLS2_VERSION) >= 0;
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
                    _log.debug(StoreJob.this.getJobId() + ": sent a " + _msgSize + " byte netDb message through tunnel " + _sendThrough + " after " + howLong);
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
        private final AtomicBoolean _wasRun = new AtomicBoolean();

        public FailedJob(RouterContext enclosingContext, RouterInfo peer, long sendOn) {
            super(enclosingContext);
            _peer = peer;
            _sendOn = sendOn;
        }
        public void runJob() {
            if (!_wasRun.compareAndSet(false, true))
                return;
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
        // logged in subclass
        //if (_log.shouldInfo()) {
        //    _log.info(getJobId() + ": Succeeded sending key " + _state.getTarget());
            if (_log.shouldDebug())
                _log.debug(getJobId() + ": State of successful send: " + _state);
        //}
        if (_onSuccess != null)
            getContext().jobQueue().addJob(_onSuccess);
        _state.complete(true);
        getContext().statManager().addRateData("netDb.storePeers", _state.getAttemptedCount());
    }

    /**
     * Send totally failed
     */
    protected void fail() {
        if (_log.shouldInfo()) {
            _log.info(getJobId() + ": Failed sending key " + _state.getTarget());
            if (_log.shouldDebug())
                _log.debug(getJobId() + ": State of failed send: " + _state, new Exception("Who failed me?"));
        }
        if (_onFailure != null)
            getContext().jobQueue().addJob(_onFailure);
        _state.complete(true);
        getContext().statManager().addRateData("netDb.storeFailedPeers", _state.getAttemptedCount());
    }
}
