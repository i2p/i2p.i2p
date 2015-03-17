package net.i2p.client.streaming;

import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Count how often we have received an incoming connection
 * This offers basic DOS protection but is not a complete solution.
 *
 * @since 0.7.14
 */
class ConnThrottler {
    private final ObjectCounter<Hash> counter;
    private final int _max;
    private final int _totalMax;
    private final AtomicInteger _currentTotal;

    /*
     * @param max per-peer, 0 for unlimited
     * @param totalMax for all peers, 0 for unlimited
     * @param period ms
     */
    ConnThrottler(int max, int totalMax, long period) {
        _max = max;
        _totalMax = totalMax;
        if (max > 0)
            this.counter = new ObjectCounter<Hash>();
        else
            this.counter = null;
        if (totalMax > 0)
            _currentTotal = new AtomicInteger();
        else
            _currentTotal = null;
        SimpleScheduler.getInstance().addPeriodicEvent(new Cleaner(), period);
    }

    /** increments before checking */
    boolean shouldThrottle(Hash h) {
        if (_totalMax > 0 && _currentTotal.incrementAndGet() > _totalMax)
            return true;
        if (_max > 0)
            return this.counter.increment(h) > _max;
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
