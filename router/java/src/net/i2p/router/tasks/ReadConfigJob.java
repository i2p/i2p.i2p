package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Simply read the router config periodically,
 * so that the user may make config changes externally.
 * This isn't advertised as a feature,
 * but it could be used, for example, to change bandwidth limits
 * at certain times of day.
 *
 * Unfortunately it will also read the file back in every time the
 * router writes it.
 *
 * We must keep this enabled, as it's the only way for people
 * to set routerconsole.advanced=true without restarting.
 */
public class ReadConfigJob extends JobImpl {
    private final static long DELAY = 30*1000; // reread every 30 seconds
    private volatile long _lastRead;

    public ReadConfigJob(RouterContext ctx) {
        super(ctx);
        _lastRead = ctx.clock().now();
    }
    
    public String getName() { return "Read Router Configuration"; }

    public void runJob() {
        File configFile = new File(getContext().router().getConfigFilename());
        if (shouldReread(configFile)) {
            getContext().router().readConfig();
            _lastRead = getContext().clock().now();
            Log log = getContext().logManager().getLog(ReadConfigJob.class);
            if (log.shouldDebug())
                log.debug("Reloaded " + configFile);
        }
        requeue(DELAY);
    }
    
    private boolean shouldReread(File configFile) {
        // lastModified() returns 0 if not found
        //if (!configFile.exists()) return false;
        return configFile.lastModified() > _lastRead;
    }
}
