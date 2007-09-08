package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import java.net.Socket;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.I2PThread;
import net.i2p.util.EepGet;

/**
 * Handler to deal with reseed requests.  This reseed from the URL
 * http://dev.i2p.net/i2pdb2/ unless the I2P configuration property "i2p.reseedURL" is
 * set.  It always writes to ./netDb/, so don't mess with that.
 *
 */
public class ReseedHandler {
    private static ReseedRunner _reseedRunner;
    private RouterContext _context;
    private Log _log;

    // Reject unreasonably big files, because we download into a ByteArrayOutputStream.
    private static final long MAX_RESEED_RESPONSE_SIZE = 8 * 1024 * 1024;

    private static final String DEFAULT_SEED_URL = "http://dev.i2p.net/i2pdb2/";

    public ReseedHandler() {
        this(ContextHelper.getContext(null));
    }
    public ReseedHandler(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(ReseedHandler.class);
    }

    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
            _log = _context.logManager().getLog(ReseedHandler.class);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }


    public void setReseedNonce(String nonce) { 
        if (nonce == null) return;
        if (nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.nonce")) ||
            nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.noncePrev"))) {
            requestReseed();
        }
    }
    
    public void requestReseed() {
        synchronized (ReseedHandler.class) {
            if (_reseedRunner == null)
                _reseedRunner = new ReseedRunner();
            if (_reseedRunner.isRunning()) {
                return;
            } else {
                System.setProperty("net.i2p.router.web.ReseedHandler.reseedInProgress", "true");
                I2PThread reseed = new I2PThread(_reseedRunner, "Reseed");
                reseed.start();
            }
        }

    }

    public class ReseedRunner implements Runnable, EepGet.StatusListener {
        private boolean _isRunning;

        public ReseedRunner() {
            _isRunning = false; 
            System.setProperty("net.i2p.router.web.ReseedHandler.statusMessage","Reseeding.");
        }
        public boolean isRunning() { return _isRunning; }
        public void run() {
            _isRunning = true;
            reseed(false);
            System.out.println("Reseeding complete");
            System.setProperty("net.i2p.router.web.ReseedHandler.reseedInProgress", "false");
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
        private void reseed(boolean echoStatus) {

            String seedURL = _context.getProperty("i2p.reseedURL", DEFAULT_SEED_URL);
            if ( (seedURL == null) || (seedURL.trim().length() <= 0) ) 
                seedURL = DEFAULT_SEED_URL;
            try {
                System.setProperty("net.i2p.router.web.ReseedHandler.errorMessage","");
                System.setProperty("net.i2p.router.web.ReseedHandler.statusMessage","Reseeding: fetching seed URL.");
                URL dir = new URL(seedURL);
                byte contentRaw[] = readURL(dir);
                if (contentRaw == null) {
                    System.setProperty("net.i2p.router.web.ReseedHandler.errorMessage",
                        "Last reseed failed fully (failed reading seed URL). " +
                        "Ensure that nothing blocks outbound HTTP, check <a href=logs.jsp>logs</a> " +
                        "and if nothing helps, read FAQ about reseeding manually.");
                    // Logging deprecated here since attemptFailed() provides better info
                    _log.debug("Failed reading seed URL: " + seedURL);
                    return;
                }
                String content = new String(contentRaw);
                Set urls = new HashSet();
                int cur = 0;
                while (true) {
                    int start = content.indexOf("href=\"routerInfo-", cur);
                    if (start < 0)
                        break;

                    int end = content.indexOf(".dat\">", start);
                    String name = content.substring(start+"href=\"routerInfo-".length(), end);
                    urls.add(name);
                    cur = end + 1;
                }
                if (urls.size() <= 0) {
                    _log.error("Read " + contentRaw.length + " bytes from seed " + seedURL + ", but found no routerInfo URLs.");
                    System.setProperty("net.i2p.router.web.ReseedHandler.errorMessage",
                        "Last reseed failed fully (no routerInfo URLs at seed URL). " +
                        "Ensure that nothing blocks outbound HTTP, check <a href=logs.jsp>logs</a> " +
                        "and if nothing helps, read FAQ about reseeding manually.");
                    return;
                }

                int fetched = 0;
                int errors = 0;
                for (Iterator iter = urls.iterator(); iter.hasNext(); ) {
                    try {
                        System.setProperty("net.i2p.router.web.ReseedHandler.statusMessage",
                            "Reseeding: fetching router info from seed URL (" +
                            fetched + " successful, " + errors + " errors, " + urls.size() + " total).");

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
                if (echoStatus) System.out.println();
                if (errors > 0) {
                    System.setProperty("net.i2p.router.web.ReseedHandler.errorMessage",
                        "Last reseed failed partly (" + errors + " of " + urls.size() + "). " +
                        "Ensure that nothing blocks outbound HTTP, check <a href=logs.jsp>logs</a> " +
                        "and if nothing helps, read FAQ about reseeding manually.");
                }
            } catch (Throwable t) {
                System.setProperty("net.i2p.router.web.ReseedHandler.errorMessage",
                    "Last reseed failed fully (exception caught). " +
                    "Ensure that nothing blocks outbound HTTP, check <a href=logs.jsp>logs</a> " +
                    "and if nothing helps, read FAQ about reseeding manually.");
                _log.error("Error reseeding", t);
            }
        }
    
        /* Since we don't return a value, we should always throw an exception if something fails. */
        private void fetchSeed(String seedURL, String peer) throws Exception {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(ReseedHandler.class);
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
            File netDbDir = new File(dirName);
            if (!netDbDir.exists()) {
                boolean ok = netDbDir.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(new File(netDbDir, "routerInfo-" + name + ".dat"));
            fos.write(data);
            fos.close();
        }

    }

    public static void main(String args[]) {
        if ( (args != null) && (args.length == 1) && (!Boolean.valueOf(args[0]).booleanValue()) ) {
            System.out.println("Not reseeding, as requested");
            return; // not reseeding on request
        }
        System.out.println("Reseeding");
        ReseedHandler reseedHandler = new ReseedHandler();
        reseedHandler.requestReseed();
    }

}
