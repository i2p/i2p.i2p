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

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Log;

class TestTunnelJob extends JobImpl {
    private Log _log;
    /** tunnel that we want to test */
    private TunnelId _primaryId;
    /** tunnel that is used to help test the primary id */
    private TunnelId _secondaryId;
    private TunnelPool _pool;
    private long _nonce;
    
    public TestTunnelJob(RouterContext ctx, TunnelId id, TunnelPool pool) {
        super(ctx);
        _log = ctx.logManager().getLog(TestTunnelJob.class);
        _primaryId = id;
        _pool = pool;
        _nonce = ctx.random().nextInt(Integer.MAX_VALUE);
    }
    public String getName() { return "Test Tunnel"; }
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Testing tunnel " + _primaryId.getTunnelId());
        TunnelInfo info = _pool.getTunnelInfo(_primaryId);
        if (info == null) {
            _log.error("wtf, why are we testing a tunnel that we do not know about? [" 
                       + _primaryId.getTunnelId() + "]", getAddedBy());
            return;
        }
        
        // mark it as something we're testing
        info.setLastTested(_context.clock().now());
        if (isOutbound(info)) {
            testOutbound(info);
        } else {
            testInbound(info);
        }
    }
    
    private boolean isOutbound(TunnelInfo info) {
        if (info == null) {
            _log.error("wtf, null info?", new Exception("Who checked a null tunnel info?"));
            return false;
        }
        if (_context.routerHash().equals(info.getThisHop()))
            return true;
        else
            return false;
    }
    
    private final static long TEST_TIMEOUT = 30*1000; // 30 seconds for a test to succeed
    private final static int TEST_PRIORITY = 100;
    
    /**
     * Send a message out the tunnel with instructions to send the message back
     * to ourselves and wait for it to arrive.
     */
    private void testOutbound(TunnelInfo info) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Testing outbound tunnel " + info);
        DeliveryStatusMessage msg = new DeliveryStatusMessage(_context);
        msg.setArrival(new Date(_context.clock().now()));
        msg.setMessageId(_nonce);
        Hash us = _context.routerHash();
        _secondaryId = getReplyTunnel();
        if (_secondaryId == null) {
            _context.jobQueue().addJob(new TestFailedJob());
            return;
        }
        
        TunnelInfo inboundInfo = _pool.getTunnelInfo(_secondaryId);
        inboundInfo.setLastTested(_context.clock().now());
        
        TestFailedJob failureJob = new TestFailedJob();
        MessageSelector selector = new TestMessageSelector(msg.getMessageId(), info.getTunnelId().getTunnelId());
        SendTunnelMessageJob testJob = new SendTunnelMessageJob(_context, msg, info.getTunnelId(), us, _secondaryId, null, new TestSuccessfulJob(), failureJob, selector, TEST_TIMEOUT, TEST_PRIORITY);
        _context.jobQueue().addJob(testJob);
    }

    /**
     * Send a message to the gateway and wait for it to arrive.
     */
    private void testInbound(TunnelInfo info) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Testing inbound tunnel " + info);
        DeliveryStatusMessage msg = new DeliveryStatusMessage(_context);
        msg.setArrival(new Date(_context.clock().now()));
        msg.setMessageId(_nonce);
        
        _secondaryId = getOutboundTunnel();
        if (_secondaryId == null) {
            _context.jobQueue().addJob(new TestFailedJob());
            return;
        }
        
        TunnelInfo outboundInfo = _pool.getTunnelInfo(_secondaryId);
        outboundInfo.setLastTested(_context.clock().now());
        
        TestFailedJob failureJob = new TestFailedJob();
        MessageSelector selector = new TestMessageSelector(msg.getMessageId(), info.getTunnelId().getTunnelId());
        SendTunnelMessageJob j = new SendTunnelMessageJob(_context, msg, _secondaryId, info.getThisHop(), info.getTunnelId(), null, new TestSuccessfulJob(), failureJob, selector, TEST_TIMEOUT, TEST_PRIORITY);
        _context.jobQueue().addJob(j);
    }
    
    /**
     * Get the tunnel for replies to be sent down when testing outbound tunnels
     *
     */
    private TunnelId getReplyTunnel() {
        TunnelSelectionCriteria crit = new TunnelSelectionCriteria();
        crit.setMinimumTunnelsRequired(2);
        crit.setMaximumTunnelsRequired(2);
        // arbitrary priorities
        crit.setAnonymityPriority(50);
        crit.setLatencyPriority(50);
        crit.setReliabilityPriority(50);
        List tunnelIds = _context.tunnelManager().selectInboundTunnelIds(crit);
        
        for (int i = 0; i < tunnelIds.size(); i++) {
            TunnelId id = (TunnelId)tunnelIds.get(i);
            if (id.equals(_primaryId)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Not testing a tunnel with itself [duh]");
            } else {
                return id;
            }
        }
        
        _log.error("Unable to test tunnel " + _primaryId + ", since there are NO OTHER INBOUND TUNNELS to receive the ack through");
        return null;
    }
    
    /**
     * Get the tunnel to send thte message out when testing inbound tunnels
     *
     */
    private TunnelId getOutboundTunnel() {
        TunnelSelectionCriteria crit = new TunnelSelectionCriteria();
        crit.setMinimumTunnelsRequired(2);
        crit.setMaximumTunnelsRequired(2);
        // arbitrary priorities
        crit.setAnonymityPriority(50);
        crit.setLatencyPriority(50);
        crit.setReliabilityPriority(50);
        List tunnelIds = _context.tunnelManager().selectOutboundTunnelIds(crit);
        
        for (int i = 0; i < tunnelIds.size(); i++) {
            TunnelId id = (TunnelId)tunnelIds.get(i);
            if (id.equals(_primaryId)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Not testing a tunnel with itself [duh]");
            } else {
                return id;
            }
        }
        
        _log.error("Unable to test tunnel " + _primaryId + ", since there are NO OTHER OUTBOUND TUNNELS to send the ack through");
        return null;
    }
    
    private class TestFailedJob extends JobImpl {
        public TestFailedJob() {
            super(TestTunnelJob.this._context);
        }
        
        public String getName() { return "Tunnel Test Failed"; }
        public void runJob() {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Test of tunnel " + _primaryId.getTunnelId() 
                          + " failed while waiting for nonce " + _nonce + ": " 
                          + _pool.getTunnelInfo(_primaryId), getAddedBy());
            _pool.tunnelFailed(_primaryId);
            if (_secondaryId != null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Secondary test of tunnel " + _secondaryId.getTunnelId() 
                              + " failed while waiting for nonce " + _nonce + ": " 
                              + _pool.getTunnelInfo(_secondaryId), getAddedBy());
                //_pool.tunnelFailed(_secondaryId);
            }
        }
    }
    
    private class TestSuccessfulJob extends JobImpl implements ReplyJob {
        private DeliveryStatusMessage _msg;
        public TestSuccessfulJob() {
            super(TestTunnelJob.this._context);
            _msg = null;
        }
        
        public String getName() { return "Tunnel Test Successful"; }
        public void runJob() {
            long time = (_context.clock().now() - _msg.getArrival().getTime());
            if (_log.shouldLog(Log.INFO))
                _log.info("Test of tunnel " + _primaryId+ " successfull after " 
                          + time + "ms waiting for " + _nonce);
            TunnelInfo info = _pool.getTunnelInfo(_primaryId);
            if (info != null) {
                TestTunnelJob.this._context.messageHistory().tunnelValid(info, time);
                updateProfiles(info, time);
            }
            
            info = _pool.getTunnelInfo(_secondaryId);
            if (info != null) {
                TestTunnelJob.this._context.messageHistory().tunnelValid(info, time);
                updateProfiles(info, time);
            }
            _context.statManager().addRateData("tunnel.testSuccessTime", time, time);
        }
        
        private void updateProfiles(TunnelInfo info, long time) {
            TunnelInfo cur = info;
            while (cur != null) {
                Hash peer = cur.getThisHop();
                if ( (peer != null) && (!_context.routerHash().equals(peer)) )
                    _context.profileManager().tunnelTestSucceeded(peer, time);
                cur = cur.getNextHopInfo();
            }
        }
        
        public void setMessage(I2NPMessage message) {
            _msg = (DeliveryStatusMessage)message;
        }
    }
    
    private class TestMessageSelector implements MessageSelector {
        private long _id;
        private long _tunnelId;
        private boolean _found;
        private long _expiration;
        public TestMessageSelector(long id, long tunnelId) {
            _id = id;
            _tunnelId = tunnelId;
            _found = false;
            _expiration = _context.clock().now() + TEST_TIMEOUT;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("the expiration while testing tunnel " + tunnelId 
                           + " waiting for nonce " + id + ": " + new Date(_expiration));
        }
        public boolean continueMatching() {
            if (!_found) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Continue matching while looking for nonce for tunnel " + _tunnelId);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Don't continue matching for tunnel " + _tunnelId + " / " + _id);
            }
            return !_found;
        }
        public long getExpiration() {
            if (_expiration < _context.clock().now()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("EXPIRED while looking for nonce " + _id + " for tunnel " + _tunnelId);
            }
            return _expiration;
        }
        public boolean isMatch(I2NPMessage message) {
            if ( (message != null) && (message instanceof DeliveryStatusMessage) ) {
                DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
                if (msg.getMessageId() == _id) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Found successful test of tunnel " + _tunnelId + " after " 
                                   + (_context.clock().now() - msg.getArrival().getTime()) 
                                   + "ms waiting for " + _id);
                    _found = true;
                    return true;
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Found a delivery status message, but it contains nonce " 
                                   + msg.getMessageId() + " and not " + _id);
                }
            } else {
                //_log.debug("Not a match while looking to test tunnel " + _tunnelId + " with nonce " + _id + " (" + message + ")");
            }
            return false;
        }
        public String toString() {
            StringBuffer buf = new StringBuffer(256);
            buf.append(super.toString());
            buf.append(": TestMessageSelector: tunnel ").append(_tunnelId);
            buf.append(" looking for ").append(_id).append(" expiring in ");
            buf.append(_expiration - _context.clock().now());
            buf.append("ms");
            return buf.toString();
        }
    }
}
