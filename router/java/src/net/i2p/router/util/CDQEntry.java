package net.i2p.router.util;

/**
 *  For CoDelQueue
 *  @since 0.9.3
 */
public interface CDQEntry {

    /**
     *  To be set by the queue
     */
    public void setEnqueueTime(long time);

    public long getEnqueueTime();

    /**
     *  Implement any reclaimation of resources here
     */
    public void drop();
}
