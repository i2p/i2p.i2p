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
import net.i2p.router.tunnelmanager.PoolingTunnelManagerFacade;

/**
 * Build and maintain tunnels throughout the network.
 *
 */ 
public abstract class TunnelManagerFacade implements Service {
    private static TunnelManagerFacade _instance = new PoolingTunnelManagerFacade();
    public static TunnelManagerFacade getInstance() { return _instance; }
    
    /** 
     * React to a request to join the specified tunnel.
     *
     * @return true if the router will accept participation, else false.
     */
    public abstract boolean joinTunnel(TunnelInfo info);
    /**
     * Retrieve the information related to a particular tunnel
     *
     */
    public abstract TunnelInfo getTunnelInfo(TunnelId id);
    /**
     * Retrieve a set of tunnels from the existing ones for various purposes
     */
    public abstract List selectOutboundTunnelIds(TunnelSelectionCriteria criteria);
    /**
     * Retrieve a set of tunnels from the existing ones for various purposes
     */
    public abstract List selectInboundTunnelIds(TunnelSelectionCriteria criteria);
    
    /**
     * Make sure appropriate outbound tunnels are in place, builds requested
     * inbound tunnels, then fire off a job to ask the ClientManagerFacade to 
     * validate the leaseSet, then publish it in the network database.
     *
     */
    public abstract void createTunnels(Destination destination, ClientTunnelSettings clientSettings, long timeoutMs);
    
    /**
     * Called when a peer becomes unreachable - go through all of the current
     * tunnels and rebuild them if we can, or drop them if we can't.
     *
     */
    public abstract void peerFailed(Hash peer);

    /**
     * True if the peer currently part of a tunnel
     *
     */
    public abstract boolean isInUse(Hash peer);
}
