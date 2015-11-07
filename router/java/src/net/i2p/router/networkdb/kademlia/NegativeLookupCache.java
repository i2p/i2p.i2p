package net.i2p.router.networkdb.kademlia;

import java.util.Map;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.LHMCache;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

/**
 * Track lookup fails
 *
 * @since 0.9.4
 */
class NegativeLookupCache {
    private final ObjectCounter<Hash> counter;
    private final Map<Hash, Destination> badDests;
    private final int _maxFails;
    
    private static final int MAX_FAILS = 3;
    private static final int MAX_BAD_DESTS = 128;
    private static final long CLEAN_TIME = 2*60*1000;

    public NegativeLookupCache(RouterContext context) {
        this.counter = new ObjectCounter<Hash>();
        this.badDests = new LHMCache<Hash, Destination>(MAX_BAD_DESTS);
        this._maxFails = context.getProperty("netdb.negativeCache.maxFails",MAX_FAILS);
        final long cleanTime = context.getProperty("netdb.negativeCache.cleanupInterval", CLEAN_TIME);
        SimpleTimer2.getInstance().addPeriodicEvent(new Cleaner(), cleanTime);
    }

    public void lookupFailed(Hash h) {
        this.counter.increment(h);
    }

    public boolean isCached(Hash h) {
        if (counter.count(h) >= _maxFails)
            return true;
        synchronized(badDests) {
            return badDests.get(h) != null;
        }
    }

    /**
     *  Negative cache the hash until restart,
     *  but cache the destination.
     *
     *  @since 0.9.16
     */
    public void failPermanently(Destination dest) {
        Hash h = dest.calculateHash();
        synchronized(badDests) {
            badDests.put(h, dest);
        }
    }

    /**
     *  Get an unsupported but cached Destination
     *
     *  @return dest or null if not cached
     *  @since 0.9.16
     */
    public Destination getBadDest(Hash h) {
        synchronized(badDests) {
            return badDests.get(h);
        }
    }

    /**
     *  @since 0.9.16
     */
    public void clear() {
        counter.clear();
        synchronized(badDests) {
            badDests.clear();
        }
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            NegativeLookupCache.this.counter.clear();
        }
    }
}
