package net.i2p.client.streaming.impl;

import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.util.ObjectCounter;
import net.i2p.util.RandomSource;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

/**
 * Count how often we have received an incoming connection
 * This offers basic DOS protection but is not a complete solution.
 *
 * @since 0.7.14
 */
class ConnThrottler {
    private final ObjectCounter<Hash> counter;
    private volatile int _max;
    private volatile int _totalMax;
    private final AtomicInteger _currentTotal;

    /*
     * @param max per-peer, 0 for unlimited
     * @param totalMax for all peers, 0 for unlimited
     * @param period ms
     */
    ConnThrottler(int max, int totalMax, long period, SimpleTimer2 timer) {
        _max = max;
        _totalMax = totalMax;
        this.counter = new ObjectCounter<Hash>();
        _currentTotal = new AtomicInteger();
        // shorten the initial period by a random amount
        // to prevent correlation across destinations
        // and identification of router startup time
        timer.addPeriodicEvent(new Cleaner(),
                               (period / 2) + RandomSource.getInstance().nextLong(period / 2),
                               period);
    }

    /*
     * @param max per-peer, 0 for unlimited
     * @param totalMax for all peers, 0 for unlimited
     * @since 0.9.3
     */
    public void updateLimits(int max, int totalMax) {
        _max = max;
        _totalMax = totalMax;
    }

    /**
     *  Checks both individual and total. Increments before checking.
     */
    boolean shouldThrottle(Hash h) {
        // do this first, so we don't increment total if individual throttled
        if (_max > 0 && this.counter.increment(h) > _max)
            return true;
        if (_totalMax > 0 && _currentTotal.incrementAndGet() > _totalMax)
            return true;
        return false;
    }

    /**
     *  Checks individual count only. Does not increment.
     *  @since 0.9.3
     */
    boolean isThrottled(Hash h) {
        if (_max > 0)
            return this.counter.count(h) > _max;
        return false;
    }

    /**
     *  Checks if individual count is over the limit by this much. Does not increment.
     *  @since 0.9.34
     */
    boolean isOverBy(Hash h, int over) {
        if (_max > 0)
            return this.counter.count(h) >  _max + over;
        return false;
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (_totalMax > 0)
                _currentTotal.set(0);
            if (_max > 0)
                ConnThrottler.this.counter.clear();
        }
    }
}
