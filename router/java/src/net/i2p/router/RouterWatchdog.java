package net.i2p.router;

import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Periodically check to make sure things haven't gone totally haywire (and if
 * they have, restart the JVM)
 *
 */
class RouterWatchdog implements Runnable {
    private Log _log;
    private RouterContext _context;
    
    private static final long MAX_JOB_RUN_LAG = 60*1000;
    
    public RouterWatchdog(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(RouterWatchdog.class);
    }
    
    public boolean verifyJobQueueLiveliness() {
        long when = _context.jobQueue().getLastJobBegin();
        if (when < 0) 
            return true;
        long howLongAgo = _context.clock().now() - when;
        if (howLongAgo > MAX_JOB_RUN_LAG) {
            Job cur = _context.jobQueue().getLastJob();
            if (cur != null) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Last job was queued up " + DataHelper.formatDuration(howLongAgo)
                               + " ago: " + cur);
                return false;
            } else {
                // no prob, just normal lag
                return true;
            }
        } else {
            return true;
        }
    }
    
    public boolean verifyClientLiveliness() {
        return _context.clientManager().verifyClientLiveliness();
    }
    
    private boolean shutdownOnHang() {
        return true;
    }
    
    public void run() {
        while (true) {
            try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
            monitorRouter();
        }
    }
    
    public void monitorRouter() {
        boolean ok = verifyJobQueueLiveliness();
        ok = ok && verifyClientLiveliness();
        
        if (!ok && shutdownOnHang()) {
            _log.log(Log.CRIT, "Router hung!  hard restart!");
            System.exit(Router.EXIT_HARD_RESTART);
        }
    }
}
