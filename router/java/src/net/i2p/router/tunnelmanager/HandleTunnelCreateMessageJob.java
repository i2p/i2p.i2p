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

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.SourceRouteBlock;
import net.i2p.data.i2np.TunnelCreateMessage;
import net.i2p.data.i2np.TunnelCreateStatusMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSettings;
import net.i2p.router.message.BuildTestMessageJob;
import net.i2p.router.message.SendReplyMessageJob;
import net.i2p.util.Log;

public class HandleTunnelCreateMessageJob extends JobImpl {
    private Log _log;
    private TunnelCreateMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private SourceRouteBlock _replyBlock;
    
    private final static long TIMEOUT = 30*1000; // 30 secs to contact a peer that will be our next hop
    private final static int PRIORITY = 123;
    
    HandleTunnelCreateMessageJob(RouterContext ctx, TunnelCreateMessage receivedMessage, 
                                 RouterIdentity from, Hash fromHash, SourceRouteBlock replyBlock) {
        super(ctx);
        _log = ctx.logManager().getLog(HandleTunnelCreateMessageJob.class);
        ctx.statManager().createRateStat("tunnel.rejectOverloaded", "How many tunnels did we deny due to throttling?", "Tunnels", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
        _replyBlock = replyBlock;
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
                       + " with ok (" + ok + ") to router " + _message.getReplyBlock().getRouter().toBase64());
    
        getContext().messageHistory().receiveTunnelCreate(_message.getTunnelId(), _message.getNextRouter(), 
                                                      new Date(getContext().clock().now() + 1000*_message.getTunnelDurationSeconds()), 
                                                      ok, _message.getReplyBlock().getRouter());

        TunnelCreateStatusMessage msg = new TunnelCreateStatusMessage(getContext());
        msg.setFromHash(getContext().routerHash());
        msg.setTunnelId(_message.getTunnelId());
        if (ok) {
            msg.setStatus(TunnelCreateStatusMessage.STATUS_SUCCESS);
        } else {
            // since we don't actually check anything, this is a catch all
            msg.setStatus(TunnelCreateStatusMessage.STATUS_FAILED_OVERLOADED);
        }
        msg.setMessageExpiration(new Date(getContext().clock().now()+60*1000));
        SendReplyMessageJob job = new SendReplyMessageJob(getContext(), _message.getReplyBlock(), msg, PRIORITY);
        getContext().jobQueue().addJob(job);
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
