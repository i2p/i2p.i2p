package net.i2p.router.networkdb.kademlia;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.Hash;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.RouterContext;

/**
 * Data related to a particular search
 *
 */
class SearchState {
    private final RouterContext _context;
    private final Set<Hash> _pendingPeers;
    private final Map<Hash, Long> _pendingPeerTimes;
    private final Set<Hash> _attemptedPeers;
    private final Set<Hash> _failedPeers;
    private final Set<Hash> _successfulPeers;
    private final Set<Hash> _repliedPeers;
    private final Hash _searchKey;
    private volatile long _completed;
    private volatile long _started;
    private volatile boolean _aborted;
    
    public SearchState(RouterContext context, Hash key) {
        _context = context;
        _searchKey = key;
        _pendingPeers = new HashSet<Hash>(16);
        _attemptedPeers = new HashSet<Hash>(16);
        _failedPeers = new HashSet<Hash>(16);
        _successfulPeers = new HashSet<Hash>(16);
        _pendingPeerTimes = new HashMap<Hash, Long>(16);
        _repliedPeers = new HashSet<Hash>(16);
        _completed = -1;
        _started = _context.clock().now();
    }
    
    public Hash getTarget() { return _searchKey; }
    public Set<Hash> getPending() {
        synchronized (_pendingPeers) {
            return new HashSet<Hash>(_pendingPeers);
        }
    }
    public Set<Hash> getAttempted() {
        synchronized (_attemptedPeers) {
            return new HashSet<Hash>(_attemptedPeers);
        }
    }
    public Set<Hash> getClosestAttempted(int max) {
        synchronized (_attemptedPeers) {
            return locked_getClosest(_attemptedPeers, max, _searchKey);
        }
    }
    
    private Set<Hash> locked_getClosest(Set<Hash> peers, int max, Hash target) {
        if (_attemptedPeers.size() <= max)
            return new HashSet<Hash>(_attemptedPeers);
        TreeSet<Hash> closest = new TreeSet<Hash>(new XORComparator<Hash>(target));
        closest.addAll(_attemptedPeers);
        Set<Hash> rv = new HashSet<Hash>(max);
        int i = 0;
        for (Iterator<Hash> iter = closest.iterator(); iter.hasNext() && i < max; i++) {
            rv.add(iter.next());
        }
        return rv;
    }
    
    public boolean wasAttempted(Hash peer) {
        synchronized (_attemptedPeers) {
            return _attemptedPeers.contains(peer);
        }
    }
    public Set<Hash> getSuccessful() {
        synchronized (_successfulPeers) {
            return new HashSet<Hash>(_successfulPeers);
        }
    }
    public Set<Hash> getFailed() {
        synchronized (_failedPeers) {
            return new HashSet<Hash>(_failedPeers);
        }
    }

    public boolean completed() { return _completed != -1; }

    public void complete() {
        _completed = _context.clock().now();
    }

    /** @since 0.9.16 */
    public boolean isAborted() { return _aborted; }

    /** @since 0.9.16 */
    public void abort() {
        _aborted = true;
    }
    
    public long getWhenStarted() { return _started; }
    public long getWhenCompleted() { return _completed; }
    
    public void addPending(Collection<Hash> pending) {
        synchronized (_pendingPeers) {
            _pendingPeers.addAll(pending);
            for (Hash peer : pending)
                _pendingPeerTimes.put(peer, Long.valueOf(_context.clock().now()));
        }
        synchronized (_attemptedPeers) {
            _attemptedPeers.addAll(pending);
        }
    }
    public void addPending(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.add(peer);
            _pendingPeerTimes.put(peer, Long.valueOf(_context.clock().now()));
        }
        synchronized (_attemptedPeers) {
            _attemptedPeers.add(peer);
        }
    }
    /** we didn't actually want to add this peer as part of the pending list... */
    public void removePending(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            _pendingPeerTimes.remove(peer);
        }
        synchronized (_attemptedPeers) {
            _attemptedPeers.remove(peer);
        }
    }
    
    /** how long did it take to get the reply, or -1 if we don't know */
    public long dataFound(Hash peer) {
        long rv = -1;
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            Long when = _pendingPeerTimes.remove(peer);
            if (when != null)
                rv = _context.clock().now() - when.longValue();
        }
        synchronized (_successfulPeers) {
            _successfulPeers.add(peer);
        }
        return rv;
    }
    
    /** how long did it take to get the reply, or -1 if we dont know */
    public long replyFound(Hash peer) {
        synchronized (_repliedPeers) {
            _repliedPeers.add(peer);
        }
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            Long when = _pendingPeerTimes.remove(peer);
            if (when != null)
                return _context.clock().now() - when.longValue();
            else
                return -1;
        }
    }
    
    public Set<Hash> getRepliedPeers() { synchronized (_repliedPeers) { return new HashSet<Hash>(_repliedPeers); } }
    
    public void replyTimeout(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            _pendingPeerTimes.remove(peer);
        }
        synchronized (_failedPeers) {
            _failedPeers.add(peer);
        }
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("Searching for ").append(_searchKey);
        buf.append(" ");
        if (_completed <= 0)
            buf.append(" completed? false ");
        else
            buf.append(" completed on ").append(new Date(_completed));
        if (_aborted)
            buf.append("  (Aborted)");
        buf.append("\n\tAttempted: ");
        synchronized (_attemptedPeers) {
            buf.append(_attemptedPeers.size()).append(' ');
            for (Hash peer : _attemptedPeers) {
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append("\n\tPending: ");
        synchronized (_pendingPeers) {
            buf.append(_pendingPeers.size()).append(' ');
            for (Hash peer : _pendingPeers) {
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append("\n\tFailed: ");
        synchronized (_failedPeers) {
            buf.append(_failedPeers.size()).append(' ');
            for (Hash peer : _failedPeers) {
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append("\n\tSuccessful: ");
        synchronized (_successfulPeers) {
            buf.append(_successfulPeers.size()).append(' ');
            for (Hash peer : _successfulPeers) {
                buf.append(peer.toBase64()).append(" ");
            }
        }
        return buf.toString();
    }
}
