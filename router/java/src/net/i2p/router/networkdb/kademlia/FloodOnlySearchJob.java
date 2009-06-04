package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;

/**
 * Try sending a search to some floodfill peers, failing completely if we don't get
 * a match from one of those peers, with no fallback to the kademlia search
 *
 * Exception (a semi-exception, since we still fail completely without fallback):
 * If we don't know any floodfill peers, we ask a couple of peers at random,
 * who will hopefully reply with some floodfill keys.
 * We still fail without fallback, but we then spin off a job to
 * ask that same random peer for the RouterInfos for those keys.
 * If that job succeeds, the next search should work better.
 *
 * In addition, we follow the floodfill keys in the DSRM
 * (DatabaseSearchReplyMessage) if we know less than 4 floodfills.
 *
 * These enhancements allow the router to bootstrap back into the network
 * after it loses (or never had) floodfill references, as long as it
 * knows one peer that is up.
 *
 */
class FloodOnlySearchJob extends FloodSearchJob {
    protected Log _log;
    private FloodfillNetworkDatabaseFacade _facade;
    protected Hash _key;
    private final List _onFind;
    private final List _onFailed;
    private long _expiration;
    protected int _timeoutMs;
    private long _origExpiration;
    private boolean _isLease;
    protected volatile int _lookupsRemaining;
    private volatile boolean _dead;
    private long _created;
    private boolean _shouldProcessDSRM;
    private final HashSet _unheardFrom;
    
    protected final List _out;
    protected MessageSelector _replySelector;
    protected ReplyJob _onReply;
    protected Job _onTimeout;
    public FloodOnlySearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key, Job onFind, Job onFailed, int timeoutMs, boolean isLease) {
        super(ctx, facade, key, onFind, onFailed, timeoutMs, isLease);
        _log = ctx.logManager().getLog(FloodOnlySearchJob.class);
        _facade = facade;
        _key = key;
        _onFind = new ArrayList();
        _onFind.add(onFind);
        _onFailed = new ArrayList();
        _onFailed.add(onFailed);
        _timeoutMs = Math.min(timeoutMs, SearchJob.PER_FLOODFILL_PEER_TIMEOUT);
        _expiration = _timeoutMs + ctx.clock().now();
        _origExpiration = _timeoutMs + ctx.clock().now();
        _isLease = isLease;
        _lookupsRemaining = 0;
        _dead = false;
        _out = Collections.synchronizedList(new ArrayList(2));
        _unheardFrom = new HashSet(CONCURRENT_SEARCHES);
        _replySelector = new FloodOnlyLookupSelector(getContext(), this);
        _onReply = new FloodOnlyLookupMatchJob(getContext(), this);
        _onTimeout = new FloodOnlyLookupTimeoutJob(getContext(), this);
        _created = System.currentTimeMillis();
        _shouldProcessDSRM = false;
    }
    @Override
    void addDeferred(Job onFind, Job onFailed, long timeoutMs, boolean isLease) {
        if (_dead) {
            getContext().jobQueue().addJob(onFailed);
        } else {
            if (onFind != null) synchronized (_onFind) { _onFind.add(onFind); }
            if (onFailed != null) synchronized (_onFailed) { _onFailed.add(onFailed); }
        }
    }
    @Override
    public long getExpiration() { return _expiration; }
    public long getCreated() { return _created; }
    public boolean shouldProcessDSRM() { return _shouldProcessDSRM; }
    private static final int CONCURRENT_SEARCHES = 2;
    @Override
    public void runJob() {
        // pick some floodfill peers and send out the searches
        List floodfillPeers = _facade.getFloodfillPeers();
        if (floodfillPeers.size() <= 3)
            _shouldProcessDSRM = true;
        if (floodfillPeers.size() <= 0) {
            // ask anybody, they may not return the answer but they will return a few ff peers we can go look up,
            // so this situation should be temporary
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running netDb searches against the floodfill peers, but we don't know any");
            floodfillPeers = new ArrayList(_facade.getAllRouters());
            if (floodfillPeers.size() <= 0) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("We don't know any peers at all");
                failed();
                return;
            }
        }
        OutNetMessage out = getContext().messageRegistry().registerPending(_replySelector, _onReply, _onTimeout, _timeoutMs);
        synchronized (_out) { _out.add(out); }

        // We need to randomize our ff selection, else we stay with the same ones since
        // getFloodfillPeers() is sorted by closest distance. Always using the same
        // ones didn't help reliability.
        // Also, query the unheard-from, unprofiled, failing, unreachable and shitlisted ones last.
        // We should hear from floodfills pretty frequently so set a 30m time limit.
        // If unprofiled we haven't talked to them in a long time.
        // We aren't contacting the peer directly, so shitlist doesn't strictly matter,
        // but it's a bad sign, and we often shitlist a peer before we fail it...
        if (floodfillPeers.size() > CONCURRENT_SEARCHES) {
            Collections.shuffle(floodfillPeers, getContext().random());
            List ffp = new ArrayList(floodfillPeers.size());
            int failcount = 0;
            long before = getContext().clock().now() - 30*60*1000;
            for (int i = 0; i < floodfillPeers.size(); i++) {
                 Hash peer = (Hash)floodfillPeers.get(i);
                 PeerProfile profile = getContext().profileOrganizer().getProfile(peer);
                 if (profile == null || profile.getLastHeardFrom() < before ||
                     profile.getIsFailing() || getContext().shitlist().isShitlisted(peer) ||
                     getContext().commSystem().wasUnreachable(peer)) {
                     failcount++;
                     ffp.add(peer);
                 } else
                     ffp.add(0, peer);
            }
            // This will help us recover if the router just started and all the floodfills
            // have changed since the last time we were running
            if (floodfillPeers.size() - failcount <= 2)
                _shouldProcessDSRM = true;
            if (_log.shouldLog(Log.INFO) && failcount > 0)
                _log.info(getJobId() + ": " + failcount + " of " + floodfillPeers.size() + " floodfills are not heard from, unprofiled, failing, unreachable or shitlisted");
            floodfillPeers = ffp;
        } else {
            _shouldProcessDSRM = true;
        }

        int count = 0; // keep a separate count since _lookupsRemaining could be decremented elsewhere
        for (int i = 0; _lookupsRemaining < CONCURRENT_SEARCHES && i < floodfillPeers.size(); i++) {
            Hash peer = (Hash)floodfillPeers.get(i);
            if (peer.equals(getContext().routerHash()))
                continue;
            
            DatabaseLookupMessage dlm = new DatabaseLookupMessage(getContext(), true);
            TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundTunnel();
            TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundTunnel();
            if ( (replyTunnel == null) || (outTunnel == null) ) {
                failed();
                return;
            }
            synchronized(_unheardFrom) {
                _unheardFrom.add(peer);
            }
            dlm.setFrom(replyTunnel.getPeer(0));
            dlm.setMessageExpiration(getContext().clock().now()+10*1000);
            dlm.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
            dlm.setSearchKey(_key);
            
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " to " + peer.toBase64());
            getContext().tunnelDispatcher().dispatchOutbound(dlm, outTunnel.getSendTunnelId(0), peer);
            count++;
            _lookupsRemaining++;
        }
        
        if (count <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " had no peers to send to");
            // no floodfill peers, fail
            failed();
        }
    }
    @Override
    public String getName() { return "NetDb flood search (phase 1)"; }
    
    @Override
    Hash getKey() { return _key; }
    @Override
    void decrementRemaining() { if (_lookupsRemaining > 0) _lookupsRemaining--; }
    @Override
    int getLookupsRemaining() { return _lookupsRemaining; }
    /** Note that we heard from the peer */
    void decrementRemaining(Hash peer) {
        decrementRemaining();
        synchronized(_unheardFrom) {
            _unheardFrom.remove(peer);
        }
    }
    
    @Override
    void failed() {
        synchronized (this) {
            if (_dead) return;
            _dead = true;
        }
        List outBuf = null;
        synchronized (_out) { outBuf = new ArrayList(_out); }
        for (int i = 0; i < outBuf.size(); i++) {
            OutNetMessage out = (OutNetMessage)outBuf.get(i);
            getContext().messageRegistry().unregisterPending(out);
        }
        int timeRemaining = (int)(_origExpiration - getContext().clock().now());
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " failed with " + timeRemaining + " remaining after " + (System.currentTimeMillis()-_created));
        synchronized(_unheardFrom) {
            for (Iterator iter = _unheardFrom.iterator(); iter.hasNext(); ) 
                getContext().profileManager().dbLookupFailed((Hash) iter.next());
        }
        _facade.complete(_key);
        getContext().statManager().addRateData("netDb.failedTime", System.currentTimeMillis()-_created, System.currentTimeMillis()-_created);
        synchronized (_onFailed) {
            for (int i = 0; i < _onFailed.size(); i++) {
                Job j = (Job)_onFailed.remove(0);
                getContext().jobQueue().addJob(j);
            }
        }
    }
    @Override
    void success() {
        synchronized (this) {
            if (_dead) return;
            _dead = true;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " successful");
        // Sadly, we don't know which of the two replied, unless the first one sent a DSRM
        // before the second one sent the answer, which isn't that likely.
        // Would be really nice to fix this, but it isn't clear how unless CONCURRENT_SEARCHES == 1.
        // Maybe don't unregister the msg from the Registry for a while and see if we get a 2nd reply?
        // Or delay the 2nd search for a few seconds?
        // We'll have to rely primarily on other searches (ExploreJob which calls SearchJob,
        // and FloodfillVerifyStoreJob) to record successful searches for now.
        // StoreJob also calls dbStoreSent() which updates the lastHeardFrom timer - this also helps.
        synchronized(_unheardFrom) {
            if (_unheardFrom.size() == 1) {
                Hash peer = (Hash) _unheardFrom.iterator().next();
                getContext().profileManager().dbLookupSuccessful(peer, System.currentTimeMillis()-_created);
            }
        }
        _facade.complete(_key);
        getContext().statManager().addRateData("netDb.successTime", System.currentTimeMillis()-_created, System.currentTimeMillis()-_created);
        synchronized (_onFind) {
            while (_onFind.size() > 0)
                getContext().jobQueue().addJob((Job)_onFind.remove(0));
        }
    }
}
