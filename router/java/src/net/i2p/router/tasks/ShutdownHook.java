package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Just for failsafe. Standard shutdown should cancel this.
 *
 *  @since 0.8.12 moved from Router.java
 */
public class ShutdownHook extends Thread {
    private final RouterContext _context;
    private static int __id = 0;
    private final int _id;

    public ShutdownHook(RouterContext ctx) {
        _context = ctx;
        _id = ++__id;
    }

    @Override
    public void run() {
        setName("Router " + _id + " shutdown");
        Log l = _context.logManager().getLog(Router.class);
        l.log(Log.CRIT, "Shutting down the router...");
        // Needed to make the wrapper happy, otherwise it gets confused
        // and thinks we haven't shut down, possibly because it
        // prevents other shutdown hooks from running
        _context.router().setKillVMOnEnd(false);
        _context.router().shutdown2(Router.EXIT_HARD);
    }
}
