package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.BuildResponseRecord;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.router.tunnel.BuildMessageProcessor;
import net.i2p.router.tunnel.BuildReplyHandler;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 *
 */
class BuildHandler {
    private RouterContext _context;
    private Log _log;
    private BuildExecutor _exec;
    private Job _buildMessageHandlerJob;
    private Job _buildReplyMessageHandlerJob;
    /** list of BuildMessageState, oldest first */
    private final List _inboundBuildMessages;
    /** list of BuildReplyMessageState, oldest first */
    private final List _inboundBuildReplyMessages;
    /** list of BuildEndMessageState, oldest first */
    private final List _inboundBuildEndMessages;
    private BuildMessageProcessor _processor;
    
    public BuildHandler(RouterContext ctx, BuildExecutor exec) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _exec = exec;
        _inboundBuildMessages = new ArrayList(16);
        _inboundBuildReplyMessages = new ArrayList(16);
        _inboundBuildEndMessages = new ArrayList(16);
    
        _context.statManager().createRateStat("tunnel.reject.10", "How often we reject a tunnel probabalistically", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.reject.20", "How often we reject a tunnel because of transient overload", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.reject.30", "How often we reject a tunnel because of bandwidth overload", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.reject.50", "How often we reject a tunnel because of a critical issue (shutdown, etc)", "Tunnels", new long[] { 60*1000, 10*60*1000 });

        _context.statManager().createRateStat("tunnel.decryptRequestTime", "How long it takes to decrypt a new tunnel build request", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.rejectTimeout", "How often we reject a tunnel because we can't find the next hop", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.rejectTimeout2", "How often we fail a tunnel because we can't contact the next hop", "Tunnels", new long[] { 60*1000, 10*60*1000 });

        _context.statManager().createRateStat("tunnel.rejectOverloaded", "How long we had to wait before processing the request (when it was rejected)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.acceptLoad", "Delay before processing the accepted request", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.dropConnLimits", "Drop instead of reject due to conn limits", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.dropLoad", "How long we had to wait before finally giving up on an inbound request (period is queue count)?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.dropLoadDelay", "How long we had to wait before finally giving up on an inbound request?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.dropLoadBacklog", "How many requests were pending when they were so lagged that we had to drop a new inbound request??", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.dropLoadProactive", "What the estimated queue time was when we dropped an inbound request (period is num pending)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.dropLoadProactiveAbort", "How often we would have proactively dropped a request, but allowed it through?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.handleRemaining", "How many pending inbound requests were left on the queue after one pass?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildReplyTooSlow", "How often a tunnel build reply came back after we had given up waiting for it?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        
        _context.statManager().createRateStat("tunnel.receiveRejectionProbabalistic", "How often we are rejected probabalistically?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tunnel.receiveRejectionTransient", "How often we are rejected due to transient overload?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tunnel.receiveRejectionBandwidth", "How often we are rejected due to bandwidth overload?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tunnel.receiveRejectionCritical", "How often we are rejected due to critical failure?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        
        _processor = new BuildMessageProcessor(ctx);
        _buildMessageHandlerJob = new TunnelBuildMessageHandlerJob(ctx);
        _buildReplyMessageHandlerJob = new TunnelBuildReplyMessageHandlerJob(ctx);
        ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelBuildMessage.MESSAGE_TYPE, new TunnelBuildMessageHandlerJobBuilder());
        ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelBuildReplyMessage.MESSAGE_TYPE, new TunnelBuildReplyMessageHandlerJobBuilder());
    }
    
    private static final int MAX_HANDLE_AT_ONCE = 2;
    private static final int NEXT_HOP_LOOKUP_TIMEOUT = 5*1000;
    
    /**
     * Blocking call to handle a few of the pending inbound requests, returning how many
     * requests remain after this pass
     */
    int handleInboundRequests() {
        int dropExpired = 0;
        int remaining = 0;
        List handled = null;
        long beforeFindHandled = System.currentTimeMillis();
        synchronized (_inboundBuildMessages) {
            int toHandle = _inboundBuildMessages.size();
            if (toHandle > 0) {
                if (toHandle > MAX_HANDLE_AT_ONCE)
                    toHandle = MAX_HANDLE_AT_ONCE;
                handled = new ArrayList(toHandle);
                if (false) {
                    for (int i = 0; i < toHandle; i++) // LIFO for lower response time (should we RED it for DoS?)
                        handled.add(_inboundBuildMessages.remove(_inboundBuildMessages.size()-1));
                } else {
                    // drop any expired messages
                    long dropBefore = System.currentTimeMillis() - (BuildRequestor.REQUEST_TIMEOUT/4);
                    do {
                        BuildMessageState state = (BuildMessageState)_inboundBuildMessages.get(0);
                        if (state.recvTime <= dropBefore) {
                            _inboundBuildMessages.remove(0);
                            dropExpired++;
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Not even trying to handle/decrypt the request " + state.msg.getUniqueId() 
                                           + ", since we received it a long time ago: " + (System.currentTimeMillis() - state.recvTime));
                            _context.statManager().addRateData("tunnel.dropLoadDelay", System.currentTimeMillis() - state.recvTime, 0);
                        } else {
                            break;
                        }
                    } while (_inboundBuildMessages.size() > 0);
                    
                    if (dropExpired > 0)
                        _context.throttle().setTunnelStatus("Dropping tunnel requests: Too slow");

                    // now pull off the oldest requests first (we're doing a tail-drop
                    // when adding)
                    for (int i = 0; i < toHandle && _inboundBuildMessages.size() > 0; i++)
                        handled.add(_inboundBuildMessages.remove(0));
                }
            }
            remaining = _inboundBuildMessages.size();
        }
        if (handled != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Handling " + handled.size() + " requests (took " + (System.currentTimeMillis()-beforeFindHandled) + "ms to find them)");
            
            for (int i = 0; i < handled.size(); i++) {
                BuildMessageState state = (BuildMessageState)handled.get(i);
                long beforeHandle = System.currentTimeMillis();
                long actualTime = handleRequest(state);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Handle took " + (System.currentTimeMillis()-beforeHandle) + "/" + actualTime + " (" + i + " out of " + handled.size() + " with " + remaining + " remaining)");
            }
            handled.clear();
        }
        synchronized (_inboundBuildEndMessages) {
            int toHandle = _inboundBuildEndMessages.size();
            if (toHandle > 0) {
                if (handled == null)
                    handled = new ArrayList(_inboundBuildEndMessages);
                else
                    handled.addAll(_inboundBuildEndMessages);
                _inboundBuildEndMessages.clear();
            }
        }
        if (handled != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Handling " + handled.size() + " requests that are actually replies");
            // these are inbound build messages that actually contain the full replies, since
            // they are for inbound tunnels we have created
            for (int i = 0; i < handled.size(); i++) {
                BuildEndMessageState state = (BuildEndMessageState)handled.get(i);
                handleRequestAsInboundEndpoint(state);
            }
        }
        
        // anything else?
        /*
        synchronized (_inboundBuildMessages) {
            int remaining = _inboundBuildMessages.size();
            return remaining;
        }
         */
        if (remaining > 0)
            _context.statManager().addRateData("tunnel.handleRemaining", remaining, 0);
        return remaining;
    }
    
    void handleInboundReplies() {
        List handled = null;
        synchronized (_inboundBuildReplyMessages) {
            int toHandle = _inboundBuildReplyMessages.size();
            if (toHandle > 0) {
                // always handle all of them - they're replies that we were waiting for!
                handled = new ArrayList(_inboundBuildReplyMessages);
                _inboundBuildReplyMessages.clear();
            }
        }
        if (handled != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Handling " + handled.size() + " replies");
            
            for (int i = 0; i < handled.size(); i++) {
                BuildReplyMessageState state = (BuildReplyMessageState)handled.get(i);
                handleReply(state);
            }
        }
    }
    
    private void handleReply(BuildReplyMessageState state) {
        // search through the tunnels for a reply
        long replyMessageId = state.msg.getUniqueId();
        PooledTunnelCreatorConfig cfg = null;
        List building = _exec.locked_getCurrentlyBuilding();
        StringBuilder buf = null;
        synchronized (building) {
            for (int i = 0; i < building.size(); i++) {
                PooledTunnelCreatorConfig cur = (PooledTunnelCreatorConfig)building.get(i);
                if (cur.getReplyMessageId() == replyMessageId) {
                    building.remove(i);
                    cfg = cur;
                    break;
                }
            }
            if ( (cfg == null) && (_log.shouldLog(Log.DEBUG)) )
                buf = new StringBuilder(building.toString());
        }
        
        if (cfg == null) {
            // cannot handle - not pending... took too long?
            if (_log.shouldLog(Log.WARN))
                _log.warn("The reply " + replyMessageId + " did not match any pending tunnels");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Pending tunnels: " + buf.toString());
            _context.statManager().addRateData("tunnel.buildReplyTooSlow", 1, 0);
        } else {
            handleReply(state.msg, cfg, System.currentTimeMillis()-state.recvTime);
        }
    }
    
    private void handleReply(TunnelBuildReplyMessage msg, PooledTunnelCreatorConfig cfg, long delay) {
        long requestedOn = cfg.getExpiration() - 10*60*1000;
        long rtt = _context.clock().now() - requestedOn;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(msg.getUniqueId() + ": Handling the reply after " + rtt + ", delayed " + delay + " waiting for " + cfg);
        
        BuildReplyHandler handler = new BuildReplyHandler();
        List order = cfg.getReplyOrder();
        int statuses[] = handler.decrypt(_context, msg, cfg, order);
        if (statuses != null) {
            boolean allAgree = true;
            // For each peer in the tunnel
            for (int i = 0; i < cfg.getLength(); i++) {
                Hash peer = cfg.getPeer(i);
                int record = order.indexOf(Integer.valueOf(i));
                if (record < 0) {
                    _log.error("Bad status index " + i);
                    // don't leak
                    _exec.buildComplete(cfg, cfg.getTunnelPool());
                    return;
                }
                int howBad = statuses[record];
                // If this tunnel member isn't ourselves
                if (!peer.toBase64().equals(_context.routerHash().toBase64())) {
                    // Look up routerInfo
                    RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
                    // Default and detect bandwidth tier
                    String bwTier = "Unknown";
                    if (ri != null) bwTier = ri.getBandwidthTier(); // Returns "Unknown" if none recognized
                        else if (_log.shouldLog(Log.WARN)) _log.warn("Failed detecting bwTier, null routerInfo for: " + peer);
                    // Record that a peer of the given tier agreed or rejected
                    if (howBad == 0) {
                        _context.statManager().addRateData("tunnel.tierAgree" + bwTier, 1, 0);
                    } else {
                        _context.statManager().addRateData("tunnel.tierReject" + bwTier, 1, 0);
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info(msg.getUniqueId() + ": Peer " + peer.toBase64() + " replied with status " + howBad);
                }

                if (howBad == 0) {
                    // w3wt
                    _context.profileManager().tunnelJoined(peer, rtt);
                } else {
                    allAgree = false;
                    switch (howBad) {
                        case TunnelHistory.TUNNEL_REJECT_BANDWIDTH:
                            _context.statManager().addRateData("tunnel.receiveRejectionBandwidth", 1, 0);
                            break;
                        case TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD:
                            _context.statManager().addRateData("tunnel.receiveRejectionTransient", 1, 0);
                            break;
                        case TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT:
                            _context.statManager().addRateData("tunnel.receiveRejectionProbabalistic", 1, 0);
                            break;
                        case TunnelHistory.TUNNEL_REJECT_CRIT:
                        default:
                            _context.statManager().addRateData("tunnel.receiveRejectionCritical", 1, 0);
                    }
                    // penalize peer based on their bitchiness level
                    _context.profileManager().tunnelRejected(peer, rtt, howBad);
                    _context.messageHistory().tunnelParticipantRejected(peer, "peer rejected after " + rtt + " with " + howBad + ": " + cfg.toString());
                }
            }
            _exec.buildComplete(cfg, cfg.getTunnelPool());
            if (allAgree) {
                // wikked, completely build
                if (cfg.isInbound())
                    _context.tunnelDispatcher().joinInbound(cfg);
                else
                    _context.tunnelDispatcher().joinOutbound(cfg);
                cfg.getTunnelPool().addTunnel(cfg); // self.self.self.foo!
                _exec.buildSuccessful(cfg);
                
                ExpireJob expireJob = new ExpireJob(_context, cfg, cfg.getTunnelPool());
                cfg.setExpireJob(expireJob);
                _context.jobQueue().addJob(expireJob);
                if (cfg.getDestination() == null)
                    _context.statManager().addRateData("tunnel.buildExploratorySuccess", rtt, rtt);
                else
                    _context.statManager().addRateData("tunnel.buildClientSuccess", rtt, rtt);
            } else {
                // someone is no fun
                if (cfg.getDestination() == null)
                    _context.statManager().addRateData("tunnel.buildExploratoryReject", rtt, rtt);
                else
                    _context.statManager().addRateData("tunnel.buildClientReject", rtt, rtt);
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn(msg.getUniqueId() + ": Tunnel reply could not be decrypted for tunnel " + cfg);
            // don't leak
            _exec.buildComplete(cfg, cfg.getTunnelPool());
        }
    }
    
    private long handleRequest(BuildMessageState state) {
        long timeSinceReceived = System.currentTimeMillis()-state.recvTime;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(state.msg.getUniqueId() + ": handling request after " + timeSinceReceived);
        
        if (timeSinceReceived > (BuildRequestor.REQUEST_TIMEOUT*3)) {
            // don't even bother, since we are so overloaded locally
            _context.throttle().setTunnelStatus("Dropping tunnel requests: Overloaded");
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not even trying to handle/decrypt the request " + state.msg.getUniqueId() 
                           + ", since we received it a long time ago: " + timeSinceReceived);
            _context.statManager().addRateData("tunnel.dropLoadDelay", timeSinceReceived, 0);
            return -1;
        }
        // ok, this is not our own tunnel, so we need to do some heavy lifting
        // this not only decrypts the current hop's record, but encrypts the other records
        // with the enclosed reply key
        long beforeDecrypt = System.currentTimeMillis();
        BuildRequestRecord req = _processor.decrypt(_context, state.msg, _context.routerHash(), _context.keyManager().getPrivateKey());
        long decryptTime = System.currentTimeMillis() - beforeDecrypt;
        _context.statManager().addRateData("tunnel.decryptRequestTime", decryptTime, decryptTime);
        if (decryptTime > 500)
            _log.warn("Took too long to decrypt the request: " + decryptTime + " for message " + state.msg.getUniqueId() + " received " + (timeSinceReceived+decryptTime) + " ago");
        if (req == null) {
            // no records matched, or the decryption failed.  bah
            if (_log.shouldLog(Log.WARN))
                _log.warn("The request " + state.msg.getUniqueId() + " could not be decrypted");
            return -1;
        }

        long beforeLookup = System.currentTimeMillis();
        Hash nextPeer = req.readNextIdentity();
        long readPeerTime = System.currentTimeMillis()-beforeLookup;
        RouterInfo nextPeerInfo = _context.netDb().lookupRouterInfoLocally(nextPeer);
        long lookupTime = System.currentTimeMillis()-beforeLookup;
        if (lookupTime > 500)
            _log.warn("Took too long to lookup the request: " + lookupTime + "/" + readPeerTime + " for message " + state.msg.getUniqueId() + " received " + (timeSinceReceived+decryptTime) + " ago");
        if (nextPeerInfo == null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + state.msg.getUniqueId() + "/" + req.readReceiveTunnelId() + "/" + req.readNextTunnelId() 
                           + " handled, looking for the next peer " + nextPeer.toBase64());
            _context.netDb().lookupRouterInfo(nextPeer, new HandleReq(_context, state, req, nextPeer), new TimeoutReq(_context, state, req, nextPeer), NEXT_HOP_LOOKUP_TIMEOUT);
            return -1;
        } else {
            long beforeHandle = System.currentTimeMillis();
            handleReq(nextPeerInfo, state, req, nextPeer);
            long handleTime = System.currentTimeMillis() - beforeHandle;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + state.msg.getUniqueId() + " handled and we know the next peer " 
                           + nextPeer.toBase64() + " after " + handleTime
                           + "/" + decryptTime + "/" + lookupTime + "/" + timeSinceReceived);
            return handleTime;
        }
    }
    
    /**
     * This request is actually a reply, process it as such
     */
    private void handleRequestAsInboundEndpoint(BuildEndMessageState state) {
        TunnelBuildReplyMessage msg = new TunnelBuildReplyMessage(_context);
        for (int i = 0; i < TunnelBuildMessage.RECORD_COUNT; i++)
            msg.setRecord(i, state.msg.getRecord(i));
        msg.setUniqueId(state.msg.getUniqueId());
        handleReply(msg, state.cfg, System.currentTimeMillis() - state.recvTime);
    }
    
    private class HandleReq extends JobImpl {
        private BuildMessageState _state;
        private BuildRequestRecord _req;
        private Hash _nextPeer;
        HandleReq(RouterContext ctx, BuildMessageState state, BuildRequestRecord req, Hash nextPeer) {
            super(ctx);
            _state = state;
            _req = req;
            _nextPeer = nextPeer;
        }
        public String getName() { return "Deferred tunnel join processing"; }
        public void runJob() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + _state.msg.getUniqueId() + " handled with a successful deferred lookup for the next peer " + _nextPeer.toBase64());

            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(_nextPeer);
            if (ri != null)
                handleReq(ri, _state, _req, _nextPeer);
            else
                _log.error("Deferred successfully, but we couldnt find " + _nextPeer.toBase64() + "?");
        }
    }

    private static class TimeoutReq extends JobImpl {
        private BuildMessageState _state;
        private BuildRequestRecord _req;
        private Hash _nextPeer;
        TimeoutReq(RouterContext ctx, BuildMessageState state, BuildRequestRecord req, Hash nextPeer) {
            super(ctx);
            _state = state;
            _req = req;
            _nextPeer = nextPeer;
        }
        public String getName() { return "Timeout looking for next peer for tunnel join"; }
        public void runJob() {
            getContext().statManager().addRateData("tunnel.rejectTimeout", 1, 0);
            // logging commented out so class can be static
            //if (_log.shouldLog(Log.WARN))
            //    _log.warn("Request " + _state.msg.getUniqueId() 
            //              + " could no be satisfied, as the next peer could not be found: " + _nextPeer.toBase64());

            // ???  should we blame the peer here?   getContext().profileManager().tunnelTimedOut(_nextPeer);
            getContext().messageHistory().tunnelRejected(_state.fromHash, new TunnelId(_req.readReceiveTunnelId()), _nextPeer, 
                                                         "rejected because we couldn't find " + _nextPeer.toBase64() + ": " +
                                                         _state.msg.getUniqueId() + "/" + _req.readNextTunnelId());
        }
    }
    
    /**
     * If we are dropping lots of requests before even trying to handle them,
     * I suppose you could call us "overloaded"
     */
    private final static int MAX_PROACTIVE_DROPS = 240;
    
    private int countProactiveDrops() {
        int dropped = 0;
        dropped += countEvents("tunnel.dropLoadProactive", 60*1000);
        dropped += countEvents("tunnel.dropLoad", 60*1000);
        dropped += countEvents("tunnel.dropLoadBacklog", 60*1000);
        dropped += countEvents("tunnel.dropLoadDelay", 60*1000);
        return dropped;
    }
    private int countEvents(String stat, long period) {
        RateStat rs = _context.statManager().getRate(stat);
        if (rs != null) {
            Rate r = rs.getRate(period);
            if (r != null)
                return (int)r.getCurrentEventCount();
        }
        return 0;
    }
    
    private void handleReq(RouterInfo nextPeerInfo, BuildMessageState state, BuildRequestRecord req, Hash nextPeer) {
        long ourId = req.readReceiveTunnelId();
        long nextId = req.readNextTunnelId();
        boolean isInGW = req.readIsInboundGateway();
        boolean isOutEnd = req.readIsOutboundEndpoint();
        long time = req.readRequestTime();
        long now = (_context.clock().now() / (60l*60l*1000l)) * (60*60*1000);
        int ourSlot = -1;

        int response = _context.throttle().acceptTunnelRequest();
        if (_context.tunnelManager().getTunnelInfo(new TunnelId(ourId)) != null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Already participating in a tunnel with the given Id (" + ourId + "), so gotta reject");
            if (response == 0)
                response = TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
        }
        
        //if ( (response == 0) && (_context.random().nextInt(50) <= 1) )
        //    response = TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
        
        int proactiveDrops = countProactiveDrops();
        long recvDelay = System.currentTimeMillis()-state.recvTime;
        if (response == 0) {
            float pDrop = ((float) recvDelay) / (float) (BuildRequestor.REQUEST_TIMEOUT*3);
            pDrop = (float)Math.pow(pDrop, 16);
            if (_context.random().nextFloat() < pDrop) { // || (proactiveDrops > MAX_PROACTIVE_DROPS) ) ) {
                _context.statManager().addRateData("tunnel.rejectOverloaded", recvDelay, proactiveDrops);
                _context.throttle().setTunnelStatus("Rejecting tunnels: Request overload");
                if (true || (proactiveDrops < MAX_PROACTIVE_DROPS*2))
                    response = TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
                else
                    response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
            } else {
                _context.statManager().addRateData("tunnel.acceptLoad", recvDelay, recvDelay);
            }
        }
        
        /*
         * Being a IBGW or OBEP generally leads to more connections, so if we are
         * approaching our connection limit (i.e. !haveCapacity()),
         * reject this request.
         *
         * Don't do this for class O, under the assumption that they are already talking
         * to most of the routers, so there's no reason to reject. This may drive them
         * to their conn. limits, but it's hopefully a temporary solution to the
         * tunnel build congestion. As the net grows this will have to be revisited.
         */
        RouterInfo ri = _context.router().getRouterInfo();
        if (response == 0 &&
            (ri == null || ri.getBandwidthTier().charAt(0) != 'O') &&
            ((isInGW && ! _context.commSystem().haveInboundCapacity(87)) ||
             (isOutEnd && ! _context.commSystem().haveOutboundCapacity(87)))) {
                _context.throttle().setTunnelStatus("Rejecting tunnels: Connection limit");
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Responding to " + state.msg.getUniqueId() + "/" + ourId
                       + " after " + recvDelay + "/" + proactiveDrops + " with " + response 
                       + " from " + (state.fromHash != null ? state.fromHash.toBase64() : 
                                     state.from != null ? state.from.calculateHash().toBase64() : "tunnel"));

        HopConfig cfg = null;
        if (response == 0) {
            cfg = new HopConfig();
            cfg.setCreation(_context.clock().now());
            cfg.setExpiration(_context.clock().now() + 10*60*1000);
            cfg.setIVKey(req.readIVKey());
            cfg.setLayerKey(req.readLayerKey());
            if (isInGW) {
                cfg.setReceiveFrom(null);
            } else {
                if (state.fromHash != null) {
                    cfg.setReceiveFrom(state.fromHash);
                } else if (state.from != null) {
                    cfg.setReceiveFrom(state.from.calculateHash());
                } else {
                    // b0rk
                    return;
                }
            }
            cfg.setReceiveTunnelId(DataHelper.toLong(4, ourId));
            if (isOutEnd) {
                cfg.setSendTo(null);
                cfg.setSendTunnelId(null);
            } else {
                cfg.setSendTo(req.readNextIdentity());
                cfg.setSendTunnelId(DataHelper.toLong(4, nextId));
            }

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Joining " + state.msg.getUniqueId() + "/" + cfg.getReceiveTunnel() + "/" + recvDelay + " as " + (isOutEnd ? "outbound endpoint" : isInGW ? "inbound gw" : "participant"));
            
            // now "actually" join
            if (isOutEnd)
                _context.tunnelDispatcher().joinOutboundEndpoint(cfg);
            else if (isInGW)
                _context.tunnelDispatcher().joinInboundGateway(cfg);
            else
                _context.tunnelDispatcher().joinParticipant(cfg);
        } else {
            _context.statManager().addRateData("tunnel.reject." + response, 1, 1);
            _context.messageHistory().tunnelRejected(state.fromHash, new TunnelId(ourId), req.readNextIdentity(), 
                                                     "rejecting for " + response + ": " +
                                                     state.msg.getUniqueId() + "/" + ourId + "/" + req.readNextTunnelId() + " delay " +
                                                     recvDelay + " as " +
                                                     (isOutEnd ? "outbound endpoint" : isInGW ? "inbound gw" : "participant"));
        }

        // Connection congestion control:
        // If we rejected the request, are near our conn limits, and aren't connected to the next hop,
        // just drop it.
        // 81% = between 75% control measures in Transports and 87% rejection above
        if (response != 0 &&
            (! _context.routerHash().equals(nextPeer)) &&
            (! _context.commSystem().haveOutboundCapacity(81)) &&
            (! _context.commSystem().isEstablished(nextPeer))) {
            _context.statManager().addRateData("tunnel.dropConnLimits", 1, 0);
            return;
        }

        BuildResponseRecord resp = new BuildResponseRecord();
        byte reply[] = resp.create(_context, response, req.readReplyKey(), req.readReplyIV(), state.msg.getUniqueId());
        for (int j = 0; j < TunnelBuildMessage.RECORD_COUNT; j++) {
            if (state.msg.getRecord(j) == null) {
                ourSlot = j;
                state.msg.setRecord(j, new ByteArray(reply));
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Full reply record for slot " + ourSlot + "/" + ourId + "/" + nextId + "/" + req.readReplyMessageId()
                //               + ": " + Base64.encode(reply));
                break;
            }
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read slot " + ourSlot + " containing our hop @ " + _context.routerHash().toBase64()
                      + " accepted? " + response + " receiving on " + ourId 
                      + " sending to " + nextId
                      + " on " + nextPeer.toBase64()
                      + " inGW? " + isInGW + " outEnd? " + isOutEnd + " time difference " + (now-time)
                      + " recvDelay " + recvDelay + " replyMessage " + req.readReplyMessageId()
                      + " replyKey " + req.readReplyKey().toBase64() + " replyIV " + Base64.encode(req.readReplyIV()));

        // now actually send the response
        if (!isOutEnd) {
            state.msg.setUniqueId(req.readReplyMessageId());
            state.msg.setMessageExpiration(_context.clock().now() + 10*1000);
            OutNetMessage msg = new OutNetMessage(_context);
            msg.setMessage(state.msg);
            msg.setExpiration(state.msg.getMessageExpiration());
            msg.setPriority(300);
            msg.setTarget(nextPeerInfo);
            if (response == 0)
                msg.setOnFailedSendJob(new TunnelBuildNextHopFailJob(_context, cfg));
            _context.outNetMessagePool().add(msg);
        } else {
            // send it to the reply tunnel on the reply peer within a new TunnelBuildReplyMessage
            // (enough layers jrandom?)
            TunnelBuildReplyMessage replyMsg = new TunnelBuildReplyMessage(_context);
            for (int i = 0; i < state.msg.RECORD_COUNT; i++) // LINT -- Accessing Static field "RECORD_COUNT"
                replyMsg.setRecord(i, state.msg.getRecord(i));
            replyMsg.setUniqueId(req.readReplyMessageId());
            replyMsg.setMessageExpiration(_context.clock().now() + 10*1000);
            TunnelGatewayMessage m = new TunnelGatewayMessage(_context);
            m.setMessage(replyMsg);
            m.setMessageExpiration(replyMsg.getMessageExpiration());
            m.setTunnelId(new TunnelId(nextId));
            if (_context.routerHash().equals(nextPeer)) {
                // ok, we are the gateway, so inject it
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("We are the reply gateway for " + nextId
                              + " when replying to replyMessage " + req.readReplyMessageId());
                _context.tunnelDispatcher().dispatch(m);
            } else {
                // ok, the gateway is some other peer, shove 'er across
                OutNetMessage outMsg = new OutNetMessage(_context);
                outMsg.setExpiration(m.getMessageExpiration());
                outMsg.setMessage(m);
                outMsg.setPriority(300);
                outMsg.setTarget(nextPeerInfo);
                if (response == 0)
                    outMsg.setOnFailedSendJob(new TunnelBuildNextHopFailJob(_context, cfg));
                _context.outNetMessagePool().add(outMsg);
            }
        }
    }
    
    public int getInboundBuildQueueSize() {
        synchronized (_inboundBuildMessages) {
            return _inboundBuildMessages.size();
        }
    }
    
    /** um, this is bad.  don't set this. */
    private static final boolean DROP_ALL_REQUESTS = false;
    private static final boolean HANDLE_REPLIES_INLINE = true;

    private class TunnelBuildMessageHandlerJobBuilder implements HandlerJobBuilder {
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            // need to figure out if this is a reply to an inbound tunnel request (where we are the
            // endpoint, receiving the request at the last hop)
            long reqId = receivedMessage.getUniqueId();
            PooledTunnelCreatorConfig cfg = null;
            List building = _exec.locked_getCurrentlyBuilding();
            List ids = new ArrayList();
            synchronized (building) {
                for (int i = 0; i < building.size(); i++) {
                    PooledTunnelCreatorConfig cur = (PooledTunnelCreatorConfig)building.get(i);
                    ids.add(new Long(cur.getReplyMessageId()));
                    if ( (cur.isInbound()) && (cur.getReplyMessageId() == reqId) ) {
                        building.remove(i);
                        cfg = cur;
                        break;
                    } else if (cur.getReplyMessageId() == reqId) {
                        _log.error("received it, but its not inbound? " + cur);
                    }
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive tunnel build message " + reqId + " from " 
                           + (from != null ? from.calculateHash().toBase64() : fromHash != null ? fromHash.toBase64() : "tunnels") 
                           + ", waiting ids: " + ids + ", found matching tunnel? " + (cfg != null), 
                           null);//new Exception("source"));
            if (cfg != null) {
                BuildEndMessageState state = new BuildEndMessageState(cfg, receivedMessage);
                if (HANDLE_REPLIES_INLINE) {
                    handleRequestAsInboundEndpoint(state);
                } else {
                    synchronized (_inboundBuildEndMessages) {
                        _inboundBuildEndMessages.add(state);
                    }
                    _exec.repoll();
                }
            } else {
                if (DROP_ALL_REQUESTS || _exec.wasRecentlyBuilding(reqId)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping the reply " + reqId + ", as we used to be building that");
                } else {
                    synchronized (_inboundBuildMessages) {
                        boolean removed = false;
                        int dropped = 0;
                        for (int i = 0; i < _inboundBuildMessages.size(); i++) {
                            BuildMessageState cur = (BuildMessageState)_inboundBuildMessages.get(i);
                            long age = System.currentTimeMillis() - cur.recvTime;
                            if (age >= BuildRequestor.REQUEST_TIMEOUT/4) {
                                _inboundBuildMessages.remove(i);
                                i--;
                                dropped++;
                                _context.statManager().addRateData("tunnel.dropLoad", age, _inboundBuildMessages.size());
                            }
                        }
                        if (dropped > 0) {
                            _context.throttle().setTunnelStatus("Dropping tunnel requests: High load");
                            // if the queue is backlogged, stop adding new messages
                            _context.statManager().addRateData("tunnel.dropLoadBacklog", _inboundBuildMessages.size(), _inboundBuildMessages.size());
                        } else {
                            int queueTime = estimateQueueTime(_inboundBuildMessages.size());
                            float pDrop = queueTime/((float)BuildRequestor.REQUEST_TIMEOUT*3);
                            pDrop = (float)Math.pow(pDrop, 16); // steeeep
                            float f = _context.random().nextFloat();
                            if ( (pDrop > f) && (allowProactiveDrop()) ) {
                                _context.throttle().setTunnelStatus("Dropping tunnel requests: Queue time");
                                _context.statManager().addRateData("tunnel.dropLoadProactive", queueTime, _inboundBuildMessages.size());
                            } else {
                                _inboundBuildMessages.add(new BuildMessageState(receivedMessage, from, fromHash));
                            }
                        }
                    }
                    _exec.repoll();
                }
            }
            return _buildMessageHandlerJob;
        }
    }
    
    private boolean allowProactiveDrop() {
        String allow = _context.getProperty("router.allowProactiveDrop", "true");
        boolean rv = false;
        if ( (allow == null) || (Boolean.valueOf(allow).booleanValue()) )
            rv = true;
        if (!rv)
            _context.statManager().addRateData("tunnel.dropLoadProactiveAbort", 1, 0);
        return rv;
    }
    
    private int estimateQueueTime(int numPendingMessages) {
        int decryptTime = 200;
        RateStat rs = _context.statManager().getRate("tunnel.decryptRequestTime");
        if (rs != null) {
            Rate r = rs.getRate(60*1000);
            double avg = 0;
            if (r != null)
                avg = r.getAverageValue();
            if (avg > 0) {
                decryptTime = (int)avg;
            } else {
                avg = rs.getLifetimeAverageValue();
                if (avg > 0)
                    decryptTime = (int)avg;
            }
        }
        float estimatedQueueTime = numPendingMessages * decryptTime;
        estimatedQueueTime *= 1.2f; // lets leave some cpu to spare, 'eh?
        return (int)estimatedQueueTime;
    }
    
    
    private class TunnelBuildReplyMessageHandlerJobBuilder implements HandlerJobBuilder {
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive tunnel build reply message " + receivedMessage.getUniqueId() + " from "
                           + (fromHash != null ? fromHash.toBase64() : from != null ? from.calculateHash().toBase64() : "a tunnel"));
            if (HANDLE_REPLIES_INLINE) {
                handleReply(new BuildReplyMessageState(receivedMessage));
            } else {
                synchronized (_inboundBuildReplyMessages) {
                    _inboundBuildReplyMessages.add(new BuildReplyMessageState(receivedMessage));
                }
                _exec.repoll();
            }
            return _buildReplyMessageHandlerJob;
        }
    }
    
    /** normal inbound requests from other people */
    private static class BuildMessageState {
        TunnelBuildMessage msg;
        RouterIdentity from;
        Hash fromHash;
        long recvTime;
        public BuildMessageState(I2NPMessage m, RouterIdentity f, Hash h) {
            msg = (TunnelBuildMessage)m;
            from = f;
            fromHash = h;
            recvTime = System.currentTimeMillis();
        }
    }
    /** replies for outbound tunnels that we have created */
    private static class BuildReplyMessageState {
        TunnelBuildReplyMessage msg;
        long recvTime;
        public BuildReplyMessageState(I2NPMessage m) {
            msg = (TunnelBuildReplyMessage)m;
            recvTime = System.currentTimeMillis();
        }
    }
    /** replies for inbound tunnels we have created */
    private static class BuildEndMessageState {
        TunnelBuildMessage msg;
        PooledTunnelCreatorConfig cfg;
        long recvTime;
        public BuildEndMessageState(PooledTunnelCreatorConfig c, I2NPMessage m) {
            cfg = c;
            msg = (TunnelBuildMessage)m;
            recvTime = System.currentTimeMillis();
        }
    }

    // noop
    private static class TunnelBuildMessageHandlerJob extends JobImpl {
        private TunnelBuildMessageHandlerJob(RouterContext ctx) { super(ctx); }
        public void runJob() {}
        public String getName() { return "Receive tunnel build message"; }
    }
    // noop
    private static class TunnelBuildReplyMessageHandlerJob extends JobImpl {
        private TunnelBuildReplyMessageHandlerJob(RouterContext ctx) { super(ctx); }
        public void runJob() {}
        public String getName() { return "Receive tunnel build reply message"; }
    }

    /**
     *  Remove the participating tunnel if we can't contact the next hop
     *  Not strictly necessary, as the entry doesn't use that much space,
     *  but it affects capacity calculations
     */
    private static class TunnelBuildNextHopFailJob extends JobImpl {
        HopConfig _cfg;
        private TunnelBuildNextHopFailJob(RouterContext ctx, HopConfig cfg) {
            super(ctx);
            _cfg = cfg;
        }
        public String getName() { return "Timeout contacting next peer for tunnel join"; }
        public void runJob() {
            getContext().tunnelDispatcher().remove(_cfg);
            getContext().statManager().addRateData("tunnel.rejectTimeout2", 1, 0);
            // static, no _log
            //_log.error("Cant contact next hop for " + _cfg);
        }
    }
}
