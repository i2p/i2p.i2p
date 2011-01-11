package net.i2p.router.networkdb.reseed;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.router.RouterContext;
import net.i2p.util.EepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SSLEepGet;
import net.i2p.util.Translate;

/**
 * Moved from ReseedHandler in routerconsole. See ReseedChecker for additional comments.
 *
 * Handler to deal with reseed requests.  This will reseed from the URLs
 * specified below unless the I2P configuration property "i2p.reseedURL" is
 * set.  It always writes to ./netDb/, so don't mess with that.
 *
 * This is somewhat complicated by trying to log to three places - the console,
 * the router log, and the wrapper log.
 */
public class Reseeder {
    private static ReseedRunner _reseedRunner;
    private RouterContext _context;
    private Log _log;

    // Reject unreasonably big files, because we download into a ByteArrayOutputStream.
    private static final long MAX_RESEED_RESPONSE_SIZE = 1024 * 1024;

    public static final String DEFAULT_SEED_URL =
              "http://a.netdb.i2p2.de/,http://b.netdb.i2p2.de/,http://c.netdb.i2p2.de/," +
              "http://reseed.i2p-projekt.de/,http://www.i2pbote.net/netDb/,http://r31453.ovh.net/static_media/files/netDb/";

    /** @since 0.8.2 */
    public static final String DEFAULT_SSL_SEED_URL =
              "https://a.netdb.i2p2.de/,https://c.netdb.i2p2.de/," +
              "https://www.i2pbote.net/netDb/," +
              "https://r31453.ovh.net/static_media/files/netDb/";

    private static final String PROP_INPROGRESS = "net.i2p.router.web.ReseedHandler.reseedInProgress";
    /** the console shows this message while reseedInProgress == false */
    private static final String PROP_ERROR = "net.i2p.router.web.ReseedHandler.errorMessage";
    /** the console shows this message while reseedInProgress == true */
    private static final String PROP_STATUS = "net.i2p.router.web.ReseedHandler.statusMessage";
    public static final String PROP_PROXY_HOST = "router.reseedProxyHost";
    public static final String PROP_PROXY_PORT = "router.reseedProxyPort";
    /** @since 0.8.2 */
    public static final String PROP_PROXY_ENABLE = "router.reseedProxyEnable";
    /** @since 0.8.2 */
    public static final String PROP_SSL_DISABLE = "router.reseedSSLDisable";
    /** @since 0.8.2 */
    public static final String PROP_SSL_REQUIRED = "router.reseedSSLRequired";
    /** @since 0.8.3 */
    public static final String PROP_RESEED_URL = "i2p.reseedURL";

    public Reseeder(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(Reseeder.class);
    }

    public void requestReseed() {
        synchronized (Reseeder.class) {
            if (_reseedRunner == null)
                _reseedRunner = new ReseedRunner();
            if (_reseedRunner.isRunning()) {
                return;
            } else {
                // set to daemon so it doesn't hang a shutdown
                Thread reseed = new I2PAppThread(_reseedRunner, "Reseed", true);
                reseed.start();
            }
        }

    }

    public class ReseedRunner implements Runnable, EepGet.StatusListener {
        private boolean _isRunning;
        private String _proxyHost;
        private int _proxyPort;
        private SSLEepGet.SSLState _sslState;

        public ReseedRunner() {
            _isRunning = false; 
            System.clearProperty(PROP_ERROR);
            System.setProperty(PROP_STATUS, _("Reseeding"));
            System.setProperty(PROP_INPROGRESS, "true");
        }
        public boolean isRunning() { return _isRunning; }

        /*
         * Do it.
         * We update PROP_ERROR here.
         */
        public void run() {
            _isRunning = true;
            _sslState = null;  // start fresh
            if (_context.getBooleanProperty(PROP_PROXY_ENABLE)) {
                _proxyHost = _context.getProperty(PROP_PROXY_HOST);
                _proxyPort = _context.getProperty(PROP_PROXY_PORT, -1);
            }
            System.out.println("Reseed start");
            int total = reseed(false);
            if (total >= 50) {
                System.out.println("Reseed complete, " + total + " received");
                System.clearProperty(PROP_ERROR);
            } else if (total > 0) {
                System.out.println("Reseed complete, only " + total + " received");
                System.setProperty(PROP_ERROR, ngettext("Reseed fetched only 1 router.",
                                                        "Reseed fetched only {0} routers.", total));
            } else {
                System.out.println("Reseed failed, check network connection");
                System.out.println(
                     "Ensure that nothing blocks outbound HTTP, check the logs, " +
                     "and if nothing helps, read the FAQ about reseeding manually.");
                System.setProperty(PROP_ERROR, _("Reseed failed.") + ' '  +
                                               _("See {0} for help.",
                                                 "<a target=\"_top\" href=\"/configreseed\">" + _("reseed configuration page") + "</a>"));
            }	
            System.setProperty(PROP_INPROGRESS, "false");
            System.clearProperty(PROP_STATUS);
            _sslState = null;  // don't hold ref
            _isRunning = false;
        }

        // EepGet status listeners
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            // Since readURL() runs an EepGet with 0 retries,
            // we can report errors with attemptFailed() instead of transferFailed().
            // It has the benefit of providing cause of failure, which helps resolve issues.
            if (_log.shouldLog(Log.ERROR)) _log.error("EepGet failed on " + url, cause);
        }
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {}
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {}
        public void headerReceived(String url, int attemptNum, String key, String val) {}
        public void attempting(String url) {}
        // End of EepGet status listeners

        /**
        * Reseed has been requested, so lets go ahead and do it.  Fetch all of
        * the routerInfo-*.dat files from the specified URL (or the default) and
        * save them into this router's netDb dir.
        *
        * - If list specified in the properties, use it randomly, without regard to http/https
        * - If SSL not disabled, use the https randomly then
        *   the http randomly
        * - Otherwise just the http randomly.
        *
        * @param echoStatus apparently always false
        * @return count of routerinfos successfully fetched
        */
        private int reseed(boolean echoStatus) {
            List<String> URLList = new ArrayList();
            String URLs = _context.getProperty(PROP_RESEED_URL);
            boolean defaulted = URLs == null;
            boolean SSLDisable = _context.getBooleanProperty(PROP_SSL_DISABLE);
            if (defaulted) {
                if (SSLDisable)
                    URLs = DEFAULT_SEED_URL;
                else
                    URLs = DEFAULT_SSL_SEED_URL;
            }
            StringTokenizer tok = new StringTokenizer(URLs, " ,");
            while (tok.hasMoreTokens())
                URLList.add(tok.nextToken().trim());
            Collections.shuffle(URLList, _context.random());
            if (defaulted && !SSLDisable) {
                // put the non-SSL at the end of the SSL
                List<String> URLList2 = new ArrayList();
                tok = new StringTokenizer(DEFAULT_SEED_URL, " ,");
                while (tok.hasMoreTokens())
                    URLList2.add(tok.nextToken().trim());
                Collections.shuffle(URLList2, _context.random());
                URLList.addAll(URLList2);
            }
            int total = 0;
            for (int i = 0; i < URLList.size() && _isRunning; i++) {
                String url = URLList.get(i);
                int dl = reseedOne(url, echoStatus);
                if (dl > 0) {
                    total += dl;
                    // remove alternate version if we haven't tried it yet
                    String alt;
                    if (url.startsWith("http://"))
                        alt = url.replace("http://", "https://");
                    else
                        alt = url.replace("https://", "http://");
                    int idx = URLList.indexOf(alt);
                    if (idx > i)
                        URLList.remove(i);
                }
            }
            return total;
        }

        /**
         *  Fetch a directory listing and then up to 200 routerInfo files in the listing.
         *  The listing must contain (exactly) strings that match:
         *           href="routerInfo-{hash}.dat">
         *  OR
         *           HREF="routerInfo-{hash}.dat">
         * and then it fetches the files
         *           {seedURL}routerInfo-{hash}.dat
         * after appending a '/' to seedURL if it doesn't have one.
         * Essentially this means that the seedURL must be a directory, it
         * can't end with 'index.html', for example.
         *
         * Jetty directory listings are not compatible, as they look like
         * HREF="/full/path/to/routerInfo-...
         *
         * We update PROP_STATUS here.
         *
         * @param echoStatus apparently always false
         * @return count of routerinfos successfully fetched
         **/
        private int reseedOne(String seedURL, boolean echoStatus) {
            try {
                System.setProperty(PROP_STATUS, _("Reseeding: fetching seed URL."));
                System.err.println("Reseeding from " + seedURL);
                URL dir = new URL(seedURL);
                byte contentRaw[] = readURL(dir);
                if (contentRaw == null) {
                    // Logging deprecated here since attemptFailed() provides better info
                    _log.warn("Failed reading seed URL: " + seedURL);
                    System.err.println("Reseed got no router infos from " + seedURL);
                    return 0;
                }
                String content = new String(contentRaw);
                Set<String> urls = new HashSet(1024);
                int cur = 0;
                int total = 0;
                while (total++ < 1000) {
                    int start = content.indexOf("href=\"routerInfo-", cur);
                    if (start < 0) {
                        start = content.indexOf("HREF=\"routerInfo-", cur);
                        if (start < 0)
                            break;
                    }

                    int end = content.indexOf(".dat\">", start);
                    String name = content.substring(start+"href=\"routerInfo-".length(), end);
                    urls.add(name);
                    cur = end + 1;
                }
                if (total <= 0) {
                    _log.warn("Read " + contentRaw.length + " bytes from seed " + seedURL + ", but found no routerInfo URLs.");
                    System.err.println("Reseed got no router infos from " + seedURL);
                    return 0;
                }

                List<String> urlList = new ArrayList(urls);
                Collections.shuffle(urlList, _context.random());
                int fetched = 0;
                int errors = 0;
                // 200 max from one URL
                for (Iterator<String> iter = urlList.iterator(); iter.hasNext() && fetched < 200; ) {
                    try {
                        System.setProperty(PROP_STATUS,
                            _("Reseeding: fetching router info from seed URL ({0} successful, {1} errors).", fetched, errors));

                        fetchSeed(seedURL, iter.next());
                        fetched++;
                        if (echoStatus) {
                            System.out.print(".");
                            if (fetched % 60 == 0)
                                System.out.println();
                        }
                    } catch (IOException e) {
                        errors++;
                    }
                }
                System.err.println("Reseed got " + fetched + " router infos from " + seedURL + " with " + errors + " errors");

                if (fetched > 0)
                    _context.netDb().rescan();
                // Don't go on to the next URL if we have enough
                if (fetched >= 100)
                    _isRunning = false;
                return fetched;
            } catch (Throwable t) {
                _log.warn("Error reseeding", t);
                System.err.println("Reseed got no router infos from " + seedURL);
                return 0;
            }
        }
    
        /* Since we don't return a value, we should always throw an exception if something fails. */
        private void fetchSeed(String seedURL, String peer) throws IOException {
            URL url = new URL(seedURL + (seedURL.endsWith("/") ? "" : "/") + "routerInfo-" + peer + ".dat");

            byte data[] = readURL(url);
            if (data == null) {
                // Logging deprecated here since attemptFailed() provides better info
                _log.debug("Failed fetching seed: " + url.toString());
                throw new IOException("Failed fetching seed.");
            }
            //System.out.println("read: " + (data != null ? data.length : -1));
            writeSeed(peer, data);
        }

        private byte[] readURL(URL url) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);

            EepGet get;
            boolean ssl = url.toString().startsWith("https");
            if (ssl) {
                SSLEepGet sslget;
                if (_sslState == null) {
                    sslget = new SSLEepGet(I2PAppContext.getGlobalContext(), baos, url.toString());
                    // save state for next time
                    _sslState = sslget.getSSLState();
                } else {
                    sslget = new SSLEepGet(I2PAppContext.getGlobalContext(), baos, url.toString(), _sslState);
                }
                get = sslget;
            } else {
                // Do a (probably) non-proxied eepget into our ByteArrayOutputStream with 0 retries
                boolean shouldProxy = _proxyHost != null && _proxyHost.length() > 0 && _proxyPort > 0;
                get = new EepGet(I2PAppContext.getGlobalContext(), shouldProxy, _proxyHost, _proxyPort, 0, 0, MAX_RESEED_RESPONSE_SIZE,
                                 null, baos, url.toString(), false, null, null);
            }
            get.addStatusListener(ReseedRunner.this);
            if (get.fetch())
                return baos.toByteArray();
            return null;
        }
    
        private void writeSeed(String name, byte data[]) throws IOException {
            String dirName = "netDb"; // _context.getProperty("router.networkDatabase.dbDir", "netDb");
            File netDbDir = new SecureDirectory(_context.getRouterDir(), dirName);
            if (!netDbDir.exists()) {
                boolean ok = netDbDir.mkdirs();
            }
            FileOutputStream fos = null;
            try {
                fos = new SecureFileOutputStream(new File(netDbDir, "routerInfo-" + name + ".dat"));
                fos.write(data);
            } finally {
                try {
                    if (fos != null) fos.close();
                } catch (IOException ioe) {}
            }
        }

    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {
        return s;
    }

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /** translate */
    private String _(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    /** translate */
    private String _(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }

    /** translate */
    private String _(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, _context, BUNDLE_NAME);
    }

    /** translate */
    private String ngettext(String s, String p, int n) {
        return Translate.getString(n, s, p, _context, BUNDLE_NAME);
    }

/******
    public static void main(String args[]) {
        if ( (args != null) && (args.length == 1) && (!Boolean.valueOf(args[0]).booleanValue()) ) {
            System.out.println("Not reseeding, as requested");
            return; // not reseeding on request
        }
        System.out.println("Reseeding");
        Reseeder reseedHandler = new Reseeder();
        reseedHandler.requestReseed();
    }
******/
}
