package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;

/**
 * Defines the information associated with a tunnel
 */
public interface TunnelInfo {
    /** how many peers are there in the tunnel (including the creator)? */
    public int getLength();
    
    /**
     * retrieve the tunnelId that the given hop receives messages on.  
     * the gateway is hop 0.
     *
     */
    public TunnelId getReceiveTunnelId(int hop);
    /**
     * retrieve the tunnelId that the given hop sends messages on.  
     * the gateway is hop 0.
     *
     */
    public TunnelId getSendTunnelId(int hop);
    
    /** retrieve the peer at the given hop.  the gateway is hop 0 */
    public Hash getPeer(int hop);

    /** is this an inbound tunnel? */
    public boolean isInbound();

    /** if this is a client tunnel, what destination is it for? */
    public Hash getDestination();
    
    public long getExpiration();
    /** 
     * take note that the tunnel was able to measurably Do Good 
     * in the given time 
     */
    public void testSuccessful(int responseTime);
    
    public long getProcessedMessagesCount();
}
