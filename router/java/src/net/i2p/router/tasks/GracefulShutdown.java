package net.i2p.router.tasks;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Simple thread that sits and waits forever, managing the
 * graceful shutdown "process" (describing it would take more text
 * than just reading the code...)
 *
 *  @since 0.8.12 moved from Router
 */
public class GracefulShutdown implements Runnable {
    private final RouterContext _context;

    public GracefulShutdown(RouterContext ctx) {
        _context = ctx;
    }

    public void run() {
        Log log = _context.logManager().getLog(Router.class);
        while (true) {
            boolean shutdown = _context.router().gracefulShutdownInProgress();
            if (shutdown) {
                int gracefulExitCode = _context.router().scheduledGracefulExitCode();
                if (gracefulExitCode == Router.EXIT_HARD || gracefulExitCode == Router.EXIT_HARD_RESTART ||
                    _context.tunnelManager().getParticipatingCount() <= 0) {
                    if (gracefulExitCode == Router.EXIT_HARD)
                        log.log(Log.CRIT, "Shutting down after a brief delay");
                    else if (gracefulExitCode == Router.EXIT_HARD_RESTART)
                        log.log(Log.CRIT, "Restarting after a brief delay");
                    else
                        log.log(Log.CRIT, "Graceful shutdown progress - no more tunnels, safe to die");
                    // Allow time for a UI reponse
                    try {
                        synchronized (Thread.currentThread()) {
                            Thread.currentThread().wait(2*1000);
                        }
                    } catch (InterruptedException ie) {}
                    _context.router().shutdown(gracefulExitCode);
                    return;
                } else {
                    try {
                        synchronized (Thread.currentThread()) {
                            Thread.currentThread().wait(10*1000);
                        }
                    } catch (InterruptedException ie) {}
                }
            } else {
                try {
                    synchronized (Thread.currentThread()) {
                        Thread.currentThread().wait();
                    }
                } catch (InterruptedException ie) {}
            }
        }
    }
}

