package net.i2p.router.networkdb.reseed;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Moved from ReseedHandler in routerconsole. See ReseedChecker for additional comments.
 *
 * Handler to deal with reseed requests.  This will reseed from the URLs
 * specified below unless the I2P configuration property "i2p.reseedURL" is
 * set.  It always writes to ./netDb/, so don't mess with that.
 *
 */
public class Reseeder {
    private static ReseedRunner _reseedRunner;
    private RouterContext _context;
    private Log _log;

    // Reject unreasonably big files, because we download into a ByteArrayOutputStream.
    private static final long MAX_RESEED_RESPONSE_SIZE = 8 * 1024 * 1024;

    private static final String DEFAULT_SEED_URL = "http://netdb.i2p2.de/,http://b.netdb.i2p2.de/,http://reseed.i2p-projekt.de/";
    private static final String PROP_INPROGRESS = "net.i2p.router.web.ReseedHandler.reseedInProgress";
    private static final String PROP_ERROR = "net.i2p.router.web.ReseedHandler.errorMessage";
    private static final String PROP_STATUS = "net.i2p.router.web.ReseedHandler.statusMessage";

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
                System.setProperty(PROP_INPROGRESS, "true");
                I2PThread reseed = new I2PThread(_reseedRunner, "Reseed");
                reseed.start();
            }
        }

    }

    public class ReseedRunner implements Runnable, EepGet.StatusListener {
        private boolean _isRunning;

        public ReseedRunner() {
            _isRunning = false; 
            System.setProperty(PROP_STATUS, "Reseeding.");
        }
        public boolean isRunning() { return _isRunning; }
        public void run() {
            _isRunning = true;
            System.out.println("Reseed start");
            reseed(false);
            System.out.println("Reseed complete");
            System.setProperty(PROP_INPROGRESS, "false");
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
        */
        private static final String RESEED_TIPS =
        "Ensure that nothing blocks outbound HTTP, check <a href=logs.jsp>logs</a> " +
        "and if nothing helps, read FAQ about reseeding manually.";
        
        private void reseed(boolean echoStatus) {
            List URLList = new ArrayList();
            String URLs = _context.getProperty("i2p.reseedURL", DEFAULT_SEED_URL);
            StringTokenizer tok = new StringTokenizer(URLs, " ,");
            while (tok.hasMoreTokens())
                URLList.add(tok.nextToken().trim());
            Collections.shuffle(URLList);
            for (int i = 0; i < URLList.size() && _isRunning; i++)
                reseedOne((String) URLList.get(i), echoStatus);
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
         **/
        private void reseedOne(String seedURL, boolean echoStatus) {

            try {
                System.setProperty(PROP_ERROR, "");
                System.setProperty(PROP_STATUS, "Reseeding: fetching seed URL.");
                System.err.println("Reseed from " + seedURL);
                URL dir = new URL(seedURL);
                byte contentRaw[] = readURL(dir);
                if (contentRaw == null) {
                    System.setProperty(PROP_ERROR,
                        "Last reseed failed fully (failed reading seed URL). " +
                        RESEED_TIPS);
                    // Logging deprecated here since attemptFailed() provides better info
                    _log.debug("Failed reading seed URL: " + seedURL);
                    return;
                }
                String content = new String(contentRaw);
                Set urls = new HashSet();
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
                    _log.error("Read " + contentRaw.length + " bytes from seed " + seedURL + ", but found no routerInfo URLs.");
                    System.setProperty(PROP_ERROR,
                        "Last reseed failed fully (no routerInfo URLs at seed URL). " +
                        RESEED_TIPS);
                    return;
                }

                List urlList = new ArrayList(urls);
                Collections.shuffle(urlList);
                int fetched = 0;
                int errors = 0;
                // 200 max from one URL
                for (Iterator iter = urlList.iterator(); iter.hasNext() && fetched < 200; ) {
                    try {
                        System.setProperty(PROP_STATUS,
                            "Reseeding: fetching router info from seed URL (" +
                            fetched + " successful, " + errors + " errors, " + total + " total).");

                        fetchSeed(seedURL, (String)iter.next());
                        fetched++;
                        if (echoStatus) {
                            System.out.print(".");
                            if (fetched % 60 == 0)
                                System.out.println();
                        }
                    } catch (Exception e) {
                        errors++;
                    }
                }
                System.err.println("Reseed got " + fetched + " router infos from " + seedURL);
                
                int failPercent = 100 * errors / total;
                
                // Less than 10% of failures is considered success,
                // because some routerInfos will always fail.
                if ((failPercent >= 10) && (failPercent < 90)) {
                    System.setProperty(PROP_ERROR,
                        "Last reseed failed partly (" + failPercent + "% of " + total + "). " +
                        RESEED_TIPS);
                }
                if (failPercent >= 90) {
                    System.setProperty(PROP_ERROR,
                        "Last reseed failed (" + failPercent + "% of " + total + "). " +
                        RESEED_TIPS);
                }
                if (fetched > 0)
                    _context.netDb().rescan();
                // Don't go on to the next URL if we have enough
                if (fetched >= 100)
                    _isRunning = false;
            } catch (Throwable t) {
                System.setProperty(PROP_ERROR,
                    "Last reseed failed fully (exception caught). " +
                    RESEED_TIPS);
                _log.error("Error reseeding", t);
            }
        }
    
        /* Since we don't return a value, we should always throw an exception if something fails. */
        private void fetchSeed(String seedURL, String peer) throws Exception {
            URL url = new URL(seedURL + (seedURL.endsWith("/") ? "" : "/") + "routerInfo-" + peer + ".dat");

            byte data[] = readURL(url);
            if (data == null) {
                // Logging deprecated here since attemptFailed() provides better info
                _log.debug("Failed fetching seed: " + url.toString());
                throw new Exception ("Failed fetching seed.");
            }
            //System.out.println("read: " + (data != null ? data.length : -1));
            writeSeed(peer, data);
        }

        private byte[] readURL(URL url) throws Exception {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);

            // Do a non-proxied eepget into our ByteArrayOutputStream with 0 retries
            EepGet get = new EepGet( I2PAppContext.getGlobalContext(), false, null, -1, 0, 0, MAX_RESEED_RESPONSE_SIZE,
                null, baos, url.toString(), false, null, null);
            get.addStatusListener(ReseedRunner.this);
            if (get.fetch()) return baos.toByteArray(); else return null;
        }
    
        private void writeSeed(String name, byte data[]) throws Exception {
            String dirName = "netDb"; // _context.getProperty("router.networkDatabase.dbDir", "netDb");
            File netDbDir = new File(_context.getRouterDir(), dirName);
            if (!netDbDir.exists()) {
                boolean ok = netDbDir.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(new File(netDbDir, "routerInfo-" + name + ".dat"));
            fos.write(data);
            fos.close();
        }

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
