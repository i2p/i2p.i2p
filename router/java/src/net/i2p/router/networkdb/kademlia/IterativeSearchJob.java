package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.router.util.RandomIterator;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * A traditional Kademlia search that continues to search
 * when the initial lookup fails, by iteratively searching the
 * closer-to-the-key peers returned by the query in a DSRM.
 *
 * Unlike traditional Kad, it doesn't stop when there are no
 * closer keys, it keeps going until the timeout or max number
 * of searches is reached.
 *
 * Differences from FloodOnlySearchJob:
 * Chases peers in DSRM's immediately.
 * FOSJ searches the two closest in parallel and then stops.
 * There is no per-search timeout, only a total timeout.
 * Here, we search one at a time, and must have a separate per-search timeout.
 *
 * Advantages: Much more robust than FOSJ, especially in a large network
 * where not all floodfills are known. Longer total timeout.
 * Halves search traffic for successful searches, as this doesn't do
 * two sesarches in parallel like FOSJ does.
 *
 * Public only for JobQueue, not a public API, not for external use.
 *
 * @since 0.8.9
 */
public class IterativeSearchJob extends FloodSearchJob {
    /** peers not sent to yet, sorted closest-to-the-routing-key */
    private final SortedSet<Hash> _toTry;
    /** query sent, no reply yet */
    private final Set<Hash> _unheardFrom;
    /** query sent, failed, timed out, or got DSRM */
    private final Set<Hash> _failedPeers;
    /** the time the query was sent to a peer, which we need to update profiles correctly */
    private final Map<Hash, Long> _sentTime;
    /** the routing key */
    private final Hash _rkey;
    /** this is a marker to register with the MessageRegistry, it is never sent */
    private OutNetMessage _out;
    private final Hash _fromLocalDest;
    /** testing */
    private static Hash _alwaysQueryHash;
    /** Max number of peers to query */
    private final int _totalSearchLimit;
    private final MaskedIPSet _ipSet;
    private final Set<Hash> _skippedPeers;
    
    private static final int MAX_NON_FF = 3;
    /** Max number of peers to query */
    private static final int TOTAL_SEARCH_LIMIT = 5;
    /** Max number of peers to query if we are ff */
    private static final int TOTAL_SEARCH_LIMIT_WHEN_FF = 3;
    /** Extra peers to get from peer selector, as we may discard some before querying */
    private static final int EXTRA_PEERS = 1;
    private static final int IP_CLOSE_BYTES = 3;
    /** TOTAL_SEARCH_LIMIT * SINGLE_SEARCH_TIME, plus some extra */
    private static final int MAX_SEARCH_TIME = 30*1000;
    /**
     *  The time before we give up and start a new search - much shorter than the message's expire time
     *  Longer than the typ. response time of 1.0 - 1.5 sec, but short enough that we move
     *  on to another peer quickly.
     */
    private final long _singleSearchTime;
    /** 
     * The default single search time
     */
    private static final long SINGLE_SEARCH_TIME = 3*1000;
    /** the actual expire time for a search message */
    private static final long SINGLE_SEARCH_MSG_TIME = 10*1000;
    /**
     *  Use instead of CONCURRENT_SEARCHES in super() which is final.
     *  For now, we don't do concurrent, but we keep SINGLE_SEARCH_TIME very short,
     *  so we have effective concurrency in that we fail a search quickly.
     */
    private final int _maxConcurrent;
    /**
     * The default _maxConcurrent
     */
    private static final int MAX_CONCURRENT = 1;

    public static final String PROP_ENCRYPT_RI = "router.encryptRouterLookups";

    /** only on fast boxes, for now */
    public static final boolean DEFAULT_ENCRYPT_RI =
            SystemVersion.isX86() && /* SystemVersion.is64Bit() && */
            !SystemVersion.isApache() && !SystemVersion.isGNU() &&
            NativeBigInteger.isNative();

    //private static final String MIN_QUERY_VERSION = SigType.EdDSA_SHA512_Ed25519.getSupportedSince();
    private static final String MIN_QUERY_VERSION = StoreJob.MIN_STORE_VERSION;

    /**
     *  Lookup using exploratory tunnels
     */
    public IterativeSearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key,
                              Job onFind, Job onFailed, int timeoutMs, boolean isLease) {
        this(ctx, facade, key, onFind, onFailed, timeoutMs, isLease, null);
    }

    /**
     *  Lookup using the client's tunnels.
     *  Do not use for RI lookups down client tunnels,
     *  as the response will be dropped in InboundMessageDistributor.
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.10
     */
    public IterativeSearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key,
                              Job onFind, Job onFailed, int timeoutMs, boolean isLease, Hash fromLocalDest) {
        super(ctx, facade, key, onFind, onFailed, timeoutMs, isLease);
        // these override the settings in super
        _timeoutMs = Math.min(timeoutMs, MAX_SEARCH_TIME);
        _expiration = _timeoutMs + ctx.clock().now();
        _rkey = ctx.routingKeyGenerator().getRoutingKey(key);
        _toTry = new TreeSet<Hash>(new XORComparator<Hash>(_rkey));
        int totalSearchLimit = (facade.floodfillEnabled() && ctx.router().getUptime() > 30*60*1000) ?
                            TOTAL_SEARCH_LIMIT_WHEN_FF : TOTAL_SEARCH_LIMIT;
        _totalSearchLimit = ctx.getProperty("netdb.searchLimit", totalSearchLimit);
        _ipSet = new MaskedIPSet(2 * (_totalSearchLimit + EXTRA_PEERS));
        _singleSearchTime = ctx.getProperty("netdb.singleSearchTime", SINGLE_SEARCH_TIME);
        _maxConcurrent = ctx.getProperty("netdb.maxConcurrent", MAX_CONCURRENT);
        _unheardFrom = new HashSet<Hash>(CONCURRENT_SEARCHES);
        _failedPeers = new HashSet<Hash>(_totalSearchLimit);
        _skippedPeers = new HashSet<Hash>(4);
        _sentTime = new ConcurrentHashMap<Hash, Long>(_totalSearchLimit);
        _fromLocalDest = fromLocalDest;
        if (fromLocalDest != null && !isLease && _log.shouldLog(Log.WARN))
            _log.warn("Search for RI " + key + " down client tunnel " + fromLocalDest, new Exception());
        // all createRateStat in FNDF
    }

    @Override
    public void runJob() {
        if (_facade.isNegativeCached(_key)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Negative cached, not searching: " + _key);
            failed();
            return;
        }
        // pick some floodfill peers and send out the searches
        List<Hash> floodfillPeers;
        KBucketSet<Hash> ks = _facade.getKBuckets();
        if (ks != null) {
            // Ideally we would add the key to an exclude list, so we don't try to query a ff peer for itself,
            // but we're passing the rkey not the key, so we do it below instead in certain cases.
            floodfillPeers = ((FloodfillPeerSelector)_facade.getPeerSelector()).selectFloodfillParticipants(_rkey, _totalSearchLimit + EXTRA_PEERS, ks);
        } else {
            floodfillPeers = new ArrayList<Hash>(_totalSearchLimit);
        }

        // For testing or local networks... we will
        // pretend that the specified router is floodfill, and always closest-to-the-key.
        // May be set after startup but can't be changed or unset later.
        // Warning - experts only!
        String alwaysQuery = getContext().getProperty("netDb.alwaysQuery");
        if (alwaysQuery != null) {
            if (_alwaysQueryHash == null) {
                byte[] b = Base64.decode(alwaysQuery);
                if (b != null && b.length == Hash.HASH_LENGTH)
                    _alwaysQueryHash = Hash.create(b);
            }
        }

        if (floodfillPeers.isEmpty()) {
            // ask anybody, they may not return the answer but they will return a few ff peers we can go look up,
            // so this situation should be temporary
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running netDb searches against the floodfill peers, but we don't know any");
            List<Hash> all = new ArrayList<Hash>(_facade.getAllRouters());
            if (all.isEmpty()) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("We don't know any peers at all");
                failed();
                return;
            }
            Iterator<Hash> iter = new RandomIterator<Hash>(all);
            // Limit non-FF to 3, because we don't sort the FFs ahead of the non-FFS,
            // so once we get some FFs we want to be sure to query them
            for (int i = 0; iter.hasNext() && i < MAX_NON_FF; i++) {
                floodfillPeers.add(iter.next());
            }
        }
        final boolean empty;
        // outside sync to avoid deadlock
        final Hash us = getContext().routerHash();
        synchronized(this) {
            _toTry.addAll(floodfillPeers);
            // don't ask ourselves or the target
            _toTry.remove(us);
            _toTry.remove(_key);
            empty = _toTry.isEmpty();
        }
        if (empty) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": ISJ for " + _key + " had no peers to send to");
            // no floodfill peers, fail
            failed();
            return;
        }
        // This OutNetMessage is never used or sent (setMessage() is never called), it's only
        // so we can register a reply selector.
        MessageSelector replySelector = new IterativeLookupSelector(getContext(), this);
        ReplyJob onReply = new FloodOnlyLookupMatchJob(getContext(), this);
        Job onTimeout = new FloodOnlyLookupTimeoutJob(getContext(), this);
        _out = getContext().messageRegistry().registerPending(replySelector, onReply, onTimeout);
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": New ISJ for " +
                      (_isLease ? "LS " : "RI ") +
                      _key + " (rkey " + _rkey + ") timeout " +
                      DataHelper.formatDuration(_timeoutMs) + " toTry: "  + DataHelper.toString(_toTry));
        retry();
    }

    /**
     *  Send lookups to one or more peers, up to the configured concurrent and total limits
     */
    private void retry() {
        long now = getContext().clock().now();
        if (_expiration < now) {
            failed();
            return;
        }
        if (_expiration - 500 < now)  {
            // not enough time left to bother
            return;
        }
        while (true) {
            Hash peer = null;
            final int done, pend;
            synchronized (this) {
                if (_dead) return;
                pend = _unheardFrom.size();
                if (pend >= _maxConcurrent)
                    return;
                done = _failedPeers.size();
            }
            if (done >= _totalSearchLimit) {
                failed();
                return;
            }
            // even if pend and todo are empty, we don't fail, as there may be more peers
            // coming via newPeerToTry()
            if (done + pend >= _totalSearchLimit)
                return;
            synchronized(this) {
                if (_alwaysQueryHash != null &&
                        !_unheardFrom.contains(_alwaysQueryHash) &&
                        !_failedPeers.contains(_alwaysQueryHash)) {
                    // For testing or local networks... we will
                    // pretend that the specified router is floodfill, and always closest-to-the-key.
                    // May be set after startup but can't be changed or unset later.
                    // Warning - experts only!
                    peer = _alwaysQueryHash;
                } else {
                    if (_toTry.isEmpty())
                        return;
                    for (Iterator<Hash> iter = _toTry.iterator(); iter.hasNext(); ) {
                        Hash h = iter.next();
                        iter.remove();
                        Set<String> peerIPs = new MaskedIPSet(getContext(), h, IP_CLOSE_BYTES);
                        if (!_ipSet.containsAny(peerIPs)) {
                            _ipSet.addAll(peerIPs);
                            peer = h;
                            break;
                        }
                        if (_log.shouldLog(Log.INFO))
                            _log.info(getJobId() + ": Skipping query w/ router too close to others " + h);
                        _skippedPeers.add(h);
                        // go around again
                    }
                    if (peer == null)
                        return;
                }
                _unheardFrom.add(peer);
            }
            sendQuery(peer);
        }
    }

    /**
     *  Send a DLM to the peer
     */
    private void sendQuery(Hash peer) {
            TunnelManagerFacade tm = getContext().tunnelManager();
            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(peer);
            if (ri != null) {
                // Now that most of the netdb is Ed RIs and EC LSs, don't even bother
                // querying old floodfills that don't know about those sig types.
                // This is also more recent than the version that supports encrypted replies,
                // so we won't request unencrypted replies anymore either.
                String v = ri.getVersion();
                String since = MIN_QUERY_VERSION;
                if (VersionComparator.comp(v, since) < 0) {
                    failed(peer, false);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getJobId() + ": not sending query to old version " + v + ": " + peer);
                    return;
                }
            }
            TunnelInfo outTunnel;
            TunnelInfo replyTunnel;
            boolean isClientReplyTunnel;
            boolean isDirect;
            if (_fromLocalDest != null) {
                outTunnel = tm.selectOutboundTunnel(_fromLocalDest, peer);
                if (outTunnel == null)
                    outTunnel = tm.selectOutboundExploratoryTunnel(peer);
                replyTunnel = tm.selectInboundTunnel(_fromLocalDest, peer);
                isClientReplyTunnel = replyTunnel != null;
                if (!isClientReplyTunnel)
                    replyTunnel = tm.selectInboundExploratoryTunnel(peer);
                isDirect = false;
            } else if ((!_isLease) && ri != null && getContext().commSystem().isEstablished(peer)) {
                // If it's a RI lookup, not from a client, and we're already connected, just ask directly
                // This also saves the ElG encryption for us and the decryption for the ff
                // There's no anonymity reason to use an expl. tunnel... the main reason
                // is to limit connections to the ffs. But if we're already connected,
                // do it the fast and easy way.
                outTunnel = null;
                replyTunnel = null;
                isClientReplyTunnel = false;
                isDirect = true;
                getContext().statManager().addRateData("netDb.RILookupDirect", 1);
            } else {
                outTunnel = tm.selectOutboundExploratoryTunnel(peer);
                replyTunnel = tm.selectInboundExploratoryTunnel(peer);
                isClientReplyTunnel = false;
                isDirect = false;
                getContext().statManager().addRateData("netDb.RILookupDirect", 0);
            }
            if ((!isDirect) && (replyTunnel == null || outTunnel == null)) {
                failed();
                return;
            }

            // As explained above, it's hard to keep the key itself out of the ff list,
            // so let's just skip it for now if the outbound tunnel is zero-hop.
            // Yes, that means we aren't doing double-lookup for a floodfill
            // if it happens to be closest to itself and we are using zero-hop exploratory tunnels.
            // If we don't, the OutboundMessageDistributor ends up logging erors for
            // not being able to send to the floodfill, if we don't have an older netdb entry.
            if (outTunnel != null && outTunnel.getLength() <= 1) {
                if (peer.equals(_key)) {
                    failed(peer, false);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getJobId() + ": not doing zero-hop self-lookup of " + peer);
                    return;
                }
                if (_facade.lookupLocallyWithoutValidation(peer) == null) {
                    failed(peer, false);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getJobId() + ": not doing zero-hop lookup to unknown " + peer);
                    return;
                }
            }
            
            DatabaseLookupMessage dlm = new DatabaseLookupMessage(getContext(), true);
            if (isDirect) {
                dlm.setFrom(getContext().routerHash());
            } else {
                dlm.setFrom(replyTunnel.getPeer(0));
                dlm.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
            }
            dlm.setMessageExpiration(getContext().clock().now() + SINGLE_SEARCH_MSG_TIME);
            dlm.setSearchKey(_key);
            dlm.setSearchType(_isLease ? DatabaseLookupMessage.Type.LS : DatabaseLookupMessage.Type.RI);
            
            if (_log.shouldLog(Log.INFO)) {
                int tries;
                synchronized(this) {
                    tries = _unheardFrom.size() + _failedPeers.size();
                }
                _log.info(getJobId() + ": ISJ try " + tries + " for " +
                          (_isLease ? "LS " : "RI ") +
                          _key + " to " + peer +
                          " direct? " + isDirect +
                          " reply via client tunnel? " + isClientReplyTunnel);
            }
            long now = getContext().clock().now();
            _sentTime.put(peer, Long.valueOf(now));

            I2NPMessage outMsg = null;
            if (isDirect) {
                // never wrap
            } else if (_isLease ||
                       (getContext().getProperty(PROP_ENCRYPT_RI, DEFAULT_ENCRYPT_RI) &&
                        getContext().jobQueue().getMaxLag() < 300)) {
                // Full ElG is fairly expensive so only do it for LS lookups
                // and for RI lookups on fast boxes.
                // if we have the ff RI, garlic encrypt it
                if (ri != null) {
                    // request encrypted reply
                    // now covered by version check above, which is more recent
                    //if (DatabaseLookupMessage.supportsEncryptedReplies(ri)) {
                    if (true) {
                        MessageWrapper.OneTimeSession sess;
                        if (isClientReplyTunnel)
                            sess = MessageWrapper.generateSession(getContext(), _fromLocalDest);
                        else
                            sess = MessageWrapper.generateSession(getContext());
                        if (sess != null) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info(getJobId() + ": Requesting encrypted reply from " + peer + ' ' + sess.key + ' ' + sess.tag);
                            dlm.setReplySession(sess.key, sess.tag);
                        } // else client went away, but send it anyway
                    }
                    outMsg = MessageWrapper.wrap(getContext(), dlm, ri);
                    // ElG can take a while so do a final check before we send it,
                    // a response may have come in.
                    if (_dead) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(getJobId() + ": aborting send, finished while wrapping msg to " + peer);
                        return;
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(getJobId() + ": Encrypted DLM for " + _key + " to " + peer);
                }
            }
            if (outMsg == null)
                outMsg = dlm;
            if (isDirect) {
                OutNetMessage m = new OutNetMessage(getContext(), outMsg, outMsg.getMessageExpiration(),
                                                    OutNetMessage.PRIORITY_MY_NETDB_LOOKUP, ri);
                // Should always succeed, we are connected already
                //m.setOnFailedReplyJob(onFail);
                //m.setOnFailedSendJob(onFail);
                //m.setOnReplyJob(onReply);
                //m.setReplySelector(selector);
                //getContext().messageRegistry().registerPending(m);
                getContext().commSystem().processMessage(m);
            } else {
                getContext().tunnelDispatcher().dispatchOutbound(outMsg, outTunnel.getSendTunnelId(0), peer);
            }

            // The timeout job is always run (never cancelled)
            // Note that the timeout is much shorter than the message expiration (see above)
            Job j = new IterativeTimeoutJob(getContext(), peer, this);
            long expire = Math.min(_expiration, now + _singleSearchTime);
            j.getTiming().setStartAfter(expire);
            getContext().jobQueue().addJob(j);

    }

    @Override
    public String getName() { return "Iterative search"; }
    
    /**
     *  Note that the peer did not respond with a DSM
     *  (either a DSRM, timeout, or failure).
     *  This is not necessarily a total failure of the search.
     *  @param timedOut if true, will blame the peer's profile
     */
    void failed(Hash peer, boolean timedOut) {
        boolean isNewFail;
        synchronized (this) {
            if (_dead) return;
            _unheardFrom.remove(peer);
            isNewFail = _failedPeers.add(peer);
        }
        if (isNewFail) {
            if (timedOut) {
                getContext().profileManager().dbLookupFailed(peer);
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": search timed out to " + peer);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": search failed to " + peer);
            }
        }
        retry();
    }

    /**
     *  A new (floodfill) peer was discovered that may have the answer.
     *  @param peer may not actually be new
     */
    void newPeerToTry(Hash peer) {
        // don't ask ourselves or the target
        if (peer.equals(getContext().routerHash()) ||
            peer.equals(_key))
            return;
        if (getContext().banlist().isBanlistedForever(peer)) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": banlisted peer from DSRM " + peer);
            return;
        }
        RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(peer);
        if (ri != null && !FloodfillNetworkDatabaseFacade.isFloodfill(ri)) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": non-ff peer from DSRM " + peer);
            return;
        }
        synchronized (this) {
            if (_failedPeers.contains(peer) ||
                _unheardFrom.contains(peer) ||
                _skippedPeers.contains(peer))
                return;  // already tried or skipped
            if (!_toTry.add(peer))
                return;  // already in the list
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": new peer from DSRM: known? " + (ri != null) + ' ' + peer);
        retry();
    }

    /**
     *  Hash of the dest this query is from
     *  @return null for router
     *  @since 0.9.13
     */
    public Hash getFromHash() {
        return _fromLocalDest;
    }

    /**
     *  Did we send a request to this peer?
     *  @since 0.9.13
     */
    public boolean wasQueried(Hash peer) {
        synchronized (this) {
            return _unheardFrom.contains(peer) || _failedPeers.contains(peer);
        }
    }

    /**
     *  When did we send the query to the peer?
     *  @return context time, or -1 if never sent
     */
    long timeSent(Hash peer) {
        Long rv = _sentTime.get(peer);
        return rv == null ? -1 : rv.longValue();
    }

    /**
     *  Dropped by the job queue
     *  @since 0.9.31
     */
    @Override
    public void dropped() {
        failed();
    }

    /**
     *  Total failure
     */
    @Override
    void failed() {
        synchronized (this) {
            if (_dead) return;
            _dead = true;
        }
        _facade.complete(_key);
        if (getContext().commSystem().getStatus() != Status.DISCONNECTED)
            _facade.lookupFailed(_key);
        getContext().messageRegistry().unregisterPending(_out);
        int tries;
        final List<Hash> unheard;
        synchronized(this) {
            tries = _unheardFrom.size() + _failedPeers.size();
            unheard = new ArrayList<Hash>(_unheardFrom);
        }
        // blame the unheard-from (others already blamed in failed() above)
        for (Hash h : unheard) {
            getContext().profileManager().dbLookupFailed(h);
        }
        long time = System.currentTimeMillis() - _created;
        if (_log.shouldLog(Log.INFO)) {
            long timeRemaining = _expiration - getContext().clock().now();
            _log.info(getJobId() + ": ISJ for " + _key + " failed with " + timeRemaining + " remaining after " + time +
                      ", peers queried: " + tries);
        }
        if (tries > 0) {
            // don't bias the stats with immediate fails
            getContext().statManager().addRateData("netDb.failedTime", time);
            getContext().statManager().addRateData("netDb.failedRetries", tries - 1);
        }
        for (Job j : _onFailed) {
            getContext().jobQueue().addJob(j);
        }
        _onFailed.clear();
    }

    @Override
    void success() {
        // Sadly, we don't know for sure which one replied.
        // If the reply is after expiration (which moves the hash from _unheardFrom to _failedPeers),
        // we will credit the wrong one.
        int tries;
        Hash peer = null;
        synchronized(this) {
            if (_dead) return;
            _dead = true;
            _success = true;
            tries = _unheardFrom.size() + _failedPeers.size();
            if (_unheardFrom.size() == 1) {
                peer = _unheardFrom.iterator().next();
                _unheardFrom.clear();
            }
        }
        _facade.complete(_key);
        if (peer != null) {
            Long timeSent = _sentTime.get(peer);
            if (timeSent != null)
                getContext().profileManager().dbLookupSuccessful(peer, getContext().clock().now() - timeSent.longValue());
        }
        long time = System.currentTimeMillis() - _created;
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": ISJ for " + _key + " successful after " + time +
                      ", peers queried: " + tries);
        getContext().statManager().addRateData("netDb.successTime", time);
        getContext().statManager().addRateData("netDb.successRetries", tries - 1);
        for (Job j : _onFind) {
            getContext().jobQueue().addJob(j);
        }
        _onFind.clear();
    }
}
