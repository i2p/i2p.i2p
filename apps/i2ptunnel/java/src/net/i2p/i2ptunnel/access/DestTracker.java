package net.i2p.i2ptunnel.access;

import net.i2p.data.Hash;

/**
 * Tracks the connection attempts for a given remote Destination
 *
 * @since 0.9.40
 */
class DestTracker {
    
    private final Hash hash;
    private final Threshold threshold;
    private final AccessCounter counter;

    /**
     * @param hash hash of the remote destination
     * @param threshold threshold defined in the access rule
     */
    DestTracker(Hash hash, Threshold threshold) {
        this(hash, threshold, 0);
    }

    /** @param recorderConnections largest recorder threshold, or zero */
    DestTracker(Hash hash, Threshold threshold, int recorderConnections) {
        this.hash = hash;
        this.threshold = threshold;
        int connections = threshold == Threshold.ALLOW ? 0 : Math.max(1, threshold.getConnections());
        this.counter = new AccessCounter(Math.max(connections, recorderConnections));
    }

    Hash getHash() {
        return hash;
    }

    synchronized boolean isBreached(Threshold threshold, long now) {
        return counter.isBreached(threshold, now);
    }

    /**
     * @return true if this access causes threshold breach
     */
    synchronized boolean recordAccess(long now) {
        counter.recordAccess(now);
        return counter.isBreached(threshold,now);
    }

    synchronized boolean purge(long olderThan) {
        return counter.purge(olderThan);
    }
}
