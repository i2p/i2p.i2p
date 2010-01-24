package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/** start up the peer manager */
public class BootPeerManagerJob extends JobImpl {
    
    public BootPeerManagerJob(RouterContext ctx) {
        super(ctx);
    }
    
    public String getName() { return "Boot Peer Manager"; }
    
    public void runJob() {
        getContext().peerManager().startup();
    }
}
