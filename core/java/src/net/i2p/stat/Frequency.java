package net.i2p.stat;

/**
 * Manage the calculation of a moving average event frequency over a certain period.
 *
 * This provides lifetime, and rolling average, frequency counts.
 * Unlike Rate, it does not support "bucketed" averages.
 * There is no tracking of the event frequency in the current or last bucket.
 * There are no buckets at all.
 *
 * Depending on what you want, a rolling average might be better than buckets.
 * Or not.
 */
public class Frequency {
    private double _avgInterval;
    private double _minAverageInterval;
    private final long _period;
    private long _lastEvent;
    private final long _start = now();
    private long _count;

    /** @param period ms */
    public Frequency(long period) {
        _period = period;
        _avgInterval = period + 1;
        _minAverageInterval = _avgInterval;
    }

    /** how long is this frequency averaged over? (ms) */
    public long getPeriod() {
            return _period;
    }

    /**
     * when did the last event occur?
     * @deprecated unused
     */
    public long getLastEvent() {
            return _lastEvent;
    }

    /** 
     * on average over the last $period, after how many milliseconds are events coming in, 
     * as calculated during the last event occurrence? 
     * @return milliseconds; returns period + 1 if no events in previous period
     */
    public double getAverageInterval() {
            return _avgInterval;
    }

    /**
     * what is the lowest average interval (aka most frequent) we have seen? (ms)
     * @return milliseconds; returns period + 1 if no events in previous period
     * @deprecated unused
     */
    public double getMinAverageInterval() {
            return _minAverageInterval;
    }

    /**
     * Calculate how many events would occur in a period given the current (rolling) average.
     * Use getStrictAverageInterval() for the real lifetime average.
     */
    public double getAverageEventsPerPeriod() {
        synchronized (this) {
            if (_avgInterval > 0) return _period / _avgInterval;
                
            return 0;
        }
    }

    /**
     * Calculate how many events would occur in a period given the maximum rolling average.
     * Use getStrictAverageEventsPerPeriod() for the real lifetime average.
     */
    public double getMaxAverageEventsPerPeriod() {
        synchronized (this) {
            if (_minAverageInterval > 0 && _minAverageInterval <= _period) return _period / _minAverageInterval;

            return 0;
        }
    }

    /**
     * Over the lifetime of this stat, without any decay or weighting, what was the average interval between events? (ms)
     * @return milliseconds; returns Double.MAX_VALUE if no events ever
     */
    public double getStrictAverageInterval() {
            long duration = now() - _start;
            if ((duration <= 0) || (_count <= 0)) return Double.MAX_VALUE;
            return duration / (double) _count;
    }

    /** using the strict average interval, how many events occur within an average period? */
    public double getStrictAverageEventsPerPeriod() {
        double avgInterval = getStrictAverageInterval();
        if (avgInterval > 0) return _period / avgInterval;
        return 0;
    }

    /** how many events have occurred within the lifetime of this stat? */
    public long getEventCount() {
            return _count;
    }

    /** 
     * Take note that a new event occurred, recalculating all the averages and frequencies
     *
     */
    public void eventOccurred() {
        recalculate(true);
    }

    /** 
     * Recalculate the averages
     *
     */
    public void recalculate() {
        recalculate(false);
    }

    /**
     * Recalculate, but only update the lastEvent if eventOccurred
     */
    private void recalculate(boolean eventOccurred) {
        synchronized (this) {
            // This calculates something of a rolling average interval.
            long now = now();
            long interval = now - _lastEvent;
            if (interval > _period)
                interval = _period;
            else if (interval <= 0) interval = 1;

            if (interval >= _period && !eventOccurred) {
                // ensure getAverageEventsPerPeriod() will return 0
                _avgInterval = _period + 1;
            } else {
                double oldWeight = 1 - (interval / (float) _period);
                double newWeight = (interval / (float) _period);
                double oldInterval = _avgInterval * oldWeight;
                double newInterval = interval * newWeight;
                _avgInterval = oldInterval + newInterval;
            }

            if ((_avgInterval < _minAverageInterval) || (_minAverageInterval <= 0)) _minAverageInterval = _avgInterval;

            if (eventOccurred) {
                _lastEvent = now;
                _count++;
            }
        }
    }

    private final static long now() {
        return System.currentTimeMillis();
    }
}
