package net.i2p.router.networkdb.kademlia;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;

/**
 *  Tracks the state of a StoreJob
 */
class StoreState {
    private final RouterContext _context;
    private final Hash _key;
    private final DatabaseEntry _data;
    private final HashSet<Hash> _pendingPeers;
    private final Map<Hash, Long> _pendingPeerTimes;
    private final Map<Hash, MessageWrapper.WrappedMessage> _pendingMessages;
    private final HashSet<Hash> _successfulPeers;
    //private final HashSet<Hash> _successfulExploratoryPeers;
    private final HashSet<Hash> _failedPeers;
    private final HashSet<Hash> _attemptedPeers;
    private int _completeCount;
    private volatile long _completed;
    private volatile long _started;

    public StoreState(RouterContext ctx, Hash key, DatabaseEntry data) {
        this(ctx, key, data, null);
    }
    public StoreState(RouterContext ctx, Hash key, DatabaseEntry data, Set<Hash> toSkip) {
        _context = ctx;
        _key = key;
        _data = data;
        _pendingPeers = new HashSet(4);
        _pendingPeerTimes = new HashMap(4);
        _pendingMessages = new ConcurrentHashMap(4);
        _attemptedPeers = new HashSet(8);
        if (toSkip != null) {
            _attemptedPeers.addAll(toSkip);
            _completeCount = toSkip.size();
        }
        _failedPeers = new HashSet(8);
        _successfulPeers = new HashSet(4);
        //_successfulExploratoryPeers = new HashSet(16);
        _completed = -1;
        _started = _context.clock().now();
    }

    public Hash getTarget() { return _key; }
    public DatabaseEntry getData() { return _data; }
    public Set<Hash> getPending() { 
        synchronized (_pendingPeers) {
            return (Set<Hash>)_pendingPeers.clone(); 
        }
    }
    public Set<Hash> getAttempted() { 
        synchronized (_attemptedPeers) {
            return (Set<Hash>)_attemptedPeers.clone(); 
        }
    }
    public Set<Hash> getSuccessful() { 
        synchronized (_successfulPeers) {
            return (Set<Hash>)_successfulPeers.clone(); 
        }
    }
    /** unused */
/****
    public Set<Hash> getSuccessfulExploratory() { 
        synchronized (_successfulExploratoryPeers) {
            return (Set<Hash>)_successfulExploratoryPeers.clone(); 
        }
    }
****/

    public Set<Hash> getFailed() { 
        synchronized (_failedPeers) {
            return (Set<Hash>)_failedPeers.clone(); 
        }
    }
    public boolean completed() { return _completed != -1; }
    public void complete(boolean completed) { 
        if (completed && _completed <= 0)
            _completed = _context.clock().now();
    }
    public int getCompleteCount() { return _completeCount; }

    public long getWhenStarted() { return _started; }
    public long getWhenCompleted() { return _completed; }

    /*
     * @since 0.7.10
     */
    public void addPending(Hash peer, MessageWrapper.WrappedMessage msg) {
        addPending(peer);
        _pendingMessages.put(peer, msg);
    }

    /*
     * @return the message or null; will only return the message once, so
     * tags are only acked or failed once.
     * @since 0.7.10
     */
    public MessageWrapper.WrappedMessage getPendingMessage(Hash peer) {
        return _pendingMessages.remove(peer);
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
    public void addPending(Collection<Hash> pending) {
        synchronized (_pendingPeers) {
            _pendingPeers.addAll(pending);
            for (Iterator<Hash> iter = pending.iterator(); iter.hasNext(); ) 
                _pendingPeerTimes.put(iter.next(), Long.valueOf(_context.clock().now()));
        }
        synchronized (_attemptedPeers) {
            _attemptedPeers.addAll(pending);
        }
    }
    /** we aren't even going to try to contact this peer */
    public void addSkipped(Hash peer) {
        synchronized (_attemptedPeers) {
            _attemptedPeers.add(peer);
        }
    }

    public long confirmed(Hash peer) {
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
        _completeCount++;
        return rv;
    }

    /** unused */
/****
    public long confirmedExploratory(Hash peer) {
        long rv = -1;
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            Long when = _pendingPeerTimes.remove(peer);
            if (when != null)
                rv = _context.clock().now() - when.longValue();
        }
        synchronized (_successfulExploratoryPeers) {
            _successfulExploratoryPeers.add(peer);
        }
        return rv;
    }
****/

    public void replyTimeout(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
        }
        synchronized (_failedPeers) {
            _failedPeers.add(peer);
        }
    }

    @Override
    public String toString() { 
        StringBuilder buf = new StringBuilder(256);
        buf.append("Storing ").append(_key);
        buf.append(" ");
        if (_completed <= 0)
            buf.append(" completed? false ");
        else
            buf.append(" completed on ").append(new Date(_completed));
        buf.append(" Attempted: ");
        synchronized (_attemptedPeers) {
            buf.append(_attemptedPeers.size()).append(' ');
            for (Iterator<Hash> iter = _attemptedPeers.iterator(); iter.hasNext(); ) {
                Hash peer = iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append(" Pending: ");
        synchronized (_pendingPeers) {
            buf.append(_pendingPeers.size()).append(' ');
            for (Iterator<Hash> iter = _pendingPeers.iterator(); iter.hasNext(); ) {
                Hash peer = iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append(" Failed: ");
        synchronized (_failedPeers) { 
            buf.append(_failedPeers.size()).append(' ');
            for (Iterator<Hash> iter = _failedPeers.iterator(); iter.hasNext(); ) {
                Hash peer = iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append(" Successful: ");
        synchronized (_successfulPeers) {
            buf.append(_successfulPeers.size()).append(' ');
            for (Iterator<Hash> iter = _successfulPeers.iterator(); iter.hasNext(); ) {
                Hash peer = iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
/****
        buf.append(" Successful Exploratory: ");
        synchronized (_successfulExploratoryPeers) {
            buf.append(_successfulExploratoryPeers.size()).append(' ');
            for (Iterator<Hash> iter = _successfulExploratoryPeers.iterator(); iter.hasNext(); ) {
                Hash peer = iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
****/
        return buf.toString();
    }
}
