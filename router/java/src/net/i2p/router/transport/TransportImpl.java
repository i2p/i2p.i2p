package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

/**
 * Defines a way to send a message to another peer and start listening for messages
 *
 */
public abstract class TransportImpl implements Transport {
    private final Log _log;
    private TransportEventListener _listener;
    protected final List<RouterAddress> _currentAddresses;
    // Only used by NTCP. SSU does not use. See send() below.
    private final BlockingQueue<OutNetMessage> _sendPool;
    protected final RouterContext _context;
    /** map from routerIdentHash to timestamp (Long) that the peer was last unreachable */
    private final Map<Hash, Long>  _unreachableEntries;
    private final Map<Hash, Long> _wasUnreachableEntries;
    private final Set<InetAddress> _localAddresses;
    /** global router ident -> IP */
    private static final Map<Hash, byte[]> _IPMap;

    private static final long UNREACHABLE_PERIOD = 5*60*1000;
    private static final long WAS_UNREACHABLE_PERIOD = 30*60*1000;

    static {
        long maxMemory = SystemVersion.getMaxMemory();
        long min = 512;
        long max = 4096;
        // 1024 nominal for 128 MB
        int size = (int) Math.max(min, Math.min(max, 1 + (maxMemory / (128*1024))));
        _IPMap = new LHMCache<Hash, byte[]>(size);
    }

    /**
     * Initialize the new transport
     *
     */
    public TransportImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(TransportImpl.class);

        _context.statManager().createRateStat("transport.sendMessageFailureLifetime", "How long the lifetime of messages that fail are?", "Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRequiredRateStat("transport.sendMessageSize", "Size of sent messages (bytes)", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRequiredRateStat("transport.receiveMessageSize", "Size of received messages (bytes)", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.receiveMessageTime", "How long it takes to read a message?", "Transport", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.receiveMessageTimeSlow", "How long it takes to read a message (when it takes more than a second)?", "Transport", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRequiredRateStat("transport.sendProcessingTime", "Time to process and send a message (ms)", "Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        //_context.statManager().createRateStat("transport.sendProcessingTime." + getStyle(), "Time to process and send a message (ms)", "Transport", new long[] { 60*1000l });
        _context.statManager().createRateStat("transport.expiredOnQueueLifetime", "How long a message that expires on our outbound queue is processed", "Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l } );

        _currentAddresses = new CopyOnWriteArrayList<RouterAddress>();
        if (getStyle().equals("NTCP"))
            _sendPool = new ArrayBlockingQueue<OutNetMessage>(8);
        else
            _sendPool = null;
        _unreachableEntries = new HashMap<Hash, Long>(32);
        _wasUnreachableEntries = new HashMap<Hash, Long>(32);
        _localAddresses = new ConcurrentHashSet<InetAddress>(4);
        _context.simpleTimer2().addPeriodicEvent(new CleanupUnreachable(), 2 * UNREACHABLE_PERIOD, UNREACHABLE_PERIOD / 2);
    }

    /**
     * How many peers are we connected to?
     */
    public abstract int countPeers();

    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to or received a message from in the last five minutes.
     */
    public abstract int countActivePeers();

    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to in the last minute.
     *  Unused for anything, to be removed.
     */
    public abstract int countActiveSendPeers();

    /** ...and 50/100/150/200/250 for BW Tiers K/L/M/N/O */
    private static final int MAX_CONNECTION_FACTOR = 50;

    /** Per-transport connection limit */
    public int getMaxConnections() {
        if (_context.commSystem().isDummy())
            // testing
            return 0;
        String style = getStyle();
        // object churn
        String maxProp;
        if (style.equals("SSU"))
            maxProp = "i2np.udp.maxConnections";
        else if (style.equals("NTCP"))
            maxProp = "i2np.ntcp.maxConnections";
        else // shouldn't happen
            maxProp = "i2np." + style.toLowerCase(Locale.US) + ".maxConnections";
        int def = MAX_CONNECTION_FACTOR;
        // get it from here, not the RI, to avoid deadlock
        char bw = _context.router().getBandwidthClass();

            switch (bw) {
                case Router.CAPABILITY_BW12:
                case 'u':  // unknown
                default:
                    break;
                case Router.CAPABILITY_BW32:
                    def *= 2;
                    break;
                case Router.CAPABILITY_BW64:
                    def *= 3;
                    break;
                case Router.CAPABILITY_BW128:
                    def *= 5;
                    break;
                case Router.CAPABILITY_BW256:
                    def *= 9;
                    break;
                case Router.CAPABILITY_BW512:
                    def *= 11;
                    break;
                case Router.CAPABILITY_BW_UNLIMITED:
                    def *= 14;
                    break;
            }

        if (_context.netDb().floodfillEnabled()) {
            // && !SystemVersion.isWindows()) {
            def *= 17; def /= 10;
        }
        // increase limit for SSU, for now
        if (style.equals("SSU"))
            //def = def * 3 / 2;
            def *= 3;
        return _context.getProperty(maxProp, def);
    }

    private static final int DEFAULT_CAPACITY_PCT = 75;

    /**
     * Can we initiate or accept a connection to another peer, saving some margin
     */
    public boolean haveCapacity() {
        return haveCapacity(DEFAULT_CAPACITY_PCT);
    }

    /** @param pct are we under x% 0-100 */
    public boolean haveCapacity(int pct) {
        return countPeers() < getMaxConnections() * pct / 100;
    }

    /**
     * Return our peer clock skews on a transport.
     * Vector composed of Long, each element representing a peer skew in seconds.
     * Dummy version. Transports override it.
     */
    public Vector<Long> getClockSkews() { return new Vector<Long>(); }

    public List<String> getMostRecentErrorMessages() { return Collections.emptyList(); }

    /**
     * Nonblocking call to pull the next outbound message
     * off the queue.
     *
     * Only used by NTCP. SSU does not call.
     *
     * @return the next message or null if none are available
     */
    protected OutNetMessage getNextMessage() {
        OutNetMessage msg = _sendPool.poll();
        if (msg != null)
            msg.beginSend();
        return msg;
    }

    /**
     * The transport is done sending this message
     *
     * @param msg message in question
     * @param sendSuccessful true if the peer received it
     */
    protected void afterSend(OutNetMessage msg, boolean sendSuccessful) {
        afterSend(msg, sendSuccessful, true, 0);
    }
    /**
     * The transport is done sending this message
     *
     * @param msg message in question
     * @param sendSuccessful true if the peer received it
     * @param allowRequeue true if we should try other transports if available
     */
    protected void afterSend(OutNetMessage msg, boolean sendSuccessful, boolean allowRequeue) {
        afterSend(msg, sendSuccessful, allowRequeue, 0);
    }
    /**
     * The transport is done sending this message
     *
     * @param msg message in question
     * @param sendSuccessful true if the peer received it
     * @param msToSend how long it took to transfer the data to the peer
     */
    protected void afterSend(OutNetMessage msg, boolean sendSuccessful, long msToSend) {
        afterSend(msg, sendSuccessful, true, msToSend);
    }
    /**
     * The transport is done sending this message.  This is the method that actually
     * does all of the cleanup - firing off jobs, requeueing, updating stats, etc.
     *
     * @param msg message in question
     * @param sendSuccessful true if the peer received it
     * @param msToSend how long it took to transfer the data to the peer
     * @param allowRequeue true if we should try other transports if available
     */
    protected void afterSend(OutNetMessage msg, boolean sendSuccessful, boolean allowRequeue, long msToSend) {
        if (msg.getTarget() == null) {
            // Probably injected by the transport.
            // Bail out now as it will NPE in a dozen places below.
            return;
        }
        boolean log = false;
        if (sendSuccessful)
            msg.timestamp("afterSend(successful)");
        else
            msg.timestamp("afterSend(failed)");

        if (!sendSuccessful)
            msg.transportFailed(getStyle());

        if (msToSend > 1500) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getStyle() + " afterSend slow: " + (sendSuccessful ? "success " : "FAIL ")
                          + msg.getMessageSize() + " byte "
                          + msg.getMessageType() + ' ' + msg.getMessageId() + " to "
                          + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) + " took " + msToSend + " ms");
        }
        //if (true)
        //    _log.error("(not error) I2NP message sent? " + sendSuccessful + " " + msg.getMessageId() + " after " + msToSend + "/" + msg.getTransmissionTime());

        long lifetime = msg.getLifetime();
        if (lifetime > 3000) {
            int level = Log.INFO;
            if (!sendSuccessful)
                level = Log.DEBUG;
            if (_log.shouldLog(level))
                _log.log(level, getStyle() + " afterSend slow (" + (sendSuccessful ? "success " : "FAIL ")
                          + lifetime + "/" + msToSend + "): " + msg.getMessageSize() + " byte "
                          + msg.getMessageType() + " " + msg.getMessageId() + " from " + _context.routerHash().toBase64().substring(0,6)
                          + " to " + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) + ": " + msg.toString());
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getStyle() + " afterSend: " + (sendSuccessful ? "success " : "FAIL ")
                          + msg.getMessageSize() + " byte "
                          + msg.getMessageType() + " " + msg.getMessageId() + " from " + _context.routerHash().toBase64().substring(0,6)
                          + " to " + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) + "\n" + msg.toString());
        }

        if (sendSuccessful) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getStyle() + " Sent " + msg.getMessageType() + " successfully to "
                           + msg.getTarget().getIdentity().getHash().toBase64());
            Job j = msg.getOnSendJob();
            if (j != null)
                _context.jobQueue().addJob(j);
            log = true;
            msg.discardData();
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getStyle() + " Failed to send " + msg.getMessageType()
                          + " to " + msg.getTarget().getIdentity().getHash().toBase64()
                          + " (details: " + msg + ')');
            if (msg.getExpiration() < _context.clock().now())
                _context.statManager().addRateData("transport.expiredOnQueueLifetime", lifetime);

            if (allowRequeue) {
                if ( ( (msg.getExpiration() <= 0) || (msg.getExpiration() > _context.clock().now()) )
                     && (msg.getMessage() != null) ) {
                    // this may not be the last transport available - keep going
                    _context.outNetMessagePool().add(msg);
                    // don't discard the data yet!
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("No more time left (" + new Date(msg.getExpiration())
                                  + ", expiring without sending successfully the "
                                  + msg.getMessageType());
                    if (msg.getOnFailedSendJob() != null)
                        _context.jobQueue().addJob(msg.getOnFailedSendJob());
                    MessageSelector selector = msg.getReplySelector();
                    if (selector != null) {
                        _context.messageRegistry().unregisterPending(msg);
                    }
                    log = true;
                    msg.discardData();
                }
            } else {
                MessageSelector selector = msg.getReplySelector();
                if (_log.shouldLog(Log.INFO))
                    _log.info("Failed and no requeue allowed for a "
                              + msg.getMessageSize() + " byte "
                              + msg.getMessageType() + " message with selector " + selector, new Exception("fail cause"));
                if (msg.getOnFailedSendJob() != null)
                    _context.jobQueue().addJob(msg.getOnFailedSendJob());
                if (msg.getOnFailedReplyJob() != null)
                    _context.jobQueue().addJob(msg.getOnFailedReplyJob());
                if (selector != null)
                    _context.messageRegistry().unregisterPending(msg);
                log = true;
                msg.discardData();
            }
        }

        if (log) {
            /*
            String type = msg.getMessageType();
            // the udp transport logs some further details
            _context.messageHistory().sendMessage(type, msg.getMessageId(),
                                                  msg.getExpiration(),
                                                  msg.getTarget().getIdentity().getHash(),
                                                  sendSuccessful);
             */
        }

        long now = _context.clock().now();
        long sendTime = now - msg.getSendBegin();
        long allTime = now - msg.getCreated();
        if (allTime > 5*1000) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Took too long from preparation to afterSend(ok? " + sendSuccessful
                          + "): " + allTime + "ms/" + sendTime + "ms after failing on: "
                          + msg.getFailedTransports() + " and succeeding on " + getStyle());
            if ( (allTime > 60*1000) && (sendSuccessful) ) {
                // VERY slow
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Severe latency? More than a minute slow? " + msg.getMessageType()
                              + " of id " + msg.getMessageId() + " (send begin on "
                              + new Date(msg.getSendBegin()) + " / created on "
                              + new Date(msg.getCreated()) + "): " + msg);
                _context.messageHistory().messageProcessingError(msg.getMessageId(),
                                                                 msg.getMessageType(),
                                                                 "Took too long to send [" + allTime + "ms]");
            }
        }


        if (sendSuccessful) {
            // TODO fix this stat for SSU ticket #698
            _context.statManager().addRateData("transport.sendProcessingTime", lifetime);
            // object churn. 33 ms for NTCP and 788 for SSU, but meaningless due to
            // differences in how it's computed (immediate vs. round trip)
            //_context.statManager().addRateData("transport.sendProcessingTime." + getStyle(), lifetime, 0);
            _context.profileManager().messageSent(msg.getTarget().getIdentity().getHash(), getStyle(), sendTime, msg.getMessageSize());
            _context.statManager().addRateData("transport.sendMessageSize", msg.getMessageSize(), sendTime);
        } else {
            _context.profileManager().messageFailed(msg.getTarget().getIdentity().getHash(), getStyle());
            _context.statManager().addRateData("transport.sendMessageFailureLifetime", lifetime);
        }
    }

    /**
     * Asynchronously send the message as requested in the message and, if the
     * send is successful, queue up any msg.getOnSendJob job, and register it
     * with the OutboundMessageRegistry (if it has a reply selector).  If the
     * send fails, queue up any msg.getOnFailedSendJob
     *
     * Only used by NTCP. SSU overrides.
     *
     * Note that this adds to the queue and then takes it back off in the same thread,
     * so it actually blocks, and we don't need a big queue.
     *
     * TODO: Override in NTCP also and get rid of queue?
     */
    public void send(OutNetMessage msg) {
        if (msg.getTarget() == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error - bad message enqueued [target is null]: " + msg, new Exception("Added by"));
            return;
        }
        try {
            _sendPool.put(msg);
        } catch (InterruptedException ie) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Interrupted during send " + msg);
            return;
        }

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Message added to send pool");
        //msg.timestamp("send on " + getStyle());
        outboundMessageReady();
        //if (_log.shouldLog(Log.INFO))
        //    _log.debug("OutboundMessageReady called");
    }
    /**
     * This message is called whenever a new message is added to the send pool,
     * and it should not block
     *
     * Only used by NTCP. SSU throws UOE.
     */
    protected abstract void outboundMessageReady();

    /**
     * Message received from the I2NPMessageReader - send it to the listener
     *
     * @param inMsg non-null
     * @param remoteIdent may be null
     * @param remoteIdentHash may be null, calculated from remoteIdent if null
     */
    public void messageReceived(I2NPMessage inMsg, RouterIdentity remoteIdent, Hash remoteIdentHash, long msToReceive, int bytesReceived) {
        //if (true)
        //    _log.error("(not error) I2NP message received: " + inMsg.getUniqueId() + " after " + msToReceive);

        int level = Log.INFO;
        if (msToReceive > 5000)
            level = Log.WARN;
        if (_log.shouldLog(level)) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Message received: ").append(inMsg.getClass().getSimpleName());
            buf.append(" / ").append(inMsg.getUniqueId());
            buf.append(" in ").append(msToReceive).append("ms containing ");
            buf.append(bytesReceived).append(" bytes ");
            buf.append(" from ");
            if (remoteIdentHash != null) {
                buf.append(remoteIdentHash.toBase64());
            } else if (remoteIdent != null) {
                buf.append(remoteIdent.getHash().toBase64());
            } else {
                buf.append("[unknown]");
            }
            buf.append(" and forwarding to listener: ");
            if (_listener != null)
                buf.append(_listener);

            _log.log(level, buf.toString());
        }

        if (remoteIdent != null)
            remoteIdentHash = remoteIdent.getHash();
        if (remoteIdentHash != null) {
            _context.profileManager().messageReceived(remoteIdentHash, getStyle(), msToReceive, bytesReceived);
            _context.statManager().addRateData("transport.receiveMessageSize", bytesReceived, msToReceive);
        }

        _context.statManager().addRateData("transport.receiveMessageTime", msToReceive);
        if (msToReceive > 1000) {
            _context.statManager().addRateData("transport.receiveMessageTimeSlow", msToReceive);
        }

        //// this functionality is built into the InNetMessagePool
        //String type = inMsg.getClass().getName();
        //MessageHistory.getInstance().receiveMessage(type, inMsg.getUniqueId(), inMsg.getMessageExpiration(), remoteIdentHash, true);

        if (_listener != null) {
            _listener.messageReceived(inMsg, remoteIdent, remoteIdentHash);
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Null listener! this = " + toString(), new Exception("Null listener"));
        }
    }

    /** Do we increase the advertised cost when approaching conn limits? */
    protected static final boolean ADJUST_COST = true;
    /** TODO change to 2 */
    protected static final int CONGESTION_COST_ADJUSTMENT = 1;

    /**
     *  What addresses are we currently listening to?
     *  Replaces getCurrentAddress()
     *  @return all addresses, non-null
     *  @since IPv6
     */
    public List<RouterAddress> getCurrentAddresses() {
        return _currentAddresses;
    }

    /**
     *  What address are we currently listening to?
     *  Replaces getCurrentAddress()
     *  @param ipv6 true for IPv6 only; false for IPv4 only
     *  @return first matching address or null
     *  @since IPv6
     */
    public RouterAddress getCurrentAddress(boolean ipv6) {
        for (RouterAddress ra : _currentAddresses) {
            if (ipv6 == TransportUtil.isIPv6(ra))
                return ra;
        }
        return null;
    }

    /**
     *  Do we have any current address?
     *  @since IPv6
     */
    public boolean hasCurrentAddress() {
        return !_currentAddresses.isEmpty();
    }

    /**
     * Ask the transport to update its address based on current information and return it
     * Transports should override.
     * @return all addresses, non-null
     * @since 0.7.12
     */
    public List<RouterAddress> updateAddress() {
        return _currentAddresses;
    }

    /**
     *  Replace any existing addresses for the current transport
     *  with the same IP length (4 or 16) with the given one.
     *  TODO: Allow multiple addresses of the same length.
     *  Calls listener.transportAddressChanged()
     *  To remove all IPv4 or IPv6 addresses, use removeAddress(boolean).
     *
     *  @param address null to remove all
     */
    protected void replaceAddress(RouterAddress address) {
        if (_log.shouldLog(Log.WARN))
             _log.warn("Replacing address with " + address, new Exception());
        if (address == null) {
            _currentAddresses.clear();
        } else {
            boolean isIPv6 = TransportUtil.isIPv6(address);
            for (RouterAddress ra : _currentAddresses) {
                if (isIPv6 == TransportUtil.isIPv6(ra))
                    // COWAL
                    _currentAddresses.remove(ra);
            }
            _currentAddresses.add(address);
        }
        if (_log.shouldLog(Log.WARN))
             _log.warn(getStyle() + " now has " + _currentAddresses.size() + " addresses");
        if (_listener != null)
            _listener.transportAddressChanged();
    }

    /**
     *  Remove only this address.
     *  Calls listener.transportAddressChanged().
     *  To remove all IPv4 or IPv6 addresses, use removeAddress(boolean).
     *  To remove all IPv4 and IPv6 addresses, use replaceAddress(null).
     *
     *  @since 0.9.20
     */
    protected void removeAddress(RouterAddress address) {
        if (_log.shouldWarn())
             _log.warn("Removing address " + address, new Exception());
        boolean changed = _currentAddresses.remove(address);
            changed = true;
        if (changed) {
            if (_log.shouldWarn())
                 _log.warn(getStyle() + " now has " + _currentAddresses.size() + " addresses");
            if (_listener != null)
                _listener.transportAddressChanged();
        } else {
            if (_log.shouldWarn())
                 _log.warn(getStyle() + " no addresses removed");
        }
    }

    /**
     *  Remove all existing addresses with the specified IP length (4 or 16).
     *  Calls listener.transportAddressChanged().
     *  To remove all IPv4 and IPv6 addresses, use replaceAddress(null).
     *
     *  @param ipv6 true to remove all IPv6 addresses, false to remove all IPv4 addresses
     *  @since 0.9.20
     */
    protected void removeAddress(boolean ipv6) {
        if (_log.shouldWarn())
             _log.warn("Removing addresses, ipv6? " + ipv6, new Exception());
        boolean changed = false;
        for (RouterAddress ra : _currentAddresses) {
            if (ipv6 == TransportUtil.isIPv6(ra)) {
                // COWAL
                if (_currentAddresses.remove(ra))
                    changed = true;
            }
        }
        if (changed) {
            if (_log.shouldWarn())
                 _log.warn(getStyle() + " now has " + _currentAddresses.size() + " addresses");
            if (_listener != null)
                _listener.transportAddressChanged();
        } else {
            if (_log.shouldWarn())
                 _log.warn(getStyle() + " no addresses removed");
        }
    }

    /**
     *  Save a local address we were notified about before we started.
     *
     *  @since IPv6
     */
    protected void saveLocalAddress(InetAddress address) {
        _localAddresses.add(address);
    }

    /**
     *  Return and then clear all saved local addresses.
     *
     *  @since IPv6
     */
    protected Collection<InetAddress> getSavedLocalAddresses() {
        List<InetAddress> rv = new ArrayList<InetAddress>(_localAddresses);
        _localAddresses.clear();
        return rv;
    }

    /**
     *  Get all available address we can use,
     *  shuffled and then sorted by cost/preference.
     *  Lowest cost (most preferred) first.
     *  @return non-null, possibly empty
     *  @since IPv6
     */
    protected List<RouterAddress> getTargetAddresses(RouterInfo target) {
        List<RouterAddress> rv;
        String alt = getAltStyle();
        if (alt != null)
            rv = target.getTargetAddresses(getStyle(), alt);
        else
            rv = target.getTargetAddresses(getStyle());
        if (rv.isEmpty())
            return rv;
        if (rv.size() > 1) {
            // Shuffle so everybody doesn't use the first one
            Collections.shuffle(rv, _context.random());
            TransportUtil.IPv6Config config = getIPv6Config();
            int adj;
            switch (config) {
                  case IPV6_DISABLED:
                    adj = 10;
                  /**** IPv6 addresses will be rejected in isPubliclyRoutable()
                    for (Iterator<RouterAddress> iter = rv.iterator(); iter.hasNext(); ) {
                        byte[] ip = iter.next().getIP();
                        if (ip != null && ip.length == 16)
                            iter.remove();
                    }
                   ****/
                    break;

                  case IPV6_NOT_PREFERRED:
                    adj = 1; break;
                  default:

                  case IPV6_ENABLED:
                    adj = 0; break;

                  case IPV6_PREFERRED:
                    adj = -1; break;

                  case IPV6_ONLY:
                    adj = -10;
                  /**** IPv6 addresses will be rejected in isPubliclyRoutable()
                    for (Iterator<RouterAddress> iter = rv.iterator(); iter.hasNext(); ) {
                        byte[] ip = iter.next().getIP();
                        if (ip != null && ip.length == 4)
                            iter.remove();
                    }
                   ****/
                    break;
            }
            Collections.sort(rv, new AddrComparator(adj));
        }
        return rv;
    }

    /**
     *  Compare based on published cost, adjusting for our IPv6 preference.
     *  Lowest cost (most preferred) first.
     *  @since IPv6
     */
    private static class AddrComparator implements Comparator<RouterAddress>, Serializable {
        private final int adj;

        public AddrComparator(int ipv6Adjustment) {
            adj = ipv6Adjustment;
        }

        public int compare(RouterAddress l, RouterAddress r) {
            int lc = l.getCost();
            int rc = r.getCost();
            byte[] lip = l.getIP();
            byte[] rip = r.getIP();
            if (lip == null)
                lc += 20;
            else if (lip.length == 16)
                lc += adj;
            if (rip == null)
                rc += 20;
            else if (rip.length == 16)
                rc += adj;
            if (lc > rc)
                return 1;
            if (lc < rc)
                return -1;
            return 0;
        }
    }

    /**
     *  Notify a transport of an external address change.
     *  This may be from a local interface, UPnP, a config change, etc.
     *  This should not be called if the ip didn't change
     *  (from that source's point of view), or is a local address.
     *  May be called multiple times for IPv4 or IPv6.
     *  The transport should also do its own checking on whether to accept
     *  notifications from this source.
     *
     *  This can be called before startListening() to set an initial address,
     *  or after the transport is running.
     *
     *  @param source defined in Transport.java
     *  @param ip typ. IPv4 or IPv6 non-local; may be null to indicate IPv4 failure or port info only
     *  @param port 0 for unknown or unchanged
     */
    public abstract void externalAddressReceived(AddressSource source, byte[] ip, int port);

    /**
     *  Notify a transport of an external address change.
     *  This may be from a local interface, UPnP, a config change, etc.
     *  This should not be called if the ip didn't change
     *  (from that source's point of view), or is a local address.
     *  May be called multiple times for IPv4 or IPv6.
     *  The transport should also do its own checking on whether to accept
     *  notifications from this source.
     *
     *  This can be called after the transport is running.
     *
     *  TODO externalAddressRemoved(source, ip, port)
     *
     *  This implementation does nothing. Transports should override if they want notification.
     *
     *  @param source defined in Transport.java
     *  @since 0.9.20
     */
    public void externalAddressRemoved(AddressSource source, boolean ipv6) {}

    /**
     *  Notify a transport of the results of trying to forward a port.
     *
     *  This implementation does nothing. Transports should override if they want notification.
     *
     *  @param ip may be null
     *  @param port the internal port
     *  @param externalPort the external port, which for now should always be the same as
     *                      the internal port if the forwarding was successful.
     */
    public void forwardPortStatus(byte[] ip, int port, int externalPort, boolean success, String reason) {}

    /**
     * What INTERNAL port would the transport like to have forwarded by UPnP.
     * This can't be passed via getCurrentAddress(), as we have to open the port
     * before we can publish the address, and that's the external port anyway.
     *
     * @return port or -1 for none or 0 for any
     */
    public int getRequestedPort() { return -1; }

    /** Who to notify on message availability */
    public void setListener(TransportEventListener listener) { _listener = listener; }
    /** Make this stuff pretty (only used in the old console) */
    public void renderStatusHTML(Writer out) throws IOException {}
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { renderStatusHTML(out); }

    /**
     *  Previously returned short, now enum as of 0.9.20
     */
    public abstract Status getReachabilityStatus();

    /**
     * @deprecated unused
     */
    @Deprecated
    public void recheckReachability() {}

    /**
     *  This returns true if the force-firewalled setting is configured, false otherwise.
     *
     *  @since 0.9.20, public since 0.9.30
     */
    public boolean isIPv4Firewalled() {
        return TransportUtil.isIPv4Firewalled(_context, getStyle());
    }

    /**
     *  This returns true if the force-firewalled setting is configured, false otherwise.
     *
     *  @since 0.9.27, public since 0.9.30
     */
    public boolean isIPv6Firewalled() {
        return TransportUtil.isIPv6Firewalled(_context, getStyle());
    }

    public boolean isBacklogged(Hash peer) { return false; }
    public boolean isEstablished(Hash peer) { return false; }

    /**
     * Tell the transport that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    public void mayDisconnect(Hash peer) {}

    public boolean isUnreachable(Hash peer) {
        long now = _context.clock().now();
        synchronized (_unreachableEntries) {
            Long when = _unreachableEntries.get(peer);
            if (when == null) return false;
            if (when.longValue() + UNREACHABLE_PERIOD < now) {
                _unreachableEntries.remove(peer);
                return false;
            } else {
                return true;
            }
        }
    }

    /** called when we can't reach a peer */
    public void markUnreachable(Hash peer) {
        Status status = _context.commSystem().getStatus();
        if (status == Status.DISCONNECTED ||
            status == Status.HOSED)
            return;
        Long now = Long.valueOf(_context.clock().now());
        synchronized (_unreachableEntries) {
            // This isn't very useful since it is cleared when they contact us
            _unreachableEntries.put(peer, now);
        }
        // This is not cleared when they contact us
        markWasUnreachable(peer, true);
    }

    /** called when we establish a peer connection (outbound or inbound) */
    public void markReachable(Hash peer, boolean isInbound) {
        // if *some* transport can reach them, then we shouldn't banlist 'em
        _context.banlist().unbanlistRouter(peer);
        synchronized (_unreachableEntries) {
            _unreachableEntries.remove(peer);
        }
        if (!isInbound)
            markWasUnreachable(peer, false);
    }

    private class CleanupUnreachable implements SimpleTimer.TimedEvent {
        public void timeReached() {
            long now = _context.clock().now();
            synchronized (_unreachableEntries) {
                for (Iterator<Long> iter = _unreachableEntries.values().iterator(); iter.hasNext(); ) {
                    Long when = iter.next();
                    if (when.longValue() + UNREACHABLE_PERIOD < now)
                        iter.remove();
                }
            }
            synchronized (_wasUnreachableEntries) {
                for (Iterator<Long> iter = _wasUnreachableEntries.values().iterator(); iter.hasNext(); ) {
                    Long when = iter.next();
                    if (when.longValue() + WAS_UNREACHABLE_PERIOD < now)
                        iter.remove();
                }
            }
        }
    }

    /**
     * Was the peer UNreachable (outbound only) the last time we tried it?
     * This is NOT reset if the peer contacts us.
     */
    public boolean wasUnreachable(Hash peer) {
        long now = _context.clock().now();
        synchronized (_wasUnreachableEntries) {
            Long when = _wasUnreachableEntries.get(peer);
            if (when != null) {
                if (when.longValue() + WAS_UNREACHABLE_PERIOD < now) {
                    _unreachableEntries.remove(peer);
                    return false;
                } else {
                    return true;
                }
            }
        }
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
        if (ri == null)
            return false;
        return null == ri.getTargetAddress(this.getStyle());
    }

    /**
     * Maintain the WasUnreachable list
     */
    private void markWasUnreachable(Hash peer, boolean yes) {
        if (yes) {
            Long now = Long.valueOf(_context.clock().now());
            synchronized (_wasUnreachableEntries) {
                _wasUnreachableEntries.put(peer, now);
            }
        } else {
            synchronized (_wasUnreachableEntries) {
                _wasUnreachableEntries.remove(peer);
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(this.getStyle() + " setting wasUnreachable to " + yes + " for " + peer,
                      yes ? new Exception() : null);
    }

    /**
     * Are we allowed to connect to local addresses?
     *
     * @since 0.9.28 moved from UDPTransport
     */
    public boolean allowLocal() {
        return _context.getBooleanProperty("i2np.allowLocal");
    }

    /**
     * IP of the peer from the last connection (in or out, any transport).
     *
     * @param ip IPv4 or IPv6, non-null
     */
    public void setIP(Hash peer, byte[] ip) {
        byte[] old;
        synchronized (_IPMap) {
            old = _IPMap.put(peer, ip);
        }
        if (!DataHelper.eq(old, ip))
            _context.commSystem().queueLookup(ip);
    }

    /**
     * IP of the peer from the last connection (in or out, any transport).
     *
     * @return IPv4 or IPv6 or null
     */
    public static byte[] getIP(Hash peer) {
        synchronized (_IPMap) {
            return _IPMap.get(peer);
        }
    }

    /**
     * An alternate supported style, or null.
     * @return null, override to add support
     * @since 0.9.35
     */
    public String getAltStyle() { return null; }

    /**
     *  @since 0.9.3
     */
    static void clearCaches() {
        synchronized(_IPMap) {
            _IPMap.clear();
        }
    }

    /**
     *  @since IPv6, public since 0.9.30
     */
    public TransportUtil.IPv6Config getIPv6Config() {
        return TransportUtil.getIPv6Config(_context, getStyle());
    }

    /**
     *  Allows IPv6 only if the transport is configured for it.
     *  Caller must check if we actually have a public IPv6 address.
     *  @param addr non-null
     */
    protected boolean isPubliclyRoutable(byte addr[]) {
        TransportUtil.IPv6Config cfg = getIPv6Config();
        return TransportUtil.isPubliclyRoutable(addr,
                                                cfg != TransportUtil.IPv6Config.IPV6_ONLY,
                                                cfg != TransportUtil.IPv6Config.IPV6_DISABLED);
    }

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /**
     *  Translate
     *  @since 0.9.8 moved from transports
     */
    protected String _t(String s) {
        return Translate.getString(s, _context, BUNDLE_NAME);
    }

    /**
     *  Translate
     *  @since 0.9.8 moved from transports
     */
    protected String _t(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }

    /**
     *  Translate
     *  @since 0.9.8
     */
    protected String ngettext(String s, String p, int n) {
        return Translate.getString(n, s, p, _context, BUNDLE_NAME);
    }
}
