package net.i2p.router.networkdb.kademlia;

import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Collection;
import java.util.Date;

import net.i2p.data.Hash;
import net.i2p.data.DataStructure;
import net.i2p.router.RouterContext;

class StoreState {
    private RouterContext _context;
    private Hash _key;
    private DataStructure _data;
    private HashSet _pendingPeers;
    private HashMap _pendingPeerTimes;
    private HashSet _successfulPeers;
    private HashSet _successfulExploratoryPeers;
    private HashSet _failedPeers;
    private HashSet _attemptedPeers;
    private volatile long _completed;
    private volatile long _started;

    public StoreState(RouterContext ctx, Hash key, DataStructure data) {
        this(ctx, key, data, null);
    }
    public StoreState(RouterContext ctx, Hash key, DataStructure data, Set toSkip) {
        _context = ctx;
        _key = key;
        _data = data;
        _pendingPeers = new HashSet(16);
        _pendingPeerTimes = new HashMap(16);
        _attemptedPeers = new HashSet(16);
        if (toSkip != null)
            _attemptedPeers.addAll(toSkip);
        _failedPeers = new HashSet(16);
        _successfulPeers = new HashSet(16);
        _successfulExploratoryPeers = new HashSet(16);
        _completed = -1;
        _started = _context.clock().now();
    }

    public Hash getTarget() { return _key; }
    public DataStructure getData() { return _data; }
    public Set getPending() { 
        synchronized (_pendingPeers) {
            return (Set)_pendingPeers.clone(); 
        }
    }
    public Set getAttempted() { 
        synchronized (_attemptedPeers) {
            return (Set)_attemptedPeers.clone(); 
        }
    }
    public Set getSuccessful() { 
        synchronized (_successfulPeers) {
            return (Set)_successfulPeers.clone(); 
        }
    }
    public Set getSuccessfulExploratory() { 
        synchronized (_successfulExploratoryPeers) {
            return (Set)_successfulExploratoryPeers.clone(); 
        }
    }
    public Set getFailed() { 
        synchronized (_failedPeers) {
            return (Set)_failedPeers.clone(); 
        }
    }
    public boolean completed() { return _completed != -1; }
    public void complete(boolean completed) { 
        if (completed)
            _completed = _context.clock().now();
    }

    public long getWhenStarted() { return _started; }
    public long getWhenCompleted() { return _completed; }

    public void addPending(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.add(peer);
            _pendingPeerTimes.put(peer, new Long(_context.clock().now()));
        }
        synchronized (_attemptedPeers) {
            _attemptedPeers.add(peer);
        }
    }
    public void addPending(Collection pending) {
        synchronized (_pendingPeers) {
            _pendingPeers.addAll(pending);
            for (Iterator iter = pending.iterator(); iter.hasNext(); ) 
                _pendingPeerTimes.put(iter.next(), new Long(_context.clock().now()));
        }
        synchronized (_attemptedPeers) {
            _attemptedPeers.addAll(pending);
        }
    }

    public long confirmed(Hash peer) {
        long rv = -1;
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            Long when = (Long)_pendingPeerTimes.remove(peer);
            if (when != null)
                rv = _context.clock().now() - when.longValue();
        }
        synchronized (_successfulPeers) {
            _successfulPeers.add(peer);
        }
        return rv;
    }

    public long confirmedExploratory(Hash peer) {
        long rv = -1;
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
            Long when = (Long)_pendingPeerTimes.remove(peer);
            if (when != null)
                rv = _context.clock().now() - when.longValue();
        }
        synchronized (_successfulExploratoryPeers) {
            _successfulExploratoryPeers.add(peer);
        }
        return rv;
    }

    public void replyTimeout(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
        }
        synchronized (_failedPeers) {
            _failedPeers.add(peer);
        }
    }

    public String toString() { 
        StringBuffer buf = new StringBuffer(256);
        buf.append("Storing ").append(_key);
        buf.append(" ");
        if (_completed <= 0)
            buf.append(" completed? false ");
        else
            buf.append(" completed on ").append(new Date(_completed));
        buf.append(" Attempted: ");
        synchronized (_attemptedPeers) {
            buf.append(_attemptedPeers.size()).append(' ');
            for (Iterator iter = _attemptedPeers.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append(" Pending: ");
        synchronized (_pendingPeers) {
            buf.append(_pendingPeers.size()).append(' ');
            for (Iterator iter = _pendingPeers.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append(" Failed: ");
        synchronized (_failedPeers) { 
            buf.append(_failedPeers.size()).append(' ');
            for (Iterator iter = _failedPeers.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append(" Successful: ");
        synchronized (_successfulPeers) {
            buf.append(_successfulPeers.size()).append(' ');
            for (Iterator iter = _successfulPeers.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
        buf.append(" Successful Exploratory: ");
        synchronized (_successfulExploratoryPeers) {
            buf.append(_successfulExploratoryPeers.size()).append(' ');
            for (Iterator iter = _successfulExploratoryPeers.iterator(); iter.hasNext(); ) {
                Hash peer = (Hash)iter.next();
                buf.append(peer.toBase64()).append(" ");
            }
        }
        return buf.toString();
    }
}