package net.i2p.router.update;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TimeZone;

import net.i2p.app.ClientAppManager;
import net.i2p.crypto.SU3File;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.news.NewsEntry;
import net.i2p.router.news.NewsManager;
import net.i2p.router.news.NewsMetadata;
import net.i2p.router.news.NewsXMLParser;
import net.i2p.router.util.RFC822Date;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.NewsHelper;
import net.i2p.update.*;
import static net.i2p.update.UpdateType.*;
import static net.i2p.update.UpdateMethod.*;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import net.i2p.util.ReusableGZIPInputStream;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SSLEepGet;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

import org.cybergarage.xml.Node;

/**
 * Task to fetch updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 *
 * @since 0.9.4 moved from NewsFetcher and make an Updater
 */
class NewsFetcher extends UpdateRunner {
    private String _lastModified;
    private long _newLastModified;
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
        long lastMod = NewsHelper.lastUpdated(ctx);
        if (lastMod > 0)
            _lastModified = RFC822Date.to822Date(lastMod);
    }

    @Override
    public void run() {
        _isRunning = true;
        try {
            fetchNews();
        } catch (Throwable t) {
            _mgr.notifyTaskFailed(this, "", t);
        } finally {
            _mgr.notifyCheckComplete(this, _isNewer, _success);
            _isRunning = false;
        }
    }

    public void fetchNews() {
        boolean shouldProxy = _context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY_NEWS, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY_NEWS);
        String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
        int proxyPort = ConfigUpdateHandler.proxyPort(_context);
        if (shouldProxy && proxyPort == ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT &&
            proxyHost.equals(ConfigUpdateHandler.DEFAULT_PROXY_HOST) &&
            _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY) < 0) {
            if (_log.shouldWarn())
                _log.warn("Cannot fetch news - HTTP client tunnel not running");
            return;
        }
        if (shouldProxy && _context.commSystem().isDummy()) {
            if (_log.shouldWarn())
                _log.warn("Cannot fetch news - VM Comm system");
            return;
        }

        for (URI uri : _urls) {
            _currentURI = addLang(uri);
            String newsURL = _currentURI.toString();

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
                // will be adjusted in headerReceived() below
                _newLastModified = start;
                if (get.fetch()) {
                    int status = get.getStatusCode();
                    if (status == 200 || status == 304) {
                        Map<String, String> opts = new HashMap<String, String>(2);
                        opts.put(NewsHelper.PROP_LAST_CHECKED, Long.toString(start));
                        if (status == 200 && _isNewer)
                            opts.put(NewsHelper.PROP_LAST_UPDATED, Long.toString(_newLastModified));
                        _context.router().saveConfig(opts, null);
                        return;
                    }
                }
            } catch (Throwable t) {
                _log.error("Error fetching the news", t);
            }
        }
    }

    /**
     *  Add a query param for the local language to get translated news.
     *  Unchanged if disabled by property, if language is english,
     *  or if URI already contains a language parameter
     *
     *  @since 0.9.21
     */
    private URI addLang(URI uri) {
        if (!_context.getBooleanPropertyDefaultTrue(NewsHelper.PROP_TRANSLATE))
            return uri;
        String lang = Translate.getLanguage(_context);
        if (lang.equals("en"))
            return uri;
        String query = uri.getRawQuery();
        if (query != null && (query.startsWith("lang=") || query.contains("&lang=")))
            return uri;
        String url = uri.toString();
        StringBuilder buf = new StringBuilder();
        buf.append(url);
        if (query != null)
            buf.append("&lang=");
        else
            buf.append("?lang=");
        buf.append(lang);
        String co = Translate.getCountry(_context);
        if (co.length() > 0)
            buf.append('_').append(co);
        try {
            return new URI(buf.toString());
        } catch (URISyntaxException use) {
            return uri;
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
     *  TODO: SU3
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
     *  Overriden to get the last-modified header
     */
    @Override
    public void headerReceived(String url, int attemptNum, String key, String val) {
        if ("Last-Modified".equals(key)) {
            long lm = RFC822Date.parse822Date(val);
            // _newLastModified was set to start time in fetchNews() above
            if (lm > 0 && lm < _newLastModified)
                _newLastModified = lm;
        }
    }

    /**
     *  Copies the file from temp dir to the news location,
     *  calls checkForUpdates()
     */
    @Override
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
        if (_log.shouldLog(Log.INFO))
            _log.info("News fetched from " + url + " with " + (alreadyTransferred+bytesTransferred));
        
        if (_tempFile.exists() && _tempFile.length() > 0) {
            File from;
            // TODO check magic number instead?
            // But then a corrupt file would be displayed as-is...
            if (url.endsWith(".su3") || url.contains(".su3?")) {
                try {
                    from = processSU3();
                } catch (IOException ioe) {
                    _log.error("Failed to extract the news file", ioe);
                    _tempFile.delete();
                    return;
                }
            } else {
                from = _tempFile;
            }
            boolean copied = FileUtil.rename(from, _newsFile);
            _tempFile.delete();
            if (copied) {
                // this is either the start time or the Last-Modified header
                String newVer = Long.toString(_newLastModified);
                // fixme su3 version ? but it will be older than file version, which is older than now.
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

    /**
     *  Process the fetched su3 news file _tempFile.
     *  Handles 3 types of contained files: xml.gz (preferred), xml, and html (old format fake xml)
     *
     *  @return the temp file contining the HTML-format news.xml
     *  @since 0.9.17
     */
    private File processSU3() throws IOException {
        SU3File su3 = new SU3File(_context, _tempFile);
        // real xml, maybe gz, maybe not
        File to1 = new File(_context.getTempDir(), "tmp-" + _context.random().nextInt() + ".xml");
        // real xml
        File to2 = new File(_context.getTempDir(), "tmp2-" + _context.random().nextInt() + ".xml");
        try {
            su3.verifyAndMigrate(to1);
            int type = su3.getFileType();
            if (su3.getContentType() != SU3File.CONTENT_NEWS)
                throw new IOException("bad content type: " + su3.getContentType());
            if (type == SU3File.TYPE_HTML)
                return to1;
            if (type != SU3File.TYPE_XML && type != SU3File.TYPE_XML_GZ)
                throw new IOException("bad file type: " + type);
            File xml;
            if (type == SU3File.TYPE_XML_GZ) {
                gunzip(to1, to2);
                xml = to2;
                to1.delete();
            } else {
                xml = to1;
            }
            NewsXMLParser parser = new NewsXMLParser(_context);
            Node root = parser.parse(xml);
            xml.delete();
            NewsMetadata data = parser.getMetadata();
            List<NewsEntry> entries = parser.getEntries();
            // add entries to the news manager
            ClientAppManager cmgr = _context.clientAppManager();
            if (cmgr != null) {
                NewsManager nmgr = (NewsManager) cmgr.getRegisteredApp(NewsManager.APP_NAME);
                if (nmgr != null) {
                    nmgr.addEntries(entries);
                    List<Node> nodes = NewsXMLParser.getNodes(root, "entry");
                    nmgr.storeEntries(nodes);
                }
            }
            // store entries and metadata in old news.xml format
            String sudVersion = su3.getVersionString();
            String signingKeyName = su3.getSignerString();
            File to3 = new File(_context.getTempDir(), "tmp3-" + _context.random().nextInt() + ".xml");
            outputOldNewsXML(data, entries, sudVersion, signingKeyName, to3);
            return to3;
        } finally {
            to2.delete();
        }
    }

    /**
     *  Gunzip the file
     *
     *  @since 0.9.17
     */
    private static void gunzip(File from, File to) throws IOException {
        ReusableGZIPInputStream in = ReusableGZIPInputStream.acquire();
        OutputStream out = null;
        try {
            in.initialize(new FileInputStream(from));
            out = new SecureFileOutputStream(to);
            byte buf[] = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        } finally {
            if (out != null) try { 
                out.close(); 
            } catch (IOException ioe) {}
            ReusableGZIPInputStream.release(in);
        }
    }

    /**
     *  Output in the old format.
     *
     *  @since 0.9.17
     */
    private void outputOldNewsXML(NewsMetadata data, List<NewsEntry> entries,
                                  String sudVersion, String signingKeyName, File to) throws IOException {
        NewsMetadata.Release latestRelease = data.releases.get(0);
        Writer out = null;
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(to), "UTF-8"));
            out.write("<!--\n");
            // update metadata in old format
            out.write("<i2p.release ");
            if (latestRelease.i2pVersion != null)
                out.write(" version=\"" + latestRelease.i2pVersion + '"');
            if (latestRelease.minVersion != null)
                out.write(" minVersion=\"" + latestRelease.minVersion + '"');
            if (latestRelease.minJavaVersion != null)
                out.write(" minJavaVersion=\"" + latestRelease.minJavaVersion + '"');
            String su3Torrent = "";
            String su2Torrent = "";
            for (NewsMetadata.Update update : latestRelease.updates) {
                if (update.torrent != null) {
                    if ("su3".equals(update.type))
                        su3Torrent = update.torrent;
                    else if ("su2".equals(update.type))
                        su2Torrent = update.torrent;
                }
            }
            if (!su2Torrent.isEmpty())
                out.write(" su2Torrent=\"" + su2Torrent + '"');
            if (!su3Torrent.isEmpty())
                out.write(" su3Torrent=\"" + su3Torrent + '"');
            out.write("/>\n");
            // su3 and feed metadata for debugging
            out.write("** News version:\t" + DataHelper.stripHTML(sudVersion) + '\n');
            out.write("** Signed by:\t" + signingKeyName + '\n');
            out.write("** Feed:\t" + DataHelper.stripHTML(data.feedTitle) + '\n');
            out.write("** Feed ID:\t" + DataHelper.stripHTML(data.feedID) + '\n');
            out.write("** Feed Date:\t" + (new Date(data.feedUpdated)) + '\n');
            out.write("-->\n");
            if (entries == null)
                return;
            DateFormat fmt = DateFormat.getDateInstance(DateFormat.SHORT);
            // the router sets the JVM time zone to UTC but saves the original here so we can get it
            String systemTimeZone = _context.getProperty("i2p.systemTimeZone");
            if (systemTimeZone != null)
                fmt.setTimeZone(TimeZone.getTimeZone(systemTimeZone));
            for (NewsEntry e : entries) {
                if (e.title == null || e.content == null)
                    continue;
                Date date = new Date(e.updated);
                out.write("<!-- Entry Date: " + date + " -->\n");
                out.write("<h3>");
                out.write(fmt.format(date));
                out.write(": ");
                out.write(e.title);
                out.write("</h3>\n");
                out.write(e.content);
                out.write("\n\n");
            }
        } finally {
            if (out != null) try { 
                out.close(); 
            } catch (IOException ioe) {}
        }
    }
}
