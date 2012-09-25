package net.i2p.router.web;

import java.io.File;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.update.ConsoleUpdateManager;
import static net.i2p.update.UpdateType.*;

/**
 *  If news file does not exist, use file from the initialNews directory
 *  in $I2P
 *
 *  @since 0.8.2
 */
public class NewsHelper extends ContentHelper {
    
    public static final String PROP_LAST_UPDATE_TIME = "router.updateLastDownloaded";
    /** @since 0.8.12 */
    private static final String PROP_LAST_HIDDEN = "routerconsole.newsLastHidden";
    /** @since 0.9.2 */
    public static final String PROP_LAST_CHECKED = "routerconsole.newsLastChecked";
    /** @since 0.9.2 */
    public static final String PROP_LAST_UPDATED = "routerconsole.newsLastUpdated";
    public static final String NEWS_FILE = "docs/news.xml";

    /**
     *  If ANY update is in progress.
     *  @since 0.9.2 was stored in system properties
     */
    public static boolean isAnyUpdateInProgress() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return false;
        return mgr.isUpdateInProgress();
    }

    /**
     *  If a signed or unsigned router update is in progress.
     *  Does NOT cover plugins, news, etc.
     *  @since 0.9.2 was stored in system properties
     */
    public static boolean isUpdateInProgress() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return false;
        return mgr.isUpdateInProgress(ROUTER_SIGNED) ||
               mgr.isUpdateInProgress(ROUTER_UNSIGNED) ||
               mgr.isUpdateInProgress(TYPE_DUMMY);
    }

    /**
     *  @since 0.9.2 moved from NewsFetcher
     */
    public static boolean isUpdateAvailable() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return false;
        return mgr.getUpdateAvailable(ROUTER_SIGNED) != null;
    }

    /**
     *  @return null if none
     *  @since 0.9.2 moved from NewsFetcher
     */
    public static String updateVersion() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return null;
        return mgr.getUpdateAvailable(ROUTER_SIGNED);
    }

    /**
     *  @since 0.9.2 moved from NewsFetcher
     */
    public static boolean isUnsignedUpdateAvailable() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return false;
        return mgr.getUpdateAvailable(ROUTER_UNSIGNED) != null;
    }

    /**
     *  @return null if none
     *  @since 0.9.2 moved from NewsFetcher
     */
    public static String unsignedUpdateVersion() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return null;
        return mgr.getUpdateAvailable(ROUTER_UNSIGNED);
    }

    /**
     *  @return "" if none
     *  @since 0.9.2 moved from UpdateHelper
     */
    public static String getUpdateStatus() {
        ConsoleUpdateManager mgr = ConsoleUpdateManager.getInstance();
        if (mgr == null) return "";
        return mgr.getStatus();
    }

    @Override
    public String getContent() {
        File news = new File(_page);
        if (!news.exists())
            _page = (new File(_context.getBaseDir(), "docs/initialNews/initialNews.xml")).getAbsolutePath();
        return super.getContent();
    }

    /**
     *  Is the news newer than the last time it was hidden?
     *  @since 0.8.12
     */
    public boolean shouldShowNews() {
        return shouldShowNews(_context);
    }

    /**
     *  @since 0.9.2
     */
    public static boolean shouldShowNews(RouterContext ctx) {
         long lastUpdated = lastUpdated(ctx);
        if (lastUpdated <= 0)
            return true;
        String h = ctx.getProperty(PROP_LAST_HIDDEN);
        if (h == null)
            return true;
        long last = 0;
        try {
            last = Long.parseLong(h);
        } catch (NumberFormatException nfe) {}
        return lastUpdated > last;
    }

    /**
     *  Save config with the timestamp of the current news to hide, or 0 to show
     *  @since 0.8.12
     */
    public void showNews(boolean yes) {
        showNews(_context, yes);
    }

    /**
     *  Save config with the timestamp of the current news to hide, or 0 to show
     *  @since 0.9.2
     */
    public static void showNews(RouterContext ctx, boolean yes) {
         long lastUpdated = 0;
/////// FIME from props, or from last mod time?
        long stamp = yes ? 0 : lastUpdated;
        ctx.router().saveConfig(PROP_LAST_HIDDEN, Long.toString(stamp));
    }

    /**
     *  @return HTML
     *  @since 0.9.2 moved from NewsFetcher
     */
    public String status() {
        return status(_context);
    }

    /**
     *  @return HTML
     *  @since 0.9.2 moved from NewsFetcher
     */
    public static String status(RouterContext ctx) {
         StringBuilder buf = new StringBuilder(128);
         long now = ctx.clock().now();
         buf.append("<i>");
         long lastUpdated = lastUpdated(ctx);
         long lastFetch = lastChecked(ctx);
         if (lastUpdated > 0) {
             buf.append(Messages.getString("News last updated {0} ago.",
                                           DataHelper.formatDuration2(now - lastUpdated),
                                           ctx))
                .append('\n');
         }
         if (lastFetch > lastUpdated) {
             buf.append(Messages.getString("News last checked {0} ago.",
                                           DataHelper.formatDuration2(now - lastFetch),
                                           ctx));
         }
         buf.append("</i>");
         String consoleNonce = System.getProperty("router.consoleNonce");
         if (lastUpdated > 0 && consoleNonce != null) {
             if (shouldShowNews(ctx)) {
                 buf.append(" <a href=\"/?news=0&amp;consoleNonce=").append(consoleNonce).append("\">")
                    .append(Messages.getString("Hide news", ctx));
             } else {
                 buf.append(" <a href=\"/?news=1&amp;consoleNonce=").append(consoleNonce).append("\">")
                    .append(Messages.getString("Show news", ctx));
             }
             buf.append("</a>");
         }
         return buf.toString();
    }
    
    /**
     *  @since 0.9.2 moved from NewsFetcher
     */
    public static boolean dontInstall(RouterContext ctx) {
        File test = new File(ctx.getBaseDir(), "history.txt");
        boolean readonly = ((test.exists() && !test.canWrite()) || (!ctx.getBaseDir().canWrite()));
        boolean disabled = ctx.getBooleanProperty(ConfigUpdateHandler.PROP_UPDATE_DISABLED);
        return readonly || disabled;
    }

    /**
     *  @since 0.9.2
     */
    public static long lastChecked(RouterContext ctx) {
        String lc = ctx.getProperty(PROP_LAST_CHECKED);
        if (lc == null) {
            try {
                return Long.parseLong(lc);
            } catch (NumberFormatException nfe) {}
        }
        return 0;
    }

    /**
     *  When the news was last downloaded
     *  @since 0.9.2
     */
    public static long lastUpdated(RouterContext ctx) {
        String lc = ctx.getProperty(PROP_LAST_UPDATED);
        if (lc == null) {
            try {
                return Long.parseLong(lc);
            } catch (NumberFormatException nfe) {}
        }
        File newsFile = new File(ctx.getRouterDir(), NEWS_FILE);
        long rv = newsFile.lastModified();
        ctx.router().saveConfig(PROP_LAST_UPDATED, Long.toString(rv));
        return rv;
    }
}
