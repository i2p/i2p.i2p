package net.i2p.router.tasks;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 *  Kaboom
 *
 *  @since 0.8.12 moved from Router.java
 */
public class OOMListener implements I2PThread.OOMEventListener { 
    private final RouterContext _context;

    public OOMListener(RouterContext ctx) {
        _context = ctx;
    }

    public void outOfMemory(OutOfMemoryError oom) { 
        Router.clearCaches();
        Log log = _context.logManager().getLog(Router.class);
        log.log(Log.CRIT, "Thread ran out of memory, shutting down I2P", oom);
        // prevent multiple parallel shutdowns (when you OOM, you OOM a lot...)
        if (_context.router().isFinalShutdownInProgress())
            return;
        for (int i = 0; i < 5; i++) { // try this 5 times, in case it OOMs
            try { 
                log.log(Log.CRIT, "free mem: " + Runtime.getRuntime().freeMemory() + 
                                  " total mem: " + Runtime.getRuntime().totalMemory());
                break; // w00t
            } catch (OutOfMemoryError oome) {
                // gobble
            }
        }
        log.log(Log.CRIT, "To prevent future shutdowns, increase wrapper.java.maxmemory in $I2P/wrapper.config");
        _context.router().shutdown(Router.EXIT_OOM); 
    }
}
