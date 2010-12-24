package net.i2p.router.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.Base64;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
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
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Send a client message out a random outbound tunnel and into a random inbound
 * tunnel on the target leaseSet.  This also bundles the sender's leaseSet and
 * a DeliveryStatusMessage (for ACKing any sessionTags used in the garlic).
 *
 */
public class OutboundClientMessageOneShotJob extends JobImpl {
    private Log _log;
    private long _overallExpiration;
    private ClientMessage _clientMessage;
    private MessageId _clientMessageId;
    private int _clientMessageSize;
    private Destination _from;
    private Destination _to;
    private String _toString;
    /** target destination's leaseSet, if known */
    private LeaseSet _leaseSet;
    /** Actual lease the message is being routed through */
    private Lease _lease;
    private PayloadGarlicConfig _clove;
    private long _cloveId;
    private long _start;
    private boolean _finished;
    private long _leaseSetLookupBegin;
    private TunnelInfo _outTunnel;
    private TunnelInfo _inTunnel;
    private boolean _wantACK;
    
    /**
     * final timeout (in milliseconds) that the outbound message will fail in.
     * This can be overridden in the router.config or the client's session config
     * (the client's session config takes precedence)
     */
    public final static String OVERALL_TIMEOUT_MS_PARAM = "clientMessageTimeout";
    private final static long OVERALL_TIMEOUT_MS_DEFAULT = 60*1000;
    private final static long OVERALL_TIMEOUT_MS_MIN = 8*1000;
    
    /** priority of messages, that might get honored some day... */
    private final static int SEND_PRIORITY = 500;
    
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
    
    private static final Object _initializeLock = new Object();
    private static boolean _initialized = false;
    private static final int CLEAN_INTERVAL = 5*60*1000;
    private static final int REPLY_REQUEST_INTERVAL = 60*1000;

    /**
     * Send the sucker
     */
    public OutboundClientMessageOneShotJob(RouterContext ctx, ClientMessage msg) {
        super(ctx);
        _log = ctx.logManager().getLog(OutboundClientMessageOneShotJob.class);
        
        synchronized (_initializeLock) {
            if (!_initialized) {
                SimpleScheduler.getInstance().addPeriodicEvent(new OCMOSJCacheCleaner(ctx), CLEAN_INTERVAL, CLEAN_INTERVAL);
                ctx.statManager().createFrequencyStat("client.sendMessageFailFrequency", "How often does a client fail to send a message?", "ClientMessages", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.sendMessageSize", "How large are messages sent by the client?", "ClientMessages", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.sendAckTime", "Message round trip time", "ClientMessages", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.timeoutCongestionTunnel", "How lagged our tunnels are when a send times out?", "ClientMessages", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.timeoutCongestionMessage", "How fast we process messages locally when a send times out?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.timeoutCongestionInbound", "How much faster we are receiving data than our average bps when a send times out?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.leaseSetFoundLocally", "How often we tried to look for a leaseSet and found it locally?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.leaseSetFoundRemoteTime", "How long we tried to look for a remote leaseSet (when we succeeded)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.leaseSetFailedRemoteTime", "How long we tried to look for a remote leaseSet (when we failed)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.dispatchPrepareTime", "How long until we've queued up the dispatch job (since we started)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.dispatchTime", "How long until we've dispatched the message (since we started)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.dispatchSendTime", "How long the actual dispatching takes?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.dispatchNoTunnels", "How long after start do we run out of tunnels to send/receive with?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
                ctx.statManager().createRateStat("client.dispatchNoACK", "Repeated message sends to a peer (no ack required)", "ClientMessages", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l });
                _initialized = true;
            }
        }
        long timeoutMs = OVERALL_TIMEOUT_MS_DEFAULT;
        _clientMessage = msg;
        _clientMessageId = msg.getMessageId();
        _clientMessageSize = msg.getPayload().getSize();
        _from = msg.getFromDestination();
        _to = msg.getDestination();
        _toString = _to.calculateHash().toBase64().substring(0,4);
        _leaseSetLookupBegin = -1;
        _start = getContext().clock().now();
        
        // use expiration requested by client if available, otherwise session config,
        // otherwise router config, otherwise default
        _overallExpiration = msg.getExpiration();
        if (_overallExpiration > 0) {
           _overallExpiration = Math.max(_overallExpiration, _start + OVERALL_TIMEOUT_MS_MIN);
           _overallExpiration = Math.min(_overallExpiration, _start + OVERALL_TIMEOUT_MS_DEFAULT);
           if (_log.shouldLog(Log.INFO))
               _log.info(getJobId() + ": Message Expiration (ms): " + (_overallExpiration - _start));
        } else {
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
            _overallExpiration = timeoutMs + _start;
           if (_log.shouldLog(Log.INFO))
               _log.info(getJobId() + " Default Expiration (ms): " + timeoutMs);
        }
        _finished = false;
    }
    
    public String getName() { return "Outbound client message"; }
    
    public void runJob() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Send outbound client message job beginning");
        long timeoutMs = _overallExpiration - getContext().clock().now();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": preparing to search for the leaseSet for " + _toString);
        Hash key = _to.calculateHash();
        SendJob success = new SendJob(getContext());
        LookupLeaseSetFailedJob failed = new LookupLeaseSetFailedJob(getContext());
        LeaseSet ls = getContext().netDb().lookupLeaseSetLocally(key);
        if (ls != null) {
            getContext().statManager().addRateData("client.leaseSetFoundLocally", 1, 0);
            _leaseSetLookupBegin = -1;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Send outbound client message - leaseSet found locally for " + _toString);
            success.runJob();
        } else {
            _leaseSetLookupBegin = getContext().clock().now();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Send outbound client message - sending off leaseSet lookup job for " + _toString);
            getContext().netDb().lookupLeaseSet(key, success, failed, timeoutMs);
        }
    }
    
    /**
      * Returns the reply lease set if forced to do so,
      * or if configured to do so,
      * or if a certain percentage of the time if configured to do so,
      * or if our lease set has changed since we last talked to them,
      * or 10% of the time anyway so they don't forget us (disabled for now),
      * or null otherwise.
      *
      * Note that wantACK randomly forces us another 5% of the time.
      *
      * We don't want to do this too often as a typical 2-lease leaseset
      * in a DatabaseStoreMessage is 861+37=898 bytes -
      * when added to garlic it's a 1056-byte addition total, which is huge.
      *
      * Key the cache on the source+dest pair.
      */
    private final static HashMap<String, LeaseSet> _leaseSetCache = new HashMap();
    private LeaseSet getReplyLeaseSet(boolean force) {
        LeaseSet newLS = getContext().netDb().lookupLeaseSetLocally(_from.calculateHash());
        if (newLS == null)
            return null;   // punt

        if (!force) {
            // Don't send it every time unless configured to; default=false
            Properties opts = _clientMessage.getSenderConfig().getOptions();
            String wantBundle = opts.getProperty(BUNDLE_REPLY_LEASESET, "false");
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
                if (probability >= 100)
                    return newLS;  // do this every time so don't worry about cache
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": Bundle leaseSet probability is " + probability);
                if (probability >= getContext().random().nextInt(100))
                    force = true;  // just add newLS to cache below and return
                // fall through to cache check and add
            }
        }

        // If the last leaseSet we sent him is still good, don't bother sending again
        long now = getContext().clock().now();
        synchronized (_leaseSetCache) {
            if (!force) {
                LeaseSet ls = _leaseSetCache.get(hashPair());
                if (ls != null) {
                    if (ls.equals(newLS)) {
                        // still good, send it 10% of the time
                        // sendACK does 5% random which forces us, good enough
                        //if (10 >= getContext().random().nextInt(100)) {
                        //    if (_log.shouldLog(Log.INFO))
                        //        _log.info("Found in cache - including reply leaseset for " + _toString); 
                        //    return ls;
                        //} else {
                            if (_log.shouldLog(Log.INFO))
                                _log.info(getJobId() + ": Found in cache - NOT including reply leaseset for " + _toString); 
                            return null;
                        //}
                    } else {
                        if (_log.shouldLog(Log.INFO))
                            _log.info(getJobId() + ": Expired from cache - reply leaseset for " + _toString); 
                        // will get overwritten below
                        // _leaseSetCache.remove(hashPair());
                    }
                }
            }
            _leaseSetCache.put(hashPair(), newLS);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Added to cache - reply leaseset for " + _toString); 
        return newLS;
    }
    
    /** send a message to a lease */
    private class SendJob extends JobImpl {
        public SendJob(RouterContext enclosingContext) { 
            super(enclosingContext);
        }
        public String getName() { return "Send outbound client message through the lease"; }
        public void runJob() {
            if (_leaseSetLookupBegin > 0) {
                long lookupTime = getContext().clock().now() - _leaseSetLookupBegin;
                getContext().statManager().addRateData("client.leaseSetFoundRemoteTime", lookupTime, lookupTime);
            }
            _wantACK = false;
            boolean ok = getNextLease();
            if (ok) {
                send();
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to send on a random lease, as getNext returned null (to=" + _toString + ")");
                dieFatal();
            }
        }
    }
    
    /**
     * Use the same inbound tunnel (i.e. lease) as we did for the same destination previously,
     * if possible, to keep the streaming lib happy
     * Key the caches on the source+dest pair.
     *
     * We're going to use the lease until it expires, as long as it remains in the current leaseSet.
     *
     * If not found,
     * fetch the next lease that we should try sending through, randomly chosen
     * from within the sorted leaseSet (NOT sorted by # of failures through each 
     * lease).
     *
     */
    private final static HashMap<String, Lease> _leaseCache = new HashMap();
    private boolean getNextLease() {
        _leaseSet = getContext().netDb().lookupLeaseSetLocally(_to.calculateHash());
        if (_leaseSet == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Lookup locally didn't find the leaseSet for " + _toString);
            return false;
        } 
        long now = getContext().clock().now();

        // Use the same lease if it's still good
        // Even if _leaseSet changed, _leaseSet.getEncryptionKey() didn't...
        synchronized (_leaseCache) {
            _lease = _leaseCache.get(hashPair());
            if (_lease != null) {
                // if outbound tunnel length == 0 && lease.firsthop.isBacklogged() don't use it ??
                if (!_lease.isExpired(Router.CLOCK_FUDGE_FACTOR)) {
                    // see if the current leaseSet contains the old lease, so that if the dest removes
                    // it (due to failure for example) we won't continue to use it.
                    for (int i = 0; i < _leaseSet.getLeaseCount(); i++) {
                        Lease lease = _leaseSet.getLease(i);
                        if (_lease.equals(lease)) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info(getJobId() + ": Found in cache - lease for " + _toString); 
                            return true;
                        }
                    }
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": Expired from cache - lease for " + _toString); 
                _leaseCache.remove(_to);
            }
        }

        // get the possible leases
        List leases = new ArrayList(_leaseSet.getLeaseCount());
        for (int i = 0; i < _leaseSet.getLeaseCount(); i++) {
            Lease lease = _leaseSet.getLease(i);
            if (lease.isExpired(Router.CLOCK_FUDGE_FACTOR)) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": getNextLease() - expired lease! - " + lease + " for " + _toString);
                continue;
            } else {
                leases.add(lease);
            }
        }
        
        if (leases.isEmpty()) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": No leases found from: " + _leaseSet);
            return false;
        }
        
        // randomize the ordering (so leases with equal # of failures per next 
        // sort are randomly ordered)
        Collections.shuffle(leases);
        
/****
        if (false) {
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
            
            _lease = (Lease)orderedLeases.get(orderedLeases.firstKey());
        } else {
****/


        // Avoid a lease on a gateway we think is unreachable, if possible
        for (int i = 0; i < leases.size(); i++) {
            Lease l = (Lease) leases.get(i);
/***
 ***  Anonymity concerns with this, as the dest could act unreachable just to us, then
 ***  look at our lease selection.
 ***  Let's just look at whether the gw thinks it is unreachable instead -
 ***  unfortunately the "U" is rarely seen.
            if (!getContext().commSystem().wasUnreachable(l.getGateway())) {
***/
            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(l.getGateway());
            if (ri == null || ri.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) < 0) {
                _lease = l;
                break;
            }
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Skipping unreachable gateway " + l.getGateway() + " for " + _toString); 
        }
        if (_lease == null) {
            _lease = (Lease)leases.get(0);
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": All leases are unreachable for " + _toString); 
        }
        synchronized (_leaseCache) {
            _leaseCache.put(hashPair(), _lease);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Added to cache - lease for " + _toString); 
        _wantACK = true;
        return true;
    }

    
    /** 
     * we couldn't even find the leaseSet, but try again (or die 
     * if we've already tried too hard)
     *
     */
    private class LookupLeaseSetFailedJob extends JobImpl {
        public LookupLeaseSetFailedJob(RouterContext enclosingContext)  {
            super(enclosingContext);
        }
        public String getName() { return "Lookup for outbound client message failed"; }
        public void runJob() {
            if (_leaseSetLookupBegin > 0) {
                long lookupTime = getContext().clock().now() - _leaseSetLookupBegin;
                getContext().statManager().addRateData("client.leaseSetFailedRemoteTime", lookupTime, lookupTime);
            }
            
            if (!_finished) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to send to " + _toString + " because we couldn't find their leaseSet");
            }

            dieFatal();
        }
    }
    
    /**
     * This cache is used to ensure that we request a reply every so often.
     * Hopefully this allows the router to recognize a failed tunnel and switch,
     * before upper layers like streaming lib fail, even for low-bandwidth
     * connections like IRC.
     */
    private final static HashMap<String, Long> _lastReplyRequestCache = new HashMap();

    /**
     * Send the message to the specified tunnel by creating a new garlic message containing
     * the (already created) payload clove as well as a new delivery status message.  This garlic
     * message is sent out one of our tunnels, destined for the lease (tunnel+router) specified, and the delivery
     * status message is targetting one of our free inbound tunnels as well.  We use a new
     * reply selector to keep an eye out for that delivery status message's token
     *
     */
    private void send() {
        if (_finished) return;
        long now = getContext().clock().now();
        if (now >= _overallExpiration) {
            dieFatal();
            return;
        }

        int existingTags = GarlicMessageBuilder.estimateAvailableTags(getContext(), _leaseSet.getEncryptionKey(),
                                                                      _from.calculateHash());
        _outTunnel = selectOutboundTunnel(_to);
        // boolean wantACK = _wantACK || existingTags <= 30 || getContext().random().nextInt(100) < 5;
        // what's the point of 5% random? possible improvements or replacements:
        // DONE (getNextLease() is called before this): wantACK if we changed their inbound lease (getNextLease() sets _wantACK)
        // DONE (selectOutboundTunnel() moved above here): wantACK if we changed our outbound tunnel (selectOutboundTunnel() sets _wantACK)
        // DONE (added new cache): wantACK if we haven't in last 1m (requires a new static cache probably)
        boolean wantACK;
        synchronized (_lastReplyRequestCache) {
            Long lastSent = _lastReplyRequestCache.get(hashPair());
            wantACK = _wantACK || existingTags <= 30 ||
                      lastSent == null || lastSent.longValue() < now - REPLY_REQUEST_INTERVAL;
            if (wantACK)
                _lastReplyRequestCache.put(hashPair(), Long.valueOf(now));
        }
        
        PublicKey key = _leaseSet.getEncryptionKey();
        SessionKey sessKey = new SessionKey();
        Set<SessionTag> tags = new HashSet();
        // If we want an ack, bundle a leaseSet... (so he can get back to us)
        LeaseSet replyLeaseSet = getReplyLeaseSet(wantACK);
        // ... and vice versa  (so we know he got it)
        if (replyLeaseSet != null)
            wantACK = true;
        long token = (wantACK ? getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE) : -1);
        if (wantACK)
            _inTunnel = selectInboundTunnel();

        boolean ok = (_clientMessage != null) && buildClove();
        if (!ok) {
            dieFatal();
            return;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Clove built to " + _toString);
        long msgExpiration = _overallExpiration; // getContext().clock().now() + OVERALL_TIMEOUT_MS_DEFAULT;
        GarlicMessage msg = OutboundClientMessageJobHelper.createGarlicMessage(getContext(), token, 
                                                                               msgExpiration, key, 
                                                                               _clove, _from.calculateHash(), 
                                                                               _to, _inTunnel,
                                                                               sessKey, tags, 
                                                                               wantACK, replyLeaseSet);
        if (msg == null) {
            // set to null if there are no tunnels to ack the reply back through
            // (should we always fail for this? or should we send it anyway, even if
            // we dont receive the reply? hmm...)
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Unable to create the garlic message (no tunnels left or too lagged) to " + _toString);
            getContext().statManager().addRateData("client.dispatchNoTunnels", now - _start, 0);            
            dieFatal();
            return;
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": send() - token expected " + token + " to " + _toString);
        
        SendSuccessJob onReply = null;
        SendTimeoutJob onFail = null;
        ReplySelector selector = null;
        if (wantACK) {
            TagSetHandle tsh = null;
            if ( (sessKey != null) && (tags != null) && (!tags.isEmpty()) ) {
                if (_leaseSet != null) {
                    SessionKeyManager skm = getContext().clientManager().getClientSessionKeyManager(_from.calculateHash());
                    if (skm != null)
                        tsh = skm.tagsDelivered(_leaseSet.getEncryptionKey(), sessKey, tags);
                }
            }
            onReply = new SendSuccessJob(getContext(), sessKey, tsh);
            onFail = new SendTimeoutJob(getContext(), sessKey, tsh);
            selector = new ReplySelector(token);
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Placing GarlicMessage into the new tunnel message bound for " 
                       + _toString + " at "
                       + _lease.getTunnelId() + " on " 
                       + _lease.getGateway().toBase64());
        
        if (_outTunnel != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Sending tunnel message out " + _outTunnel.getSendTunnelId(0) + " to " 
                           + _toString + " at "
                           + _lease.getTunnelId() + " on " 
                           + _lease.getGateway().toBase64());

            DispatchJob dispatchJob = new DispatchJob(getContext(), msg, selector, onReply, onFail, (int)(_overallExpiration-getContext().clock().now()));
            //if (false) // dispatch may take 100+ms, so toss it in its own job
            //    getContext().jobQueue().addJob(dispatchJob);
            //else
                dispatchJob.runJob();
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Could not find any outbound tunnels to send the payload through... this might take a while");
            getContext().statManager().addRateData("client.dispatchNoTunnels", now - _start, 0);
            dieFatal();
        }
        _clientMessage = null;
        _clove = null;
        getContext().statManager().addRateData("client.dispatchPrepareTime", now - _start, 0);
        if (!wantACK)
            getContext().statManager().addRateData("client.dispatchNoACK", 1, 0);
    }

    private class DispatchJob extends JobImpl {
        private GarlicMessage _msg;
        private ReplySelector _selector;
        private SendSuccessJob _replyFound;
        private SendTimeoutJob _replyTimeout;
        private int _timeoutMs;
        public DispatchJob(RouterContext ctx, GarlicMessage msg, ReplySelector sel, SendSuccessJob success, SendTimeoutJob timeout, int timeoutMs) {
            super(ctx);
            _msg = msg;
            _selector = sel;
            _replyFound = success;
            _replyTimeout = timeout;
            _timeoutMs = timeoutMs;
        }
        public String getName() { return "Dispatch outbound client message"; }
        public void runJob() {
            if (_selector != null)
                getContext().messageRegistry().registerPending(_selector, _replyFound, _replyTimeout, _timeoutMs);
            if (_log.shouldLog(Log.INFO))
                _log.info(OutboundClientMessageOneShotJob.this.getJobId() +
                          ": Dispatching message to " + _toString + ": " + _msg);
            long before = getContext().clock().now();
            getContext().tunnelDispatcher().dispatchOutbound(_msg, _outTunnel.getSendTunnelId(0), _lease.getTunnelId(), _lease.getGateway());
            long dispatchSendTime = getContext().clock().now() - before; 
            if (_log.shouldLog(Log.INFO))
                _log.info(OutboundClientMessageOneShotJob.this.getJobId() +
                          ": Dispatching message to " + _toString + " complete");
            getContext().statManager().addRateData("client.dispatchTime", getContext().clock().now() - _start, 0);
            getContext().statManager().addRateData("client.dispatchSendTime", dispatchSendTime, 0);
        }
    }
    
    /**
     * This is the place where we make I2P go fast.
     *
     * We have five static caches.
     * - The LeaseSet cache is used to decide whether to bundle our own leaseset,
     *   which minimizes overhead.
     * - The Lease cache is used to persistently send to the same lease for the destination,
     *   which keeps the streaming lib happy by minimizing out-of-order delivery.
     * - The Tunnel and BackloggedTunnel caches are used to persistently use the same outbound tunnel
     *   for the same destination,
     *   which keeps the streaming lib happy by minimizing out-of-order delivery.
     * - The last reply requested cache ensures that a reply is requested every so often,
     *   so that failed tunnels are recognized.
     *
     */

    /**
     * String used to cache things with based on source + dest
     * Put the dest first to make string matching faster
     */
    private String _hashPair;
    private String hashPair() {
        if (_hashPair == null)
            _hashPair = _to.calculateHash().toBase64() + _from.calculateHash().toBase64();
        return _hashPair;
    }

    /**
     * This is a little sneaky, but get the _from back out of the "opaque" hash key
     * (needed for cleanTunnelCache)
     * 44 = 32 * 4 / 3
     */
    private static Hash sourceFromHashPair(String s) {
        return new Hash(Base64.decode(s.substring(44, 88)));
    }

    /**
     * Called on failure to give us a better chance of success next time.
     * Of course this is probably 60s too late.
     * And we could pick the bad ones at random again.
     * Or remove entries that were sent and succeeded after this was sent but before this failed.
     * But it's a start.
     */
    private void clearCaches() {
        String key = hashPair();
        if (_inTunnel != null) {   // if we wanted an ack, we sent our lease too
            synchronized(_leaseSetCache) {
                _leaseSetCache.remove(key);
            }
        }
        if (_lease != null) {
            synchronized(_leaseCache) {
                Lease l = _leaseCache.get(key);
                if (l != null && l.equals(_lease))
                    _leaseCache.remove(key);
            }
        }
        if (_outTunnel != null) {
            synchronized(_tunnelCache) {
                TunnelInfo t = _backloggedTunnelCache.get(key);
                if (t != null && t.equals(_outTunnel))
                    _backloggedTunnelCache.remove(key);
                t = _tunnelCache.get(key);
                if (t != null && t.equals(_outTunnel))
                    _tunnelCache.remove(key);
            }
        }
    }

    /**
     * Clean out old leaseSets from a set.
     * Caller must synchronize on tc.
     */
    private static void cleanLeaseSetCache(RouterContext ctx, HashMap tc) {
        long now = ctx.clock().now();
        for (Iterator iter = tc.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry entry = (Map.Entry)iter.next();
            String k = (String) entry.getKey();
            LeaseSet l = (LeaseSet) entry.getValue();
            if (l.getEarliestLeaseDate() < now)
                iter.remove();
        }
    }

    /**
     * Clean out old leases from a set.
     * Caller must synchronize on tc.
     */
    private static void cleanLeaseCache(HashMap tc) {
        for (Iterator iter = tc.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry entry = (Map.Entry)iter.next();
            String k = (String) entry.getKey();
            Lease l = (Lease) entry.getValue();
            if (l.isExpired(Router.CLOCK_FUDGE_FACTOR))
                iter.remove();
        }
    }

    /**
     * Clean out old tunnels from a set.
     * Caller must synchronize on tc.
     */
    private static void cleanTunnelCache(RouterContext ctx, HashMap tc) {
        for (Iterator iter = tc.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry entry = (Map.Entry)iter.next();
            String k = (String) entry.getKey();
            TunnelInfo tunnel = (TunnelInfo) entry.getValue();
            if (!ctx.tunnelManager().isValidTunnel(sourceFromHashPair(k), tunnel))
                iter.remove();
        }
    }

    /**
     * Clean out old reply times
     * Caller must synchronize on tc.
     */
    private static void cleanReplyCache(RouterContext ctx, HashMap tc) {
        long now = ctx.clock().now();
        for (Iterator iter = tc.values().iterator(); iter.hasNext(); ) {
            Long l = (Long) iter.next();
            if (l.longValue() < now - CLEAN_INTERVAL)
                iter.remove();
        }
    }

    private static class OCMOSJCacheCleaner implements SimpleTimer.TimedEvent {
        private RouterContext _ctx;
        private OCMOSJCacheCleaner(RouterContext ctx) {
            _ctx = ctx;
        }
        public void timeReached() {
            synchronized(_leaseSetCache) {
                cleanLeaseSetCache(_ctx, _leaseSetCache);
            }
            synchronized(_leaseCache) {
                cleanLeaseCache(_leaseCache);
            }
            synchronized(_tunnelCache) {
                cleanTunnelCache(_ctx, _tunnelCache);
                cleanTunnelCache(_ctx, _backloggedTunnelCache);
            }
            synchronized(_lastReplyRequestCache) {
                cleanReplyCache(_ctx, _lastReplyRequestCache);
            }
        }
    }

    /**
     * Use the same outbound tunnel as we did for the same destination previously,
     * if possible, to keep the streaming lib happy
     * Use two caches - although a cache of a list of tunnels per dest might be
     * more elegant.
     * Key the caches on the source+dest pair.
     *
     */
    private static final HashMap<String, TunnelInfo> _tunnelCache = new HashMap();
    private static HashMap<String, TunnelInfo> _backloggedTunnelCache = new HashMap();
    private TunnelInfo selectOutboundTunnel(Destination to) {
        TunnelInfo tunnel;
        long now = getContext().clock().now();
        synchronized (_tunnelCache) {
            /**
             * If old tunnel is valid and no longer backlogged, use it.
             * This prevents an active anonymity attack, where a peer could tell
             * if you were the originator by backlogging the tunnel, then removing the
             * backlog and seeing if traffic came back or not.
             */
            tunnel = _backloggedTunnelCache.get(hashPair());
            if (tunnel != null) {
                if (getContext().tunnelManager().isValidTunnel(_from.calculateHash(), tunnel)) {
                    if (!getContext().commSystem().isBacklogged(tunnel.getPeer(1))) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Switching back to tunnel " + tunnel + " for " + _toString); 
                        _backloggedTunnelCache.remove(hashPair());
                        _tunnelCache.put(hashPair(), tunnel);
                        _wantACK = true;
                        return tunnel;
                    }  // else still backlogged
                } else // no longer valid
                    _backloggedTunnelCache.remove(hashPair());
            }
            // Use the same tunnel unless backlogged
            tunnel = _tunnelCache.get(hashPair());
            if (tunnel != null) {
                if (getContext().tunnelManager().isValidTunnel(_from.calculateHash(), tunnel)) {
                    if (tunnel.getLength() <= 1 || !getContext().commSystem().isBacklogged(tunnel.getPeer(1)))
                        return tunnel;
                    // backlogged
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Switching from backlogged " + tunnel + " for " + _toString); 
                    _backloggedTunnelCache.put(hashPair(), tunnel);
                } // else no longer valid
                _tunnelCache.remove(hashPair());
            }
            // Pick a new tunnel
            tunnel = selectOutboundTunnel();
            if (tunnel != null)
                _tunnelCache.put(hashPair(), tunnel);
            _wantACK = true;
        }
        return tunnel;
    }
    /**
     * Pick an arbitrary outbound tunnel to send the message through, or null if
     * there aren't any around
     *
     */
    private TunnelInfo selectOutboundTunnel() {
        return getContext().tunnelManager().selectOutboundTunnel(_from.calculateHash());
    }
    /**
     * Pick an arbitrary outbound tunnel for any deliveryStatusMessage to come back in
     *
     */
    private TunnelInfo selectInboundTunnel() {
        return getContext().tunnelManager().selectInboundTunnel(_from.calculateHash());
    }
    
    /**
     * give up the ghost, this message just aint going through.  tell the client.
     *
     * this is safe to call multiple times (only tells the client once)
     */
    private void dieFatal() {
        if (_finished) return;
        _finished = true;
        
        long sendTime = getContext().clock().now() - _start;
        if (_log.shouldLog(Log.WARN))
            _log.warn(getJobId() + ": Failed to send the message " + _clientMessageId + " to " + _toString +
                       " out " + _outTunnel + " in " + _lease + " ack " + _inTunnel +
                       " after " + sendTime + "ms");
        
        long messageDelay = getContext().throttle().getMessageDelay();
        long tunnelLag = getContext().throttle().getTunnelLag();
        long inboundDelta = (long)getContext().throttle().getInboundRateDelta();
            
        getContext().statManager().addRateData("client.timeoutCongestionTunnel", tunnelLag, 1);
        getContext().statManager().addRateData("client.timeoutCongestionMessage", messageDelay, 1);
        getContext().statManager().addRateData("client.timeoutCongestionInbound", inboundDelta, 1);
    
        clearCaches();
        getContext().messageHistory().sendPayloadMessage(_clientMessageId.getMessageId(), false, sendTime);
        getContext().clientManager().messageDeliveryStatusUpdate(_from, _clientMessageId, false);
        getContext().statManager().updateFrequency("client.sendMessageFailFrequency");
        _clientMessage = null;
        _clove = null;
    }
    
    /** build the payload clove that will be used for all of the messages, placing the clove in the status structure */
    private boolean buildClove() {
        PayloadGarlicConfig clove = new PayloadGarlicConfig();
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_DESTINATION);
        instructions.setDestination(_to.calculateHash());
        
        instructions.setDelayRequested(false);
        instructions.setDelaySeconds(0);
        instructions.setEncrypted(false);
        
        clove.setCertificate(Certificate.NULL_CERT);
        clove.setDeliveryInstructions(instructions);
        clove.setExpiration(OVERALL_TIMEOUT_MS_DEFAULT+getContext().clock().now());
        clove.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        
        DataMessage msg = new DataMessage(getContext());
        Payload p = _clientMessage.getPayload();
        if (p == null)
            return false;
        byte d[] = p.getEncryptedData();
        if (d == null)
            return false;
        msg.setData(d);
        msg.setMessageExpiration(clove.getExpiration());
        
        clove.setPayload(msg);
        clove.setRecipientPublicKey(null);
        clove.setRequestAck(false);
        
        _clove = clove;
        _cloveId = _clove.getId();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Built payload clove with id " + clove.getId());
        return true;
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
            if (_log.shouldLog(Log.INFO))
                _log.info(OutboundClientMessageOneShotJob.this.getJobId() 
                           + ": Reply selector for client message: token=" + token);
        }
        
        public boolean continueMatching() { 
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug(OutboundClientMessageOneShotJob.this.getJobId() 
            //               + ": dont continue matching for token=" + _pendingToken);
            return false; 
        }
        public long getExpiration() { return _overallExpiration; }
        
        public boolean isMatch(I2NPMessage inMsg) {
            if (inMsg.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug(OutboundClientMessageOneShotJob.this.getJobId() 
                //               + ": delivery status message received: " + inMsg + " our token: " + _pendingToken);
                return _pendingToken == ((DeliveryStatusMessage)inMsg).getMessageId();
            } else {
                return false;
            }
        }
        
        @Override
        public String toString() {
            return "sending " + _toString + " waiting for token " + _pendingToken
                   + " for cloveId " + _cloveId;
        }
    }
    
    /**
     * Called after we get a confirmation that the message was delivered safely
     * (hoo-ray!)
     *
     */
    private class SendSuccessJob extends JobImpl implements ReplyJob {
        private SessionKey _key;
        private TagSetHandle _tags;
        
        /**
         * Create a new success job that will be fired when the message encrypted with
         * the given session key and bearing the specified tags are confirmed delivered.
         *
         */
        public SendSuccessJob(RouterContext enclosingContext, SessionKey key, TagSetHandle tags) {
            super(enclosingContext);
            _key = key;
            _tags = tags;
        }
        
        public String getName() { return "Send client message successful"; }
        public void runJob() {
            // do we leak tags here?
            if (_finished) return;
            _finished = true;
            long sendTime = getContext().clock().now() - _start;
            if (_log.shouldLog(Log.INFO))
                _log.info(OutboundClientMessageOneShotJob.this.getJobId() 
                           + ": SUCCESS!  msg " + _clientMessageId
                           + " acked by DSM after " + sendTime + "ms");
            
            if (_key != null && _tags != null && _leaseSet != null) {
                SessionKeyManager skm = getContext().clientManager().getClientSessionKeyManager(_from.calculateHash());
                if (skm != null)
                    skm.tagsAcked(_leaseSet.getEncryptionKey(), _key, _tags);
            }
            
            long dataMsgId = _cloveId;
            getContext().messageHistory().sendPayloadMessage(dataMsgId, true, sendTime);
            getContext().clientManager().messageDeliveryStatusUpdate(_from, _clientMessageId, true);
            // unused
            //_lease.setNumSuccess(_lease.getNumSuccess()+1);
        
            int size = _clientMessageSize;
            
            getContext().statManager().addRateData("client.sendAckTime", sendTime, 0);
            getContext().statManager().addRateData("client.sendMessageSize", _clientMessageSize, sendTime);
            if (_outTunnel != null) {
                if (_outTunnel.getLength() > 0)
                    size = ((size + 1023) / 1024) * 1024; // messages are in ~1KB blocks
                
                for (int i = 0; i < _outTunnel.getLength(); i++) {
                    getContext().profileManager().tunnelTestSucceeded(_outTunnel.getPeer(i), sendTime);
                    getContext().profileManager().tunnelDataPushed(_outTunnel.getPeer(i), sendTime, size);
                }
                _outTunnel.incrementVerifiedBytesTransferred(size);
            }
            if (_inTunnel != null)
                for (int i = 0; i < _inTunnel.getLength(); i++)
                    getContext().profileManager().tunnelTestSucceeded(_inTunnel.getPeer(i), sendTime);
        }

        public void setMessage(I2NPMessage msg) {}
    }
    
    /**
     * Fired after the basic timeout for sending through the given tunnel has been reached.
     * We'll accept successes later, but won't expect them
     *
     */
    private class SendTimeoutJob extends JobImpl {
        private SessionKey _key;
        private TagSetHandle _tags;

        public SendTimeoutJob(RouterContext enclosingContext, SessionKey key, TagSetHandle tags) {
            super(enclosingContext);
            _key = key;
            _tags = tags;
        }
        
        public String getName() { return "Send client message timed out"; }
        public void runJob() {
            if (_log.shouldLog(Log.INFO))
                _log.info(OutboundClientMessageOneShotJob.this.getJobId()
                           + ": Soft timeout through the lease " + _lease);
            
            // unused
            //_lease.setNumFailure(_lease.getNumFailure()+1);
            if (_key != null && _tags != null && _leaseSet != null) {
                SessionKeyManager skm = getContext().clientManager().getClientSessionKeyManager(_from.calculateHash());
                if (skm != null)
                    skm.failTags(_leaseSet.getEncryptionKey(), _key, _tags);
            }
            dieFatal();
        }
    }
}
