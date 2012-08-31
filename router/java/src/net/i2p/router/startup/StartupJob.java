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

/**
 * The StartupJob should be run once on router startup to initialize the system
 * and set things in motion.  This task loads the router configuration and then
 * queues up a LoadRouterInfoJob, which reads the old RouterInfo structure from
 * a previously saved version on disk.  If it can't find one, it fires up a 
 * CreateRouterInfoJob which builds a new one from scratch, including a new 
 * RouterIdentity and then reruns the LoadRouterInfoJob.  After that the 
 * router begins listening on its ports by running the BootCommSystemJob which
 * is followed by the BootNetworkDbJob, though BuildTrustedLinksJob may occur
 * as well.  After running the BootNetworkDbJob, the final 
 * StartAcceptingClientsJob is queued up, which finishes the startup.
 *
 */
public class StartupJob extends JobImpl {
    
    public StartupJob(RouterContext context) {
        super(context);
    }

    public String getName() { return "Startup Router"; }
    public void runJob() {
        if (!System.getProperty("java.vendor").contains("Android"))
            getContext().jobQueue().addJob(new LoadClientAppsJob(getContext()));
        getContext().statPublisher().startup();
        getContext().jobQueue().addJob(new LoadRouterInfoJob(getContext()));
    }
}
