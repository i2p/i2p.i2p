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
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Implement the session key management, but keep everything in memory (don't write 
 * to disk).  However, this being java, we cannot guarantee that the keys aren't swapped
 * out to disk so this should not be considered secure in that sense.
 *
 */
class TransientSessionKeyManager extends SessionKeyManager {
    private Log _log;
    private Map _outboundSessions; // PublicKey --> OutboundSession
    private Map _inboundTagSets; // SessionTag --> TagSet
    protected I2PAppContext _context;

    /** 
     * Let session tags sit around for 10 minutes before expiring them.  We can now have such a large
     * value since there is the persistent session key manager.  This value is for outbound tags - 
     * inbound tags are managed by SESSION_LIFETIME_MAX_MS
     *
     */
    public final static long SESSION_TAG_DURATION_MS = 10 * 60 * 1000;
    /**
     * Keep unused inbound session tags around for up to 15 minutes (5 minutes longer than
     * session tags are used on the outbound side so that no reasonable network lag 
     * can cause failed decrypts)
     *
     */
    public final static long SESSION_LIFETIME_MAX_MS = SESSION_TAG_DURATION_MS + 2 * 60 * 1000;
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
    }
    private TransientSessionKeyManager() { this(null); }

    /** TagSet */
    protected Set getInboundTagSets() {
        synchronized (_inboundTagSets) {
            return new HashSet(_inboundTagSets.values());
        }
    }

    /** OutboundSession */
    protected Set getOutboundSessions() {
        synchronized (_outboundSessions) {
            return new HashSet(_outboundSessions.values());
        }
    }

    protected void setData(Set inboundTagSets, Set outboundSessions) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Loading " + inboundTagSets.size() + " inbound tag sets, and " 
                      + outboundSessions.size() + " outbound sessions");
        Map tagSets = new HashMap(inboundTagSets.size());
        for (Iterator iter = inboundTagSets.iterator(); iter.hasNext();) {
            TagSet ts = (TagSet) iter.next();
            for (Iterator tsIter = ts.getTags().iterator(); tsIter.hasNext();) {
                SessionTag tag = (SessionTag) tsIter.next();
                tagSets.put(tag, ts);
            }
        }
        synchronized (_inboundTagSets) {
            _inboundTagSets.clear();
            _inboundTagSets.putAll(tagSets);
        }
        Map sessions = new HashMap(outboundSessions.size());
        for (Iterator iter = outboundSessions.iterator(); iter.hasNext();) {
            OutboundSession sess = (OutboundSession) iter.next();
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
    public void createSession(PublicKey target, SessionKey key) {
        OutboundSession sess = new OutboundSession(target);
        sess.setCurrentKey(key);
        addSession(sess);
    }

    /**
     * Retrieve the next available session tag for identifying the use of the given
     * key when communicating with the target.  If this returns null, no tags are
     * available so ElG should be used with the given key (a new sessionKey should
     * NOT be used)
     *
     */
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
                _log.debug("Tag consumed: " + nxt);
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
    public void tagsDelivered(PublicKey target, SessionKey key, Set sessionTags) {
        OutboundSession sess = getSession(target);
        if (sess == null) {
            createSession(target, key);
            sess = getSession(target);
        }
        sess.setCurrentKey(key);
        TagSet set = new TagSet(sessionTags, key, _context.clock().now());
        sess.addTags(set);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Tags delivered to set " + set + " on session " + sess);
        if (sessionTags.size() > 0)
            _log.debug("Tags delivered: " + sessionTags.size() + " total = " + sess.availableTags());
    }

    /**
     * Mark all of the tags delivered to the target up to this point as invalid, since the peer
     * has failed to respond when they should have.  This call essentially lets the system recover
     * from corrupted tag sets and crashes
     *
     */
    public void failTags(PublicKey target) {
        removeSession(target);
    }

    /**
     * Accept the given tags and associate them with the given key for decryption
     *
     */
    public void tagsReceived(SessionKey key, Set sessionTags) {
        TagSet tagSet = new TagSet(sessionTags, key, _context.clock().now());
        for (Iterator iter = sessionTags.iterator(); iter.hasNext();) {
            SessionTag tag = (SessionTag) iter.next();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receiving tag " + tag + " for key " + key);
            synchronized (_inboundTagSets) {
                _inboundTagSets.put(tag, tagSet);
            }
        }
        synchronized (_inboundTagSets) {
            // todo: make this limit the tags by sessionKey and actually enforce the limit!
            int overage = _inboundTagSets.size() - MAX_INBOUND_SESSION_TAGS;
            if (overage > 0) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("TOO MANY SESSION TAGS! " + (_inboundTagSets.size()));
            }
        }

        if (sessionTags.size() <= 0) _log.debug("Received 0 tags for key " + key);
    }

    /**
     * Determine if we have received a session key associated with the given session tag,
     * and if so, discard it (but keep track for frequent dups) and return the decryption
     * key it was received with (via tagsReceived(...)).  returns null if no session key
     * matches
     *
     */
    public SessionKey consumeTag(SessionTag tag) {
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
                _log.debug("Consuming tag " + tag + " for sessionKey " + key);
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
        long now = _context.clock().now();
        Set tagsToDrop = null; // new HashSet(64);
        synchronized (_inboundTagSets) {
            for (Iterator iter = _inboundTagSets.keySet().iterator(); iter.hasNext();) {
                SessionTag tag = (SessionTag) iter.next();
                TagSet ts = (TagSet) _inboundTagSets.get(tag);
                if (ts.getDate() < now - SESSION_LIFETIME_MAX_MS) {
                    if (tagsToDrop == null)
                        tagsToDrop = new HashSet(4);
                    tagsToDrop.add(tag);
                }
            }
            if (tagsToDrop != null) {
                removed += tagsToDrop.size();
                for (Iterator iter = tagsToDrop.iterator(); iter.hasNext();)
                    _inboundTagSets.remove(iter.next());
            }
        }
        //_log.warn("Expiring tags: [" + tagsToDrop + "]");

        synchronized (_outboundSessions) {
            Set sessionsToDrop = null;
            for (Iterator iter = _outboundSessions.keySet().iterator(); iter.hasNext();) {
                PublicKey key = (PublicKey) iter.next();
                OutboundSession sess = (OutboundSession) _outboundSessions.get(key);
                removed += sess.expireTags();
                if (sess.getTagSets().size() <= 0) {
                    if (sessionsToDrop == null)
                        sessionsToDrop = new HashSet(4);
                    sessionsToDrop.add(key);
                }
            }
            if (sessionsToDrop != null) {
                for (Iterator iter = sessionsToDrop.iterator(); iter.hasNext();) {
                    OutboundSession cur = (OutboundSession)_outboundSessions.remove(iter.next());
                    if ( (cur != null) && (_log.shouldLog(Log.WARN)) )
                        _log.warn("Removing session tags with " + cur.availableTags() + " available for "
                                   + (cur.getLastExpirationDate()-_context.clock().now())
                                   + "ms more", new Exception("Removed by"));
                }
            }
        }
        return removed;
    }

    public String renderStatusHTML() {
        StringBuffer buf = new StringBuffer(1024);
        buf.append("<h2>Inbound sessions</h2>");
        buf.append("<table border=\"1\">");
        Set inbound = getInboundTagSets();
        Map inboundSets = new HashMap(inbound.size());
        for (Iterator iter = inbound.iterator(); iter.hasNext();) {
            TagSet ts = (TagSet) iter.next();
            if (!inboundSets.containsKey(ts.getAssociatedKey())) inboundSets.put(ts.getAssociatedKey(), new HashSet());
            Set sets = (Set) inboundSets.get(ts.getAssociatedKey());
            sets.add(ts);
        }
        for (Iterator iter = inboundSets.keySet().iterator(); iter.hasNext();) {
            SessionKey skey = (SessionKey) iter.next();
            Set sets = (Set) inboundSets.get(skey);
            buf.append("<tr><td><b>Session key</b>: ").append(skey.toBase64()).append("</td>");
            buf.append("<td><b># Sets:</b> ").append(sets.size()).append("</td></tr>");
            buf.append("<tr><td colspan=\"2\"><ul>");
            for (Iterator siter = sets.iterator(); siter.hasNext();) {
                TagSet ts = (TagSet) siter.next();
                buf.append("<li><b>Received on:</b> ").append(new Date(ts.getDate())).append(" with ")
                   .append(ts.getTags().size()).append(" tags remaining</li>");
            }
            buf.append("</ul></td></tr>");
        }
        buf.append("</table>");

        buf.append("<h2><b>Outbound sessions</b></h2>");

        buf.append("<table border=\"1\">");
        Set outbound = getOutboundSessions();
        for (Iterator iter = outbound.iterator(); iter.hasNext();) {
            OutboundSession sess = (OutboundSession) iter.next();
            buf.append("<tr><td><b>Target key:</b> ").append(sess.getTarget().toString()).append("<br />");
            buf.append("<b>Established:</b> ").append(new Date(sess.getEstablishedDate())).append("<br />");
            buf.append("<b>Last Used:</b> ").append(new Date(sess.getLastUsedDate())).append("<br />");
            buf.append("<b># Sets:</b> ").append(sess.getTagSets().size()).append("</td></tr>");
            buf.append("<tr><td><b>Session key:</b> ").append(sess.getCurrentKey().toBase64()).append("</td></tr>");
            buf.append("<tr><td><ul>");
            for (Iterator siter = sess.getTagSets().iterator(); siter.hasNext();) {
                TagSet ts = (TagSet) siter.next();
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
        private List _tagSets;

        public OutboundSession(PublicKey target) {
            this(target, null, _context.clock().now(), _context.clock().now(), new ArrayList());
        }

        OutboundSession(PublicKey target, SessionKey curKey, long established, long lastUsed, List tagSets) {
            _target = target;
            _currentKey = curKey;
            _established = established;
            _lastUsed = lastUsed;
            _tagSets = tagSets;
        }

        /** list of TagSet objects */
        List getTagSets() {
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
                    List sets = _tagSets;
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
                for (Iterator iter = _tagSets.iterator(); iter.hasNext();) {
                    TagSet set = (TagSet) iter.next();
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
        private Set _sessionTags;
        private SessionKey _key;
        private long _date;

        public TagSet(Set tags, SessionKey key, long date) {
            if (key == null) throw new IllegalArgumentException("Missing key");
            if (tags == null) throw new IllegalArgumentException("Missing tags");
            _sessionTags = tags;
            _key = key;
            _date = date;
        }

        public long getDate() {
            return _date;
        }

        void setDate(long when) {
            _date = when;
        }

        public Set getTags() {
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

        public int hashCode() {
            long rv = 0;
            if (_key != null) rv = rv * 7 + _key.hashCode();
            rv = rv * 7 + _date;
            // no need to hashCode the tags, key + date should be enough
            return (int) rv;
        }

        public boolean equals(Object o) {
            if ((o == null) || !(o instanceof TagSet)) return false;
            TagSet ts = (TagSet) o;
            return DataHelper.eq(ts.getAssociatedKey(), getAssociatedKey()) 
                   //&& DataHelper.eq(ts.getTags(), getTags())
                   && ts.getDate() == getDate();
        }
    }
}