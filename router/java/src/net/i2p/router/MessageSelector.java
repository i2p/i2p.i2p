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
     * Returns true if the received message matches the selector.
     * If this returns true, the job specified by OutNetMessage.getOnReplyJob()
     * will be run for every OutNetMessage associated with this selector
     * (by InNetMessagePool), after calling setMessage() for that ReplyJob.
     *
     * WARNING this is called from within OutboundMessageSelector.getOriginalMessages()
     * inside a lock and can lead to deadlocks if the selector does too much in isMatch().
     * Until the lock is removed, take care to keep it simple.
     *
     */
    public boolean isMatch(I2NPMessage message);

    /**
     * Returns true if the selector should still keep searching for further matches.
     * This is called only if isMatch() returns true.
     * If this returns true, isMatch() will not be called again.
     */
    public boolean continueMatching();

    /**
     * Returns the # of milliseconds since the epoch after which this selector should
     * stop searching for matches.
     * At some time after expiration, if continueMatching() has not returned false,
     * the job specified by OutNetMessage.getOnFailedReplyJob()
     * will be run for every OutNetMessage associated with this selector
     * (by OutboundMessageRegistry).
     */
    public long getExpiration();
}
