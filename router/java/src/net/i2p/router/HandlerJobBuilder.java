package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessage;

/**
 * Defines a class that builds jobs to handle a particular message - these
 * builders are registered with the InNetMessagePool for various I2NP message 
 * types, allowing immediate queueing of a handler job rather than waiting for
 * a polling job to come pick it up.
 *
 */
public interface HandlerJobBuilder {
    /**
     * Create a new job to handle the received message.  
     *
     * @param receivedMessage I2NP message received
     * @param from router that sent the message (if available)
     * @param fromHash hash of the routerIdentity of the router that sent the message (if available)
     * @return a job or null if no particular job is appropriate (in which case,
     *         the message should go into the inbound message pool)
     */
    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash);
}
