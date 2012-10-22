package net.i2p.update;

import java.net.URI;

/**
 *  A running check or download. Cannot be restarted.
 *
 *  @since 0.9.4
 */
public interface UpdateTask {
    
    /**
     *  Tasks must not start themselves in the constructor. Do it here.
     */
    public void start();

    public void shutdown();

    public boolean isRunning();

    public UpdateType getType();

    public UpdateMethod getMethod();

   /**
    *  The current URI being checked or downloaded from.
    *  Can change if there are multiple URIs to try.
    */
    public URI getURI();

   /**
    *  Valid for plugins
    */
    public String getID();
}
