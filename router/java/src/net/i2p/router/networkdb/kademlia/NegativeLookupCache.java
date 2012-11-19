package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Track lookup fails
 *
 * @since 0.9.4
 */
class NegativeLookupCache {
    private final ObjectCounter<Hash> counter;
    private static final int MAX_FAILS = 3;
    private static final long CLEAN_TIME = 4*60*1000;

    public NegativeLookupCache() {
        this.counter = new ObjectCounter();
        SimpleScheduler.getInstance().addPeriodicEvent(new Cleaner(), CLEAN_TIME);
    }

    public void lookupFailed(Hash h) {
        this.counter.increment(h);
    }

    public boolean isCached(Hash h) {
        return this.counter.count(h) >= MAX_FAILS;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            NegativeLookupCache.this.counter.clear();
        }
    }
}
