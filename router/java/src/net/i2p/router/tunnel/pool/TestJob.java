package net.i2p.router.tunnel.pool;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import net.i2p.data.Certificate;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.PayloadGarlicConfig;
import net.i2p.router.message.GarlicMessageBuilder;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Log;

class TestJob extends JobImpl {
    private Log _log;
    private TunnelPool _pool;
    private Object _buildToken;
    private PooledTunnelCreatorConfig _cfg;
    private boolean _found;
    
    /** base to randomize the test delay on */
    private static final int TEST_DELAY = 60*1000;
    
    public TestJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool, Object buildToken) {
        super(ctx);
        _log = ctx.logManager().getLog(TestJob.class);
        _pool = pool;
        _cfg = cfg;
        _buildToken = buildToken;
        getTiming().setStartAfter(getDelay() + ctx.clock().now());
        ctx.statManager().createRateStat("tunnel.testFailedTime", "How long did the failure take (max of 60s for full timeout)?", "Tunnels", 
                                         new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.testExploratoryFailedTime", "How long did the failure of an exploratory tunnel take (max of 60s for full timeout)?", "Tunnels", 
                                         new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.testSuccessLength", "How long were the tunnels that passed the test?", "Tunnels", 
                                         new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.testSuccessTime", "How long did tunnel testing take?", "Tunnels", 
                                         new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("tunnel.testAborted", "Tunnel test could not occur, since there weren't any tunnels to test with", "Tunnels", 
                                         new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000l });
    }
    public String getName() { return "Test tunnel"; }
    public void runJob() {
        _found = false;
        // note: testing with exploratory tunnels always, even if the tested tunnel
        // is a client tunnel (per _cfg.getDestination())
        TunnelInfo replyTunnel = null;
        TunnelInfo outTunnel = null;
        if (_cfg.isInbound()) {
            replyTunnel = _cfg;
            outTunnel = getContext().tunnelManager().selectOutboundTunnel();
        } else {
            replyTunnel = getContext().tunnelManager().selectInboundTunnel();
            outTunnel = _cfg;
        }
        
        if ( (replyTunnel == null) || (outTunnel == null) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Insufficient tunnels to test " + _cfg + " with: " + replyTunnel + " / " + outTunnel);
            getContext().statManager().addRateData("tunnel.testAborted", _cfg.getLength(), 0);
            scheduleRetest();
        } else {
            int testPeriod = getTestPeriod();
            long testExpiration = getContext().clock().now() + testPeriod;
            DeliveryStatusMessage m = new DeliveryStatusMessage(getContext());
            m.setArrival(getContext().clock().now());
            m.setMessageExpiration(testExpiration+2*testPeriod);
            m.setMessageId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
            // keep an eye out for the message even after we fail the tunnel for another 40s
            ReplySelector sel = new ReplySelector(getContext(), m.getMessageId(), testExpiration + 2*testPeriod);
            OnTestReply onReply = new OnTestReply(getContext());
            OnTestTimeout onTimeout = new OnTestTimeout(getContext());
            getContext().messageRegistry().registerPending(sel, onReply, onTimeout, 3*testPeriod);
            sendTest(m, outTunnel, replyTunnel);
        }
    }
    
    private void sendTest(I2NPMessage m, TunnelInfo outTunnel, TunnelInfo replyTunnel) {
        if (false) {
            getContext().tunnelDispatcher().dispatchOutbound(m, outTunnel.getSendTunnelId(0), 
                                                             replyTunnel.getReceiveTunnelId(0), 
                                                             replyTunnel.getPeer(0));
        } else {
            // garlic route that DeliveryStatusMessage to ourselves so the endpoints and gateways
            // can't tell its a test.  to simplify this, we encrypt it with a random key and tag,
            // remembering that key+tag so that we can decrypt it later.  this means we can do the
            // garlic encryption without any ElGamal (yay)
            DeliveryInstructions instructions = new DeliveryInstructions();
            instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);

            PayloadGarlicConfig payload = new PayloadGarlicConfig();
            payload.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
            payload.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
            payload.setPayload(m);
            payload.setRecipient(getContext().router().getRouterInfo());
            payload.setDeliveryInstructions(instructions);
            payload.setRequestAck(false);
            payload.setExpiration(m.getMessageExpiration());

            SessionKey encryptKey = getContext().keyGenerator().generateSessionKey();
            SessionTag encryptTag = new SessionTag(true);
            SessionKey sentKey = new SessionKey();
            Set sentTags = null;
            GarlicMessage msg = GarlicMessageBuilder.buildMessage(getContext(), payload, sentKey, sentTags, 
                                                                  getContext().keyManager().getPublicKey(), 
                                                                  encryptKey, encryptTag);

            Set encryptTags = new HashSet(1);
            encryptTags.add(encryptTag);
            getContext().sessionKeyManager().tagsReceived(encryptKey, encryptTags);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending garlic test of " + outTunnel + " / " + replyTunnel);
            getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0),
                                                             replyTunnel.getReceiveTunnelId(0),
                                                             replyTunnel.getPeer(0));
        }
    }
    
    public void testSuccessful(int ms) {
        getContext().statManager().addRateData("tunnel.testSuccessLength", _cfg.getLength(), 0);
        getContext().statManager().addRateData("tunnel.testSuccessTime", ms, 0);
        scheduleRetest();
    }
    
    private void testFailed(long timeToFail) {
        if (_pool.getSettings().isExploratory())
            getContext().statManager().addRateData("tunnel.testExploratoryFailedTime", timeToFail, timeToFail);
        else
            getContext().statManager().addRateData("tunnel.testFailedTime", timeToFail, timeToFail);
        _cfg.tunnelFailed();
        if (_log.shouldLog(Log.WARN))
            _log.warn("Tunnel test failed in " + timeToFail + "ms: " + _cfg);
    }
    
    /** randomized time we should wait before testing */
    private int getDelay() { return TEST_DELAY + getContext().random().nextInt(TEST_DELAY); }
    /** how long we allow tests to run for before failing them */
    private int getTestPeriod() { return 20*1000; }
    private void scheduleRetest() {
        int delay = getDelay();
        if (_cfg.getExpiration() > getContext().clock().now() + delay)
            requeue(delay);
    }
    
    private class ReplySelector implements MessageSelector {
        private RouterContext _context;
        private long _id;
        private long _expiration;
        public ReplySelector(RouterContext ctx, long id, long expiration) {
            _context = ctx;
            _id = id;
            _expiration = expiration;
            _found = false;
        }
        
        public boolean continueMatching() { return !_found && _context.clock().now() < _expiration; }
        public long getExpiration() { return _expiration; }
        public boolean isMatch(I2NPMessage message) {
            if (message instanceof DeliveryStatusMessage) {
                return ((DeliveryStatusMessage)message).getMessageId() == _id;
            }
            return false;
        }
        
        public String toString() {
            StringBuffer rv = new StringBuffer(64);
            rv.append("Testing tunnel ").append(_cfg.toString()).append(" waiting for ");
            rv.append(_id).append(" found? ").append(_found);
            return rv.toString();
        }
    }
    
    /**
     * Test successfull (w00t)
     */
    private class OnTestReply extends JobImpl implements ReplyJob {
        private long _successTime;
        public OnTestReply(RouterContext ctx) { super(ctx); }
        public String getName() { return "Tunnel test success"; }
        public void runJob() { 
            if (_successTime < getTestPeriod())
                testSuccessful((int)_successTime);
            else
                testFailed(_successTime);
            _found = true;
        }
        // who cares about the details...
        public void setMessage(I2NPMessage message) {
            _successTime = getContext().clock().now() - ((DeliveryStatusMessage)message).getArrival();
        }
        
        public String toString() {
            StringBuffer rv = new StringBuffer(64);
            rv.append("Testing tunnel ").append(_cfg.toString());
            rv.append(" successful after ").append(_successTime);
            return rv.toString();
        }
    }
    
    /**
     * Test failed (boo, hiss)
     */
    private class OnTestTimeout extends JobImpl {
        private long _started;
        public OnTestTimeout(RouterContext ctx) { 
            super(ctx); 
            _started = ctx.clock().now();
        }
        public String getName() { return "Tunnel test timeout"; }
        public void runJob() {
            if (!_found)
                testFailed(getContext().clock().now() - _started);
        }
        
        public String toString() {
            StringBuffer rv = new StringBuffer(64);
            rv.append("Testing tunnel ").append(_cfg.toString());
            rv.append(" timed out");
            return rv.toString();
        }
    }
}