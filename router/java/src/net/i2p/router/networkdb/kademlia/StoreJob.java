package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Certificate;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.RoutingKeyGenerator;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.MessageSelector;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.ProfileManager;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.router.message.GarlicConfig;
import net.i2p.router.message.PayloadGarlicConfig;
import net.i2p.router.message.SendGarlicJob;
import net.i2p.router.message.SendTunnelMessageJob;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

class StoreJob extends JobImpl {
    private final Log _log = new Log(StoreJob.class);
    private KademliaNetworkDatabaseFacade _facade;
    private StoreState _state;
    private Job _onSuccess;
    private Job _onFailure;
    private long _timeoutMs;
    private long _expiration;

    private final static int PARALLELIZATION = 1; // how many sent at a time
    private final static int REDUNDANCY = 2; // we want the data sent to 2 peers
    /**
     * additionally send to 1 outlier(s), in case all of the routers chosen in our
     * REDUNDANCY set are attacking us by accepting DbStore messages but dropping
     * the data.  
     *
     * TODO: um, honor this.  make sure we send to this many peers that aren't 
     * closest to the key.
     *
     */
    private final static int EXPLORATORY_REDUNDANCY = 1; 
    private final static int STORE_PRIORITY = 100;

    /**
     * Create a new search for the routingKey specified
     * 
     */
    public StoreJob(KademliaNetworkDatabaseFacade facade, Hash key, DataStructure data, Job onSuccess, Job onFailure, long timeoutMs) {
	_facade = facade;
	_state = new StoreState(key, data);
	_onSuccess = onSuccess;
	_onFailure = onFailure;
	_timeoutMs = timeoutMs;
	_expiration = Clock.getInstance().now() + timeoutMs;
    }

    public String getName() { return "Kademlia NetDb Store";}
    public void runJob() {
	sendNext();
    }

    protected boolean isExpired() { 
	return Clock.getInstance().now() >= _expiration;
    }

    /**
     * send the key to the next batch of peers
     */
    protected void sendNext() {
	if (_state.completed()) {
	    _log.info("Already completed");
	    return;
	}
	if (isExpired()) {
	    _state.complete(true);
	    fail();
	} else {
	    _log.info("Sending: " + _state);
	    continueSending();
	}
    }

    /**
     * Send a series of searches to the next available peers as selected by
     * the routing table, but making sure no more than PARALLELIZATION are outstanding
     * at any time
     *
     */
    protected void continueSending() { 
	if (_state.completed()) return;
	int toCheck = PARALLELIZATION - _state.getPending().size();
	if (toCheck <= 0) {
	    // too many already pending
	    return;
	} 
	if (toCheck > PARALLELIZATION)
	    toCheck = PARALLELIZATION;
	
	List closestHashes = getClosestRouters(_state.getTarget(), toCheck, _state.getAttempted());
	if ( (closestHashes == null) || (closestHashes.size() <= 0) ) {
	    if (_state.getPending().size() <= 0) {
		// we tried to find some peers, but there weren't any and no one else is going to answer
		fail();
	    } else {
		// no more to try, but we might get data or close peers from some outstanding requests
		return;
	    }
	} else {
	    _state.addPending(closestHashes);
	    _log.info("Continue sending key " + _state.getTarget() + " to " + closestHashes);
	    for (Iterator iter = closestHashes.iterator(); iter.hasNext(); ) {
		Hash peer = (Hash)iter.next();
		DataStructure ds = _facade.getDataStore().get(peer);
		if ( (ds == null) || !(ds instanceof RouterInfo) ) {
		    _log.warn("Error selecting closest hash that wasnt a router! " + peer + " : " + ds);
		} else {
		    sendStore((RouterInfo)ds);
		}
	    }
	}
    }

    /**
     * Set of Hash structures for routers we want to send the data to next.  This is the 
     * 'interesting' part of the algorithm.  DBStore isn't usually as time sensitive as 
     * it is reliability sensitive, so lets delegate it off to the PeerSelector via 
     * selectNearestExplicit, which is currently O(n*log(n))
     *
     * @return ordered list of Hash objects
     */
    protected List getClosestRouters(Hash key, int numClosest, Set alreadyChecked) {
	Hash rkey = RoutingKeyGenerator.getInstance().getRoutingKey(key);
	_log.debug("Current routing key for " + key + ": " + rkey);
	return PeerSelector.getInstance().selectNearestExplicit(rkey, numClosest, alreadyChecked, _facade.getKBuckets());
    }

    /**
     * Send a store to the given peer through a garlic route, including a reply 
     * DeliveryStatusMessage so we know it got there
     *
     */
    protected void sendStore(RouterInfo router) {
	DatabaseStoreMessage msg = new DatabaseStoreMessage();
	msg.setKey(_state.getTarget());
	if (_state.getData() instanceof RouterInfo) 
	    msg.setRouterInfo((RouterInfo)_state.getData());
	else if (_state.getData() instanceof LeaseSet) 
	    msg.setLeaseSet((LeaseSet)_state.getData());
	else
	    throw new IllegalArgumentException("Storing an unknown data type! " + _state.getData());
	msg.setMessageExpiration(new Date(Clock.getInstance().now() + _timeoutMs));

	if (router.getIdentity().equals(Router.getInstance().getRouterInfo().getIdentity())) {
	    // don't send it to ourselves
	    _log.error("Dont send store to ourselves - why did we try?");
	    return;
	} else {
	    _log.debug("Send store to " + router.getIdentity().getHash().toBase64());
	}

	sendStore(msg, router, _expiration);
    }
    
    protected void sendStore(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
	//sendStoreAsGarlic(msg, peer, expiration);
	sendStoreThroughTunnel(msg, peer, expiration);
    }

    protected void sendStoreThroughTunnel(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
	FailedJob fail = new FailedJob(peer);
	Job sent = new OptimisticSendSuccess(peer);
	TunnelInfo info = null;
	TunnelId outboundTunnelId = selectOutboundTunnel();
	if (outboundTunnelId != null)
	    info = TunnelManagerFacade.getInstance().getTunnelInfo(outboundTunnelId);
	if (info == null) {
	    _log.error("selectOutboundTunnel didn't find a valid tunnel!  outboundTunnelId = " + outboundTunnelId + " is not known by the tunnel manager");
	    return;
	}
	_log.info("Store for " + _state.getTarget() + " expiring on " + new Date(_expiration) + " is going to " + peer.getIdentity().getHash() + " via outbound tunnel: " + info);
	// send it out our outboundTunnelId with instructions for our endpoint to forward it
	// to the router specified (though no particular tunnelId on the target)
	JobQueue.getInstance().addJob(new SendTunnelMessageJob(msg, outboundTunnelId, peer.getIdentity().getHash(), null, sent, null, fail, null, _expiration-Clock.getInstance().now(), STORE_PRIORITY));
    }
    
    private TunnelId selectOutboundTunnel() {
	TunnelSelectionCriteria criteria = new TunnelSelectionCriteria();
	criteria.setAnonymityPriority(80);
	criteria.setLatencyPriority(50);
	criteria.setReliabilityPriority(20);
	criteria.setMaximumTunnelsRequired(1);
	criteria.setMinimumTunnelsRequired(1);
	List tunnelIds = TunnelManagerFacade.getInstance().selectOutboundTunnelIds(criteria);
	if (tunnelIds.size() <= 0) {
	    _log.error("No outbound tunnels?!");
	    return null;
	} else {
	    return (TunnelId)tunnelIds.get(0);
	}
    }

    
    /**
     * Send the store to the peer by way of a garlic and route an ack back to us
     *
     */
    protected void sendStoreAsGarlic(DatabaseStoreMessage msg, RouterInfo peer, long expiration) {
	long waitingForId = RandomSource.getInstance().nextInt(Integer.MAX_VALUE);
	GarlicConfig cfg = buildGarlicConfig(msg, peer, waitingForId, expiration);
	FailedJob failedJob = new FailedJob(peer);
	long timeoutMs = expiration - Clock.getInstance().now();
	StoreMessageSelector selector = new StoreMessageSelector(peer, waitingForId);
	SessionKey sentKey = new SessionKey();
	Set sentTags = new HashSet(32);
	PublicKey rcptKey = cfg.getRecipientPublicKey();
	if (rcptKey == null) {
	    if (cfg.getRecipient() == null) {
		throw new IllegalArgumentException("Null recipient specified");
	    } else if (cfg.getRecipient().getIdentity() == null) {
		throw new IllegalArgumentException("Null recipient.identity specified");
	    } else if (cfg.getRecipient().getIdentity().getPublicKey() == null) {
		throw new IllegalArgumentException("Null recipient.identity.publicKey specified");
	    } else 
		rcptKey = cfg.getRecipient().getIdentity().getPublicKey();
	}
	
	JobQueue.getInstance().addJob(new SendGarlicJob(cfg, null, failedJob, new UpdateReplyFoundJob(peer, sentKey, sentTags, rcptKey), failedJob, timeoutMs, STORE_PRIORITY, selector, sentKey, sentTags));
    }

    /**
     * Build a garlic containing the data store and an ack to be unwrapped at the
     * target, with the data store sent locally and the ack sent back to us through
     * a random tunnel as a DeliveryStatusMessage containing the ackId
     *
     */
    protected GarlicConfig buildGarlicConfig(I2NPMessage msg, RouterInfo target, long ackId, long expiration) {
	GarlicConfig config = new GarlicConfig();

	PayloadGarlicConfig dataClove = buildDataClove(msg, target, expiration);
	config.addClove(dataClove);
	PayloadGarlicConfig ackClove = buildAckClove(ackId, expiration);
	config.addClove(ackClove);

	DeliveryInstructions instructions = new DeliveryInstructions();
	instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_ROUTER);
	instructions.setDelayRequested(false);
	instructions.setDelaySeconds(0);
	instructions.setEncrypted(false);
	instructions.setEncryptionKey(null);
	instructions.setRouter(target.getIdentity().getHash());
	instructions.setTunnelId(null);

	config.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
	config.setDeliveryInstructions(instructions);
	config.setId(RandomSource.getInstance().nextInt(Integer.MAX_VALUE));
	config.setExpiration(_expiration);
	config.setRecipientPublicKey(target.getIdentity().getPublicKey());
	config.setRecipient(target);
	config.setRequestAck(false);

	return config;
    }

    /**
     * Build a clove that sends a DeliveryStatusMessage to us after tunneling it
     * through a random inbound tunnel
     *
     */
    protected PayloadGarlicConfig buildAckClove(long ackId, long expiration) {
	DeliveryStatusMessage ackMsg = new DeliveryStatusMessage();
	ackMsg.setArrival(new Date(Clock.getInstance().now()));
	ackMsg.setMessageId(ackId);
	ackMsg.setMessageExpiration(new Date(expiration));
	ackMsg.setUniqueId(RandomSource.getInstance().nextInt(Integer.MAX_VALUE));

	PayloadGarlicConfig ackClove = new PayloadGarlicConfig();

	TunnelSelectionCriteria criteria = new TunnelSelectionCriteria();
	criteria.setAnonymityPriority(80);
	criteria.setLatencyPriority(20);
	criteria.setReliabilityPriority(50);
	criteria.setMaximumTunnelsRequired(1);
	criteria.setMinimumTunnelsRequired(1);
	List tunnelIds = TunnelManagerFacade.getInstance().selectInboundTunnelIds(criteria);
	if (tunnelIds.size() <= 0) {
	    _log.error("No inbound tunnels exist for a db store ack to come through!");
	    return null;
	}
	TunnelId replyToTunnelId = (TunnelId)tunnelIds.get(0); // tunnel id on that gateway
	TunnelInfo info = TunnelManagerFacade.getInstance().getTunnelInfo(replyToTunnelId);
	RouterInfo replyPeer = NetworkDatabaseFacade.getInstance().lookupRouterInfoLocally(info.getThisHop()); // inbound tunnel gateway
	if (replyPeer == null) {
	    _log.error("We don't know how to reach the gateway of our own inbound tunnel?! " + info);
	    return null;
	}
	Hash replyToTunnelRouter = replyPeer.getIdentity().getHash(); 

	DeliveryInstructions ackInstructions = new DeliveryInstructions();
	ackInstructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_TUNNEL);
	ackInstructions.setRouter(replyToTunnelRouter);
	ackInstructions.setTunnelId(replyToTunnelId);
	ackInstructions.setDelayRequested(false);
	ackInstructions.setDelaySeconds(0);
	ackInstructions.setEncrypted(false);

	ackClove.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
	ackClove.setDeliveryInstructions(ackInstructions);
	ackClove.setExpiration(_expiration);
	ackClove.setId(RandomSource.getInstance().nextInt(Integer.MAX_VALUE));
	ackClove.setPayload(ackMsg);
	ackClove.setRecipient(replyPeer);
	ackClove.setRequestAck(false);

	return ackClove;
    }

    /**
     * Build a clove that sends the data to the target (which is local)
     */
    protected PayloadGarlicConfig buildDataClove(I2NPMessage data, RouterInfo target, long expiration) {
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
	clove.setId(RandomSource.getInstance().nextInt(Integer.MAX_VALUE));
	clove.setPayload(data);
	clove.setRecipientPublicKey(null);
	clove.setRequestAck(false);

	return clove;
    }

    
    /**
     * Called after a match to a db store is found (match against a deliveryStatusMessage)
     *
     */
    protected class UpdateReplyFoundJob extends JobImpl implements ReplyJob {
	private I2NPMessage _message;
	private Hash _peer;
	private SessionKey _sentKey;
	private Set _sentTags;
	private PublicKey _toKey;

	public UpdateReplyFoundJob(RouterInfo peer, SessionKey sentKey, Set sentTags, PublicKey toKey) {
	    super();
	    _peer = peer.getIdentity().getHash();
	    _sentKey = sentKey;
	    _sentTags = sentTags;
	    _toKey = toKey;
	}

	public String getName() { return "Update Reply Found for Kademlia Store"; }
	public void runJob() {
	    _log.info("Reply from " + _peer + " with message " + _message);

	    if (_message.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
		long delay = _state.confirmed(_peer);
		ProfileManager.getInstance().dbStoreSent(_peer, delay);

		if ( (_sentKey != null) && (_sentKey.getData() != null) && (_sentTags != null) && (_sentTags.size() > 0) && (_toKey != null) ) {
		    SessionKeyManager.getInstance().tagsDelivered(_toKey, _sentKey, _sentTags);
		    _log.info("Delivered tags successfully to " + _peer + "!  # tags: " + _sentTags.size());
		}
		
		if (_state.getSuccessful().size() >= REDUNDANCY) {
		    succeed();
		} else {
		    sendNext();
		}
	    } else {
		_log.error("Selector matched to an UpdateReplyFoundJob with a message that isnt a DeliveryStatusMessage!  " + _message);
	    }
	}

	public void setMessage(I2NPMessage message) { _message = message; }    
    }
    
    /**
     * Called after sending a dbStore to a peer successfully without waiting for confirm and 
     * optimistically mark the store as successful
     *
     */
    protected class OptimisticSendSuccess extends JobImpl {
	private Hash _peer;

	public OptimisticSendSuccess(RouterInfo peer) {
	    super();
	    _peer = peer.getIdentity().getHash();
	}

	public String getName() { return "Optimistic Kademlia Store Send Success"; }
	public void runJob() {
	    _log.info("Optimistically marking store of " + _state.getTarget() + " to " + _peer + " successful");
	    //long howLong = _state.confirmed(_peer);
	    //ProfileManager.getInstance().dbStoreSent(_peer, howLong);

	    if (_state.getSuccessful().size() >= REDUNDANCY) {
		succeed();
	    } else {
		sendNext();
	    }
	}
    }

    /**
     * Called when a particular peer failed to respond before the timeout was 
     * reached, or if the peer could not be contacted at all.
     *
     */
    protected class FailedJob extends JobImpl {
	private Hash _peer;
	public FailedJob(RouterInfo peer) {
	    super();
	    _peer = peer.getIdentity().getHash();
	}
	public void runJob() {
	    _state.replyTimeout(_peer);
	    ProfileManager.getInstance().dbStoreFailed(_peer);
	    sendNext();
	}
	public String getName() { return "Kademlia Store Failed"; }
    }

    /**
     * Check to see the message is a reply from the peer regarding the current 
     * search
     *
     */
    protected class StoreMessageSelector implements MessageSelector {
	private Hash _peer;
	private long _waitingForId;
	private boolean _found;
	public StoreMessageSelector(RouterInfo peer, long waitingForId) {
	    _peer = peer.getIdentity().getHash();
	    _found = false;
	    _waitingForId = waitingForId;
	}

	public boolean continueMatching() { return !_found; }
	public long getExpiration() { return _expiration; }
	public boolean isMatch(I2NPMessage message) {
	    _log.debug("isMatch("+message.getClass().getName() + ") [want deliveryStatusMessage from " + _peer + " wrt " + _state.getTarget() + "]");
	    if (message instanceof DeliveryStatusMessage) {
		DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
		if (msg.getMessageId() == _waitingForId) {
		    _log.debug("Found match for the key we're waiting for: " + _waitingForId);
		    _found = true;
		    return true;
		} else {
		    _log.debug("DeliveryStatusMessage of a key we're not looking for");
		    return false;
		}
	    } else {
		_log.debug("Not a DeliveryStatusMessage");
		return false;
	    }
	}
    }

    /**
     * Send was totally successful
     */
    protected void succeed() {
	_log.info("Succeeded sending key " + _state.getTarget());
	_log.debug("State of successful send: " + _state);
	if (_onSuccess != null)
	    JobQueue.getInstance().addJob(_onSuccess);
	_facade.noteKeySent(_state.getTarget());
    }

    /**
     * Send totally failed
     */
    protected void fail() {
	_log.info("Failed sending key " + _state.getTarget());
	_log.debug("State of failed send: " + _state, new Exception("Who failed me?"));
	if (_onFailure != null)
	    JobQueue.getInstance().addJob(_onFailure);
    }

    protected static class StoreState {
	private Hash _key;
	private DataStructure _data;
	private HashSet _pendingPeers;
	private HashMap _pendingPeerTimes;
	private HashSet _successfulPeers;
	private HashSet _successfulExploratoryPeers;
	private HashSet _failedPeers;
	private HashSet _attemptedPeers;
	private volatile long _completed;
	private volatile long _started;

	public StoreState(Hash key, DataStructure data) {
	    _key = key;
	    _data = data;
	    _pendingPeers = new HashSet(16);
	    _pendingPeerTimes = new HashMap(16);
	    _attemptedPeers = new HashSet(16);
	    _failedPeers = new HashSet(16);
	    _successfulPeers = new HashSet(16);
	    _successfulExploratoryPeers = new HashSet(16);
	    _completed = -1;
	    _started = Clock.getInstance().now();
	}

	public Hash getTarget() { return _key; }
	public DataStructure getData() { return _data; }
	public Set getPending() { 
	    synchronized (_pendingPeers) {
		return (Set)_pendingPeers.clone(); 
	    }
	}
	public Set getAttempted() { 
	    synchronized (_attemptedPeers) {
		return (Set)_attemptedPeers.clone(); 
	    }
	}
	public Set getSuccessful() { 
	    synchronized (_successfulPeers) {
		return (Set)_successfulPeers.clone(); 
	    }
	}
	public Set getSuccessfulExploratory() { 
	    synchronized (_successfulExploratoryPeers) {
		return (Set)_successfulExploratoryPeers.clone(); 
	    }
	}
	public Set getFailed() { 
	    synchronized (_failedPeers) {
		return (Set)_failedPeers.clone(); 
	    }
	}
	public boolean completed() { return _completed != -1; }
	public void complete(boolean completed) { 
	    if (completed)
		_completed = Clock.getInstance().now();
	}

	public long getWhenStarted() { return _started; }
	public long getWhenCompleted() { return _completed; }

	public void addPending(Collection pending) {
	    synchronized (_pendingPeers) {
		_pendingPeers.addAll(pending);
		for (Iterator iter = pending.iterator(); iter.hasNext(); ) 
		    _pendingPeerTimes.put(iter.next(), new Long(Clock.getInstance().now()));
	    }
	    synchronized (_attemptedPeers) {
		_attemptedPeers.addAll(pending);
	    }
	}

	public long confirmed(Hash peer) {
	    long rv = -1;
	    synchronized (_pendingPeers) {
		_pendingPeers.remove(peer);
		Long when = (Long)_pendingPeerTimes.remove(peer);
		if (when != null)
		    rv = Clock.getInstance().now() - when.longValue();
	    }
	    synchronized (_successfulPeers) {
		_successfulPeers.add(peer);
	    }
	    return rv;
	}
	
	public long confirmedExploratory(Hash peer) {
	    long rv = -1;
	    synchronized (_pendingPeers) {
		_pendingPeers.remove(peer);
		Long when = (Long)_pendingPeerTimes.remove(peer);
		if (when != null)
		    rv = Clock.getInstance().now() - when.longValue();
	    }
	    synchronized (_successfulExploratoryPeers) {
		_successfulExploratoryPeers.add(peer);
	    }
	    return rv;
	}

	public void replyTimeout(Hash peer) {
	    synchronized (_pendingPeers) {
		_pendingPeers.remove(peer);
	    }
	    synchronized (_failedPeers) {
		_failedPeers.add(peer);
	    }
	}

	public String toString() { 
	    StringBuffer buf = new StringBuffer(256);
	    buf.append("Storing ").append(_key);
	    buf.append(" ");
	    if (_completed <= 0)
		buf.append(" completed? false ");
	    else
		buf.append(" completed on ").append(new Date(_completed));
	    buf.append(" Attempted: ");
	    synchronized (_attemptedPeers) {
		for (Iterator iter = _attemptedPeers.iterator(); iter.hasNext(); ) {
		    Hash peer = (Hash)iter.next();
		    buf.append(peer.toBase64()).append(" ");
		}
	    }
	    buf.append(" Pending: ");
	    synchronized (_pendingPeers) {
		for (Iterator iter = _pendingPeers.iterator(); iter.hasNext(); ) {
		    Hash peer = (Hash)iter.next();
		    buf.append(peer.toBase64()).append(" ");
		}
	    }
	    buf.append(" Failed: ");
	    synchronized (_failedPeers) { 
		for (Iterator iter = _failedPeers.iterator(); iter.hasNext(); ) {
		    Hash peer = (Hash)iter.next();
		    buf.append(peer.toBase64()).append(" ");
		}
	    }
	    buf.append(" Successful: ");
	    synchronized (_successfulPeers) {
		for (Iterator iter = _successfulPeers.iterator(); iter.hasNext(); ) {
		    Hash peer = (Hash)iter.next();
		    buf.append(peer.toBase64()).append(" ");
		}
	    }
	    buf.append(" Successful Exploratory: ");
	    synchronized (_successfulExploratoryPeers) {
		for (Iterator iter = _successfulExploratoryPeers.iterator(); iter.hasNext(); ) {
		    Hash peer = (Hash)iter.next();
		    buf.append(peer.toBase64()).append(" ");
		}
	    }
	    return buf.toString();
	}
    }
}

