package net.i2p.stat;

/**
 * Manage the calculation of a moving event frequency over a certain period.
 *
 */
public class Frequency {
    private double _avgInterval;
    private double _minAverageInterval;
    private long _period;
    private long _lastEvent;
    private long _start = now();
    private long _count = 0;
    private Object _lock = this; // new Object(); // in case we want to do fancy sync later

    public Frequency(long period) {
        setPeriod(period);
        setLastEvent(0);
        setAverageInterval(0);
        setMinAverageInterval(0);
    }

    /** how long is this frequency averaged over? */
    public long getPeriod() {
        synchronized (_lock) {
            return _period;
        }
    }

    /** when did the last event occur? */
    public long getLastEvent() {
        synchronized (_lock) {
            return _lastEvent;
        }
    }

    /** 
     * on average over the last $period, after how many milliseconds are events coming in, 
     * as calculated during the last event occurrence? 
     *
     */
    public double getAverageInterval() {
        synchronized (_lock) {
            return _avgInterval;
        }
    }

    /** what is the lowest average interval (aka most frequent) we have seen? */
    public double getMinAverageInterval() {
        synchronized (_lock) {
            return _minAverageInterval;
        }
    }

    /** calculate how many events would occur in a period given the current average */
    public double getAverageEventsPerPeriod() {
        synchronized (_lock) {
            if (_avgInterval > 0) return _period / _avgInterval;
                
            return 0;
        }
    }

    /** calculate how many events would occur in a period given the maximum average */
    public double getMaxAverageEventsPerPeriod() {
        synchronized (_lock) {
            if (_minAverageInterval > 0) return _period / _minAverageInterval;
            
            return 0;
        }
    }

    /** over the lifetime of this stat, without any decay or weighting, what was the average interval between events? */
    public double getStrictAverageInterval() {
        synchronized (_lock) {
            long duration = now() - _start;
            if ((duration <= 0) || (_count <= 0)) return 0;
           
            return duration / _count;
        }
    }

    /** using the strict average interval, how many events occur within an average period? */
    public double getStrictAverageEventsPerPeriod() {
        double avgInterval = getStrictAverageInterval();
        synchronized (_lock) {
            if (avgInterval > 0) return _period / avgInterval;
           
            return 0;
        }
    }

    /** how many events have occurred within the lifetime of this stat? */
    public long getEventCount() {
        synchronized (_lock) {
            return _count;
        }
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
        synchronized (_lock) {
            long now = now();
            long interval = now - _lastEvent;
            if (interval >= _period)
                interval = _period - 1;
            else if (interval <= 0) interval = 1;

            double oldWeight = 1 - (interval / (float) _period);
            double newWeight = (interval / (float) _period);

            double oldInterval = _avgInterval * oldWeight;
            double newInterval = interval * newWeight;
            _avgInterval = oldInterval + newInterval;

            if ((_avgInterval < _minAverageInterval) || (_minAverageInterval <= 0)) _minAverageInterval = _avgInterval;

            if (eventOccurred) {
                _lastEvent = now;
                _count++;
            }
        }
    }

    private void setPeriod(long milliseconds) {
        synchronized (_lock) {
            _period = milliseconds;
        }
    }

    private void setLastEvent(long when) {
        synchronized (_lock) {
            _lastEvent = when;
        }
    }

    private void setAverageInterval(double msInterval) {
        synchronized (_lock) {
            _avgInterval = msInterval;
        }
    }

    private void setMinAverageInterval(double minAverageInterval) {
        synchronized (_lock) {
            _minAverageInterval = minAverageInterval;
        }
    }

    private final static long now() {
        return System.currentTimeMillis();
    }
}