package net.i2p.client.streaming;

/**
 * Coordinates what we do 'next'.  The scheduler used by a connection is
 * selected based upon its current state.
 *
 */
public interface TaskScheduler {
    /**
     * An event has occurred (timeout, message sent, or message received),
     * so schedule what to do next based on our current state.
     *
     */
    public void eventOccurred(Connection con);
    
    /** 
     * Determine whether this scheduler is fit to operate against the 
     * given connection
     *
     */ 
    public boolean accept(Connection con);
}
