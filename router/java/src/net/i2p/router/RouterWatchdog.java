package net.i2p.router;

import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.ShellCommand;
import net.i2p.util.Log;

/**
 * Periodically check to make sure things haven't gone totally haywire (and if
 * they have, restart the JVM)
 *
 */
class RouterWatchdog implements Runnable {
    private final Log _log;
    private final RouterContext _context;
    private int _consecutiveErrors;
    
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
        // prop default false
        if (!Boolean.valueOf(_context.getProperty("watchdog.haltOnHang")).booleanValue())
            return false;

        // Client manager starts complaining after 10 minutes, and we run every minute,
        // so this will restart 30 minutes after we lose a lease, if the wrapper is present.
        if (_consecutiveErrors >= 20 && System.getProperty("wrapper.version") != null)
            return true;
        return false;
    }
    
    private void dumpStatus() {
        if (_log.shouldLog(Log.ERROR)) {
            /*
            Job cur = _context.jobQueue().getLastJob();
            if (cur != null) 
                _log.error("Most recent job: " + cur);
            _log.error("Last job began: " 
                       + DataHelper.formatDuration(_context.clock().now()-_context.jobQueue().getLastJobBegin())
                       + " ago");
            _log.error("Last job ended: " 
                       + DataHelper.formatDuration(_context.clock().now()-_context.jobQueue().getLastJobEnd())
                       + " ago");
            */
            _log.error("Ready and waiting jobs: " + _context.jobQueue().getReadyCount());
            _log.error("Job lag: " + _context.jobQueue().getMaxLag());
            _log.error("Participating tunnel count: " + _context.tunnelManager().getParticipatingCount());
            
            RateStat rs = _context.statManager().getRate("transport.sendProcessingTime");
            Rate r = null;
            if (rs != null)
                r = rs.getRate(60*1000);
            double processTime = (r != null ? r.getAverageValue() : 0);
            _log.error("1minute send processing time: " + processTime);
            
            rs = _context.statManager().getRate("bw.sendBps");
            r = null;
            if (rs != null)
                r = rs.getRate(60*1000);
            double bps = (r != null ? r.getAverageValue() : 0);
            _log.error("Outbound send rate: " + bps + " Bps");
            long max = Runtime.getRuntime().maxMemory();
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            _log.error("Memory: " + DataHelper.formatSize(used) + '/' + DataHelper.formatSize(max));
            if (_consecutiveErrors == 1) {
                _log.log(Log.CRIT, "Router appears hung, or there is severe network congestion.  Watchdog starts barking!");
                // This works on linux...
                // It won't on windows, and we can't call i2prouter.bat either, it does something
                // completely different...
                if (System.getProperty("wrapper.version") != null && !System.getProperty("os.name").startsWith("Win")) {
                    ShellCommand sc = new ShellCommand();
                    boolean success = sc.executeSilentAndWaitTimed("./i2prouter dump", 10);
                    if (success)
                        _log.log(Log.CRIT, "Threads dumped to wrapper log");
                }
            }
        }
    }
    
    public void run() {
        while (true) {
            try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
            monitorRouter();
        }
    }
    
    public void monitorRouter() {
        boolean ok = verifyJobQueueLiveliness();
        // If we aren't connected to the network that's why there's nobody to talk to
        long netErrors = 0;
        RateStat rs = _context.statManager().getRate("udp.sendException");
        if (rs != null) {
            Rate r = rs.getRate(60*1000);
            if (r != null)
                netErrors = r.getLastEventCount();
        }

        ok = ok && (verifyClientLiveliness() || netErrors >= 5);
        
        if (ok) {
            _consecutiveErrors = 0;
        } else {
            _consecutiveErrors++;
            dumpStatus();
            if (shutdownOnHang()) {
                _log.log(Log.CRIT, "Router hung!  Restart forced by watchdog!");
                try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
                // halt and not system.exit, since some of the shutdown hooks might be misbehaving
                Runtime.getRuntime().halt(Router.EXIT_HARD_RESTART);
            }
        }
    }
}
