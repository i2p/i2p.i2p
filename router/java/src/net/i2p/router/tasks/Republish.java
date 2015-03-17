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
import net.i2p.util.SimpleTimer;
import net.i2p.util.Log;

/**
 * Periodically publish our RouterInfo to the netdb
 *
 * @since 0.8.12 moved from Router.java
 */
public class Republish implements SimpleTimer.TimedEvent {
    private final RouterContext _context;

    public Republish(RouterContext ctx) {
        _context = ctx;
    }

    public void timeReached() {
        try {
            _context.netDb().publish(_context.router().getRouterInfo());
        } catch (IllegalArgumentException iae) {
            Log log = _context.logManager().getLog(Router.class);
            log.log(Log.CRIT, "Local router info is invalid?  rebuilding a new identity", iae);
            _context.router().rebuildNewIdentity();
        }
    }
}

