package net.i2p.router.tunnelmanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;
import java.util.List;

import net.i2p.data.Certificate;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.TunnelCreateMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelCreateStatusMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSettings;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.message.BuildTestMessageJob;
import net.i2p.router.message.GarlicConfig;
import net.i2p.router.message.GarlicMessageBuilder;
import net.i2p.router.message.PayloadGarlicConfig;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Log;

public class HandleTunnelCreateMessageJob extends JobImpl {
    private Log _log;
    private TunnelCreateMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    
    private final static long TIMEOUT = 30*1000; // 30 secs to contact a peer that will be our next hop
    private final static int PRIORITY = 123;
    
    HandleTunnelCreateMessageJob(RouterContext ctx, TunnelCreateMessage receivedMessage, 
                                 RouterIdentity from, Hash fromHash) {
        super(ctx);
        _log = ctx.logManager().getLog(HandleTunnelCreateMessageJob.class);
        ctx.statManager().createRateStat("tunnel.rejectOverloaded", "How many tunnels did we deny due to throttling?", "Tunnels", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
    }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Handling tunnel create");
        if (isOverloaded()) {
            sendReply(false);
            return;
        } 
        TunnelInfo info = new TunnelInfo(getContext());
        info.setConfigurationKey(_message.getConfigurationKey());
        info.setEncryptionKey(_message.getTunnelKey());
        info.setNextHop(_message.getNextRouter());
        info.setNextHopId(_message.getNextTunnelId());
	
        TunnelSettings settings = new TunnelSettings(getContext());
        settings.setBytesPerMinuteAverage(_message.getMaxAvgBytesPerMin());
        settings.setBytesPerMinutePeak(_message.getMaxPeakBytesPerMin());
        settings.setMessagesPerMinuteAverage(_message.getMaxAvgMessagesPerMin());
        settings.setMessagesPerMinutePeak(_message.getMaxPeakMessagesPerMin());
        settings.setExpiration(_message.getTunnelDurationSeconds()*1000+getContext().clock().now());
        settings.setIncludeDummy(_message.getIncludeDummyTraffic());
        settings.setReorder(_message.getReorderMessages());
        info.setSettings(settings);

        info.setSigningKey(_message.getVerificationPrivateKey());
        info.setThisHop(getContext().routerHash());
        info.setTunnelId(_message.getTunnelId());
        info.setVerificationKey(_message.getVerificationPublicKey());
	
        info.getTunnelId().setType(TunnelId.TYPE_PARTICIPANT);
	
        if (_message.getNextRouter() == null) {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("We're the endpoint, don't test the \"next\" peer [duh]");
            boolean ok = getContext().tunnelManager().joinTunnel(info);
            sendReply(ok);
        } else {
            getContext().netDb().lookupRouterInfo(info.getNextHop(), new TestJob(info), new JoinJob(info, false), TIMEOUT);
        }
    }
    
    private boolean isOverloaded() {
        boolean shouldAccept = getContext().throttle().acceptTunnelRequest(_message);
        if (!shouldAccept) {
            getContext().statManager().addRateData("tunnel.rejectOverloaded", 1, 1);
            if (_log.shouldLog(Log.INFO))
                _log.info("Refusing tunnel request due to overload");
        }
        return !shouldAccept;
    }
    
    private class TestJob extends JobImpl {
        private TunnelInfo _target;
        public TestJob(TunnelInfo target) {
            super(HandleTunnelCreateMessageJob.this.getContext());
            _target = target;
        }

        public String getName() { return "Run a test for peer reachability"; }
        public void runJob() {
            RouterInfo info = TestJob.this.getContext().netDb().lookupRouterInfoLocally(_target.getNextHop());
            if (info == null) {
                if (_log.shouldLog(Log.ERROR)) 
                    _log.error("Error - unable to look up peer " + _target.toBase64() + ", even though we were queued up via onSuccess??");
                return;
            } else {
                if (_log.shouldLog(Log.INFO)) 
                    _log.info("Lookup successful for tested peer " + _target.toBase64() + ", now continue with the test");
                Hash peer = TestJob.this.getContext().routerHash();
                JoinJob success = new JoinJob(_target, true);
                JoinJob failure = new JoinJob(_target, false);
                BuildTestMessageJob test = new BuildTestMessageJob(TestJob.this.getContext(), info, peer, success, failure, TIMEOUT, PRIORITY);
                TestJob.this.getContext().jobQueue().addJob(test);
            }
        }
    }
    
    private void sendReply(boolean ok) {
        if (_log.shouldLog(Log.DEBUG)) 
            _log.debug("Sending reply to a tunnel create of id " + _message.getTunnelId() 
                       + " with ok (" + ok + ") to tunnel " + _message.getReplyTunnel()
                       + " on router " + _message.getReplyPeer());
    
        getContext().messageHistory().receiveTunnelCreate(_message.getTunnelId(), _message.getNextRouter(), 
                                                          new Date(getContext().clock().now() + 1000*_message.getTunnelDurationSeconds()), 
                                                          ok, _message.getReplyPeer());

        TunnelCreateStatusMessage msg = new TunnelCreateStatusMessage(getContext());
        msg.setFromHash(getContext().routerHash());
        msg.setTunnelId(_message.getTunnelId());
        if (ok) {
            msg.setStatus(TunnelCreateStatusMessage.STATUS_SUCCESS);
        } else {
            // since we don't actually check anything, this is a catch all
            msg.setStatus(TunnelCreateStatusMessage.STATUS_FAILED_OVERLOADED);
        }
        msg.setMessageExpiration(new Date(getContext().clock().now()+TIMEOUT));
        
        // put that message into a garlic
        GarlicMessage reply = createReply(msg);
        
        TunnelId outTunnelId = selectReplyTunnel();
        
        SendTunnelMessageJob job = new SendTunnelMessageJob(getContext(), reply, outTunnelId, 
                                                            _message.getReplyPeer(), 
                                                            _message.getReplyTunnel(), 
                                                            (Job)null, (ReplyJob)null, 
                                                            (Job)null, (MessageSelector)null, 
                                                            TIMEOUT, PRIORITY);
        getContext().jobQueue().addJob(job);
    }
    
    private GarlicMessage createReply(TunnelCreateStatusMessage body) {
        GarlicConfig cfg = createReplyConfig(body);
        return GarlicMessageBuilder.buildMessage(getContext(), cfg, null, null, null, 
                                                 _message.getReplyKey(), _message.getReplyTag());
    }
    
    private GarlicConfig createReplyConfig(TunnelCreateStatusMessage body) {
        GarlicConfig config = new GarlicConfig();
        
        PayloadGarlicConfig replyClove = buildReplyClove(body);
        config.addClove(replyClove);
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
        instructions.setDelayRequested(false);
        instructions.setDelaySeconds(0);
        instructions.setEncrypted(false);
        instructions.setEncryptionKey(null);
        instructions.setRouter(null);
        instructions.setTunnelId(null);
        
        config.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        config.setDeliveryInstructions(instructions);
        config.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        config.setExpiration(TIMEOUT+getContext().clock().now());
        config.setRecipient(null);
        config.setRequestAck(false);
        
        return config;
    }

    /**
     * Build a clove that sends the tunnel create reply 
     */
    private PayloadGarlicConfig buildReplyClove(TunnelCreateStatusMessage body) {
        PayloadGarlicConfig replyClove = new PayloadGarlicConfig();
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
        instructions.setRouter(null);
        instructions.setDelayRequested(false);
        instructions.setDelaySeconds(0);
        instructions.setEncrypted(false);
        
        replyClove.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        replyClove.setDeliveryInstructions(instructions);
        replyClove.setExpiration(TIMEOUT+getContext().clock().now());
        replyClove.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        replyClove.setPayload(body);
        replyClove.setRecipient(null);
        replyClove.setRequestAck(false);
        
        return replyClove;
    }
    
    
    private TunnelId selectReplyTunnel() {
        TunnelSelectionCriteria crit = new TunnelSelectionCriteria();
        crit.setMinimumTunnelsRequired(1);
        crit.setMaximumTunnelsRequired(1);
        List ids = getContext().tunnelManager().selectOutboundTunnelIds(crit);
        if ( (ids != null) && (ids.size() > 0) )
            return (TunnelId)ids.get(0);
        else
            return null;
    }
    
    public String getName() { return "Handle Tunnel Create Message"; }
    
    private class JoinJob extends JobImpl {
        private TunnelInfo _info;
        private boolean _isReachable;
        public JoinJob(TunnelInfo info, boolean isReachable) {
            super(HandleTunnelCreateMessageJob.this.getContext());
            _info = info;
            _isReachable = isReachable;
        }
	
        public void runJob() {
            if (!_isReachable) {
                long before = JoinJob.this.getContext().clock().now();
                sendReply(false);
                long after = JoinJob.this.getContext().clock().now();
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("JoinJob .refuse took " + (after-before) + "ms to refuse " + _info);
            } else {
                long before = JoinJob.this.getContext().clock().now();
                boolean ok = JoinJob.this.getContext().tunnelManager().joinTunnel(_info);
                long afterJoin = JoinJob.this.getContext().clock().now();
                sendReply(ok);
                long after = JoinJob.this.getContext().clock().now();
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("JoinJob .joinTunnel took " + (afterJoin-before) + "ms and sendReply took " + (after-afterJoin) + "ms");
            }
        }
        public String getName() { return "Process the tunnel join after testing the nextHop"; }
    }
    
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), 
                                                             _message.getClass().getName(), 
                                                             "Dropped due to overload");
    }
}
