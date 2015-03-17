package net.i2p.router;

import net.i2p.data.Hash;

/**
 * Gatekeeper for deciding whether to throttle the further processing 
 * of messages through the router.  This is seperate from the bandwidth 
 * limiting which simply makes sure the bytes transferred dont exceed the 
 * bytes allowed (though the router throttle should take into account the
 * current bandwidth usage and limits when determining whether to accept or
 * reject certain activities, such as tunnels)
 *
 */
public interface RouterThrottle {
    /** 
     * Should we accept any more data from the network for any sort of message, 
     * taking into account our current load, or should we simply slow down?  
     *
     */
    public boolean acceptNetworkMessage();
    /**
     * Should we accept the request to participate in the given tunnel,
     * taking into account our current load and bandwidth usage commitments?
     * 
     * @return 0 if it should be accepted, higher values for more severe rejection
     */
    public int acceptTunnelRequest();
    /**
     * Should we accept the netDb lookup message, replying either with the 
     * value or some closer peers, or should we simply drop it due to overload?
     *
     */
    public boolean acceptNetDbLookupRequest(Hash key);
    
    /** How backed up we are at the moment processing messages (in milliseconds) */
    public long getMessageDelay();
    /** How backed up our tunnels are at the moment (in milliseconds) */
    public long getTunnelLag();
    /** 
     * How much faster (or if negative, slower) we are receiving data as 
     * opposed to our longer term averages?
     *
     */
    public double getInboundRateDelta();
    /**
     * Message on the state of participating tunnel acceptance
     */
    public String getTunnelStatus();
    public void setTunnelStatus(String msg);

    /** @since 0.8.12 */
    public void setShutdownStatus();

    /** @since 0.8.12 */
    public void cancelShutdownStatus();
}
