package net.i2p.i2ptunnel.access;

/**
 * Counts connection attempts and decides if specified thresholds have been
 * breached.
 *
 * @since 0.9.40
 */
class AccessCounter {

    // Callers synchronize on the owning DestTracker. Only the newest
    // maxConnections attempts are needed for any sliding-window threshold.
    private static final long[] EMPTY = new long[0];
    private final int maxConnections;
    private long[] accesses = EMPTY;
    private int head;
    private int size;

    AccessCounter(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    /**
     * records that a connection attempt was made
     * 
     * @param now the current time
     */
    void recordAccess(long now) {
        if (maxConnections == 0)
            return;
        // Clock changes and callers reading the clock before acquiring the
        // tracker lock must not put the history out of order.
        if (size > 0)
            now = Math.max(now, accesses[index(size - 1)]);
        if (size == maxConnections) {
            accesses[head] = now;
            head = (head + 1) % accesses.length;
            return;
        }
        if (size == accesses.length) {
            int capacity = (int) Math.min(maxConnections, Math.max(4L, 2L * size));
            long[] grown = new long[capacity];
            int first = Math.min(size, accesses.length - head);
            System.arraycopy(accesses, head, grown, 0, first);
            System.arraycopy(accesses, 0, grown, first, size - first);
            accesses = grown;
            head = 0;
        }
        accesses[index(size)] = now;
        size++;
    }

    /**
     * @param threshold definition of a threshold
     * @param now current time
     * @return true if the given threshold has been breached
     */
    boolean isBreached(Threshold threshold, long now) {
        if (threshold == Threshold.ALLOW)
            return false;
        if (threshold.getConnections() == 0)
            return size > 0;
        int connections = Math.max(1, threshold.getConnections());
        if (size < connections)
            return false;
        return accesses[index(size - connections)] >= now - threshold.getSeconds() * 1000L;
    }

    /**
     * Purges old accesses from the list.
     * @param olderThan remove all accesses older than the given timestamp
     * @return true if there is nothing left in the access history
     */
    boolean purge(long olderThan) {
        while (size > 0 && accesses[head] < olderThan) {
            head = (head + 1) % accesses.length;
            size--;
        }
        if (size == 0)
            head = 0;
        return size == 0;
    }

    private int index(int offset) {
        int remaining = accesses.length - head;
        return offset < remaining ? head + offset : offset - remaining;
    }
}
