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
import java.util.Map;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.tunnel.pool.TunnelPool;

/**
 * Build and maintain tunnels throughout the network.
 *
 */ 
public interface TunnelManagerFacade extends Service {

    /**
     * Retrieve the information related to a particular tunnel
     *
     * @param id the tunnelId as seen at the gateway
     * @deprecated unused
     */
    @Deprecated
    TunnelInfo getTunnelInfo(TunnelId id);

    /**
     * Pick a random inbound exploratory tunnel
     *
     * @return null if none
     */
    TunnelInfo selectInboundTunnel();

    /**
     * Pick a random inbound tunnel from the given destination's pool
     *
     * @param destination if null, returns inbound exploratory tunnel
     * @return null if none
     */
    TunnelInfo selectInboundTunnel(Hash destination);

    /**
     * Pick a random outbound exploratory tunnel
     *
     * @return null if none
     */
    TunnelInfo selectOutboundTunnel();

    /**
     * Pick a random outbound tunnel from the given destination's pool
     *
     * @param destination if null, returns outbound exploratory tunnel
     * @return null if none
     */
    TunnelInfo selectOutboundTunnel(Hash destination);
    
    /**
     * Pick the inbound exploratory tunnel with the gateway closest to the given hash.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectInboundExploratoryTunnel(Hash closestTo); 
    
    /**
     * Pick the inbound tunnel with the gateway closest to the given hash
     * from the given destination's pool.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param destination if null, returns inbound exploratory tunnel
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectInboundTunnel(Hash destination, Hash closestTo);
    
    /**
     * Pick the outbound exploratory tunnel with the endpoint closest to the given hash.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectOutboundExploratoryTunnel(Hash closestTo);
    
    /**
     * Pick the outbound tunnel with the endpoint closest to the given hash
     * from the given destination's pool.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param destination if null, returns outbound exploratory tunnel
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectOutboundTunnel(Hash destination, Hash closestTo);

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
    /** how many outbound client tunnels in this pool? */
    public int getOutboundClientTunnelCount(Hash destination);
    public double getShareRatio();
    
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

    /**
     *  Add another destination to the same tunnels.
     *  Must have same encryption key and a different signing key.
     *  @throws IllegalArgumentException if not
     *  @return success
     *  @since 0.9.21
     */
    public boolean addAlias(Destination dest, ClientTunnelSettings settings, Destination existingClient);

    /**
     *  Remove another destination to the same tunnels.
     *  @since 0.9.21
     */
    public void removeAlias(Destination dest);
    
    public TunnelPoolSettings getInboundSettings();
    public TunnelPoolSettings getOutboundSettings();
    public TunnelPoolSettings getInboundSettings(Hash client);
    public TunnelPoolSettings getOutboundSettings(Hash client);
    public void setInboundSettings(TunnelPoolSettings settings);
    public void setOutboundSettings(TunnelPoolSettings settings);
    public void setInboundSettings(Hash client, TunnelPoolSettings settings);
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings);
    /** for TunnelRenderer in router console */
    public void listPools(List<TunnelPool> out);
    /** for TunnelRenderer in router console */
    public Map<Hash, TunnelPool> getInboundClientPools();
    /** for TunnelRenderer in router console */
    public Map<Hash, TunnelPool> getOutboundClientPools();
    /** for TunnelRenderer in router console */
    public TunnelPool getInboundExploratoryPool();
    /** for TunnelRenderer in router console */
    public TunnelPool getOutboundExploratoryPool();

    /**
     *  @return pool or null
     *  @since 0.9.34
     */
    public TunnelPool getInboundPool(Hash client);

    /**
     *  @return pool or null
     *  @since 0.9.34
     */
    public TunnelPool getOutboundPool(Hash client);

    /** @since 0.8.13 */
    public void fail(Hash peer);
}
