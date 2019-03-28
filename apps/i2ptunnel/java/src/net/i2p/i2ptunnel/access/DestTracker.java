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
        this.hash = hash;
        this.threshold = threshold;
        this.counter = new AccessCounter();
    }

    Hash getHash() {
        return hash;
    }

    AccessCounter getCounter() {
        return counter;
    }

    /**
     * @return true if this access causes threshold breach
     */
    synchronized boolean recordAccess(long now) {
        counter.recordAccess(now);
        return counter.isBreached(threshold);
    }

    synchronized boolean purge(long olderThan) {
        return counter.purge(olderThan);
    }
}
