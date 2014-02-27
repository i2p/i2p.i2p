package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2np.I2NPMessage;

/**
 * Defines an executable task that can be fired off in reply to a message
 *
 */
public interface ReplyJob extends Job {

    /**
     *  Called by InNetMessagePool when an I2NPMessage
     *  matching a MessageSelector registered with the OutboundMessageRegistry
     *  is received
     */
    public void setMessage(I2NPMessage message);
}
