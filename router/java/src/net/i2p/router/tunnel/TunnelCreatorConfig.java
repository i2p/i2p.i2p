package net.i2p.router.tunnel;

import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 * Coordinate the info that the tunnel creator keeps track of, including what 
 * peers are in the tunnel and what their configuration is
 *
 */
public class TunnelCreatorConfig {
    /** only necessary for client tunnels */
    private Destination _destination;
    /** gateway first */
    private HopConfig _config[];
    /** gateway first */
    private Hash _peers[];
    private boolean _isInbound;
    
    public TunnelCreatorConfig(int length, boolean isInbound) {
        this(length, isInbound, null);
    }
    public TunnelCreatorConfig(int length, boolean isInbound, Destination destination) {
        _config = new HopConfig[length];
        _peers = new Hash[length];
        for (int i = 0; i < length; i++) {
            _config[i] = new HopConfig();
        }
        _isInbound = isInbound;
        _destination = destination;
    }
    
    /** how many hops are there in the tunnel? */
    public int getLength() { return _config.length; }
    
    /** 
     * retrieve the config for the given hop.  the gateway is
     * hop 0.
     */
    public HopConfig getConfig(int hop) { return _config[hop]; }
    
    /** retrieve the peer at the given hop.  the gateway is hop 0 */
    public Hash getPeer(int hop) { return _peers[hop]; }
    public void setPeer(int hop, Hash peer) { _peers[hop] = peer; }
    
    /** is this an inbound tunnel? */
    public boolean isInbound() { return _isInbound; }

    /** if this is a client tunnel, what destination is it for? */
    public Destination getDestination() { return _destination; }
}
