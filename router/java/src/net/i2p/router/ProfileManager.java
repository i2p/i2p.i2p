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

public interface ProfileManager {    
    /**
     * Note that it took msToSend to send a message of size bytesSent to the peer over the transport.
     * This should only be called if the transport considered the send successful.
     *
     */
    void messageSent(Hash peer, String transport, long msToSend, long bytesSent);
    
    /**
     * Note that the router failed to send a message to the peer over the transport specified
     *
     */
    void messageFailed(Hash peer, String transport);
    
    /**
     * Note that the router failed to send a message to the peer over any transport
     *
     */
    void messageFailed(Hash peer);
    
    /**
     * Note that there was some sort of communication error talking with the peer
     *
     * @deprecated unused
     */
    @Deprecated
    void commErrorOccurred(Hash peer);
	
    /**
     * Note that the router agreed to participate in a tunnel
     *
     */
    void tunnelJoined(Hash peer, long responseTimeMs);
    
    /**
     * Note that a router explicitly rejected joining a tunnel
     * 
     * @param peer who rejected us
     * @param responseTimeMs how long it took to get the rejection
     * @param severity how much the peer doesnt want to participate in the 
     *                 tunnel (large == more severe)
     */
    void tunnelRejected(Hash peer, long responseTimeMs, int severity);
    
    /**
     * Note that a router timed out joining a tunnel
     * 
     * @param peer who rejected us
     */
    void tunnelTimedOut(Hash peer);
    
    /**
     * Note that a tunnel that the router is participating in
     * was successfully tested with the given round trip latency
     *
     */
    void tunnelTestSucceeded(Hash peer, long responseTimeMs);

    /**
     * Note that we were able to push some data through a tunnel that the peer
     * is participating in (detected after rtt).
     *
     */
    void tunnelDataPushed(Hash peer, long rtt, int size);
    
    /**
     * Note that the peer is participating in a tunnel that pushed the given amount of data
     * over the last minute.
     */
    void tunnelDataPushed1m(Hash peer, int size);
    
    /**
     * Note that we were able to push the given amount of data through a tunnel
     * that the peer is participating in
     */
    void tunnelLifetimePushed(Hash peer, long lifetime, long size);
    
    /**
     * Note that the peer participated in a tunnel that failed.  Its failure may not have
     * been the peer's fault however.
     *
     */
    void tunnelFailed(Hash peer, int pct);
    
    /**
     * Note that the peer was able to return the valid data for a db lookup
     *
     */
    void dbLookupSuccessful(Hash peer, long responseTimeMs);
    
    /**
     * Note that the peer was unable to reply to a db lookup - either with data or with
     * a lookupReply redirecting the user elsewhere
     *
     */
    void dbLookupFailed(Hash peer);
    
    /**
     * Note that the peer replied to a db lookup with a redirect to other routers, where
     * the list of redirected users included newPeers routers that the local router didn't
     * know about, oldPeers routers that the local router already knew about, the given invalid
     * routers that were invalid in some way, and the duplicate number of routers that we explicitly
     * asked them not to send us, but they did anyway
     *
     */
    void dbLookupReply(Hash peer, int newPeers, int oldPeers, int invalid, int duplicate, long responseTimeMs);
    
    /**
     * Note that the local router received a db lookup from the given peer
     *
     * @deprecated unused
     */
    @Deprecated
    void dbLookupReceived(Hash peer);
    
    /**
     * Note that the local router received an unprompted db store from the given peer
     *
     */
    void dbStoreReceived(Hash peer, boolean wasNewKey);
    
    /**
     * Note that we've confirmed a successful send of db data to the peer (though we haven't
     * necessarily requested it again from them, so they /might/ be lying)
     *
     */
    void dbStoreSent(Hash peer, long responseTimeMs);
    
    /**
     * Note that we confirmed a successful send of db data to 
     * the peer.
     *
     */
    void dbStoreSuccessful(Hash peer);

    /**
     * Note that we were unable to confirm a successful send of db data to 
     * the peer, at least not within our timeout period
     *
     */
    void dbStoreFailed(Hash peer);
    
    /**
     * Note that the local router received a reference to the given peer, either
     * through an explicit dbStore or in a dbLookupReply
     *
     * @deprecated use heardAbout(peer, caps)
     */
    @Deprecated
    void heardAbout(Hash peer);

    /**
     * Note that the local router received a reference to the given peer
     * at a certain time. Only update the time if newer.
     * Non-blocking. Will not update the profile if we can't get the lock.
     *
     * @deprecated use heardAbout(peer, caps, when)
     */
    @Deprecated
    void heardAbout(Hash peer, long when);
    
    /**
     * Contitionally note that the local router received a reference to the given peer, either
     * through an explicit dbStore or in a dbLookupReply
     * Non-blocking. Will not update the profile if we can't get the lock.
     *
     * @param caps non-null from the peer's RI, used to determine if the profile will be created if it doesn't exist
     * @since 0.9.70
     */
    public void heardAbout(Hash peer, String caps);

    /**
     * Conditionally note that the local router received a reference to the given peer
     * at a certain time. Only update the time if newer.
     * Non-blocking. Will not update the profile if we can't get the lock.
     *
     * @param caps non-null from the peer's RI, used to determine if the profile will be created if it doesn't exist
     * @since 0.9.70
     */
    public void heardAbout(Hash peer, String caps, long when);

    /**
     * Note that the router received a message from the given peer on the specified
     * transport.  Messages received without any "from" information aren't recorded
     * through this metric.  If msToReceive is negative, there was no timing information
     * available
     *
     */
    void messageReceived(Hash peer, String style, long msToReceive, int bytesRead);
}
