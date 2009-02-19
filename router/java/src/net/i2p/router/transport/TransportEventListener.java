package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessage;

public interface TransportEventListener {
    public void messageReceived(I2NPMessage message, RouterIdentity fromRouter, Hash fromRouterHash);
    public void transportAddressChanged();
}
