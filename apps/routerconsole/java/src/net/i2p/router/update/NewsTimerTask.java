package net.i2p.router.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.ConfigUpdateHelper;
import net.i2p.router.web.NewsHelper;
import static net.i2p.update.UpdateType.*;
import net.i2p.util.EepGet;
import net.i2p.util.EepHead;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Task to periodically look for updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 * Also looks for unsigned updates.
 *
 * Runs forever on instantiation, can't be stopped.
 *
 * @since 0.9.2 moved from NewsFetcher
 */
class NewsTimerTask implements SimpleTimer.TimedEvent {
    private final RouterContext _context;
    private final Log _log;
    private final ConsoleUpdateManager _mgr;

    private static final long INITIAL_DELAY = 5*60*1000;
    private static final long RUN_DELAY = 10*60*1000;

    public NewsTimerTask(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(NewsTimerTask.class);
        _mgr = (ConsoleUpdateManager) _context.updateManager();
        ctx.simpleScheduler().addPeriodicEvent(this,
                                             INITIAL_DELAY + _context.random().nextLong(INITIAL_DELAY),
                                             RUN_DELAY);
        // UpdateManager calls NewsFetcher to check the existing news at startup
    }

    public void timeReached() {
        if (shouldFetchNews()) {
            fetchNews();
            if (shouldFetchUnsigned())
                fetchUnsignedHead();
        }
    }
    
    private boolean shouldFetchNews() {
        if (_context.router().gracefulShutdownInProgress())
            return false;
        if (NewsHelper.isUpdateInProgress())
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

    private void fetchNews() {
        _mgr.check(NEWS, "");
    }
    
    private boolean shouldFetchUnsigned() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL);
        return url != null && url.length() > 0 &&
               _context.getBooleanProperty(ConfigUpdateHandler.PROP_UPDATE_UNSIGNED) &&
               !NewsHelper.dontInstall(_context);
    }

    /**
     * HEAD the update url, and if the last-mod time is newer than the last update we
     * downloaded, as stored in the properties, then we download it using eepget.
     */
    private void fetchUnsignedHead() {
        _mgr.check(ROUTER_UNSIGNED, "");
    }
}
