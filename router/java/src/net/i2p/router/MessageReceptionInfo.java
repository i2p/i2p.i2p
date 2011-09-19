package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;

/**
 * Wrap up the details of how a ClientMessage was received from the network
 *
 * @deprecated unused
 */
public class MessageReceptionInfo {
    private Hash _fromPeer;
    private TunnelId _fromTunnel;
    
    public MessageReceptionInfo() {
	setFromPeer(null);
	setFromTunnel(null);
    }
    
    /** Hash of the RouterIdentity of the peer that sent the message */
    public Hash getFromPeer() { return _fromPeer; }
    public void setFromPeer(Hash routerIdentityHash) { _fromPeer = routerIdentityHash; }
    /** TunnelId the message came in on, if applicable */
    public TunnelId getFromTunnel() { return _fromTunnel; }
    public void setFromTunnel(TunnelId fromTunnel) { _fromTunnel = fromTunnel; }
}
