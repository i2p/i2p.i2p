package net.i2p.router.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.ClientMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.util.Log;

/**
 * Send a client message, taking into consideration the fact that there may be
 * multiple inbound tunnels that the target provides.  This job sends it to one
 * of them and if it doesnt get a confirmation within 15 seconds (SEND_TIMEOUT_MS),
 * it tries the next, continuing on until a confirmation is received, the full
 * timeout has been reached (60 seconds, or the ms defined in the client's or
 * router's "clientMessageTimeout" option).
 *
 * After sending through all of the leases without success, if there's still
 * time left it fails the leaseSet itself, does a new search for that leaseSet,
 * and continues sending down any newly found leases.
 *
 */
public class OutboundClientMessageJob extends JobImpl {
    private Log _log;
    private OutboundClientMessageStatus _status;
    private NextStepJob _nextStep;
    private LookupLeaseSetFailedJob _lookupLeaseSetFailed;
    private long _overallExpiration;
    private boolean _shouldBundle;
    
    /**
     * final timeout (in milliseconds) that the outbound message will fail in.
     * This can be overridden in the router.config or the client's session config
     * (the client's session config takes precedence)
     */
    public final static String OVERALL_TIMEOUT_MS_PARAM = "clientMessageTimeout";
    private final static long OVERALL_TIMEOUT_MS_DEFAULT = 60*1000;
    
    /** how long for each send do we allow before going on to the next? */
    private final static long SEND_TIMEOUT_MS = 10*1000;
    /** priority of messages, that might get honored some day... */
    private final static int SEND_PRIORITY = 500;
    
    /** dont search for the lease more than 6 times */
    private final static int MAX_LEASE_LOOKUPS = 6;
    
    /**
     * If the client's config specifies shouldBundleReplyInfo=true, messages sent from
     * that client to any peers will probabalistically include the sending destination's
     * current LeaseSet (allowing the recipient to reply without having to do a full
     * netDb lookup).  This should improve performance during the initial negotiations,
     * but is not necessary for communication that isn't bidirectional.
     *
     */
    public static final String BUNDLE_REPLY_LEASESET = "shouldBundleReplyInfo";
    /**
     * Allow the override of the frequency of bundling the reply info in with a message.
     * The client app can specify bundleReplyInfoProbability=80 (for instance) and that
     * will cause the router to include the sender's leaseSet with 80% of the messages
     * sent to the peer.
     *
     */
    public static final String BUNDLE_PROBABILITY = "bundleReplyInfoProbability";
    /** 
     * How often do messages include the reply leaseSet (out of every 100 tries).  
     * Including it each time is probably overkill, but who knows.  
     */
    private static final int BUNDLE_PROBABILITY_DEFAULT = 100;
    
    /**
     * Send the sucker
     */
    public OutboundClientMessageJob(RouterContext ctx, ClientMessage msg) {
        super(ctx);
        _log = ctx.logManager().getLog(OutboundClientMessageJob.class);
        
        ctx.statManager().createFrequencyStat("client.sendMessageFailFrequency", "How often does a client fail to send a message?", "Client Messages", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.sendMessageSize", "How large are messages sent by the client?", "Client Messages", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.sendAttemptAverage", "How many different tunnels do we have to try when sending a client message?", "Client Messages", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.sendAckTime", "How long does it take to get an ACK back from a message?", "Client Messages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.sendsPerFailure", "How many send attempts do we make when they all fail?", "Client Messages", new long[] { 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.timeoutCongestionTunnel", "How lagged our tunnels are when a send times out?", "Client Messages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.timeoutCongestionMessage", "How fast we process messages locally when a send times out?", "Client Messages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.timeoutCongestionInbound", "How much faster we are receiving data than our average bps when a send times out?", "Client Messages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });

        long timeoutMs = OVERALL_TIMEOUT_MS_DEFAULT;
        
        String param = msg.getSenderConfig().getOptions().getProperty(OVERALL_TIMEOUT_MS_PARAM);
        if (param == null)
            param = ctx.router().getConfigSetting(OVERALL_TIMEOUT_MS_PARAM);
        if (param != null) {
            try {
                timeoutMs = Long.parseLong(param);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid client message timeout specified [" + param 
                              + "], defaulting to " + OVERALL_TIMEOUT_MS_DEFAULT, nfe);
                timeoutMs = OVERALL_TIMEOUT_MS_DEFAULT;
            }
        }
        
        _overallExpiration = timeoutMs + getContext().clock().now();
        _status = new OutboundClientMessageStatus(ctx, msg);
        _nextStep = new NextStepJob();
        _lookupLeaseSetFailed = new LookupLeaseSetFailedJob();
        _shouldBundle = getShouldBundle();
    }
    
    public String getName() { return "Outbound client message"; }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Send outbound client message job beginning");
        buildClove();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Clove built");
        Hash to = _status.getTo().calculateHash();
        long timeoutMs = _overallExpiration - getContext().clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Send outbound client message - sending off leaseSet lookup job");
        _status.incrementLookups();
        getContext().netDb().lookupLeaseSet(to, _nextStep, _lookupLeaseSetFailed, timeoutMs);
    }
    
    /**
     * Continue on sending through the next tunnel
     */
    private void sendNext() {
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug(getJobId() + ": sendNext() called with " + _status.getNumSent() + " already sent");
        }
        
        if (_status.getSuccess()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": sendNext() - already successful!");
            return;
        }
        if (_status.getFailure()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": sendNext() - already failed!");
            return;
        }
        
        long now = getContext().clock().now();
        if (now >= _overallExpiration) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": sendNext() - Expired");
            dieFatal();
            return;
        }
        
        Lease nextLease = getNextLease();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Send outbound client message - next lease found for [" 
                       + _status.getTo().calculateHash().toBase64() + "] - " 
                       + nextLease);
        
        if (nextLease == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": No more leases, and we still haven't heard back from the peer"
                          + ", refetching the leaseSet to try again");
            LeaseSet ls = _status.getLeaseSet();
            _status.setLeaseSet(null);
            long remainingMs = _overallExpiration - getContext().clock().now();
            if (_status.getNumLookups() < MAX_LEASE_LOOKUPS) {
                _status.incrementLookups();
                Hash to = _status.getMessage().getDestination().calculateHash();
                _status.clearAlreadySent(); // so we can send down old tunnels again
                getContext().netDb().fail(to); // so we don't just fetch what we have
                getContext().netDb().lookupLeaseSet(to, _nextStep, _lookupLeaseSetFailed, remainingMs);
                if (ls != null)
                    getContext().jobQueue().addJob(new ShortCircuitSearchJob(ls));
                return;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": sendNext() - max # lease lookups exceeded! " 
                              + _status.getNumLookups());
                dieFatal();
                return;
            }
        }
        
        getContext().jobQueue().addJob(new SendJob(nextLease));
    }
    
    private static final long MAX_SEARCH_INTERVAL = 10*1000;
    /** 
     * If the netDb refetch isn't going well, lets fall back on the old leaseSet
     * anyway
     *
     */
    private class ShortCircuitSearchJob extends JobImpl {
        private LeaseSet _ls;
        public ShortCircuitSearchJob(LeaseSet ls) {
            super(OutboundClientMessageJob.this.getContext());
            _ls = ls;
            ShortCircuitSearchJob.this.getTiming().setStartAfter(getContext().clock().now() + MAX_SEARCH_INTERVAL);
        }
        public String getName() { return "Short circuit search"; }
        public void runJob() {
            LeaseSet ls = getContext().netDb().lookupLeaseSetLocally(_ls.getDestination().calculateHash());
            if (ls == null) {
                try {
                    getContext().netDb().store(_ls.getDestination().calculateHash(), _ls);
                } catch (IllegalArgumentException iae) {
                    // ignore - it expired anyway
                }
            }
        }
    }
    
    /**
     * fetch the next lease that we should try sending through, or null if there
     * are no remaining leases available (or there weren't any in the first place...).
     * This implements the logic to determine which lease should be next by picking a
     * random one that has been failing the least (e.g. if there are 3 leases in the leaseSet
     * and one has failed, the other two are randomly chosen as the 'next')
     *
     */
    private Lease getNextLease() {
        LeaseSet ls = _status.getLeaseSet();
        if (ls == null) {
            ls = getContext().netDb().lookupLeaseSetLocally(_status.getTo().calculateHash());
            if (ls == null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": Lookup locally didn't find the leaseSet");
                return null;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getJobId() + ": Lookup locally DID find the leaseSet");
            }
            _status.setLeaseSet(ls);
        }
        long now = getContext().clock().now();
        
        // get the possible leases
        List leases = new ArrayList(4);
        for (int i = 0; i < ls.getLeaseCount(); i++) {
            Lease lease = ls.getLease(i);
            if (lease.isExpired(Router.CLOCK_FUDGE_FACTOR)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": getNextLease() - expired lease! - " + lease);
                continue;
            }
            
            if (!_status.alreadySent(lease.getRouterIdentity().getHash(), lease.getTunnelId())) {
                leases.add(lease);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getJobId() + ": getNextLease() - skipping lease we've already sent it down - " 
                               + lease);
            }
        }
        
        if (leases.size() <= 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": No leases found, since we've tried them all (so fail it and relookup)");
            return null;
        }
        
        // randomize the ordering (so leases with equal # of failures per next 
        // sort are randomly ordered)
        Collections.shuffle(leases);
        
        // ordered by lease number of failures
        TreeMap orderedLeases = new TreeMap();
        for (Iterator iter = leases.iterator(); iter.hasNext(); ) {
            Lease lease = (Lease)iter.next();
            long id = lease.getNumFailure();
            while (orderedLeases.containsKey(new Long(id)))
                id++;
            orderedLeases.put(new Long(id), lease);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": ranking lease we havent sent it down as " + id);
        }
        
        return (Lease)orderedLeases.get(orderedLeases.firstKey());
    }
    
    private boolean getShouldBundle() {
        Properties opts = _status.getMessage().getSenderConfig().getOptions();
        String wantBundle = opts.getProperty(BUNDLE_REPLY_LEASESET, "true");
        if ("true".equals(wantBundle)) {
            int probability = BUNDLE_PROBABILITY_DEFAULT;
            String str = opts.getProperty(BUNDLE_PROBABILITY);
            try { 
                if (str != null) 
                    probability = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": Bundle leaseSet probability overridden incorrectly [" 
                              + str + "]", nfe);
            }
            if (probability >= getContext().random().nextInt(100))
                return true;
            else
                return false;
        } else {
            return false;
        }
    }
    
    /**
     * Send the message to the specified tunnel by creating a new garlic message containing
     * the (already created) payload clove as well as a new delivery status message.  This garlic
     * message is sent out one of our tunnels, destined for the lease (tunnel+router) specified, and the delivery
     * status message is targetting one of our free inbound tunnels as well.  We use a new
     * reply selector to keep an eye out for that delivery status message's token
     *
     */
    private void send(Lease lease) {
        long token = getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
        PublicKey key = _status.getLeaseSet().getEncryptionKey();
        SessionKey sessKey = new SessionKey();
        Set tags = new HashSet();
        LeaseSet replyLeaseSet = null;
        if (_shouldBundle) {
            replyLeaseSet = getContext().netDb().lookupLeaseSetLocally(_status.getFrom().calculateHash());
        }
        
        GarlicMessage msg = OutboundClientMessageJobHelper.createGarlicMessage(getContext(), token, 
                                                                               _overallExpiration, key, 
                                                                               _status.getClove(), 
                                                                               _status.getTo(), sessKey, 
                                                                               tags, true, replyLeaseSet);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send(lease) - token expected " + token);
        
        _status.sent(lease.getRouterIdentity().getHash(), lease.getTunnelId());
        
        SendSuccessJob onReply = new SendSuccessJob(lease, sessKey, tags);
        SendTimeoutJob onFail = new SendTimeoutJob(lease);
        ReplySelector selector = new ReplySelector(token);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Placing GarlicMessage into the new tunnel message bound for " 
                       + lease.getTunnelId() + " on " 
                       + lease.getRouterIdentity().getHash().toBase64());
        
        TunnelId outTunnelId = selectOutboundTunnel();
        if (outTunnelId != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Sending tunnel message out " + outTunnelId + " to " 
                           + lease.getTunnelId() + " on " 
                           + lease.getRouterIdentity().getHash().toBase64());
            SendTunnelMessageJob j = new SendTunnelMessageJob(getContext(), msg, outTunnelId, 
                                                              lease.getRouterIdentity().getHash(), 
                                                              lease.getTunnelId(), null, onReply, 
                                                              onFail, selector, SEND_TIMEOUT_MS, 
                                                              SEND_PRIORITY);
            getContext().jobQueue().addJob(j);
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error(getJobId() + ": Could not find any outbound tunnels to send the payload through... wtf?");
            dieFatal();
        }
    }
    
    /**
     * Pick an arbitrary outbound tunnel to send the message through, or null if
     * there aren't any around
     *
     */
    private TunnelId selectOutboundTunnel() {
        TunnelSelectionCriteria crit = new TunnelSelectionCriteria();
        crit.setMaximumTunnelsRequired(1);
        crit.setMinimumTunnelsRequired(1);
        List tunnelIds = getContext().tunnelManager().selectOutboundTunnelIds(crit);
        if (tunnelIds.size() <= 0)
            return null;
        else
            return (TunnelId)tunnelIds.get(0);
    }
    
    /**
     * give up the ghost, this message just aint going through.  tell the client to fuck off.
     *
     * this is safe to call multiple times (only tells the client once)
     */
    private void dieFatal() {
        if (_status.getSuccess()) return;
        boolean alreadyFailed = _status.failed();
        long sendTime = getContext().clock().now() - _status.getStart();
        ClientMessage msg = _status.getMessage();
        if (alreadyFailed) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": dieFatal() - already failed sending " + msg.getMessageId() 
                           + ", no need to do it again", new Exception("Duplicate death?"));
            return;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Failed to send the message " + msg.getMessageId() + " after " 
                           + _status.getNumSent() + " sends and " + _status.getNumLookups() 
                           + " lookups (and " + sendTime + "ms)", 
                           new Exception("Message send failure"));
        }
        
        getContext().messageHistory().sendPayloadMessage(msg.getMessageId().getMessageId(), false, sendTime);
        getContext().clientManager().messageDeliveryStatusUpdate(msg.getFromDestination(), msg.getMessageId(), false);
        getContext().statManager().updateFrequency("client.sendMessageFailFrequency");
        getContext().statManager().addRateData("client.sendAttemptAverage", _status.getNumSent(), sendTime);
        getContext().statManager().addRateData("client.sendsPerFailure", _status.getNumSent(), sendTime);
    }
    
    /** build the payload clove that will be used for all of the messages, placing the clove in the status structure */
    private void buildClove() {
        PayloadGarlicConfig clove = new PayloadGarlicConfig();
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_DESTINATION);
        instructions.setDestination(_status.getTo().calculateHash());
        
        instructions.setDelayRequested(false);
        instructions.setDelaySeconds(0);
        instructions.setEncrypted(false);
        
        clove.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        clove.setDeliveryInstructions(instructions);
        clove.setExpiration(_overallExpiration);
        clove.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        
        DataMessage msg = new DataMessage(getContext());
        msg.setData(_status.getMessage().getPayload().getEncryptedData());
        
        clove.setPayload(msg);
        clove.setRecipientPublicKey(null);
        clove.setRequestAck(false);
        
        _status.setClove(clove);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Built payload clove with id " + clove.getId());
    }
    
    /**
     * Keep an eye out for any of the delivery status message tokens that have been
     * sent down the various tunnels to deliver this message
     *
     */
    private class ReplySelector implements MessageSelector {
        private long _pendingToken;
        public ReplySelector(long token) {
            _pendingToken = token;
        }
        
        public boolean continueMatching() { return false; }
        public long getExpiration() { return _overallExpiration; }
        
        public boolean isMatch(I2NPMessage inMsg) {
            if (inMsg.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
                return _pendingToken == ((DeliveryStatusMessage)inMsg).getMessageId();
            } else {
                return false;
            }
        }
    }
    
    /** queued by the db lookup success and the send timeout to get us to try the next lease */
    private class NextStepJob extends JobImpl {
        public NextStepJob() {
            super(OutboundClientMessageJob.this.getContext());
        }
        public String getName() { return "Process next step for outbound client message"; }
        public void runJob() { sendNext(); }
    }
    
    /** 
     * we couldn't even find the leaseSet, but try again (or die 
     * if we've already tried too hard)
     *
     */
    private class LookupLeaseSetFailedJob extends JobImpl {
        public LookupLeaseSetFailedJob()  {
            super(OutboundClientMessageJob.this.getContext());
        }
        public String getName() { return "Lookup for outbound client message failed"; }
        public void runJob() { 
            sendNext();
        }
    }
    
    /** send a message to a lease */
    private class SendJob extends JobImpl {
        private Lease _lease;
        public SendJob(Lease lease) { 
            super(OutboundClientMessageJob.this.getContext());
            _lease = lease;
        }
        public String getName() { return "Send outbound client message through the lease"; }
        public void runJob() { send(_lease); }
    }
    
    /**
     * Called after we get a confirmation that the message was delivered safely
     * (hoo-ray!)
     *
     */
    private class SendSuccessJob extends JobImpl implements ReplyJob {
        private Lease _lease;
        private SessionKey _key;
        private Set _tags;
        
        /**
         * Create a new success job that will be fired when the message encrypted with
         * the given session key and bearing the specified tags are confirmed delivered.
         *
         */
        public SendSuccessJob(Lease lease, SessionKey key, Set tags) {
            super(OutboundClientMessageJob.this.getContext());
            _lease = lease;
            _key = key;
            _tags = tags;
        }
        
        public String getName() { return "Send client message successful to a lease"; }
        public void runJob() {
            long sendTime = getContext().clock().now() - _status.getStart();
            boolean alreadySuccessful = _status.success();
            MessageId msgId = _status.getMessage().getMessageId();
            if (_log.shouldLog(Log.INFO))
                _log.info(OutboundClientMessageJob.this.getJobId() 
                           + ": SUCCESS!  msg " + msgId 
                           + " sent after " + sendTime + "ms after " 
                           + _status.getNumLookups() + " lookups and " 
                           + _status.getNumSent() + " sends");
            
            if ( (_key != null) && (_tags != null) && (_tags.size() > 0) ) {
                LeaseSet ls = _status.getLeaseSet();
                if (ls != null)
                    getContext().sessionKeyManager().tagsDelivered(ls.getEncryptionKey(),
                                                              _key, _tags);
            }
            
            if (alreadySuccessful) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(OutboundClientMessageJob.this.getJobId() 
                               + ": Success is a duplicate for " +  _status.getMessage().getMessageId() 
                               + ", dont notify again...");
                return;
            }
            long dataMsgId = _status.getClove().getId();
            getContext().messageHistory().sendPayloadMessage(dataMsgId, true, sendTime);
            getContext().clientManager().messageDeliveryStatusUpdate(_status.getFrom(), msgId, true);
            _lease.setNumSuccess(_lease.getNumSuccess()+1);
            
            getContext().statManager().addRateData("client.sendAckTime", sendTime, 0);
            getContext().statManager().addRateData("client.sendMessageSize", _status.getMessage().getPayload().getSize(), sendTime);
            getContext().statManager().addRateData("client.sendAttemptAverage", _status.getNumSent(), sendTime);
        }
        
        public void setMessage(I2NPMessage msg) {}
    }
    
    /**
     * Fired after the basic timeout for sending through the given tunnel has been reached.
     * We'll accept successes later, but won't expect them
     *
     */
    private class SendTimeoutJob extends JobImpl {
        private Lease _lease;
        
        public SendTimeoutJob(Lease lease) {
            super(OutboundClientMessageJob.this.getContext());
            _lease = lease;
        }
        
        public String getName() { return "Send client message timed out through a lease"; }
        public void runJob() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(OutboundClientMessageJob.this.getJobId()
                           + ": Soft timeout through the lease " + _lease);
            
            long messageDelay = getContext().throttle().getMessageDelay();
            long tunnelLag = getContext().throttle().getTunnelLag();
            long inboundDelta = (long)getContext().throttle().getInboundRateDelta();
            getContext().statManager().addRateData("client.timeoutCongestionTunnel", tunnelLag, 1);
            getContext().statManager().addRateData("client.timeoutCongestionMessage", messageDelay, 1);
            getContext().statManager().addRateData("client.timeoutCongestionInbound", inboundDelta, 1);
            
            _lease.setNumFailure(_lease.getNumFailure()+1);
            sendNext();
        }
    }
}
