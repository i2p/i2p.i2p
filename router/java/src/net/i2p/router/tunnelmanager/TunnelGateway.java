package net.i2p.router.tunnelmanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.TunnelId;
import net.i2p.data.Hash;

class TunnelGateway {
    private TunnelId _tunnel;
    private Hash _gateway;
    public TunnelGateway(TunnelId id, Hash gateway) { 
	_tunnel = id;
	_gateway = gateway;
    }
    public TunnelId getTunnelId() { return _tunnel; }
    public Hash getGateway() { return _gateway; }
}
