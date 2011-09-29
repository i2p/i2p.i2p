package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
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
    private Log _log;
    /** Map allowing us to go from the targeted PublicKey to the OutboundSession used */
    private final Map<PublicKey, OutboundSession> _outboundSessions;
    /** Map allowing us to go from a SessionTag to the containing TagSet */
    private final Map<SessionTag, TagSet> _inboundTagSets;
    protected I2PAppContext _context;
    private volatile boolean _alive;

    /** 
     * Let session tags sit around for 10 minutes before expiring them.  We can now have such a large
     * value since there is the persistent session key manager.  This value is for outbound tags - 
     * inbound tags are managed by SESSION_LIFETIME_MAX_MS
     *
     */
    public final static long SESSION_TAG_DURATION_MS = 10 * 60 * 1000;
    /**
     * Keep unused inbound session tags around for up to 12 minutes (2 minutes longer than
     * session tags are used on the outbound side so that no reasonable network lag 
     * can cause failed decrypts)
     *
     */
    public final static long SESSION_LIFETIME_MAX_MS = SESSION_TAG_DURATION_MS + 5 * 60 * 1000;
    /**
     * a few MB? how about 16MB!
     * This is the max size of _inboundTagSets.
     */
    public final static int MAX_INBOUND_SESSION_TAGS = 500 * 1000; // this will consume at most a few MB

    /** 
     * The session key manager should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public TransientSessionKeyManager(I2PAppContext context) {
        super(context);
        _log = context.logManager().getLog(TransientSessionKeyManager.class);
        _context = context;
        _outboundSessions = new HashMap(64);
        _inboundTagSets = new HashMap(1024);
        context.statManager().createRateStat("crypto.sessionTagsExpired", "How many tags/sessions are expired?", "Encryption", new long[] { 10*60*1000, 60*60*1000, 3*60*60*1000 });
        context.statManager().createRateStat("crypto.sessionTagsRemaining", "How many tags/sessions are remaining after a cleanup?", "Encryption", new long[] { 10*60*1000, 60*60*1000, 3*60*60*1000 });
         _alive = true;
        SimpleScheduler.getInstance().addEvent(new CleanupEvent(), 60*1000);
    }
    private TransientSessionKeyManager() { this(null); }
    
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
            long expireTime = _context.clock().now() - beforeExpire;
            _context.statManager().addRateData("crypto.sessionTagsExpired", expired, expireTime);
            SimpleScheduler.getInstance().addEvent(this, 60*1000);
        }
    }


    /** TagSet - used only by HTML */
    private Set<TagSet> getInboundTagSets() {
        synchronized (_inboundTagSets) {
            return new HashSet(_inboundTagSets.values());
        }
    }

    /** OutboundSession - used only by HTML */
    private Set<OutboundSession> getOutboundSessions() {
        synchronized (_outboundSessions) {
            return new HashSet(_outboundSessions.values());
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
                          + "ms with target " + target);
            return null;
        }
        return sess.getCurrentKey();
    }

    /**
     * Associate a new session key with the specified target.  Metrics to determine
     * when to expire that key begin with this call.
     *
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
        OutboundSession sess = new OutboundSession(target);
        sess.setCurrentKey(key);
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
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("No session for " + target);
            return null;
        }
        if (sess.getCurrentKey().equals(key)) {
            SessionTag nxt = sess.consumeNext();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("OB Tag consumed: " + nxt + " with: " + key);
            return nxt;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Key does not match existing key, no tag");
        return null;
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
        if (_log.shouldLog(Log.DEBUG)) {
            //_log.debug("Tags delivered to set " + set + " on session " + sess);
            if (!sessionTags.isEmpty())
                _log.debug("Tags delivered: " + sessionTags.size() + " for key: " + key + ": " + sessionTags);
        }
        OutboundSession sess = getSession(target);
        if (sess == null)
            sess = createAndReturnSession(target, key);
        else
            sess.setCurrentKey(key);
        TagSet set = new TagSet(sessionTags, key, _context.clock().now());
        sess.addTags(set);
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
        if (sess == null)
            return;
        if(!key.equals(sess.getCurrentKey()))
            return;
        sess.failTags((TagSet)ts);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("TagSet failed: " + ts);
    }

    /**
     * Mark these tags as acked, start to use them (if we haven't already)
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        OutboundSession sess = getSession(target);
        if (sess == null)
            return;
        if(!key.equals(sess.getCurrentKey()))
            return;
        sess.ackTags((TagSet)ts);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("TagSet acked: " + ts);
    }

    /**
     * Accept the given tags and associate them with the given key for decryption
     *
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags) {
        int overage = 0;
        TagSet tagSet = new TagSet(sessionTags, key, _context.clock().now());
        TagSet old = null;
        SessionTag dupTag = null;
        for (Iterator<SessionTag> iter = sessionTags.iterator(); iter.hasNext();) {
            SessionTag tag = iter.next();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receiving tag " + tag + " for key " + key + ": tagSet: " + tagSet);
            synchronized (_inboundTagSets) {
                old = _inboundTagSets.put(tag, tagSet);
                overage = _inboundTagSets.size() - MAX_INBOUND_SESSION_TAGS;
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
                for (Iterator<SessionTag> iter = old.getTags().iterator(); iter.hasNext(); ) {
                    SessionTag tag = iter.next();
                    _inboundTagSets.remove(tag);
                }
                for (Iterator<SessionTag> iter = sessionTags.iterator(); iter.hasNext(); ) {
                    SessionTag tag = iter.next();
                    _inboundTagSets.remove(tag);
                }
            }

            if (_log.shouldLog(Log.WARN)) {
                _log.warn("Multiple tags matching!  tagSet: " + tagSet + " and old tagSet: " + old + " tag: " + dupTag + "/" + dupTag);
                _log.warn("Earlier tag set creation: " + old + ": key=" + old.getAssociatedKey());
                _log.warn("Current tag set creation: " + tagSet + ": key=" + tagSet.getAssociatedKey());
            }
        }
        
        if (overage > 0)
            clearExcess(overage);

        if ( (sessionTags.isEmpty()) && (_log.shouldLog(Log.DEBUG)) )
            _log.debug("Received 0 tags for key " + key);
        //if (false) aggressiveExpire();
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
        _log.log(Log.CRIT, "TOO MANY SESSION TAGS! Starting cleanup, overage = " + overage);
        List<TagSet> removed = new ArrayList(toRemove);
        synchronized (_inboundTagSets) {
            for (Iterator<TagSet> iter = _inboundTagSets.values().iterator(); iter.hasNext(); ) {
                TagSet set = iter.next();
                int size = set.getTags().size();
                if (size > 1000)
                    absurd++;
                if (size > 100)
                    large++;
                if (now - set.getDate() > SESSION_LIFETIME_MAX_MS)
                    old++;
                else if (now - set.getDate() < 1*60*1000)
                    recent++;

                if ((removed.size() < (toRemove)) || (now - set.getDate() > SESSION_LIFETIME_MAX_MS))
                    removed.add(set);
            }
            for (int i = 0; i < removed.size(); i++) {
                TagSet cur = (TagSet)removed.get(i);
                for (Iterator<SessionTag> iter = cur.getTags().iterator(); iter.hasNext(); ) {
                    SessionTag tag = iter.next();
                    _inboundTagSets.remove(tag);
                    tags++;
                }
            }
        }
        if (_log.shouldLog(Log.CRIT))
            _log.log(Log.CRIT, "TOO MANY SESSION TAGS!  removing " + removed 
                     + " tag sets arbitrarily, with " + tags + " tags,"
                     + "where there are " + old + " long lasting sessions, "
                     + recent + " ones created in the last minute, and "
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
        //if (false) aggressiveExpire();
        synchronized (_inboundTagSets) {
            TagSet tagSet = (TagSet) _inboundTagSets.remove(tag);
            if (tagSet == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Cannot consume IB " + tag + " as it is not known");
                return null;
            }
            tagSet.consume(tag);

            SessionKey key = tagSet.getAssociatedKey();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Consuming IB " + tag + " for " + key + " on: " + tagSet);
            return key;
        }
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
     * @return number of tag sets expired
     */
    private int aggressiveExpire() {
        int removed = 0;
        int remaining = 0;
        long now = _context.clock().now();
        StringBuilder buf = null;
        //StringBuilder bufSummary = null;
        if (_log.shouldLog(Log.DEBUG)) {
            buf = new StringBuilder(128);
            buf.append("Expiring inbound: ");
            //bufSummary = new StringBuilder(1024);
        }
        synchronized (_inboundTagSets) {
            for (Iterator<SessionTag> iter = _inboundTagSets.keySet().iterator(); iter.hasNext();) {
                SessionTag tag = iter.next();
                TagSet ts = _inboundTagSets.get(tag);
                long age = now - ts.getDate();
                if (age > SESSION_LIFETIME_MAX_MS) {
                //if (ts.getDate() < now - SESSION_LIFETIME_MAX_MS) {
                    iter.remove();
                    removed++;
                    if (buf != null)
                        buf.append(tag).append(" @ age ").append(DataHelper.formatDuration(age));
                //} else if (false && (bufSummary != null) ) {
                //    bufSummary.append("\nTagSet: " + ts + ", key: " + ts.getAssociatedKey()
                //                      + ": tag: " + tag);
                }
            }
            remaining = _inboundTagSets.size();
        }
        _context.statManager().addRateData("crypto.sessionTagsRemaining", remaining, 0);
        if ( (buf != null) && (removed > 0) )
            _log.debug(buf.toString());
        //if (bufSummary != null)
        //    _log.debug("Cleaning up with remaining: " + bufSummary.toString());

        //_log.warn("Expiring tags: [" + tagsToDrop + "]");

        synchronized (_outboundSessions) {
            for (Iterator<OutboundSession> iter = _outboundSessions.values().iterator(); iter.hasNext();) {
                OutboundSession sess = iter.next();
                removed += sess.expireTags();
                // don't kill a new session or one that's temporarily out of tags
                if (sess.getLastUsedDate() < now - (SESSION_LIFETIME_MAX_MS / 2) &&
                    sess.availableTags() <= 0) {
                    iter.remove();
                    removed++;   // just to have a non-zero return value?
                }
            }
        }
        return removed;
    }

    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h2>Inbound sessions</h2>" +
                   "<table>");
        Set<TagSet> inbound = getInboundTagSets();
        Map<SessionKey, Set<TagSet>> inboundSets = new HashMap(inbound.size());
        // Build a map of the inbound tag sets, grouped by SessionKey
        for (Iterator<TagSet> iter = inbound.iterator(); iter.hasNext();) {
            TagSet ts = iter.next();
            if (!inboundSets.containsKey(ts.getAssociatedKey())) inboundSets.put(ts.getAssociatedKey(), new HashSet());
            Set<TagSet> sets = inboundSets.get(ts.getAssociatedKey());
            sets.add(ts);
        }
        int total = 0;
        long now = _context.clock().now();
        for (Map.Entry<SessionKey, Set<TagSet>> e : inboundSets.entrySet()) {
            SessionKey skey = e.getKey();
            Set<TagSet> sets = new TreeSet(new TagSetComparator());
            sets.addAll(e.getValue());
            buf.append("<tr><td><b>Session key</b>: ").append(skey.toBase64()).append("</td>" +
                       "<td><b># Sets:</b> ").append(sets.size()).append("</td></tr>" +
                       "<tr><td colspan=\"2\"><ul>");
            for (Iterator<TagSet> siter = sets.iterator(); siter.hasNext();) {
                TagSet ts = siter.next();
                int size = ts.getTags().size();
                total += size;
                buf.append("<li><b>Received:</b> ").append(DataHelper.formatDuration(now - ts.getDate())).append(" ago with ");
                buf.append(size).append(" tags remaining</li>");
            }
            buf.append("</ul></td></tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("<tr><th colspan=\"2\">Total tags: ").append(total).append(" (");
        buf.append(DataHelper.formatSize(32*total)).append("B)</th></tr>\n" +
                   "</table>" +
                   "<h2><b>Outbound sessions</b></h2>" +
                   "<table>");
        total = 0;
        Set<OutboundSession> outbound = getOutboundSessions();
        for (Iterator<OutboundSession> iter = outbound.iterator(); iter.hasNext();) {
            OutboundSession sess = iter.next();
            Set<TagSet> sets = new TreeSet(new TagSetComparator());
            sets.addAll(sess.getTagSets());
            buf.append("<tr><td><b>Target key:</b> ").append(sess.getTarget().toBase64().substring(0, 64)).append("<br>" +
                       "<b>Established:</b> ").append(DataHelper.formatDuration(now - sess.getEstablishedDate())).append(" ago<br>" +
                       "<b>Last Used:</b> ").append(DataHelper.formatDuration(now - sess.getLastUsedDate())).append(" ago<br>" +
                       "<b>Session key:</b> ").append(sess.getCurrentKey().toBase64()).append("</td>" +
                       "<td><b># Sets:</b> ").append(sess.getTagSets().size()).append("</td></tr>" +
                       "<tr><td colspan=\"2\"><ul>");
            for (Iterator<TagSet> siter = sets.iterator(); siter.hasNext();) {
                TagSet ts = siter.next();
                int size = ts.getTags().size();
                total += size;
                buf.append("<li><b>Sent:</b> ").append(DataHelper.formatDuration(now - ts.getDate())).append(" ago with ");
                buf.append(size).append(" tags remaining; acked? ").append(ts.getAcked()).append("</li>");
            }
            buf.append("</ul></td></tr>\n");
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("<tr><th colspan=\"2\">Total tags: ").append(total).append(" (");
        buf.append(DataHelper.formatSize(32*total)).append("B)</th></tr>\n" +
                   "</table>");

        out.write(buf.toString());
    }

    /**
     *  Just for the HTML method above so we can see what's going on easier
     *  Earliest first
     */
    private static class TagSetComparator implements Comparator<TagSet> {
         public int compare(TagSet l, TagSet r) {
             return (int) (l.getDate() - r.getDate());
        }
    }

    /** fixme pass in context and change to static */
    private class OutboundSession {
        private PublicKey _target;
        private SessionKey _currentKey;
        private long _established;
        private long _lastUsed;
        /** before the first ack, all tagsets go here. These are never expired, we rely
            on the callers to call failTags() or ackTags() to remove them from this list. */
        private /* FIXME final FIXME */ List<TagSet> _unackedTagSets;
        /**
         *  As tagsets are acked, they go here.
         *  After the first ack, new tagsets go here (i.e. presumed acked)
         */
        private /* FIXME final FIXME */ List<TagSet> _tagSets;
        /** set to true after first tagset is acked */
        private boolean _acked;

        public OutboundSession(PublicKey target) {
            this(target, null, _context.clock().now(), _context.clock().now(), new ArrayList());
        }

        OutboundSession(PublicKey target, SessionKey curKey, long established, long lastUsed, List<TagSet> tagSets) {
            _target = target;
            _currentKey = curKey;
            _established = established;
            _lastUsed = lastUsed;
            _unackedTagSets = tagSets;
            _tagSets = new ArrayList();
        }

        /**
         *  @return list of TagSet objects
         *  This is used only by renderStatusHTML().
         *  It includes both acked and unacked TagSets.
         */
        List<TagSet> getTagSets() {
            List<TagSet> rv;
            synchronized (_tagSets) {
                rv = new ArrayList(_unackedTagSets);
                rv.addAll(_tagSets);
            }
            return rv;
        }

        /**
         *  got an ack for these tags
         *  For tagsets delivered after the session was acked, this is a nop
         *  because the tagset was originally placed directly on the acked list.
         */
        void ackTags(TagSet set) {
            synchronized (_tagSets) {
                if (_unackedTagSets.remove(set)) {
                    _tagSets.add(set);
                    _acked = true;
                }
            }
            set.setAcked();
        }

        /** didn't get an ack for these tags */
        void failTags(TagSet set) {
            synchronized (_tagSets) {
                _unackedTagSets.remove(set);
                _tagSets.remove(set);
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
                    int dropped = 0;
                    List<TagSet> sets = _tagSets;
                    _tagSets = new ArrayList();
                    for (int i = 0; i < sets.size(); i++) {
                        TagSet set = (TagSet) sets.get(i);
                        dropped += set.getTags().size();
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Rekeyed from " + _currentKey + " to " + key 
                                  + ": dropping " + dropped + " session tags");
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
                for (int i = 0; i < _tagSets.size(); i++) {
                    TagSet set = _tagSets.get(i);
                    if (set.getDate() + SESSION_TAG_DURATION_MS <= now) {
                        _tagSets.remove(i);
                        i--;
                        removed++;
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
                        if (tag != null) return tag;
                    } else {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("TagSet from " + new Date(set.getDate()) + " expired");
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
                            sz /= 3;
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
                for (Iterator<TagSet> iter = _tagSets.iterator(); iter.hasNext();) {
                    TagSet set = iter.next();
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
            if (_acked) {
                synchronized (_tagSets) {
                    _tagSets.add(set);
                }
            } else {
                synchronized (_unackedTagSets) {
                    _unackedTagSets.add(set);
                }
            }
        }
    }

    private static class TagSet implements TagSetHandle {
        private Set<SessionTag> _sessionTags;
        private SessionKey _key;
        private long _date;
        //private Exception _createdBy;
        /** did we get an ack for this tagset? */
        private boolean _acked;

        public TagSet(Set<SessionTag> tags, SessionKey key, long date) {
            if (key == null) throw new IllegalArgumentException("Missing key");
            if (tags == null) throw new IllegalArgumentException("Missing tags");
            _sessionTags = tags;
            _key = key;
            _date = date;
            //if (true) {
            //    long now = I2PAppContext.getGlobalContext().clock().now();
            //    _createdBy = new Exception("Created by: key=" + _key.toBase64() + " on " 
            //                               + new Date(now) + "/" + now 
            //                               + " via " + Thread.currentThread().getName());
            //}
        }

        /** when the tag set was created */
        public long getDate() {
            return _date;
        }

        void setDate(long when) {
            _date = when;
        }

        /** tags still available */
        public Set<SessionTag> getTags() {
            return _sessionTags;
        }

        public SessionKey getAssociatedKey() {
            return _key;
        }

        public boolean contains(SessionTag tag) {
            return _sessionTags.contains(tag);
        }

        public void consume(SessionTag tag) {
            _sessionTags.remove(tag);
        }

        /** let's do this without counting the elements first */
        public SessionTag consumeNext() {
            SessionTag first;
            try {
                first = _sessionTags.iterator().next();
            } catch (NoSuchElementException nsee) {
                return null;
            }
            _sessionTags.remove(first);
            return first;
        }
        
        //public Exception getCreatedBy() { return _createdBy; }

        public void setAcked() { _acked = true; }
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

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(256);
            buf.append("TagSet established: ").append(new Date(_date));
            buf.append(" Session key: ").append(_key.toBase64());
            buf.append(" Size: ").append(_sessionTags.size());
            buf.append(" Acked? ").append(_acked);
            return buf.toString();
        }
    }
}
