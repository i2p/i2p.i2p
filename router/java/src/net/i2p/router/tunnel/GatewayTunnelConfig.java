package net.i2p.router.tunnel;

import net.i2p.data.SessionKey;

/**
 * Coordinate the data that the gateway to a tunnel needs to know
 *
 */
public class GatewayTunnelConfig {
    /** the key for the first hop after the gateway is in _keys[0] */
    private SessionKey _keys[];
    
    /** Creates a new instance of TunnelConfig */
    public GatewayTunnelConfig() {
        _keys = new SessionKey[GatewayMessage.HOPS];
    }
    
    /** What is the session key for the given hop? */
    public SessionKey getSessionKey(int layer) { return _keys[layer]; }
    public void setSessionKey(int layer, SessionKey key) { _keys[layer] = key; }
}
