package net.i2p.router.util;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 *  Priority Blocking Queue using methods in the entries,
 *  as definied in PQEntry, to store priority and sequence number,
 *  ensuring FIFO order within a priority.
 *
 *  Input: add(), offer(), and put() are overridden to add a sequence number.
 *
 *  @since 0.9.3
 */
public class PriBlockingQueue<E extends PQEntry> extends PriorityBlockingQueue<E> {

    private final AtomicLong _seqNum = new AtomicLong();

    protected static final int BACKLOG_SIZE = 256;

    public PriBlockingQueue(int initialCapacity) {
        super(initialCapacity, new PriorityComparator());
    }

    @Override
    public boolean add(E o) {
        timestamp(o);
        return super.add(o);
    }

    @Override
    public boolean offer(E o) {
        timestamp(o);
        return super.offer(o);
    }

    @Override
    public boolean offer(E o, long timeout, TimeUnit unit) {
        timestamp(o);
        return super.offer(o, timeout, unit);
    }

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
    private static class PriorityComparator<E extends PQEntry> implements Comparator<E> {
        public int compare(E l, E r) {
            int d = r.getPriority() - l.getPriority();
            if (d != 0)
                return d;
            long ld = l.getSeqNum() - r.getSeqNum();
            return ld > 0 ? 1 : -1;
        }
    }
}
