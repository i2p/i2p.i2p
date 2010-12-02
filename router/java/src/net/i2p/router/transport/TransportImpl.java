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
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Defines a way to send a message to another peer and start listening for messages
 *
 */
public abstract class TransportImpl implements Transport {
    private Log _log;
    private TransportEventListener _listener;
    private RouterAddress _currentAddress;
    private final List _sendPool;
    protected RouterContext _context;
    /** map from routerIdentHash to timestamp (Long) that the peer was last unreachable */
    private final Map<Hash, Long>  _unreachableEntries;
    private Set<Hash> _wasUnreachableEntries;
    /** global router ident -> IP */
    private static Map<Hash, byte[]> _IPMap = new ConcurrentHashMap(128);

    /**
     * Initialize the new transport
     *
     */
    public TransportImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(TransportImpl.class);

        _context.statManager().createRateStat("transport.sendMessageFailureLifetime", "How long the lifetime of messages that fail are?", "Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.sendMessageSize", "How large are the messages sent?", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.receiveMessageSize", "How large are the messages received?", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.receiveMessageTime", "How long it takes to read a message?", "Transport", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.receiveMessageTimeSlow", "How long it takes to read a message (when it takes more than a second)?", "Transport", new long[] { 60*1000l, 5*60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.sendProcessingTime", "How long does it take from noticing that we want to send the message to having it completely sent (successfully or failed)?", "Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.expiredOnQueueLifetime", "How long a message that expires on our outbound queue is processed", "Transport", new long[] { 60*1000l, 10*60*1000l, 60*60*1000l, 24*60*60*1000l } );
        _sendPool = new ArrayList(16);
        _unreachableEntries = new HashMap(16);
        _wasUnreachableEntries = new ConcurrentHashSet(16);
        _currentAddress = null;
        SimpleScheduler.getInstance().addPeriodicEvent(new CleanupUnreachable(), 2 * UNREACHABLE_PERIOD, UNREACHABLE_PERIOD / 2);
    }

    /**
     * How many peers are we connected to?
     * For NTCP, this is the same as active,
     * but SSU actually looks at idle time for countActivePeers()
     */
    public int countPeers() { return countActivePeers(); }
    /**
     * How many peers active in the last few minutes?
     */
    public int countActivePeers() { return 0; }
    /**
     * How many peers are we actively sending messages to (this minute)
     */
    public int countActiveSendPeers() { return 0; }

    /** Default is 500 for floodfills... */
    private static final int DEFAULT_MAX_CONNECTIONS = 425;
    /** ...and 50/100/150/200/250 for BW Tiers K/L/M/N/O */
    private static final int MAX_CONNECTION_FACTOR = 50;
    /** Per-transport connection limit */
    public int getMaxConnections() {
        String style = getStyle();
        if (style.equals("SSU"))
            style = "udp";
        else
            style = style.toLowerCase();
        int def = DEFAULT_MAX_CONNECTIONS;
        RouterInfo ri = _context.router().getRouterInfo();
        if (ri != null) {
            char bw = ri.getBandwidthTier().charAt(0);
            if (bw != 'U' &&
                ! ((FloodfillNetworkDatabaseFacade)_context.netDb()).floodfillEnabled())
                def = MAX_CONNECTION_FACTOR * (1 + bw - Router.CAPABILITY_BW12);
        }
        // increase limit for SSU, for now
        if (style.equals("udp"))
            def = def * 3 / 2;
        return _context.getProperty("i2np." + style + ".maxConnections", def);
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
    public Vector getClockSkews() { return new Vector(); }

    public List getMostRecentErrorMessages() { return Collections.EMPTY_LIST; }
    /**
     * Nonblocking call to pull the next outbound message
     * off the queue.
     *
     * @return the next message or null if none are available
     */
    public OutNetMessage getNextMessage() {
        OutNetMessage msg = null;
        synchronized (_sendPool) {
            if (_sendPool.isEmpty()) return null;
            msg = (OutNetMessage)_sendPool.remove(0); // use priority queues later
        }
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
        boolean log = false;
        if (sendSuccessful)
            msg.timestamp("afterSend(successful)");
        else
            msg.timestamp("afterSend(failed)");

        if (!sendSuccessful)
            msg.transportFailed(getStyle());

        if (msToSend > 1000) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("afterSend slow: [success=" + sendSuccessful + "] " + msg.getMessageSize() + "byte "
                          + msg.getMessageType() + " " + msg.getMessageId() + " to "
                          + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) + " took " + msToSend
                          + "/" + msg.getTransmissionTime());
        }
        //if (true)
        //    _log.error("(not error) I2NP message sent? " + sendSuccessful + " " + msg.getMessageId() + " after " + msToSend + "/" + msg.getTransmissionTime());

        long lifetime = msg.getLifetime();
        if (lifetime > 3000) {
            int level = Log.WARN;
            if (!sendSuccessful)
                level = Log.INFO;
            if (_log.shouldLog(level))
                _log.log(level, "afterSend slow (" + lifetime + "/" + msToSend + "/" + msg.getTransmissionTime() + "): [success=" + sendSuccessful + "]" + msg.getMessageSize() + "byte "
                          + msg.getMessageType() + " " + msg.getMessageId() + " from " + _context.routerHash().toBase64().substring(0,6)
                          + " to " + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) + ": " + msg.toString());
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("afterSend: [success=" + sendSuccessful + "]" + msg.getMessageSize() + "byte "
                          + msg.getMessageType() + " " + msg.getMessageId() + " from " + _context.routerHash().toBase64().substring(0,6)
                          + " to " + msg.getTarget().getIdentity().calculateHash().toBase64().substring(0,6) + "\n" + msg.toString());
        }

        if (sendSuccessful) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Send message " + msg.getMessageType() + " to "
                           + msg.getTarget().getIdentity().getHash().toBase64() + " with transport "
                           + getStyle() + " successfully");
            Job j = msg.getOnSendJob();
            if (j != null)
                _context.jobQueue().addJob(j);
            log = true;
            msg.discardData();
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Failed to send message " + msg.getMessageType()
                          + " to " + msg.getTarget().getIdentity().getHash().toBase64()
                          + " with transport " + getStyle() + " (details: " + msg + ")");
            if (msg.getExpiration() < _context.clock().now())
                _context.statManager().addRateData("transport.expiredOnQueueLifetime", lifetime, lifetime);

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
            String type = msg.getMessageType();
            // the udp transport logs some further details
            /*
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
                _log.info("Took too long from preperation to afterSend(ok? " + sendSuccessful
                          + "): " + allTime + "ms/" + sendTime + "ms after failing on: "
                          + msg.getFailedTransports() + " and succeeding on " + getStyle());
            if ( (allTime > 60*1000) && (sendSuccessful) ) {
                // WTF!!@#
                if (_log.shouldLog(Log.WARN))
                    _log.warn("WTF, more than a minute slow? " + msg.getMessageType()
                              + " of id " + msg.getMessageId() + " (send begin on "
                              + new Date(msg.getSendBegin()) + " / created on "
                              + new Date(msg.getCreated()) + "): " + msg, msg.getCreatedBy());
                _context.messageHistory().messageProcessingError(msg.getMessageId(),
                                                                 msg.getMessageType(),
                                                                 "Took too long to send [" + allTime + "ms]");
            }
        }


        if (sendSuccessful) {
            _context.statManager().addRateData("transport.sendProcessingTime", lifetime, lifetime);
            _context.profileManager().messageSent(msg.getTarget().getIdentity().getHash(), getStyle(), sendTime, msg.getMessageSize());
            _context.statManager().addRateData("transport.sendMessageSize", msg.getMessageSize(), sendTime);
        } else {
            _context.profileManager().messageFailed(msg.getTarget().getIdentity().getHash(), getStyle());
            _context.statManager().addRateData("transport.sendMessageFailureLifetime", lifetime, lifetime);
        }
    }

    /**
     * Asynchronously send the message as requested in the message and, if the
     * send is successful, queue up any msg.getOnSendJob job, and register it
     * with the OutboundMessageRegistry (if it has a reply selector).  If the
     * send fails, queue up any msg.getOnFailedSendJob
     *
     */
    public void send(OutNetMessage msg) {
        if (msg.getTarget() == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error - bad message enqueued [target is null]: " + msg, new Exception("Added by"));
            return;
        }
        boolean duplicate = false;
        synchronized (_sendPool) {
            if (_sendPool.contains(msg))
                duplicate = true;
            else
                _sendPool.add(msg);
        }
        if (duplicate) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Message already is in the queue?  wtf.  msg = " + msg,
                           new Exception("wtf, requeued?"));
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Message added to send pool");
        msg.timestamp("send on " + getStyle());
        outboundMessageReady();
        if (_log.shouldLog(Log.INFO))
            _log.debug("OutboundMessageReady called");
    }
    /**
     * This message is called whenever a new message is added to the send pool,
     * and it should not block
     */
    protected abstract void outboundMessageReady();

    /**
     * Message received from the I2NPMessageReader - send it to the listener
     *
     */
    public void messageReceived(I2NPMessage inMsg, RouterIdentity remoteIdent, Hash remoteIdentHash, long msToReceive, int bytesReceived) {
        //if (true)
        //    _log.error("(not error) I2NP message received: " + inMsg.getUniqueId() + " after " + msToReceive);

        int level = Log.INFO;
        if (msToReceive > 5000)
            level = Log.WARN;
        if (_log.shouldLog(level)) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Message received: ").append(inMsg.getClass().getName());
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

        _context.statManager().addRateData("transport.receiveMessageTime", msToReceive, msToReceive);
        if (msToReceive > 1000) {
            _context.statManager().addRateData("transport.receiveMessageTimeSlow", msToReceive, msToReceive);
        }

        //// this functionality is built into the InNetMessagePool
        //String type = inMsg.getClass().getName();
        //MessageHistory.getInstance().receiveMessage(type, inMsg.getUniqueId(), inMsg.getMessageExpiration(), remoteIdentHash, true);

        if (_listener != null) {
            _listener.messageReceived(inMsg, remoteIdent, remoteIdentHash);
        } else {
            if (_log.shouldLog(Log.ERROR))
                _log.error("WTF! Null listener! this = " + toString(), new Exception("Null listener"));
        }
    }

    /** Do we increase the advertised cost when approaching conn limits? */
    public static final boolean ADJUST_COST = true;

    /** What addresses are we currently listening to? */
    public RouterAddress getCurrentAddress() {
        return _currentAddress;
    }

    /**
     * Ask the transport to update its address based on current information and return it
     * Transports should override.
     * @since 0.7.12
     */
    public RouterAddress updateAddress() {
        return _currentAddress;
    }

    /**
     * Replace any existing addresses for the current transport with the given
     * one.
     */
    protected void replaceAddress(RouterAddress address) {
        // _log.error("Replacing address for " + getStyle() + " was " + _currentAddress + " now " + address);
        _currentAddress = address;
        if (_listener != null)
            _listener.transportAddressChanged();
    }

    /**
     *  Notify a transport of an external address change.
     *  This may be from a local interface, UPnP, a config change, etc.
     *  This should not be called if the ip didn't change
     *  (from that source's point of view), or is a local address,
     *  or if the ip is IPv6, but the transport should check anyway.
     *  The transport should also do its own checking on whether to accept
     *  notifications from this source.
     *
     *  This can be called before startListening() to set an initial address,
     *  or after the transport is running.
     *
     *  @param source defined in Transport.java
     *  @param ip typ. IPv4 non-local
     *  @param port 0 for unknown or unchanged
     */
    public void externalAddressReceived(String source, byte[] ip, int port) {}

    /**
     *  Notify a transport of the results of trying to forward a port
     */
    public void forwardPortStatus(int port, boolean success, String reason) {}

    /**
     * What port would the transport like to have forwarded by UPnP.
     * This can't be passed via getCurrentAddress(), as we have to open the port
     * before we can publish the address.
     *
     * @return port or -1 for none or 0 for any
     */
    public int getRequestedPort() { return -1; }

    /** Who to notify on message availability */
    public void setListener(TransportEventListener listener) { _listener = listener; }
    /** Make this stuff pretty (only used in the old console) */
    public void renderStatusHTML(Writer out) throws IOException {}
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { renderStatusHTML(out); }

    public RouterContext getContext() { return _context; }
    public short getReachabilityStatus() { return CommSystemFacade.STATUS_UNKNOWN; }
    public void recheckReachability() {}
    public boolean isBacklogged(Hash dest) { return false; }
    public boolean isEstablished(Hash dest) { return false; }

    private static final long UNREACHABLE_PERIOD = 5*60*1000;
    public boolean isUnreachable(Hash peer) {
        long now = _context.clock().now();
        synchronized (_unreachableEntries) {
            Long when = (Long)_unreachableEntries.get(peer);
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
    /** This isn't very useful since it is cleared when they contact us */
    public void markUnreachable(Hash peer) {
        long now = _context.clock().now();
        synchronized (_unreachableEntries) {
            _unreachableEntries.put(peer, new Long(now));
        }
        markWasUnreachable(peer, true);
    }
    /** called when we establish a peer connection (outbound or inbound) */
    public void markReachable(Hash peer, boolean isInbound) {
        // if *some* transport can reach them, then we shouldn't shitlist 'em
        _context.shitlist().unshitlistRouter(peer);
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
                for (Iterator iter = _unreachableEntries.keySet().iterator(); iter.hasNext(); ) {
                    Hash peer = (Hash)iter.next();
                    Long when = (Long)_unreachableEntries.get(peer);
                    if (when.longValue() + UNREACHABLE_PERIOD < now)
                        iter.remove();
                }
            }
        }
    }

    /**
     * Was the peer UNreachable (outbound only) the last time we tried it?
     * This is NOT reset if the peer contacts us and it is never expired.
     */
    public boolean wasUnreachable(Hash peer) {
        if (_wasUnreachableEntries.contains(peer))
            return true;
        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
        if (ri == null)
            return false;
        return null == ri.getTargetAddress(this.getStyle());
    }
    /**
     * Maintain the WasUnreachable list
     */
    public void markWasUnreachable(Hash peer, boolean yes) {
        if (yes)
            _wasUnreachableEntries.add(peer);
        else
            _wasUnreachableEntries.remove(peer);
        if (_log.shouldLog(Log.WARN))
            _log.warn(this.getStyle() + " setting wasUnreachable to " + yes + " for " + peer);
    }

    public void setIP(Hash peer, byte[] ip) {
        _IPMap.put(peer, ip);
        _context.commSystem().queueLookup(ip);
    }

    public static byte[] getIP(Hash peer) {
        return _IPMap.get(peer);
    }

    public static boolean isPubliclyRoutable(byte addr[]) {
        if (addr.length == 4) {
            if ((addr[0]&0xFF) == 127) return false;
            if ((addr[0]&0xFF) == 10) return false;
            if ( ((addr[0]&0xFF) == 172) && ((addr[1]&0xFF) >= 16) && ((addr[1]&0xFF) <= 31) ) return false;
            if ( ((addr[0]&0xFF) == 192) && ((addr[1]&0xFF) == 168) ) return false;
            if ((addr[0]&0xFF) >= 224) return false; // no multicast
            if ((addr[0]&0xFF) == 0) return false;
            if ( ((addr[0]&0xFF) == 169) && ((addr[1]&0xFF) == 254) ) return false;
            // 5/8 allocated to RIPE (30 November 2010)
            //if ((addr[0]&0xFF) == 5) return false;  // Hamachi
            return true; // or at least possible to be true
        } else if (addr.length == 16) {
            return false;
        } else {
            // ipv?
            return false;
        }
    }
}
