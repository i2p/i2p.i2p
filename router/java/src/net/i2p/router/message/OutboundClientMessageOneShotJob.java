package net.i2p.router.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.client.SendMessageOptions;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.PublicKey;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
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

/**
 * Send a client message out an outbound tunnel and into an inbound
 * tunnel on the target leaseSet.  This also (sometimes) bundles the sender's leaseSet and
 * a DeliveryStatusMessage (for ACKing any sessionTags used in the garlic).
 *
 * <p>
 * This class is where we make several important decisions about
 * what to send and what path to send it over. These decisions
 * will dramatically affect:
 * <ul>
 * <li>Local performance and outbound bandwidth usage
 * <li>Streaming performance and reliability
 * <li>Overall network performace and connection congestion
 * </ul>
 *
 * <p>
 * For the outbound message, we build and encrypt a garlic message,
 * after making the following decisions:
 * <ul>
 * <li>Whether to bundle our leaseset
 * <li>Whether to bundle session tags, and if so, how many
 * <li>Whether to bundle an encrypted DeliveryStatusMessage to be returned
 *     to us as an acknowledgement
 * </ul>
 *
 * <p>
 * Also, we make the following path selection decisions:
 * <ul>
 * <li>What outbound client tunnel of ours to use send the message out
 * <li>What inbound client tunnel of his (i.e. lease, chosen from his leaseset)
 *     to use to send the message in
 * <li>If a DeliveryStatusMessage is bundled, What inbound client tunnel of ours
 *     do we specify to receive it
 * </ul>
 *
 * <p>
 * Note that the 4th tunnel in the DeliveryStatusMessage's round trip (his outbound tunnel)
 * is not selected by us, it is chosen by the recipient.
 *
 * <p>
 * If a DeliveryStatusMessage is sent, a listener is registered to wait for its reply.
 * When a reply is received, or the timeout is reached, this is noted
 * and will influence subsequent bundling and path selection decisions.
 *
 * <p>
 * Path selection decisions are cached and reused if still valid and if
 * previous deliveries were apparently successful. This significantly
 * reduces out-of-order delivery and network connection congestion.
 * Caching is based on the local/remote destination pair.
 *
 * <p>
 * Bundling decisions, and both messaging and reply expiration times, are generally
 * set here but may be overridden by the client on a per-message basis.
 * Within clients, there may be overall settings or per-message settings.
 * The streaming lib also overrides defaults for some messages.
 * A datagram-based DHT application may need significantly different
 * settings than a streaming application. For an application such as
 * a bittorrent client that sends both types of traffic on the same tunnels,
 * it is important to tune the settings for efficiency and performance.
 * The per-session and per-message overrides are set via I2CP.
 *
 *
 */
public class OutboundClientMessageOneShotJob extends JobImpl {
    private final Log _log;
    private final OutboundCache _cache;
    private final long _overallExpiration;
    private final ClientMessage _clientMessage;
    private final MessageId _clientMessageId;
    private final int _clientMessageSize;
    private final Destination _from;
    private final Destination _to;
    private final String _toString;
    /** target destination's leaseSet, if known */
    private LeaseSet _leaseSet;
    /** Actual lease the message is being routed through */
    private Lease _lease;
    private final long _start;
    /** note we can succeed after failure, but not vice versa */
    private enum Result {NONE, FAIL, SUCCESS}
    private Result _finished = Result.NONE;
    private long _leaseSetLookupBegin;
    private TunnelInfo _outTunnel;
    private TunnelInfo _inTunnel;
    private boolean _wantACK;
    
    /**
     * Key used to cache things with, based on source + dest
     */
    private final OutboundCache.HashPair _hashPair;


    /**
     * final timeout (in milliseconds) that the outbound message will fail in.
     * This can be overridden in the router.config or the client's session config
     * (the client's session config takes precedence)
     */
    public final static String OVERALL_TIMEOUT_MS_PARAM = "clientMessageTimeout";
    private final static long OVERALL_TIMEOUT_MS_DEFAULT = 60*1000;
    private final static long OVERALL_TIMEOUT_MS_MIN = 8*1000;
    private final static long OVERALL_TIMEOUT_MS_MAX = 90*1000;
    private final static long LS_LOOKUP_TIMEOUT = 15*1000;
    private final static long OVERALL_TIMEOUT_NOLS_MIN = OVERALL_TIMEOUT_MS_MIN + LS_LOOKUP_TIMEOUT;
    private final static long REPLY_TIMEOUT_MS_MIN = OVERALL_TIMEOUT_MS_DEFAULT - 5*1000;
    
    /**
     * NOTE: Changed as of 0.9.2.
     *
     * Defaults to true.
     *
     * If the client's config specifies shouldBundleReplyInfo=true, messages sent from
     * that client to any peers will periodically include the sending destination's
     * current LeaseSet (allowing the recipient to reply without having to do a full
     * netDb lookup).  This should improve performance during the initial negotiations.
     *
     * For clients that do not publish their LeaseSet, this option must be true
     * for any reply to be possible.
     *
     * Setting to "false" may save significant outbound bandwidth, especially if
     * the client is configured with a large number of inbound tunnels (Leases).
     * If replies are still required, this may shift the bandwidth burden to
     * the far-end client and the floodfill.
     *
     * There are several cases where "false" is may be appropriate:
     * <ul><li>
     * Unidirectional communication, no reply required
     * <li>
     * LeaseSet is published and higher reply latency is acceptable
     * <li>
     * LeaseSet is published, client is a "server", all connections are inbound
     * so the connecting far-end destination obviously has the leaseset already.
     * Connections are either short, or it is acceptable for latency on a long-lived
     * connection to temporarily increase while the other end re-fetches the LeaseSet
     * after expiration.
     * HTTP servers may fit these requirements.
     * </li></ul>
     */
    public static final String BUNDLE_REPLY_LEASESET = "shouldBundleReplyInfo";
    
    private static final int REPLY_REQUEST_INTERVAL = 60*1000;

    /**
     * Send the sucker
     */
    public OutboundClientMessageOneShotJob(RouterContext ctx, OutboundCache cache, ClientMessage msg) {
        super(ctx);
        _start = ctx.clock().now();
        _cache = cache;
        _log = ctx.logManager().getLog(OutboundClientMessageOneShotJob.class);
        
        long timeoutMs = OVERALL_TIMEOUT_MS_DEFAULT;
        _clientMessage = msg;
        _clientMessageId = msg.getMessageId();
        _clientMessageSize = msg.getPayload().getSize();
        _from = msg.getFromDestination();
        _to = msg.getDestination();
        Hash toHash = _to.calculateHash();
        _hashPair = new OutboundCache.HashPair(_from.calculateHash(), toHash);
        _toString = toHash.toBase64().substring(0,4);
        // we look up here rather than runJob() so we may adjust the timeout
        _leaseSet = ctx.netDb().lookupLeaseSetLocally(toHash);
        
        // use expiration requested by client if available, otherwise session config,
        // otherwise router config, otherwise default
        long overallExpiration = msg.getExpiration();
        if (overallExpiration > 0) {
            if (overallExpiration < 24*60*60*1000l) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Client bug - interval instead of timestamp " + overallExpiration);
                overallExpiration += _start;
            }
            // Unless it's already expired, set a min and max expiration
            if (overallExpiration > _start) {
                // extend the minimum timeout if we must lookup LS
                long minTimeout = _leaseSet != null ? OVERALL_TIMEOUT_MS_MIN : OVERALL_TIMEOUT_NOLS_MIN;
                overallExpiration = Math.max(overallExpiration, _start + minTimeout);
                overallExpiration = Math.min(overallExpiration, _start + OVERALL_TIMEOUT_MS_MAX);
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": Message Expiration (ms): " + (overallExpiration - _start));
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": Expired before we got to it");
                // runJob() will call dieFatal()
            }
        } else {
            // undocumented until 0.9.14, unused
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
            overallExpiration = timeoutMs + _start;
           if (_log.shouldLog(Log.DEBUG))
               _log.debug(getJobId() + " Default Expiration (ms): " + timeoutMs);
        }
        _overallExpiration = overallExpiration;
    }
    
    /** call once only */
    public static void init(RouterContext ctx) {
        ctx.statManager().createFrequencyStat("client.sendMessageFailFrequency", "How often does a client fail to send a message?", "ClientMessages", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.sendMessageSize", "How large are messages sent by the client?", "ClientMessages", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRequiredRateStat("client.sendAckTime", "Message round trip time (ms)", "ClientMessages", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.timeoutCongestionTunnel", "How lagged our tunnels are when a send times out?", "ClientMessages", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.timeoutCongestionMessage", "How fast we process messages locally when a send times out?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.timeoutCongestionInbound", "How much faster we are receiving data than our average bps when a send times out?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        //ctx.statManager().createRateStat("client.leaseSetFoundLocally", "How often we tried to look for a leaseSet and found it locally?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.leaseSetFoundRemoteTime", "How long we tried to look for a remote leaseSet (when we succeeded)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.leaseSetFailedRemoteTime", "How long we tried to look for a remote leaseSet (when we failed)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.dispatchPrepareTime", "How long until we've queued up the dispatch job (since we started)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.dispatchTime", "How long until we've dispatched the message (since we started)?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.dispatchSendTime", "How long the actual dispatching takes?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.dispatchNoTunnels", "How long after start do we run out of tunnels to send/receive with?", "ClientMessages", new long[] { 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        ctx.statManager().createRateStat("client.dispatchNoACK", "Repeated message sends to a peer (no ack required)", "ClientMessages", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l });
        // for HandleGarlicMessageJob / GarlicMessageReceiver
        ctx.statManager().createRateStat("crypto.garlic.decryptFail", "How often garlic messages are undecryptable", "Encryption", new long[] { 5*60*1000, 60*60*1000, 24*60*60*1000 });
    }

    public String getName() { return "Outbound client message"; }
    
    public void runJob() {
        long now = getContext().clock().now();
        if (now >= _overallExpiration) {
            dieFatal(MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED);
            return;
        }
        if (_leaseSet != null && _leaseSet.getType() == DatabaseEntry.KEY_TYPE_META_LS2) {
            // can't send to a meta LS
            dieFatal(MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET);
            return;
        }

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(getJobId() + ": Send outbound client message job beginning" +
        //               ": preparing to search for the leaseSet for " + _toString);
        SendJob success = new SendJob(getContext());
        // set in constructor
        //_leaseSet = getContext().netDb().lookupLeaseSetLocally(key);
        if (_leaseSet != null) {
            //getContext().statManager().addRateData("client.leaseSetFoundLocally", 1);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Send outbound client message - leaseSet found locally for " + _toString);

            if (!_leaseSet.isCurrent(Router.CLOCK_FUDGE_FACTOR / 4)) {
                // If it's about to expire, refetch in the background, we'll
                // probably need it again. This will prevent stalls later.
                // We don't know if the other end is actually publishing his LS, so this could be a waste of time.
                // When we move to LS2, we will have a bit that tells us if it is published.
                if (_log.shouldWarn()) {
                    long exp = now - _leaseSet.getLatestLeaseDate();
                    _log.warn(getJobId() + ": leaseSet expired " + DataHelper.formatDuration(exp) + " ago, firing search: " + _leaseSet.getHash().toBase32());
                }
                getContext().netDb().lookupLeaseSetRemotely(_leaseSet.getHash(), _from.calculateHash());
            }

            success.runJob();
        } else {
            _leaseSetLookupBegin = getContext().clock().now();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getJobId() + ": Send outbound client message - sending off leaseSet lookup job for " + _toString);
            LookupLeaseSetFailedJob failed = new LookupLeaseSetFailedJob(getContext());
            Hash key = _to.calculateHash();
            getContext().netDb().lookupLeaseSet(key, success, failed, LS_LOOKUP_TIMEOUT, _from.calculateHash());
        }
    }
    
    /**
     *  @param force to force including a reply lease set
     *  @return lease set or null if we should not send the lease set
     */
    private LeaseSet getReplyLeaseSet(boolean force) {
        LeaseSet newLS = getContext().netDb().lookupLeaseSetLocally(_from.calculateHash());
        if (newLS == null)
            return null;   // punt

        // If the last leaseSet we sent him is still good, don't bother sending again
            LeaseSet ls = _cache.leaseSetCache.put(_hashPair, newLS);
            if (!force) {
                if (ls != null) {
                    if (ls.equals(newLS)) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info(getJobId() + ": Found in cache - NOT including reply leaseset for " + _toString); 
                            return null;
                    } else {
                        if (_log.shouldLog(Log.INFO))
                            _log.info(getJobId() + ": Expired from cache - reply leaseset for " + _toString); 
                    }
                }
            }

        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Added to cache - reply leaseset for " + _toString); 
        return newLS;
    }
    
    /**
     *  Send a message to a lease.
     *  Note: This is generally run inline by runJob() above.
     *  It is only run on the job queue after a LS lookup.
     */
    private class SendJob extends JobImpl {
        public SendJob(RouterContext enclosingContext) { 
            super(enclosingContext);
        }

        public String getName() { return "Outbound client message delayed send"; }

        public void runJob() {
            if (_leaseSetLookupBegin > 0) {
                long lookupTime = getContext().clock().now() - _leaseSetLookupBegin;
                getContext().statManager().addRateData("client.leaseSetFoundRemoteTime", lookupTime);
            }
            _wantACK = false;
            int rc = getNextLease();
            if (rc == 0) {
                send();
            } else {
                // shouldn't happen
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to send on a random lease, as getNext returned null (to=" + _toString + ")");
                dieFatal(rc);
            }
        }
    }
    
    /**
     *  Choose a lease from his leaseset to send the message to. Sets _lease.
     *  Sets _wantACK if it's new or changed.
     *  @return 0 on success, or a MessageStatusMessage failure code
     */
    private int getNextLease() {
        // set in runJob if found locally
        if (_leaseSet == null) {
            _leaseSet = getContext().netDb().lookupLeaseSetLocally(_to.calculateHash());
            if (_leaseSet == null) {
                // shouldn't happen
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getJobId() + ": Lookup locally didn't find the leaseSet for " + _toString);
                return MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET;
            } 
        } 
        if (_leaseSet.getType() == DatabaseEntry.KEY_TYPE_META_LS2) {
            // can't send to a meta LS
            return MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET;
        }

        // Use the same lease if it's still good
        // Even if _leaseSet changed, _leaseSet.getEncryptionKey() didn't...
            _lease = _cache.leaseCache.get(_hashPair);
            if (_lease != null) {
                // if outbound tunnel length == 0 && lease.firsthop.isBacklogged() don't use it ??
                if (!_lease.isExpired(Router.CLOCK_FUDGE_FACTOR / 4)) {
                    // see if the current leaseSet contains the old lease, so that if the dest removes
                    // it (due to failure for example) we won't continue to use it.
                    for (int i = 0; i < _leaseSet.getLeaseCount(); i++) {
                        Lease lease = _leaseSet.getLease(i);
                        // Don't use Lease.equals(), as that compares expiration time too,
                        // and that time may change in subsequent publication
                        //if (_lease.equals(lease)) {
                        if (_lease.getTunnelId().equals(lease.getTunnelId()) &&
                            _lease.getGateway().equals(lease.getGateway())) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info(getJobId() + ": Found in cache - lease for " + _toString); 
                            return 0;
                        }
                    }
                }
                // remove only if still equal to _lease (concurrent)
                _cache.leaseCache.remove(_hashPair, _lease);
                if (_log.shouldLog(Log.INFO))
                    _log.info(getJobId() + ": Expired from cache - lease for " + _toString); 
            }

        // get the possible leases
        List<Lease> leases = new ArrayList<Lease>(_leaseSet.getLeaseCount());
        // first try to get ones that really haven't expired
        for (int i = 0; i < _leaseSet.getLeaseCount(); i++) {
            Lease lease = _leaseSet.getLease(i);
            if (!lease.isExpired(Router.CLOCK_FUDGE_FACTOR / 4))
                leases.add(lease);
        }

        if (leases.isEmpty()) {
            // TODO if _lease != null, fire off
            // a lookup ? KNDF will keep giving us the current ls until CLOCK_FUDGE_FACTOR
            // try again with a fudge factor
            for (int i = 0; i < _leaseSet.getLeaseCount(); i++) {
                Lease lease = _leaseSet.getLease(i);
                if (!lease.isExpired(Router.CLOCK_FUDGE_FACTOR))
                    leases.add(lease);
            }
        }
        
        if (leases.isEmpty()) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getJobId() + ": No leases found from: " + _leaseSet);
            return MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET;
        }
        
        // randomize the ordering (so leases with equal # of failures per next 
        // sort are randomly ordered)
        Collections.shuffle(leases, getContext().random());
        
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
            Lease l = leases.get(i);
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
            _lease = leases.get(0);
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": All leases are unreachable for " + _toString); 
        }
        _cache.leaseCache.put(_hashPair, _lease);
        if (_log.shouldLog(Log.INFO))
            _log.info(getJobId() + ": Added to cache - lease for " + _toString); 
        _wantACK = true;
        return 0;
    }

    
    /** 
     * We couldn't even find the leaseSet, so die 
     */
    private class LookupLeaseSetFailedJob extends JobImpl {
        public LookupLeaseSetFailedJob(RouterContext enclosingContext)  {
            super(enclosingContext);
        }
        public String getName() { return "Outbound client message lease lookup failed"; }
        public void runJob() {
            if (_leaseSetLookupBegin > 0) {
                long lookupTime = getContext().clock().now() - _leaseSetLookupBegin;
                getContext().statManager().addRateData("client.leaseSetFailedRemoteTime", lookupTime);
            }
            

            int cause;
            if (getContext().netDb().isNegativeCachedForever(_to.calculateHash())) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to send to " + _toString + " because the sig type is unsupported");
                cause = MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to send to " + _toString + " because we couldn't find their leaseSet");
                cause = MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET;
            }

            dieFatal(cause);
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
    private void send() {
        synchronized(this) {
            if (_finished != Result.NONE) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(OutboundClientMessageOneShotJob.this.getJobId() 
                              + ": SEND-AFTER-" + _finished);
                return;
            }
        }
        long now = getContext().clock().now();
        if (now >= _overallExpiration) {
            dieFatal(MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED);
            return;
        }

        _outTunnel = selectOutboundTunnel(_to);
        if (_outTunnel == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Could not find any outbound tunnels to send the payload through... this might take a while");
            getContext().statManager().addRateData("client.dispatchNoTunnels", now - _start);
            dieFatal(MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS);
            return;
        }

        // boolean wantACK = _wantACK || existingTags <= 30 || getContext().random().nextInt(100) < 5;
        // what's the point of 5% random? possible improvements or replacements:
        // DONE (getNextLease() is called before this): wantACK if we changed their inbound lease (getNextLease() sets _wantACK)
        // DONE (selectOutboundTunnel() moved above here): wantACK if we changed our outbound tunnel (selectOutboundTunnel() sets _wantACK)
        // DONE (added new cache): wantACK if we haven't in last 1m (requires a new static cache probably)

        Long lastReplyRequestSent = _cache.lastReplyRequestCache.get(_hashPair);
        boolean shouldRequestReply = lastReplyRequestSent == null ||
                                     lastReplyRequestSent.longValue() < now - REPLY_REQUEST_INTERVAL;

        int sendFlags = _clientMessage.getFlags();
        // Per-message flag > 0 overrides per-session option
        int tagsRequired = SendMessageOptions.getTagThreshold(sendFlags);
        boolean wantACK = _wantACK ||
                          shouldRequestReply ||
                          GarlicMessageBuilder.needsTags(getContext(), _leaseSet.getEncryptionKey(),
                                                         _from.calculateHash(), tagsRequired);
        
        LeaseSet replyLeaseSet;
        // Per-message flag == false overrides session option which is default true
        String allow = _clientMessage.getSenderConfig().getOptions().getProperty(BUNDLE_REPLY_LEASESET);
        boolean allowLeaseBundle = SendMessageOptions.getSendLeaseSet(sendFlags) &&
                                   (allow == null || Boolean.parseBoolean(allow));
        if (allowLeaseBundle) {
            // If we want an ack, bundle a leaseSet...
            //replyLeaseSet = getReplyLeaseSet(wantACK);
            // Only when necessary. We don't need to force.
            // ACKs find their own way back, they don't need a leaseset.
            replyLeaseSet = getReplyLeaseSet(false);
            // ... and vice versa  (so we know he got it)
            if (replyLeaseSet != null)
                wantACK = true;
        } else {
            replyLeaseSet = null;
        }

        long token;
        if (wantACK) {
            _cache.lastReplyRequestCache.put(_hashPair, Long.valueOf(now));
            token = getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE);
            // 0.9.38 change to DESTINATION reply delivery
            // NOPE! Rejected in InboundMessageDistributor
            _inTunnel = selectInboundTunnel();
        } else {
            token = -1;
        }

        PayloadGarlicConfig clove = buildClove();
        if (clove == null) {
            dieFatal(MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION);
            return;
        }
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(getJobId() + ": Clove built to " + _toString);

        PublicKey key = _leaseSet.getEncryptionKey();
        SessionKey sessKey = new SessionKey();
        Set<SessionTag> tags = new HashSet<SessionTag>();

        // Per-message flag > 0 overrides per-session option
        int tagsToSend = SendMessageOptions.getTagsToSend(sendFlags);
        GarlicMessage msg = OutboundClientMessageJobHelper.createGarlicMessage(getContext(), token, 
                                                                               _overallExpiration, key, 
                                                                               clove, _from.calculateHash(), 
                                                                               _to, _inTunnel, tagsToSend,
                                                                               tagsRequired, sessKey, tags, 
                                                                               wantACK, replyLeaseSet);
        if (msg == null) {
            // set to null if there are no tunnels to ack the reply back through
            // (should we always fail for this? or should we send it anyway, even if
            // we dont receive the reply? hmm...)
            if (_log.shouldLog(Log.WARN))
                _log.warn(getJobId() + ": Unable to create the garlic message (no tunnels left or too lagged) to " + _toString);
            getContext().statManager().addRateData("client.dispatchNoTunnels", now - _start);            
            dieFatal(MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS);
            return;
        }
        
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(getJobId() + ": send() - token expected " + token + " to " + _toString);
        
        SendSuccessJob onReply = null;
        SendTimeoutJob onFail = null;
        ReplySelector selector = null;
        if (wantACK) {
            TagSetHandle tsh = null;
            if (!tags.isEmpty()) {
                    SessionKeyManager skm = getContext().clientManager().getClientSessionKeyManager(_from.calculateHash());
                    if (skm != null)
                        tsh = skm.tagsDelivered(_leaseSet.getEncryptionKey(), sessKey, tags);
            }
            onReply = new SendSuccessJob(getContext(), sessKey, tsh);
            onFail = new SendTimeoutJob(getContext(), sessKey, tsh);
            long expiration = Math.max(_overallExpiration, _start + REPLY_TIMEOUT_MS_MIN);
            selector = new ReplySelector(token, expiration);
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getJobId() + ": Sending msg out " + _outTunnel.getSendTunnelId(0) + " to " 
                           + _toString + " at "
                           + _lease.getTunnelId() + " on " 
                           + _lease.getGateway());

        DispatchJob dispatchJob = new DispatchJob(getContext(), msg, selector, onReply, onFail);
        //if (false) // dispatch may take 100+ms, so toss it in its own job
        //    getContext().jobQueue().addJob(dispatchJob);
        //else
        dispatchJob.runJob();
        getContext().statManager().addRateData("client.dispatchPrepareTime", now - _start);
        if (!wantACK)
            getContext().statManager().addRateData("client.dispatchNoACK", 1);
    }

    /**
     *  Note: This is run inline by send(), not on the job queue.
     *  TODO replace with a method
     */
    private class DispatchJob extends JobImpl {
        private final GarlicMessage _msg;
        private final ReplySelector _selector;
        private final SendSuccessJob _replyFound;
        private final SendTimeoutJob _replyTimeout;

        /**
         *  @param sel may be null
         *  @param success non-null if sel non-null
         *  @param timeout non-null if sel non-null
         */
        public DispatchJob(RouterContext ctx, GarlicMessage msg, ReplySelector sel,
                           SendSuccessJob success, SendTimeoutJob timeout) {
            super(ctx);
            _msg = msg;
            _selector = sel;
            _replyFound = success;
            _replyTimeout = timeout;
        }

        public String getName() { return "Outbound client message dispatch"; }

        public void runJob() {
            if (_selector != null) {
                if (_overallExpiration >= _selector.getExpiration()) {
                    // We use the Message Registry to call our timeout when the selector expires
                    // Either the success or timeout job will fire, never both.
                    getContext().messageRegistry().registerPending(_selector, _replyFound, _replyTimeout);
                    if (_log.shouldLog(Log.INFO))
                        _log.info(OutboundClientMessageOneShotJob.this.getJobId() +
                                  ": Reply selector expires " +
                                  DataHelper.formatDuration(_overallExpiration - _selector.getExpiration()) +
                                  " before message, using selector only");
                } else {
                    // We put our own timeout on the job queue before the selector expires,
                    // so we can keep waiting for the reply and restore the tags (success-after-failure)
                    // The timeout job will always fire, even after success.
                    // We don't bother cancelling the timeout job as JobQueue.removeJob() is a linear search
                    getContext().messageRegistry().registerPending(_selector, _replyFound, null);
                    _replyTimeout.getTiming().setStartAfter(_overallExpiration);
                    getContext().jobQueue().addJob(_replyTimeout);
                    if (_log.shouldLog(Log.INFO))
                        _log.info(OutboundClientMessageOneShotJob.this.getJobId() +
                                  ": Reply selector expires " +
                                  DataHelper.formatDuration(_selector.getExpiration() - _overallExpiration) +
                                  " after message, queueing separate timeout job");
                }
            }
            if (_log.shouldLog(Log.INFO))
                _log.info(OutboundClientMessageOneShotJob.this.getJobId() +
                          ": Dispatching message to " + _toString + ": " + _msg);
            long before = getContext().clock().now();

            // Note we do not have a first hop fail job, or a success job, here,
            // as we do in e.g. build handler.
            // Nor do we ever send a STATUS_SEND_BEST_EFFORT_SUCCESS (when no selector)
            getContext().tunnelDispatcher().dispatchOutbound(_msg, _outTunnel.getSendTunnelId(0), _lease.getTunnelId(), _lease.getGateway());
            long dispatchSendTime = getContext().clock().now() - before; 
            //if (_log.shouldLog(Log.INFO))
            //    _log.info(OutboundClientMessageOneShotJob.this.getJobId() +
            //              ": Dispatching message to " + _toString + " complete");
            // avg. 6 ms on a 2005-era PC
            getContext().statManager().addRateData("client.dispatchTime", getContext().clock().now() - _start);
            // avg. 1 ms on a 2005-era PC
            getContext().statManager().addRateData("client.dispatchSendTime", dispatchSendTime);
        }
    }

    /**
     * Called on failure to give us a better chance of success next time.
     * Of course this is probably 60s too late.
     * And we could pick the bad ones at random again.
     * Or remove entries that were sent and succeeded after this was sent but before this failed.
     * But it's a start.
     */
    private void clearCaches() {
        _cache.clearCaches(_hashPair, _lease, _inTunnel, _outTunnel);
    }

    /**
     *  Choose our outbound tunnel to send the message through.
     *  Sets _wantACK if it's new or changed.
     *  @return the tunnel or null on failure
     */
    private TunnelInfo selectOutboundTunnel(Destination to) {
        TunnelInfo tunnel;
        synchronized (_cache.tunnelCache) {
            /**
             * If old tunnel is valid and no longer backlogged, use it.
             * This prevents an active anonymity attack, where a peer could tell
             * if you were the originator by backlogging the tunnel, then removing the
             * backlog and seeing if traffic came back or not.
             */
            tunnel = _cache.backloggedTunnelCache.get(_hashPair);
            if (tunnel != null) {
                if (getContext().tunnelManager().isValidTunnel(_from.calculateHash(), tunnel)) {
                    if (!getContext().commSystem().isBacklogged(tunnel.getPeer(1))) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Switching back to tunnel " + tunnel + " for " + _toString); 
                        _cache.backloggedTunnelCache.remove(_hashPair);
                        _cache.tunnelCache.put(_hashPair, tunnel);
                        _wantACK = true;
                        return tunnel;
                    }  // else still backlogged
                } else // no longer valid
                    _cache.backloggedTunnelCache.remove(_hashPair);
            }
            // Use the same tunnel unless backlogged
            tunnel = _cache.tunnelCache.get(_hashPair);
            if (tunnel != null) {
                if (getContext().tunnelManager().isValidTunnel(_from.calculateHash(), tunnel)) {
                    if (tunnel.getLength() <= 1 || !getContext().commSystem().isBacklogged(tunnel.getPeer(1)))
                        return tunnel;
                    // backlogged
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Switching from backlogged " + tunnel + " for " + _toString); 
                    _cache.backloggedTunnelCache.put(_hashPair, tunnel);
                } // else no longer valid
                _cache.tunnelCache.remove(_hashPair);
            }
            // Pick a new tunnel
            tunnel = selectOutboundTunnel();
            if (tunnel != null)
                _cache.tunnelCache.put(_hashPair, tunnel);
            _wantACK = true;
        }
        return tunnel;
    }

    /**
     * Pick an arbitrary outbound tunnel to send the message through, or null if
     * there aren't any around
     *
     * Rather than pick one at random, pick the "closest" to the lease,
     * to minimize network OBEP - IBGW connections?
     * This would also eliminate a connection when OBEP == IBGW.
     * Anonymity issues?
     */
    private TunnelInfo selectOutboundTunnel() {
        // hurts reliability? let's try picking at random again
        //Hash gw = _lease.getGateway();
        //return getContext().tunnelManager().selectOutboundTunnel(_from.calculateHash(), gw);
        return getContext().tunnelManager().selectOutboundTunnel(_from.calculateHash());
    }

    /**
     * Pick an arbitrary inbound tunnel for any deliveryStatusMessage to come back in
     *
     */
    private TunnelInfo selectInboundTunnel() {
        // Use tunnel EP closest to his hash, as a simple cache to minimize connections
        return getContext().tunnelManager().selectInboundTunnel(_from.calculateHash(), _to.calculateHash());
    }
    
    /**
     * give up the ghost, this message just aint going through.  tell the client.
     *
     * this is safe to call multiple times (only tells the client once)
     */
/****
    private void dieFatal() {
        dieFatal(MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE);
    }
****/

    /**
     * give up the ghost, this message just aint going through.  tell the client.
     *
     * this is safe to call multiple times (only tells the client once)
     * We may still succeed later.
     */
    private void dieFatal(int status) {
        // never fail twice or fail after success
        synchronized(this) {
            if (_finished != Result.NONE) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(OutboundClientMessageOneShotJob.this.getJobId() 
                              + ": FAIL-AFTER-" + _finished);
                return;
            }
            _finished = Result.FAIL;
        }
        
        long sendTime = getContext().clock().now() - _start;
        if (_log.shouldLog(Log.WARN))
            _log.warn(getJobId() + ": Send failed (cause: " + status + ") " + _clientMessageId + " to " + _toString +
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
        getContext().clientManager().messageDeliveryStatusUpdate(_from, _clientMessageId,
                                                                 _clientMessage.getMessageNonce(), status);
        getContext().statManager().updateFrequency("client.sendMessageFailFrequency");
    }
    
    /**
     *  Build the payload clove that will be used for all of the messages,
     *  placing the clove in the status structure.
     *
     *  @return null on failure
     */
    private PayloadGarlicConfig buildClove() {
        PayloadGarlicConfig clove = new PayloadGarlicConfig();
        
        DeliveryInstructions instructions = new DeliveryInstructions();
        instructions.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_DESTINATION);
        instructions.setDestination(_to.calculateHash());
        
        // defaults
        //instructions.setDelayRequested(false);
        //instructions.setDelaySeconds(0);
        //instructions.setEncrypted(false);
        
        clove.setCertificate(Certificate.NULL_CERT);
        clove.setDeliveryInstructions(instructions);
        clove.setExpiration(OVERALL_TIMEOUT_MS_DEFAULT+getContext().clock().now());
        clove.setId(getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE));
        
        DataMessage msg = new DataMessage(getContext());
        Payload p = _clientMessage.getPayload();
        if (p == null)
            return null;
        byte d[] = p.getEncryptedData();
        if (d == null)
            return null;
        msg.setData(d);
        msg.setMessageExpiration(clove.getExpiration());
        
        clove.setPayload(msg);
        // defaults
        //clove.setRecipientPublicKey(null);
        //clove.setRequestAck(false);
        
        
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(getJobId() + ": Built payload clove with id " + clove.getId());
        return clove;
    }
    
    /**
     * Keep an eye out for any of the delivery status message tokens that have been
     * sent down the various tunnels to deliver this message
     *
     */
    private static class ReplySelector implements MessageSelector {
        private final long _pendingToken;
        private final long _expiration;

        public ReplySelector(long token, long expiration) {
            _pendingToken = token;
            _expiration = expiration;
        }
        
        public boolean continueMatching() { 
            return false; 
        }

        public long getExpiration() { return _expiration; }
        
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
            return "OCMOSJ.RS waiting for token " + _pendingToken + " until " + new Date(_expiration);
        }
    }
    
    /**
     * Called after we get a confirmation that the message was delivered safely.
     * This may be run after failure.
     *
     */
    private class SendSuccessJob extends JobImpl implements ReplyJob {
        private final SessionKey _key;
        private final TagSetHandle _tags;
        
        /**
         * Create a new success job that will be fired when the message encrypted with
         * the given session key and bearing the specified tags are confirmed delivered.
         * This is only instantiated if we are expecting a reply.
         *
         * @param key may be null
         * @param tags may be null
         */
        public SendSuccessJob(RouterContext enclosingContext, SessionKey key, TagSetHandle tags) {
            super(enclosingContext);
            _key = key;
            _tags = tags;
        }
        
        public String getName() { return "Outbound client message send success"; }

        /**
         * May be run after SendTimeoutJob, will re-add the tags.
         */
        public void runJob() {
            // do we leak tags here?
            Result old;
            // never succeed twice but we can succeed after fail
            synchronized(OutboundClientMessageOneShotJob.this) {
                old = _finished;
                if (old == Result.SUCCESS) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(OutboundClientMessageOneShotJob.this.getJobId() 
                                  + ": SUCCESS-AFTER-SUCCESS");
                    return;
                }
                _finished = Result.SUCCESS;
                // in sync block so we don't race with SendTimeoutJob
                if (_key != null && _tags != null && _leaseSet != null) {
                    SessionKeyManager skm = getContext().clientManager().getClientSessionKeyManager(_from.calculateHash());
                    if (skm != null)
                        skm.tagsAcked(_leaseSet.getEncryptionKey(), _key, _tags);
                }
            }

            long sendTime = getContext().clock().now() - _start;
            if (old == Result.FAIL) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(OutboundClientMessageOneShotJob.this.getJobId() 
                           + ": SUCCESS-AFTER-TIMEOUT " + _clientMessageId
                           + " acked by DSM after " + sendTime + "ms");
            } else if (_log.shouldLog(Log.INFO)) {
                _log.info(OutboundClientMessageOneShotJob.this.getJobId() 
                           + ": SUCCESS " + _clientMessageId
                           + " acked by DSM after " + sendTime + "ms");
            }            
            
            //long dataMsgId = _cloveId;   // fake ID 99999
            getContext().messageHistory().sendPayloadMessage(99999, true, sendTime);
            getContext().clientManager().messageDeliveryStatusUpdate(_from, _clientMessageId, _clientMessage.getMessageNonce(),
                                                                     MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS);
            // unused
            //_lease.setNumSuccess(_lease.getNumSuccess()+1);
        
            int size = _clientMessageSize;
            
            getContext().statManager().addRateData("client.sendAckTime", sendTime);
            getContext().statManager().addRateData("client.sendMessageSize", _clientMessageSize, sendTime);
            if (_outTunnel != null) {
                if (_outTunnel.getLength() > 0)
                    size = ((size + 1023) / 1024) * 1024; // messages are in ~1KB blocks
                
                // skip ourselves at first hop
                for (int i = 1; i < _outTunnel.getLength(); i++) {
                    getContext().profileManager().tunnelTestSucceeded(_outTunnel.getPeer(i), sendTime);
                    getContext().profileManager().tunnelDataPushed(_outTunnel.getPeer(i), sendTime, size);
                }
                _outTunnel.incrementVerifiedBytesTransferred(size);
            }
            if (_inTunnel != null) {
                // skip ourselves at last hop
                for (int i = 0; i < _inTunnel.getLength() - 1; i++) {
                    getContext().profileManager().tunnelTestSucceeded(_inTunnel.getPeer(i), sendTime);
                }
            }
        }

        public void setMessage(I2NPMessage msg) {}
    }
    
    /**
     * Fired after the basic timeout for sending through the given tunnel has been reached.
     * We'll accept successes later, but won't expect them
     *
     */
    private class SendTimeoutJob extends JobImpl {
        private final SessionKey _key;
        private final TagSetHandle _tags;

        /**
         * Create a new timeout job that will be fired when the reply is not received.
         * This is only instantiated if we are expecting a reply.
         *
         * @param key may be null
         * @param tags may be null
         */
        public SendTimeoutJob(RouterContext enclosingContext, SessionKey key, TagSetHandle tags) {
            super(enclosingContext);
            _key = key;
            _tags = tags;
        }
        
        public String getName() { return "Outbound client message send timeout"; }

        /**
         * May be run after SendSuccessJob, will have no effect.
         */
        public void runJob() {
            Result old;
            // never fail after success
            synchronized(OutboundClientMessageOneShotJob.this) {
                old = _finished;
                if (old == Result.SUCCESS) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(OutboundClientMessageOneShotJob.this.getJobId() 
                                  + ": TIMEOUT-AFTER-SUCCESS");
                    return;
                }
                // in sync block so we don't fail after success
                if (_key != null && _tags != null && _leaseSet != null) {
                    SessionKeyManager skm = getContext().clientManager().getClientSessionKeyManager(_from.calculateHash());
                    if (skm != null)
                        skm.failTags(_leaseSet.getEncryptionKey(), _key, _tags);
                }
            }
            if (old == Result.NONE)
                dieFatal(MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE);
        }
    }
}
