package net.i2p.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collections;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.ConcurrentHashSet;

/**
 *  A concurrent implementation using ConcurrentHashSet.
 *  The max size (K) may be temporarily exceeded due to concurrency,
 *  a pending split, or the behavior of the supplied trimmer,
 *  as explained below.
 *  The creator is responsible for splits.
 *
 *  This class has no knowledge of the DHT base used for XORing,
 *  and thus there are no validity checks in add/remove.
 *
 *  The begin and end values are immutable.
 *  All entries in this bucket will have at least one bit different
 *  from us in the range [begin, end] inclusive.
 *  Splits must be implemented by creating two new buckets
 *  and discarding this one.
 *
 *  The keys are kept in a Set and are NOT sorted by last-seen.
 *  Per-key last-seen-time, failures, etc. must be tracked elsewhere.
 *
 *  If this bucket is full (i.e. begin == end && size == max)
 *  then add() will call KBucketTrimmer.trim() do
 *  (possibly) remove older entries, and indicate whether
 *  to add the new entry. If the trimmer returns true without
 *  removing entries, this KBucket will exceed the max size.
 *
 *  Refactored from net.i2p.router.networkdb.kademlia
 */
class KBucketImpl<T extends SimpleDataStructure> implements KBucket<T> {
    /**
     *  set of Hash objects for the peers in the kbucket
     */
    private final Set<T> _entries;
    /** include if any bits equal or higher to this bit (in big endian order) */
    private final int _begin;
    /** include if no bits higher than this bit (inclusive) are set */
    private final int _end;
    private final int _max;
    private final KBucketSet.KBucketTrimmer _trimmer;
    /** when did we last shake things up */
    private long _lastChanged;
    private final I2PAppContext _context;
    
    /**
     *  All entries in this bucket will have at least one bit different
     *  from us in the range [begin, end] inclusive.
     */
    public KBucketImpl(I2PAppContext context, int begin, int end, int max, KBucketSet.KBucketTrimmer trimmer) {
        if (begin > end)
            throw new IllegalArgumentException(begin + " > " + end);
        _context = context;
        _entries = new ConcurrentHashSet(max + 4);
        _begin = begin;
        _end = end;
        _max = max;
        _trimmer = trimmer;
    }
    
    public int getRangeBegin() { return _begin; }

    public int getRangeEnd() { return _end; }

    public int getKeyCount() {
        return _entries.size();
    }
    
    /**
     *  @return an unmodifiable view; not a copy
     */
    public Set<T> getEntries() {
        return Collections.unmodifiableSet(_entries);
    }

    public void getEntries(SelectionCollector<T> collector) {
        for (T h : _entries) {
             collector.add(h);
        }
    }
    
    public void clear() {
        _entries.clear();
    }
    
    /**
     *  Sets last-changed if rv is true OR if the peer is already present.
     *  Calls the trimmer if begin == end and we are full.
     *  If begin != end then add it and caller must do bucket splitting.
     *  @return true if added
     */
    public boolean add(T peer) {
        if (_begin != _end || _entries.size() < _max ||
            _entries.contains(peer) || _trimmer.trim(this, peer)) {
            // do this even if already contains, to call setLastChanged()
            boolean rv = _entries.add(peer);
            setLastChanged();
            return rv;
        }
        return false;
    }
    
    /**
     *  @return if removed. Does NOT set lastChanged.
     */
    public boolean remove(T peer) {
        boolean rv = _entries.remove(peer);
        //if (rv)
        //    setLastChanged();
        return rv;
    }
    
    /**
     *  Update the last-changed timestamp to now.
     */
    public void setLastChanged() {
        _lastChanged = _context.clock().now();
    }

    /**
     *  The last-changed timestamp, which actually indicates last-added or last-seen.
     */
    public long getLastChanged() {
        return _lastChanged;
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append(_entries.size());
        buf.append(" entries in (").append(_begin).append(',').append(_end);
        buf.append(") : ").append(_entries.toString());
        return buf.toString();
    }
}
