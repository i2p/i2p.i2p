package net.i2p.router.tunnelmanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.TunnelId;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.SourceRouteBlock;
import net.i2p.data.i2np.TunnelCreateMessage;
import net.i2p.data.i2np.TunnelCreateStatusMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelSettings;
import net.i2p.router.MessageHistory;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.message.BuildTestMessageJob;
import net.i2p.router.message.SendReplyMessageJob;
import net.i2p.util.Log;
import net.i2p.util.Clock;

import java.util.Date;

public class HandleTunnelCreateMessageJob extends JobImpl {
    private final static Log _log = new Log(HandleTunnelCreateMessageJob.class);
    private TunnelCreateMessage _message;
    private RouterIdentity _from;
    private Hash _fromHash;
    private SourceRouteBlock _replyBlock;
    
    private final static long TIMEOUT = 30*1000; // 30 secs to contact a peer that will be our next hop
    private final static int PRIORITY = 123;
    
    HandleTunnelCreateMessageJob(TunnelCreateMessage receivedMessage, RouterIdentity from, Hash fromHash, SourceRouteBlock replyBlock) {
	_message = receivedMessage;
	_from = from;
	_fromHash = fromHash;
	_replyBlock = replyBlock;
    }
    
    public void runJob() {
	if (_log.shouldLog(Log.DEBUG)) _log.debug("Handling tunnel create");
	TunnelInfo info = new TunnelInfo();
	info.setConfigurationKey(_message.getConfigurationKey());
	info.setEncryptionKey(_message.getTunnelKey());
	info.setNextHop(_message.getNextRouter());
	
	TunnelSettings settings = new TunnelSettings();
	settings.setBytesPerMinuteAverage(_message.getMaxAvgBytesPerMin());
	settings.setBytesPerMinutePeak(_message.getMaxPeakBytesPerMin());
	settings.setMessagesPerMinuteAverage(_message.getMaxAvgMessagesPerMin());
	settings.setMessagesPerMinutePeak(_message.getMaxPeakMessagesPerMin());
	settings.setExpiration(_message.getTunnelDurationSeconds()*1000+Clock.getInstance().now());
	settings.setIncludeDummy(_message.getIncludeDummyTraffic());
	settings.setReorder(_message.getReorderMessages());
	info.setSettings(settings);
	
	info.setSigningKey(_message.getVerificationPrivateKey());
	info.setThisHop(Router.getInstance().getRouterInfo().getIdentity().getHash());
	info.setTunnelId(_message.getTunnelId());
	info.setVerificationKey(_message.getVerificationPublicKey());
	
	info.getTunnelId().setType(TunnelId.TYPE_PARTICIPANT);
	
	if (_message.getNextRouter() == null) {
	    if (_log.shouldLog(Log.DEBUG)) _log.debug("We're the endpoint, don't test the \"next\" peer [duh]");
	    boolean ok = TunnelManagerFacade.getInstance().joinTunnel(info);
	    sendReply(ok);
	} else {
	    NetworkDatabaseFacade.getInstance().lookupRouterInfo(info.getNextHop(), new TestJob(info), new JoinJob(info, false), TIMEOUT);
	}
    }
    
    private class TestJob extends JobImpl {
	private TunnelInfo _target;
	public TestJob(TunnelInfo target) {
	    _target = target;
	}
	
	public String getName() { return "Run a test for peer reachability"; }
	public void runJob() {
	    RouterInfo info = NetworkDatabaseFacade.getInstance().lookupRouterInfoLocally(_target.getNextHop());
	    if (info == null) {
		if (_log.shouldLog(Log.ERROR)) 
		    _log.error("Error - unable to look up peer " + _target.toBase64() + ", even though we were queued up via onSuccess??");
		return;
	    } else {
		if (_log.shouldLog(Log.INFO)) 
		    _log.info("Lookup successful for tested peer " + _target.toBase64() + ", now continue with the test");
		JobQueue.getInstance().addJob(new BuildTestMessageJob(info, Router.getInstance().getRouterInfo().getIdentity().getHash(), new JoinJob(_target, true), new JoinJob(_target, false), TIMEOUT, PRIORITY));
	    }
	}
    }
    
    
    private void sendReply(boolean ok) {
	if (_log.shouldLog(Log.DEBUG)) 
	    _log.debug("Sending reply to a tunnel create of id " + _message.getTunnelId() + " with ok (" + ok + ") to router " + _message.getReplyBlock().getRouter().toBase64());

	MessageHistory.getInstance().receiveTunnelCreate(_message.getTunnelId(), _message.getNextRouter(), new Date(Clock.getInstance().now() + 1000*_message.getTunnelDurationSeconds()), ok, _message.getReplyBlock().getRouter());

	TunnelCreateStatusMessage msg = new TunnelCreateStatusMessage();
	msg.setFromHash(Router.getInstance().getRouterInfo().getIdentity().getHash());
	msg.setTunnelId(_message.getTunnelId());
	if (ok) {
	    msg.setStatus(TunnelCreateStatusMessage.STATUS_SUCCESS);
	} else {
	    // since we don't actually check anything, this is a catch all
	    msg.setStatus(TunnelCreateStatusMessage.STATUS_FAILED_OVERLOADED);
	}
	msg.setMessageExpiration(new Date(Clock.getInstance().now()+60*1000));
	SendReplyMessageJob job = new SendReplyMessageJob(_message.getReplyBlock(), msg, PRIORITY);
	JobQueue.getInstance().addJob(job);
    }
    
    public String getName() { return "Handle Tunnel Create Message"; }
    
    private class JoinJob extends JobImpl {
	private TunnelInfo _info;
	private boolean _isReachable;
	public JoinJob(TunnelInfo info, boolean isReachable) {
	    _info = info;
	    _isReachable = isReachable;
	}
	
	public void runJob() {
	    if (!_isReachable) {
		long before = Clock.getInstance().now();
		sendReply(false);
		long after = Clock.getInstance().now();
		if (_log.shouldLog(Log.DEBUG)) 
		    _log.debug("JoinJob .refuse took " + (after-before) + "ms to refuse " + _info);
	    } else {
		long before = Clock.getInstance().now();
		boolean ok = TunnelManagerFacade.getInstance().joinTunnel(_info);
		long afterJoin = Clock.getInstance().now();
		sendReply(ok);
		long after = Clock.getInstance().now();
		if (_log.shouldLog(Log.DEBUG)) 
		    _log.debug("JoinJob .joinTunnel took " + (afterJoin-before) + "ms and sendReply took " + (after-afterJoin) + "ms");
	    }
	}
	public String getName() { return "Process the tunnel join after testing the nextHop"; }
    }
    
    public void dropped() {
	MessageHistory.getInstance().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
    }
}
