package net.i2p.router.tunnel.pool;

import java.util.*;
import net.i2p.data.*;
import net.i2p.data.i2np.*;
import net.i2p.router.*;
import net.i2p.router.tunnel.*;
import net.i2p.router.peermanager.TunnelHistory;
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
    private List _inboundBuildMessages;
    /** list of BuildReplyMessageState, oldest first */
    private List _inboundBuildReplyMessages;
    /** list of BuildEndMessageState, oldest first */
    private List _inboundBuildEndMessages;
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

        _context.statManager().createRateStat("tunnel.rejectOverloaded", "How long we had to wait before processing the request (when it was rejected)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.acceptLoad", "How long we had to wait before processing the request (when it was accepted)", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.dropLoad", "How long we had to wait before finally giving up on an inbound request (period is queue count)?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("tunnel.handleRemaining", "How many pending inbound requests were left on the queue after one pass?", "Tunnels", new long[] { 60*1000, 10*60*1000 });
        
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
    
    private static final int MAX_HANDLE_AT_ONCE = 5;
    private static final int NEXT_HOP_LOOKUP_TIMEOUT = 5*1000;
    
    /**
     * Blocking call to handle a few of the pending inbound requests, returning true if
     * there are remaining requeusts we skipped over
     */
    boolean handleInboundRequests() {
        List handled = null;
        synchronized (_inboundBuildMessages) {
            int toHandle = _inboundBuildMessages.size();
            if (toHandle > 0) {
                if (toHandle > MAX_HANDLE_AT_ONCE)
                    toHandle = MAX_HANDLE_AT_ONCE;
                handled = new ArrayList(toHandle);
                for (int i = 0; i < toHandle; i++) // LIFO for lower response time (should we RED it for DoS?)
                    handled.add(_inboundBuildMessages.remove(_inboundBuildMessages.size()-1));
            }
        }
        if (handled != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Handling " + handled.size() + " requests");
            
            for (int i = 0; i < handled.size(); i++) {
                BuildMessageState state = (BuildMessageState)handled.get(i);
                handleRequest(state);
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
        synchronized (_inboundBuildMessages) {
            int remaining = _inboundBuildMessages.size();
            if (remaining > 0)
                _context.statManager().addRateData("tunnel.handleRemaining", remaining, 0);
            return remaining > 0;
        }
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
        StringBuffer buf = null;
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
                buf = new StringBuffer(building.toString());
        }
        
        if (cfg == null) {
            // cannot handle - not pending... took too long?
            if (_log.shouldLog(Log.WARN))
                _log.warn("The reply " + replyMessageId + " did not match any pending tunnels");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Pending tunnels: " + buf.toString());
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
            for (int i = 0; i < cfg.getLength(); i++) {
                Hash peer = cfg.getPeer(i);
                int record = order.indexOf(new Integer(i));
                int howBad = statuses[record];
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(msg.getUniqueId() + ": Peer " + peer.toBase64() + " replied with status " + howBad);
                
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
                _exec.buildComplete(cfg, cfg.getTunnelPool());
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
                _exec.buildComplete(cfg, cfg.getTunnelPool());
                if (cfg.getDestination() == null)
                    _context.statManager().addRateData("tunnel.buildExploratoryReject", rtt, rtt);
                else
                    _context.statManager().addRateData("tunnel.buildClientReject", rtt, rtt);
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn(msg.getUniqueId() + ": Tunnel reply could not be decrypted for tunnel " + cfg);
        }
    }
    
    private void handleRequest(BuildMessageState state) {
        long timeSinceReceived = System.currentTimeMillis()-state.recvTime;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(state.msg.getUniqueId() + ": handling request after " + timeSinceReceived);
        
        if (timeSinceReceived > BuildRequestor.REQUEST_TIMEOUT*2) {
            // don't even bother, since we are so overloaded locally
            if (_log.shouldLog(Log.ERROR))
                _log.error("Not even trying to handle/decrypt the request " + state.msg.getUniqueId() 
                           + ", since we received it a long time ago: " + timeSinceReceived);
            return;
        }
        // ok, this is not our own tunnel, so we need to do some heavy lifting
        // this not only decrypts the current hop's record, but encrypts the other records
        // with the enclosed reply key
        long beforeDecrypt = System.currentTimeMillis();
        BuildRequestRecord req = _processor.decrypt(_context, state.msg, _context.routerHash(), _context.keyManager().getPrivateKey());
        long decryptTime = System.currentTimeMillis() - beforeDecrypt;
        _context.statManager().addRateData("tunnel.decryptRequestTime", decryptTime, decryptTime);
        if (req == null) {
            // no records matched, or the decryption failed.  bah
            if (_log.shouldLog(Log.WARN))
                _log.warn("The request " + state.msg.getUniqueId() + " could not be decrypted");
            return;
        }

        Hash nextPeer = req.readNextIdentity();
        RouterInfo nextPeerInfo = _context.netDb().lookupRouterInfoLocally(nextPeer);
        if (nextPeerInfo == null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + state.msg.getUniqueId() + "/" + req.readReceiveTunnelId() + "/" + req.readNextTunnelId() 
                           + " handled, looking for the next peer " + nextPeer.toBase64());
            _context.netDb().lookupRouterInfo(nextPeer, new HandleReq(_context, state, req, nextPeer), new TimeoutReq(_context, state, req, nextPeer), NEXT_HOP_LOOKUP_TIMEOUT);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Request " + state.msg.getUniqueId() + " handled and we know the next peer " + nextPeer.toBase64());
            handleReq(nextPeerInfo, state, req, nextPeer);
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

            handleReq(getContext().netDb().lookupRouterInfoLocally(_nextPeer), _state, _req, _nextPeer);
        }
    }

    private class TimeoutReq extends JobImpl {
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
            getContext().statManager().addRateData("tunnel.rejectTimeout", 1, 1);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Request " + _state.msg.getUniqueId() 
                          + " could no be satisfied, as the next peer could not be found: " + _nextPeer.toBase64());
            getContext().messageHistory().tunnelRejected(_state.fromHash, new TunnelId(_req.readReceiveTunnelId()), _nextPeer, 
                                                         "rejected because we couldn't find " + _nextPeer.toBase64() + ": " +
                                                         _state.msg.getUniqueId() + "/" + _req.readNextTunnelId());
        }
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
        
        long recvDelay = System.currentTimeMillis()-state.recvTime;
        if ( (response == 0) && (recvDelay > BuildRequestor.REQUEST_TIMEOUT) ) {
            _context.statManager().addRateData("tunnel.rejectOverloaded", recvDelay, recvDelay);
            response = TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
        } else if (response == 0) {
            _context.statManager().addRateData("tunnel.acceptLoad", recvDelay, recvDelay);
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Responding to " + state.msg.getUniqueId() + "/" + ourId
                       + " after " + recvDelay + " with " + response 
                       + " from " + (state.fromHash != null ? state.fromHash.toBase64() : 
                                     state.from != null ? state.from.calculateHash().toBase64() : "tunnel"));

        if (response == 0) {
            HopConfig cfg = new HopConfig();
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

        BuildResponseRecord resp = new BuildResponseRecord();
        byte reply[] = resp.create(_context, response, req.readReplyKey(), req.readReplyIV(), state.msg.getUniqueId());
        for (int j = 0; j < TunnelBuildMessage.RECORD_COUNT; j++) {
            if (state.msg.getRecord(j) == null) {
                ourSlot = j;
                state.msg.setRecord(j, new ByteArray(reply));
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Full reply record for slot " + ourSlot + "/" + ourId + "/" + nextId + "/" + req.readReplyMessageId()
                               + ": " + Base64.encode(reply));
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
            _context.outNetMessagePool().add(msg);
        } else {
            // send it to the reply tunnel on the reply peer within a new TunnelBuildReplyMessage
            // (enough layers jrandom?)
            TunnelBuildReplyMessage replyMsg = new TunnelBuildReplyMessage(_context);
            for (int i = 0; i < state.msg.RECORD_COUNT; i++)
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
                _context.outNetMessagePool().add(outMsg);
            }
        }
    }
    
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
                           new Exception("source"));
            if (cfg != null) {
                BuildEndMessageState state = new BuildEndMessageState(cfg, receivedMessage, from, fromHash);
                if (HANDLE_REPLIES_INLINE) {
                    handleRequestAsInboundEndpoint(state);
                } else {
                    synchronized (_inboundBuildEndMessages) {
                        _inboundBuildEndMessages.add(state);
                    }
                    _exec.repoll();
                }
            } else {
                if (_exec.wasRecentlyBuilding(reqId)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping the reply " + reqId + ", as we used to be building that");
                } else {
                    synchronized (_inboundBuildMessages) {
                        boolean removed = false;
                        while (_inboundBuildMessages.size() > 0) {
                            BuildMessageState cur = (BuildMessageState)_inboundBuildMessages.get(0);
                            long age = System.currentTimeMillis() - cur.recvTime;
                            if (age >= BuildRequestor.REQUEST_TIMEOUT) {
                                _inboundBuildMessages.remove(0);
                                _context.statManager().addRateData("tunnel.dropLoad", age, _inboundBuildMessages.size());
                            } else {
                                break;
                            }
                        }
                        _inboundBuildMessages.add(new BuildMessageState(receivedMessage, from, fromHash));
                    }
                    _exec.repoll();
                }
            }
            return _buildMessageHandlerJob;
        }
    }
    
    private class TunnelBuildReplyMessageHandlerJobBuilder implements HandlerJobBuilder {
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receive tunnel build reply message " + receivedMessage.getUniqueId() + " from "
                           + (fromHash != null ? fromHash.toBase64() : from != null ? from.calculateHash().toBase64() : "a tunnel"));
            if (HANDLE_REPLIES_INLINE) {
                handleReply(new BuildReplyMessageState(receivedMessage, from, fromHash));
            } else {
                synchronized (_inboundBuildReplyMessages) {
                    _inboundBuildReplyMessages.add(new BuildReplyMessageState(receivedMessage, from, fromHash));
                }
                _exec.repoll();
            }
            return _buildReplyMessageHandlerJob;
        }
    }
    
    /** normal inbound requests from other people */
    private class BuildMessageState {
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
    private class BuildReplyMessageState {
        TunnelBuildReplyMessage msg;
        RouterIdentity from;
        Hash fromHash;
        long recvTime;
        public BuildReplyMessageState(I2NPMessage m, RouterIdentity f, Hash h) {
            msg = (TunnelBuildReplyMessage)m;
            from = f;
            fromHash = h;
            recvTime = System.currentTimeMillis();
        }
    }
    /** replies for inbound tunnels we have created */
    private class BuildEndMessageState {
        TunnelBuildMessage msg;
        PooledTunnelCreatorConfig cfg;
        RouterIdentity from;
        Hash fromHash;
        long recvTime;
        public BuildEndMessageState(PooledTunnelCreatorConfig c, I2NPMessage m, RouterIdentity f, Hash h) {
            cfg = c;
            msg = (TunnelBuildMessage)m;
            from = f;
            fromHash = h;
            recvTime = System.currentTimeMillis();
        }
    }

    // noop
    private class TunnelBuildMessageHandlerJob extends JobImpl {
        private TunnelBuildMessageHandlerJob(RouterContext ctx) { super(ctx); }
        public void runJob() {}
        public String getName() { return "Receive tunnel build message"; }
    }
    // noop
    private class TunnelBuildReplyMessageHandlerJob extends JobImpl {
        private TunnelBuildReplyMessageHandlerJob(RouterContext ctx) { super(ctx); }
        public void runJob() {}
        public String getName() { return "Receive tunnel build reply message"; }
    }
}
