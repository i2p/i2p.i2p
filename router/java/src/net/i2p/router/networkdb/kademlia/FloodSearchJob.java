package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Try sending a search to some floodfill peers, but if we don't get a successful
 * match within half the allowed lookup time, give up and start querying through
 * the normal (kademlia) channels.  This should cut down on spurious lookups caused
 * by simple delays in responses from floodfill peers
 *
 * NOTE: Unused directly - see FloodOnlySearchJob extension which overrides almost everything.
 * TODO: Comment out or delete what we don't use here.
 *
 * Note that this does NOT extend SearchJob.
 */
public class FloodSearchJob extends JobImpl {
    protected Log _log;
    protected final FloodfillNetworkDatabaseFacade _facade;
    protected final Hash _key;
    protected final List<Job> _onFind;
    protected final List<Job> _onFailed;
    protected long _expiration;
    protected int _timeoutMs;
    protected long _origExpiration;
    protected final boolean _isLease;
    protected volatile int _lookupsRemaining;
    protected volatile boolean _dead;

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
                List<Job> removed = null;
                synchronized (_onFailed) {
                    removed = new ArrayList(_onFailed);
                    _onFailed.clear();
                }
                while (!removed.isEmpty())
                    getContext().jobQueue().addJob(removed.remove(0));
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
    
    protected Hash getKey() { return _key; }
    protected void decrementRemaining() { if (_lookupsRemaining > 0) _lookupsRemaining--; }
    protected int getLookupsRemaining() { return _lookupsRemaining; }
    
    void failed() {
        if (_dead) return;
        _dead = true;
        int timeRemaining = (int)(_origExpiration - getContext().clock().now());
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " failed with " + timeRemaining);
        if (timeRemaining > 0) {
            _facade.searchFull(_key, _onFind, _onFailed, timeRemaining, _isLease);
        } else {
            List<Job> removed = null;
            synchronized (_onFailed) {
                removed = new ArrayList(_onFailed);
                _onFailed.clear();
            }
            while (!removed.isEmpty())
                getContext().jobQueue().addJob(removed.remove(0));
        }
    }
    void success() {
        if (_dead) return;
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Floodfill search for " + _key.toBase64() + " successful");
        _dead = true;
        _facade.complete(_key);
        List<Job> removed = null;
        synchronized (_onFind) {
            removed = new ArrayList(_onFind);
            _onFind.clear();
        }
        while (!removed.isEmpty())
            getContext().jobQueue().addJob(removed.remove(0));
    }

    private static class FloodLookupTimeoutJob extends JobImpl {
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

    private static class FloodLookupMatchJob extends JobImpl implements ReplyJob {
        private Log _log;
        private FloodSearchJob _search;
        public FloodLookupMatchJob(RouterContext ctx, FloodSearchJob job) {
            super(ctx);
            _log = ctx.logManager().getLog(FloodLookupMatchJob.class);
            _search = job;
        }
        public void runJob() { 
            if (getContext().netDb().lookupLocally(_search.getKey()) != null) {
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

    private static class FloodLookupSelector implements MessageSelector {
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
}
