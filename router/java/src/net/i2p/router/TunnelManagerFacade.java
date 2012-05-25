package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;

/**
 * Build and maintain tunnels throughout the network.
 *
 */ 
public interface TunnelManagerFacade extends Service {
    /**
     * Retrieve the information related to a particular tunnel
     *
     * @param id the tunnelId as seen at the gateway
     *
     */
    TunnelInfo getTunnelInfo(TunnelId id);
    /** pick an inbound tunnel not bound to a particular destination */
    TunnelInfo selectInboundTunnel();
    /** pick an inbound tunnel bound to the given destination */
    TunnelInfo selectInboundTunnel(Hash destination);
    /** pick an outbound tunnel not bound to a particular destination */
    TunnelInfo selectOutboundTunnel();
    /** pick an outbound tunnel bound to the given destination */
    TunnelInfo selectOutboundTunnel(Hash destination);
    
    /** Is a tunnel a valid member of the pool? */
    public boolean isValidTunnel(Hash client, TunnelInfo tunnel);
    
    /** how many tunnels are we participating in? */
    public int getParticipatingCount();
    /** how many free inbound tunnels do we have available? */
    public int getFreeTunnelCount();
    /** how many outbound tunnels do we have available? */
    public int getOutboundTunnelCount();
    /** how many free inbound client tunnels do we have available? */
    public int getInboundClientTunnelCount();
    /** how many outbound client tunnels do we have available? */
    public int getOutboundClientTunnelCount();
    
    /** When does the last tunnel we are participating in expire? */
    public long getLastParticipatingExpiration();
    
    /** count how many inbound tunnel requests we have received but not yet processed */
    public int getInboundBuildQueueSize();
    
    /** @return Set of peers that should not be allowed to be in another tunnel */
    public Set<Hash> selectPeersInTooManyTunnels();

    /** 
     * the client connected (or updated their settings), so make sure we have
     * the tunnels for them, and whenever necessary, ask them to authorize 
     * leases.
     *
     */
    public void buildTunnels(Destination client, ClientTunnelSettings settings);
    
    public TunnelPoolSettings getInboundSettings();
    public TunnelPoolSettings getOutboundSettings();
    public TunnelPoolSettings getInboundSettings(Hash client);
    public TunnelPoolSettings getOutboundSettings(Hash client);
    public void setInboundSettings(TunnelPoolSettings settings);
    public void setOutboundSettings(TunnelPoolSettings settings);
    public void setInboundSettings(Hash client, TunnelPoolSettings settings);
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings);
}
