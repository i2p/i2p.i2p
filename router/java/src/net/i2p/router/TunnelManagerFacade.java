package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.List;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;

/**
 * Build and maintain tunnels throughout the network.
 *
 */ 
public interface TunnelManagerFacade extends Service {

    /** 
     * React to a request to join the specified tunnel.
     *
     * @return true if the router will accept participation, else false.
     */
    boolean joinTunnel(TunnelInfo info);
    /**
     * Retrieve the information related to a particular tunnel
     *
     */
    TunnelInfo getTunnelInfo(TunnelId id);
    /**
     * Retrieve a set of tunnels from the existing ones for various purposes
     */
    List selectOutboundTunnelIds(TunnelSelectionCriteria criteria);
    /**
     * Retrieve a set of tunnels from the existing ones for various purposes
     */
    List selectInboundTunnelIds(TunnelSelectionCriteria criteria);
    
    /**
     * Make sure appropriate outbound tunnels are in place, builds requested
     * inbound tunnels, then fire off a job to ask the ClientManagerFacade to 
     * validate the leaseSet, then publish it in the network database.
     *
     */
    void createTunnels(Destination destination, ClientTunnelSettings clientSettings, long timeoutMs);
    
    /**
     * Called when a peer becomes unreachable - go through all of the current
     * tunnels and rebuild them if we can, or drop them if we can't.
     *
     */
    void peerFailed(Hash peer);

    /**
     * True if the peer currently part of a tunnel
     *
     */
    boolean isInUse(Hash peer);
    
    /** how many tunnels are we participating in? */
    public int getParticipatingCount();
    /** how many free inbound tunnels do we have available? */
    public int getFreeTunnelCount();
    /** how many outbound tunnels do we have available? */
    public int getOutboundTunnelCount();
}
