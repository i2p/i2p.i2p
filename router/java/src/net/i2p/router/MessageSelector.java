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
 * Define a mechanism to select what messages are associated with a particular 
 * OutNetMessage.  This is used for finding replies to messages.
 *
 */
public interface MessageSelector {
    /**
     * Returns true if the received message matches the selector
     */
    public boolean isMatch(I2NPMessage message);
    /**
     * Returns true if the selector should still keep searching for further matches
     *
     */
    public boolean continueMatching();
    /**
     * Returns the # of milliseconds since the epoch after which this selector should
     * stop searching for matches
     *
     */
    public long getExpiration();
}
