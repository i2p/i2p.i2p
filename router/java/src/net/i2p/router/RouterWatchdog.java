package net.i2p.router;

import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
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
        return Boolean.valueOf(_context.getProperty("watchdog.haltOnHang", "true")).booleanValue();
    }
    
    private void dumpStatus() {
        if (_log.shouldLog(Log.ERROR)) {
            Job cur = _context.jobQueue().getLastJob();
            if (cur != null) 
                _log.error("Most recent job: " + cur);
            _log.error("Last job began: " 
                       + DataHelper.formatDuration(_context.clock().now()-_context.jobQueue().getLastJobBegin())
                       + " ago");
            _log.error("Last job ended: " 
                       + DataHelper.formatDuration(_context.clock().now()-_context.jobQueue().getLastJobEnd())
                       + " ago");
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
            double kbps = (r != null ? r.getAverageValue() : 0);
            _log.error("Outbound send rate: " + kbps + "KBps");
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
        ok = ok && verifyClientLiveliness();
        
        if (!ok) {
            dumpStatus();
            if (shutdownOnHang()) {
                _log.log(Log.CRIT, "Router hung!  hard restart!");
                try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
                // halt and not system.exit, since some of the shutdown hooks might be misbehaving
                Runtime.getRuntime().halt(Router.EXIT_HARD_RESTART);
            }
        }
    }
}
