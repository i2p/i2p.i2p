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
import net.i2p.router.RouterClock;
import net.i2p.util.Log;

/** This actually boots almost everything */
public class BootCommSystemJob extends JobImpl {
    private Log _log;
    
    public static final String PROP_USE_TRUSTED_LINKS = "router.trustedLinks";
    
    public BootCommSystemJob(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(BootCommSystemJob.class);
    }
    
    public String getName() { return "Boot Communication System"; }
    
    public void runJob() {
        // The netDb and the peer manager both take a long time to start up,
        // as they may have to read in ~1000 files or more each
        // So turn on the multiple job queues and start these two first.
        // These two (plus the current job) will consume 3 of the 4 runners,
        // leaving one for everything else, which allows us to start without
        // a huge job lag displayed on the console.
        getContext().jobQueue().allowParallelOperation();
        startupDb();
        getContext().jobQueue().addJob(new BootPeerManagerJob(getContext()));

        // start up the network comm system
        getContext().commSystem().startup();
        getContext().tunnelManager().startup();

        // start I2CP
        getContext().jobQueue().addJob(new StartAcceptingClientsJob(getContext()));

        getContext().jobQueue().addJob(new ReadConfigJob(getContext()));
        ((RouterClock) getContext().clock()).addShiftListener(getContext().router());
    }
        
    private void startupDb() {
        Job bootDb = new BootNetworkDbJob(getContext());
        boolean useTrusted = Boolean.valueOf(getContext().getProperty(PROP_USE_TRUSTED_LINKS)).booleanValue();
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
