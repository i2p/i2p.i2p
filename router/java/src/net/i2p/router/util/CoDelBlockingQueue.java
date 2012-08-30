package net.i2p.router.util;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.i2p.I2PAppContext;

/**
 *  CoDel implementation of Active Queue Management.
 *  Ref: http://queue.acm.org/detail.cfm?id=2209336
 *  Ref: http://queue.acm.org/appendices/codel.html
 *
 *  Code and comments are directly from appendix above, apparently public domain.
 *
 *  Input: add(), offer(), and put() are overridden to add a timestamp.
 *
 *  Output : take() is overridden to implement AQM and drop entries
 *  if necessary. poll(), peek(), and remove() are NOT overridden, and do
 *  NOT implement AQM or update stats.
 *
 *  @since 0.9.3
 */
public class CoDelBlockingQueue<E extends CDQEntry> extends LinkedBlockingQueue<E> {

    private final I2PAppContext _context;

    // following 4 are state variables defined by sample code, locked by this
    /** Time when we'll declare we're above target (0 if below) */
    private long _first_above_time;
    /** Time to drop next packet */
    private long _drop_next;
    /** Packets dropped since going into drop state */
    private int _count;
    /** true if in drop state */
    private boolean _dropping;

    /** following is a per-request global for ease of use, locked by this */
    private long _now;

    /**
     *  Quote:
     *  Below a target of 5 ms, utilization suffers for some conditions and traffic loads;
     *  above 5 ms there is very little or no improvement in utilization.
     *
     *  Maybe need to make configurable per-instance.
     */
    private static final long TARGET = 5;

    /**
     *  Quote:
     *  A setting of 100 ms works well across a range of RTTs from 10 ms to 1 second
     *
     *  Maybe need to make configurable per-instance.
     */
    private static final long INTERVAL = 100;
    //private static final int MAXPACKET = 512;

    private final String STAT_DROP;
    private final String STAT_DELAY;

    /**
     *  @param name for stats
     */
    public CoDelBlockingQueue(I2PAppContext ctx, String name, int capacity) {
        super(capacity);
        _context = ctx;
        STAT_DROP = "router.codel." + name + ".drop";
        STAT_DELAY = "router.codel." + name + ".delay";
        ctx.statManager().createRequiredRateStat(STAT_DROP, "drop rate", "Router", new long[] { 10*60*1000 });
        ctx.statManager().createRequiredRateStat(STAT_DELAY, "delay", "Router", new long[] { 10*60*1000 });
    }

    @Override
    public boolean add(E o) {
        o.setEnqueueTime(_context.clock().now());
        return super.add(o);
    }

    @Override
    public boolean offer(E o) {
        o.setEnqueueTime(_context.clock().now());
        return super.offer(o);
    }

    @Override
    public boolean offer(E o, long timeout, TimeUnit unit) throws InterruptedException {
        o.setEnqueueTime(_context.clock().now());
        return super.offer(o, timeout, unit);
    }

    @Override
    public void put(E o) throws InterruptedException {
        o.setEnqueueTime(_context.clock().now());
        super.put(o);
    }

    @Override
    public void clear() {
        super.clear();
        synchronized(this) {
            _first_above_time = 0;
            _drop_next = 0;
            _count = 0;
            _dropping = false;
        }
    }

    @Override
    public E take() throws InterruptedException {
        E rv;
        do {
            rv = deque();
        } while (rv == null);
        return rv;
    }

    /////// private below here

    /**
     *  Caller must synch on this
     *  @param entry may be null
     */
    private boolean updateVars(E entry) {
        // This is a helper routine the does the actual packet dequeue and tracks whether the sojourn time
        // is above or below target and, if above, if it has remained above continuously for at least interval.
        // It returns two values, a Boolean indicating whether it is OK to drop (sojourn time above target
        // for at least interval) and the packet dequeued.
        _now = _context.clock().now();
        if (entry == null) {
            _first_above_time = 0;
            return false;
        }
        boolean ok_to_drop = false;
        long sojurn = _now - entry.getEnqueueTime();
        _context.statManager().addRateData(STAT_DELAY, sojurn);
        // I2P use isEmpty instead of size() < MAXPACKET
        if (sojurn < TARGET || isEmpty()) {
            _first_above_time = 0;
        } else {
            if (_first_above_time == 0) {
                // just went above from below. if we stay above
                // for at least INTERVAL we'll say it's ok to drop
                _first_above_time = _now + INTERVAL;
            } else if (_now >= _first_above_time) {
                ok_to_drop = true;
            }
        }
        return ok_to_drop;
    }

    /**
     *  @return if null, call again
     */
    private E deque() throws InterruptedException {
        E rv = super.take();
        synchronized (this) {
            // non-blocking inside this synchronized block

            boolean ok_to_drop = updateVars(rv);
            // All of the work of CoDel is done here.
            // There are two branches: if we're in packet-dropping state (meaning that the queue-sojourn
            // time has gone above target and hasn't come down yet), then we need to check if it's time
            // to leave or if it's time for the next drop(s); if we're not in dropping state, then we need
            // to decide if it's time to enter and do the initial drop.
            if (_dropping) {
                if (!ok_to_drop) {
                    // sojurn time below target - leave dropping state
                    _dropping = false;
                } else if (_now >= _drop_next) {
                    // It's time for the next drop. Drop the current packet and dequeue the next.
                    // The dequeue might take us out of dropping state. If not, schedule the next drop.
                    // A large backlog might result in drop rates so high that the next drop should happen now;
                    // hence, the while loop.
                    while (_now >= _drop_next && _dropping) {
                        drop(rv);
                        _count++;
                        // I2P - we poll here instead of lock so we don't get stuck
                        // inside the lock. If empty, deque() will be called again.
                        rv = poll();
                        ok_to_drop = updateVars(rv);
                        if (!ok_to_drop) {
                            // leave dropping state
                            _dropping = false;
                        } else {
                            // schedule the next drop
                            control_law(_drop_next);
                        }
                    }
                }
            } else if (ok_to_drop &&
                       (_now - _drop_next < INTERVAL || _now - _first_above_time >= INTERVAL)) {
                // If we get here, then we're not in dropping state. If the sojourn time has been above
                // target for interval, then we decide whether it's time to enter dropping state.
                // We do so if we've been either in dropping state recently or above target for a relatively
                // long time. The "recently" check helps ensure that when we're successfully controlling
                // the queue we react quickly (in one interval) and start with the drop rate that controlled
                // the queue last time rather than relearn the correct rate from scratch. If we haven't been
                // dropping recently, the "long time above" check adds some hysteresis to the state entry
                // so we don't drop on a slightly bigger-than-normal traffic pulse into an otherwise quiet queue.
                drop(rv);
                // I2P - we poll here instead of lock so we don't get stuck
                // inside the lock. If empty, deque() will be called again.
                rv = poll();
                updateVars(rv);
                _dropping = true;
                // If we're in a drop cycle, the drop rate that controlled the queue
                // on the last cycle is a good starting point to control it now.
                if (_now - _drop_next < INTERVAL)
                    _count = _count > 2 ? _count - 2 : 1;
                else
                    _count = 1;
                control_law(_now);
            }
        }
        return rv;
    }

    private void drop(E entry) {
        _context.statManager().addRateData(STAT_DROP, 1);
        entry.drop();
    }

    /**
     *  Caller must synch on this
     */
    private void control_law(long t) {
        _drop_next = t + (long) (INTERVAL / Math.sqrt(_count));
    }
}
