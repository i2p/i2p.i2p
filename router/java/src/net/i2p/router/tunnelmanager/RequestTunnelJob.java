package net.i2p.router.tunnelmanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelCreateMessage;
import net.i2p.data.i2np.TunnelCreateStatusMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageHistory;
import net.i2p.router.MessageSelector;
import net.i2p.router.PeerSelectionCriteria;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.message.GarlicConfig;
import net.i2p.router.message.GarlicMessageBuilder;
import net.i2p.router.message.PayloadGarlicConfig;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Log;

/**
 * Request the creation of a new tunnel
 *
 */
public class RequestTunnelJob extends JobImpl {
    private Log _log;
    private TunnelPool _pool;
    private boolean _complete;
    private long _timeoutMs;
    private long _expiration;
    private TunnelInfo _tunnelGateway;
    private List _toBeRequested;            // list of participants, from endpoint to gateway
    private Set _failedTunnelParticipants;  // set of Hash of the RouterIdentity of participants who timed out or rejected
    private boolean _isInbound;
    
    private final static int PRIORITY = 300; // high since we are creating tunnels for a client
    
    RequestTunnelJob(RouterContext context, TunnelPool pool, TunnelInfo tunnelGateway, boolean isInbound, long timeoutMs) {
        super(context);
        _log = context.logManager().getLog(RequestTunnelJob.class);
        context.statManager().createFrequencyStat("tunnel.buildFrequency", "How often does the router build a tunnel?", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        context.statManager().createFrequencyStat("tunnel.buildFailFrequency", "How often does a peer in the tunnel fail to join??", "Tunnels", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });

        _pool = pool;
        _tunnelGateway = tunnelGateway;
        _timeoutMs = timeoutMs;
        _expiration = -1;
        _isInbound = isInbound;
        _failedTunnelParticipants = new HashSet();
        _complete = false;
            
        List participants = new ArrayList();
        TunnelInfo cur = _tunnelGateway;
        while (cur != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Tunnel " + cur.getTunnelId() + " includes " + cur.getThisHop().toBase64());
            participants.add(cur);
            cur = cur.getNextHopInfo();
        }
        if (isInbound) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Requesting inbound tunnel " + _tunnelGateway.getTunnelId() + " with " 
                          + participants.size() + " participants in it");
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Requesting outbound tunnel " + _tunnelGateway.getTunnelId() + " with " + participants.size() + " participants in it");
        }

        // work backwards (end point, then the router pointing at the endpoint, then the router pointing at that, etc, until the gateway
        _toBeRequested = new ArrayList(participants.size());
        for (int i = participants.size()-1; i >= 0; i--) {
            TunnelInfo peer = (TunnelInfo)participants.get(i);
            if (null != getContext().netDb().lookupRouterInfoLocally(peer.getThisHop())) {
                _toBeRequested.add(participants.get(i));
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("ok who the fuck requested someone we don't know about? (dont answer that");
            }
        }
        
        // since we request serially, we need to up the timeout serially
        // change this once we go parallel
        //_timeoutMs *= participants.size()+1;
        _expiration = (_timeoutMs * _toBeRequested.size()) + getContext().clock().now();
    }
    
    public String getName() { return "Request Tunnel"; }
    public void runJob() {
        if (getContext().clock().now() > _expiration) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Timeout reached building tunnel (timeout = " + _timeoutMs + " expiration = " + new Date(_expiration) + ")");
            fail();
            return;
        }

        TunnelInfo peer = null;
        synchronized (_toBeRequested) {
            if (_toBeRequested.size() > 0) {
                _pool.addPendingTunnel(_tunnelGateway);

                peer = (TunnelInfo)_toBeRequested.remove(0);
                if ( (peer == null) || (peer.getThisHop() == null) ) {
                    return;
                } else {
                    // jump out of the synchronized block to request
                }
            }
        }
        if (peer != null)
            requestParticipation(peer);
    }
    
    private void requestParticipation(TunnelInfo participant) {
        // find the info about who we're looking for
        RouterInfo target = getContext().netDb().lookupRouterInfoLocally(participant.getThisHop());
        if (target == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error - no db info known for participant " + participant.getThisHop());
            fail();
            return;
        }

        if (target.getIdentity().getHash().equals(getContext().routerHash())) {
            // short circuit the ok
            okLocalParticipation(participant);
            return;
        }

        // select send method [outbound tunnel or garlic through peers]
        TunnelId outboundTunnel = selectOutboundTunnel();
        if (outboundTunnel == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No outbound tunnels!  unable to request a new tunnel!");
            fail();
            return;
        }
            
        // select inbound tunnel gateway
        TunnelGateway inboundGateway = selectInboundGateway(participant);
        if (inboundGateway == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to find an inbound gateway");
            fail();
            return;
        }
        
        SessionKey wrappedKey = new SessionKey();
        Set wrappedTags = new HashSet(64);
        PublicKey wrappedTo = new PublicKey();
            
        RequestState state = new RequestState(wrappedKey, wrappedTags, wrappedTo, 
                                              participant, inboundGateway, 
                                              outboundTunnel, target);
        Request r = new Request(state);
        getContext().jobQueue().addJob(r);	
    }
    
    /**
     * The request job steps through the RequestState, pushing it along one pass 
     * at a time, all with each pass occurring as a seperate sequential job.  This
     * is useful since the RequestTunnelJob can otherwise take upwards of 3+ seconds,
     * since the various steps may involve full ElGamal encryption (for source route
     * blocks, the garlic, etc).
     */
    public class Request extends JobImpl {
        private RequestState _state;
        Request(RequestState state) {
            super(RequestTunnelJob.this.getContext());
            _state = state;
        }
        
        public void runJob() {
            boolean needsMore = _state.doNext();
            if (needsMore) {
                requeue(0);
            } else {
                MessageHistory hist = Request.this.getContext().messageHistory();
                hist.requestTunnelCreate(_tunnelGateway.getTunnelId(), 
                                         _state.getOutboundTunnel(), 
                                         _state.getParticipant().getThisHop(), 
                                         _state.getParticipant().getNextHop(), 
                                         _state.getInboundGateway().getTunnelId(), 
                                         _state.getInboundGateway().getGateway());
            }
        }

        public String getName() { return "Request Tunnel (partial)"; }
    }
    
    /**
     * Contain the partial state for preparing the request - doNext starts by 
     * building a TunnelCreateMessage, and on the next pass it builds a 
     * DeliveryStatusMessage, and on the pass after that, it builds a GarlicMessage
     * containing those two, and on its final pass, it sends everything out through
     * a tunnel with appropriate handling jobs
     *
     */
    private class RequestState {
        private SessionKey _wrappedKey;
        private Set _wrappedTags;
        private PublicKey _wrappedTo;
        private TunnelCreateMessage _createMsg;
        private GarlicMessage _garlicMessage;
        private TunnelInfo _participant;
        private TunnelGateway _inboundGateway;
        private TunnelId _outboundTunnel;
        private RouterInfo _target;
            
        public RequestState(SessionKey wrappedKey, Set wrappedTags, PublicKey wrappedTo, 
                            TunnelInfo participant, TunnelGateway inboundGateway, 
                            TunnelId outboundTunnel, RouterInfo target) {
            _wrappedKey = wrappedKey;
            _wrappedTags = wrappedTags;
            _wrappedTo = wrappedTo;
            _participant = participant;
            _inboundGateway = inboundGateway;
            _outboundTunnel = outboundTunnel;
            _target = target;
        }
        
        public TunnelId getOutboundTunnel() { return _outboundTunnel; }
        public TunnelInfo getParticipant() { return _participant; }
        public TunnelGateway getInboundGateway() { return _inboundGateway; }
        
        public boolean doNext() {
            if (_createMsg == null) {
                _createMsg = buildTunnelCreate(_participant, _inboundGateway);
                return true;
            } else if (_garlicMessage == null) {
                _garlicMessage = buildGarlicMessage(_createMsg, _inboundGateway, _target,
                                                    _wrappedKey, _wrappedTags, _wrappedTo);
                return true;
            } else {
                // send the GarlicMessage
                if (_log.shouldLog(Log.INFO))
                    _log.info("Sending tunnel create to " + _target.getIdentity().getHash().toBase64() +
                    " to inbound gateway " + _inboundGateway.getGateway().toBase64() +
                    " : " + _inboundGateway.getTunnelId().getTunnelId());
                ReplyJob onReply = new Success(_participant, _wrappedKey, _wrappedTags, _wrappedTo);
                Job onFail = new Failure(_participant);
                MessageSelector selector = new Selector(_participant);
                SendTunnelMessageJob j = new SendTunnelMessageJob(getContext(), _garlicMessage, 
                                                                  _outboundTunnel, _target.getIdentity().getHash(), 
                                                                  null, null, onReply, onFail, 
                                                                  selector, _timeoutMs, PRIORITY);
                getContext().jobQueue().addJob(j);
                return false;
            }
        }
    }
    
    /**
     * Handle the "will you participate" request that we would send to ourselves in a special case (aka fast) manner,
     * as, chances are, we'll always agree ;)
     *
     */
    private void okLocalParticipation(TunnelInfo info) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Short circuiting the local join to tunnel " + info.getTunnelId());
        peerSuccess(info);
    }
    
    /**
     * Select an outbound tunnel for sending the tunnel create status message
     *
     */
    private TunnelId selectOutboundTunnel() {
        TunnelSelectionCriteria crit = new TunnelSelectionCriteria();
        crit.setMaximumTunnelsRequired(1);
        crit.setMinimumTunnelsRequired(1);
        crit.setAnonymityPriority(50);   // arbitrary
        crit.setLatencyPriority(50);     // arbitrary
        crit.setReliabilityPriority(50); // arbitrary
        
        List tunnelIds = getContext().tunnelManager().selectOutboundTunnelIds(crit);
        TunnelId id = null;
        if (tunnelIds.size() > 0)
            id = (TunnelId)tunnelIds.get(0);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Outbound tunnel selected: " + id);
        return id;
    }
    
    /**
     * Select an inbound tunnel to receive replies and acks from the participant 
     *
     */
    private TunnelGateway selectInboundGateway(TunnelInfo participant) {
        TunnelSelectionCriteria criteria = new TunnelSelectionCriteria();
        criteria.setAnonymityPriority(66);
        criteria.setReliabilityPriority(66);
        criteria.setLatencyPriority(33);
        criteria.setMaximumTunnelsRequired(1);
        criteria.setMinimumTunnelsRequired(1);
        List ids = getContext().tunnelManager().selectInboundTunnelIds(criteria);
        if (ids.size() <= 0) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No inbound tunnels to receive the tunnel create messages.  Argh", 
                           new Exception("Tunnels suck.  whats up?"));
            return null;
        } else {
            TunnelInfo gateway = null;
            TunnelId id = null;
            for (int i = 0; i < ids.size(); i++) {
                id = (TunnelId)ids.get(i);
                gateway = getContext().tunnelManager().getTunnelInfo(id);
                if (gateway != null)
                    break;
            }
            if (gateway != null) {
                TunnelGateway gw = new TunnelGateway(id, gateway.getThisHop());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Inbound tunnel gateway: " + id + " on router " + gateway.getThisHop());
                return gw;
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("No gateway found?!", new Exception("No gateway"));
                return null;
            }
        }
    }
    
    /**
     * Build a TunnelCreateMessage to the participant
     */
    private TunnelCreateMessage buildTunnelCreate(TunnelInfo participant, TunnelGateway replyGateway) {
        TunnelCreateMessage msg = new TunnelCreateMessage(getContext());
        msg.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        msg.setConfigurationKey(participant.getConfigurationKey());
        msg.setIncludeDummyTraffic(participant.getSettings().getIncludeDummy());
        msg.setMaxAvgBytesPerMin(participant.getSettings().getBytesPerMinuteAverage());
        msg.setMaxAvgMessagesPerMin(participant.getSettings().getMessagesPerMinuteAverage());
        msg.setMaxPeakBytesPerMin(participant.getSettings().getBytesPerMinutePeak());
        msg.setMaxPeakMessagesPerMin(participant.getSettings().getMessagesPerMinutePeak());
        msg.setNextRouter(participant.getNextHop());
        // TODO: update the TunnelInfo structure so we can have the tunnel contain
        // different tunnelIds per hop
        msg.setNextTunnelId(participant.getTunnelId());
        if (participant.getNextHop() == null)
            msg.setParticipantType(TunnelCreateMessage.PARTICIPANT_TYPE_ENDPOINT);
        else if (participant.getSigningKey() != null)
            msg.setParticipantType(TunnelCreateMessage.PARTICIPANT_TYPE_GATEWAY);
        else
            msg.setParticipantType(TunnelCreateMessage.PARTICIPANT_TYPE_OTHER);
        msg.setReorderMessages(participant.getSettings().getReorder());
        
        
        SessionKey replySessionKey = getContext().keyGenerator().generateSessionKey();
        SessionTag tag = new SessionTag(true);
        Set tags = new HashSet();
        tags.add(tag);
        // make it so we'll read the session tag correctly and use the right session key
        getContext().sessionKeyManager().tagsReceived(replySessionKey, tags);
        
        msg.setReplyPeer(replyGateway.getGateway());
        msg.setReplyTunnel(replyGateway.getTunnelId());
        msg.setReplyKey(replySessionKey);
        msg.setReplyTag(tag);
        
        long duration = participant.getSettings().getExpiration() - getContext().clock().now();
        if (duration == 0) duration = 1;
        msg.setTunnelDurationSeconds(duration/1000);
        msg.setTunnelId(participant.getTunnelId());
        msg.setTunnelKey(participant.getEncryptionKey());
        msg.setVerificationPrivateKey(participant.getSigningKey());
        msg.setVerificationPublicKey(participant.getVerificationKey());
        
        return msg;
    }
    
    /**
     * Build a garlic message wrapping the data and status as cloves with both to be routed
     * through the target, where the data is destined.
     *
     */
    private GarlicMessage buildGarlicMessage(I2NPMessage data, 
                                             TunnelGateway replyTunnel, 
                                             RouterInfo target, SessionKey wrappedKey, 
                                             Set wrappedTags, PublicKey wrappedTo) {
        GarlicConfig config = buildGarlicConfig(data, replyTunnel, target);
        
        PublicKey rcptKey = config.getRecipientPublicKey();
        if (rcptKey == null) {
            if (config.getRecipient() == null) {
                throw new IllegalArgumentException("Null recipient specified");
            } else if (config.getRecipient().getIdentity() == null) {
                throw new IllegalArgumentException("Null recipient.identity specified");
            } else if (config.getRecipient().getIdentity().getPublicKey() == null) {
                throw new IllegalArgumentException("Null recipient.identity.publicKey specified");
            } else
                rcptKey = config.getRecipient().getIdentity().getPublicKey();
        }
        
        if (wrappedTo != null)
            wrappedTo.setData(rcptKey.getData());
        
        long start = getContext().clock().now();
        GarlicMessage message = GarlicMessageBuilder.buildMessage(getContext(), config, wrappedKey, wrappedTags);
        long end = getContext().clock().now();
        if ( (end - start) > 1000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Took more than a second (" + (end-start) + "ms) to create the garlic for the tunnel");
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Took LESS than a second (" + (end-start) + "ms) to create the garlic for the tunnel!");
        }
        return message;
    }
    
    private GarlicConfig buildGarlicConfig(I2NPMessage data, 
                                          TunnelGateway replyTunnel, RouterInfo target) {
        GarlicConfig config = new GarlicConfig();
        
        long garlicExpiration = getContext().clock().now() + _timeoutMs;
        PayloadGarlicConfig dataClove = buildDataClove(data, target, garlicExpiration);
        config.addClove(dataClove);
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_ROUTER);
        instructions.setDelayRequested(false);
        instructions.setDelaySeconds(0);
        instructions.setEncrypted(false);
        instructions.setEncryptionKey(null);
        instructions.setRouter(target.getIdentity().getHash());
        instructions.setTunnelId(null);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Setting the expiration on the garlic config to " + (new Date(garlicExpiration)));
        
        config.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        config.setDeliveryInstructions(instructions);
        config.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        config.setExpiration(garlicExpiration);
        config.setRecipientPublicKey(target.getIdentity().getPublicKey());
        
        return config;
    }
    
    /**
     * Build a clove that sends the data to the target (which is local)
     */
    private PayloadGarlicConfig buildDataClove(I2NPMessage data, RouterInfo target, long expiration) {
        PayloadGarlicConfig clove = new PayloadGarlicConfig();
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
        instructions.setRouter(target.getIdentity().getHash());
        instructions.setTunnelId(null);
        instructions.setDelayRequested(false);
        instructions.setDelaySeconds(0);
        instructions.setEncrypted(false);
        
        clove.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        clove.setDeliveryInstructions(instructions);
        clove.setExpiration(expiration);
        clove.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        clove.setPayload(data);
        clove.setRecipientPublicKey(null);
        clove.setRequestAck(false);
        
        return clove;
    }
    
    private void fail() {
        if (_complete) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Build tunnel failed via " + _tunnelGateway.getThisHop().toBase64() 
                          + ", but we've already completed, so fuck off: " + _tunnelGateway, 
                          new Exception("Fail aborted"));
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Build tunnel " + _tunnelGateway.getTunnelId().getTunnelId() 
                          + " with gateway " + _tunnelGateway.getThisHop().toBase64() 
                          + " FAILED: " + _failedTunnelParticipants + " - " + _tunnelGateway, 
                          new Exception("Why did we fail building?"));
            synchronized (_toBeRequested) {
                _toBeRequested.clear();
            }
            synchronized (_failedTunnelParticipants) {
                _failedTunnelParticipants.clear();
            }
            _complete = true;
        }
    }
    private void peerSuccess(TunnelInfo peer) {
        int numLeft = 0;
        synchronized (_toBeRequested) {
            numLeft = _toBeRequested.size();
        }
        if (numLeft <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Peer (" + peer.getThisHop().toBase64() 
                          + ") successful: mark the tunnel as completely ready [inbound? " 
                          + _isInbound + "]");
            _complete = true;
            if (_isInbound)
                _pool.addFreeTunnel(_tunnelGateway);
            else
                _pool.addOutboundTunnel(_tunnelGateway);
            _tunnelGateway.setIsReady(true);
            getContext().statManager().updateFrequency("tunnel.buildFrequency");
        } else {
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuffer buf = new StringBuffer(128);
                buf.append("Hop to ").append(peer.getThisHop().toBase64());
                buf.append(" successful for tunnel ").append(peer.getTunnelId().getTunnelId());
                buf.append(", but ").append(numLeft).append(" are pending");
                _log.debug(buf.toString());
            }
            getContext().jobQueue().addJob(this);
        }
    }
    
    
    public void dropped() {
        _pool.buildFakeTunnels();
        if (_log.shouldLog(Log.WARN))
            _log.warn("Dropping request to create a new tunnel, so we may have manually created a new fake inbound and a new fake outbound, just in case we needed that...");
    }
    
    
    private class Success extends JobImpl implements ReplyJob {
        private TunnelInfo _tunnel;
        private List _messages;
        private boolean _successCompleted;
        private SessionKey _wrappedKey;
        private Set _wrappedTags;
        private PublicKey _wrappedTo;
        private long _started;
        
        public Success(TunnelInfo tunnel, SessionKey wrappedKey, Set wrappedTags, PublicKey wrappedTo) {
            super(RequestTunnelJob.this.getContext());
            _tunnel = tunnel;
            _messages = new LinkedList();
            _successCompleted = false;
            _wrappedKey = wrappedKey;
            _wrappedTags = wrappedTags;
            _wrappedTo = wrappedTo;
            _started = getContext().clock().now();
        }
        
        public String getName() { return "Create Tunnel Status Received"; }
        public void runJob() {
            List toProc = null;
            synchronized (_messages) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("# messages received for successs: " + _messages.size());
                toProc = new ArrayList(_messages);
                _messages.clear();
            }
            
            long responseTime = getContext().clock().now() - _started;
            for (Iterator iter = toProc.iterator(); iter.hasNext(); ) {
                I2NPMessage msg = (I2NPMessage)iter.next();
                process(msg, responseTime);
            }
        }
        
        private void process(I2NPMessage message, long responseTime) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Running success status job (tunnel = " + _tunnel + " msg = " + message + ")");
            if (message.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Tunnel creation message acknowledged for tunnel " + _tunnel.getTunnelId() 
                               + " at router " + _tunnel.getThisHop().toBase64());
            } else {
                TunnelCreateStatusMessage msg = (TunnelCreateStatusMessage)message;
                if (_successCompleted) {
                    _log.info("Already completed in the Success task [skipping " + msg.getStatus() + "]");
                    return;
                }
                switch (msg.getStatus()) {
                    case TunnelCreateStatusMessage.STATUS_FAILED_CERTIFICATE:
                    case TunnelCreateStatusMessage.STATUS_FAILED_DELETED:
                    case TunnelCreateStatusMessage.STATUS_FAILED_DUPLICATE_ID:
                    case TunnelCreateStatusMessage.STATUS_FAILED_OVERLOADED:
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Tunnel creation failed for tunnel " + _tunnel.getTunnelId() 
                                      + " at router " + _tunnel.getThisHop().toBase64() 
                                      + " with status " + msg.getStatus());
                        getContext().profileManager().tunnelRejected(_tunnel.getThisHop(), responseTime, true);
                        Success.this.getContext().messageHistory().tunnelRejected(_tunnel.getThisHop(), 
                                                                              _tunnel.getTunnelId(), 
                                                                              null, "refused");
                        fail();
                        _successCompleted = true;
                        break;
                    case TunnelCreateStatusMessage.STATUS_SUCCESS:
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Tunnel creation succeeded for tunnel " + _tunnel.getTunnelId() 
                                       + " at router " + _tunnel.getThisHop().toBase64());
                        
                        if ( (_wrappedKey != null) && (_wrappedKey.getData() != null) && 
                             (_wrappedTags != null) && (_wrappedTags.size() > 0) && 
                             (_wrappedTo != null) ) {
                            Success.this.getContext().sessionKeyManager().tagsDelivered(_wrappedTo, _wrappedKey, _wrappedTags);
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Delivered tags successfully to " + _tunnel.getThisHop().toBase64() 
                                          + "!  # tags: " + _wrappedTags.size());
                        }
                        
                        _tunnel.setIsReady(true);
                        getContext().profileManager().tunnelJoined(_tunnel.getThisHop(), responseTime);
                        peerSuccess(_tunnel);
                        _successCompleted = true;
                        break;
                }
            }
        }
        
        public void setMessage(I2NPMessage message) {
            synchronized (_messages) {
                _messages.add(message);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Reply message " + _messages.size() + " received " 
                               + message.getClass().getName(), new Exception("Received from"));
            }
        }
    }
    
    private class Failure extends JobImpl {
        private TunnelInfo _tunnel;
        private long _started;
        public Failure(TunnelInfo tunnel) {
            super(RequestTunnelJob.this.getContext());
            _tunnel = tunnel;
            _started = getContext().clock().now();
        }
        
        public String getName() { return "Create Tunnel Failed"; }
        public void runJob() {
            // update the tunnel so its known to be not working
            if (_log.shouldLog(Log.WARN)) {
                _log.warn("Tunnel creation timed out for tunnel " + _tunnel.getTunnelId() + " at router " 
                           + _tunnel.getThisHop().toBase64() + " from router " 
                           + getContext().routerHash().toBase64() + " after waiting " 
                           + (getContext().clock().now()-_started) + "ms");
                _log.warn("Added by", Failure.this.getAddedBy());
            }
            synchronized (_failedTunnelParticipants) {
                _failedTunnelParticipants.add(_tunnel.getThisHop());
            }
            Failure.this.getContext().messageHistory().tunnelRequestTimedOut(_tunnel.getThisHop(), _tunnel.getTunnelId());
            long responseTime = getContext().clock().now() - _started;
            // perhaps not an explicit reject, but an implicit one (due to dropped messages, tunnel failure, etc)
            getContext().profileManager().tunnelRejected(_tunnel.getThisHop(), responseTime, false);
            getContext().profileManager().messageFailed(_tunnel.getThisHop());
            Failure.this.getContext().statManager().updateFrequency("tunnel.buildFailFrequency");
            fail();
        }
    }
    
    private class Selector implements MessageSelector {
        private TunnelInfo _tunnel;
        private boolean _statusFound;
        private long _attemptExpiration;
        
        public Selector(TunnelInfo tunnel) {
            _tunnel = tunnel;
            _statusFound = false;
            _attemptExpiration = getContext().clock().now() + _timeoutMs;
        }
        
        public boolean continueMatching() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("ContinueMatching looking for tunnel " + _tunnel.getTunnelId().getTunnelId() 
                           + " from " + _tunnel.getThisHop().toBase64() + ": found? " + _statusFound);
            return !_statusFound;
        }
        public long getExpiration() { return _attemptExpiration; }
        public boolean isMatch(I2NPMessage message) {
            if (message.getType() == TunnelCreateStatusMessage.MESSAGE_TYPE) {
                TunnelCreateStatusMessage msg = (TunnelCreateStatusMessage)message;
                if (_tunnel.getThisHop().equals(msg.getFromHash())) {
                    if (_tunnel.getTunnelId().equals(msg.getTunnelId())) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Matches the tunnel create status message");
                        _statusFound = true;
                        return true;
                    } else {
                        // hmm another tunnel through the peer...
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Status message from peer [" + msg.getFromHash().toBase64() 
                                       + "], with wrong tunnelId [" + msg.getTunnelId() 
                                       + "] not [" + _tunnel.getTunnelId().getTunnelId() + "]");
                        return false;
                    }
                } else {
                    // status message but from the wrong peer
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Status message from the wrong peer [" 
                                   + msg.getFromHash().toBase64() + "], not [" 
                                   + _tunnel.getThisHop().toBase64() + "]");
                    return false;
                }
            } else {
                //_log.debug("Message " + message.getClass().getName() 
                //           + " is not a delivery status or tunnel create status message [waiting for ok for tunnel " 
                //           + _tunnel.getTunnelId() + " so we can fire " + _onCreated + "]");
                return false;
            }
        }
        
        public String toString() { 
            return "Build Tunnel Job Selector for tunnel " + _tunnel.getTunnelId().getTunnelId() 
                   + " at " + _tunnel.getThisHop().toBase64() + " [found=" + _statusFound + "] (@" 
                   + (new Date(getExpiration())) + ")"; 
        }
    }
}
