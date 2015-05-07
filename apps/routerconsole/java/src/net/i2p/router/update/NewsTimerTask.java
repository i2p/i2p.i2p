package net.i2p.router.update;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.NewsHelper;
import static net.i2p.update.UpdateType.*;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Task to periodically look for updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 * Also looks for unsigned updates.
 *
 * Runs forever on instantiation, can't be stopped.
 *
 * @since 0.9.4 moved from NewsFetcher
 */
class NewsTimerTask implements SimpleTimer.TimedEvent {
    private final RouterContext _context;
    private final Log _log;
    private final ConsoleUpdateManager _mgr;
    private volatile boolean _firstRun = true;

    private static final long INITIAL_DELAY = 5*60*1000;
    private static final long NEW_INSTALL_DELAY = 25*60*1000;
    private static final long RUN_DELAY = 10*60*1000;

    public NewsTimerTask(RouterContext ctx, ConsoleUpdateManager mgr) {
        _context = ctx;
        _log = ctx.logManager().getLog(NewsTimerTask.class);
        _mgr = mgr;
        long installed = ctx.getProperty("router.firstInstalled", 0L);
        boolean isNew = (ctx.clock().now() - installed) < 30*60*1000L;
        long delay = isNew ? NEW_INSTALL_DELAY : INITIAL_DELAY;
        delay += _context.random().nextLong(INITIAL_DELAY);
        if (_log.shouldLog(Log.INFO))
            _log.info("Scheduling first news check in " + DataHelper.formatDuration(delay));
        ctx.simpleTimer2().addPeriodicEvent(this, delay, RUN_DELAY);
        // UpdateManager calls NewsFetcher to check the existing news at startup
    }

    public void timeReached() {
        if (shouldFetchNews()) {
            Thread t = new Fetcher();
            t.start();
        } else if (_firstRun) {
            // This covers the case where we got a new news but then shut down before it
            // was successfully downloaded, and then restarted within the 36 hour delay
            // before fetching news again.
            // If we already know about a new version (from ConsoleUpdateManager calling
            // NewsFetcher.checkForUpdates() before any Updaters were registered),
            // this will fire off an update.
            // If disabled this does nothing.
            // TODO unsigned too?
            if (_mgr.shouldInstall() &&
                !_mgr.isCheckInProgress() && !_mgr.isUpdateInProgress())
                // non-blocking
                _mgr.update(ROUTER_SIGNED);
        }
        _firstRun = false;
    }
    
    private boolean shouldFetchNews() {
        if (_context.router().gracefulShutdownInProgress())
            return false;
        if (_mgr.isCheckInProgress() || _mgr.isUpdateInProgress())
            return false;
        long lastFetch = NewsHelper.lastChecked(_context);
        String freq = _context.getProperty(ConfigUpdateHandler.PROP_REFRESH_FREQUENCY,
                                           ConfigUpdateHandler.DEFAULT_REFRESH_FREQUENCY);
        try {
            long ms = Long.parseLong(freq);
            if (ms <= 0)
                return false;
            
            if (lastFetch + ms < _context.clock().now()) {
                return true;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Last fetched " + DataHelper.formatDuration(_context.clock().now() - lastFetch) + " ago");
                return false;
            }
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid refresh frequency: " + freq);
            return false;
        }
    }

    /** blocking */
    private void fetchNews() {
        _mgr.checkAvailable(NEWS, 60*1000);
    }
    
    private boolean shouldFetchUnsigned() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL);
        return url != null && url.length() > 0 &&
               _context.getBooleanProperty(ConfigUpdateHandler.PROP_UPDATE_UNSIGNED) &&
               !NewsHelper.dontInstall(_context);
    }
    
    /** @since 0.9.20 */
    private boolean shouldFetchDevSU3() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_DEV_SU3_URL);
        return url != null && url.length() > 0 &&
               _context.getBooleanProperty(ConfigUpdateHandler.PROP_UPDATE_DEV_SU3) &&
               !NewsHelper.dontInstall(_context);
    }

    /**
     * Don't clog the scheduler when fetching the news
     *
     * @since 0.9.9
     */
    private class Fetcher extends I2PAppThread {
        public Fetcher() {
            super("News Fetcher");
            setDaemon(true);
        }

        public void run() {
            // blocking
            fetchNews();
            if (shouldFetchDevSU3()) {
                // give it a sec for the download to kick in, if it's going to
                try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
                if (!_mgr.isCheckInProgress() && !_mgr.isUpdateInProgress())
                    // nonblocking
                    _mgr.check(ROUTER_DEV_SU3);
            }
            if (shouldFetchUnsigned()) {
                // give it a sec for the download to kick in, if it's going to
                try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
                if (!_mgr.isCheckInProgress() && !_mgr.isUpdateInProgress())
                    // nonblocking
                    _mgr.check(ROUTER_UNSIGNED);
            }
        }
    }
}
