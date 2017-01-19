package net.i2p.router.util;

import java.io.Serializable;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 *  Priority Blocking Queue using methods in the entries,
 *  as defined in PQEntry, to store priority and sequence number,
 *  ensuring FIFO order within a priority.
 *
 *  Input: add(), offer(), and put() are overridden to add a sequence number.
 *
 *  @since 0.9.3
 */
public class PriBlockingQueue<E extends PQEntry> extends PriorityBlockingQueue<E> {

    private static final long serialVersionUID = 1L;
    protected transient final I2PAppContext _context;
    protected transient final Log _log;
    protected final String _name;
    private final AtomicLong _seqNum = new AtomicLong();

    private final String STAT_FULL;
    protected static final long[] RATES = {5*60*1000, 60*60*1000};
    protected static final int BACKLOG_SIZE = 256;
    protected static final int MAX_SIZE = 512;

    /**
     *  Bounded queue with a hardcoded failsafe max size,
     *  except when using put(), which is unbounded.
     */
    public PriBlockingQueue(I2PAppContext ctx, String name, int initialCapacity) {
        super(initialCapacity, new PriorityComparator<E>());
        _context = ctx;
        _log = ctx.logManager().getLog(PriorityBlockingQueue.class);
        _name = name;
        STAT_FULL = ("pbq." + name + ".full").intern();
        ctx.statManager().createRateStat(STAT_FULL, "queue full", "Router", RATES);
    }

    /**
     *  OpenJDK add(o) calls offer(o), so use offer(o) to avoid dup stamping.
     *  Returns false if full
     *  @deprecated use offer(o)
     */
    @Deprecated
    @Override
    public boolean add(E o) {
        timestamp(o);
        if (size() >= MAX_SIZE) {
            _context.statManager().addRateData(STAT_FULL, 1);
            return false;
        }
        return super.add(o);
    }

    /**
     *  Returns false if full
     */
    @Override
    public boolean offer(E o) {
        timestamp(o);
        if (size() >= MAX_SIZE) {
            _context.statManager().addRateData(STAT_FULL, 1);
            return false;
        }
        return super.offer(o);
    }

    /**
     *  OpenJDK offer(o, timeout, unit) calls offer(o), so use offer(o) to avoid dup stamping.
     *  Non blocking. Returns false if full.
     *  @param timeout ignored
     *  @param unit ignored
     *  @deprecated use offer(o)
     */
    @Deprecated
    @Override
    public boolean offer(E o, long timeout, TimeUnit unit) {
        timestamp(o);
        if (size() >= MAX_SIZE) {
            _context.statManager().addRateData(STAT_FULL, 1);
            return false;
        }
        return super.offer(o, timeout, unit);
    }

    /**
     *  OpenJDK put(o) calls offer(o), so use offer(o) to avoid dup stamping.
     *  Non blocking. Does not add if full.
     *  @deprecated use offer(o)
     */
    @Deprecated
    @Override
    public void put(E o) {
        timestamp(o);
        super.put(o);
    }

    /**
     *  Is the queue too big?
     */
    public boolean isBacklogged() {
        return size() >= BACKLOG_SIZE;
    }

    /////// private below here

    protected void timestamp(E o) {
        o.setSeqNum(_seqNum.incrementAndGet());
    }

    /**
     *  highest priority first, then lowest sequence number first
     */
    private static class PriorityComparator<E extends PQEntry> implements Comparator<E>, Serializable {
        public int compare(E l, E r) {
            int d = r.getPriority() - l.getPriority();
            if (d != 0)
                return d;
            long ld = l.getSeqNum() - r.getSeqNum();
            return ld > 0 ? 1 : -1;
        }
    }
}
