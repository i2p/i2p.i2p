package net.i2p.router.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.DataHelper;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Implement the session key management, but keep everything in memory (don't write 
 * to disk).  However, this being java, we cannot guarantee that the keys aren't swapped
 * out to disk so this should not be considered secure in that sense.
 *
 * The outbound and inbound sides are completely independent, each with
 * their own keys and tags.
 *
 * For a new session, outbound tags are not considered delivered until an ack is received.
 * Otherwise, the loss of the first message would render all subsequent messages
 * undecryptable. True?
 *
 * For an existing session, outbound tags are immediately considered delivered, and are
 * later revoked if the ack times out. This prevents massive stream slowdown caused by
 * repeated tag delivery after the minimum tag threshold is reached. Included tags
 * pushes messages above the ideal 1956 size by ~2KB and causes excessive fragmentation
 * and padding. As the tags are not seen by the streaming lib, they aren't accounted
 * for in the window size, and one or more of a series of large messages is likely to be dropped,
 * either due to high fragmentation or drop priorites at the tunnel OBEP.
 *
 * For this to work, the minimum tag threshold and tag delivery quanitity defined in
 * GarlicMessageBuilder must be chosen with streaming lib windows sizes in mind.
 * If a single TagSet is not delivered, there will be no stall as long as the
 * current window size is smaller than the minimum tag threshold.
 * Additional TagSets will be sent before the acked tags completely run out. See below.
 * all subsequent messages will fail to decrypt.
 * See ConnectionOptions in streaming for more information.
 *
 * There are large inefficiencies caused by the repeated delivery of tags in a new session.
 * With an initial streaming window size of 6 and 40 tags per delivery, a web server
 * would deliver up to 240 tags (7680 bytes, not including bundled leaseset, etc.)
 * in the first volley of the response.
 *
 * Could the two directions be linked somehow, such that the initial request could
 * contain a key or tags for the response?
 *
 * Should the tag threshold and quantity be adaptive?
 *
 * Todo: Switch to ConcurrentHashMaps and ReadWriteLocks, only get write lock during cleanup
 *
 */
public class TransientSessionKeyManager extends SessionKeyManager {
    private final Log _log;
    /** Map allowing us to go from the targeted PublicKey to the OutboundSession used */
    private final Map<PublicKey, OutboundSession> _outboundSessions;
    /** Map allowing us to go from a SessionTag to the containing TagSet */
    private final Map<SessionTag, TagSet> _inboundTagSets;
    protected final I2PAppContext _context;
    private volatile boolean _alive;
    /** for debugging */
    private final AtomicInteger _rcvTagSetID = new AtomicInteger();
    private final AtomicInteger _sentTagSetID = new AtomicInteger();
    private final int _tagsToSend;
    private final int _lowThreshold;

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

    /**
     * Time to send more if we are this close to expiration
     */
    private static final long SESSION_TAG_EXPIRATION_WINDOW = 90 * 1000;

    /**
     * a few MB? how about 24 MB!
     * This is the max size of _inboundTagSets.
     */
    public final static int MAX_INBOUND_SESSION_TAGS = 750 * 1000;

    /**
     *  This was 100 since 0.6.1.10 (50 before that). It's important because:
     * <pre>
     *  - Tags are 32 bytes. So it previously added 3200 bytes to an initial message.
     *  - Too many tags adds a huge overhead to short-duration connections
     *    (like http, datagrams, etc.)
     *  - Large messages have a much higher chance of being dropped due to
     *    one of their 1KB fragments being discarded by a tunnel participant.
     *  - This reduces the effective maximum datagram size because the client
     *    doesn't know when tags will be bundled, so the tag size must be
     *    subtracted from the maximum I2NP size or transport limit.
     * </pre>
     *
     *  Issues with too small a value:
     * <pre>
     *  - When tags are sent, a reply leaseset (~1KB) is always bundled.
     *    Maybe don't need to bundle more than every minute or so
     *    rather than every time?
     *  - Does the number of tags (and the threshold of 20) limit the effective
     *    streaming lib window size? Should the threshold and the number of
     *    sent tags be variable based on the message rate?
     * </pre>
     *
     *  We have to be very careful if we implement an adaptive scheme,
     *  since the key manager is per-router, not per-local-dest.
     *  Or maybe that's a bad idea, and we need to move to a per-dest manager.
     *  This needs further investigation.
     *
     *  So a value somewhat higher than the low threshold
     *  seems appropriate.
     *
     *  Use care when adjusting these values. See ConnectionOptions in streaming,
     *  and TransientSessionKeyManager in crypto, for more information.
     *
     *  @since 0.9.2 moved from GarlicMessageBuilder to per-SKM config
     */
    public static final int DEFAULT_TAGS = 40;
    /** ditto */
    public static final int LOW_THRESHOLD = 30;

    /**
     * The session key manager should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public TransientSessionKeyManager(I2PAppContext context) {
        this(context, DEFAULT_TAGS, LOW_THRESHOLD);
    }

    /**
     *  @param tagsToSend how many to send at a time, may be lower or higher than lowThreshold. 1-128
     *  @param lowThreshold below this, send more. 1-128
     *  @since 0.9.2
     */
    public TransientSessionKeyManager(I2PAppContext context, int tagsToSend, int lowThreshold) {
        super(context);
        if (tagsToSend <= 0 || tagsToSend > 128 || lowThreshold <= 0 || lowThreshold > 128)
            throw new IllegalArgumentException();
        _tagsToSend = tagsToSend;
        _lowThreshold = lowThreshold;
        _log = context.logManager().getLog(TransientSessionKeyManager.class);
        _context = context;
        _outboundSessions = new HashMap<PublicKey, OutboundSession>(64);
        _inboundTagSets = new HashMap<SessionTag, TagSet>(128);
        context.statManager().createRateStat("crypto.sessionTagsExpired", "How many tags/sessions are expired?", "Encryption", new long[] { 10*60*1000, 60*60*1000, 3*60*60*1000 });
        context.statManager().createRateStat("crypto.sessionTagsRemaining", "How many tags/sessions are remaining after a cleanup?", "Encryption", new long[] { 10*60*1000, 60*60*1000, 3*60*60*1000 });
         _alive = true;
        _context.simpleTimer2().addEvent(new CleanupEvent(), 60*1000);
    }

    @Override
    public void shutdown() {
         _alive = false;
        synchronized (_inboundTagSets) {
            _inboundTagSets.clear();
        }
        synchronized (_outboundSessions) {
            _outboundSessions.clear();
        }
    }

    private class CleanupEvent implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (!_alive)
                return;
            long beforeExpire = _context.clock().now();
            int expired = aggressiveExpire();
            int overage = _inboundTagSets.size() - MAX_INBOUND_SESSION_TAGS;
            if (overage > 0)
                clearExcess(overage);
            long expireTime = _context.clock().now() - beforeExpire;
            _context.statManager().addRateData("crypto.sessionTagsExpired", expired, expireTime);
            _context.simpleTimer2().addEvent(this, 60*1000);
        }
    }


    /** TagSet */
    private Set<TagSet> getInboundTagSets() {
        synchronized (_inboundTagSets) {
            return new HashSet<TagSet>(_inboundTagSets.values());
        }
    }

    /** OutboundSession - used only by HTML */
    private Set<OutboundSession> getOutboundSessions() {
        synchronized (_outboundSessions) {
            return new HashSet<OutboundSession>(_outboundSessions.values());
        }
    }

/****** leftover from when we had the persistent SKM
    protected void setData(Set<TagSet> inboundTagSets, Set<OutboundSession> outboundSessions) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Loading " + inboundTagSets.size() + " inbound tag sets, and " 
                      + outboundSessions.size() + " outbound sessions");
        Map<SessionTag, TagSet> tagSets = new HashMap(inboundTagSets.size());
        for (Iterator<TagSet> iter = inboundTagSets.iterator(); iter.hasNext();) {
            TagSet ts = iter.next();
            for (Iterator<SessionTag> tsIter = ts.getTags().iterator(); tsIter.hasNext();) {
                SessionTag tag = tsIter.next();
                tagSets.put(tag, ts);
            }
        }
        synchronized (_inboundTagSets) {
            _inboundTagSets.clear();
            _inboundTagSets.putAll(tagSets);
        }
        Map<PublicKey, OutboundSession> sessions = new HashMap(outboundSessions.size());
        for (Iterator<OutboundSession> iter = outboundSessions.iterator(); iter.hasNext();) {
            OutboundSession sess = iter.next();
            sessions.put(sess.getTarget(), sess);
        }
        synchronized (_outboundSessions) {
            _outboundSessions.clear();
            _outboundSessions.putAll(sessions);
        }
    }
******/

    /**
     * Retrieve the session key currently associated with encryption to the target,
     * or null if a new session key should be generated.
     *
     * Warning - don't generate a new session if this returns null, it's racy, use getCurrentOrNewKey()
     */
    @Override
    public SessionKey getCurrentKey(PublicKey target) {
        OutboundSession sess = getSession(target);
        if (sess == null) return null;
        long now = _context.clock().now();
        if (sess.getLastUsedDate() < now - SESSION_LIFETIME_MAX_MS) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Expiring old session key established on " 
                          + new Date(sess.getEstablishedDate())
                          + " but not used for "
                          + (now-sess.getLastUsedDate())
                          + "ms with target " + toString(target));
            return null;
        }
        return sess.getCurrentKey();
    }

    /**
     * Retrieve the session key currently associated with encryption to the target.
     * Generates a new session and session key if not previously exising.
     *
     * @return non-null
     * @since 0.9
     */
    @Override
    public SessionKey getCurrentOrNewKey(PublicKey target) {
        synchronized (_outboundSessions) {
            OutboundSession sess = _outboundSessions.get(target);
            if (sess != null) {
                long now = _context.clock().now();
                if (sess.getLastUsedDate() < now - SESSION_LIFETIME_MAX_MS)
                    sess = null;
            }
            if (sess == null) {
                SessionKey key = _context.keyGenerator().generateSessionKey();
                createAndReturnSession(target, key);
                return key;
            }
            return sess.getCurrentKey();
        }
    }

    /**
     * Associate a new session key with the specified target.  Metrics to determine
     * when to expire that key begin with this call.
     *
     * Racy if called after getCurrentKey() to check for a current session;
     * use getCurrentOrNewKey() in that case.
     */
    @Override
    public void createSession(PublicKey target, SessionKey key) {
        createAndReturnSession(target, key);
    }

    /**
     * Same as above but for internal use, returns OutboundSession so we don't have
     * to do a subsequent getSession()
     *
     */
    private OutboundSession createAndReturnSession(PublicKey target, SessionKey key) {
        if (_log.shouldLog(Log.INFO))
            _log.info("New OB session, sesskey: " + key + " target: " + toString(target));
        OutboundSession sess = new OutboundSession(_context, _log, target, key);
        addSession(sess);
        return sess;
    }

    /**
     * Retrieve the next available session tag for identifying the use of the given
     * key when communicating with the target.  If this returns null, no tags are
     * available so ElG should be used with the given key (a new sessionKey should
     * NOT be used)
     *
     */
    @Override
    public SessionTag consumeNextAvailableTag(PublicKey target, SessionKey key) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No session for " + toString(target));
            return null;
        }
        if (sess.getCurrentKey().equals(key)) {
            SessionTag nxt = sess.consumeNext();
            // logged in OutboundSession
            //if (nxt != null && _log.shouldLog(Log.DEBUG))
            //    _log.debug("OB Tag consumed: " + nxt + " with: " + key);
            return nxt;
        }
        if (_log.shouldLog(Log.WARN))
            _log.warn("Key does not match existing key, no tag");
        return null;
    }

    /**
     *  How many to send, IF we need to.
     *  @return the configured value (not adjusted for current available)
     *  @since 0.9.2
     */
    @Override
    public int getTagsToSend() { return _tagsToSend; };

    /**
     *  @return the configured value
     *  @since 0.9.2
     */
    @Override
    public int getLowThreshold() { return _lowThreshold; };

    /**
     *  @return true if we have less than the threshold or what we have is about to expire
     *  @since 0.9.2
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key, int lowThreshold) {
        return getAvailableTags(target, key) < lowThreshold ||
               getAvailableTimeLeft(target, key) < SESSION_TAG_EXPIRATION_WINDOW;
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
     * Here, we wait for the ack if the session is new, otherwise we use right away.
     * Will this work???
     * If the tags are pipelined sufficiently, it will.
     *
     * @return the TagSetHandle. Caller MUST subsequently call failTags() or tagsAcked()
     * with this handle.
     */
    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
        // if this is ever null, this is racy and needs synch
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No session for delivered TagSet to target: " + toString(target));
            sess = createAndReturnSession(target, key);
        } else {
            sess.setCurrentKey(key);
        }
        TagSet set = new TagSet(sessionTags, key, _context.clock().now(), _sentTagSetID.incrementAndGet());
        sess.addTags(set);
        if (_log.shouldLog(Log.DEBUG))
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
            if (_log.shouldLog(Log.WARN))
                _log.warn("No session for failed TagSet: " + ts);
            return;
        }
        if(!key.equals(sess.getCurrentKey())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Wrong session key (wanted " + sess.getCurrentKey() + ") for failed TagSet: " + ts);
            return;
        }
        if (_log.shouldLog(Log.WARN))
            _log.warn("TagSet failed: " + ts);
        sess.failTags((TagSet)ts);
    }

    /**
     * Mark these tags as acked, start to use them (if we haven't already)
     * If the set was previously failed, it will be added back in.
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("No session for acked TagSet: " + ts);
            return;
        }
        if(!key.equals(sess.getCurrentKey())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Wrong session key (wanted " + sess.getCurrentKey() + ") for acked TagSet: " + ts);
            return;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("TagSet acked: " + ts);
        sess.ackTags((TagSet)ts);
    }

    /**
     * Accept the given tags and associate them with the given key for decryption
     *
     * @param sessionTags modifiable; NOT copied
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags) {
        tagsReceived(key, sessionTags, SESSION_LIFETIME_MAX_MS);
    }

    /**
     * Accept the given tags and associate them with the given key for decryption
     *
     * @param sessionTags modifiable; NOT copied. Non-null, non-empty.
     * @param expire time from now
     * @since 0.9.7
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags, long expire) {
        TagSet tagSet = new TagSet(sessionTags, key, _context.clock().now() + expire,
                                   _rcvTagSetID.incrementAndGet());
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Received " + tagSet);
            //_log.debug("Tags: " + DataHelper.toString(sessionTags));
        }
        TagSet old = null;
        SessionTag dupTag = null;
        synchronized (_inboundTagSets) {
            for (SessionTag tag : sessionTags) {
                old = _inboundTagSets.put(tag, tagSet);
                if (old != null) {
                    if (!old.getAssociatedKey().equals(tagSet.getAssociatedKey())) {
                        _inboundTagSets.remove(tag);
                        dupTag = tag;
                        break;
                    } else {
                        old = null; // ignore the dup
                    }
                }
            }
        }

        if (old != null) {
            // drop both old and tagSet tags
            synchronized (_inboundTagSets) {
                for (SessionTag tag : old.getTags()) {
                    _inboundTagSets.remove(tag);
                }
                for (SessionTag tag : sessionTags) {
                    _inboundTagSets.remove(tag);
                }
            }

            if (_log.shouldLog(Log.WARN)) {
                _log.warn("Multiple tags matching!  tagSet: " + tagSet + " and old tagSet: " + old + " tag: " + dupTag + "/" + dupTag);
                _log.warn("Earlier tag set creation: " + old + ": key=" + old.getAssociatedKey());
                _log.warn("Current tag set creation: " + tagSet + ": key=" + tagSet.getAssociatedKey());
            }
        }
    }

    /**
     * remove a bunch of arbitrarily selected tags, then drop all of
     * the associated tag sets.  this is very time consuming - iterating
     * across the entire _inboundTagSets map, but it should be very rare,
     * and the stats we can gather can hopefully reduce the frequency of
     * using too many session tags in the future
     *
     */
    private void clearExcess(int overage) {
        long now = _context.clock().now();
        int old = 0;
        int large = 0;
        int absurd = 0;
        int recent = 0;
        int tags = 0;
        int toRemove = overage * 2;
        _log.logAlways(Log.WARN, "TOO MANY SESSION TAGS! Starting cleanup, overage = " + overage);
        List<TagSet> removed = new ArrayList<TagSet>(toRemove);
        synchronized (_inboundTagSets) {
            for (TagSet set : _inboundTagSets.values()) {
                int size = set.getTags().size();
                if (size > 1000)
                    absurd++;
                if (size > 100)
                    large++;
                if (set.getDate() - now < 3*60*1000) {
                    // expiration is 12 minutes, so these are older than 9 minutes
                    old++;
                    removed.add(set);
                    continue;
                } else if (set.getDate() - now > 8*60*1000) {
                    // expiration is 12 minutes, so these were created in last 4 minutes
                    recent++;
                    continue;
                }

                if (removed.size() < toRemove)
                    removed.add(set);
            }
            for (int i = 0; i < removed.size(); i++) {
                TagSet cur = removed.get(i);
                for (SessionTag tag : cur.getTags()) {
                    _inboundTagSets.remove(tag);
                    tags++;
                }
            }
        }
        _log.logAlways(Log.WARN, "TOO MANY SESSION TAGS!  removed " + removed.size()
                     + " tag sets arbitrarily, with " + tags + " tags,"
                     + "where there are " + old + " long lasting sessions, "
                     + recent + " ones created in the last few minutes, and "
                     + large + " sessions with more than 100 tags (and "
                     + absurd + " with more than 1000!), leaving a total of "
                     + _inboundTagSets.size() + " tags behind");
    }

    /**
     * Determine if we have received a session key associated with the given session tag,
     * and if so, discard it (but keep track for frequent dups) and return the decryption
     * key it was received with (via tagsReceived(...)).  returns null if no session key
     * matches
     *
     */
    @Override
    public SessionKey consumeTag(SessionTag tag) {
        TagSet tagSet;
        synchronized (_inboundTagSets) {
            tagSet = _inboundTagSets.remove(tag);
            if (tagSet == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Cannot consume IB " + tag + " as it is not known");
                return null;
            }
            tagSet.consume(tag);
        }

        SessionKey key = tagSet.getAssociatedKey();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("IB Tag consumed: " + tag + " from: " + tagSet);
        return key;
    }

    private OutboundSession getSession(PublicKey target) {
        synchronized (_outboundSessions) {
            return _outboundSessions.get(target);
        }
    }

    private void addSession(OutboundSession sess) {
        synchronized (_outboundSessions) {
            _outboundSessions.put(sess.getTarget(), sess);
        }
    }

    private void removeSession(PublicKey target) {
        if (target == null) return;
        OutboundSession session = null;
        synchronized (_outboundSessions) {
            session = _outboundSessions.remove(target);
        }
        if ( (session != null) && (_log.shouldLog(Log.WARN)) )
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
        int removed = 0;
        int remaining = 0;
        long now = _context.clock().now();

        synchronized (_inboundTagSets) {
            for (Iterator<TagSet> iter = _inboundTagSets.values().iterator(); iter.hasNext();) {
                TagSet ts = iter.next();
                // for inbound tagsets, getDate() is the expire time
                if (ts.getDate() <= now) {
                    iter.remove();
                    // bug, this counts inbound tags, not tag sets
                    removed++;
                }
            }
            remaining = _inboundTagSets.size();
            if (remaining > 500) {
                // find SessionKeys with a large number of TagSets and trim them
                Map<SessionKey, Set<TagSet>> inboundSets = getInboundTagSetsBySessionKey();
                for (Map.Entry<SessionKey, Set<TagSet>> e : inboundSets.entrySet()) {
                    SessionKey skey = e.getKey();
                    Set<TagSet> sets = e.getValue();
                    int count = sets.size();
                    if (count >= 10) {
                        if (_log.shouldInfo())
                            _log.info("Session key " + skey.toBase64() + " has " + count + " tag sets");
                        // for any session key with at least 10 tagsets,
                       // remove all tagsets larger than 8 tags that haven't been used and
                        // are old. The more the tagsets, the more aggressively we expire.
                        // From 9 minutes at 10 down to one minute at 50
                        long age = Math.min(5*60*1000, Math.max(60*1000, 9*60*1000 - ((count - 10) * 8*60*1000/40)));
                        for (TagSet ts : sets) {
                            Set<SessionTag> tags = ts.getTags();
                            int curSize = tags.size();
                            int origSize = ts.getOriginalSize();
                            long expires = ts.getDate();
                            if (curSize == origSize && curSize > 8 &&
                                expires < now + SESSION_LIFETIME_MAX_MS - age) {
                                if (_log.shouldInfo())
                                    _log.info("Removed unused tag set " + ts);
                                for (SessionTag tag : tags) {
                                    _inboundTagSets.remove(tag);
                                }
                                removed += curSize;
                            }
                        }
                    }
                }
                remaining = _inboundTagSets.size();
            }
        }
        _context.statManager().addRateData("crypto.sessionTagsRemaining", remaining, 0);
        if (removed > 0 && _log.shouldInfo())
            _log.info("Expired inbound: " + removed);

        int oremoved = 0;
        synchronized (_outboundSessions) {
            for (Iterator<OutboundSession> iter = _outboundSessions.values().iterator(); iter.hasNext();) {
                OutboundSession sess = iter.next();
                oremoved += sess.expireTags();
                // don't kill a new session or one that's temporarily out of tags
                if (sess.getLastUsedDate() < now - (SESSION_LIFETIME_MAX_MS / 2) &&
                    sess.availableTags() <= 0) {
                    iter.remove();
                    oremoved++;   // just to have a non-zero return value?
                }
            }
        }
        if (oremoved > 0 && _log.shouldInfo())
            _log.info("Expired outbound: " + oremoved);
        return removed + oremoved;
    }

    /**
     *  Return a map of session key to a set of inbound TagSets for that SessionKey
     *
     *  @since 0.9.33 split out from renderStatusHTML()
     */
    private Map<SessionKey, Set<TagSet>> getInboundTagSetsBySessionKey() {
        Set<TagSet> inbound = getInboundTagSets();
        Map<SessionKey, Set<TagSet>> inboundSets = new HashMap<SessionKey, Set<TagSet>>(inbound.size());
        // Build a map of the inbound tag sets, grouped by SessionKey
        for (TagSet ts : inbound) {
            Set<TagSet> sets = inboundSets.get(ts.getAssociatedKey());
            if (sets == null) {
                sets = new HashSet<TagSet>(4);
                inboundSets.put(ts.getAssociatedKey(), sets);
            }
            sets.add(ts);
        }
        return inboundSets;
    }

    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h3 class=\"debug_inboundsessions\">Inbound sessions</h3>" +
                   "<table>");
        Map<SessionKey, Set<TagSet>> inboundSets = getInboundTagSetsBySessionKey();
        int total = 0;
        int totalSets = 0;
        long now = _context.clock().now();
        Set<TagSet> sets = new TreeSet<TagSet>(new TagSetComparator());
        for (Map.Entry<SessionKey, Set<TagSet>> e : inboundSets.entrySet()) {
            SessionKey skey = e.getKey();
            sets.clear();
            sets.addAll(e.getValue());
            totalSets += sets.size();
            buf.append("<tr><td><b>Session key:</b> ").append(skey.toBase64()).append("</td>" +
                       "<td><b>Sets:</b> ").append(sets.size()).append("</td></tr>" +
                       "<tr class=\"expiry\"><td colspan=\"2\"><ul>");
            for (TagSet ts : sets) {
                int size = ts.getTags().size();
                total += size;
                buf.append("<li><b>ID: ").append(ts.getID());
                long expires = ts.getDate() - now;
                if (expires > 0)
                    buf.append(" expires in:</b> ").append(DataHelper.formatDuration2(expires)).append(" with ");
                else
                    buf.append(" expired:</b> ").append(DataHelper.formatDuration2(0 - expires)).append(" ago with ");
                buf.append(size).append('/').append(ts.getOriginalSize()).append(" tags remaining</li>");
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
                   "<h3 class=\"debug_outboundsessions\">Outbound sessions</h3>" +
                   "<table>");
        total = 0;
        totalSets = 0;
        Set<OutboundSession> outbound = getOutboundSessions();
        for (Iterator<OutboundSession> iter = outbound.iterator(); iter.hasNext();) {
            OutboundSession sess = iter.next();
            sets.clear();
            sets.addAll(sess.getTagSets());
            totalSets += sets.size();
            buf.append("<tr class=\"debug_outboundtarget\"><td><div class=\"debug_targetinfo\"><b>Target public key:</b> ").append(toString(sess.getTarget())).append("<br>" +
                       "<b>Established:</b> ").append(DataHelper.formatDuration2(now - sess.getEstablishedDate())).append(" ago<br>" +
                       "<b>Ack Received?</b> ").append(sess.getAckReceived()).append("<br>" +
                       "<b>Last Used:</b> ").append(DataHelper.formatDuration2(now - sess.getLastUsedDate())).append(" ago<br>" +
                       "<b>Session key:</b> ").append(sess.getCurrentKey().toBase64()).append("</div></td>" +
                       "<td><b># Sets:</b> ").append(sess.getTagSets().size()).append("</td></tr>" +
                       "<tr><td colspan=\"2\"><ul>");
            for (Iterator<TagSet> siter = sets.iterator(); siter.hasNext();) {
                TagSet ts = siter.next();
                int size = ts.getTags().size();
                total += size;
                buf.append("<li><b>ID: ").append(ts.getID())
                   .append(" Sent:</b> ").append(DataHelper.formatDuration2(now - ts.getDate())).append(" ago with ");
                buf.append(size).append('/').append(ts.getOriginalSize()).append(" tags remaining; acked? ").append(ts.getAcked()).append("</li>");
            }
            buf.append("</ul></td></tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("<tr><th colspan=\"2\">Total outbound tags: ").append(total).append(" (")
           .append(DataHelper.formatSize2(32*total)).append("B); sets: ").append(totalSets)
           .append("; sessions: ").append(outbound.size())
           .append("</th></tr>\n</table>");

        out.write(buf.toString());
    }

    /**
     *  For debugging
     *  @since 0.9
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
    private static class TagSetComparator implements Comparator<TagSet>, Serializable {
         public int compare(TagSet l, TagSet r) {
             int rv = (int) (l.getDate() - r.getDate());
             if (rv != 0)
                 return rv;
             return l.hashCode() - r.hashCode();
        }
    }

    /**
     *  The state for a crypto session to a single public key
     */
    private static class OutboundSession {
        private final I2PAppContext _context;
        private final Log _log;
        private final PublicKey _target;
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
        private final Set<TagSet> _unackedTagSets;
        /**
         *  As tagsets are acked, they go here.
         *  After the first ack, new tagsets go here (i.e. presumed acked)
         *  In order, earliest first.
         */
        private final List<TagSet> _tagSets;
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

        public OutboundSession(I2PAppContext ctx, Log log, PublicKey target, SessionKey key) {
            _context = ctx;
            _log = log;
            _target = target;
            _currentKey = key;
            _established = ctx.clock().now();
            _lastUsed = _established;
            _unackedTagSets = new HashSet<TagSet>(4);
            _tagSets = new ArrayList<TagSet>(6);
        }

        /**
         *  @return list of TagSet objects
         *  This is used only by renderStatusHTML().
         *  It includes both acked and unacked TagSets.
         */
        List<TagSet> getTagSets() {
            List<TagSet> rv;
            synchronized (_tagSets) {
                rv = new ArrayList<TagSet>(_unackedTagSets);
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
        void ackTags(TagSet set) {
            synchronized (_tagSets) {
                if (_unackedTagSets.remove(set)) {
                    // we could perhaps use it even if not previuosly in unacked,
                    // i.e. it was expired already, but _tagSets is a list not a set...
                    _tagSets.add(set);
                } else if (!_tagSets.contains(set)) {
                    // add back (sucess after fail)
                    _tagSets.add(set);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Ack of unknown (previously failed?) tagset: " + set);
                } else if (set.getAcked()) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dup ack of tagset: " + set);
                }
                _acked = true;
                _consecutiveFailures = 0;
            }
            set.setAcked();
        }

        /** didn't get an ack for these tags */
        void failTags(TagSet set) {
            synchronized (_tagSets) {
                _unackedTagSets.remove(set);
                if (_tagSets.remove(set)) {
                    if (++_consecutiveFailures >= MAX_FAILS) {
                        // revert back to non-speculative ack mode,
                        // and force full ElG next time by reclassifying all tagsets that weren't really acked
                        _acked = false;
                        int acked = 0;
                        int unacked = 0;
                        for (Iterator<TagSet> iter = _tagSets.iterator(); iter.hasNext(); ) {
                            TagSet ts = iter.next();
                            if (!ts.getAcked()) {
                                iter.remove();
                                _unackedTagSets.add(ts);
                                unacked++;
                            } else {
                                acked++;
                            }
                        }
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(_consecutiveFailures + " consecutive failed tagset deliveries to " + _currentKey
                                      + ": reverting to full ElG and un-acking " + unacked + " unacked tag sets, with "
                                      + acked + " remaining acked tag sets");
                    }
                }
            }
        }

        public PublicKey getTarget() {
            return _target;
        }

        public SessionKey getCurrentKey() {
            return _currentKey;
        }

        public void setCurrentKey(SessionKey key) {
            _lastUsed = _context.clock().now();
            if (_currentKey != null) {
                if (!_currentKey.equals(key)) {
                    synchronized (_tagSets) {
                        if (_log.shouldLog(Log.WARN)) {
                            int dropped = 0;
                            for (TagSet set : _tagSets) {
                                dropped += set.getTags().size();
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
                for (Iterator<TagSet> iter = _tagSets.iterator(); iter.hasNext(); ) {
                    TagSet set = iter.next();
                    if (set.getDate() + SESSION_TAG_DURATION_MS <= now) {
                        iter.remove();
                        removed++;
                    }
                }
                // failsafe, sometimes these are sticking around, not sure why, so clean them periodically
                if ((now & 0x0f) == 0) {
                    for (Iterator<TagSet> iter = _unackedTagSets.iterator(); iter.hasNext(); ) {
                        TagSet set = iter.next();
                        if (set.getDate() + SESSION_TAG_DURATION_MS <= now) {
                            iter.remove();
                            removed++;
                        }
                    }
                }
            }
            return removed;
        }

        public SessionTag consumeNext() {
            long now = _context.clock().now();
            _lastUsed = now;
            synchronized (_tagSets) {
                while (!_tagSets.isEmpty()) {
                    TagSet set = _tagSets.get(0);
                    if (set.getDate() + SESSION_TAG_DURATION_MS > now) {
                        SessionTag tag = set.consumeNext();
                        if (tag != null) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("OB Tag consumed: " + tag + " from: " + set);
                            return tag;
                        } else if (_log.shouldLog(Log.INFO)) {
                            _log.info("Removing empty " + set);
                        }
                    } else {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Expired " + set);
                    }
                    _tagSets.remove(0);
                }
            }
            return null;
        }

        /** @return the total number of tags in acked TagSets */
        public int availableTags() {
            int tags = 0;
            long now = _context.clock().now();
            synchronized (_tagSets) {
                for (int i = 0; i < _tagSets.size(); i++) {
                    TagSet set = _tagSets.get(i);
                    if (set.getDate() + SESSION_TAG_DURATION_MS > now) {
                        int sz = set.getTags().size();
                        // so tags are sent when the acked tags are below
                        // 30, 17, and 4.
                        if (!set.getAcked())
                            // round up so we don't report 0 when we have 1 or 2 remaining and get the session removed
                            sz = (sz + 2) / 3;
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
                for (TagSet set : _tagSets) {
                    if ( (set.getDate() > last) && (!set.getTags().isEmpty()) ) 
                        last = set.getDate();
                }
            }
            if (last > 0)
                return last + SESSION_TAG_DURATION_MS;
            else
                return -1;
        }

        /**
         *  If the session has never been acked, put the TagSet on the unacked list.
         *  Otherwise, consider it good right away.
         */
        public void addTags(TagSet set) {
            _lastUsed = _context.clock().now();
            synchronized (_tagSets) {
                if (_acked)
                    _tagSets.add(set);
                else
                    _unackedTagSets.add(set);
            }
        }

        /** @since 0.9 for debugging */
        public boolean getAckReceived() {
            return _acked;
        }
    }

    private static class TagSet implements TagSetHandle {
        private final Set<SessionTag> _sessionTags;
        private final SessionKey _key;
        private final long _date;
        private final int _id;
        private final int _origSize;
        //private Exception _createdBy;
        /** did we get an ack for this tagset? Only for outbound tagsets */
        private boolean _acked;

        /**
         *  @param date For inbound: when the TagSet will expire; for outbound: creation time
         */
        public TagSet(Set<SessionTag> tags, SessionKey key, long date, int id) {
            if (key == null) throw new IllegalArgumentException("Missing key");
            if (tags == null) throw new IllegalArgumentException("Missing tags");
            _sessionTags = tags;
            _key = key;
            _date = date;
            _id = id;
            _origSize = tags.size();
            //if (true) {
            //    long now = I2PAppContext.getGlobalContext().clock().now();
            //    _createdBy = new Exception("Created by: key=" + _key.toBase64() + " on " 
            //                               + new Date(now) + "/" + now 
            //                               + " via " + Thread.currentThread().getName());
            //}
        }

        /**
         *  For inbound: when the TagSet will expire; for outbound: creation time
         */
        public long getDate() {
            return _date;
        }

        /** @since 0.9.3 for debugging */
        public int getOriginalSize() {
            return _origSize;
        }

        //void setDate(long when) {
        //    _date = when;
        //}

        /** tags still available */
        public Set<SessionTag> getTags() {
            return _sessionTags;
        }

        public SessionKey getAssociatedKey() {
            return _key;
        }

        /**
         *  Caller must synch.
         */
        public void consume(SessionTag tag) {
            _sessionTags.remove(tag);
        }

        /**
         *  For outbound only.
         *  Caller must synch.
         *  @return a tag or null
         */
        public SessionTag consumeNext() {
            Iterator<SessionTag> iter = _sessionTags.iterator();
            if (!iter.hasNext())
                return null;
            SessionTag first = iter.next();
            iter.remove();
            return first;
        }

        //public Exception getCreatedBy() { return _createdBy; }

        /**
         *  For outbound only.
         */
        public void setAcked() { _acked = true; }

        /**
         *  For outbound only.
         */
        public boolean getAcked() { return _acked; }

/******    this will return a dup if two in the same ms, so just use java
        @Override
        public int hashCode() {
            long rv = 0;
            if (_key != null) rv = _key.hashCode();
            rv = rv * 7 + _date;
            // no need to hashCode the tags, key + date should be enough
            return (int) rv;
        }

        @Override
        public boolean equals(Object o) {
            if ((o == null) || !(o instanceof TagSet)) return false;
            TagSet ts = (TagSet) o;
            return DataHelper.eq(ts.getAssociatedKey(), _key)
                   //&& DataHelper.eq(ts.getTags(), getTags())
                   && ts.getDate() == _date;
        }
******/

        /** @since 0.9 for debugging */
        public int getID() {
            return _id;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(256);
            buf.append("TagSet #").append(_id).append(" created: ").append(new Date(_date));
            buf.append(" Session key: ").append(_key);
            buf.append(" Size: ").append(_sessionTags.size());
            buf.append('/').append(_origSize);
            buf.append(" Acked? ").append(_acked);
            return buf.toString();
        }
    }
}
