package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Update the routing Key modifier every day at midnight (plus on startup).
 * This is done here because we want to make sure the key is updated before anyone
 * uses it.
 *
 * @since 0.8.12 moved from Router.java
 */
public class UpdateRoutingKeyModifierJob extends JobImpl {
    private final Log _log;
    // Run every 15 minutes in case of time zone change, clock skew, etc.
    private static final long MAX_DELAY_FAILSAFE = 15*60*1000;

    public UpdateRoutingKeyModifierJob(RouterContext ctx) { 
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
    }

    public String getName() { return "Update Routing Key Modifier"; }

    public void runJob() {
        RouterKeyGenerator gen = getContext().routerKeyGenerator();
        // make sure we requeue quickly if just before midnight
        long delay = Math.max(5, Math.min(MAX_DELAY_FAILSAFE, gen.getTimeTillMidnight()));
        // TODO tell netdb if mod data changed?
        gen.generateDateBasedModData();
        requeue(delay);
    }
}
