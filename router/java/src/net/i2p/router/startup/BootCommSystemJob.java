package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

public class BootCommSystemJob extends JobImpl {
    private Log _log;
    
    public static final String PROP_USE_TRUSTED_LINKS = "router.trustedLinks";
    
    public BootCommSystemJob(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(BootCommSystemJob.class);
    }
    
    public String getName() { return "Boot Communication System"; }
    
    public void runJob() {
        // start up the network comm system
        
        getContext().commSystem().startup();
        getContext().tunnelManager().startup();
        getContext().peerManager().startup();
        
        Job bootDb = new BootNetworkDbJob(getContext());
        boolean useTrusted = false;
        String useTrustedStr = getContext().router().getConfigSetting(PROP_USE_TRUSTED_LINKS);
        if (useTrustedStr != null) {
            useTrusted = Boolean.TRUE.toString().equalsIgnoreCase(useTrustedStr);
        }
        if (useTrusted) {
            _log.debug("Using trusted links...");
            getContext().jobQueue().addJob(new BuildTrustedLinksJob(getContext(), bootDb));
            return;
        } else {
            _log.debug("Not using trusted links - boot db");
            getContext().jobQueue().addJob(bootDb);
        }
    }
}
