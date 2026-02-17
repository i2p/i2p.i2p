package net.i2p.router.tunnel.pool;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.crypto.EncType;
import net.i2p.data.DataHelper;
import net.i2p.data.EmptyProperties;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.BuildRequestRecord;
import net.i2p.data.i2np.BuildResponseRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.OutboundTunnelBuildReplyMessage;
import net.i2p.data.i2np.ShortTunnelBuildMessage;
import net.i2p.data.i2np.ShortTunnelBuildReplyMessage;
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
import net.i2p.router.RouterThrottleImpl;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;
import static net.i2p.router.tunnel.pool.BuildExecutor.Result.*;
import net.i2p.router.util.CDQEntry;
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
 *
 * There is only one of these objects but there may be multiple
 * threads running it. Instantiated and started by TunnelPoolManager.
 *
 */
class BuildHandler implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private final TunnelPoolManager _manager;
    private final BuildExecutor _exec;
    private final Job _buildMessageHandlerJob;
    private final Job _buildReplyMessageHandlerJob;
    private final BlockingQueue<BuildMessageState> _inboundBuildMessages;
    private final BuildMessageProcessor _processor;
    private final RequestThrottler _requestThrottler;
    private final ParticipatingThrottler _throttler;
    private final BuildReplyHandler _buildReplyHandler;
    private final AtomicInteger _currentLookups = new AtomicInteger();
    private volatile boolean _isRunning;
    private final Object _startupLock = new Object();
    private ExplState _explState = ExplState.NONE;

    private enum ExplState { NONE, IB, OB, BOTH }

    /** TODO these may be too high, review and adjust */
    private static final int MIN_QUEUE = 18;
    private static final int MAX_QUEUE = 192;

    private static final int NEXT_HOP_LOOKUP_TIMEOUT = 15*1000;
    private static final int PRIORITY = OutNetMessage.PRIORITY_BUILD_REPLY;

    /** limits on concurrent next-hop RI lookup */
    private static final int MIN_LOOKUP_LIMIT = 10;
    private static final int MAX_LOOKUP_LIMIT = 100;
    /** limit lookups to this % of current participating tunnels */
    private static final int PERCENT_LOOKUP_LIMIT = 3;
    
    /**
     *  This must be high, as if we timeout the send we remove the tunnel from
     *  participating via OnFailedSendJob.
     *  If them msg actually got through then we will be dropping
     *  all the traffic in TunnelDispatcher.dispatch(TunnelDataMessage msg, Hash recvFrom).
     *  10s was not enough.
     */
    private static final int NEXT_HOP_SEND_TIMEOUT = 25*1000;

    private static final long MAX_REQUEST_FUTURE = 5*60*1000;
    /** must be > 1 hour due to rouding down */
    private static final long MAX_REQUEST_AGE = 65*60*1000;
    private static final long MAX_REQUEST_AGE_ECIES = 8*60*1000;

    private static final long JOB_LAG_LIMIT_TUNNEL = 350;

    /**
     * This is the baseline minimum for estimating tunnel bandwidth, if accepted.
     * We use an estimate of 40 messages (1 KB each) in 10 minutes.
     *
     * 40 KB in 10 minutes equals 67 Bps.
     */
    private static final int DEFAULT_BW_PER_TUNNEL_ESTIMATE = RouterThrottleImpl.DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE * 1024 / (10*60);

    public BuildHandler(RouterContext ctx, TunnelPoolManager manager, BuildExecutor exec) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = manager;
        _exec = exec;
        // Queue size = 12 * share BW / 48K
        int sz = Math.min(MAX_QUEUE, Math.max(MIN_QUEUE, TunnelDispatcher.getShareBandwidth(ctx) * MIN_QUEUE / 48));
        //_inboundBuildMessages = new CoDelBlockingQueue(ctx, "BuildHandler", sz);
        _inboundBuildMessages = new LinkedBlockingQueue<BuildMessageState>(sz);
    
        //_context.statManager().createRateStat("tunnel.reject.10", "How often we reject a tunnel probabalistically", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        //_context.statManager().createRateStat("tunnel.reject.20", "How often we reject a tunnel because of transient overload", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.reject.30", "How often we reject a tunnel because of bandwidth overload", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        //_context.statManager().createRateStat("tunnel.reject.50", "How often we reject a tunnel because of a critical issue (shutdown, etc)", "Tunnels", new long[] { 60*1000, 10*60*1000 });

        _context.statManager().createRequiredRateStat("tunnel.decryptRequestTime", "Time to decrypt a build request (ms)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.rejectTooOld", "Reject tunnel count (too old)", "Tunnels", new long[] { 3*60*60*1000 });
        _context.statManager().createRateStat("tunnel.rejectFuture", "Reject tunnel count (time in future)", "Tunnels", new long[] { 3*60*60*1000 });
        _context.statManager().createRateStat("tunnel.rejectTimeout", "Reject tunnel count (unknown next hop)", "Tunnels", new long[] { 60*60*1000 });
        _context.statManager().createRateStat("tunnel.rejectTimeout2", "Reject tunnel count (can't contact next hop)", "Tunnels", new long[] { 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.rejectDupID", "Part. tunnel dup ID", "Tunnels", new long[] { 24*60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.ownDupID", "Our tunnel dup. ID", "Tunnels", new long[] { 24*60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.rejectHostile", "Reject malicious tunnel", "Tunnels", new long[] { 24*60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.rejectHopThrottle", "Reject per-hop limit", "Tunnels", new long[] { 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.dropReqThrottle", "Drop per-hop limit", "Tunnels", new long[] { 60*60*1000 });
        _context.statManager().createRequiredRateStat("tunnel.dropLookupThrottle", "Drop next hop lookup", "Tunnels", new long[] { 60*60*1000 });
        _context.statManager().createRateStat("tunnel.dropDecryptFail", "Can't find our slot", "Tunnels", new long[] { 60*60*1000 });

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
        // used for previous hop, for all requests
        boolean testMode = ctx.getBooleanProperty("i2np.allowLocal");
        _requestThrottler = testMode ? null : new RequestThrottler(ctx);
        // used for previous and next hops, for successful builds only
        _throttler = testMode ? null : new ParticipatingThrottler(ctx);
        _buildReplyHandler = new BuildReplyHandler(ctx);
        _buildMessageHandlerJob = new TunnelBuildMessageHandlerJob(ctx);
        _buildReplyMessageHandlerJob = new TunnelBuildReplyMessageHandlerJob(ctx);
        TunnelBuildMessageHandlerJobBuilder tbmhjb = new TunnelBuildMessageHandlerJobBuilder();
        TunnelBuildReplyMessageHandlerJobBuilder tbrmhjb = new TunnelBuildReplyMessageHandlerJobBuilder();
        ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelBuildMessage.MESSAGE_TYPE, tbmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(TunnelBuildReplyMessage.MESSAGE_TYPE, tbrmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(VariableTunnelBuildMessage.MESSAGE_TYPE, tbmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(VariableTunnelBuildReplyMessage.MESSAGE_TYPE, tbrmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(ShortTunnelBuildMessage.MESSAGE_TYPE, tbmhjb);
        ctx.inNetMessagePool().registerHandlerJobBuilder(OutboundTunnelBuildReplyMessage.MESSAGE_TYPE, tbrmhjb);
    }

    /**
     *  Call the same time you start the threads
     *
     *  @since 0.9.18
     */
    void init() {
        if (_context.commSystem().isDummy()) {
            _explState = ExplState.BOTH;
            _context.router().setExplTunnelsReady();
            return;
        }
        // fixup startup state if 0-hop exploratory is allowed in either direction
        int ibl = _manager.getInboundSettings().getLength();
        int ibv = _manager.getInboundSettings().getLengthVariance();
        int obl = _manager.getOutboundSettings().getLength();
        int obv = _manager.getOutboundSettings().getLengthVariance();
        boolean ibz = ibl <= 0 || ibl + ibv <= 0;
        boolean obz = obl <= 0 || obl + obv <= 0;
        if (ibz && obz) {
            _explState = ExplState.BOTH;
            _context.router().setExplTunnelsReady();
        } else if (ibz) {
            _explState = ExplState.IB;
        } else if (obz) {
            _explState = ExplState.OB;
        }
    }

    
    /**
     *  @since 0.9
     */
    public void restart() {
        _inboundBuildMessages.clear();
    }

    /**
     *  Cannot be restarted.
     *  @param numThreads the number of threads to be shut down
     *  @since 0.9
     */
    public synchronized void shutdown(int numThreads) {
        _isRunning = false;
        _inboundBuildMessages.clear();
        BuildMessageState poison = new BuildMessageState(_context, null, null, null);
        for (int i = 0; i < numThreads; i++) {
            _inboundBuildMessages.offer(poison);
        }
    }

    /**
     * Thread to handle inbound requests
     * @since 0.8.11
     */
    public void run() {
        _isRunning = true;
        while (_isRunning && !_manager.isShutdown()) {
            try {
                handleInboundRequest();
            } catch (RuntimeException e) {
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

            // check for poison
            if (state.msg == null) {
                _isRunning = false;
                return;
            }

            long now = _context.clock().now();
            long dropBefore = now - (BuildRequestor.REQUEST_TIMEOUT/4);
            if (state.recvTime <= dropBefore) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not even trying to handle/decrypt the request " + state.msg.getUniqueId() 
                              + ", since we received it a long time ago: " + (now - state.recvTime));
                _context.statManager().addRateData("tunnel.dropLoadDelay", now - state.recvTime);
                _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: Too slow"));
                return;
            }       

            long lag = _context.jobQueue().getMaxLag();
            // TODO reject instead of drop also for a lower limit? see throttle
            if (lag > JOB_LAG_LIMIT_TUNNEL) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping tunnel request, as the job lag is " + lag);
                _context.statManager().addRateData("router.throttleTunnelCause", lag);
                _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: High job lag"));
                return;
            }       

            handleRequest(state, now);

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
        if (cfg == null) {
            // cannot handle - not pending... took too long?
            if (_log.shouldLog(Log.WARN))
                _log.warn("The reply " + replyMessageId + " did not match any pending tunnels");
            _context.statManager().addRateData("tunnel.buildReplyTooSlow", 1);
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
        BuildReplyHandler.Result statuses[] = _buildReplyHandler.decrypt(msg, cfg, order);
        if (statuses != null) {
            boolean allAgree = true;
            int allocated = 0;
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
                    _exec.buildComplete(cfg, BAD_RESPONSE);
                    return;
                }

                int howBad = statuses[record].code;

                    // Look up routerInfo
                    RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
                    // Default and detect bandwidth tier
                    String bwTier = "Unknown";
                    if (ri != null) bwTier = ri.getBandwidthTier(); // Returns "Unknown" if none recognized
                        else if (_log.shouldLog(Log.WARN)) _log.warn("Failed detecting bwTier, null routerInfo for: " + peer);
                    // Record that a peer of the given tier agreed or rejected
                    if (howBad == 0) {
                        _context.statManager().addRateData("tunnel.tierAgree" + bwTier, 1);
                    } else {
                        _context.statManager().addRateData("tunnel.tierReject" + bwTier, 1);
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info(msg.getUniqueId() + ": Peer " + peer + " replied with status " + howBad);

                if (howBad == 0) {
                    // w3wt
                    _context.profileManager().tunnelJoined(peer, rtt);
                    Properties props = statuses[record].props;
                    if (props != null) {
                        String avail = props.getProperty(BuildRequestor.PROP_AVAIL_BW);
                        if (avail != null) {
                            if (_log.shouldWarn())
                                _log.warn(msg.getUniqueId() + ": peer replied available: " + avail + "KBps");
                            try {
                                int av = Integer.parseInt(avail) * 1000;
                                if (av > 0 && (allocated == 0 || av < allocated))
                                    allocated = av;
                            } catch (NumberFormatException nfe) {}
                        }
                    }
                } else {
                    allAgree = false;
                    switch (howBad) {
                        case TunnelHistory.TUNNEL_REJECT_BANDWIDTH:
                            _context.statManager().addRateData("tunnel.receiveRejectionBandwidth", 1);
                            break;
                        case TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD:
                            _context.statManager().addRateData("tunnel.receiveRejectionTransient", 1);
                            break;
                        case TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT:
                            _context.statManager().addRateData("tunnel.receiveRejectionProbabalistic", 1);
                            break;
                        case TunnelHistory.TUNNEL_REJECT_CRIT:
                        default:
                            _context.statManager().addRateData("tunnel.receiveRejectionCritical", 1);
                    }
                    // penalize peer based on their reported error level
                    _context.profileManager().tunnelRejected(peer, rtt, howBad);
                    _context.messageHistory().tunnelParticipantRejected(peer, "peer rejected after " + rtt + " with " + howBad + ": " + cfg.toString());
                }
            }

            if (allAgree) {
                // wikked, completely build
                boolean success;
                if (cfg.isInbound())
                    success = _context.tunnelDispatcher().joinInbound(cfg);
                else
                    success = _context.tunnelDispatcher().joinOutbound(cfg);
                if (!success) {
                    // This will happen very rarely. We check for dups when
                    // creating the config, but we don't track IDs for builds in progress.
                    _context.statManager().addRateData("tunnel.ownDupID", 1);
                    _exec.buildComplete(cfg, DUP_ID);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dup ID for our own tunnel " + cfg);
                    return;
                }
                if (allocated > 0)
                    cfg.setAllocatedBW(allocated);
                _exec.buildComplete(cfg, SUCCESS);

                if (cfg.getTunnelPool().getSettings().isExploratory()) {
                    // Notify router that exploratory tunnels are ready
                    boolean isIn = cfg.isInbound();
                    synchronized(_startupLock) {
                        switch (_explState) {
                            case NONE:
                                if (isIn)
                                    _explState = ExplState.IB;
                                else
                                    _explState = ExplState.OB;
                                break;

                            case IB:
                                if (!isIn) {
                                    _explState = ExplState.BOTH;
                                    _context.router().setExplTunnelsReady();
                                }
                                break;

                            case OB:
                                if (isIn) {
                                    _explState = ExplState.BOTH;
                                    _context.router().setExplTunnelsReady();
                                }
                                break;

                            case BOTH:
                                break;
                        }
                    }
                }
                
                if (cfg.getDestination() == null)
                    _context.statManager().addRateData("tunnel.buildExploratorySuccess", rtt);
                else
                    _context.statManager().addRateData("tunnel.buildClientSuccess", rtt);
            } else {
                // someone is no fun
                _exec.buildComplete(cfg, REJECT);
                if (cfg.getDestination() == null)
                    _context.statManager().addRateData("tunnel.buildExploratoryReject", rtt);
                else
                    _context.statManager().addRateData("tunnel.buildClientReject", rtt);
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn(msg.getUniqueId() + ": Tunnel reply could not be decrypted for tunnel " + cfg);
            _context.statManager().addRateData("tunnel.corruptBuildReply", 1);
            // don't leak
            _exec.buildComplete(cfg, BAD_RESPONSE);
            // TODO blame everybody
        }
    }
    
    /**
     *  Decrypt the request, lookup the RI locally,
     *  and call handleReq() if found or queue a lookup job.
     *
     *  @return handle time or -1 if it wasn't completely handled
     */
    private long handleRequest(BuildMessageState state, long now) {
        long timeSinceReceived = now - state.recvTime;
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(state.msg.getUniqueId() + ": handling request after " + timeSinceReceived);
        
        Hash from = state.fromHash;
        if (from == null && state.from != null)
            from = state.from.calculateHash();
        if (from != null && _context.banlist().isBanlisted(from)) {
            // Usually won't have connected, but may have been banlisted after connect
            if (_log.shouldWarn())
                _log.warn("Drop request, previous peer is banned: " + from);
            _context.commSystem().mayDisconnect(from);
            return -1;
        }

        if (timeSinceReceived > (BuildRequestor.REQUEST_TIMEOUT*3)) {
            // don't even bother, since we are so overloaded locally
            _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: Overloaded"));
            if (_log.shouldLog(Log.WARN))
                _log.warn("Not even trying to handle/decrypt the request " + state.msg.getUniqueId() 
                           + ", since we received it a long time ago: " + timeSinceReceived);
            _context.statManager().addRateData("tunnel.dropLoadDelay", timeSinceReceived);
            if (from != null)
                _context.commSystem().mayDisconnect(from);
            return -1;
        }
        // ok, this is not our own tunnel, so we need to do some heavy lifting
        // this not only decrypts the current hop's record, but encrypts the other records
        // with the enclosed reply key
        long beforeDecrypt = System.currentTimeMillis();
        BuildRequestRecord req = _processor.decrypt(state.msg, _context.routerHash(), _context.keyManager().getPrivateKey());
        long decryptTime = System.currentTimeMillis() - beforeDecrypt;
        _context.statManager().addRateData("tunnel.decryptRequestTime", decryptTime);
        if (decryptTime > 500 && _log.shouldLog(Log.WARN))
            _log.warn("Took too long to decrypt the request: " + decryptTime + " for message " + state.msg.getUniqueId() + " received " + (timeSinceReceived+decryptTime) + " ago");
        if (req == null) {
            // no records matched, or the decryption failed.  bah
            // very common, probably very old routers confused about ECIES
            if (_log.shouldInfo())
                _log.info("The request " + state.msg.getUniqueId() + " could not be decrypted from: " + from);
            _context.statManager().addRateData("tunnel.dropDecryptFail", 1);
            if (from != null)
                _context.commSystem().mayDisconnect(from);
            return -1;
        }

        Hash nextPeer = req.readNextIdentity();
        if (_context.banlist().isBanlisted(nextPeer)) {
            if (_log.shouldWarn())
                _log.warn("Drop request, next peer is banned: " + nextPeer);
            if (from != null)
                _context.commSystem().mayDisconnect(from);
            return -1;
        }
        RouterInfo nextPeerInfo = _context.netDb().lookupRouterInfoLocally(nextPeer);
        if (nextPeerInfo == null) {
            // limit concurrent next-hop lookups to prevent job queue overload attacks
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            int limit = Math.max(MIN_LOOKUP_LIMIT, Math.min(MAX_LOOKUP_LIMIT, numTunnels * PERCENT_LOOKUP_LIMIT / 100));
            int current;
            // leaky counter, since it isn't reliable
            if (_context.random().nextInt(16) > 0)
                current = _currentLookups.incrementAndGet();
            else
                current = 1;
            if (current <= limit) {
                // don't let it go negative
                if (current <= 0)
                    _currentLookups.set(1);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Request " + req
                               + " From: " + from
                               + " ID: " + state.msg.getUniqueId()
                               + " handled, lookup next peer " + nextPeer
                               + " lookups: " + current + '/' + limit);
                _context.netDb().lookupRouterInfo(nextPeer, new HandleReq(_context, state, req, nextPeer),
                                              new TimeoutReq(_context, state, req, nextPeer), NEXT_HOP_LOOKUP_TIMEOUT);
            } else {
                _currentLookups.decrementAndGet();
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Drop next hop lookup, limit " + limit + ": " + req
                               + " From: " + from
                               + " ID: " + state.msg.getUniqueId());
                _context.statManager().addRateData("tunnel.dropLookupThrottle", 1);
                if (from != null)
                    _context.commSystem().mayDisconnect(from);
            }
            return -1;
        } else {
            long beforeHandle = System.currentTimeMillis();
            handleReq(nextPeerInfo, state, req, nextPeer);
            long handleTime = System.currentTimeMillis() - beforeHandle;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + req
                           + " From: " + from
                           + " ID: " + state.msg.getUniqueId()
                           + " handled and we know the next peer " 
                           + nextPeer + " after " + handleTime
                           + "/" + decryptTime + "/" + timeSinceReceived);
            return handleTime;
        }
    }
    
    /**
     * This request is actually a reply, process it as such
     */
    private void handleRequestAsInboundEndpoint(BuildEndMessageState state) {
        int records = state.msg.getRecordCount();
        TunnelBuildReplyMessage msg;
        if (state.msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE)
            msg = new ShortTunnelBuildReplyMessage(_context, records);
        else if (records == TunnelBuildMessage.MAX_RECORD_COUNT)
            msg = new TunnelBuildReplyMessage(_context);
        else
            msg = new VariableTunnelBuildReplyMessage(_context, records);
        for (int i = 0; i < records; i++) {
            msg.setRecord(i, state.msg.getRecord(i));
        }
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
            // decrement in-progress counter
            _currentLookups.decrementAndGet();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + _state.msg.getUniqueId() + " handled with a successful deferred lookup: " + _req);

            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(_nextPeer);
            if (ri != null) {
                handleReq(ri, _state, _req, _nextPeer);
                getContext().statManager().addRateData("tunnel.buildLookupSuccess", 1);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Deferred successfully, but we couldnt find " + _nextPeer + "? " + _req);
                getContext().statManager().addRateData("tunnel.buildLookupSuccess", 0);
            }
        }
    }

    private class TimeoutReq extends JobImpl {
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
            // decrement in-progress counter
            _currentLookups.decrementAndGet();
            getContext().statManager().addRateData("tunnel.rejectTimeout", 1);
            getContext().statManager().addRateData("tunnel.buildLookupSuccess", 0);
            if (_log.shouldInfo()) {
                Hash from = _state.fromHash;
                if (from == null && _state.from != null)
                    from = _state.from.calculateHash();
                _log.info("Next hop lookup failure: " + _req
                          + " From: " + from
                          + " ID: " + _state.msg.getUniqueId());
            }

            // ???  should we blame the peer here?   getContext().profileManager().tunnelTimedOut(_nextPeer);
            getContext().messageHistory().tunnelRejected(_state.fromHash, new TunnelId(_req.readReceiveTunnelId()), _nextPeer, 
                                                         // this is all disabled anyway
                                                         //"rejected because we couldn't find " + _nextPeer + ": " +
                                                         //_state.msg.getRawUniqueId() + "/" + _req.readNextTunnelId());
                                                         "lookup fail");
        }
    }
    
    /**
     * If we are dropping lots of requests before even trying to handle them,
     * I suppose you could call us "overloaded"
     */
/**** unused, see handleReq() below
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
****/
    
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

        Hash from = state.fromHash;
        if (from == null && state.from != null)
            from = state.from.calculateHash();
        // warning, from could be null, but it should only
        // happen if we will be a IBGW and it came from us as a OBEP

        if (isInGW && isOutEnd) {
            _context.statManager().addRateData("tunnel.rejectHostile", 1);
            _log.error("Dropping build request, IBGW+OBEP: " + req);
            if (from != null)
                _context.commSystem().mayDisconnect(from);
            return;
        }

        if (ourId <= 0 || ourId > TunnelId.MAX_ID_VALUE ||
            nextId <= 0 || nextId > TunnelId.MAX_ID_VALUE) {
            _context.statManager().addRateData("tunnel.rejectHostile", 1);
            if (_log.shouldWarn())
                _log.warn("Dropping build request, bad tunnel ID: " + req);
            if (from != null)
                _context.commSystem().mayDisconnect(from);
            return;
        }

        // Loop checks
        if ((!isOutEnd) && _context.routerHash().equals(nextPeer)) {
            _context.statManager().addRateData("tunnel.rejectHostile", 1);
            // We are 2 hops in a row? Drop it without a reply.
            // No way to recognize if we are every other hop, but see below
            // old i2pd
            if (_log.shouldWarn())
                _log.warn("Dropping build request, we are the next hop: " + req);
            if (from != null)
                _context.commSystem().mayDisconnect(from);
            return;
        }
        if (!isInGW) {
            // if from is null, it came via OutboundMessageDistributor.distribute(),
            // i.e. we were the OBEP, which is fine if we're going to be an IBGW
            // but if not, something is seriously wrong here.
            if (from == null || _context.routerHash().equals(from)) {
                _context.statManager().addRateData("tunnel.rejectHostile", 1);
                if (_log.shouldWarn())
                    _log.warn("Dropping build request, we are the previous hop: " + req);
                return;
            }
        }
        if ((!isOutEnd) && (!isInGW)) {
            // Previous and next hop the same? Don't help somebody be evil. Drop it without a reply.
            // A-B-C-A is not preventable
            if (nextPeer.equals(from)) {
                // i2pd does this
                _context.statManager().addRateData("tunnel.rejectHostile", 1);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping build request with the same previous and next hop: " + req);
                _context.commSystem().mayDisconnect(from);
                return;
            }
        }

        long time = req.readRequestTime();
        long now = _context.clock().now();
        boolean isEC = _context.keyManager().getPrivateKey().getType() == EncType.ECIES_X25519;
        long timeDiff;
        long maxAge;
        if (isEC) {
            // time is in minutes, rounded down.
            long roundedNow = (now / (60*1000L)) * (60*1000);
            timeDiff = roundedNow - time;
            maxAge = MAX_REQUEST_AGE_ECIES;
        } else {
            // time is in hours, rounded down.
            // tunnel-alt-creation.html specifies that this is enforced +/- 1 hour but it was not.
            // As of 0.9.16, allow + 5 minutes to - 65 minutes.
            long roundedNow = (now / (60*60*1000L)) * (60*60*1000);
            timeDiff = roundedNow - time;
            maxAge = MAX_REQUEST_AGE;
        }
        if (timeDiff > maxAge) {
            _context.statManager().addRateData("tunnel.rejectTooOld", 1);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping build request too old... replay attack? " + DataHelper.formatDuration(timeDiff) + ": " + req);
            if (from != null)
                _context.commSystem().mayDisconnect(from);
            return;
        }
        if (timeDiff < 0 - MAX_REQUEST_FUTURE) {
            _context.statManager().addRateData("tunnel.rejectFuture", 1);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping build request too far in future " + DataHelper.formatDuration(0 - timeDiff) + ": " + req);
            if (from != null)
                _context.commSystem().mayDisconnect(from);
            return;
        }

        int response;
        if (_context.router().isHidden()) {
            _context.throttle().setTunnelStatus(_x("Rejecting tunnels: Hidden mode"));
            response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        } else {
            response = _context.throttle().acceptTunnelRequest();
        }

        // This only checked OUR tunnels, so the log message was wrong.
        // Now checked by TunnelDispatcher.joinXXX()
        // and returned as success value, checked below.
        //if (_context.tunnelManager().getTunnelInfo(new TunnelId(ourId)) != null) {
        //    if (_log.shouldLog(Log.ERROR))
        //        _log.error("Already participating in a tunnel with the given Id (" + ourId + "), so gotta reject");
        //    if (response == 0)
        //        response = TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
        //}
        
        //if ( (response == 0) && (_context.random().nextInt(50) <= 1) )
        //    response = TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
        
        if (response == 0) {
            // only in short build request, otherwise 0
            int type = req.readLayerEncryptionType();
            if (type != 0) {
                if (_log.shouldWarn())
                    _log.warn("Unsupported layer enc. type: " + type);
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
            }
        }

        long recvDelay = now - state.recvTime;

        if (response == 0) {
            // unused
            //int proactiveDrops = countProactiveDrops();
            float pDrop = ((float) recvDelay) / (float) (BuildRequestor.REQUEST_TIMEOUT*3);
            pDrop = (float)Math.pow(pDrop, 16);
            if (_context.random().nextFloat() < pDrop) { // || (proactiveDrops > MAX_PROACTIVE_DROPS) ) ) {
                _context.statManager().addRateData("tunnel.rejectOverloaded", recvDelay);
                _context.throttle().setTunnelStatus(_x("Rejecting tunnels: Request overload"));
                //if (true || (proactiveDrops < MAX_PROACTIVE_DROPS*2))
                    response = TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
                //else
                //    response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
            } else {
                _context.statManager().addRateData("tunnel.acceptLoad", recvDelay);
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
                if (bw != 'O' && bw != 'N' && bw != 'P' && bw != 'X' &&
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
        if (response == 0 && !isInGW && _throttler != null && from != null) {
            ParticipatingThrottler.Result result = _throttler.shouldThrottle(from);
            if (result == ParticipatingThrottler.Result.DROP) {
                if (_log.shouldWarn())
                    _log.warn("Dropping request (hop throttle), previous hop: " + from + ": " + req);
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                _context.commSystem().mayDisconnect(from);
                // fake failed so we won't use him for our tunnels
                _context.profileManager().tunnelFailed(from, 400);
                 return;
            }
            if (result == ParticipatingThrottler.Result.REJECT) {
                if (_log.shouldWarn())
                    _log.warn("Rejecting tunnel (hop throttle), previous hop: " + from + ": " + req);
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                // fake failed so we won't use him for our tunnels
                _context.profileManager().tunnelFailed(from, 200);
            }
        }
        if (response == 0 && (!isOutEnd) && _throttler != null) {
            ParticipatingThrottler.Result result = _throttler.shouldThrottle(nextPeer);
            if (result == ParticipatingThrottler.Result.DROP) {
                if (_log.shouldWarn())
                    _log.warn("Dropping request (hop throttle), next hop: " + nextPeer + ": " + req);
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                if (from != null)
                    _context.commSystem().mayDisconnect(from);
                // fake failed so we won't use him for our tunnels
                _context.profileManager().tunnelFailed(nextPeer, 400);
                 return;
            }
            if (result == ParticipatingThrottler.Result.REJECT) {
                if (_log.shouldWarn())
                    _log.warn("Rejecting tunnel (hop throttle), next hop: " + nextPeer + ": " + req);
                _context.statManager().addRateData("tunnel.rejectHopThrottle", 1);
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                // fake failed so we won't use him for our tunnels
                _context.profileManager().tunnelFailed(nextPeer, 200);
            }
        }

        // BW params
        int avail = 0;
        if (response == 0) {
            Properties props = req.readOptions();
            if (props != null && !props.isEmpty()) {
                int min = 0;
                int rqu = 0;
                int ibgwmax = 0;
                String smin = props.getProperty(BuildRequestor.PROP_MIN_BW);
                if (smin != null) {
                    try {
                        min = 1000 * Integer.parseInt(smin);
                    } catch (NumberFormatException nfe) {
                        response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                    }
                }
                String sreq = props.getProperty(BuildRequestor.PROP_REQ_BW);
                if (sreq != null) {
                    try {
                        rqu = 1000 * Integer.parseInt(sreq);
                    } catch (NumberFormatException nfe) {
                        response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                    }
                }
                if (isInGW) {
                    String smax = props.getProperty(BuildRequestor.PROP_MAX_BW);
                    if (smax != null) {
                        try {
                            ibgwmax = 1000 * Integer.parseInt(smax);
                        } catch (NumberFormatException nfe) {
                            response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                        }
                    }
                }
                if ((min > 0 || rqu > 0 || ibgwmax > 0) && response == 0) {
                    int share = 1000 * TunnelDispatcher.getShareBandwidth(_context);
                    int max = share / 20;
                    if (min > max) {
                        response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                    } else {
                        RateStat stat = _context.statManager().getRate("tunnel.participatingBandwidth");
                        if (stat != null) {
                            Rate rate = stat.getRate(10*60*1000);
                            if (rate != null) {
                                int used = (int) rate.getAvgOrLifetimeAvg();
                                avail = Math.min(max, (share - used) / 4);
                                if (min > avail) {
                                    if (_log.shouldWarn())
                                        _log.warn("REJECT Part tunnel: min: " + min + " req: " + rqu + " avail: " + avail);
                                    response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                                } else {
                                    if (min > 0 && rqu > 4 * min)
                                        rqu = 4 * min;
                                    if (rqu > 0 && rqu < avail)
                                        avail = rqu;
                                    if (ibgwmax > 0 && ibgwmax < avail)
                                        avail = ibgwmax;
                                    if (_log.shouldWarn())
                                        _log.warn("ACCEPT Part tunnel: min: " + min + " req: " + rqu + " max: " + ibgwmax + " avail: " + avail);
                                }
                            }
                        }
                    }
                }
            }
        }

        HopConfig cfg = null;
        if (response == 0) {
            cfg = new HopConfig();
            cfg.setCreation(now);
            cfg.setExpiration(now + 10*60*1000);
            cfg.setIVKey(req.readIVKey());
            cfg.setLayerKey(req.readLayerKey());
            if (isInGW) {
                // default
                //cfg.setReceiveFrom(null);
            } else {
                if (from != null) {
                    cfg.setReceiveFrom(from);
                } else {
                    // b0rk
                    return;
                }
            }
            cfg.setReceiveTunnelId(ourId);
            if (isOutEnd) {
                // default
                //cfg.setSendTo(null);
                //cfg.setSendTunnelId(null);
            } else {
                cfg.setSendTo(nextPeer);
                cfg.setSendTunnelId(nextId);
            }
            if (avail > 0)
                cfg.setAllocatedBW(avail);
            else
                cfg.setAllocatedBW(DEFAULT_BW_PER_TUNNEL_ESTIMATE);

            // now "actually" join
            boolean success;
            if (isOutEnd)
                success = _context.tunnelDispatcher().joinOutboundEndpoint(cfg);
            else if (isInGW)
                success = _context.tunnelDispatcher().joinInboundGateway(cfg);
            else
                success = _context.tunnelDispatcher().joinParticipant(cfg);
            if (success) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Joining: " + req);
            } else {
                // Dup Tunnel ID. This can definitely happen (birthday paradox).
                // Probability in 11 minutes (per hop type):
                // 0.1% for 2900 tunnels; 1% for 9300 tunnels
                response = TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
                _context.statManager().addRateData("tunnel.rejectDupID", 1);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("DUP ID failure: " + req);
            }
        }

        // determination of response is now complete

        if (response != 0) {
            _context.statManager().addRateData("tunnel.reject." + response, 1);
            _context.messageHistory().tunnelRejected(from, new TunnelId(ourId), nextPeer, 
                                                     // this is all disabled anyway
                                                     //"rejecting for " + response + ": " +
                                                     //state.msg.getRawUniqueId() + "/" + ourId + "/" + req.readNextTunnelId() + " delay " +
                                                     //recvDelay + " as " +
                                                     //(isOutEnd ? "outbound endpoint" : isInGW ? "inbound gw" : "participant"));
                                                     Integer.toString(response));
            if (from != null)
                _context.commSystem().mayDisconnect(from);
            // Connection congestion control:
            // If we rejected the request, are near our conn limits, and aren't connected to the next hop,
            // just drop it.
            // 81% = between 75% control measures in Transports and 87% rejection above
            if ((! _context.routerHash().equals(nextPeer)) &&
                (! _context.commSystem().haveOutboundCapacity(81)) &&
                (! _context.commSystem().isEstablished(nextPeer))) {
                _context.statManager().addRateData("tunnel.dropConnLimits", 1);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not sending rejection due to conn limits: " + req);
                return;
            }
        } else if (isInGW && from != null) {
            // we're the start of the tunnel, no use staying connected
            _context.commSystem().mayDisconnect(from);
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Responding to " + state.msg.getUniqueId()
                       + " after " + recvDelay + " with " + response 
                       + " from " + (from != null ? from : "tunnel") + ": " + req);

        int records = state.msg.getRecordCount();
        int ourSlot = -1;
        for (int j = 0; j < records; j++) {
            if (state.msg.getRecord(j) == null) {
                ourSlot = j;
                break;
            }
        }
        EncryptedBuildRecord reply;
        if (isEC) {
            Properties props;
            if (avail > 0) {
                props = new Properties();
                props.setProperty(BuildRequestor.PROP_AVAIL_BW, Integer.toString(avail / 1000));
            } else {
                props = EmptyProperties.INSTANCE;
            }
            if (state.msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE) {
                reply = BuildResponseRecord.createShort(_context, response, req.getChaChaReplyKey(), req.getChaChaReplyAD(), props, ourSlot);
            } else {
                reply = BuildResponseRecord.create(_context, response, req.getChaChaReplyKey(), req.getChaChaReplyAD(), props);
            }
        } else {
            reply = BuildResponseRecord.create(_context, response, req.readReplyKey(), req.readReplyIV(), state.msg.getUniqueId());
        }
        state.msg.setRecord(ourSlot, reply);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read slot " + ourSlot + " containing: " + req
                      + " accepted? " + response
                      + " recvDelay " + recvDelay + " replyMessage " + req.readReplyMessageId());

        // now actually send the response
        long expires = now + NEXT_HOP_SEND_TIMEOUT;
        if (!isOutEnd) {
            TunnelBuildMessage nextMessage = state.msg;
            nextMessage.setUniqueId(req.readReplyMessageId());
            nextMessage.setMessageExpiration(expires);
            OutNetMessage msg = new OutNetMessage(_context, nextMessage, expires, PRIORITY, nextPeerInfo);
            if (response == 0)
                msg.setOnFailedSendJob(new TunnelBuildNextHopFailJob(_context, cfg));
            _context.outNetMessagePool().add(msg);
        } else {
            // We are the OBEP.
            // send it to the reply tunnel on the reply peer within a new TunnelBuildReplyMessage
            // (enough layers jrandom?)
            TunnelBuildReplyMessage replyMsg;
            if (state.msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE) {
                OutboundTunnelBuildReplyMessage otbrm  = new OutboundTunnelBuildReplyMessage(_context, records);
                replyMsg = otbrm;
            } else if (records == TunnelBuildMessage.MAX_RECORD_COUNT) {
                replyMsg = new TunnelBuildReplyMessage(_context);
            } else {
                replyMsg = new VariableTunnelBuildReplyMessage(_context, records);
            }
            for (int i = 0; i < records; i++) {
                replyMsg.setRecord(i, state.msg.getRecord(i));
            }
            replyMsg.setUniqueId(req.readReplyMessageId());
            replyMsg.setMessageExpiration(expires);
            boolean replyGwIsUs = _context.routerHash().equals(nextPeer);
            I2NPMessage outMessage;
            if (!replyGwIsUs && state.msg.getType() == ShortTunnelBuildMessage.MESSAGE_TYPE) {
                // garlic encrypt
                outMessage = MessageWrapper.wrap(_context, replyMsg, req.readGarlicKeys());
                if (outMessage == null) {
                    if (_log.shouldWarn())
                        _log.warn("OTBRM encrypt fail");
                    return;
                }
            } else {
                outMessage = replyMsg;
            }
            TunnelGatewayMessage m = new TunnelGatewayMessage(_context);
            m.setMessage(outMessage);
            m.setMessageExpiration(expires);
            m.setTunnelId(new TunnelId(nextId));
            if (replyGwIsUs) {
                // ok, we are the gateway, so inject it
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("We are the reply gateway for " + nextId
                              + " when replying to replyMessage " + req);
                _context.tunnelDispatcher().dispatch(m);
            } else {
                // ok, the gateway is some other peer, shove 'er across
                OutNetMessage outMsg = new OutNetMessage(_context, m, expires, PRIORITY, nextPeerInfo);
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

        /**
         *  Either from or fromHash may be null, but both should be null only if
         *  we're to be a IBGW and it came from us as a OBEP.
         */
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            // need to figure out if this is a reply to an inbound tunnel request (where we are the
            // endpoint, receiving the request at the last hop)
            long reqId = receivedMessage.getUniqueId();
            PooledTunnelCreatorConfig cfg = _exec.removeFromBuilding(reqId);
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Receive tunnel build message " + reqId + " from " 
            //               + (from != null ? from.calculateHash() : fromHash != null ? fromHash : "tunnels") 
            //               + ", found matching tunnel? " + (cfg != null));
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
                    _context.statManager().addRateData("tunnel.buildReplyTooSlow", 1);
                } else {
                    int sz = _inboundBuildMessages.size();
                    // Can probably remove this check, since CoDel is in use
                    BuildMessageState cur = _inboundBuildMessages.peek();
                    boolean accept = true;
                    if (cur != null) {
                        long age = _context.clock().now() - cur.recvTime;
                        if (age >= BuildRequestor.REQUEST_TIMEOUT/4) {
                            _context.statManager().addRateData("tunnel.dropLoad", age, sz);
                            _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: High load"));
                            // if the queue is backlogged, stop adding new messages
                            accept = false;
                        }
                    }
                    if (accept && _requestThrottler != null) {
                        // early request throttle check, before queueing and decryption
                        Hash fh = fromHash;
                        if (fh == null && from != null)
                            fh = from.calculateHash();
                        if (fh != null && _requestThrottler.shouldThrottle(fh)) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Dropping tunnel request (from throttle) id " + reqId + ", previous hop: " + fh);
                            _context.statManager().addRateData("tunnel.dropReqThrottle", 1);
                            // fake failed so we won't use him for our tunnels
                            _context.profileManager().tunnelFailed(fh, 400);
                            accept = false;
                        }
                    }
                    if (accept) {
                        // This is expensive and rarely seen, use CoDel instead
                        //int queueTime = estimateQueueTime(sz);
                        //float pDrop = queueTime/((float)BuildRequestor.REQUEST_TIMEOUT*3);
                        //pDrop = (float)Math.pow(pDrop, 16); // steeeep
                        //float f = _context.random().nextFloat();
                        //if ( (pDrop > f) && (allowProactiveDrop()) ) {
                        //if (pDrop > f) {
                        //    _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: Queue time"));
                        //    _context.statManager().addRateData("tunnel.dropLoadProactive", queueTime, sz);
                        //} else {
                            accept = _inboundBuildMessages.offer(new BuildMessageState(_context, receivedMessage, from, fromHash));
                            if (accept) {
                                // wake up the Executor to call handleInboundRequests()
                                _exec.repoll();
                            } else {
                                _context.throttle().setTunnelStatus(_x("Dropping tunnel requests: High load"));
                                _context.statManager().addRateData("tunnel.dropLoadBacklog", sz);
                            }
                        //}
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
    
/****
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
****/
    
    /** */
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
    private static class BuildMessageState implements CDQEntry {
        private final RouterContext _ctx;
        final TunnelBuildMessage msg;
        final RouterIdentity from;
        final Hash fromHash;
        final long recvTime;

        /**
         *  Either f or h may be null, but both should be null only if
         *  we're to be a IBGW and it came from us as a OBEP.
         */
        public BuildMessageState(RouterContext ctx, I2NPMessage m, RouterIdentity f, Hash h) {
            _ctx = ctx;
            msg = (TunnelBuildMessage)m;
            from = f;
            fromHash = h;
            recvTime = ctx.clock().now();
        }

        public void setEnqueueTime(long time) {
            // set at instantiation, which is just before enqueueing
        }

        public long getEnqueueTime() {
            return recvTime;
        }

        public void drop() {
            _ctx.throttle().setTunnelStatus(_x("Dropping tunnel requests: Queue time"));
            _ctx.statManager().addRateData("tunnel.dropLoadProactive", _ctx.clock().now() - recvTime);
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
            //  TODO
            //  This doesn't seem to be a reliable indication of actual failure,
            //  as we sometimes get subsequent tunnel messages.
            //  Until this is investigated and fixed, don't remove the tunnel.
            //getContext().tunnelDispatcher().remove(_cfg);
            getContext().statManager().addRateData("tunnel.rejectTimeout2", 1);
            Log log = getContext().logManager().getLog(BuildHandler.class);
            if (log.shouldInfo())
                log.info("Timeout contacting next hop for " + _cfg);
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
