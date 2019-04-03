package net.i2p.i2ptunnel;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * Count how often something happens with a particular peer and all peers.
 * This offers basic DOS protection but is not a complete solution.
 *
 * This is a little different from the one in streaming, in that the
 * ban time is different from the check time, and we keep a separate
 * map of throttled peers with individual time stamps.
 * The streaming version is lightweight but "sloppy" since it
 * uses a single time bucket for all.
 *
 * @since 0.9.9
 */
class ConnThrottler {
    private int _max;
    private int _totalMax;
    private long _checkPeriod;
    private long _throttlePeriod;
    private long _totalThrottlePeriod;
    private int _currentTotal;
    private final Map<Hash, Record> _peers;
    private long _totalThrottleUntil;
    private final String _action;
    private final Log _log;
    private final DateFormat _fmt;
    private final SimpleTimer2.TimedEvent _cleaner;
    private boolean _isRunning;

    /*
     * Caller MUST call start()
     *
     * @param max per-peer, 0 for unlimited
     * @param totalMax for all peers, 0 for unlimited
     * @param period check window (ms)
     * @param throttlePeriod how long to ban a peer (ms)
     * @param totalThrottlePeriod how long to ban all peers (ms)
     * @param action just a name to note in the log
     */
    public ConnThrottler(int max, int totalMax, long period,
                         long throttlePeriod, long totalThrottlePeriod, String action, Log log) {
        updateLimits(max, totalMax, period, throttlePeriod, totalThrottlePeriod);
        _peers = new HashMap<Hash, Record>(4);
        _action = action;
        _log = log;
        // for logging
        _fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        _fmt.setTimeZone(SystemVersion.getSystemTimeZone());
        _cleaner = new Cleaner();
    }

    /*
     * If already started, has no effect.
     *
     * @since 0.9.40
     */
    public synchronized void start() {
        if (_isRunning)
            return;
        _isRunning = true;
        _cleaner.schedule(_checkPeriod);
    }

    /*
     * May be restarted.
     *
     * @since 0.9.40
     */
    public synchronized void stop() {
        _isRunning = false;
        _cleaner.cancel();
        clear();
    }

    /*
     * All periods in ms
     * @param max per-peer, 0 for unlimited
     * @param totalMax for all peers, 0 for unlimited
     * @since 0.9.3
     */
    public synchronized void updateLimits(int max, int totalMax, long checkPeriod, long throttlePeriod, long totalThrottlePeriod) {
        _max = max;
        _totalMax = totalMax;
        _checkPeriod = Math.max(checkPeriod, 10*1000);
        _throttlePeriod = Math.max(throttlePeriod, 10*1000);
        _totalThrottlePeriod = Math.max(totalThrottlePeriod, 10*1000);
    }

    /**
     *  Checks both individual and total. Increments before checking.
     */
    public synchronized boolean shouldThrottle(Hash h) {
        // all throttled already?
        if (_totalMax > 0) {
            if (_totalThrottleUntil > 0) {
                if (_totalThrottleUntil > Clock.getInstance().now())
                    return true;
                _totalThrottleUntil = 0;
            }
        }
        // do this first, so we don't increment total if individual throttled
        if (_max > 0) {
            Record rec = _peers.get(h);
            if (rec != null) {
                // peer throttled already?
                if (rec.getUntil() > 0)
                    return true;
                rec.increment();
                long now = Clock.getInstance().now();
                if (rec.countSince(now - _checkPeriod) > _max) {
                    long until = now + _throttlePeriod;
                    String date = _fmt.format(new Date(until));
                    _log.logAlways(Log.WARN, "Throttling " + _action + " until " + date +
                                             " after exceeding max of " + _max +
                                             " in " + DataHelper.formatDuration(_checkPeriod) +
                                             ": " + h.toBase64());
                    rec.ban(until);
                    return true;
                }
            } else {
                _peers.put(h, new Record());
            }
        }
        if (_totalMax > 0 && ++_currentTotal > _totalMax) {
            if (_totalThrottleUntil == 0) {
                _totalThrottleUntil = Clock.getInstance().now() + _totalThrottlePeriod;
                String date = _fmt.format(new Date(_totalThrottleUntil));
                _log.logAlways(Log.WARN, "*** Throttling " + _action + " from ALL peers until " + date +
                                         " after exceeding max of " + _max +
                                         " in " + DataHelper.formatDuration(_checkPeriod));
            }
            return true;
        }
        return false;
    }

    /**
     *  start over
     */
    public synchronized void clear() {
        _currentTotal = 0;
        _totalThrottleUntil = 0;
        _peers.clear();
    }

    /**
     *  Keep a list of seen times, and a ban-until time.
     *  Caller must sync all methods.
     */
    private static class Record {
        private final List<Long> times;
        private long until;

        public Record() {
            times = new ArrayList<Long>(8);
            increment();
        }

        /** Caller must synch */
        public int countSince(long time) {
            for (Iterator<Long> iter = times.iterator(); iter.hasNext(); ) {
                if (iter.next().longValue() < time)
                    iter.remove();
                else
                    break;
            }
            return times.size();
        }

        /** Caller must synch */
        public void increment() {
            times.add(Long.valueOf(Clock.getInstance().now()));
        }

        /** Caller must synch */
        public void ban(long untilTime) {
            until = untilTime;
            // don't need to save times if banned
            times.clear();
        }

        /** Caller must synch */
        public long getUntil() {
            if (until < Clock.getInstance().now())
                until = 0;
            return until;
        }
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {
        /** must call schedule() later */
        public Cleaner() {
            super(SimpleTimer2.getInstance());
        }

        public void timeReached() {
            synchronized(ConnThrottler.this) {
                if (_totalMax > 0)
                    _currentTotal = 0;
                if (_max > 0 && !_peers.isEmpty()) {
                    long then = Clock.getInstance().now()  - _checkPeriod;
                    for (Iterator<Record> iter = _peers.values().iterator(); iter.hasNext(); ) {
                        Record rec = iter.next();
                        if (rec.getUntil() <= 0 && rec.countSince(then) <= 0)
                            iter.remove();
                    }
                }
            }
            schedule(_checkPeriod);
        }
    }
}
