package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;

/**
 * Define the manageable service interface for the subsystems in the I2P router
 *
 */
public interface Service {
    /**
     * Instruct the service that it should start normal operation.
     * This call DOES block until the service is ready.
     *
     */
    public void startup();
    
    /**
     * Instruct the service that the router is shutting down and that it should do
     * whatever is necessary to go down gracefully.  It should not depend on other
     * components at this point.  This call DOES block.
     *
     */
    public void shutdown();
    
    /**
     * Perform a soft restart.
     *
     */
    public void restart();
    
    public void renderStatusHTML(Writer out) throws IOException;
}
