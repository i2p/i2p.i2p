package net.i2p.router.tunnelmanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.MessageHistory;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Log;
import net.i2p.util.Clock;
import net.i2p.util.RandomSource;

class TestTunnelJob extends JobImpl {
    private final static Log _log = new Log(TestTunnelJob.class);
    private TunnelId _id;
    private TunnelPool _pool;
    private long _nonce;
    
    public TestTunnelJob(TunnelId id, TunnelPool pool) {
	super();
	_id = id;
	_pool = pool;
	_nonce = RandomSource.getInstance().nextInt(Integer.MAX_VALUE);
    }
    public String getName() { return "Test Tunnel"; }
    public void runJob() {
	if (_log.shouldLog(Log.INFO))
	    _log.info("Testing tunnel " + _id.getTunnelId());
	TunnelInfo info = _pool.getTunnelInfo(_id);
	if (info == null) {
	    _log.error("wtf, why are we testing a tunnel that we do not know about? [" + _id.getTunnelId() + "]", getAddedBy());
	    return;
	}
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
	if (Router.getInstance().getRouterInfo().getIdentity().getHash().equals(info.getThisHop()))
	    return true;
	else
	    return false;
    }

    private final static long TEST_TIMEOUT = 60*1000; // 60 seconds for a test to succeed
    private final static int TEST_PRIORITY = 100;
    
    /**
     * Send a message out the tunnel with instructions to send the message back
     * to ourselves and wait for it to arrive.
     */
    private void testOutbound(TunnelInfo info) {
	if (_log.shouldLog(Log.INFO))
	    _log.info("Testing outbound tunnel " + info);
	DeliveryStatusMessage msg = new DeliveryStatusMessage();
	msg.setArrival(new Date(Clock.getInstance().now()));
	msg.setMessageId(_nonce);
	Hash us = Router.getInstance().getRouterInfo().getIdentity().getHash();
	TunnelId inboundTunnelId = getReplyTunnel();
	if (inboundTunnelId == null) {
	    return;
	} 
	
	TestFailedJob failureJob = new TestFailedJob();
	MessageSelector selector = new TestMessageSelector(msg.getMessageId(), info.getTunnelId().getTunnelId());
	SendTunnelMessageJob testJob = new SendTunnelMessageJob(msg, info.getTunnelId(), us, inboundTunnelId, null, new TestSuccessfulJob(), failureJob, selector, TEST_TIMEOUT, TEST_PRIORITY);
	JobQueue.getInstance().addJob(testJob);
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
	List tunnelIds = TunnelManagerFacade.getInstance().selectInboundTunnelIds(crit);
	 
	for (int i = 0; i < tunnelIds.size(); i++) {
	    TunnelId id = (TunnelId)tunnelIds.get(i);
	    if (id.equals(_id)) {
		if (_log.shouldLog(Log.DEBUG))
		    _log.debug("Not testing a tunnel with itself [duh]");
	    } else {
		return id;
	    }
	}
	
	_log.error("Unable to test tunnel " + _id + ", since there are NO OTHER INBOUND TUNNELS to receive the ack through");
	return null;
    }
    
    /**
     * Send a message to the gateway and wait for it to arrive.
     * todo: send the message to the gateway via an outbound tunnel or garlic, NOT DIRECT.  
     */
    private void testInbound(TunnelInfo info) {
	if (_log.shouldLog(Log.INFO))
	    _log.info("Testing inbound tunnel " + info);
	DeliveryStatusMessage msg = new DeliveryStatusMessage();
	msg.setArrival(new Date(Clock.getInstance().now()));
	msg.setMessageId(_nonce);
	TestFailedJob failureJob = new TestFailedJob();
	MessageSelector selector = new TestMessageSelector(msg.getMessageId(), info.getTunnelId().getTunnelId());
	TunnelMessage tmsg = new TunnelMessage();
	try {
	    ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
	    msg.writeBytes(baos);
	    tmsg.setData(baos.toByteArray());
	    tmsg.setTunnelId(info.getTunnelId());
	    JobQueue.getInstance().addJob(new SendMessageDirectJob(tmsg, info.getThisHop(), new TestSuccessfulJob(), failureJob, selector, Clock.getInstance().now() + TEST_TIMEOUT, TEST_PRIORITY));
	
	    String bodyType = msg.getClass().getName();
	    MessageHistory.getInstance().wrap(bodyType, msg.getUniqueId(), TunnelMessage.class.getName(), tmsg.getUniqueId());
	} catch (IOException ioe) {
	    _log.error("Error writing out the tunnel message to send to the tunnel", ioe);
	    _pool.tunnelFailed(_id);
	} catch (DataFormatException dfe) {
	    _log.error("Error writing out the tunnel message to send to the tunnel", dfe);
	    _pool.tunnelFailed(_id);
	}
    }
    
    private class TestFailedJob extends JobImpl {
	public TestFailedJob() {
	    super();
	}
	
	public String getName() { return "Tunnel Test Failed"; }
	public void runJob() {
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Test of tunnel " + _id.getTunnelId() + " failed while waiting for nonce " + _nonce, getAddedBy());
	    _pool.tunnelFailed(_id);
	}
    }
    
    private class TestSuccessfulJob extends JobImpl implements ReplyJob {
	private DeliveryStatusMessage _msg;
	public TestSuccessfulJob() {
	    super();
	    _msg = null;
	}
	
	public String getName() { return "Tunnel Test Successful"; }
	public void runJob() {
	    long time = (Clock.getInstance().now() - _msg.getArrival().getTime());
	    if (_log.shouldLog(Log.INFO))
		_log.info("Test of tunnel " + _id+ " successfull after " + time + "ms waiting for " + _nonce);
	    TunnelInfo info = _pool.getTunnelInfo(_id);
	    if (info != null)
		MessageHistory.getInstance().tunnelValid(info, time);
	}
	
	public void setMessage(I2NPMessage message) {
	    _msg = (DeliveryStatusMessage)message;
	}
    }
    
    private static class TestMessageSelector implements MessageSelector {
	private long _id;
	private long _tunnelId;
	private boolean _found;
	private long _expiration;
	public TestMessageSelector(long id, long tunnelId) {
	    _id = id;
	    _tunnelId = tunnelId;
	    _found = false;
	    _expiration = Clock.getInstance().now() + TEST_TIMEOUT;
	    if (_log.shouldLog(Log.DEBUG))
		_log.debug("the expiration while testing tunnel " + tunnelId + " waiting for nonce " + id + ": " + new Date(_expiration));
	}
	public boolean continueMatching() { 
	    if (!_found) {
		if (_log.shouldLog(Log.DEBUG))
		    _log.debug("Continue matching while looking for nonce for tunnel " + _tunnelId);
	    } else {
		if (_log.shouldLog(Log.INFO))
		    _log.info("Don't continue matching for tunnel " + _tunnelId + " / " + _id);
	    }
	    return !_found; 
	}
	public long getExpiration() { 
	    if (_expiration < Clock.getInstance().now()) {
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
			_log.debug("Found successful test of tunnel " + _tunnelId + " after " + (Clock.getInstance().now() - msg.getArrival().getTime()) + "ms waiting for " + _id);
		    _found = true;
		    return true;
		} else {
		    if (_log.shouldLog(Log.DEBUG))
			_log.debug("Found a delivery status message, but it contains nonce " + msg.getMessageId() + " and not " + _id);
		}
	    } else {
		//_log.debug("Not a match while looking to test tunnel " + _tunnelId + " with nonce " + _id + " (" + message + ")");
	    }
	    return false;
	}
	public String toString() {
	    StringBuffer buf = new StringBuffer(256);
	    buf.append(super.toString());
	    buf.append(": TestMessageSelector: tunnel ").append(_tunnelId).append(" looking for ").append(_id).append(" expiring on ");
	    buf.append(new Date(_expiration));
	    return buf.toString();
	}
    }
}
