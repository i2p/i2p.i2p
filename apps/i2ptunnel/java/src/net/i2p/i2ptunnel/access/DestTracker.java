package net.i2p.i2ptunnel.access;


class DestTracker {
    
    private final String b32;
    private final Threshold threshold;
    private final AccessCounter counter;

    DestTracker(String b32, Threshold threshold) {
        this.b32 = b32;
        this.threshold = threshold;
        this.counter = new AccessCounter();
    }

    String getB32() {
        return b32;
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
