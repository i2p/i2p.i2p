package net.i2p.router.web;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.app.ClientAppManager;
import net.i2p.app.NotificationService;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 *  Periodic check
 *  ref: https://dzone.com/articles/how-detect-java-deadlocks
 *
 *  In routerconsole because java.lang.management is
 *  not available in Android.
 *
 *  @since 0.9.55, public since 0.9.56
 */
public class DeadlockDetector extends SimpleTimer2.TimedEvent {

    private final RouterContext _context;
    private final Log _log;
    private static final String PROP_INTERVAL = "router.deadlockDetectIntervalHours";
    private static final long DEFAULT_INTERVAL = SystemVersion.isSlow() ? 12 : 4;
    private static final AtomicBoolean _isDeadlocked = new AtomicBoolean();

    public DeadlockDetector(RouterContext ctx) {
        super(ctx.simpleTimer2());
        _context = ctx;
        _log = _context.logManager().getLog(DeadlockDetector.class);
        long interval = getInterval();
        if (interval > 0)
            schedule(interval);
    }

    private long getInterval() {
        long rv = _context.getProperty(PROP_INTERVAL, DEFAULT_INTERVAL);
        return rv * 60*60*1000L;
    }

    public void timeReached() {
        long start = System.currentTimeMillis();
        boolean detected = detect();
        // only reschedule if not detected
        if (!detected) {
            long time = System.currentTimeMillis() - start;
            if (_log.shouldDebug())
                _log.debug("No deadlocks detected, took " + time + "ms");
            long interval = getInterval();
            if (interval > 0)
                schedule(interval);
        }
    }

    private boolean detect() {
        return detect(_context);
    }

    public static boolean detect(RouterContext ctx) {
        if (_isDeadlocked.get())
            return true;
        try {
            ThreadMXBean mxb = ManagementFactory.getThreadMXBean();
            long[] ids = mxb.findDeadlockedThreads();
            if (ids == null)
                return false;
            ThreadInfo[] infos;
            try {
                // java 10
                //infos = mxb.getThreadInfo(ids, true, true, Integer.MAX_VALUE);
                // java 6
                infos = mxb.getThreadInfo(ids, true, true);
            } catch (UnsupportedOperationException e) {
                // won't throw
                infos = mxb.getThreadInfo(ids, Integer.MAX_VALUE);
            }
            StringBuilder buf = new StringBuilder(2048);
            String msg1 = Messages.getString("Deadlock detected", ctx) + " - " + Messages.getString("Please report", ctx);
            buf.append(msg1).append("\n\n");
            for (int i = 0; i < infos.length; i++) {
                ThreadInfo info = infos[i];
                if (info == null)
                    continue;
                buf.append("Thread ").append(i).append(':');
                buf.append(info.toString());
                StackTraceElement[] stes = info.getStackTrace();
                buf.append("        Stack Trace:\n");
                for (StackTraceElement ste : stes) {
                    buf.append("        at ").append(ste.toString()).append('\n');
                }
                buf.append('\n');
            }
            String msg2 = Messages.getString("After reporting, please restart your router", ctx);
            buf.append('\n').append(msg2).append('\n');
            Log log = ctx.logManager().getLog(DeadlockDetector.class);
            log.log(Log.CRIT, buf.toString());
            ctx.router().eventLog().addEvent(EventLog.DEADLOCK, infos.length + " threads");
            _isDeadlocked.set(true);
            ClientAppManager cmgr = ctx.clientAppManager();
            if (cmgr != null) {
                NotificationService ns = (NotificationService) cmgr.getRegisteredApp("desktopgui");
                if (ns != null) {
                    ns.notify("Router", null, Log.CRIT, Messages.getString("Router", ctx), 
                              msg1 + '\n' + msg2, null);
                }
            }
        } catch (Throwable t) {
            // class not found, unsupportedoperation, ...
            Log log = ctx.logManager().getLog(DeadlockDetector.class);
            log.warn("fail", t);
            return false;
        }
        return true;
    }

    /**
     *  Return the results of the last test. Does not run a new test.
     *
     *  @since 0.9.56
     */
    public static boolean isDeadlocked() {
        return _isDeadlocked.get();
    }

/*
    public static void main(String[] args) {
        final Object o1 = new Object();
        final Object o2 = new Object();
        Thread t1 = new Thread(new Runnable() {
            public void run() {
                synchronized(o1) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    // should hang here
                    synchronized(o2) {
                        System.out.println("Test fail");
                    }
                }
            }
        });
        t1.start();
        Thread t2 = new Thread(new Runnable() {
            public void run() {
                synchronized(o2) {
                    // should hang here
                    synchronized(o1) {
                        System.out.println("Test fail");
                    }
                }
            }
        });
        t2.start();
        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
        long start = System.currentTimeMillis();
        boolean yes = detect(I2PAppContext.getGlobalContext());
        if (!yes)
            System.out.println("Test fail");
        long time = System.currentTimeMillis() - start;
        System.out.println("Test took " + time + "ms");
    }
*/
}

