package net.i2p.router.crypto.ratchet;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.router.RouterContext;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *
 *
 *
 *  @since 0.9.44
 */
public class RatchetSKM extends SessionKeyManager implements SessionTagListener {
    private final Log _log;
    /** Map allowing us to go from the targeted PublicKey to the OutboundSession used */
    private final ConcurrentHashMap<PublicKey, OutboundSession> _outboundSessions;
    private final HashMap<PublicKey, List<OutboundSession>> _pendingOutboundSessions;
    /** Map allowing us to go from a SessionTag to the containing RatchetTagSet */
    private final ConcurrentHashMap<RatchetSessionTag, RatchetTagSet> _inboundTagSets;
    protected final RouterContext _context;
    private volatile boolean _alive;
    private final HKDF _hkdf;
    private final DecayingHashSet _replayFilter;
    private final Destination _destination;

    /**
     * Let outbound session tags sit around for this long before expiring them.
     * Inbound tag expiration is set by SESSION_LIFETIME_MAX_MS
     */
    final static long SESSION_TAG_DURATION_MS = 8 * 60 * 1000;

    /**
     * Keep unused inbound session tags around for this long (a few minutes longer than
     * session tags are used on the outbound side so that no reasonable network lag 
     * can cause failed decrypts)
     *
     * This is also the max idle time for an outbound session.
     */
    final static long SESSION_LIFETIME_MAX_MS = SESSION_TAG_DURATION_MS + 2 * 60 * 1000;

    final static long SESSION_PENDING_DURATION_MS = 3 * 60 * 1000;
    // replace an old session created before this if we get a new NS
    private static final long SESSION_REPLACE_AGE = 2*60*1000;

    private static final byte[] ZEROLEN = new byte[0];


    /**
     * The session key manager should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public RatchetSKM(RouterContext context, Destination dest) {
        super(context);
        _log = context.logManager().getLog(RatchetSKM.class);
        _context = context;
        _destination = dest;
        _outboundSessions = new ConcurrentHashMap<PublicKey, OutboundSession>(64);
        _pendingOutboundSessions = new HashMap<PublicKey, List<OutboundSession>>(64);
        _inboundTagSets = new ConcurrentHashMap<RatchetSessionTag, RatchetTagSet>(128);
        _hkdf = new HKDF(context);
        _replayFilter = new DecayingHashSet(context, (int) ECIESAEADEngine.MAX_NS_AGE, 32, "Ratchet-NS");
        // start the precalc of Elg2 keys if it wasn't already started
        context.eciesEngine().startup();
         _alive = true;
        new CleanupEvent();
    }

    /**
     *  Cannot be restarted
     */
    @Override
    public void shutdown() {
         _alive = false;
        _inboundTagSets.clear();
        _outboundSessions.clear();
        synchronized (_pendingOutboundSessions) {
            _pendingOutboundSessions.clear();
        }
        _replayFilter.stopDecaying();
    }

    private class CleanupEvent extends SimpleTimer2.TimedEvent {
        public CleanupEvent() {
            // wait until first expiration time to start
            super(_context.simpleTimer2(), SESSION_PENDING_DURATION_MS);
        }

        public void timeReached() {
            if (!_alive)
                return;
            aggressiveExpire();
            schedule(60*1000);
        }
    }

    /**
     *  @since 0.9.46
     */
    public Destination getDestination() {
        return _destination;
    }

    /** RatchetTagSet */
    private Set<RatchetTagSet> getRatchetTagSets() {
        synchronized (_inboundTagSets) {
            return new HashSet<RatchetTagSet>(_inboundTagSets.values());
        }
    }

    /** OutboundSession - used only by HTML */
    private Set<OutboundSession> getOutboundSessions() {
        return new HashSet<OutboundSession>(_outboundSessions.values());
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public SessionKey getCurrentKey(PublicKey target) {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public SessionKey getCurrentOrNewKey(PublicKey target) {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public void createSession(PublicKey target, SessionKey key) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @return true if a dup
     *  @since 0.9.46
     */
    boolean isDuplicate(PublicKey pk) {
        return _replayFilter.add(pk.getData(), 0, 32);
    }

    /**
     * Inbound or outbound. Checks state.getRole() to determine.
     * For outbound (NS sent), adds to list of pending inbound sessions and returns true.
     * For inbound (NS rcvd), if no other pending outbound sessions, creates one
     * and returns true, or false if one already exists.
     *
     * @param d null if unknown
     * @param callback null for inbound, may be null for outbound
     */
    boolean createSession(PublicKey target, Destination d, HandshakeState state, ReplyCallback callback) {
        EncType type = target.getType();
        if (type != EncType.ECIES_X25519)
            throw new IllegalArgumentException("Bad public key type " + type);
        OutboundSession sess = new OutboundSession(target, d, null, state, callback);
        boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
        if (isInbound) {
            // we are Bob, NS received
            boolean rv = addSession(sess, true);
            if (_log.shouldInfo()) {
                if (rv)
                    _log.info("New OB session " + state.hashCode() + " as Bob. Alice: " + toString(target));
                else
                    _log.info("Dup OB session " + state.hashCode() + " as Bob. Alice: " + toString(target));
            }
            return rv;
        } else {
            // we are Alice, NS sent
            synchronized (_pendingOutboundSessions) {
                List<OutboundSession> pending = _pendingOutboundSessions.get(target);
                if (pending != null) {
                    pending.add(sess);
                    if (_log.shouldInfo())
                        _log.info("Another new OB session " + state.hashCode() + " as Alice, total now: " + pending.size() +
                                  ". Bob: " + toString(target));
                } else {
                    pending = new ArrayList<OutboundSession>(4);
                    pending.add(sess);
                    _pendingOutboundSessions.put(target, pending);
                    if (_log.shouldInfo())
                        _log.info("First new OB session " + state.hashCode() + " as Alice. Bob: " + toString(target));
                }
            }
            return true;
        }
    }

    /**
     * Inbound or outbound. Checks state.getRole() to determine.
     * For outbound (NSR rcvd by Alice), sets session to transition to ES mode outbound.
     * For inbound (NSR sent by Bob), sets up inbound ES tagset.
     *
     * @param oldState null for inbound, pre-clone for outbound
     * @return true if this was the first NSR received
     */
    boolean updateSession(PublicKey target, HandshakeState oldState, HandshakeState state,
                          ReplyCallback callback, SplitKeys split) {
        EncType type = target.getType();
        if (type != EncType.ECIES_X25519)
            throw new IllegalArgumentException("Bad public key type " + type);
        boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
        if (isInbound) {
            // we are Bob, NSR sent
            if (_log.shouldInfo())
                _log.info("Session " + state.hashCode() + " update as Bob. Alice: " + toString(target));
            OutboundSession sess = getSession(target);
            if (sess == null) {
                if (_log.shouldWarn())
                    _log.warn("Update Bob session but no session found for "  + target);
                // TODO can we recover?
                return false;
            }
            sess.updateSession(state, callback, split);
        } else {
            // we are Alice, NSR received
            if (_log.shouldInfo())
                _log.info("Session " + oldState.hashCode() + " to " + state.hashCode() + " update as Alice. Bob: " + toString(target));
            synchronized (_pendingOutboundSessions) {
                List<OutboundSession> pending = _pendingOutboundSessions.get(target);
                if (pending == null) {
                    if (_log.shouldDebug())
                        _log.debug("Update Alice session but no pending sessions for "  + target);
                    // Normal case for multiple NSRs, was already removed
                    return false;
                }
                boolean found = false;
                for (Iterator<OutboundSession> iter = pending.iterator(); iter.hasNext(); ) {
                    OutboundSession sess = iter.next();
                    HandshakeState pstate = sess.getHandshakeState();
                    if (oldState.equals(pstate)) {
                        if (!found) {
                            found = true;
                            sess.updateSession(state, null, split);
                            boolean ok = addSession(sess, false);
                            if (_log.shouldDebug()) {
                                if (ok)
                                    _log.debug("Update Alice session from NSR to ES for "  + target);
                                else
                                    _log.debug("Session already updated from NSR to ES for "  + target);
                            }
                            iter.remove();
                        } else {
                            // won't happen
                            if (_log.shouldDebug())
                                _log.debug("Dup pending session " + sess + " for "  + target);
                        }
                    } else {
                        if (_log.shouldDebug())
                            _log.debug("Other pending session " + sess + " for "  + target);
                    }
                }
                if (found) {
                    _pendingOutboundSessions.remove(target);
                    if (!pending.isEmpty()) {
                        for (OutboundSession sess : pending) {
                            //sess.getHandshakeState().destroy();
                            // TODO remove its inbound tags?
                        }
                    }
                } else {
                    if (_log.shouldDebug())
                        _log.debug("Update Alice session but no session found (out of " + pending.size() + ") for "  + target);
                    // TODO can we recover?
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @since 0.9.46
     */
    void nextKeyReceived(PublicKey target, NextSessionKey key) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("Got NextKey but no session found for "  + target);
            return;
        }
        sess.nextKeyReceived(key);
    }

    /**
     * Side effect - binds this session to the supplied destination.
     *
     * @param d the far-end Destination for this PublicKey if known, or null
     * @return true if registered
     * @since 0.9.47
     */
    boolean registerTimer(PublicKey target, Destination d, SimpleTimer2.TimedEvent timer) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("registerTimer() but no session found for "  + target);
            return false;
        }
        return sess.registerTimer(d, timer);
    }

    /**
     * @return the far-end Destination for this PublicKey, or null
     * @since 0.9.47
     */
    Destination getDestination(PublicKey target) {
        OutboundSession sess = getSession(target);
        if (sess != null) {
            return sess.getDestination();
        }
        return null;
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public SessionTag consumeNextAvailableTag(PublicKey target, SessionKey key) {
        throw new UnsupportedOperationException();
    }

    /**
     * Outbound.
     *
     * Retrieve the next available session tag and key for sending a message to the target.
     *
     * If this returns null, no session is set up yet, and a New Session message should be sent.
     *
     * If this returns non-null, the tag in the RatchetEntry will be non-null.
     *
     * If the SessionKeyAndNonce contains a HandshakeState, then the session setup is in progress,
     * and a New Session Reply message should be sent.
     * Otherwise, an Existing Session message should be sent.
     *
     */
    public RatchetEntry consumeNextAvailableTag(PublicKey target) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            //if (_log.shouldDebug())
            //    _log.debug("No OB session to " + toString(target));
            return null;
        }
        RatchetEntry rv = sess.consumeNext();
        if (_log.shouldDebug()) {
            if (rv != null)
                _log.debug("Using tag " + rv + " to " + toString(target));
            else
                _log.debug("No more tags in OB session to " + toString(target));
        }
        return rv;
    }

    /**
     *  How many to send, IF we need to.
     *  @return the configured value (not adjusted for current available)
     */
    @Override
    public int getTagsToSend() { return 0; };

    /**
     *  @return the configured value
     */
    @Override
    public int getLowThreshold() { return 999999; };

    /**
     *  @return false always
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key, int lowThreshold) {
        return false;
    }

    /**
     * Determine (approximately) how many available session tags for the current target
     * have been confirmed and are available
     *
     */
    @Override
    public int getAvailableTags(PublicKey target, SessionKey key) {
        OutboundSession sess = getSession(target);
        if (sess == null) { return 0; }
        if (sess.getCurrentKey().equals(key)) {
            return sess.availableTags();
        }
        return 0;
    }

    /**
     * Determine how long the available tags will be available for before expiring, in 
     * milliseconds
     */
    @Override
    public long getAvailableTimeLeft(PublicKey target, SessionKey key) {
        OutboundSession sess = getSession(target);
        if (sess == null) { return 0; }
        if (sess.getCurrentKey().equals(key)) {
            long end = sess.getLastExpirationDate();
            if (end <= 0) 
                return 0;
            else
                return end - _context.clock().now();
        }
        return 0;
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
        throw new UnsupportedOperationException();
    }

    /**
     * Mark all of the tags delivered to the target up to this point as invalid, since the peer
     * has failed to respond when they should have.  This call essentially lets the system recover
     * from corrupted tag sets and crashes
     *
     * @deprecated unused and rather drastic
     * @throws UnsupportedOperationException always
     */
    @Override
    @Deprecated
    public void failTags(PublicKey target) {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public void failTags(PublicKey target, SessionKey key, TagSetHandle ts) {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags) {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags, long expire) {
        throw new UnsupportedOperationException();
    }

    /**
     * One time session
     * @param expire time from now
     */
    public void tagsReceived(SessionKey key, RatchetSessionTag tag, long expire) {
        new SingleTagSet(this, key, tag, _context.clock().now(),  expire);
    }

    /**
     * remove a bunch of arbitrarily selected tags, then drop all of
     * the associated tag sets.  this is very time consuming - iterating
     * across the entire _inboundTagSets map, but it should be very rare,
     * and the stats we can gather can hopefully reduce the frequency of
     * using too many session tags in the future
     *
     */
    private void clearExcess(int overage) {}

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public SessionKey consumeTag(SessionTag tag) {
        throw new UnsupportedOperationException();
    }

    /**
     * Inbound.
     *
     * Determine if we have received a session key associated with the given session tag,
     * and if so, discard it and return the decryption
     * key it was received with (via tagsReceived(...)).  returns null if no session key
     * matches
     *
     * If the return value has null data, it will have a non-null HandshakeState.
     *
     * @return a SessionKeyAndNonce or null
     */
    public SessionKeyAndNonce consumeTag(RatchetSessionTag tag) {
        RatchetTagSet tagSet;
        tagSet = _inboundTagSets.remove(tag);
        if (tagSet == null) {
            //if (_log.shouldDebug())
            //    _log.debug("IB tag not found: " + tag.toBase64());
            return null;
        }
        boolean firstInbound;
        SessionKeyAndNonce key;
        synchronized(tagSet) {
            firstInbound = !tagSet.getAcked();
            key = tagSet.consume(tag);
            if (key != null)
                tagSet.setDate(_context.clock().now());
        }
        if (key != null) {
            HandshakeState state = tagSet.getHandshakeState();
            if (state == null) {
                // TODO this should really be after decrypt...
                PublicKey pk = tagSet.getRemoteKey();
                if (pk != null) {
                    OutboundSession sess = getSession(pk);
                    if (sess != null) {
                        if (firstInbound)
                            sess.firstTagConsumed(tagSet);
                        else
                            sess.tagConsumed(tagSet);
                    } else {
                        if (_log.shouldDebug())
                            _log.debug("Tag consumed but session is gone");
                    }
                } // else null for SingleTagSets
            }
            if (_log.shouldDebug()) {
                if (state != null)
                    _log.debug("IB NSR Tag " + key.getNonce() + " consumed: " + tag.toBase64() + " from\n" + tagSet);
                else
                    _log.debug("IB ES Tag " + key.getNonce() + " consumed: " + tag.toBase64() + " from\n" + tagSet);
            }
        } else {
            if (_log.shouldWarn())
                _log.warn("tag " + tag + " not found in tagset!!! " + tagSet);
        }
        return key;
    }

    private OutboundSession getSession(PublicKey target) {
        return _outboundSessions.get(target);
    }

    /**
     * For inbound, we are Bob, NS received, replace if very old
     * For outbound, we are Alice, NSR received, never replace
     *
     * @param isInbound Bob if true
     * @return true if added
     */
    private boolean addSession(OutboundSession sess, boolean isInbound) {
        synchronized (_outboundSessions) {
            OutboundSession old = _outboundSessions.putIfAbsent(sess.getTarget(), sess);
            boolean rv = old == null;
            if (!rv) {
                if (isInbound && old.getEstablishedDate() < _context.clock().now() - SESSION_REPLACE_AGE) {
                    // He restarted with same key, or something went wrong. Start over.
                    _outboundSessions.put(sess.getTarget(), sess);
                    rv = true;
                    if (_log.shouldWarn())
                        _log.warn("Replaced old session, got new NS for " + sess.getTarget());
                } else {
                    if (_log.shouldDebug())
                        _log.debug("Not replacing existing session for " + sess.getTarget());
                }
            }
            return rv;
        }
    }

    private void removeSession(PublicKey target) {
        if (target == null) return;
        OutboundSession session = _outboundSessions.remove(target);
        if ( (session != null) && (_log.shouldWarn()) )
            _log.warn("Removing session tags with " + session.availableTags() + " available for "
                       + (session.getLastExpirationDate()-_context.clock().now())
                       + "ms more", new Exception("Removed by"));
    }

    /**
     * Aggressively expire inbound tag sets and outbound sessions
     *
     * @return number of tag sets expired (bogus as it overcounts inbound)
     */
    private int aggressiveExpire() {
        long now = _context.clock().now();

        // inbound
        int removed = 0;
        for (Iterator<RatchetTagSet> iter = _inboundTagSets.values().iterator(); iter.hasNext();) {
            RatchetTagSet ts = iter.next();
            if (ts.getExpiration() < now) {
                iter.remove();
                removed++;
            }
        }

        // outbound
        int oremoved = 0;
        int cremoved = 0;
        long exp = now - SESSION_TAG_DURATION_MS;
        for (Iterator<OutboundSession> iter = _outboundSessions.values().iterator(); iter.hasNext();) {
            OutboundSession sess = iter.next();
            oremoved += sess.expireTags(now);
            cremoved += sess.expireCallbacks(now);
            if (sess.getLastUsedDate() < exp || sess.getLastReceivedDate() < exp) {
                iter.remove();
                oremoved++;
            }
        }

        // pending outbound
        int premoved = 0;
        exp = now - SESSION_PENDING_DURATION_MS;
        synchronized (_pendingOutboundSessions) {
            for (Iterator<List<OutboundSession>> iter = _pendingOutboundSessions.values().iterator(); iter.hasNext();) {
                List<OutboundSession> pending = iter.next();
                for (Iterator<OutboundSession> liter = pending.iterator(); liter.hasNext();) {
                    OutboundSession sess = liter.next();
                    cremoved += sess.expireCallbacks(now);
                    if (sess.getEstablishedDate() < exp) {
                        liter.remove();
                        premoved++;
                    }
                }
                if (pending.isEmpty())
                    iter.remove();
            }
        }
        if ((removed > 0 || oremoved > 0 || premoved > 0 || cremoved > 0) && _log.shouldInfo())
            _log.info("Expired inbound: " + removed + ", outbound: " + oremoved +
                      ", pending: " + premoved + ", callbacks: " + cremoved);

        return removed + oremoved + premoved;
    }

    /// begin SessionTagListener ///

    /**
     *  Map the tag to this tagset.
     *
     *  @return true if added, false if dup
     */
    public boolean addTag(RatchetSessionTag tag, RatchetTagSet ts) {
        return _inboundTagSets.putIfAbsent(tag, ts) == null;
    }

    /**
     *  Remove the tag associated with this tagset.
     */
    public void expireTag(RatchetSessionTag tag, RatchetTagSet ts) {
        _inboundTagSets.remove(tag, ts);
    }

    /// end SessionTagListener ///

    /// ACKS ///

    /**
     *  @since 0.9.46
     */
    void registerCallback(PublicKey target, int id, int n, ReplyCallback callback) {
        if (_log.shouldInfo())
            _log.info("Register callback tgt " + target + " id=" + id + " n=" + n + " callback " + callback);
        OutboundSession sess = getSession(target);
        if (sess != null)
            sess.registerCallback(id, n, callback);
        else if (_log.shouldWarn())
            _log.warn("no session found for register callback");
    }

    /**
     *  @since 0.9.46
     */
    void receivedACK(PublicKey target, int id, int n) {
        OutboundSession sess = getSession(target);
        if (sess != null)
            sess.receivedACK(id, n);
        else if (_log.shouldWarn())
            _log.warn("no session found for received ack");
    }

    /**
     *  @since 0.9.46
     */
    void ackRequested(PublicKey target, int id, int n) {
        if (_log.shouldInfo())
            _log.info("rcvd ACK REQUEST id=" + id + " n=" + n);
        OutboundSession sess = getSession(target);
        if (sess != null)
            sess.ackRequested(id, n);
        else if (_log.shouldWarn())
            _log.warn("no session found for ack req");
    }


    /// end ACKS ///

    /**
     *  Return a map of PublicKey to a set of inbound RatchetTagSets for that key.
     *  Only for renderStatusHTML() below.
     */
    private Map<PublicKey, Set<RatchetTagSet>> getRatchetTagSetsByPublicKey() {
        Set<RatchetTagSet> inbound = getRatchetTagSets();
        Map<PublicKey, Set<RatchetTagSet>> inboundSets = new HashMap<PublicKey, Set<RatchetTagSet>>(inbound.size());
        // Build a map of the inbound tag sets, grouped by PublicKey
        for (RatchetTagSet ts : inbound) {
            PublicKey pk = ts.getRemoteKey();
            Set<RatchetTagSet> sets = inboundSets.get(pk);
            if (sets == null) {
                sets = new HashSet<RatchetTagSet>(4);
                inboundSets.put(pk, sets);
            }
            sets.add(ts);
        }
        return inboundSets;
    }

    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);

        // inbound
        buf.append("<h3 class=\"debug_inboundsessions\">Ratchet Inbound sessions</h3>" +
                   "<table>");
        Map<PublicKey, Set<RatchetTagSet>> inboundSets = getRatchetTagSetsByPublicKey();
        int total = 0;
        int totalSets = 0;
        long now = _context.clock().now();
        Comparator<RatchetTagSet> comp = new RatchetTagSetComparator();
        List<RatchetTagSet> sets = new ArrayList<RatchetTagSet>();
        for (Map.Entry<PublicKey, Set<RatchetTagSet>> e : inboundSets.entrySet()) {
            PublicKey skey = e.getKey();
            sets.clear();
            sets.addAll(e.getValue());
            Collections.sort(sets, comp);
            totalSets += sets.size();
            buf.append("<tr><td><b>From public key:</b> ").append(toString(skey)).append("</td>" +
                       "<td><b>Sets:</b> ").append(sets.size()).append("</td></tr>" +
                       "<tr class=\"expiry\"><td colspan=\"2\"><ul>");
            for (RatchetTagSet ts : sets) {
                synchronized(ts) {
                    int size = ts.size();
                    total += size;
                    buf.append("<li><b>ID: ");
                    int id = ts.getID();
                    if (id == RatchetTagSet.DEBUG_IB_NSR)
                        buf.append("NSR");
                    else if (id == RatchetTagSet.DEBUG_SINGLE_ES)
                        buf.append("ES");
                    else
                        buf.append(id);
                    buf.append('/').append(ts.getDebugID());
                    // inbound sets are multi-column, keep it short
                    //buf.append(" created:</b> ").append(DataHelper.formatTime(ts.getCreated()))
                    //   .append(" <b>last use:</b> ").append(DataHelper.formatTime(ts.getDate()));
                    long expires = ts.getExpiration() - now;
                    if (expires > 0)
                        buf.append(" expires in:</b> ").append(DataHelper.formatDuration2(expires)).append(" with ");
                    else
                        buf.append(" expired:</b> ").append(DataHelper.formatDuration2(0 - expires)).append(" ago with ");
                    buf.append(size).append('+').append(ts.remaining() - size).append(" tags remaining</li>");
                }
            }
            buf.append("</ul></td></tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("<tr><th colspan=\"2\">Total inbound tags: ").append(total).append(" (")
           .append(DataHelper.formatSize2(8 * total)).append("B); sets: ").append(totalSets)
           .append("; sessions: ").append(inboundSets.size())
           .append("</th></tr>\n" +
                   "</table>" +
                   "<h3 class=\"debug_outboundsessions\">Ratchet Outbound sessions</h3>" +
                   "<table>");

        // outbound
        totalSets = 0;
        Set<OutboundSession> outbound = getOutboundSessions();
        for (OutboundSession sess : outbound) {
            sets.clear();
            sets.addAll(sess.getTagSets());
            Collections.sort(sets, comp);
            totalSets += sets.size();
            buf.append("<tr class=\"debug_outboundtarget\"><td><div class=\"debug_targetinfo\"><b>To public key:</b> ").append(toString(sess.getTarget())).append("<br>" +
                       "<b>Established:</b> ").append(DataHelper.formatDuration2(now - sess.getEstablishedDate())).append(" ago<br>" +
                       "<b>Last Used:</b> ").append(DataHelper.formatDuration2(now - sess.getLastUsedDate())).append(" ago<br>" +
                       "<b>Last Rcvd:</b> ").append(DataHelper.formatDuration2(now - sess.getLastReceivedDate())).append(" ago<br>");
            SessionKey sk = sess.getCurrentKey();
            if (sk != null)
                buf.append("<b>Session key:</b> ").append(sk.toBase64());
            buf.append("</div></td>" +
                       "<td><b>Sets:</b> ").append(sets.size()).append("</td></tr>" +
                       "<tr><td colspan=\"2\"><ul>");
            for (RatchetTagSet ts : sets) {
                synchronized(ts) {
                    int size = ts.remaining();
                    buf.append("<li><b>ID: ");
                    int id = ts.getID();
                    if (id == RatchetTagSet.DEBUG_OB_NSR)
                        buf.append("NSR");
                    else
                        buf.append(id);
                    buf.append('/').append(ts.getDebugID());
                    if (ts.getAcked())
                        buf.append(" acked");
                    buf.append(" created:</b> ").append(DataHelper.formatTime(ts.getCreated()))
                       .append(" <b>last use:</b> ").append(DataHelper.formatTime(ts.getDate()));
                    long expires = ts.getExpiration() - now;
                    if (expires > 0)
                        buf.append(" <b>expires in:</b> ").append(DataHelper.formatDuration2(expires)).append(" with ");
                    else
                        buf.append(" <b>expired:</b> ").append(DataHelper.formatDuration2(0 - expires)).append(" ago with ");
                    buf.append(size).append(" tags remaining");
                    if (ts.getNextKey() != null)
                        buf.append(" <b>NK sent</b>");
                }
                buf.append("</li>");
            }
            buf.append("</ul></td></tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("<tr><th colspan=\"2\">Total sets: ").append(totalSets)
           .append("; sessions: ").append(outbound.size())
           .append("</th></tr>\n</table>");

        out.write(buf.toString());
    }

    /**
     *  For debugging
     */
    private static String toString(PublicKey target) {
        if (target == null)
            return "null";
        return target.toBase64().substring(0, 20) + "...";
    }

    /**
     *  Just for the HTML method above so we can see what's going on easier
     *  Earliest first
     */
    private static class RatchetTagSetComparator implements Comparator<RatchetTagSet>, Serializable {
         public int compare(RatchetTagSet l, RatchetTagSet r) {
             return l.getDebugID() - r.getDebugID();
        }
    }

    /**
     *  The state for a crypto session to a single public key
     */
    private class OutboundSession {
        private final PublicKey _target;
        private final HandshakeState _state;
        private ReplyCallback _NScallback;
        private ReplyCallback _NSRcallback;
        private SessionKey _currentKey;
        private final long _established;
        private long _lastUsed;
        private long _lastReceived;
        /**
         *  Before the first ack, all tagsets go here. These are never expired, we rely
         *  on the callers to call failTags() or ackTags() to remove them from this list.
         *  Actually we now do a failsafe expire.
         *  Unsynchronized, sync to use.
         *  No particular order.
         */
        private final Set<RatchetTagSet> _unackedTagSets;
        /**
         *  There is only one active outbound tagset.
         *  Synch on _unackedTagSets to access this.
         */
        private RatchetTagSet _tagSet;
        private final ConcurrentHashMap<Integer, ReplyCallback> _callbacks;
        private final LinkedBlockingQueue<Integer> _acksToSend;
        private SimpleTimer2.TimedEvent _ackTimer;
        private Destination _destination;
        /**
         *  Set to true after first tagset is acked.
         *  Upon repeated failures, we may revert back to false.
         *  This prevents us getting "stuck" forever, using tags that weren't acked
         *  to deliver the next set of tags.
         */
        private volatile boolean _acked;

        // next key
        private int _myOBKeyID = -1;
        private int _currentOBTagSetID;
        private int _myIBKeyID = -1;
        private int _currentIBTagSetID;
        private int _myIBKeySendCount;
        private KeyPair _myIBKeys;
        private KeyPair _myOBKeys;
        private NextSessionKey _myIBKey;
        // last received, may not have data, for dup check
        private NextSessionKey _hisIBKey;
        private NextSessionKey _hisOBKey;
        // last received, with data
        private NextSessionKey _hisIBKeyWithData;
        private NextSessionKey _hisOBKeyWithData;
        private SessionKey _nextIBRootKey;

        private static final int MIN_RCV_WINDOW_NSR = 12;
        private static final int MAX_RCV_WINDOW_NSR = 12;
        private static final int MIN_RCV_WINDOW_ES = 24;
        private static final int MAX_RCV_WINDOW_ES = 160;

        private static final String INFO_0 = "SessionReplyTags";
        private static final String INFO_7 = "XDHRatchetTagSet";
        private static final int MAX_SEND_ACKS = 16;
        private static final int MAX_SEND_REVERSE_KEY = 64;

        /**
         * @param d may be null
         * @param key may be null
         * @param callback may be null. Always null for IB.
         */
        public OutboundSession(PublicKey target, Destination d, SessionKey key, HandshakeState state, ReplyCallback callback) {
            _target = target;
            _destination = d;
            _currentKey = key;
            _NScallback = callback;
            _established = _context.clock().now();
            _lastUsed = _established;
            _lastReceived = _established;
            _unackedTagSets = new HashSet<RatchetTagSet>(4);
            _callbacks = new ConcurrentHashMap<Integer, ReplyCallback>();
            _acksToSend = new LinkedBlockingQueue<Integer>();
            // generate expected tagset
            byte[] ck = state.getChainingKey();
            byte[] tagsetkey = new byte[32];
            _hkdf.calculate(ck, ZEROLEN, INFO_0, tagsetkey);
            boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
            SessionKey rk = new SessionKey(ck);
            SessionKey tk = new SessionKey(tagsetkey);
            if (isInbound) {
                // We are Bob
                // This is an INBOUND NS, we make an OUTBOUND tagset for the NSR
                RatchetTagSet tagset = new RatchetTagSet(_hkdf, state,
                                                         rk, tk,
                                                         _established);
                _tagSet = tagset;
                _state = null;
                if (_log.shouldDebug())
                    _log.debug("New OB Session, rk = " + rk + " tk = " + tk + " 1st tagset:\n" + tagset);
            } else {
                // We are Alice
                // This is an OUTBOUND NS, we make an INBOUND tagset for the NSR
                RatchetTagSet tagset = new RatchetTagSet(_hkdf, RatchetSKM.this, state,
                                                         rk, tk,
                                                         _established,
                                                         MIN_RCV_WINDOW_NSR, MAX_RCV_WINDOW_NSR);
                // store the state so we can find the right session when we receive the NSR
                _state = state;
                if (_log.shouldDebug())
                    _log.debug("New IB Session, rk = " + rk + " tk = " + tk + " 1st tagset:\n" + tagset);
            }
        }

        /**
         * Inbound or outbound. Checks state.getRole() to determine.
         * For outbound (NSR rcvd by Alice), sets session to transition to ES mode outbound.
         * For inbound (NSR sent by Bob), sets up inbound ES tagset.
         *
         * @param state current state
         * @param callback only for inbound (NSR sent by Bob), may be null
         */
        void updateSession(HandshakeState state, ReplyCallback callback, SplitKeys split) {
            SessionKey rk = split.ck;
            long now = _context.clock().now();
            _lastUsed = now;
            _lastReceived = now;
            boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
            if (isInbound) {
                // We are Bob
                // This is an OUTBOUND NSR, we make an INBOUND tagset for ES
                RatchetTagSet tagset_ab = new RatchetTagSet(_hkdf, RatchetSKM.this, _target, rk, split.k_ab,
                                                            now, 0, -1,
                                                            MIN_RCV_WINDOW_ES, MAX_RCV_WINDOW_ES);
                // and a pending outbound one
                // TODO - We could just save rk and k_ba, and defer
                // creation of the OB ES tagset to firstTagConsumed() below
                RatchetTagSet tagset_ba = new RatchetTagSet(_hkdf, rk, split.k_ba,
                                                            now, 0, -1);
                if (_log.shouldDebug()) {
                    _log.debug("Update IB Session, rk = " + rk + " tk = " + split.k_ab + " ES tagset:\n" + tagset_ab);
                    _log.debug("Pending OB Session, rk = " + rk + " tk = " + split.k_ba + " ES tagset:\n" + tagset_ba);
                }
                synchronized (_unackedTagSets) {
                    _unackedTagSets.add(tagset_ba);
                    _NSRcallback = callback;
                }
            } else {
                // We are Alice
                // This is an INBOUND NSR, we make an OUTBOUND tagset for ES
                RatchetTagSet tagset_ab = new RatchetTagSet(_hkdf, rk, split.k_ab,
                                                            now, 0, -1);
                // and an inbound one
                RatchetTagSet tagset_ba = new RatchetTagSet(_hkdf, RatchetSKM.this, _target, rk, split.k_ba,
                                                            now, 0, -1,
                                                            MIN_RCV_WINDOW_ES, MAX_RCV_WINDOW_ES);
                if (_log.shouldDebug()) {
                    _log.debug("Update OB Session, rk = " + rk + " tk = " + split.k_ab + " ES tagset:\n" + tagset_ab);
                    _log.debug("Update IB Session, rk = " + rk + " tk = " + split.k_ba + " ES tagset:\n" + tagset_ba);
                }
                synchronized (_unackedTagSets) {
                    _tagSet = tagset_ab;
                    _unackedTagSets.clear();
                    // Bob received the NS, call the callback
                    if (_NScallback != null) {
                        _NScallback.onReply();
                        _NScallback = null;
                    }
                }
                // We can't destroy the original state, as more NSRs may come in
                //_state.destroy();
            }
            // kills the keys for future NSRs
            //state.destroy();
        }

        /**
         * @since 0.9.46
         */
        public void nextKeyReceived(NextSessionKey key) {
            boolean isReverse = key.isReverse();
            boolean isRequest = key.isRequest();
            boolean hasKey = key.getData() != null;
            int id = key.getID();
            synchronized (_unackedTagSets) {
                if (isReverse) {
                    // this is about my outbound tag set,
                    // and is an ack of new key sent
                    if (isRequest) {
                        if (_log.shouldWarn())
                            _log.warn("invalid req+rev in nextkey " + key);
                        return;
                    }
                    if (key.equals(_hisIBKey)) {
                        if (_log.shouldDebug())
                            _log.debug("Got dup nextkey for OB " + key);
                        return;
                    }
                    int hisLastIBKeyID;
                    if (_hisIBKey == null)
                        hisLastIBKeyID = -1;
                    else
                        hisLastIBKeyID = _hisIBKey.getID();
                    // save as it may be replaced below; will be stored after all error checks complete
                    NextSessionKey receivedKey = key;
                    if (hisLastIBKeyID != id) {
                        // got a new key, use it
                        if (hisLastIBKeyID != id - 1) {
                            if (_log.shouldWarn())
                                _log.warn("Got nextkey for OB: " + key + " expected " + (hisLastIBKeyID + 1));
                            return;
                        }
                        if (!hasKey) {
                            if (_log.shouldWarn())
                                _log.warn("Got nextkey for OB w/o key but we don't have it " + key);
                            return;
                        }
                        // save the new key with data
                        _hisIBKeyWithData = key;
                    } else {
                        if (hasKey) {
                            // got a old key id but new data?
                            if (_hisIBKeyWithData != null && _log.shouldWarn())
                                _log.warn("Got nextkey for OB with data: " + key + " didn't match previous " + _hisIBKey + " / " + _hisIBKeyWithData);
                            return;
                        } else {
                            if (_hisIBKeyWithData == null ||
                                _hisIBKeyWithData.getID() != key.getID()) {
                                if (_log.shouldWarn())
                                    _log.warn("Got nextkey for OB w/o key but we don't have it " + key);
                                return;
                            }
                            // got a old key, use it
                            key = _hisIBKeyWithData;
                        }
                    }

                    int oldtsID;
                    if (_myOBKeyID == -1 && hisLastIBKeyID == -1)
                        oldtsID = 0;
                    else
                        oldtsID = 1 + _myOBKeyID + hisLastIBKeyID;
                    RatchetTagSet oldts = null;
                    if (_tagSet != null) {
                        if (_tagSet.getID() == oldtsID)
                            oldts = _tagSet;
                    }
                    if (oldts == null) {
                        if (_log.shouldWarn())
                            _log.warn("Got nextkey for OB " + key + " but can't find existing OB tagset " + oldtsID);
                        return;
                    }
                    KeyPair nextKeys = oldts.getNextKeys();
                    if (nextKeys == null) {
                        if (oldtsID == 0 || (oldtsID & 0x01) != 0 || _myOBKeys == null) {
                            if (_log.shouldWarn())
                                _log.warn("Got nextkey for OB " + key + " but we didn't send OB keys " + oldtsID);
                            return;
                        }
                        // reuse last keys for tsIDs 2,4,6,...
                        nextKeys = _myOBKeys;
                    } else {
                        // new keys for tsIDs 0,1,3,5...
                        _myOBKeys = nextKeys;
                        _myOBKeyID++;
                    }
                    _hisIBKey = receivedKey;

                    // create new OB TS, delete old one
                    PublicKey pub = nextKeys.getPublic();
                    PrivateKey priv = nextKeys.getPrivate();
                    PrivateKey sharedSecret = ECIESAEADEngine.doDH(priv, key);
                    byte[] sk = new byte[32];
                    _hkdf.calculate(sharedSecret.getData(), ZEROLEN, INFO_7, sk);
                    SessionKey ssk = new SessionKey(sk);
                    int newtsID = oldtsID + 1;
                    RatchetTagSet ts = new RatchetTagSet(_hkdf, oldts.getNextRootKey(), ssk,
                                                         _context.clock().now(), newtsID, _myOBKeyID);
                    _tagSet = ts;
                    _currentOBTagSetID = newtsID;
                    if (_log.shouldDebug())
                        _log.debug("Got nextkey " + key + "\nratchet to new OB ES TS:\n" + ts);
                } else {
                    // this is about my inbound tag set
                    if (key.equals(_hisOBKey)) {
                        if (_log.shouldDebug())
                            _log.debug("Got dup nextkey for IB " + key);
                        return;
                    }
                    int hisLastOBKeyID;
                    if (_hisOBKey == null)
                        hisLastOBKeyID = -1;
                    else
                        hisLastOBKeyID = _hisOBKey.getID();
                    // save as it may be replaced below; will be stored after all error checks complete
                    NextSessionKey receivedKey = key;
                    if (hisLastOBKeyID != id) {
                        // got a new key, use it
                        if (hisLastOBKeyID != id - 1) {
                            if (_log.shouldWarn())
                                _log.warn("Got nextkey for IB: " + key + " expected " + (hisLastOBKeyID + 1));
                            return;
                        }
                        if (!hasKey) {
                            if (_log.shouldWarn())
                                _log.warn("Got nextkey for IB w/o key but we don't have it " + key);
                            return;
                        }
                        // save the new key with data
                        _hisOBKeyWithData = key;
                    } else {
                        if (hasKey) {
                            // got a old key id but new data?
                            if (_hisOBKeyWithData != null && _log.shouldWarn())
                                _log.warn("Got nextkey for IB with data: " + key + " didn't match previous " + _hisOBKey + " / " + _hisOBKeyWithData);
                            return;
                        } else {
                            if (_hisOBKeyWithData == null ||
                                _hisOBKeyWithData.getID() != key.getID()) {
                                if (_log.shouldWarn())
                                    _log.warn("Got nextkey for IB w/o key but we don't have it " + key);
                                return;
                            }
                            // got a old key, use it
                            key = _hisOBKeyWithData;
                        }
                    }
                    if (_nextIBRootKey == null) {
                        // first IB ES tagset never used?
                        if (_log.shouldWarn())
                            _log.warn("Got nextkey for IB but we don't have next root key " + key);
                        return;
                    }
                    // TODO find old IB TS, check usage

                    int oldtsID;
                    if (_myIBKeyID == -1 && hisLastOBKeyID == -1)
                        oldtsID = 0;
                    else
                        oldtsID = 1 + _myIBKeyID + hisLastOBKeyID;
                    // generate or reuse reverse key
                    // store next key for sending via getReverseSendKey()
                    if ((oldtsID & 0x01) == 0) {
                        // new keys for 0,2,4,...
                        if (!isRequest && _log.shouldWarn())
                            _log.warn("Got reverse w/o request, generating new key anyway " + key);
                        _myIBKeys = _context.commSystem().getXDHFactory().getKeys();
                        _myIBKeyID++;
                        _myIBKey = new NextSessionKey(_myIBKeys.getPublic().getData(), _myIBKeyID, true, false);
                    } else {
                        // reuse keys for 1,3,5...
                        if (_myIBKeys == null) {
                            if (_log.shouldWarn())
                                _log.warn("Got nextkey IB but we don't have old keys " + key);
                            return;
                        }
                        if (isRequest && _log.shouldWarn())
                            _log.warn("Got reverse with request, using old key anyway " + key);
                        _myIBKey = new NextSessionKey(_myIBKeyID, true, false);
                    }
                    _hisOBKey = receivedKey;

                    PrivateKey sharedSecret = ECIESAEADEngine.doDH(_myIBKeys.getPrivate(), key);
                    int newtsID = oldtsID + 1;
                    _currentIBTagSetID = newtsID;
                    _myIBKeySendCount = 0;
                    // create new IB TS
                    byte[] sk = new byte[32];
                    _hkdf.calculate(sharedSecret.getData(), ZEROLEN, INFO_7, sk);
                    SessionKey ssk = new SessionKey(sk);
                    // max size from the beginning
                    RatchetTagSet ts = new RatchetTagSet(_hkdf, RatchetSKM.this, _target, _nextIBRootKey, ssk,
                                                         _context.clock().now(), newtsID, _myIBKeyID,
                                                         MAX_RCV_WINDOW_ES, MAX_RCV_WINDOW_ES);
                    _nextIBRootKey = ts.getNextRootKey();
                    if (_log.shouldDebug())
                        _log.debug("Got nextkey " + key + "\nratchet to new IB ES TS:\n" + ts);
                }
            }
        }

        /**
         *  Reverse key to send, or null
         *  @since 0.9.46
         */
        private NextSessionKey getReverseSendKey() {
            synchronized (_unackedTagSets) {
                if (_myIBKey == null)
                    return null;
                if (_myIBKeySendCount > MAX_SEND_REVERSE_KEY)
                    return null;
                _myIBKeySendCount++;
                return _myIBKey;
            }
        }

        /**
         *  A tag was received for this inbound (ES) tagset.
         *
         *  @param set the inbound tagset
         *  @since 0.9.46
         */
        void tagConsumed(RatchetTagSet set) {
            _lastReceived = set.getDate();
        }

        /**
         * First tag was received for this inbound (ES) tagset.
         * Find the corresponding outbound (ES) tagset in _unackedTagSets,
         * move it to _tagSets, and remove all others.
         *
         * @param set the inbound tagset
         */
        void firstTagConsumed(RatchetTagSet set) {
            tagConsumed(set);
            SessionKey sk = set.getAssociatedKey();
            synchronized (_unackedTagSets) {
                // save next root key
                _nextIBRootKey = set.getNextRootKey();
                for (RatchetTagSet obSet : _unackedTagSets) {
                    if (obSet.getAssociatedKey().equals(sk)) {
                        if (_log.shouldDebug())
                            _log.debug("First tag received from IB ES\n" + set +
                                       "\npromoting OB ES " + obSet);
                        _unackedTagSets.clear();
                        _tagSet = obSet;
                        if (_NSRcallback != null) {
                            _NSRcallback.onReply();
                            _NSRcallback = null;
                        }
                        _lastUsed = _context.clock().now();
                        return;
                    }
                }
                if (_log.shouldDebug())
                    _log.debug("First tag received from IB ES\n" + set +
                               " but no corresponding OB ES set found, unacked size: " + _unackedTagSets.size() +
                               " acked size: " + ((_tagSet != null) ? 1 : 0));
            }
        }

        /**
         *  This is used only by renderStatusHTML().
         *  It includes both acked and unacked RatchetTagSets.
         *  @return list of RatchetTagSet objects
         */
        List<RatchetTagSet> getTagSets() {
            List<RatchetTagSet> rv;
            synchronized (_unackedTagSets) {
                rv = new ArrayList<RatchetTagSet>(_unackedTagSets);
                if (_tagSet != null)
                    rv.add(_tagSet);
            }
            return rv;
        }

        public PublicKey getTarget() {
            return _target;
        }

        /**
         *  Original outbound state, null for inbound.
         */
        public HandshakeState getHandshakeState() {
            return _state;
        }

        public SessionKey getCurrentKey() {
            return _currentKey;
        }

        public long getEstablishedDate() {
            return _established;
        }

        /**
         *  NOT updated for inbound except for NSR and first ES tag used
         */
        public long getLastUsedDate() {
            return _lastUsed;
        }

        /**
         *  ONLY updated for inbound NS/NSR/ES tag used
         *  @since 0.9.46
         */
        public long getLastReceivedDate() {
            return _lastReceived;
        }

        /**
         * Expire old tags, returning the number of tag sets removed
         */
        public int expireTags(long now) {
            int removed = 0;
            synchronized (_unackedTagSets) {
                if (_tagSet != null) {
                    if (_tagSet.getExpiration() <= now) {
                        _tagSet = null;
                        removed++;
                    }
                }
                for (Iterator<RatchetTagSet> iter = _unackedTagSets.iterator(); iter.hasNext(); ) {
                    RatchetTagSet set = iter.next();
                    if (set.getExpiration() <= now) {
                        iter.remove();
                        removed++;
                    }
                }
            }
            return removed;
        }

        public RatchetEntry consumeNext() {
            long now = _context.clock().now();
            if (_lastReceived + SESSION_TAG_DURATION_MS < now) {
                if (_log.shouldInfo())
                    _log.info("Expired OB session because IB TS expired");
                return null;
            }
            synchronized (_unackedTagSets) {
                if (_tagSet != null) {
                    if (_ackTimer != null) {
                        // cancel all ratchet-layer acks
                        _ackTimer.cancel();
                        _ackTimer = null;
                        //if (_log.shouldDebug())
                        //    _log.debug("Cancelled the ack timer");
                    }
                    synchronized(_tagSet) {
                        // use even if expired, this will reset the expiration
                        RatchetSessionTag tag = _tagSet.consumeNext();
                        if (tag != null) {
                            _lastUsed = now;
                            _tagSet.setDate(now);
                            SessionKeyAndNonce skn = _tagSet.consumeNextKey();
                            // TODO PN
                            return new RatchetEntry(tag, skn, _tagSet.getID(), 0, _tagSet.getNextKey(),
                                                    getReverseSendKey(), getAcksToSend());
                        } else if (_log.shouldInfo()) {
                            _log.info("Removing empty " + _tagSet);
                        }
                    }
                    _tagSet = null;
                }
            }
            return null;
        }

        /**
         * A timer that we will cancel when we send someting.
         * Side effect - binds this session to the supplied destination.
         *
         * @param d the far-end Destination for this PublicKey if known, or null
         * @return true if registered
         * @since 0.9.47
         */
        public boolean registerTimer(Destination d, SimpleTimer2.TimedEvent timer) {
            synchronized (_unackedTagSets) {
                if (_ackTimer != null)
                    return false;
                if (d != null) {
                    if (_destination == null)
                        _destination = d;
                    else if (_log.shouldWarn() && !_destination.equals(d))
                        _log.warn("Destination mismatch? was: " + _destination.toBase32() + " now: " + d.toBase32());
                }
                _ackTimer = timer;
                if (_log.shouldDebug())
                    _log.debug("Registered an ack timer to: " + (_destination != null ? _destination.toBase32() : _target.toString()));
            }
            return true;
        }

        /**
         * @return the far-end Destination for this PublicKey, or null
         * @since 0.9.47
         */
        public Destination getDestination() {
            synchronized (_unackedTagSets) {
                return _destination;
            }
        }

        /** @return the total number of tags in acked RatchetTagSets */
        public int availableTags() {
            long now = _context.clock().now();
            synchronized (_unackedTagSets) {
                if (_tagSet != null) {
                    synchronized(_tagSet) {
                        if (_tagSet.getExpiration() > now)
                            return _tagSet.remaining();
                    }
                }
            }
            return 0;
        }

        /**
         * Get the furthest away tag set expiration date - after which all of the  
         * tags will have expired
         *
         */
        public long getLastExpirationDate() {
            synchronized (_unackedTagSets) {
                if (_tagSet != null)
                    return _tagSet.getExpiration();
            }
            return -1;
        }

        public boolean getAckReceived() {
            return _acked;
        }

        /**
         *  @since 0.9.46
         */
        public void registerCallback(int id, int n, ReplyCallback callback) {
            Integer key = Integer.valueOf((id << 16) | n);
            ReplyCallback old = _callbacks.putIfAbsent(key, callback);
            if (old != null) {
                if (old.getExpiration() < _context.clock().now())
                    _callbacks.put(key, callback);
                else if (_log.shouldWarn())
                    _log.warn("Not replacing callback: " + old);
            }
        }

        /**
         *  @since 0.9.46
         */
        public void receivedACK(int id, int n) {
            Integer key = Integer.valueOf((id << 16) | n);
            ReplyCallback callback = _callbacks.remove(key);
            if (callback != null) {
                if (_log.shouldInfo())
                    _log.info("ACK rcvd ID " + id + " n=" + n + " callback " + callback);
                callback.onReply();
            } else {
                if (_log.shouldInfo())
                    _log.info("ACK rcvd ID " + id + " n=" + n + ", no callback");
            }
        }

        /**
         *  @since 0.9.46
         */
        public void ackRequested(int id, int n) {
            Integer key = Integer.valueOf((id << 16) | n);
            _acksToSend.offer(key);
        }

        /**
         *  @return the acks to send, non empty, or null
         *  @since 0.9.46
         */
        private List<Integer> getAcksToSend() {
            if (_acksToSend == null)
                return null;
            int sz = _acksToSend.size();
            if (sz == 0)
                return null;
            List<Integer> rv = new ArrayList<Integer>(Math.min(sz, MAX_SEND_ACKS));
            _acksToSend.drainTo(rv, MAX_SEND_ACKS);
            if (rv.isEmpty())
                return null;
            return rv;
        }

        /**
         *  @since 0.9.46
         */
        public int expireCallbacks(long now) {
            if (_callbacks.isEmpty())
                return 0;
            int rv = 0;
            for (Iterator<ReplyCallback> iter = _callbacks.values().iterator(); iter.hasNext();) {
                ReplyCallback cb = iter.next();
                if (cb.getExpiration() < now) {
                    iter.remove();
                    rv++;
                }
            }
            return rv;
        }
    }
}
