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
import net.i2p.router.util.RFC822Date;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.ConfigUpdateHelper;
import net.i2p.router.web.NewsHelper;
import net.i2p.update.*;
import static net.i2p.update.UpdateType.*;
import static net.i2p.update.UpdateMethod.*;
import net.i2p.util.EepGet;
import net.i2p.util.EepHead;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;

/**
 * Task to fetch updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 *
 * @since 0.9.2 moved from NewsFetcher and make an Updater
 */
class NewsFetcher extends UpdateRunner {
    private String _lastModified;
    private final File _newsFile;
    private final File _tempFile;

    private static final String TEMP_NEWS_FILE = "news.xml.temp";
    
    public NewsFetcher(RouterContext ctx, List<URI> uris) { 
        super(ctx, uris);
        _newsFile = new File(ctx.getRouterDir(), NewsHelper.NEWS_FILE);
        _tempFile = new File(ctx.getTempDir(), "tmp-" + ctx.random().nextLong() + TEMP_NEWS_FILE);
        long lastMod = NewsHelper.lastChecked(ctx);
        if (lastMod > 0)
            _lastModified = RFC822Date.to822Date(lastMod);
    }

    @Override
    public UpdateType getType() { return NEWS; }

    private boolean dontInstall() {
        return NewsHelper.dontInstall(_context);
    }

    private boolean shouldInstall() {
        String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
        if ("notify".equals(policy) || dontInstall())
            return false;
        File zip = new File(_context.getRouterDir(), Router.UPDATE_FILE);
        return !zip.exists();
    }
    
    @Override
    public void update() {
        fetchNews();
    }

    public void fetchNews() {
        boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
        String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
        int proxyPort = ConfigUpdateHandler.proxyPort(_context);

        for (URI uri : _urls) {
             _currentURI = uri;
             String newsURL = uri.toString();

            if (_tempFile.exists())
                _tempFile.delete();
        
            try {
                EepGet get;
                if (shouldProxy)
                    get = new EepGet(_context, true, proxyHost, proxyPort, 0, _tempFile.getAbsolutePath(), newsURL, true, null, _lastModified);
                else
                    get = new EepGet(_context, false, null, 0, 0, _tempFile.getAbsolutePath(), newsURL, true, null, _lastModified);
                get.addStatusListener(this);
                if (get.fetch()) {
                    String lastMod = get.getLastModified();
                    if (lastMod != null) {
                        _lastModified = lastMod;
                        long lm = RFC822Date.parse822Date(lastMod);
                        if (lm > 0)
                            _context.router().saveConfig(NewsHelper.PROP_LAST_CHECKED, Long.toString(lm));
                    }
                    return;
                }
            } catch (Throwable t) {
                _log.error("Error fetching the news", t);
            }
        }
    }
    
    private static final String VERSION_STRING = "version=\"" + RouterVersion.VERSION + "\"";
    private static final String VERSION_PREFIX = "version=\"";

///// move to UpdateManager?

    /**
     *  Parse the installed (not the temp) news file for the latest version.
     *  TODO: Real XML parsing, different update methods,
     *  URLs in the file, ...
     */
    void checkForUpdates() {
        FileInputStream in = null;
        try {
            in = new FileInputStream(_newsFile);
            StringBuilder buf = new StringBuilder(128);
            while (DataHelper.readLine(in, buf)) {
                int index = buf.indexOf(VERSION_PREFIX);
                if (index >= 0) {
                    int end = buf.indexOf("\"", index + VERSION_PREFIX.length());
                    if (end > index) {
                        String ver = buf.substring(index+VERSION_PREFIX.length(), end);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Found version: [" + ver + "]");
                        if (TrustedUpdate.needsUpdate(RouterVersion.VERSION, ver)) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Our version is out of date, update!");
                            _mgr.notifyVersionAvailable(this, _currentURI,
                                                        ROUTER_SIGNED, "", HTTP,
                                                        _mgr.getUpdateURLs(ROUTER_SIGNED, "", HTTP),
                                                        ver, "");
                        } else {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Our version is current");
                        }
                        return;
                    }
                }
                if (buf.indexOf(VERSION_STRING) == -1) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("No match in " + buf.toString());
                }
                buf.setLength(0);
            }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error checking the news for an update", ioe);
            return;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
        
        if (_log.shouldLog(Log.WARN))
            _log.warn("No version found in news.xml file");
    }
    
    /** override to prevent status update */
    @Override
    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}

    /**
     *  Copies the file from temp dir to the news location,
     *  calls checkForUpdates()
     */
    @Override
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
        if (_log.shouldLog(Log.INFO))
            _log.info("News fetched from " + url + " with " + (alreadyTransferred+bytesTransferred));
        
        long now = _context.clock().now();
        if (_tempFile.exists()) {
            boolean copied = FileUtil.copy(_tempFile, _newsFile, true, false);
            _tempFile.delete();
            if (copied) {
                String newVer = Long.toString(now);
                _context.router().saveConfig(NewsHelper.PROP_LAST_UPDATED, newVer);
                _mgr.notifyVersionAvailable(this, _currentURI, NEWS, "", HTTP,
                                            null, newVer, "");
                checkForUpdates();
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Failed to copy the news file!");
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Transfer complete, but no file? - probably 304 Not Modified");
        }
    }

    /** override to prevent status update */
    @Override
    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {}
}
