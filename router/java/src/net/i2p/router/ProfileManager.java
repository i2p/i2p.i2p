package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;

import net.i2p.data.Hash;
import net.i2p.router.peermanager.ProfileManagerImpl;

public abstract class ProfileManager {
    private final static ProfileManager _instance = new ProfileManagerImpl();
    public static ProfileManager getInstance() { return _instance; }

    /** is this peer failing or already dropped? */
    public abstract boolean isFailing(Hash peer);
    
    /**
     * Note that it took msToSend to send a message of size bytesSent to the peer over the transport.
     * This should only be called if the transport considered the send successful.
     *
     */
    public abstract void messageSent(Hash peer, String transport, long msToSend, long bytesSent);
    
    /**
     * Note that the router failed to send a message to the peer over the transport specified
     *
     */
    public abstract void messageFailed(Hash peer, String transport);
    
    /**
     * Note that the router failed to send a message to the peer over any transport
     *
     */
    public abstract void messageFailed(Hash peer);
    
    /**
     * Note that there was some sort of communication error talking with the peer
     *
     */
    public abstract void commErrorOccurred(Hash peer);
	
    /**
     * Note that the router agreed to participate in a tunnel
     *
     */
    public abstract void tunnelJoined(Hash peer, long responseTimeMs);
    
    /**
     * Note that a router explicitly rejected joining a tunnel
     *
     */
    public abstract void tunnelRejected(Hash peer, long responseTimeMs);
    
    /**
     * Note that the peer participated in a tunnel that failed.  Its failure may not have
     * been the peer's fault however.
     *
     */
    public abstract void tunnelFailed(Hash peer);
    
    /**
     * Note that the peer was able to return the valid data for a db lookup
     *
     */
    public abstract void dbLookupSuccessful(Hash peer, long responseTimeMs);
    
    /**
     * Note that the peer was unable to reply to a db lookup - either with data or with
     * a lookupReply redirecting the user elsewhere
     *
     */
    public abstract void dbLookupFailed(Hash peer);
    
    /**
     * Note that the peer replied to a db lookup with a redirect to other routers, where
     * the list of redirected users included newPeers routers that the local router didn't
     * know about, oldPeers routers that the local router already knew about, the given invalid
     * routers that were invalid in some way, and the duplicate number of routers that we explicitly
     * asked them not to send us, but they did anyway
     *
     */
    public abstract void dbLookupReply(Hash peer, int newPeers, int oldPeers, int invalid, int duplicate, long responseTimeMs);
    
    /**
     * Note that the local router received a db lookup from the given peer
     *
     */
    public abstract void dbLookupReceived(Hash peer);
    
    /**
     * Note that the local router received an unprompted db store from the given peer
     *
     */
    public abstract void dbStoreReceived(Hash peer, boolean wasNewKey);
    
    /**
     * Note that we've confirmed a successful send of db data to the peer (though we haven't
     * necessarily requested it again from them, so they /might/ be lying)
     *
     */
    public abstract void dbStoreSent(Hash peer, long responseTimeMs);
    
    /**
     * Note that we were unable to confirm a successful send of db data to 
     * the peer, at least not within our timeout period
     *
     */
    public abstract void dbStoreFailed(Hash peer);
    
    /**
     * Note that the local router received a reference to the given peer, either
     * through an explicit dbStore or in a dbLookupReply
     */
    public abstract void heardAbout(Hash peer);
    
    /**
     * Note that the router received a message from the given peer on the specified
     * transport.  Messages received without any "from" information aren't recorded
     * through this metric.  If msToReceive is negative, there was no timing information
     * available
     *
     */
    public abstract void messageReceived(Hash peer, String style, long msToReceive, int bytesRead);
    
    /** provide a simple summary of a number of peers, suitable for publication in the netDb */
    public abstract Properties summarizePeers(int numPeers);
}
