package net.i2p.client.streaming;

/**
 * A ConnectionFilter that may hold state, can be started and stopped
 * @since 0.9.40
 */
public interface StatefulConnectionFilter extends IncomingConnectionFilter {

    /**
     * Tells this filter to start
     */ 
    public void start();

    /**
     * Tells this filter to stop and release any resources
     */
    public void stop();

}
