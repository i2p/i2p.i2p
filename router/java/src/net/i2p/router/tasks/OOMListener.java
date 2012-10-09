package net.i2p.router.tasks;

import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 *  Kaboom
 *
 *  @since 0.8.12 moved from Router.java
 */
public class OOMListener implements I2PThread.OOMEventListener { 
    private final RouterContext _context;
    private final AtomicBoolean _wasCalled = new AtomicBoolean();

    public OOMListener(RouterContext ctx) {
        _context = ctx;
    }

    public void outOfMemory(OutOfMemoryError oom) { 
        try { 
            // prevent multiple parallel shutdowns (when you OOM, you OOM a lot...)
            if (_context.router().isFinalShutdownInProgress())
                return;
        } catch (OutOfMemoryError oome) {}
        try { 
            // Only do this once
            if (_wasCalled.getAndSet(true))
                return;
        } catch (OutOfMemoryError oome) {}

        try { 
            // boost priority to help us shut down
            // this may or may not do anything...
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 1);
        } catch (OutOfMemoryError oome) {}
        try { 
            Router.clearCaches();
        } catch (OutOfMemoryError oome) {}
        Log log = null;
        try { 
            log = _context.logManager().getLog(Router.class);
            log.log(Log.CRIT, "Thread ran out of memory, shutting down I2P", oom);
            log.log(Log.CRIT, "free mem: " + Runtime.getRuntime().freeMemory() + 
                              " total mem: " + Runtime.getRuntime().totalMemory());
            if (_context.hasWrapper())
                log.log(Log.CRIT, "To prevent future shutdowns, increase wrapper.java.maxmemory in $I2P/wrapper.config");
        } catch (OutOfMemoryError oome) {}
        try { 
            ThreadDump.dump(_context, 1);
        } catch (OutOfMemoryError oome) {}
        try { 
            _context.router().eventLog().addEvent(EventLog.OOM);
        } catch (OutOfMemoryError oome) {}
        _context.router().shutdown(Router.EXIT_OOM); 
    }
}
