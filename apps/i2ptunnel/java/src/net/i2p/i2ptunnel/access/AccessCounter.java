package net.i2p.i2ptunnel.access;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Counts connection attempts and decides if specified thresholds have been
 * breached.
 *
 * @since 0.9.40
 */
class AccessCounter {

    /**
     * List of timestamps of each connection attempt
     */
    private final List<Long> accesses = new ArrayList<Long>();

    /**
     * records that a connection attempt was made
     * 
     * @param now the current time
     */
    void recordAccess(long now) {
        accesses.add(now);
    }

    /**
     * @param threshold definition of a threshold
     * @return true if the given threshold has been breached
     */
    boolean isBreached(Threshold threshold) {
        if (threshold.getConnections() == 0)
            return !accesses.isEmpty();
        if (accesses.size() < threshold.getConnections())
            return false;
        
        for (int i = 0; i <= accesses.size() - threshold.getConnections(); i++) {
            long start = accesses.get(i);
            long end = start + threshold.getMinutes() * 60000;
            if (accesses.get(i + threshold.getConnections() -1) <= end)
                return true;
        }

        return false;
    }

    /**
     * Purges old accesses from the list.
     * @param olderThan remove all accesses older than the given timestamp
     * @return true if there is nothing left in the access history
     */
    boolean purge(long olderThan) {
        while(!accesses.isEmpty() && accesses.get(0) < olderThan) {
            accesses.remove(0);
        }
        return accesses.isEmpty();
    }
}
