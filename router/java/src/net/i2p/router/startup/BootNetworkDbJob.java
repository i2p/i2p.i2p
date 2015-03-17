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

/** start up the network database */
public class BootNetworkDbJob extends JobImpl {
    
    public BootNetworkDbJob(RouterContext ctx) {
        super(ctx);
    }
    
    public String getName() { return "Boot Network Database"; }
    
    public void runJob() {
        getContext().netDb().startup();
    }
}
