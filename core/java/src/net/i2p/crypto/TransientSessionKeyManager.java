package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
public class TransientSessionKeyManager extends SessionKeyManager {
    private Log _log;
    /** Map allowing us to go from the targeted PublicKey to the OutboundSession used */
    private Map<PublicKey, OutboundSession> _outboundSessions;
    /** Map allowing us to go from a SessionTag to the containing TagSet */
    private Map<SessionTag, TagSet> _inboundTagSets;
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


    /** TagSet */
    protected Set<TagSet> getInboundTagSets() {
        synchronized (_inboundTagSets) {
            return new HashSet(_inboundTagSets.values());
        }
    }

    /** OutboundSession */
    protected Set<OutboundSession> getOutboundSessions() {
        synchronized (_outboundSessions) {
            return new HashSet(_outboundSessions.values());
        }
    }

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
     * Unused except in tests?
     */
    @Override
    public void createSession(PublicKey target, SessionKey key) {
        OutboundSession sess = new OutboundSession(target);
        sess.setCurrentKey(key);
        addSession(sess);
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
                _log.debug("Tag consumed: " + nxt + " with key: " + key.toBase64());
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
     * encryption to the target have definitely been received at the target (aka call this
     * method after receiving an ack to a message delivering them)
     *
     */
    @Override
    public void tagsDelivered(PublicKey target, SessionKey key, Set sessionTags) {
        if (_log.shouldLog(Log.DEBUG)) {
            //_log.debug("Tags delivered to set " + set + " on session " + sess);
            if (sessionTags.size() > 0)
                _log.debug("Tags delivered: " + sessionTags.size() + " for key: " + key.toBase64() + ": " + sessionTags);
        }
        OutboundSession sess = getSession(target);
        if (sess == null)
            sess = createAndReturnSession(target, key);
        sess.setCurrentKey(key);
        TagSet set = new TagSet(sessionTags, key, _context.clock().now());
        sess.addTags(set);
    }

    /**
     * Mark all of the tags delivered to the target up to this point as invalid, since the peer
     * has failed to respond when they should have.  This call essentially lets the system recover
     * from corrupted tag sets and crashes
     *
     */
    @Override
    public void failTags(PublicKey target) {
        removeSession(target);
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
                _log.debug("Receiving tag " + tag + " for key " + key.toBase64() + " / " + key.toString() + ": tagSet: " + tagSet);
            synchronized (_inboundTagSets) {
                old = (TagSet)_inboundTagSets.put(tag, tagSet);
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
                _log.warn("Multiple tags matching!  tagSet: " + tagSet + " and old tagSet: " + old + " tag: " + dupTag + "/" + dupTag.toBase64());
                _log.warn("Earlier tag set creation: " + old + ": key=" + old.getAssociatedKey().toBase64(), old.getCreatedBy());
                _log.warn("Current tag set creation: " + tagSet + ": key=" + tagSet.getAssociatedKey().toBase64(), tagSet.getCreatedBy());
            }
        }
        
        if (overage > 0)
            clearExcess(overage);

        if ( (sessionTags.size() <= 0) && (_log.shouldLog(Log.DEBUG)) )
            _log.debug("Received 0 tags for key " + key);
        if (false) aggressiveExpire();
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
        if (false) aggressiveExpire();
        synchronized (_inboundTagSets) {
            TagSet tagSet = (TagSet) _inboundTagSets.remove(tag);
            if (tagSet == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Cannot consume tag " + tag + " as it is not known");
                return null;
            }
            tagSet.consume(tag);

            SessionKey key = tagSet.getAssociatedKey();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Consuming tag " + tag.toString() + " for sessionKey " + key.toBase64() + " / " + key.toString() + " on tagSet: " + tagSet);
            return key;
        }
    }

    private OutboundSession getSession(PublicKey target) {
        synchronized (_outboundSessions) {
            return (OutboundSession) _outboundSessions.get(target);
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
            session = (OutboundSession)_outboundSessions.remove(target);
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
    public int aggressiveExpire() {
        int removed = 0;
        int remaining = 0;
        long now = _context.clock().now();
        StringBuilder buf = null;
        StringBuilder bufSummary = null;
        if (_log.shouldLog(Log.DEBUG)) {
            buf = new StringBuilder(128);
            buf.append("Expiring inbound: ");
            bufSummary = new StringBuilder(1024);
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
                        buf.append(tag.toString()).append(" @ age ").append(DataHelper.formatDuration(age));
                } else if (false && (bufSummary != null) ) {
                    bufSummary.append("\nTagSet: " + ts.toString() + ", key: " + ts.getAssociatedKey().toBase64()+"/" + ts.getAssociatedKey().toString() 
                                      + ": tag: " + tag.toString());
                }
            }
            remaining = _inboundTagSets.size();
        }
        _context.statManager().addRateData("crypto.sessionTagsRemaining", remaining, 0);
        if ( (buf != null) && (removed > 0) )
            _log.debug(buf.toString());
        if (bufSummary != null)
            _log.debug("Cleaning up with remaining: " + bufSummary.toString());

        //_log.warn("Expiring tags: [" + tagsToDrop + "]");

        synchronized (_outboundSessions) {
            for (Iterator<PublicKey> iter = _outboundSessions.keySet().iterator(); iter.hasNext();) {
                PublicKey key = iter.next();
                OutboundSession sess = _outboundSessions.get(key);
                removed += sess.expireTags();
                if (sess.availableTags() <= 0) {
                    iter.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public String renderStatusHTML() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h2>Inbound sessions</h2>");
        buf.append("<table>");
        Set<TagSet> inbound = getInboundTagSets();
        Map<SessionKey, Set<TagSet>> inboundSets = new HashMap(inbound.size());
        for (Iterator<TagSet> iter = inbound.iterator(); iter.hasNext();) {
            TagSet ts = iter.next();
            if (!inboundSets.containsKey(ts.getAssociatedKey())) inboundSets.put(ts.getAssociatedKey(), new HashSet());
            Set<TagSet> sets = inboundSets.get(ts.getAssociatedKey());
            sets.add(ts);
        }
        for (Iterator<SessionKey> iter = inboundSets.keySet().iterator(); iter.hasNext();) {
            SessionKey skey = iter.next();
            Set<TagSet> sets = inboundSets.get(skey);
            buf.append("<tr><td><b>Session key</b>: ").append(skey.toBase64()).append("</td>");
            buf.append("<td><b># Sets:</b> ").append(sets.size()).append("</td></tr>");
            buf.append("<tr><td colspan=\"2\"><ul>");
            for (Iterator<TagSet> siter = sets.iterator(); siter.hasNext();) {
                TagSet ts = siter.next();
                buf.append("<li><b>Received on:</b> ").append(new Date(ts.getDate())).append(" with ")
                   .append(ts.getTags().size()).append(" tags remaining</li>");
            }
            buf.append("</ul></td></tr>");
        }
        buf.append("</table>");

        buf.append("<h2><b>Outbound sessions</b></h2>");

        buf.append("<table>");
        Set<OutboundSession> outbound = getOutboundSessions();
        for (Iterator<OutboundSession> iter = outbound.iterator(); iter.hasNext();) {
            OutboundSession sess = iter.next();
            buf.append("<tr><td><b>Target key:</b> ").append(sess.getTarget().toString()).append("<br />");
            buf.append("<b>Established:</b> ").append(new Date(sess.getEstablishedDate())).append("<br />");
            buf.append("<b>Last Used:</b> ").append(new Date(sess.getLastUsedDate())).append("<br />");
            buf.append("<b># Sets:</b> ").append(sess.getTagSets().size()).append("</td></tr>");
            buf.append("<tr><td><b>Session key:</b> ").append(sess.getCurrentKey().toBase64()).append("</td></tr>");
            buf.append("<tr><td><ul>");
            for (Iterator<TagSet> siter = sess.getTagSets().iterator(); siter.hasNext();) {
                TagSet ts = siter.next();
                buf.append("<li><b>Sent on:</b> ").append(new Date(ts.getDate())).append(" with ").append(
                                                                                                          ts.getTags()
                                                                                                            .size())
                   .append(" tags remaining</li>");
            }
            buf.append("</ul></td></tr>");
        }
        buf.append("</table>");

        return buf.toString();
    }

    class OutboundSession {
        private PublicKey _target;
        private SessionKey _currentKey;
        private long _established;
        private long _lastUsed;
        private List<TagSet> _tagSets;

        public OutboundSession(PublicKey target) {
            this(target, null, _context.clock().now(), _context.clock().now(), new ArrayList());
        }

        OutboundSession(PublicKey target, SessionKey curKey, long established, long lastUsed, List<TagSet> tagSets) {
            _target = target;
            _currentKey = curKey;
            _established = established;
            _lastUsed = lastUsed;
            _tagSets = tagSets;
        }

        /** list of TagSet objects */
        List<TagSet> getTagSets() {
            synchronized (_tagSets) {
                return new ArrayList(_tagSets);
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
                    TagSet set = (TagSet) _tagSets.get(i);
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
                while (_tagSets.size() > 0) {
                    TagSet set = (TagSet) _tagSets.get(0);
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

        public int availableTags() {
            int tags = 0;
            long now = _context.clock().now();
            synchronized (_tagSets) {
                for (int i = 0; i < _tagSets.size(); i++) {
                    TagSet set = (TagSet) _tagSets.get(i);
                    if (set.getDate() + SESSION_TAG_DURATION_MS > now)
                        tags += set.getTags().size();
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
                    if ( (set.getDate() > last) && (set.getTags().size() > 0) ) 
                        last = set.getDate();
                }
            }
            if (last > 0)
                return last + SESSION_TAG_DURATION_MS;
            else
                return -1;
        }

        public void addTags(TagSet set) {
            _lastUsed = _context.clock().now();
            synchronized (_tagSets) {
                _tagSets.add(set);
            }
        }
    }

    static class TagSet {
        private Set<SessionTag> _sessionTags;
        private SessionKey _key;
        private long _date;
        private Exception _createdBy;

        public TagSet(Set<SessionTag> tags, SessionKey key, long date) {
            if (key == null) throw new IllegalArgumentException("Missing key");
            if (tags == null) throw new IllegalArgumentException("Missing tags");
            _sessionTags = tags;
            _key = key;
            _date = date;
            if (true) {
                long now = I2PAppContext.getGlobalContext().clock().now();
                _createdBy = new Exception("Created by: key=" + _key.toBase64() + " on " 
                                           + new Date(now) + "/" + now 
                                           + " via " + Thread.currentThread().getName());
            }
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
            if (contains(tag)) {
                _sessionTags.remove(tag);
            }
        }

        public SessionTag consumeNext() {
            if (_sessionTags.size() <= 0) {
                return null;
            }

            SessionTag first = (SessionTag) _sessionTags.iterator().next();
            _sessionTags.remove(first);
            return first;
        }
        
        public Exception getCreatedBy() { return _createdBy; }
        
        @Override
        public int hashCode() {
            long rv = 0;
            if (_key != null) rv = rv * 7 + _key.hashCode();
            rv = rv * 7 + _date;
            // no need to hashCode the tags, key + date should be enough
            return (int) rv;
        }
        
        @Override
        public boolean equals(Object o) {
            if ((o == null) || !(o instanceof TagSet)) return false;
            TagSet ts = (TagSet) o;
            return DataHelper.eq(ts.getAssociatedKey(), getAssociatedKey()) 
                   //&& DataHelper.eq(ts.getTags(), getTags())
                   && ts.getDate() == getDate();
        }
    }
}
