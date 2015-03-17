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

/** start I2CP interface */
public class StartAcceptingClientsJob extends JobImpl {
    
    public StartAcceptingClientsJob(RouterContext context) {
        super(context);
    }
    
    public String getName() { return "Start Accepting Clients"; }
    
    public void runJob() {

        getContext().clientManager().startup();

        // pointless
        //getContext().jobQueue().addJob(new RebuildRouterInfoJob(getContext()));
    }
}
