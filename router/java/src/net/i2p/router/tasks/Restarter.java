package net.i2p.router.tasks;

import net.i2p.router.Router;
import net.i2p.router.RouterClock;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  @since 0.8.8, moved from Router in 0.8.12
 */
public class Restarter implements Runnable {
    private final RouterContext _context;

    public Restarter(RouterContext ctx) {
        _context = ctx;
    }

    public void run() {
        Log log = _context.logManager().getLog(Router.class);
        log.error("Stopping the router for a restart...");
        log.logAlways(Log.WARN, "Stopping the client manager");
        // NOTE: DisconnectMessageHandler keys off "restart"
        try { _context.clientManager().shutdown("Router restart"); } catch (Throwable t) { log.log(Log.CRIT, "Error stopping the client manager", t); }
        log.logAlways(Log.WARN, "Stopping the comm system");
        _context.bandwidthLimiter().reinitialize();
        try { _context.messageRegistry().restart(); } catch (Throwable t) { log.log(Log.CRIT, "Error restarting the message registry", t); }
        try { _context.commSystem().restart(); } catch (Throwable t) { log.log(Log.CRIT, "Error restarting the comm system", t); }
        log.logAlways(Log.WARN, "Stopping the tunnel manager");
        try { _context.tunnelManager().restart(); } catch (Throwable t) { log.log(Log.CRIT, "Error restarting the tunnel manager", t); }

        //try { _context.peerManager().restart(); } catch (Throwable t) { log.log(Log.CRIT, "Error restarting the peer manager", t); }
        //try { _context.netDb().restart(); } catch (Throwable t) { log.log(Log.CRIT, "Error restarting the networkDb", t); }
        //try { _context.jobQueue().restart(); } catch (Throwable t) { log.log(Log.CRIT, "Error restarting the job queue", t); }
    
        log.logAlways(Log.WARN, "Router teardown complete, restarting the router...");
        try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
    
        log.logAlways(Log.WARN, "Restarting the comm system");
        log.logAlways(Log.WARN, "Restarting the tunnel manager");
        log.logAlways(Log.WARN, "Restarting the client manager");
        try { _context.clientMessagePool().restart(); } catch (Throwable t) { log.log(Log.CRIT, "Error restarting the CMP", t); }
        try { _context.clientManager().startup(); } catch (Throwable t) { log.log(Log.CRIT, "Error starting the client manager", t); }
    
        _context.router().setIsAlive();
        _context.router().rebuildRouterInfo();
    
        log.logAlways(Log.WARN, "Restart complete");
        ((RouterClock) _context.clock()).addShiftListener(_context.router());
    }
}

