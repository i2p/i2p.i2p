package net.i2p.router.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;

/**
 * Task to periodically look for updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 */
public class NewsFetcher implements Runnable, EepGet.StatusListener {
    private I2PAppContext _context;
    private Log _log;
    private boolean _updateAvailable;
    private long _lastFetch;
    private long _lastUpdated;
    private String _updateVersion;
    private String _lastModified;
    private File _newsFile;
    private File _tempFile;
    private static NewsFetcher _instance;
    //public static final synchronized NewsFetcher getInstance() { return _instance; }
    public static final synchronized NewsFetcher getInstance(I2PAppContext ctx) { 
        if (_instance != null)
            return _instance;
        _instance = new NewsFetcher(ctx);
        return _instance;
    }

    private static final String NEWS_FILE = "docs/news.xml";
    private static final String TEMP_NEWS_FILE = "news.xml.temp";
    
    private NewsFetcher(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(NewsFetcher.class);
        _instance = this;
        _lastFetch = 0;
        _newsFile = new File(_context.getRouterDir(), NEWS_FILE);
        _tempFile = new File(_context.getTempDir(), TEMP_NEWS_FILE);
        updateLastFetched();
        _lastUpdated = _lastFetch;
        _updateVersion = "";
    }
    
    private void updateLastFetched() {
        if (_newsFile.exists()) {
            if (_lastFetch == 0)
                _lastFetch = _newsFile.lastModified();
        } else
            _lastFetch = 0;
    }
    
    public boolean updateAvailable() { return _updateAvailable; }
    public String updateVersion() { return _updateVersion; }

    public String status() {
         long now = _context.clock().now();
         return
             (_lastUpdated > 0 ? "News last updated " + DataHelper.formatDuration(now - _lastUpdated) + " ago" : "") +
             (_lastFetch > _lastUpdated ? ", last checked " + DataHelper.formatDuration(now - _lastFetch) + " ago" : "");
    }
    
    public void run() {
        try { Thread.sleep(_context.random().nextLong(5*60*1000)); } catch (InterruptedException ie) {}
        while (true) {
            if (!_updateAvailable) checkForUpdates();
            if (shouldFetchNews())
                fetchNews();
            try { Thread.sleep(10*60*1000); } catch (InterruptedException ie) {}
        }
    }
    
    private boolean shouldInstall() {
        String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
        if ("notify".equals(policy))
            return false;
        File zip = new File(_context.getRouterDir(), Router.UPDATE_FILE);
        return !zip.exists();
    }
    
    private boolean shouldFetchNews() {
        updateLastFetched();
        String freq = _context.getProperty(ConfigUpdateHandler.PROP_REFRESH_FREQUENCY);
        if (freq == null)
            freq = ConfigUpdateHandler.DEFAULT_REFRESH_FREQUENCY;
        try {
            long ms = Long.parseLong(freq);
            if (ms <= 0)
                return false;
            
            if (_lastFetch + ms < _context.clock().now()) {
                return true;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Last fetched " + DataHelper.formatDuration(_context.clock().now() - _lastFetch) + " ago");
                return false;
            }
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid refresh frequency: " + freq);
            return false;
        }
    }
    public void fetchNews() {
        String newsURL = ConfigUpdateHelper.getNewsURL(_context);
        boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
        String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
        String port = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_PORT, ConfigUpdateHandler.DEFAULT_PROXY_PORT);
        if (_tempFile.exists())
            _tempFile.delete();
        
        int proxyPort = -1;
        try {
            proxyPort = Integer.parseInt(port);
            EepGet get = null;
            if (shouldProxy)
                get = new EepGet(_context, true, proxyHost, proxyPort, 2, _tempFile.getAbsolutePath(), newsURL, true, null, _lastModified);
            else
                get = new EepGet(_context, false, null, 0, 0, _tempFile.getAbsolutePath(), newsURL, true, null, _lastModified);
            get.addStatusListener(this);
            if (get.fetch())
                _lastModified = get.getLastModified();
        } catch (Throwable t) {
            _log.error("Error fetching the news", t);
        }
    }
    
    private static final String VERSION_STRING = "version=\"" + RouterVersion.VERSION + "\"";
    private static final String VERSION_PREFIX = "version=\"";
    private void checkForUpdates() {
        _updateAvailable = false;
        if ( (!_newsFile.exists()) || (_newsFile.length() <= 0) ) return;
        FileInputStream in = null;
        try {
            in = new FileInputStream(_newsFile);
            StringBuffer buf = new StringBuffer(128);
            while (DataHelper.readLine(in, buf)) {
                int index = buf.indexOf(VERSION_PREFIX);
                if (index == -1) {
                    // skip
                } else {
                    int end = buf.indexOf("\"", index + VERSION_PREFIX.length());
                    if (end > index) {
                        String ver = buf.substring(index+VERSION_PREFIX.length(), end);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Found version: [" + ver + "]");
                        if (TrustedUpdate.needsUpdate(RouterVersion.VERSION, ver)) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Our version is out of date, update!");
                            _updateVersion = ver;
                            break;
                        } else {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Our version is current");
                            return;
                        }
                    }
                }
                if (buf.indexOf(VERSION_STRING) != -1) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Our version found, no need to update: " + buf.toString());
                    return;
                } else {
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
        // could not find version="0.5.0.1", so there must be an update ;)
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Our version was NOT found (" + RouterVersion.VERSION + "), update needed");
        _updateAvailable = true;
        
        if (shouldInstall()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Policy requests update, so we update");
            UpdateHandler handler = null;
            if (_context instanceof RouterContext) {
                handler = new UpdateHandler((RouterContext)_context);
            } else {
                List contexts = RouterContext.listContexts();
                if (contexts.size() > 0)
                    handler = new UpdateHandler((RouterContext)contexts.get(0));
                else
                    _log.log(Log.CRIT, "No router context to update with?");
            }
            if (handler != null)
                handler.update();
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Policy requests manual update, so we do nothing");
        }
    }
    
    public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
        // ignore
    }
    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        // ignore
    }
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
        if (_log.shouldLog(Log.INFO))
            _log.info("News fetched from " + url + " with " + (alreadyTransferred+bytesTransferred));
        
        long now = _context.clock().now();
        if (_tempFile.exists()) {
            boolean copied = FileUtil.copy(_tempFile.getAbsolutePath(), _newsFile.getAbsolutePath(), true);
            if (copied) {
                _lastUpdated = now;
                _tempFile.delete();
                checkForUpdates();
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Failed to copy the news file!");
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Transfer complete, but no file? - probably 304 Not Modified");
        }
        _lastFetch = now;
    }
    
    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("Failed to fetch the news from " + url);
        _tempFile.delete();
    }
    public void headerReceived(String url, int attemptNum, String key, String val) {}
    public void attempting(String url) {}
}
