package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.Job;
import net.i2p.router.ReplyJob;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.router.MessageSelector;

import net.i2p.data.RouterInfo;
import net.i2p.data.Certificate;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.DeliveryStatusMessage;

import net.i2p.crypto.SessionKeyManager;

import net.i2p.util.Log;
import net.i2p.util.Clock;
import net.i2p.util.RandomSource;

import java.util.Date;
import java.util.Set;
import java.util.HashSet;

/**
 * Build a test message that will be sent to the target to make sure they're alive.
 * Once that is verified, onSendJob is enqueued.  If their reachability isn't 
 * known (or they're unreachable) within timeoutMs, onSendFailedJob is enqueued.
 * The test message is sent at the specified priority.
 *
 */
public class BuildTestMessageJob extends JobImpl {
    private final static Log _log = new Log(BuildTestMessageJob.class);
    private RouterInfo _target;
    private Hash _replyTo;
    private Job _onSend;
    private Job _onSendFailed;
    private long _timeoutMs;
    private int _priority;
    private long _testMessageKey;

    /**
     *
     * @param target router being tested
     * @param onSendJob after the ping is successful
     * @param onSendFailedJob after the ping fails or times out
     * @param timeoutMs how long to wait before timing out
     * @param priority how high priority to send this test
     */
    public BuildTestMessageJob(RouterInfo target, Hash replyTo, Job onSendJob, Job onSendFailedJob, long timeoutMs, int priority) {
	super();
	_target = target;
	_replyTo = replyTo;
	_onSend = onSendJob;
	_onSendFailed = onSendFailedJob;
	_timeoutMs = timeoutMs;
	_priority = priority;
	_testMessageKey = -1;
    }
    
    public String getName() { return "Build Test Message"; }
    
    public void runJob() {
	// This is a test message - build a garlic with a DeliveryStatusMessage that
	// first goes to the peer then back to us.
	if (_log.shouldLog(Log.DEBUG)) 
	    _log.debug("Building garlic message to test " + _target.getIdentity().getHash().toBase64());
	GarlicConfig config = buildGarlicCloveConfig();
	// TODO: make the last params on this specify the correct sessionKey and tags used
	ReplyJob replyJob = new JobReplyJob(_onSend, config.getRecipient().getIdentity().getPublicKey(), config.getId(), null, new HashSet());
	MessageSelector sel = buildMessageSelector();
	SendGarlicJob job = new SendGarlicJob(config, null, _onSendFailed, replyJob, _onSendFailed, _timeoutMs, _priority, sel);
	JobQueue.getInstance().addJob(job);
    }
    
    private MessageSelector buildMessageSelector() {
	return new TestMessageSelector(_testMessageKey, _timeoutMs + Clock.getInstance().now());
    }
    
    private GarlicConfig buildGarlicCloveConfig() {
	_testMessageKey = RandomSource.getInstance().nextInt(Integer.MAX_VALUE);
	if (_log.shouldLog(Log.INFO)) 
	    _log.info("Test message key: " + _testMessageKey);
	GarlicConfig config = new GarlicConfig();
	
	PayloadGarlicConfig ackClove = buildAckClove();
	config.addClove(ackClove);
	
	DeliveryInstructions instructions = new DeliveryInstructions();
	instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_ROUTER);
	instructions.setDelayRequested(false);
	instructions.setDelaySeconds(0);
	instructions.setEncrypted(false);
	instructions.setEncryptionKey(null);
	instructions.setRouter(_target.getIdentity().getHash());
	instructions.setTunnelId(null);
	
	config.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
	config.setDeliveryInstructions(instructions);
	config.setId(RandomSource.getInstance().nextInt(Integer.MAX_VALUE));
	config.setExpiration(_timeoutMs+Clock.getInstance().now()+2*Router.CLOCK_FUDGE_FACTOR);
	config.setRecipient(_target);
	config.setRequestAck(false);
	
	return config;
    }
    
    /**
     * Build a clove that sends a DeliveryStatusMessage to us
     */
    private PayloadGarlicConfig buildAckClove() {
	PayloadGarlicConfig ackClove = new PayloadGarlicConfig();
	
	DeliveryInstructions ackInstructions = new DeliveryInstructions();
	ackInstructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_ROUTER);
	ackInstructions.setRouter(_replyTo); // yikes!
	ackInstructions.setDelayRequested(false);
	ackInstructions.setDelaySeconds(0);
	ackInstructions.setEncrypted(false);

	DeliveryStatusMessage msg = new DeliveryStatusMessage();
	msg.setArrival(new Date(Clock.getInstance().now()));
	msg.setMessageId(_testMessageKey);
	if (_log.shouldLog(Log.DEBUG)) 
	    _log.debug("Delivery status message key: " + _testMessageKey + " arrival: " + msg.getArrival());
	
	ackClove.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
	ackClove.setDeliveryInstructions(ackInstructions);
	ackClove.setExpiration(_timeoutMs+Clock.getInstance().now());
	ackClove.setId(RandomSource.getInstance().nextInt(Integer.MAX_VALUE));
	ackClove.setPayload(msg);
	ackClove.setRecipient(_target);
	ackClove.setRequestAck(false);
	
	return ackClove;
    }
    
    /**
     * Search inbound messages for delivery status messages with our key
     */
    private final static class TestMessageSelector implements MessageSelector {
	private long _testMessageKey;
	private long _timeout;
	public TestMessageSelector(long key, long timeout) {
	    _testMessageKey = key;
	    _timeout = timeout;
	}
	public boolean continueMatching() { return false; }
	public long getExpiration() { return _timeout; }
	public boolean isMatch(I2NPMessage inMsg) {
	    if (inMsg.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
		return ((DeliveryStatusMessage)inMsg).getMessageId() == _testMessageKey;
	    } else {
		return false;
	    }
	}
    }
    
    /**
     * On reply, fire off the specified job
     *
     */
    private final static class JobReplyJob extends JobImpl implements ReplyJob {
	private Job _job;
	private PublicKey _target;
	private long _msgId;
	private Set _sessionTagsDelivered;
	private SessionKey _keyDelivered;
	public JobReplyJob(Job job, PublicKey target, long msgId, SessionKey keyUsed, Set tagsDelivered) { 
	    _job = job; 
	    _target = target;
	    _msgId = msgId;
	    _keyDelivered = keyUsed;
	    _sessionTagsDelivered = tagsDelivered;
	}
	public String getName() { return "Reply To Test Message Received"; }
	public void runJob() { 
	    if ( (_keyDelivered != null) && (_sessionTagsDelivered != null) && (_sessionTagsDelivered.size() > 0) )
		SessionKeyManager.getInstance().tagsDelivered(_target, _keyDelivered, _sessionTagsDelivered);
	    
	    JobQueue.getInstance().addJob(_job);
	}
	
	public void setMessage(I2NPMessage message) { 
	    // ignored, this is just a ping
	}
	
    }
}

