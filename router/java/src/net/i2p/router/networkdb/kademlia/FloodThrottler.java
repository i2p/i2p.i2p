package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Count how often we have recently flooded a key
 * This offers basic DOS protection but is not a complete solution.
 *
 * @since 0.7.11
 */
class FloodThrottler {
    private ObjectCounter<Hash> counter;
    private static final int MAX_FLOODS = 3;
    private static final long CLEAN_TIME = 60*1000;

    FloodThrottler() {
        this.counter = new ObjectCounter();
        SimpleScheduler.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    /** increments before checking */
    boolean shouldThrottle(Hash h) {
        return this.counter.increment(h) > MAX_FLOODS;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            FloodThrottler.this.counter.clear();
        }
    }
}
