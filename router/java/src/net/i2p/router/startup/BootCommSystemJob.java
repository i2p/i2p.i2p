package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.CommSystemFacade;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.PeerManagerFacade;
import net.i2p.router.Router;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.util.Log;

public class BootCommSystemJob extends JobImpl {
    private static Log _log = new Log(BootCommSystemJob.class);
    
    public static final String PROP_USE_TRUSTED_LINKS = "router.trustedLinks";
    
    public BootCommSystemJob() { }
    
    public String getName() { return "Boot Communication System"; }
    
    public void runJob() {
	// start up the network comm system
	
	CommSystemFacade.getInstance().startup();
	TunnelManagerFacade.getInstance().startup();
	PeerManagerFacade.getInstance().startup();
	
	Job bootDb = new BootNetworkDbJob();
	boolean useTrusted = false;
	String useTrustedStr = Router.getInstance().getConfigSetting(PROP_USE_TRUSTED_LINKS);
	if (useTrustedStr != null) {
	    useTrusted = Boolean.TRUE.toString().equalsIgnoreCase(useTrustedStr);
	}
	if (useTrusted) {
	    _log.debug("Using trusted links...");
	    JobQueue.getInstance().addJob(new BuildTrustedLinksJob(bootDb)); 
	    return;
	} else {
	    _log.debug("Not using trusted links - boot db");
	    JobQueue.getInstance().addJob(bootDb);
	}
    }
}
