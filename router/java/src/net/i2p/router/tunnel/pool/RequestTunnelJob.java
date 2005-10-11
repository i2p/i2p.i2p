package net.i2p.router.tunnel.pool;

import java.util.HashSet;
import java.util.Set;

import net.i2p.data.Certificate;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelCreateMessage;
import net.i2p.data.i2np.TunnelCreateStatusMessage;

import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.router.ReplyJob;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.util.Log;

/**
 * queue up a job to request the endpoint to join the tunnel, which then
 * requeues up another job for earlier hops, etc, until it reaches the 
 * gateway.  after the gateway is confirmed, onCreated is fired.
 *
 */
public class RequestTunnelJob extends JobImpl {
    private Log _log;
    private Job _onCreated;
    private Job _onFailed;
    private int _currentHop;
    private RouterInfo _currentPeer;
    private HopConfig _currentConfig;
    private int _lookups;
    private TunnelCreatorConfig _config;
    private long _lastSendTime;
    private boolean _isFake;
    private boolean _isExploratory;
    
    static final int HOP_REQUEST_TIMEOUT = 20*1000;
    private static final int LOOKUP_TIMEOUT = 10*1000;
    
    public RequestTunnelJob(RouterContext ctx, TunnelCreatorConfig cfg, Job onCreated, Job onFailed, int hop, boolean isFake, boolean isExploratory) {
        super(ctx);
        _log = ctx.logManager().getLog(RequestTunnelJob.class);
        _config = cfg;
        _onCreated = onCreated;
        _onFailed = onFailed;
        _currentHop = hop;
        _currentPeer = null;
        _lookups = 0;
        _lastSendTime = 0;
        _isFake = isFake;
        _isExploratory = isExploratory;
        
        ctx.statManager().createRateStat("tunnel.receiveRejectionProbabalistic", "How often we are rejected probabalistically?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.receiveRejectionTransient", "How often we are rejected due to transient overload?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.receiveRejectionBandwidth", "How often we are rejected due to bandwidth overload?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.receiveRejectionCritical", "How often we are rejected due to critical failure?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.buildFailure", "What hop was being requested when a nonexploratory tunnel request failed?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.buildExploratoryFailure", "What hop was beiing requested when an exploratory tunnel request failed?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.buildSuccess", "How often we succeed building a non-exploratory tunnel?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.buildExploratorySuccess", "How often we succeed building an exploratory tunnel?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.buildPartialTime", "How long a non-exploratory request took to be accepted?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.buildExploratoryPartialTime", "How long an exploratory request took to be accepted?", "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Requesting hop " + hop + " in " + cfg);
        if (hop < 0)
            throw new IllegalArgumentException("invalid endpoint hop [" + hop + "] cfg: " + cfg);
    }
    
    public String getName() { return "Request tunnel participation"; }
    public void runJob() {
        _currentConfig = _config.getConfig(_currentHop);
        Hash peer = _config.getPeer(_currentHop);
        if (getContext().routerHash().equals(peer)) {
            requestSelf();
        } else {
            if (_currentPeer == null) {
                _currentPeer = getContext().netDb().lookupRouterInfoLocally(peer);
                if (_currentPeer == null) {
                    _lookups++;
                    if (_lookups > 1) {
                        peerFail(0);
                        return;
                    }
                    getContext().netDb().lookupRouterInfo(peer, this, this, LOOKUP_TIMEOUT);
                    return;
                }
            }
            requestRemote(peer);
        }
    }
    
    private void requestSelf() {
        if (_config.isInbound()) {
            // inbound tunnel, which means we are the first person asked, and if
            // it is a zero hop tunnel, then we are also the last person asked
            
            long id = getContext().random().nextLong(TunnelId.MAX_ID_VALUE-1) + 1;
            _currentConfig.setReceiveTunnelId(DataHelper.toLong(4, id));
            if (_config.getLength() > 1) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Requesting ourselves to join an inbound tunnel, receiving on " 
                               + _currentConfig.getReceiveTunnel() + ": " + _config);
                // inbound tunnel with more than just ourselves
                RequestTunnelJob req = new RequestTunnelJob(getContext(), _config, _onCreated, 
                                                            _onFailed, _currentHop - 1, _isFake, _isExploratory);
                if (_isFake)
                    req.runJob();
                else
                    getContext().jobQueue().addJob(req);
            } else if (_onCreated != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Requesting ourselves to join a 0 hop inbound tunnel, receiving on " 
                               + _currentConfig.getReceiveTunnel() + ": " + _config);
                // 0 hop inbound tunnel
                if (_onCreated != null) {
                    if (_isFake)
                        _onCreated.runJob();
                    else
                        getContext().jobQueue().addJob(_onCreated);
                }
                getContext().statManager().addRateData("tunnel.buildSuccess", 1, 0);
            }
        } else {
            // outbound tunnel, we're the gateway and hence the last person asked
            
            if (_config.getLength() <= 1) {
                // pick a random tunnelId which we "send" on
                byte id[] = new byte[4];
                getContext().random().nextBytes(id);
                _config.getConfig(0).setSendTunnelId(id);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Requesting ourselves to join an outbound tunnel, sending on " 
                               + _config.getConfig(0).getSendTunnel() + ": " + _config);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Requesting ourselves to join an outbound tunnel, sending on " 
                               + _config.getConfig(1).getReceiveTunnel() + ": " + _config);
                // send to whatever the first remote hop receives on
                _config.getConfig(0).setSendTunnelId(_config.getConfig(1).getReceiveTunnelId());
                if (_config.getConfig(0).getSendTunnelId() == null) {
                    _log.error("wtf, next hop: " + _config.getConfig(1) 
                               + " didn't give us a tunnel to send to, but they passed on to us?");
                    if (_onFailed != null) {
                        if (_isFake)
                            _onFailed.runJob();
                        else
                            getContext().jobQueue().addJob(_onFailed);
                    }
                    return;
                }
                    
            }
            // we are the outbound gateway, which is the last hop which is 
            // asked to participate in the tunnel.  as such, fire off the
            // onCreated immediately
            if (_onCreated != null) {
                if (_isFake)
                    _onCreated.runJob();
                else
                    getContext().jobQueue().addJob(_onCreated);
                getContext().statManager().addRateData("tunnel.buildSuccess", 1, 0);
            }
        }
    }

    private void requestRemote(Hash peer) {
        HopConfig nextHop = (_config.getLength() > _currentHop + 1 ? _config.getConfig(_currentHop+1) : null);
        Hash nextRouter = (nextHop != null ? _config.getPeer(_currentHop+1) : null);
        TunnelId nextTunnel = (nextHop != null ? nextHop.getReceiveTunnel() : null);
        
        TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundTunnel();
        if (replyTunnel == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No inbound tunnels to build tunnels with!");
            tunnelFail();
        }
        Hash replyGateway = replyTunnel.getPeer(0);
        
        SessionKey replyKey = getContext().keyGenerator().generateSessionKey();
        SessionTag replyTag = new SessionTag(true);
        
        TunnelCreateMessage msg = new TunnelCreateMessage(getContext());
        msg.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        msg.setIVKey(_currentConfig.getIVKey());
        msg.setLayerKey(_currentConfig.getLayerKey());
        msg.setNonce(getContext().random().nextLong(TunnelCreateMessage.MAX_NONCE_VALUE));
        msg.setNextRouter(nextRouter);
        msg.setNextTunnelId(nextTunnel);
        msg.setReplyGateway(replyGateway);
        msg.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
        msg.setReplyKey(replyKey);
        msg.setReplyTag(replyTag);
        int duration = 10*60; // (int)((_config.getExpiration() - getContext().clock().now())/1000);
        msg.setDurationSeconds(duration);
        if (_currentHop == 0)
            msg.setIsGateway(true);
        else
            msg.setIsGateway(false);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("** Send remote request to " + peer.toBase64().substring(0,4) + " using nonce " 
                       + msg.getNonce() + " with replies on " + replyTunnel);
        
        // now make sure we will decrypt the reply properly
        HashSet sessionTags = new HashSet(1);
        sessionTags.add(replyTag);
        getContext().sessionKeyManager().tagsReceived(replyKey, sessionTags);
        
        HashSet sentTags = new HashSet();
        SessionKey sentKey = new SessionKey();
        ReplySelector selector = new ReplySelector(msg.getNonce());
        ReplyJob onReply = new RequestReplyJob(getContext(), sentKey, sentTags);
        Job onTimeout = new RequestTimeoutJob(getContext(), msg.getNonce());
        Job j = new SendGarlicMessageJob(getContext(), msg, _currentPeer, selector, onReply, onTimeout, sentKey, sentTags);
        getContext().jobQueue().addJob(j);
        _lastSendTime = getContext().clock().now();
    }
    
    private void peerFail(int howBad) {
        if (howBad > 0) {
            switch (howBad) {
                case TunnelHistory.TUNNEL_REJECT_CRIT:
                    getContext().statManager().addRateData("tunnel.receiveRejectionCritical", 1, 0);
                    break;
                case TunnelHistory.TUNNEL_REJECT_BANDWIDTH:
                    getContext().statManager().addRateData("tunnel.receiveRejectionBandwidth", 1, 0);
                    break;
                case TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD:
                    getContext().statManager().addRateData("tunnel.receiveRejectionTransient", 1, 0);
                    break;
                case TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT:
                    getContext().statManager().addRateData("tunnel.receiveRejectionProbabalistic", 1, 0);
                    break;
                default:
                    // ignore
            }
            
            // penalize peer based on their bitchiness level
            getContext().profileManager().tunnelRejected(_currentPeer.getIdentity().calculateHash(), 
                                                         getContext().clock().now() - _lastSendTime,
                                                         howBad);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Tunnel request failed w/ cause=" + howBad + " for peer " 
                      + _currentPeer.getIdentity().calculateHash().toBase64().substring(0,4));
        tunnelFail();
    }
    
    private void tunnelFail() {
        if (_log.shouldLog(Log.INFO))
            _log.info("tunnel building failed: " + _config + " at hop " + _currentHop);
        if (_onFailed != null)
            getContext().jobQueue().addJob(_onFailed);
        if (_isExploratory)
            getContext().statManager().addRateData("tunnel.buildExploratoryFailure", _currentHop, _config.getLength());
        else
            getContext().statManager().addRateData("tunnel.buildFailure", _currentHop, _config.getLength());
    }
    
    private void peerSuccess() {
        long now = getContext().clock().now();
        getContext().profileManager().tunnelJoined(_currentPeer.getIdentity().calculateHash(), 
                                                   now - _lastSendTime);
        if (_isExploratory)
            getContext().statManager().addRateData("tunnel.buildExploratoryPartialTime", now - _lastSendTime, 0);
        else
            getContext().statManager().addRateData("tunnel.buildPartialTime", now - _lastSendTime, 0);

        if (_currentHop > 0) {
            RequestTunnelJob j = new RequestTunnelJob(getContext(), _config, _onCreated, _onFailed, _currentHop - 1, _isFake, _isExploratory);
            getContext().jobQueue().addJob(j);
        } else {
            if (_onCreated != null)
                getContext().jobQueue().addJob(_onCreated);
            if (_isExploratory)
                getContext().statManager().addRateData("tunnel.buildExploratorySuccess", 1, 0);
            else
                getContext().statManager().addRateData("tunnel.buildSuccess", 1, 0);
        }
    }
    
    private class RequestReplyJob extends JobImpl implements ReplyJob {
        private SessionKey _sentKey;
        private Set _sentTags;
        private TunnelCreateStatusMessage _reply;
        
        public RequestReplyJob(RouterContext ctx, SessionKey sentKey, Set sentTags) {
            super(ctx);
            _sentKey = sentKey;
            _sentTags = sentTags;
        }
        public String getName() { return "handle tunnel request reply"; }
        public void runJob() {          
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("reply received: " + _config + " at hop " + _currentHop + ": " + _reply.getStatus());

            if (_sentTags.size() > 0) {
                PublicKey target = _currentPeer.getIdentity().getPublicKey();
                getContext().sessionKeyManager().tagsDelivered(target, _sentKey, _sentTags);
            }
            
            if (_reply.getStatus() == TunnelCreateStatusMessage.STATUS_SUCCESS) {
                _currentConfig.setReceiveTunnelId(_reply.getReceiveTunnelId());
                if (_currentHop >= 1)
                    _config.getConfig(_currentHop-1).setSendTunnelId(_currentConfig.getReceiveTunnelId());
                peerSuccess();
            } else {
                peerFail(_reply.getStatus());
            }
        }
        
        public void setMessage(I2NPMessage message) { _reply = (TunnelCreateStatusMessage)message; }
    }
    
    private class RequestTimeoutJob extends JobImpl {
        private long _nonce;
        public RequestTimeoutJob(RouterContext ctx, long nonce) {
            super(ctx);
            _nonce = nonce;
        }
        public String getName() { return "tunnel request timeout"; }
        public void runJob() {
            if (_log.shouldLog(Log.WARN))
                _log.warn("request timeout: " + _config + " at hop " + _currentHop 
                          + " with nonce " + _nonce);
            peerFail(0);
        }
    }
    
    private class ReplySelector implements MessageSelector {
        private long _nonce;
        private boolean _nonceFound;
        private long _expiration;
        
        public ReplySelector(long nonce) {
            _nonce = nonce;
            _nonceFound = false;
            _expiration = getContext().clock().now() + HOP_REQUEST_TIMEOUT;
        }
        public boolean continueMatching() { 
            return (!_nonceFound) && (getContext().clock().now() < _expiration);
        }
        
        public long getExpiration() { return _expiration; }
        public boolean isMatch(I2NPMessage message) {
            if (message instanceof TunnelCreateStatusMessage) {
                if (_nonce == ((TunnelCreateStatusMessage)message).getNonce()) {
                    _nonceFound = true;
                    return true;
                }
            }
            return false;
        }
        
        public String toString() { 
            StringBuffer buf = new StringBuffer(64);
            buf.append("request ");
            buf.append(_currentPeer.getIdentity().calculateHash().toBase64().substring(0,4));
            buf.append(" to join ").append(_config);
            buf.append(" (request expired ");
            buf.append(DataHelper.formatDuration(_expiration-getContext().clock().now()));
            buf.append(" ago)");
            return buf.toString();
        }
    }
}
