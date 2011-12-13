package net.i2p.router.tunnel.pool;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

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
import net.i2p.data.i2np.VariableTunnelBuildMessage;
import net.i2p.data.i2np.VariableTunnelBuildReplyMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.router.tunnel.BuildMessageProcessor;
import net.i2p.router.tunnel.BuildReplyHandler;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Handle the received tunnel build message requests and replies,
 * including sending responsses to requests, updating the
 * lists of our tunnels and participating tunnels,
 * and updating stats.
 *
 * Replies are handled immediately on reception; requests are queued.
 * As of 0.8.11 the request queue is handled in a separate thread,
 * it used to be called from the BuildExecutor thread loop.
 *
 * Note that 10 minute tunnel expiration is hardcoded in here.
 */
class BuildHandler implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private final TunnelPoolManager _manager;
    private final BuildExecutor _exec;
    private final Job _buildMessageHandlerJob;
    private final Job _buildReplyMessageHandlerJob;
    private final LinkedBlockingQueue<BuildMessageState> _inboundBuildMessages;
    private final BuildMessageProcessor _processor;
    private final ParticipatingThrottler _throttler;
    private boolean _isRunning;

    /** TODO these may be too high, review and adjust */
    private static final int MIN_QUEUE = 18;
    private static final int MAX_QUEUE = 192;

    private static final int NEXT_HOP_LOOKUP_TIMEOUT = 15*1000;
    
    /**
     *  This must be high, as if we timeout the send we remove the tunnel from
     *  participating via OnFailedSendJob.
     *  If them msg actually got through then we will be dropping
     *  all the traffic in TunnelDispatcher.dispatch(TunnelDataMessage msg, Hash recvFrom).
     *  10s was not enough.
     */
    private static final int NEXT_HOP_SEND_TIMEOUT = 25*1000;

    public BuildHandler(RouterContext ctx, TunnelPoolManager manager, BuildExecutor exec) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = manager;
        _exec = exec;
        // Queue size = 12 * share BW / 48K
        int sz = Math.min(MAX_QUEUE, Math.max(MIN_QUEUE, TunnelDispatcher.getShareBandwidth(ctx) * MIN_QUEUE / 48));
        _inboundBuildMessages = new LinkedBlockingQueue(sz);
    
        _context.statManager().createRateStat("tunnel.reject.10", "How often we reject a tunnel probabalistically", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.reject.20", "How often we reject a tunnel because of transient overload", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.reject.30", "How often we reject a tunnel because of bandwidth overload", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.reject.50", "How often we reject a tunnel because of a critical issue (shutdown, etc)", "Tunnels", new long[] { 60*1000, 10*60*1000 });

        _context.statManager().createRequiredRateStat("tunnel.decryptRequestTime", "Time to decrypt a build request (ms)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.rejectTimeout", "Reject tunnel count (unknown next hop)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.rejectTimeout2", "Reject tunnel count (can't contact next hop)", "Tunnels", new long[] { 60*1000, 10*60*1000 });

        _context.statManager().createRequiredRateStat("tunnel.rejectOverloaded", "Delay to process rejected request (ms)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.acceptLoad", "Delay to process accepted request (ms)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.dropConnLimits", "Drop instead of reject due to conn limits", "Tunnels", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("tunnel.rejectConnLimits", "Reject due to conn limits", "Tunnels", new long[] { 10*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.dropLoad", "Delay before dropping request (ms)?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.dropLoadDelay", "Delay before abandoning request (ms)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.dropLoadBacklog", "Pending request count when dropped", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.dropLoadProactive", "Delay estimate when dropped (ms)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.dropLoadProactiveAbort", "Allowed requests during load", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        //_context.statManager().createRateStat("tunnel.handleRemaining", "How many pending inbound requests were left on the queue after one pass?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.buildReplyTooSlow", "How often a tunnel build reply came back after we had given up waiting for it?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        
        _context.statManager().createRateStat("tunnel.receiveRejectionProbabalistic", "How often we are rejected probabalistically?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tunnel.receiveRejectionTransient", "How often we are rejected due to transient overload?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tunnel.receiveRejectionBandwidth", "How often we are rejected due to bandwidth overload?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("tunnel.receiveRejectionCritical", "How often we are rejected due to critical failure?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });

        _context.statManager().createRateStat("tunnel.corruptBuildReply", "", "Tunnels", new long[] { 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.buildLookupSuccess", "Was a deferred lookup successful?", "Tunnels", new long[] { 60*60*1000 });
        
        _processor = new BuildMessageProcessor(ctx);
        _throttler = new ParticipatingThrottler(ctx);
        _buildMessageHandlerJob = new TunnelBuildMessageHandlerJob(ctx);
        _buildReplyMessageHandlerJob = new TunnelBuildReplyMessageHandlerJob(ctx);
        TunnelBuildMessageHandlerJobBuilder tbmhjb = new TunnelBuildMessageHandlerJobBuilder();
        TunnelBuildReplyMessageHandlerJobBuilder tbrmhjb = new TunnelBuildReplyMessageHandlerJobBuilder();
        ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelBuildMessage.MESSAGE_TYPE, tbmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelBuildReplyMessage.MESSAGE_TYPE, tbrmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(VariableTunnelBuildMessage.MESSAGE_TYPE, tbmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(VariableTunnelBuildReplyMessage.MESSAGE_TYPE, tbrmhjb);
    }
    
    /**
     * Thread to handle inbound requests
     * @since 0.8.11
     */
    public void run() {
        _isRunning = true;
        while (!_manager.isShutdown()) {
            try {
                handleInboundRequest();
            } catch (Exception e) {
                _log.log(Log.CRIT, "B0rked in the tunnel handler", e);
            }
        }
        if (_log.shouldLog(Log.WARN))
            _log.warn("Done handling");
        _isRunning = false;
    }

    /**
     * Blocking call to handle a single inbound request
     */
    private void handleInboundRequest() {
        BuildMessageState state = null;

            try {
                state = _inboundBuildMessages.take();
            } catch (InterruptedException ie) {
                return;
            }
            long dropBefore = System.currentTimeMillis() - (BuildRequestor.REQUEST_TIMEOUT/4);
            if (state.recvTime <= dropBefore) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not even trying to handle/decrypt the request " + state.msg.getUniqueId() 
                              + ", since we received it a long time ago: " + (System.currentTimeMillis() - state.recvTime));
                _context.statManager().addRateData("tunnel.dropLoadDelay", System.currentTimeMillis() - state.recvTime, 0);
                _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: Too slow"));
                return;
            }       
            handleRequest(state);

        //int remaining = _inboundBuildMessages.size();
        //if (remaining > 0)
        //    _context.statManager().addRateData("tunnel.handleRemaining", remaining, 0);
        //return remaining;
    }
    
    /**
     * Blocking call to handle a single inbound reply
     */
    private void handleReply(BuildReplyMessageState state) {
        // search through the tunnels for a reply
        long replyMessageId = state.msg.getUniqueId();
        PooledTunnelCreatorConfig cfg = _exec.removeFromBuilding(replyMessageId);
        StringBuilder buf = null;
        
        if (cfg == null) {
            // cannot handle - not pending... took too long?
            if (_log.shouldLog(Log.WARN))
                _log.warn("The reply " + replyMessageId + " did not match any pending tunnels");
            _context.statManager().addRateData("tunnel.buildReplyTooSlow", 1, 0);
        } else {
            handleReply(state.msg, cfg, System.currentTimeMillis()-state.recvTime);
        }
    }
    
    /**
     * Blocking call to handle a single inbound reply
     */
    private void handleReply(TunnelBuildReplyMessage msg, PooledTunnelCreatorConfig cfg, long delay) {
        long requestedOn = cfg.getExpiration() - 10*60*1000;
        long rtt = _context.clock().now() - requestedOn;
        if (_log.shouldLog(Log.INFO))
            _log.info(msg.getUniqueId() + ": Handling the reply after " + rtt + ", delayed " + delay + " waiting for " + cfg);
        
        List<Integer> order = cfg.getReplyOrder();
        int statuses[] = BuildReplyHandler.decrypt(_context, msg, cfg, order);
        if (statuses != null) {
            boolean allAgree = true;
            // For each peer in the tunnel
            for (int i = 0; i < cfg.getLength(); i++) {
                Hash peer = cfg.getPeer(i);
                // If this tunnel member is us, skip this record, don't update profile or stats
                // for ourselves, we always agree
                // Why must we save a slot for ourselves anyway?
                if (peer.equals(_context.routerHash()))
                    continue;

                int record = order.indexOf(Integer.valueOf(i));
                if (record < 0) {
                    _log.error("Bad status index " + i);
                    // don't leak
                    _exec.buildComplete(cfg, cfg.getTunnelPool());
                    return;
                }

                int howBad = statuses[record];

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
                        _log.info(msg.getUniqueId() + ": Peer " + peer + " replied with status " + howBad);

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

            if (allAgree) {
                // wikked, completely build
                if (cfg.isInbound())
                    _context.tunnelDispatcher().joinInbound(cfg);
                else
                    _context.tunnelDispatcher().joinOutbound(cfg);
                cfg.getTunnelPool().addTunnel(cfg); // self.self.self.foo!
                // call buildComplete() after addTunnel() so we don't try another build.
                _exec.buildComplete(cfg, cfg.getTunnelPool());
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
                _exec.buildComplete(cfg, cfg.getTunnelPool());
                if (cfg.getDestination() == null)
                    _context.statManager().addRateData("tunnel.buildExploratoryReject", rtt, rtt);
                else
                    _context.statManager().addRateData("tunnel.buildClientReject", rtt, rtt);
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn(msg.getUniqueId() + ": Tunnel reply could not be decrypted for tunnel " + cfg);
            _context.statManager().addRateData("tunnel.corruptBuildReply", 1, 0);
            // don't leak
            _exec.buildComplete(cfg, cfg.getTunnelPool());
        }
    }
    
    /** @return handle time or -1 if it wasn't completely handled */
    private long handleRequest(BuildMessageState state) {
        long timeSinceReceived = System.currentTimeMillis()-state.recvTime;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(state.msg.getUniqueId() + ": handling request after " + timeSinceReceived);
        
        if (timeSinceReceived > (BuildRequestor.REQUEST_TIMEOUT*3)) {
            // don't even bother, since we are so overloaded locally
            _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: Overloaded"));
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
        if (decryptTime > 500 && _log.shouldLog(Log.WARN))
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
        if (lookupTime > 500 && _log.shouldLog(Log.WARN))
            _log.warn("Took too long to lookup the request: " + lookupTime + "/" + readPeerTime + " for message " + state.msg.getUniqueId() + " received " + (timeSinceReceived+decryptTime) + " ago");
        if (nextPeerInfo == null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + state.msg.getUniqueId() + "/" + req.readReceiveTunnelId() + "/" + req.readNextTunnelId() 
                           + " handled, looking for the next peer " + nextPeer);
            _context.netDb().lookupRouterInfo(nextPeer, new HandleReq(_context, state, req, nextPeer),
                                              new TimeoutReq(_context, state, req, nextPeer), NEXT_HOP_LOOKUP_TIMEOUT);
            return -1;
        } else {
            long beforeHandle = System.currentTimeMillis();
            handleReq(nextPeerInfo, state, req, nextPeer);
            long handleTime = System.currentTimeMillis() - beforeHandle;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + state.msg.getUniqueId() + " handled and we know the next peer " 
                           + nextPeer + " after " + handleTime
                           + "/" + decryptTime + "/" + lookupTime + "/" + timeSinceReceived);
            return handleTime;
        }
    }
    
    /**
     * This request is actually a reply, process it as such
     */
    private void handleRequestAsInboundEndpoint(BuildEndMessageState state) {
        int records = state.msg.getRecordCount();
        TunnelBuildReplyMessage msg;
        if (records == TunnelBuildMessage.MAX_RECORD_COUNT)
            msg = new TunnelBuildReplyMessage(_context);
        else
            msg = new VariableTunnelBuildReplyMessage(_context, records);
        for (int i = 0; i < records; i++)
            msg.setRecord(i, state.msg.getRecord(i));
        msg.setUniqueId(state.msg.getUniqueId());
        handleReply(msg, state.cfg, System.currentTimeMillis() - state.recvTime);
    }
    
    private class HandleReq extends JobImpl {
        private final BuildMessageState _state;
        private final BuildRequestRecord _req;
        private final Hash _nextPeer;
        HandleReq(RouterContext ctx, BuildMessageState state, BuildRequestRecord req, Hash nextPeer) {
            super(ctx);
            _state = state;
            _req = req;
            _nextPeer = nextPeer;
        }
        public String getName() { return "Deferred tunnel join processing"; }
        public void runJob() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + _state.msg.getUniqueId() + " handled with a successful deferred lookup for the next peer " + _nextPeer);

            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(_nextPeer);
            if (ri != null) {
                handleReq(ri, _state, _req, _nextPeer);
                getContext().statManager().addRateData("tunnel.buildLookupSuccess", 1, 0);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Deferred successfully, but we couldnt find " + _nextPeer);
                getContext().statManager().addRateData("tunnel.buildLookupSuccess", 0, 0);
            }
        }
    }

    private static class TimeoutReq extends JobImpl {
        private final BuildMessageState _state;
        private final BuildRequestRecord _req;
        private final Hash _nextPeer;
        TimeoutReq(RouterContext ctx, BuildMessageState state, BuildRequestRecord req, Hash nextPeer) {
            super(ctx);
            _state = state;
            _req = req;
            _nextPeer = nextPeer;
        }
        public String getName() { return "Timeout looking for next peer for tunnel join"; }
        public void runJob() {
            getContext().statManager().addRateData("tunnel.rejectTimeout", 1, 0);
            getContext().statManager().addRateData("tunnel.buildLookupSuccess", 0, 0);
            // logging commented out so class can be static
            //if (_log.shouldLog(Log.WARN))
            //    _log.warn("Request " + _state.msg.getUniqueId() 
            //              + " could no be satisfied, as the next peer could not be found: " + _nextPeer.toBase64());

            // ???  should we blame the peer here?   getContext().profileManager().tunnelTimedOut(_nextPeer);
            getContext().messageHistory().tunnelRejected(_state.fromHash, new TunnelId(_req.readReceiveTunnelId()), _nextPeer, 
                                                         "rejected because we couldn't find " + _nextPeer + ": " +
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
    
    /**
     *  Actually process the request and send the reply.
     *
     *  Todo: Replies are not subject to RED for bandwidth reasons,
     *  and the bandwidth is not credited to any tunnel.
     *  If we did credit the reply to the tunnel, it would
     *  prevent the classification of the tunnel as 'inactive' on tunnels.jsp.
     */
    private void handleReq(RouterInfo nextPeerInfo, BuildMessageState state, BuildRequestRecord req, Hash nextPeer) {
        long ourId = req.readReceiveTunnelId();
        long nextId = req.readNextTunnelId();
        boolean isInGW = req.readIsInboundGateway();
        boolean isOutEnd = req.readIsOutboundEndpoint();

        // Loop checks
        if ((!isOutEnd) && _context.routerHash().equals(nextPeer)) {
            // We are 2 hops in a row? Drop it without a reply.
            // No way to recognize if we are every other hop, but see below
            _log.error("Dropping build request, we the next hop");
            return;
        }
        // previous test should be sufficient to keep it from getting here but maybe not?
        if (!isInGW) {
            Hash from = state.fromHash;
            if (from == null)
                from = state.from.calculateHash();
            if (_context.routerHash().equals(from)) {
                _log.error("Dropping build request, we are the previous hop");
                return;
            }
        }
        if ((!isOutEnd) && (!isInGW)) {
            Hash from = state.fromHash;
            if (from == null)
                from = state.from.calculateHash();
            // Previous and next hop the same? Don't help somebody be evil. Drop it without a reply.
            // A-B-C-A is not preventable
            if (nextPeer.equals(from)) {
                _log.error("Dropping build request with the same previous and next hop");
                return;
            }
        }

        // time is in hours, and only for log below - what's the point?
        // tunnel-alt-creation.html specifies that this is enforced +/- 1 hour but it is not.
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
                _context.throttle().setTunnelStatus(_x("Rejecting tunnels: Request overload"));
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
         * Don't do this for class N or O, under the assumption that they are already talking
         * to most of the routers, so there's no reason to reject. This may drive them
         * to their conn. limits, but it's hopefully a temporary solution to the
         * tunnel build congestion. As the net grows this will have to be revisited.
         */
        RouterInfo ri = _context.router().getRouterInfo();
        if (response == 0) {
            if (ri == null) {
                // ?? We should always have a RI
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
            } else {
                char bw = ri.getBandwidthTier().charAt(0);
                if (bw != 'O' && bw != 'N' &&
                    ((isInGW && ! _context.commSystem().haveInboundCapacity(87)) ||
                     (isOutEnd && ! _context.commSystem().haveOutboundCapacity(87)))) {
                        _context.statManager().addRateData("tunnel.rejectConnLimits", 1);
                        _context.throttle().setTunnelStatus(_x("Rejecting tunnels: Connection limit"));
                        response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                }
            }
        }
        
        // Check participating throttle counters for previous and next hops
        // This is at the end as it compares to a percentage of created tunnels.
        // We may need another counter above for requests.
        if (response == 0 && !isInGW) {
            Hash from = state.fromHash;
            if (from == null)
                from = state.from.calculateHash();
            if (from != null && _throttler.shouldThrottle(from)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Rejecting tunnel (hop throttle), previous hop: " + from);
                // no setTunnelStatus() indication
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
            }
        }
        if (response == 0 && (!isOutEnd) &&
            _throttler.shouldThrottle(nextPeer)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rejecting tunnel (hop throttle), next hop: " + nextPeer);
            // no setTunnelStatus() indication
            response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Responding to " + state.msg.getUniqueId() + "/" + ourId
                       + " after " + recvDelay + "/" + proactiveDrops + " with " + response 
                       + " from " + (state.fromHash != null ? state.fromHash : 
                                     state.from != null ? state.from.calculateHash() : "tunnel"));

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
                cfg.setSendTo(nextPeer);
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
            _context.messageHistory().tunnelRejected(state.fromHash, new TunnelId(ourId), nextPeer, 
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

        byte reply[] = BuildResponseRecord.create(_context, response, req.readReplyKey(), req.readReplyIV(), state.msg.getUniqueId());
        int records = state.msg.getRecordCount();
        for (int j = 0; j < records; j++) {
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
            _log.debug("Read slot " + ourSlot + " containing our hop @ " + _context.routerHash()
                      + " accepted? " + response + " receiving on " + ourId 
                      + " sending to " + nextId
                      + " on " + nextPeer
                      + " inGW? " + isInGW + " outEnd? " + isOutEnd + " time difference " + (now-time)
                      + " recvDelay " + recvDelay + " replyMessage " + req.readReplyMessageId()
                      + " replyKey " + req.readReplyKey() + " replyIV " + Base64.encode(req.readReplyIV()));

        // now actually send the response
        if (!isOutEnd) {
            state.msg.setUniqueId(req.readReplyMessageId());
            state.msg.setMessageExpiration(_context.clock().now() + NEXT_HOP_SEND_TIMEOUT);
            OutNetMessage msg = new OutNetMessage(_context);
            msg.setMessage(state.msg);
            msg.setExpiration(state.msg.getMessageExpiration());
            msg.setPriority(300);
            msg.setTarget(nextPeerInfo);
            if (response == 0)
                msg.setOnFailedSendJob(new TunnelBuildNextHopFailJob(_context, cfg));
            _context.outNetMessagePool().add(msg);
        } else {
            // We are the OBEP.
            // send it to the reply tunnel on the reply peer within a new TunnelBuildReplyMessage
            // (enough layers jrandom?)
            TunnelBuildReplyMessage replyMsg;
            if (records == TunnelBuildMessage.MAX_RECORD_COUNT)
                replyMsg = new TunnelBuildReplyMessage(_context);
            else
                replyMsg = new VariableTunnelBuildReplyMessage(_context, records);
            for (int i = 0; i < records; i++)
                replyMsg.setRecord(i, state.msg.getRecord(i));
            replyMsg.setUniqueId(req.readReplyMessageId());
            replyMsg.setMessageExpiration(_context.clock().now() + NEXT_HOP_SEND_TIMEOUT);
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
            return _inboundBuildMessages.size();
    }
    
    /**
     *  Handle incoming Tunnel Build Messages, which are generally requests to us,
     *  but could also be the reply where we are the IBEP.
     */
    private class TunnelBuildMessageHandlerJobBuilder implements HandlerJobBuilder {
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            // need to figure out if this is a reply to an inbound tunnel request (where we are the
            // endpoint, receiving the request at the last hop)
            long reqId = receivedMessage.getUniqueId();
            PooledTunnelCreatorConfig cfg = _exec.removeFromBuilding(reqId);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive tunnel build message " + reqId + " from " 
                           + (from != null ? from.calculateHash() : fromHash != null ? fromHash : "tunnels") 
                           + ", found matching tunnel? " + (cfg != null));
            if (cfg != null) {
                if (!cfg.isInbound()) {
                    // shouldnt happen - should we put it back?
                    _log.error("received it, but its not inbound? " + cfg);
                }
                BuildEndMessageState state = new BuildEndMessageState(cfg, receivedMessage);
                handleRequestAsInboundEndpoint(state);
            } else {
                if (_exec.wasRecentlyBuilding(reqId)) {
                    // we are the IBEP but we already gave up?
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping the reply " + reqId + ", as we used to be building that");
                    _context.statManager().addRateData("tunnel.buildReplyTooSlow", 1, 0);
                } else {
                    int sz = _inboundBuildMessages.size();
                    BuildMessageState cur = _inboundBuildMessages.peek();
                    boolean accept = true;
                    if (cur != null) {
                        long age = System.currentTimeMillis() - cur.recvTime;
                        if (age >= BuildRequestor.REQUEST_TIMEOUT/4) {
                            _context.statManager().addRateData("tunnel.dropLoad", age, sz);
                            _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: High load"));
                            // if the queue is backlogged, stop adding new messages
                            _context.statManager().addRateData("tunnel.dropLoadBacklog", sz, sz);
                            accept = false;
                        }
                    }
                    if (accept) {
                        int queueTime = estimateQueueTime(sz);
                        float pDrop = queueTime/((float)BuildRequestor.REQUEST_TIMEOUT*3);
                        pDrop = (float)Math.pow(pDrop, 16); // steeeep
                        float f = _context.random().nextFloat();
                        //if ( (pDrop > f) && (allowProactiveDrop()) ) {
                        if (pDrop > f) {
                            _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: Queue time"));
                            _context.statManager().addRateData("tunnel.dropLoadProactive", queueTime, sz);
                        } else {
                            accept = _inboundBuildMessages.offer(new BuildMessageState(receivedMessage, from, fromHash));
                            if (accept) {
                                // wake up the Executor to call handleInboundRequests()
                                _exec.repoll();
                            } else {
                                _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: High load"));
                                _context.statManager().addRateData("tunnel.dropLoadBacklog", sz, sz);
                            }
                        }
                    }
                }
            }
            return _buildMessageHandlerJob;
        }
    }
    
/****
    private boolean allowProactiveDrop() {
        boolean rv = _context.getBooleanPropertyDefaultTrue("router.allowProactiveDrop");
        if (!rv)
            _context.statManager().addRateData("tunnel.dropLoadProactiveAbort", 1, 0);
        return rv;
    }
****/
    
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
                           + (fromHash != null ? fromHash : from != null ? from.calculateHash() : "a tunnel"));
            handleReply(new BuildReplyMessageState(receivedMessage));
            return _buildReplyMessageHandlerJob;
        }
    }
    
    /** normal inbound requests from other people */
    private static class BuildMessageState {
        final TunnelBuildMessage msg;
        final RouterIdentity from;
        final Hash fromHash;
        final long recvTime;
        public BuildMessageState(I2NPMessage m, RouterIdentity f, Hash h) {
            msg = (TunnelBuildMessage)m;
            from = f;
            fromHash = h;
            recvTime = System.currentTimeMillis();
        }
    }

    /** replies for outbound tunnels that we have created */
    private static class BuildReplyMessageState {
        final TunnelBuildReplyMessage msg;
        final long recvTime;
        public BuildReplyMessageState(I2NPMessage m) {
            msg = (TunnelBuildReplyMessage)m;
            recvTime = System.currentTimeMillis();
        }
    }

    /** replies for inbound tunnels we have created */
    private static class BuildEndMessageState {
        final TunnelBuildMessage msg;
        final PooledTunnelCreatorConfig cfg;
        final long recvTime;
        public BuildEndMessageState(PooledTunnelCreatorConfig c, I2NPMessage m) {
            cfg = c;
            msg = (TunnelBuildMessage)m;
            recvTime = System.currentTimeMillis();
        }
    }

    /** noop */
    private static class TunnelBuildMessageHandlerJob extends JobImpl {
        private TunnelBuildMessageHandlerJob(RouterContext ctx) { super(ctx); }
        public void runJob() {}
        public String getName() { return "Receive tunnel build message"; }
    }

    /** noop */
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
        private final HopConfig _cfg;

        private TunnelBuildNextHopFailJob(RouterContext ctx, HopConfig cfg) {
            super(ctx);
            _cfg = cfg;
        }

        public String getName() { return "Timeout contacting next peer for tunnel join"; }

        public void runJob() {
            getContext().tunnelDispatcher().remove(_cfg);
            getContext().statManager().addRateData("tunnel.rejectTimeout2", 1, 0);
            Log log = getContext().logManager().getLog(BuildHandler.class);
            if (log.shouldLog(Log.WARN))
                log.warn("Timeout contacting next hop for " + _cfg);
        }
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {
        return s;
    }
}
