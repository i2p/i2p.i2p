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
import java.util.concurrent.atomic.AtomicInteger;

import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.router.RouterContext;
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
    protected final I2PAppContext _context;
    private volatile boolean _alive;
    /** for debugging */
    private final AtomicInteger _rcvTagSetID = new AtomicInteger();
    private final AtomicInteger _sentTagSetID = new AtomicInteger();
    private final HKDF _hkdf;

    /**
     * Let outbound session tags sit around for this long before expiring them.
     * Inbound tag expiration is set by SESSION_LIFETIME_MAX_MS
     */
    private final static long SESSION_TAG_DURATION_MS = 12 * 60 * 1000;

    /**
     * Keep unused inbound session tags around for this long (a few minutes longer than
     * session tags are used on the outbound side so that no reasonable network lag 
     * can cause failed decrypts)
     *
     * This is also the max idle time for an outbound session.
     */
    private final static long SESSION_LIFETIME_MAX_MS = SESSION_TAG_DURATION_MS + 3 * 60 * 1000;

    private final static long SESSION_PENDING_DURATION_MS = 5 * 60 * 1000;

    /**
     * Time to send more if we are this close to expiration
     */
    private static final long SESSION_TAG_EXPIRATION_WINDOW = 90 * 1000;

    private static final int MIN_RCV_WINDOW_NSR = 12;
    private static final int MAX_RCV_WINDOW_NSR = 24;
    private static final int MIN_RCV_WINDOW_ES = 32;
    private static final int MAX_RCV_WINDOW_ES = 96;

    private static final byte[] ZEROLEN = new byte[0];
    private static final String INFO_0 = "SessionReplyTags";


    /**
     * The session key manager should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public RatchetSKM(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(RatchetSKM.class);
        _context = context;
        _outboundSessions = new ConcurrentHashMap<PublicKey, OutboundSession>(64);
        _pendingOutboundSessions = new HashMap<PublicKey, List<OutboundSession>>(64);
        _inboundTagSets = new ConcurrentHashMap<RatchetSessionTag, RatchetTagSet>(128);
        _hkdf = new HKDF(context);
        // start the precalc of Elg2 keys if it wasn't already started
        context.eciesEngine().startup();
         _alive = true;
        new CleanupEvent();
    }

    @Override
    public void shutdown() {
         _alive = false;
        _inboundTagSets.clear();
        _outboundSessions.clear();
    }

    private class CleanupEvent extends SimpleTimer2.TimedEvent {
        public CleanupEvent() {
            // wait until outbound expiration time to start
            super(_context.simpleTimer2(), SESSION_TAG_DURATION_MS);
        }

        public void timeReached() {
            if (!_alive)
                return;
            aggressiveExpire();
            schedule(60*1000);
        }
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
     * Inbound or outbound. Checks state.getRole() to determine.
     * For outbound (NS sent), adds to list of pending inbound sessions and returns true.
     * For inbound (NS rcvd), if no other pending outbound sessions, creates one
     * and returns true, or false if one already exists.
     *
     */
    boolean createSession(PublicKey target, HandshakeState state) {
        EncType type = target.getType();
        if (type != EncType.ECIES_X25519)
            throw new IllegalArgumentException("Bad public key type " + type);
        boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
        if (isInbound) {
            // we are Bob, NS received
            OutboundSession sess = new OutboundSession(target, null, state);
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
            OutboundSession sess = new OutboundSession(target, null, state);
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
    boolean updateSession(PublicKey target, HandshakeState oldState, HandshakeState state) {
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
                if (_log.shouldDebug())
                    _log.debug("Update Bob session but no session found for "  + target);
                // TODO can we recover?
                return false;
            }
            sess.updateSession(state);
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
                            sess.updateSession(state);
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
            if (_log.shouldDebug())
                _log.debug("No OB session to " + toString(target));
            return null;
        }
        RatchetEntry rv = sess.consumeNext();
        if (_log.shouldDebug()) {
            if (rv != null)
                _log.debug("Using next key/tag " + rv + " to " + toString(target));
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
     * Take note of the fact that the given sessionTags associated with the key for
     * encryption to the target have been sent. Whether to use the tags immediately
     * (i.e. assume they will be received) or to wait until an ack, is implementation dependent.
     *
     *
     * @param sessionTags ignored, must be null
     * @return the TagSetHandle. Caller MUST subsequently call failTags() or tagsAcked()
     *         with this handle. May be null.
     */
    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
        // TODO
        if (!(key instanceof SessionKeyAndNonce)) {
            if (_log.shouldWarn())
                _log.warn("Bad SK type");
            //TODO
            return null;
        }
        SessionKeyAndNonce sk = (SessionKeyAndNonce) key;
        // if this is ever null, this is racy and needs synch
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("No session for delivered RatchetTagSet to target: " + toString(target));
///////////
            createSession(target, key);
        } else {
            sess.setCurrentKey(key);
        }
///////////
        RatchetTagSet set = new RatchetTagSet(_hkdf, key, key, _context.clock().now(), _sentTagSetID.incrementAndGet());
        sess.addTags(set);
        if (_log.shouldDebug())
            _log.debug("Tags delivered: " + set +
                       " target: " + toString(target) /** + ": " + sessionTags */ );
        return set;
    }

    /**
     * Mark all of the tags delivered to the target up to this point as invalid, since the peer
     * has failed to respond when they should have.  This call essentially lets the system recover
     * from corrupted tag sets and crashes
     *
     * @deprecated unused and rather drastic
     */
    @Override
    @Deprecated
    public void failTags(PublicKey target) {
        removeSession(target);
    }

    /**
     * Mark these tags as invalid, since the peer
     * has failed to ack them in time.
     */
    @Override
    public void failTags(PublicKey target, SessionKey key, TagSetHandle ts) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("No session for failed RatchetTagSet: " + ts);
            return;
        }
        if(!key.equals(sess.getCurrentKey())) {
            if (_log.shouldWarn())
                _log.warn("Wrong session key (wanted " + sess.getCurrentKey() + ") for failed RatchetTagSet: " + ts);
            return;
        }
        if (_log.shouldWarn())
            _log.warn("TagSet failed: " + ts);
        sess.failTags((RatchetTagSet)ts);
    }

    /**
     * Mark these tags as acked, start to use them (if we haven't already)
     * If the set was previously failed, it will be added back in.
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldWarn())
                _log.warn("No session for acked RatchetTagSet: " + ts);
            return;
        }
        if(!key.equals(sess.getCurrentKey())) {
            if (_log.shouldWarn())
                _log.warn("Wrong session key (wanted " + sess.getCurrentKey() + ") for acked RatchetTagSet: " + ts);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("TagSet acked: " + ts);
        sess.ackTags((RatchetTagSet)ts);
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
        SessionKeyAndNonce key;
        tagSet = _inboundTagSets.remove(tag);
        if (tagSet == null) {
            //if (_log.shouldDebug())
            //    _log.debug("IB tag not found: " + tag.toBase64());
            return null;
        }
        boolean firstInbound;
        synchronized(tagSet) {
            firstInbound = !tagSet.getAcked();
            key = tagSet.consume(tag);
            if (key != null)
                tagSet.setDate(_context.clock().now());
        }
        if (key != null) {
            HandshakeState state = tagSet.getHandshakeState();
            if (firstInbound) {
                if (state == null) {
                    // TODO this should really be after decrypt...
                    PublicKey pk = tagSet.getRemoteKey();
                    OutboundSession sess = getSession(pk);
                    if (sess != null) {
                        sess.firstTagConsumed(tagSet);
                    } else {
                        if (_log.shouldDebug())
                            _log.debug("First tag consumed but session is gone");
                    }
                }
            }
            if (_log.shouldDebug()) {
                if (state != null)
                    _log.debug("IB NSR Tag consumed: " + tag.toBase64() + " from: " + tagSet);
                else
                    _log.debug("IB ES Tag consumed: " + tag.toBase64() + " from: " + tagSet);
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
                if (isInbound && old.getLastUsedDate() < _context.clock().now() - SESSION_TAG_DURATION_MS - (60*1000)) {
                    _outboundSessions.put(sess.getTarget(), sess);
                    rv = true;
                    if (_log.shouldDebug())
                        _log.debug("Replaced old session about to expire for " + sess.getTarget());
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
        long exp = now - SESSION_LIFETIME_MAX_MS;

        // inbound
        int removed = 0;
        for (Iterator<RatchetTagSet> iter = _inboundTagSets.values().iterator(); iter.hasNext();) {
            RatchetTagSet ts = iter.next();
            if (ts.getDate() < exp) {
                iter.remove();
                removed++;
            }
        }
        if (removed > 0 && _log.shouldInfo())
            _log.info("Expired inbound: " + removed);

        // outbound
        int oremoved = 0;
        exp = now - (SESSION_LIFETIME_MAX_MS / 2);
        for (Iterator<OutboundSession> iter = _outboundSessions.values().iterator(); iter.hasNext();) {
            OutboundSession sess = iter.next();
            oremoved += sess.expireTags();
            if (sess.getLastUsedDate() < exp) {
                iter.remove();
                oremoved++;
            }
        }
        if (oremoved > 0 && _log.shouldInfo())
            _log.info("Expired outbound: " + oremoved);

        int premoved = 0;
        exp = now - SESSION_PENDING_DURATION_MS;
        synchronized (_pendingOutboundSessions) {
            for (Iterator<List<OutboundSession>> iter = _pendingOutboundSessions.values().iterator(); iter.hasNext();) {
                List<OutboundSession> pending = iter.next();
                for (Iterator<OutboundSession> liter = pending.iterator(); liter.hasNext();) {
                    OutboundSession sess = liter.next();
                    if (sess.getEstablishedDate() < exp) {
                        liter.remove();
                        premoved++;
                    }
                }
                if (pending.isEmpty())
                    iter.remove();
            }
        }
        if (premoved > 0 && _log.shouldInfo())
            _log.info("Expired pending: " + premoved);

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

    /**
     *  Return a map of session key to a set of inbound RatchetTagSets for that SessionKey
     */
    private Map<SessionKey, Set<RatchetTagSet>> getRatchetTagSetsBySessionKey() {
        Set<RatchetTagSet> inbound = getRatchetTagSets();
        Map<SessionKey, Set<RatchetTagSet>> inboundSets = new HashMap<SessionKey, Set<RatchetTagSet>>(inbound.size());
        // Build a map of the inbound tag sets, grouped by SessionKey
        for (RatchetTagSet ts : inbound) {
            Set<RatchetTagSet> sets = inboundSets.get(ts.getAssociatedKey());
            if (sets == null) {
                sets = new HashSet<RatchetTagSet>(4);
                inboundSets.put(ts.getAssociatedKey(), sets);
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
        Map<SessionKey, Set<RatchetTagSet>> inboundSets = getRatchetTagSetsBySessionKey();
        int total = 0;
        int totalSets = 0;
        long now = _context.clock().now();
        long exp = now - SESSION_LIFETIME_MAX_MS;
        Set<RatchetTagSet> sets = new TreeSet<RatchetTagSet>(new RatchetTagSetComparator());
        for (Map.Entry<SessionKey, Set<RatchetTagSet>> e : inboundSets.entrySet()) {
            SessionKey skey = e.getKey();
            sets.clear();
            sets.addAll(e.getValue());
            totalSets += sets.size();
            buf.append("<tr><td><b>Session key:</b> ").append(skey.toBase64()).append("</td>" +
                       "<td><b>Sets:</b> ").append(sets.size()).append("</td></tr>" +
                       "<tr class=\"expiry\"><td colspan=\"2\"><ul>");
            for (RatchetTagSet ts : sets) {
                int size = ts.size();
                total += size;
                buf.append("<li><b>ID: ").append(ts.getID());
                buf.append(" created:</b> ").append(DataHelper.formatTime(ts.getCreated()));
                long expires = ts.getDate() - exp;
                if (expires > 0)
                    buf.append(" <b>expires in:</b> ").append(DataHelper.formatDuration2(expires)).append(" with ");
                else
                    buf.append(" <b>expired:</b> ").append(DataHelper.formatDuration2(0 - expires)).append(" ago with ");
                buf.append(size).append('/').append(ts.remaining()).append(" tags remaining</li>");
            }
            buf.append("</ul></td></tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("<tr><th colspan=\"2\">Total inbound tags: ").append(total).append(" (")
           .append(DataHelper.formatSize2(32*total)).append("B); sets: ").append(totalSets)
           .append("; sessions: ").append(inboundSets.size())
           .append("</th></tr>\n" +
                   "</table>" +
                   "<h3 class=\"debug_outboundsessions\">Ratchet Outbound sessions</h3>" +
                   "<table>");

        // outbound
        totalSets = 0;
        exp = now - SESSION_TAG_DURATION_MS;
        Set<OutboundSession> outbound = getOutboundSessions();
        for (OutboundSession sess : outbound) {
            sets.clear();
            sets.addAll(sess.getTagSets());
            totalSets += sets.size();
            buf.append("<tr class=\"debug_outboundtarget\"><td><div class=\"debug_targetinfo\"><b>Target public key:</b> ").append(toString(sess.getTarget())).append("<br>" +
                       "<b>Established:</b> ").append(DataHelper.formatDuration2(now - sess.getEstablishedDate())).append(" ago<br>" +
                       "<b>Ack Received?</b> ").append(sess.getAckReceived()).append("<br>" +
                       "<b>Last Used:</b> ").append(DataHelper.formatDuration2(now - sess.getLastUsedDate())).append(" ago<br>");
            SessionKey sk = sess.getCurrentKey();
            if (sk != null)
                buf.append("<b>Session key:</b> ").append(sk.toBase64());
            buf.append("</div></td>" +
                       "<td><b># Sets:</b> ").append(sess.getTagSets().size()).append("</td></tr>" +
                       "<tr><td colspan=\"2\"><ul>");
            for (RatchetTagSet ts : sets) {
                int size = ts.remaining();
                buf.append("<li><b>ID: ").append(ts.getID())
                   .append(" created:</b> ").append(DataHelper.formatTime(ts.getCreated()));
                long expires = ts.getDate() - exp;
                if (expires > 0)
                    buf.append(" <b>expires in:</b> ").append(DataHelper.formatDuration2(expires)).append(" with ");
                else
                    buf.append(" <b>expired:</b> ").append(DataHelper.formatDuration2(0 - expires)).append(" ago with ");
                buf.append(size).append(" tags remaining; acked? ").append(ts.getAcked()).append("</li>");
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
             return l.getID() - r.getID();
        }
    }

    /**
     *  The state for a crypto session to a single public key
     */
    private class OutboundSession {
        private final PublicKey _target;
        private final HandshakeState _state;
        private SessionKey _currentKey;
        private final long _established;
        private long _lastUsed;
        /**
         *  Before the first ack, all tagsets go here. These are never expired, we rely
         *  on the callers to call failTags() or ackTags() to remove them from this list.
         *  Actually we now do a failsafe expire.
         *  Synch on _tagSets to access this.
         *  No particular order.
         */
        private final Set<RatchetTagSet> _unackedTagSets;
        /**
         *  As tagsets are acked, they go here.
         *  After the first ack, new tagsets go here (i.e. presumed acked)
         *  In order, earliest first.
         */
        private final List<RatchetTagSet> _tagSets;
        /**
         *  Set to true after first tagset is acked.
         *  Upon repeated failures, we may revert back to false.
         *  This prevents us getting "stuck" forever, using tags that weren't acked
         *  to deliver the next set of tags.
         */
        private volatile boolean _acked;
        /**
         *  Fail count
         *  Synch on _tagSets to access this.
         */
        private int _consecutiveFailures;

        private static final int MAX_FAILS = 2;

        public OutboundSession(PublicKey target, SessionKey key, HandshakeState state) {
            _target = target;
            _currentKey = key;
            _established = _context.clock().now();
            _lastUsed = _established;
            _unackedTagSets = new HashSet<RatchetTagSet>(4);
            _tagSets = new ArrayList<RatchetTagSet>(6);
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
                                                         _established, _sentTagSetID.getAndIncrement());
                _tagSets.add(tagset);
                _state = null;
                if (_log.shouldDebug())
                    _log.debug("New OB Session, rk = " + rk + " tk = " + tk + " 1st tagset: " + tagset);
            } else {
                // We are Alice
                // This is an OUTBOUND NS, we make an INBOUND tagset for the NSR
                RatchetTagSet tagset = new RatchetTagSet(_hkdf, RatchetSKM.this, state,
                                                         rk, tk,
                                                         _established, _rcvTagSetID.getAndIncrement(),
                                                         MIN_RCV_WINDOW_NSR, MAX_RCV_WINDOW_NSR);
                // store the state so we can find the right session when we receive the NSR
                _state = state;
                if (_log.shouldDebug())
                    _log.debug("New IB Session, rk = " + rk + " tk = " + tk + " 1st tagset: " + tagset);
            }
        }

        /**
         * Inbound or outbound. Checks state.getRole() to determine.
         * For outbound (NSR rcvd by Alice), sets session to transition to ES mode outbound.
         * For inbound (NSR sent by Bob), sets up inbound ES tagset.
         *
         * @param state current state
         */
        void updateSession(HandshakeState state) {
            byte[] ck = state.getChainingKey();
            byte[] k_ab = new byte[32];
            byte[] k_ba = new byte[32];
            _hkdf.calculate(ck, ZEROLEN, k_ab, k_ba, 0);
            SessionKey rk = new SessionKey(ck);
            long now = _context.clock().now();
            boolean isInbound = state.getRole() == HandshakeState.RESPONDER;
            if (isInbound) {
                // We are Bob
                // This is an OUTBOUND NSR, we make an INBOUND tagset for ES
                RatchetTagSet tagset_ab = new RatchetTagSet(_hkdf, RatchetSKM.this, _target, rk, new SessionKey(k_ab),
                                                            now, _rcvTagSetID.getAndIncrement(),
                                                            MIN_RCV_WINDOW_ES, MAX_RCV_WINDOW_ES);
                // and a pending outbound one
                RatchetTagSet tagset_ba = new RatchetTagSet(_hkdf, rk, new SessionKey(k_ba),
                                                            now, _sentTagSetID.getAndIncrement());
                if (_log.shouldDebug()) {
                    _log.debug("Update IB Session, rk = " + rk + " tk = " + Base64.encode(k_ab) + " ES tagset: " + tagset_ab);
                    _log.debug("Pending OB Session, rk = " + rk + " tk = " + Base64.encode(k_ba) + " ES tagset: " + tagset_ba);
                }
                synchronized (_tagSets) {
                    _unackedTagSets.add(tagset_ba);
                }
            } else {
                // We are Alice
                // This is an INBOUND NSR, we make an OUTBOUND tagset for ES
                RatchetTagSet tagset_ab = new RatchetTagSet(_hkdf, rk, new SessionKey(k_ab),
                                                            now, _sentTagSetID.getAndIncrement());
                // and an inbound one
                RatchetTagSet tagset_ba = new RatchetTagSet(_hkdf, RatchetSKM.this, _target, rk, new SessionKey(k_ba),
                                                            now, _rcvTagSetID.getAndIncrement(),
                                                            MIN_RCV_WINDOW_ES, MAX_RCV_WINDOW_ES);
                if (_log.shouldDebug()) {
                    _log.debug("Update OB Session, rk = " + rk + " tk = " + Base64.encode(k_ab) + " ES tagset: " + tagset_ab);
                    _log.debug("Update IB Session, rk = " + rk + " tk = " + Base64.encode(k_ba) + " ES tagset: " + tagset_ba);
                }
                synchronized (_tagSets) {
                    _tagSets.add(tagset_ab);
                    _unackedTagSets.clear();
                }
                // We can't destroy the original state, as more NSRs may come in
                //_state.destroy();
            }
            // kills the keys for future NSRs
            //state.destroy();
        }

        /**
         * First tag was received for this inbound (ES) tagset.
         * Find the corresponding outbound (ES) tagset in _unackedTagSets,
         * move it to _tagSets, and remove all others.
         *
         * @param set the inbound tagset
         */
        void firstTagConsumed(RatchetTagSet set) {
            SessionKey sk = set.getAssociatedKey();
            synchronized (_tagSets) {
                for (RatchetTagSet obSet : _unackedTagSets) {
                    if (obSet.getAssociatedKey().equals(sk)) {
                        if (_log.shouldDebug())
                            _log.debug("First tag received from IB ES " + set +
                                       ", promoting OB ES " + obSet);
                        _unackedTagSets.clear();
                        _tagSets.clear();
                        _tagSets.add(obSet);
                        return;
                    }
                }
                if (_log.shouldDebug())
                    _log.debug("First tag received from IB ES " + set +
                               " but no corresponding OB ES set found, unacked size: " + _unackedTagSets.size() +
                               " acked size: " + _tagSets.size());
            }
        }

        /**
         *  This is used only by renderStatusHTML().
         *  It includes both acked and unacked RatchetTagSets.
         *  @return list of RatchetTagSet objects
         */
        List<RatchetTagSet> getTagSets() {
            List<RatchetTagSet> rv;
            synchronized (_tagSets) {
                rv = new ArrayList<RatchetTagSet>(_unackedTagSets);
                rv.addAll(_tagSets);
            }
            return rv;
        }

        /**
         *  got an ack for these tags
         *  For tagsets delivered after the session was acked, this is a nop
         *  because the tagset was originally placed directly on the acked list.
         *  If the set was previously failed, it will be added back in.
         */
        void ackTags(RatchetTagSet set) {
            synchronized (_tagSets) {
                if (_unackedTagSets.remove(set)) {
                    // we could perhaps use it even if not previuosly in unacked,
                    // i.e. it was expired already, but _tagSets is a list not a set...
                    _tagSets.add(set);
                } else if (!_tagSets.contains(set)) {
                    // add back (sucess after fail)
                    _tagSets.add(set);
                    if (_log.shouldWarn())
                        _log.warn("Ack of unknown (previously failed?) tagset: " + set);
                } else if (set.getAcked()) {
                    if (_log.shouldWarn())
                        _log.warn("Dup ack of tagset: " + set);
                }
                _acked = true;
                _consecutiveFailures = 0;
            }
            set.setAcked();
        }

        /** didn't get an ack for these tags */
        void failTags(RatchetTagSet set) {
            synchronized (_tagSets) {
                _unackedTagSets.remove(set);
                _tagSets.remove(set);
            }
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

        public void setCurrentKey(SessionKey key) {
            _lastUsed = _context.clock().now();
            if (_currentKey != null) {
                if (!_currentKey.equals(key)) {
                    synchronized (_tagSets) {
                        if (_log.shouldWarn()) {
                            int dropped = 0;
                            for (RatchetTagSet set : _tagSets) {
                                dropped += set.remaining();
                            }
                            _log.warn("Rekeyed from " + _currentKey + " to " + key 
                                      + ": dropping " + dropped + " session tags", new Exception());
                        }
                        _acked = false;
                        _tagSets.clear();
                    }
                }
            }
            _currentKey = key;

        }

        public long getEstablishedDate() {
            return _established;
        }

        public long getLastUsedDate() {
            return _lastUsed;
        }

        /**
         * Expire old tags, returning the number of tag sets removed
         */
        public int expireTags() {
            long now = _context.clock().now();
            int removed = 0;
            synchronized (_tagSets) {
                for (Iterator<RatchetTagSet> iter = _tagSets.iterator(); iter.hasNext(); ) {
                    RatchetTagSet set = iter.next();
                    if (set.getDate() + SESSION_TAG_DURATION_MS <= now) {
                        iter.remove();
                        removed++;
                    }
                }
                // failsafe, sometimes these are sticking around, not sure why, so clean them periodically
                if ((now & 0x0f) == 0) {
                    for (Iterator<RatchetTagSet> iter = _unackedTagSets.iterator(); iter.hasNext(); ) {
                        RatchetTagSet set = iter.next();
                        if (set.getDate() + SESSION_TAG_DURATION_MS <= now) {
                            iter.remove();
                            removed++;
                        }
                    }
                }
            }
            return removed;
        }

        public RatchetEntry consumeNext() {
            long now = _context.clock().now();
            _lastUsed = now;
            synchronized (_tagSets) {
                while (!_tagSets.isEmpty()) {
                    RatchetTagSet set = _tagSets.get(0);
                    synchronized(set) {
                        if (set.getDate() + SESSION_TAG_DURATION_MS > now) {
                            RatchetSessionTag tag = set.consumeNext();
                            if (tag != null) {
                                set.setDate(now);
                                SessionKeyAndNonce skn = set.consumeNextKey();
                                // TODO key ID and PN
                                return new RatchetEntry(tag, skn, 0, 0);
                            } else if (_log.shouldInfo()) {
                                _log.info("Removing empty " + set);
                            }
                        } else {
                            if (_log.shouldInfo())
                                _log.info("Expired " + set);
                        }
                    }
                    _tagSets.remove(0);
                }
            }
            return null;
        }

        /** @return the total number of tags in acked RatchetTagSets */
        public int availableTags() {
            int tags = 0;
            long now = _context.clock().now();
            synchronized (_tagSets) {
                for (int i = 0; i < _tagSets.size(); i++) {
                    RatchetTagSet set = _tagSets.get(i);
                    if (!set.getAcked())
                        continue;
                    if (set.getDate() + SESSION_TAG_DURATION_MS > now) {
                        // or just add fixed number?
                        int sz = set.remaining();
                        tags += sz;
                    }
                }
            }
            return tags;
        }

        /**
         * Get the furthest away tag set expiration date - after which all of the  
         * tags will have expired
         *
         */
        public long getLastExpirationDate() {
            long last = 0;
            synchronized (_tagSets) {
                for (RatchetTagSet set : _tagSets) {
                    if (set.getDate() > last && set.remaining() > 0) 
                        last = set.getDate();
                }
            }
            if (last > 0)
                return last + SESSION_TAG_DURATION_MS;
            return -1;
        }

        /**
         *  Put the RatchetTagSet on the unacked list.
         */
        public void addTags(RatchetTagSet set) {
            _lastUsed = _context.clock().now();
            synchronized (_tagSets) {
                _unackedTagSets.add(set);
            }
        }

        public boolean getAckReceived() {
            return _acked;
        }
    }
}
