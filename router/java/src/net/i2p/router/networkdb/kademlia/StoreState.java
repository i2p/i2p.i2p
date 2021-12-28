package net.i2p.router.networkdb.kademlia;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.MessageWrapper.WrappedMessage;

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
    private final Set<Hash> _successfulPeers;
    private final Set<Hash> _attemptedPeers;
    private int _completeCount;
    private int _attempted;
    private volatile long _completed;
    private volatile long _started;

    public StoreState(RouterContext ctx, Hash key, DatabaseEntry data) {
        this(ctx, key, data, null);
    }

    /**
     * @param key the DatabaseEntry hash
     * @param toSkip may be null, if non-null, all attempted and skipped targets will be added as of 0.9.53
     */
    public StoreState(RouterContext ctx, Hash key, DatabaseEntry data, Set<Hash> toSkip) {
        _context = ctx;
        _key = key;
        _data = data;
        _pendingPeers = new HashSet<Hash>(4);
        _pendingPeerTimes = new HashMap<Hash, Long>(4);
        _pendingMessages = new ConcurrentHashMap<Hash, WrappedMessage>(4);
        if (toSkip != null) {
            _attemptedPeers = toSkip;
        } else {
            _attemptedPeers = new HashSet<Hash>(8);
        }
        _successfulPeers = new HashSet<Hash>(4);
        _completed = -1;
        _started = _context.clock().now();
    }

    public Hash getTarget() { return _key; }
    public DatabaseEntry getData() { return _data; }

    /**
     *  The number of peers pending.
     *
     *  @since 0.9.53 replaces getPending()
     */
    public int getPendingCount() { 
        synchronized (_pendingPeers) {
            return _pendingPeers.size(); 
        }
    }

    /**
     *  The peers attempted OR skipped.
     *  DOES include skipped peers.
     *  Use getAttemptedCount for the number of attempts.
     */
    public Set<Hash> getAttempted() { 
        synchronized (_attemptedPeers) {
            return new HashSet<Hash>(_attemptedPeers); 
        }
    }

    /**
     *  The number of peers attempted.
     *  Does not include skipped peers.
     *  Do not use getAttempted().size() as that does include skipped peers.
     *
     *  @since 0.9.53
     */
    public int getAttemptedCount() { 
        synchronized (_attemptedPeers) {
            return _attempted; 
        }
    }

    /**
     *  Return a successful peer (a random one if more than one was successful)
     *  or null.
     *
     *  @since 0.9.53 formerly returned a copy of the Set
     */
    public Hash getSuccessful() { 
        synchronized (_successfulPeers) {
            if (_successfulPeers.isEmpty())
                return null;
            try {
                return _successfulPeers.iterator().next();
            } catch (NoSuchElementException nsee) {
                return null;
            }
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

    /**
     * Increments attempted count
     */
    public void addPending(Hash peer) {
        Long now = Long.valueOf(_context.clock().now());
        synchronized (_pendingPeers) {
            _pendingPeers.add(peer);
            _pendingPeerTimes.put(peer, now);
        }
        synchronized (_attemptedPeers) {
            if (_attemptedPeers.add(peer))
                _attempted++;
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

    public void replyTimeout(Hash peer) {
        synchronized (_pendingPeers) {
            _pendingPeers.remove(peer);
        }
    }

    @Override
    public String toString() { 
        StringBuilder buf = new StringBuilder(256);
        buf.append("Storing ").append(_key);
        buf.append(' ');
        if (_completed <= 0)
            buf.append(" completed? false ");
        else
            buf.append(" completed on ").append(new Date(_completed));
        buf.append(" Attempted: ")
           .append(_attempted)
           .append(" Attempted+Skipped: ");
        synchronized (_attemptedPeers) {
            buf.append(_attemptedPeers.size()).append(' ');
            for (Hash peer : _attemptedPeers) {
                buf.append(peer.toBase64()).append(' ');
            }
        }
        buf.append(" Pending: ");
        synchronized (_pendingPeers) {
            buf.append(_pendingPeers.size()).append(' ');
            for (Hash peer : _pendingPeers) {
                buf.append(peer.toBase64()).append(' ');
            }
        }
        buf.append(" Successful: ");
        synchronized (_successfulPeers) {
            buf.append(_successfulPeers.size()).append(' ');
            for (Hash peer : _successfulPeers) {
                buf.append(peer.toBase64()).append(' ');
            }
        }
        return buf.toString();
    }
}
