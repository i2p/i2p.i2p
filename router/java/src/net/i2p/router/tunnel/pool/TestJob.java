package net.i2p.router.tunnel.pool;

import java.util.HashSet;
import java.util.Set;

import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Certificate;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.GarlicMessageBuilder;
import net.i2p.router.message.PayloadGarlicConfig;
import net.i2p.util.Log;

class TestJob extends JobImpl {
    private final Log _log;
    private final TunnelPool _pool;
    private final PooledTunnelCreatorConfig _cfg;
    private boolean _found;
    private TunnelInfo _outTunnel;
    private TunnelInfo _replyTunnel;
    private PooledTunnelCreatorConfig _otherTunnel;
    /** save this so we can tell the SKM to kill it if the test fails */
    private SessionTag _encryptTag;
    
    /** base to randomize the test delay on */
    private static final int TEST_DELAY = 30*1000;
    private static final long[] RATES = { 60*1000, 10*60*1000l, 60*60*1000l };
    
    public TestJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _log = ctx.logManager().getLog(TestJob.class);
        _cfg = cfg;
        if (pool != null)
            _pool = pool;
        else
            _pool = cfg.getTunnelPool();
        if ( (_pool == null) && (_log.shouldLog(Log.ERROR)) )
            _log.error("Invalid tunnel test configuration: no pool for " + cfg, new Exception("origin"));
        getTiming().setStartAfter(getDelay() + ctx.clock().now());
        ctx.statManager().createRateStat("tunnel.testFailedTime", "How long did the failure take (max of 60s for full timeout)?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testExploratoryFailedTime", "How long did the failure of an exploratory tunnel take (max of 60s for full timeout)?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testFailedCompletelyTime", "How long did the complete failure take (max of 60s for full timeout)?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testExploratoryFailedCompletelyTime", "How long did the complete failure of an exploratory tunnel take (max of 60s for full timeout)?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testSuccessLength", "How long were the tunnels that passed the test?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testSuccessTime", "How long did tunnel testing take?", "Tunnels", 
                                         RATES);
        ctx.statManager().createRateStat("tunnel.testAborted", "Tunnel test could not occur, since there weren't any tunnels to test with", "Tunnels", 
                                         RATES);
    }

    public String getName() { return "Test tunnel"; }

    public void runJob() {
        if (_pool == null)
            return;
        long lag = getContext().jobQueue().getMaxLag();
        if (lag > 3000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Deferring test of " + _cfg + " due to job lag = " + lag);
            getContext().statManager().addRateData("tunnel.testAborted", _cfg.getLength(), 0);
            scheduleRetest();
            return;
        }
        if (getContext().router().gracefulShutdownInProgress())
            return;   // don't reschedule
        _found = false;
        // note: testing with exploratory tunnels always, even if the tested tunnel
        // is a client tunnel (per _cfg.getDestination())
        // should we test with the tunnel that we exposed the creation with?
        // (accessible as _cfg.getPairedTunnel())
        _replyTunnel = null;
        _outTunnel = null;
        if (_cfg.isInbound()) {
            _replyTunnel = _cfg;
            _outTunnel = getContext().tunnelManager().selectOutboundTunnel();
            _otherTunnel = (PooledTunnelCreatorConfig) _outTunnel;
        } else {
            _replyTunnel = getContext().tunnelManager().selectInboundTunnel();
            _outTunnel = _cfg;
            _otherTunnel = (PooledTunnelCreatorConfig) _replyTunnel;
        }
        
        if ( (_replyTunnel == null) || (_outTunnel == null) ) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Insufficient tunnels to test " + _cfg + " with: " + _replyTunnel + " / " + _outTunnel);
            getContext().statManager().addRateData("tunnel.testAborted", _cfg.getLength(), 0);
            scheduleRetest();
        } else {
            int testPeriod = getTestPeriod();
            long testExpiration = getContext().clock().now() + testPeriod;
            DeliveryStatusMessage m = new DeliveryStatusMessage(getContext());
            m.setArrival(getContext().clock().now());
            m.setMessageExpiration(testExpiration);
            m.setMessageId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
            ReplySelector sel = new ReplySelector(getContext(), m.getMessageId(), testExpiration);
            OnTestReply onReply = new OnTestReply(getContext());
            OnTestTimeout onTimeout = new OnTestTimeout(getContext());
            OutNetMessage msg = getContext().messageRegistry().registerPending(sel, onReply, onTimeout, testPeriod);
            onReply.setSentMessage(msg);
            sendTest(m);
        }
    }
    
    private void sendTest(I2NPMessage m) {
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
        _encryptTag = encryptTag;
        SessionKey sentKey = new SessionKey();
        Set sentTags = null;
        GarlicMessage msg = GarlicMessageBuilder.buildMessage(getContext(), payload, sentKey, sentTags, 
                                                              getContext().keyManager().getPublicKey(), 
                                                              encryptKey, encryptTag);

        if (msg == null) {
            // overloaded / unknown peers / etc
            scheduleRetest();
            return;
        }
        // can't be a singleton, the SKM modifies it
        Set encryptTags = new HashSet(1);
        encryptTags.add(encryptTag);
        // Register the single tag with the appropriate SKM
        if (_cfg.isInbound() && !_pool.getSettings().isExploratory()) {
            SessionKeyManager skm = getContext().clientManager().getClientSessionKeyManager(_pool.getSettings().getDestination());
            if (skm != null)
                skm.tagsReceived(encryptKey, encryptTags);
        } else {
            getContext().sessionKeyManager().tagsReceived(encryptKey, encryptTags);
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending garlic test of " + _outTunnel + " / " + _replyTunnel);
        getContext().tunnelDispatcher().dispatchOutbound(msg, _outTunnel.getSendTunnelId(0),
                                                         _replyTunnel.getReceiveTunnelId(0),
                                                         _replyTunnel.getPeer(0));
    }
    
    public void testSuccessful(int ms) {
        getContext().statManager().addRateData("tunnel.testSuccessLength", _cfg.getLength(), 0);
        getContext().statManager().addRateData("tunnel.testSuccessTime", ms, 0);
    
        _outTunnel.incrementVerifiedBytesTransferred(1024);
        // reply tunnel is marked in the inboundEndpointProcessor
        //_replyTunnel.incrementVerifiedBytesTransferred(1024);
        
        noteSuccess(ms, _outTunnel);
        noteSuccess(ms, _replyTunnel);
        
        _cfg.testJobSuccessful(ms);
        // credit the expl. tunnel too
        if (_otherTunnel.getLength() > 1)
            _otherTunnel.testJobSuccessful(ms);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Tunnel test successful in " + ms + "ms: " + _cfg);
        scheduleRetest();
    }
    
    private void noteSuccess(long ms, TunnelInfo tunnel) {
        if (tunnel != null)
            for (int i = 0; i < tunnel.getLength(); i++)
                getContext().profileManager().tunnelTestSucceeded(tunnel.getPeer(i), ms);
    }
    
    private void testFailed(long timeToFail) {
        if (_found) {
            // ok, not really a /success/, but we did find it, even though slowly
            noteSuccess(timeToFail, _outTunnel);
            noteSuccess(timeToFail, _replyTunnel);
        }
        if (_pool.getSettings().isExploratory())
            getContext().statManager().addRateData("tunnel.testExploratoryFailedTime", timeToFail, timeToFail);
        else
            getContext().statManager().addRateData("tunnel.testFailedTime", timeToFail, timeToFail);
        if (_log.shouldLog(Log.WARN))
            _log.warn("Tunnel test failed in " + timeToFail + "ms: " + _cfg);
        boolean keepGoing = _cfg.tunnelFailed();
        // blame the expl. tunnel too
        if (_otherTunnel.getLength() > 1)
            _otherTunnel.tunnelFailed();
        if (keepGoing) {
            scheduleRetest(true);
        } else {
            if (_pool.getSettings().isExploratory())
                getContext().statManager().addRateData("tunnel.testExploratoryFailedCompletelyTime", timeToFail, timeToFail);
            else
                getContext().statManager().addRateData("tunnel.testFailedCompletelyTime", timeToFail, timeToFail);
        }
    }
    
    /** randomized time we should wait before testing */
    private int getDelay() { return TEST_DELAY + getContext().random().nextInt(TEST_DELAY); }

    /** how long we allow tests to run for before failing them */
    private int getTestPeriod() {
        if (_outTunnel == null || _replyTunnel == null)
            return 15*1000;
        // Give it 2.5s per hop + 5s (2 hop tunnel = length 3, so this will be 15s for two 2-hop tunnels)
        // Minimum is 7.5s (since a 0-hop could be the expl. tunnel, but only >= 1-hop client tunnels are tested)
        // Network average for success is about 1.5s.
        // Another possibility - make configurable via pool options
        //
        // Try to prevent congestion collapse (failing all our tunnels and then clogging our outbound
        // with new tunnel build requests) by adding in three times the average outbound delay.
        int delay = 3 * (int) getContext().statManager().getRate("transport.sendProcessingTime").getRate(60*1000).getAverageValue();
        return delay + (2500 * (_outTunnel.getLength() + _replyTunnel.getLength()));
    }

    private void scheduleRetest() { scheduleRetest(false); }
    private void scheduleRetest(boolean asap) {
        if (asap) {
            requeue(getContext().random().nextInt(TEST_DELAY));
        } else {
            int delay = getDelay();
            if (_cfg.getExpiration() > getContext().clock().now() + delay + (3 * getTestPeriod()))
                requeue(delay);
        }
    }
    
    private class ReplySelector implements MessageSelector {
        private final RouterContext _context;
        private final long _id;
        private final long _expiration;

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
        
        @Override
        public String toString() {
            StringBuilder rv = new StringBuilder(64);
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
        private OutNetMessage _sentMessage;

        public OnTestReply(RouterContext ctx) { super(ctx); }

        public String getName() { return "Tunnel test success"; }

        public void setSentMessage(OutNetMessage m) { _sentMessage = m; }

        public void runJob() { 
            if (_sentMessage != null)
                getContext().messageRegistry().unregisterPending(_sentMessage);
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
        
        @Override
        public String toString() {
            StringBuilder rv = new StringBuilder(64);
            rv.append("Testing tunnel ").append(_cfg.toString());
            rv.append(" successful after ").append(_successTime);
            return rv.toString();
        }
    }
    
    /**
     * Test failed (boo, hiss)
     */
    private class OnTestTimeout extends JobImpl {
        private final long _started;

        public OnTestTimeout(RouterContext ctx) { 
            super(ctx); 
            _started = ctx.clock().now();
        }

        public String getName() { return "Tunnel test timeout"; }

        public void runJob() {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Timeout: found? " + _found);
            if (!_found) {
                // don't clog up the SKM with old one-tag tagsets
                if (_cfg.isInbound() && !_pool.getSettings().isExploratory()) {
                    SessionKeyManager skm = getContext().clientManager().getClientSessionKeyManager(_pool.getSettings().getDestination());
                    if (skm != null)
                        skm.consumeTag(_encryptTag);
                } else {
                    getContext().sessionKeyManager().consumeTag(_encryptTag);
                }
                testFailed(getContext().clock().now() - _started);
            }
        }
        
        @Override
        public String toString() {
            StringBuilder rv = new StringBuilder(64);
            rv.append("Testing tunnel ").append(_cfg.toString());
            rv.append(" timed out");
            return rv.toString();
        }
    }
}
