package net.i2p.util;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Alternate location for determining the time which takes into account an offset.
 * This offset will ideally be periodically updated so as to serve as the difference
 * between the local computer's current time and the time as known by some reference
 * (such as an NTP synchronized clock).
 *
 */
public class Clock {
    private final static Log _log = new Log(Clock.class);
    private final static Clock _instance = new Clock();

    public final static Clock getInstance() {
        return _instance;
    }
    private volatile long _offset;
    private boolean _alreadyChanged;
    private Set _listeners;

    /** if the clock is skewed by 3+ days, fuck 'em */
    public final static long MAX_OFFSET = 3 * 24 * 60 * 60 * 1000;
    /** if the clock skewed changes by less than 1s, ignore the update (so we don't slide all over the place) */
    public final static long MIN_OFFSET_CHANGE = 30 * 1000;

    private Clock() {
        _offset = 0;
        _alreadyChanged = false;
        _listeners = new HashSet(64);
    }

    /**
     * Specify how far away from the "correct" time the computer is - a positive
     * value means that we are slow, while a negative value means we are fast.
     *
     */
    public void setOffset(long offsetMs) {
        if ((offsetMs > MAX_OFFSET) || (offsetMs < 0 - MAX_OFFSET)) {
            _log.error("Maximum offset shift exceeded [" + offsetMs + "], NOT HONORING IT");
            return;
        }
        long delta = offsetMs - _offset;
        if ((delta < MIN_OFFSET_CHANGE) && (delta > 0 - MIN_OFFSET_CHANGE)) {
            _log.debug("Not changing offset since it is only " + delta + "ms");
            return;
        }
        if (_alreadyChanged)
            _log.log(Log.CRIT, "Updating clock offset to " + offsetMs + "ms from " + _offset + "ms");
        else
            _log.log(Log.INFO, "Initializing clock offset to " + offsetMs + "ms from " + _offset + "ms");
        _alreadyChanged = true;
        _offset = offsetMs;
        fireOffsetChanged(delta);
    }

    public long getOffset() {
        return _offset;
    }

    public void setNow(long realTime) {
        long diff = realTime - System.currentTimeMillis();
        setOffset(diff);
    }

    /**
     * Retrieve the current time synchronized with whatever reference clock is in
     * use.
     *
     */
    public long now() {
        return _offset + System.currentTimeMillis();
    }

    public void addUpdateListener(ClockUpdateListener lsnr) {
        synchronized (_listeners) {
            _listeners.add(lsnr);
        }
    }

    public void removeUpdateListener(ClockUpdateListener lsnr) {
        synchronized (_listeners) {
            _listeners.remove(lsnr);
        }
    }

    private void fireOffsetChanged(long delta) {
        synchronized (_listeners) {
            for (Iterator iter = _listeners.iterator(); iter.hasNext();) {
                ClockUpdateListener lsnr = (ClockUpdateListener) iter.next();
                lsnr.offsetChanged(delta);
            }
        }
    }

    public static interface ClockUpdateListener {
        public void offsetChanged(long delta);
    }
}