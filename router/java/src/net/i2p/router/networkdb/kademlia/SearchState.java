package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.util.Clock;

import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Collection;
import java.util.Date;

/**
 * Data related to a particular search
 *
 */
class SearchState {
    private HashSet _pendingPeers;
    private HashMap _pendingPeerTimes;
    private HashSet _attemptedPeers;
    private HashSet _failedPeers;
    private HashSet _successfulPeers;
    private Hash _searchKey;
    private volatile long _completed;
    private volatile long _started;

    public SearchState(Hash key) {
	_searchKey = key;
	_pendingPeers = new HashSet(16);
	_attemptedPeers = new HashSet(16);
	_failedPeers = new HashSet(16);
	_successfulPeers = new HashSet(16);
	_pendingPeerTimes = new HashMap(16);
	_completed = -1;
	_started = Clock.getInstance().now();
    }

    public Hash getTarget() { return _searchKey; }
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
    public boolean wasAttempted(Hash peer) {
	synchronized (_attemptedPeers) {
	    return _attemptedPeers.contains(peer);
	}
    }
    public Set getSuccessful() { 
	synchronized (_successfulPeers) {
	    return (Set)_successfulPeers.clone(); 
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
	    _completed = Clock.getInstance().now();
    }

    public long getWhenStarted() { return _started; }
    public long getWhenCompleted() { return _completed; }

    public void addPending(Collection pending) {
	synchronized (_pendingPeers) {
	    _pendingPeers.addAll(pending);
	    for (Iterator iter = pending.iterator(); iter.hasNext(); ) 
		_pendingPeerTimes.put(iter.next(), new Long(Clock.getInstance().now()));
	}
	synchronized (_attemptedPeers) {
	    _attemptedPeers.addAll(pending);
	}
    }

    /** how long did it take to get the reply, or -1 if we don't know */
    public long dataFound(Hash peer) {
	long rv = -1;
	synchronized (_pendingPeers) {
	    _pendingPeers.remove(peer);
	    Long when = (Long)_pendingPeerTimes.remove(peer);
	    if (when != null)
		rv = Clock.getInstance().now() - when.longValue();
	}
	synchronized (_successfulPeers) {
	    _successfulPeers.add(peer);
	}
	return rv;
    }

    /** how long did it take to get the reply, or -1 if we dont know */
    public long replyFound(Hash peer) {
	synchronized (_pendingPeers) {
	    _pendingPeers.remove(peer);
	    Long when = (Long)_pendingPeerTimes.remove(peer);
	    if (when != null)
		return Clock.getInstance().now() - when.longValue();
	    else
		return -1;
	}
    }

    public void replyTimeout(Hash peer) {
	synchronized (_pendingPeers) {
	    _pendingPeers.remove(peer);
	    _pendingPeerTimes.remove(peer);
	}
	synchronized (_failedPeers) {
	    _failedPeers.add(peer);
	}
    }

    public String toString() { 
	StringBuffer buf = new StringBuffer(256);
	buf.append("Searching for ").append(_searchKey);
	buf.append(" ");
	if (_completed <= 0)
	    buf.append(" completed? false ");
	else
	    buf.append(" completed on ").append(new Date(_completed));
	buf.append(" Attempted: ");
	synchronized (_attemptedPeers) {
	    for (Iterator iter = _attemptedPeers.iterator(); iter.hasNext(); ) {
		Hash peer = (Hash)iter.next();
		buf.append(peer.toBase64()).append(" ");
	    }
	}
	buf.append(" Pending: ");
	synchronized (_pendingPeers) {
	    for (Iterator iter = _pendingPeers.iterator(); iter.hasNext(); ) {
		Hash peer = (Hash)iter.next();
		buf.append(peer.toBase64()).append(" ");
	    }
	}
	buf.append(" Failed: ");
	synchronized (_failedPeers) {
	    for (Iterator iter = _failedPeers.iterator(); iter.hasNext(); ) {
		Hash peer = (Hash)iter.next();
		buf.append(peer.toBase64()).append(" ");
	    }
	}
	buf.append(" Successful: ");
	synchronized (_successfulPeers) {
	    for (Iterator iter = _successfulPeers.iterator(); iter.hasNext(); ) {
		Hash peer = (Hash)iter.next();
		buf.append(peer.toBase64()).append(" ");
	    }
	}
	return buf.toString();
    }
}
