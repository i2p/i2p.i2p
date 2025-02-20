package net.i2p.router.tunnel.pool;

import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.SessionTag;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.crypto.ratchet.MuxedSKM;
import net.i2p.router.crypto.ratchet.RatchetSessionTag;
import net.i2p.router.crypto.ratchet.RatchetSKM;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 *  Repeatedly test a single tunnel for its entire lifetime,
 *  or until the pool is shut down or removed from the client manager.
 *
 *  Tunnel testing is enabled by default.
 *  see TunnelPoolManager.buildComplete()
 */
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
    private RatchetSessionTag _ratchetEncryptTag;
    private static final AtomicInteger __id = new AtomicInteger();
    private int _id;
    
    /** base to randomize the test delay on */
    private static final int TEST_DELAY = 40*1000;
    
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
        // stats are created in TunnelPoolManager
    }

    public String getName() { return "Test tunnel"; }

    public void runJob() {
        if (_pool == null || !_pool.isAlive())
            return;
        final RouterContext ctx = getContext();
        long lag = ctx.jobQueue().getMaxLag();
        if (lag > 3000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Deferring test of " + _cfg + " due to job lag = " + lag);
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            scheduleRetest();
            return;
        }
        if (ctx.router().gracefulShutdownInProgress())
            return;   // don't reschedule
        _found = false;
        // NOTE:
        // We now only support client-to-client and expl-to-expl testing.
        // To support "mixed" testing, fix sendTest() to use the SKM
        // of the inbound tunnel.
        boolean isExpl = _pool.getSettings().isExploratory();
        if (_cfg.isInbound()) {
            _replyTunnel = _cfg;
            if (isExpl)
                _outTunnel = ctx.tunnelManager().selectOutboundTunnel();
            else
                _outTunnel = ctx.tunnelManager().selectOutboundTunnel(_pool.getSettings().getDestination());
            _otherTunnel = (PooledTunnelCreatorConfig) _outTunnel;
        } else {
            if (isExpl)
                _replyTunnel = ctx.tunnelManager().selectInboundTunnel();
            else
                _replyTunnel = ctx.tunnelManager().selectInboundTunnel(_pool.getSettings().getDestination());
            _outTunnel = _cfg;
            _otherTunnel = (PooledTunnelCreatorConfig) _replyTunnel;
        }
        
        if ( (_replyTunnel == null) || (_outTunnel == null) ) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Insufficient tunnels to test " + _cfg + " with: " + _replyTunnel + " / " + _outTunnel);
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            scheduleRetest();
        } else {
            int testPeriod = getTestPeriod();
            long now = ctx.clock().now();
            long testExpiration = now + testPeriod;
            DeliveryStatusMessage m = new DeliveryStatusMessage(ctx);
            m.setArrival(now);
            m.setMessageExpiration(testExpiration);
            m.setMessageId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            ReplySelector sel = new ReplySelector(m.getMessageId(), testExpiration);
            OnTestReply onReply = new OnTestReply();
            OnTestTimeout onTimeout = new OnTestTimeout(now);
            OutNetMessage msg = ctx.messageRegistry().registerPending(sel, onReply, onTimeout);
            onReply.setSentMessage(msg);
            sendTest(m, testPeriod);
        }
    }
    
    private void sendTest(I2NPMessage m, int testPeriod) {
        // garlic route that DeliveryStatusMessage to ourselves so the endpoints and gateways
        // can't tell its a test.  to simplify this, we encrypt it with a random key and tag,
        // remembering that key+tag so that we can decrypt it later.  this means we can do the
        // garlic encryption without any ElGamal (yay)
        final RouterContext ctx = getContext();
        _id = __id.getAndIncrement();
        if (ctx.random().nextInt(4) != 0) {
            MessageWrapper.OneTimeSession sess;
            // NOTE:
            // We now only support client-to-client and expl-to-expl testing.
            // To support "mixed" testing, fix this to use the SKM
            // of the inbound tunnel, not the tested tunnel.
            if (!_pool.getSettings().isExploratory()) {
                // to client. false means don't force AES
                sess = MessageWrapper.generateSession(ctx, _pool.getSettings().getDestination(), testPeriod, false);
            } else {
                // to router. AES or ChaCha.
                sess = MessageWrapper.generateSession(ctx, testPeriod);
            }
            if (sess == null) {
                scheduleRetest();
                return;
            }
            if (sess.tag != null) {
                // AES
                _encryptTag = sess.tag;
                m = MessageWrapper.wrap(ctx, m, sess.key, sess.tag);
            } else {
                // ratchet
                _ratchetEncryptTag = sess.rtag;
                m = MessageWrapper.wrap(ctx, m, sess.key, sess.rtag);
            }
            if (m == null) {
                // overloaded / unknown peers / etc
                scheduleRetest();
                return;
            }
        } else {
            // Periodically send unencrypted DSM to provide cover for netdb replies
            if (_log.shouldDebug())
                _log.debug("Sending tunnel test unencrypted, test #" + _id);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending garlic test #" + _id + " msg ID: " + m.getUniqueId() + " of " + _outTunnel + " / " + _replyTunnel);
        ctx.tunnelDispatcher().dispatchOutbound(m, _outTunnel.getSendTunnelId(0),
                                                         _replyTunnel.getReceiveTunnelId(0),
                                                         _replyTunnel.getPeer(0));
    }
    
    public void testSuccessful(int ms) {
        if (_pool == null || !_pool.isAlive())
            return;
        getContext().statManager().addRateData("tunnel.testSuccessLength", _cfg.getLength());
        getContext().statManager().addRateData("tunnel.testSuccessTime", ms);
    
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
            _log.debug("Tunnel test #" + _id + " successful in " + ms + "ms: " + _cfg);
        scheduleRetest();
    }
    
    private void noteSuccess(long ms, TunnelInfo tunnel) {
        if (tunnel != null)
            for (int i = 0; i < tunnel.getLength(); i++)
                getContext().profileManager().tunnelTestSucceeded(tunnel.getPeer(i), ms);
    }
    
    private void testFailed(long timeToFail) {
        if (_pool == null || !_pool.isAlive())
            return;
        if (_found) {
            // ok, not really a /success/, but we did find it, even though slowly
            noteSuccess(timeToFail, _outTunnel);
            noteSuccess(timeToFail, _replyTunnel);
        }
        if (_pool.getSettings().isExploratory())
            getContext().statManager().addRateData("tunnel.testExploratoryFailedTime", timeToFail);
        else
            getContext().statManager().addRateData("tunnel.testFailedTime", timeToFail);
        if (_log.shouldLog(Log.WARN))
            _log.warn("Tunnel test #" + _id + " failed in " + timeToFail + "ms: " + _cfg);
        boolean keepGoing = _cfg.tunnelFailed();
        // blame the expl. tunnel too
        if (_otherTunnel.getLength() > 1)
            _otherTunnel.tunnelFailed();
        if (keepGoing) {
            scheduleRetest(true);
        } else {
            if (_pool.getSettings().isExploratory())
                getContext().statManager().addRateData("tunnel.testExploratoryFailedCompletelyTime", timeToFail);
            else
                getContext().statManager().addRateData("tunnel.testFailedCompletelyTime", timeToFail);
        }
    }
    
    /** randomized time we should wait before testing */
    private int getDelay() { return TEST_DELAY + getContext().random().nextInt(TEST_DELAY / 3); }

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
        RateStat tspt = getContext().statManager().getRate("transport.sendProcessingTime");
        if (tspt != null) {
            Rate r = tspt.getRate(60*1000);
            if (r != null) {
                int delay = 3 * (int) r.getAverageValue();
                return delay + (2500 * (_outTunnel.getLength() + _replyTunnel.getLength()));
            }
        }
        return 15*1000;
    }

    private void scheduleRetest() { scheduleRetest(false); }

    private void scheduleRetest(boolean asap) {
        if (_pool == null || !_pool.isAlive())
            return;
        if (asap) {
            if (_cfg.getExpiration() > getContext().clock().now() + (60 * 1000))
                requeue((TEST_DELAY / 4) + getContext().random().nextInt(TEST_DELAY / 4));
        } else {
            int delay = getDelay();
            if (_cfg.getExpiration() > getContext().clock().now() + delay + (3 * getTestPeriod()))
                requeue(delay);
        }
    }
    
    private class ReplySelector implements MessageSelector {
        private final long _id;
        private final long _expiration;

        public ReplySelector(long id, long expiration) {
            _id = id;
            _expiration = expiration;
            _found = false;
        }
        
        public boolean continueMatching() { return !_found && getContext().clock().now() < _expiration; }

        public long getExpiration() { return _expiration; }

        public boolean isMatch(I2NPMessage message) {
            if (message.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
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

        public OnTestReply() { super(TestJob.this.getContext()); }

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

        public OnTestTimeout(long now) {
            super(TestJob.this.getContext()); 
            _started = now;
        }

        public String getName() { return "Tunnel test timeout"; }

        public void runJob() {
            if (_log.shouldDebug())
                _log.debug("Tunnel test #" + _id + " timeout: found? " + _found);
            if (!_found && (_encryptTag != null || _ratchetEncryptTag != null)) {
                // don't clog up the SKM with old one-tag tagsets
                SessionKeyManager skm;
                if (_cfg.isInbound() && !_pool.getSettings().isExploratory()) {
                    skm = getContext().clientManager().getClientSessionKeyManager(_pool.getSettings().getDestination());
                } else {
                    skm = getContext().sessionKeyManager();
                }
                if (skm != null) {
                    if (_encryptTag != null) {
                        // AES
                        skm.consumeTag(_encryptTag);
                    } else {
                        // ratchet
                        RatchetSKM rskm;
                        if (skm instanceof RatchetSKM) {
                            rskm = (RatchetSKM) skm;
                        } else if (skm instanceof MuxedSKM) {
                            rskm = ((MuxedSKM) skm).getECSKM();
                        } else {
                            // shouldn't happen
                            rskm = null;
                        }
                        if (rskm != null)
                            rskm.consumeTag(_ratchetEncryptTag);
                    }
                }
            }
            if (!_found) {
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
