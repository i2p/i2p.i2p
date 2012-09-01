package net.i2p.router.util;

/**
 *  For CoDelPriorityQueue
 *  @since 0.9.3
 */
public interface CDPQEntry extends CDQEntry {

    /**
     *  Higher is higher priority
     */
    public int getPriority();

    /**
     *  To be set by the queue
     */
    public void setSeqNum(long num);

    /**
     *  Needed to ensure FIFO ordering within a single priority
     */
    public long getSeqNum();
}
