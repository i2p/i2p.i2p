package net.i2p.router.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.util.RFC822Date;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.NewsHelper;
import net.i2p.update.*;
import static net.i2p.update.UpdateType.*;
import static net.i2p.update.UpdateMethod.*;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SSLEepGet;
import net.i2p.util.VersionComparator;

/**
 * Task to fetch updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 *
 * @since 0.9.4 moved from NewsFetcher and make an Updater
 */
class NewsFetcher extends UpdateRunner {
    private String _lastModified;
    private final File _newsFile;
    private final File _tempFile;
    /** is the news newer */
    private boolean _isNewer;
    private boolean _success;

    private static final String TEMP_NEWS_FILE = "news.xml.temp";
    
    public NewsFetcher(RouterContext ctx, ConsoleUpdateManager mgr, List<URI> uris) { 
        super(ctx, mgr, NEWS, uris);
        _newsFile = new File(ctx.getRouterDir(), NewsHelper.NEWS_FILE);
        _tempFile = new File(ctx.getTempDir(), "tmp-" + ctx.random().nextLong() + TEMP_NEWS_FILE);
        long lastMod = NewsHelper.lastChecked(ctx);
        if (lastMod > 0)
            _lastModified = RFC822Date.to822Date(lastMod);
    }

    @Override
    public void run() {
        _isRunning = true;
        try {
            fetchNews();
        } finally {
            _mgr.notifyCheckComplete(this, _isNewer, _success);
            _isRunning = false;
        }
    }

    public void fetchNews() {
        boolean shouldProxy = _context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY_NEWS, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY_NEWS);
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
                else if ("https".equals(uri.getScheme()))
                    // no constructor w/ last mod check
                    get = new SSLEepGet(_context, _tempFile.getAbsolutePath(), newsURL);
                else
                    get = new EepGet(_context, false, null, 0, 0, _tempFile.getAbsolutePath(), newsURL, true, null, _lastModified);
                get.addStatusListener(this);
                long start = _context.clock().now();
                if (get.fetch()) {
                    int status = get.getStatusCode();
                    if (status == 200 || status == 304) {
                        _context.router().saveConfig(NewsHelper.PROP_LAST_CHECKED,
                                                 Long.toString(start));
                        return;
                    }
                }
            } catch (Throwable t) {
                _log.error("Error fetching the news", t);
            }
        }
    }
    
    // Fake XML parsing
    // Line must contain this, and full entry must be on one line
    private static final String VERSION_PREFIX = "<i2p.release ";
    // all keys mapped to lower case by parseArgs()
    private static final String VERSION_KEY = "version";
    // you have to be at least this version to update to the new version
    private static final String MIN_VERSION_KEY = "minversion";
    private static final String MIN_JAVA_VERSION_KEY = "minjavaversion";
    private static final String SUD_KEY = "sudtorrent";
    private static final String SU2_KEY = "su2torrent";
    private static final String SU3_KEY = "su3torrent";
    private static final String CLEARNET_SUD_KEY = "sudclearnet";
    private static final String CLEARNET_SU2_KEY = "su2clearnet";
    private static final String CLEARNET_HTTP_SU3_KEY = "su3clearnet";
    private static final String CLEARNET_HTTPS_SU3_KEY = "su3ssl";
    private static final String I2P_SUD_KEY = "sudi2p";
    private static final String I2P_SU2_KEY = "su2i2p";

    /**
     *  Parse the installed (not the temp) news file for the latest version.
     *  TODO: Real XML parsing
     *  TODO: Check minVersion, use backup URLs specified
     */
    void checkForUpdates() {
        FileInputStream in = null;
        try {
            in = new FileInputStream(_newsFile);
            StringBuilder buf = new StringBuilder(128);
            while (DataHelper.readLine(in, buf)) {
                int index = buf.indexOf(VERSION_PREFIX);
                if (index >= 0) {
                    Map<String, String> args = parseArgs(buf.substring(index+VERSION_PREFIX.length()));
                    String ver = args.get(VERSION_KEY);
                    if (ver != null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Found version: [" + ver + "]");
                        if (TrustedUpdate.needsUpdate(RouterVersion.VERSION, ver)) {
                            if (NewsHelper.isUpdateDisabled(_context)) {
                                String msg = _mgr._("In-network updates disabled. Check package manager.");
                                _log.logAlways(Log.WARN, "Cannot update to version " + ver + ": " + msg);
                                _mgr.notifyVersionConstraint(this, _currentURI, ROUTER_SIGNED, "", ver, msg);
                                return;
                            }
                            if (NewsHelper.isBaseReadonly(_context)) {
                                String msg = _mgr._("No write permission for I2P install directory.");
                                _log.logAlways(Log.WARN, "Cannot update to version " + ver + ": " + msg);
                                _mgr.notifyVersionConstraint(this, _currentURI, ROUTER_SIGNED, "", ver, msg);
                                return;
                            }
                            String minRouter = args.get(MIN_VERSION_KEY);
                            if (minRouter != null) {
                                if (VersionComparator.comp(RouterVersion.VERSION, minRouter) < 0) {
                                    String msg = _mgr._("You must first update to version {0}", minRouter);
                                    _log.logAlways(Log.WARN, "Cannot update to version " + ver + ": " + msg);
                                    _mgr.notifyVersionConstraint(this, _currentURI, ROUTER_SIGNED, "", ver, msg);
                                    return;
                                }
                            }
                            String minJava = args.get(MIN_JAVA_VERSION_KEY);
                            if (minJava != null) {
                                String ourJava = System.getProperty("java.version");
                                if (VersionComparator.comp(ourJava, minJava) < 0) {
                                    String msg = _mgr._("Requires Java version {0} but installed Java version is {1}", minJava, ourJava);
                                    _log.logAlways(Log.WARN, "Cannot update to version " + ver + ": " + msg);
                                    _mgr.notifyVersionConstraint(this, _currentURI, ROUTER_SIGNED, "", ver, msg);
                                    return;
                                }
                            }
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Our version is out of date, update!");
                            // TODO if minversion > our version, continue
                            // and look for a second entry with clearnet URLs
                            // TODO clearnet URLs, notify with HTTP_CLEARNET and/or HTTPS_CLEARNET
                            Map<UpdateMethod, List<URI>> sourceMap = new HashMap<UpdateMethod, List<URI>>(4);
                            // Must do su3 first
                            if (ConfigUpdateHandler.USE_SU3_UPDATE) {
                                sourceMap.put(HTTP, _mgr.getUpdateURLs(ROUTER_SIGNED_SU3, "", HTTP));
                                addMethod(TORRENT, args.get(SU3_KEY), sourceMap);
                                addMethod(HTTP_CLEARNET, args.get(CLEARNET_HTTP_SU3_KEY), sourceMap);
                                addMethod(HTTPS_CLEARNET, args.get(CLEARNET_HTTPS_SU3_KEY), sourceMap);
                                // notify about all sources at once
                                _mgr.notifyVersionAvailable(this, _currentURI, ROUTER_SIGNED_SU3,
                                                            "", sourceMap, ver, "");
                                sourceMap.clear();
                            }
                            // now do sud/su2
                            sourceMap.put(HTTP, _mgr.getUpdateURLs(ROUTER_SIGNED, "", HTTP));
                            String key = FileUtil.isPack200Supported() ? SU2_KEY : SUD_KEY;
                            addMethod(TORRENT, args.get(key), sourceMap);
                            // notify about all sources at once
                            _mgr.notifyVersionAvailable(this, _currentURI, ROUTER_SIGNED,
                                                        "", sourceMap, ver, "");
                        } else {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Our version is current");
                        }
                        return;
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("No version in " + buf.toString());
                    }
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
        
        if (_log.shouldLog(Log.WARN))
            _log.warn("No version found in news.xml file");
    }
    
    /**
     *  Modified from LoadClientAppsJob and I2PTunnelHTTPClientBase
     *  All keys are mapped to lower case.
     *
     *  @param args non-null
     *  @since 0.9.4
     */
    private static Map<String, String> parseArgs(String args) {
        Map<String, String> rv = new HashMap<String, String>(8);
        char data[] = args.toCharArray();
        StringBuilder buf = new StringBuilder(32);
        boolean isQuoted = false;
        String key = null;
        for (int i = 0; i < data.length; i++) {
            switch (data[i]) {
                case '\'':
                case '"':
                    if (isQuoted) {
                        // keys never quoted
                        if (key != null) {
                            rv.put(key, buf.toString().trim());
                            key = null;
                        }
                        buf.setLength(0);
                    }
                    isQuoted = !isQuoted;
                    break;

                case ' ':
                case '\r':
                case '\n':
                case '\t':
                case ',':
                    // whitespace - if we're in a quoted section, keep this as part of the quote,
                    // otherwise use it as a delim
                    if (isQuoted) {
                        buf.append(data[i]);
                    } else {
                        if (key != null) {
                            rv.put(key, buf.toString().trim());
                            key = null;
                        }
                        buf.setLength(0);
                    }
                    break;

                case '=':
                    if (isQuoted) {
                        buf.append(data[i]);
                    } else {
                        key = buf.toString().trim().toLowerCase(Locale.US);
                        buf.setLength(0);
                    }
                    break;

                default:
                    buf.append(data[i]);
                    break;
            }
        }
        if (key != null)
            rv.put(key, buf.toString().trim());
        return rv;
    }

    private static List<URI> tokenize(String URLs) {
        StringTokenizer tok = new StringTokenizer(URLs, " ,\r\n");
        List<URI> rv = new ArrayList<URI>();
        while (tok.hasMoreTokens()) {
            try {
                rv.add(new URI(tok.nextToken().trim()));
            } catch (URISyntaxException use) {}
        }
        return rv;
    }

    /**
     *  Parse URLs and add to the map
     *  @param urls may be null
     *  @since 0.9.9
     */
    private void addMethod(UpdateMethod method, String urls, Map<UpdateMethod, List<URI>> map) {
        if (urls != null) {
            List<URI> uris = tokenize(urls);
            if (!uris.isEmpty()) {
                Collections.shuffle(uris, _context.random());
                map.put(method, uris);
            }
        }
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
                _isNewer = true;
                checkForUpdates();
            } else {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Failed to copy the news file!");
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Transfer complete, but no file? - probably 304 Not Modified");
        }
        _success = true;
    }

    /** override to prevent status update */
    @Override
    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {}
}
