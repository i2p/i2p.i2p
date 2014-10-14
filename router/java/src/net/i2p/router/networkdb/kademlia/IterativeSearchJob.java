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

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.util.RandomIterator;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SystemVersion;

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
 * @since 0.8.9
 */
class IterativeSearchJob extends FloodSearchJob {
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

    private static final int MAX_NON_FF = 3;
    /** Max number of peers to query */
    private static final int TOTAL_SEARCH_LIMIT = 7;
    /** TOTAL_SEARCH_LIMIT * SINGLE_SEARCH_TIME, plus some extra */
    private static final int MAX_SEARCH_TIME = 30*1000;
    /**
     *  The time before we give up and start a new search - much shorter than the message's expire time
     *  Longer than the typ. response time of 1.0 - 1.5 sec, but short enough that we move
     *  on to another peer quickly.
     */
    private static final long SINGLE_SEARCH_TIME = 3*1000;
    /** the actual expire time for a search message */
    private static final long SINGLE_SEARCH_MSG_TIME = 10*1000;
    /**
     *  Use instead of CONCURRENT_SEARCHES in super() which is final.
     *  For now, we don't do concurrent, but we keep SINGLE_SEARCH_TIME very short,
     *  so we have effective concurrency in that we fail a search quickly.
     */
    private static final int MAX_CONCURRENT = 1;

    public static final String PROP_ENCRYPT_RI = "router.encryptRouterLookups";

    /** only on fast boxes, for now */
    public static final boolean DEFAULT_ENCRYPT_RI =
            SystemVersion.isX86() && SystemVersion.is64Bit() &&
            !SystemVersion.isApache() && !SystemVersion.isGNU() &&
            NativeBigInteger.isNative();

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
        _unheardFrom = new HashSet<Hash>(CONCURRENT_SEARCHES);
        _failedPeers = new HashSet<Hash>(TOTAL_SEARCH_LIMIT);
        _sentTime = new ConcurrentHashMap<Hash, Long>(TOTAL_SEARCH_LIMIT);
        _fromLocalDest = fromLocalDest;
        if (fromLocalDest != null && !isLease && _log.shouldLog(Log.WARN))
            _log.warn("Search for RI " + key + " down client tunnel " + fromLocalDest, new Exception());
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
            floodfillPeers = ((FloodfillPeerSelector)_facade.getPeerSelector()).selectFloodfillParticipants(_rkey, TOTAL_SEARCH_LIMIT, ks);
        } else {
            floodfillPeers = new ArrayList<Hash>(TOTAL_SEARCH_LIMIT);
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
        _toTry.addAll(floodfillPeers);
        // don't ask ourselves or the target
        _toTry.remove(getContext().routerHash());
        _toTry.remove(_key);
        if (_toTry.isEmpty()) {
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
            Hash peer;
            synchronized (this) {
                if (_dead) return;
                int pend = _unheardFrom.size();
                if (pend >= MAX_CONCURRENT)
                    return;
                int done = _failedPeers.size();
                if (done >= TOTAL_SEARCH_LIMIT) {
                    failed();
                    return;
                }
                // even if pend and todo are empty, we don't fail, as there may be more peers
                // coming via newPeerToTry()
                if (done + pend >= TOTAL_SEARCH_LIMIT)
                    return;
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
                    Iterator<Hash> iter = _toTry.iterator();
                    peer = iter.next();
                    iter.remove();
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
            TunnelInfo outTunnel;
            TunnelInfo replyTunnel;
            boolean isClientReplyTunnel;
            if (_fromLocalDest != null) {
                outTunnel = tm.selectOutboundTunnel(_fromLocalDest, peer);
                if (outTunnel == null)
                    outTunnel = tm.selectOutboundExploratoryTunnel(peer);
                replyTunnel = tm.selectInboundTunnel(_fromLocalDest, peer);
                isClientReplyTunnel = replyTunnel != null;
                if (!isClientReplyTunnel)
                    replyTunnel = tm.selectInboundExploratoryTunnel(peer);
            } else {
                outTunnel = tm.selectOutboundExploratoryTunnel(peer);
                replyTunnel = tm.selectInboundExploratoryTunnel(peer);
                isClientReplyTunnel = false;
            }
            if ( (replyTunnel == null) || (outTunnel == null) ) {
                failed();
                return;
            }

            // As explained above, it's hard to keep the key itself out of the ff list,
            // so let's just skip it for now if the outbound tunnel is zero-hop.
            // Yes, that means we aren't doing double-lookup for a floodfill
            // if it happens to be closest to itself and we are using zero-hop exploratory tunnels.
            // If we don't, the OutboundMessageDistributor ends up logging erors for
            // not being able to send to the floodfill, if we don't have an older netdb entry.
            if (outTunnel.getLength() <= 1) {
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
            dlm.setFrom(replyTunnel.getPeer(0));
            dlm.setMessageExpiration(getContext().clock().now() + SINGLE_SEARCH_MSG_TIME);
            dlm.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
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
                          " reply via client tunnel? " + isClientReplyTunnel);
            }
            long now = getContext().clock().now();
            _sentTime.put(peer, Long.valueOf(now));

            I2NPMessage outMsg = null;
            if (_isLease || getContext().getProperty(PROP_ENCRYPT_RI, DEFAULT_ENCRYPT_RI)) {
                // Full ElG is fairly expensive so only do it for LS lookups
                // if we have the ff RI, garlic encrypt it
                RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(peer);
                if (ri != null) {
                    // request encrypted reply
                    if (DatabaseLookupMessage.supportsEncryptedReplies(ri)) {
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
            getContext().tunnelDispatcher().dispatchOutbound(outMsg, outTunnel.getSendTunnelId(0), peer);

            // The timeout job is always run (never cancelled)
            // Note that the timeout is much shorter than the message expiration (see above)
            Job j = new IterativeTimeoutJob(getContext(), peer, this);
            long expire = Math.min(_expiration, now + SINGLE_SEARCH_TIME);
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
                _unheardFrom.contains(peer))
                return;  // already tried
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
     *  Total failure
     */
    @Override
    void failed() {
        synchronized (this) {
            if (_dead) return;
            _dead = true;
        }
        _facade.complete(_key);
        if (getContext().commSystem().getReachabilityStatus() != CommSystemFacade.STATUS_DISCONNECTED)
            _facade.lookupFailed(_key);
        getContext().messageRegistry().unregisterPending(_out);
        int tries;
        synchronized(this) {
            tries = _unheardFrom.size() + _failedPeers.size();
            // blame the unheard-from (others already blamed in failed() above)
            for (Hash h : _unheardFrom)
                getContext().profileManager().dbLookupFailed(h);
        }
        long time = System.currentTimeMillis() - _created;
        if (_log.shouldLog(Log.INFO)) {
            long timeRemaining = _expiration - getContext().clock().now();
            _log.info(getJobId() + ": ISJ for " + _key + " failed with " + timeRemaining + " remaining after " + time +
                      ", peers queried: " + tries);
        }
        getContext().statManager().addRateData("netDb.failedTime", time, 0);
        getContext().statManager().addRateData("netDb.failedRetries", Math.max(0, tries - 1), 0);
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
        getContext().statManager().addRateData("netDb.successTime", time, 0);
        getContext().statManager().addRateData("netDb.successRetries", tries - 1, 0);
        for (Job j : _onFind) {
            getContext().jobQueue().addJob(j);
        }
        _onFind.clear();
    }
}
