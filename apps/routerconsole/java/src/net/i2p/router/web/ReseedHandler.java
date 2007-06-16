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

    private static ReseedRunner _reseedRunner = new ReseedRunner();
    
    public void setReseedNonce(String nonce) { 
        if (nonce == null) return;
        if (nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.nonce")) ||
            nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.noncePrev"))) {
            requestReseed();
        }
    }
    
    public static void requestReseed() {
        synchronized (_reseedRunner) {
            if (_reseedRunner.isRunning()) {
                return;
            } else {
                System.setProperty("net.i2p.router.web.ReseedHandler.reseedInProgress", "true");
                System.out.println("Reseeding");
                I2PThread reseed = new I2PThread(_reseedRunner, "Reseed");
                reseed.start();
            }
        }
    }

    public static class ReseedRunner implements Runnable {
        private boolean _isRunning;
        public ReseedRunner() { _isRunning = false; }
        public boolean isRunning() { return _isRunning; }
        public void run() {
            _isRunning = true;
            reseed(false);
            System.out.println("Reseeding complete");
            System.setProperty("net.i2p.router.web.ReseedHandler.reseedInProgress", "false");
            _isRunning = false;
        }
    }
    
    static final String DEFAULT_SEED_URL = "http://dev.i2p.net/i2pdb2/";
    /**
     * Reseed has been requested, so lets go ahead and do it.  Fetch all of
     * the routerInfo-*.dat files from the specified URL (or the default) and
     * save them into this router's netDb dir.
     *
     */
    private static void reseed(boolean echoStatus) {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        Log log = context.logManager().getLog(ReseedHandler.class);
        
        String seedURL = context.getProperty("i2p.reseedURL", DEFAULT_SEED_URL);
        if ( (seedURL == null) || (seedURL.trim().length() <= 0) ) 
            seedURL = DEFAULT_SEED_URL;
        System.setProperty("net.i2p.router.web.ReseedHandler.errorMessage","");
        try {
            URL dir = new URL(seedURL);
            byte contentRaw[] = readURL(dir);
            if (contentRaw == null) {
                System.setProperty("net.i2p.router.web.ReseedHandler.errorMessage",
                    "Last reseed failed fully (failed reading seed URL). " +
                    "Ensure that nothing blocks outbound HTTP, check <a href=logs.jsp>logs</a> " +
                    "and if nothing helps, read FAQ about reseeding manually.");
                log.error("Failed reading seed URL: " + seedURL);
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
                log.error("Read " + contentRaw.length + " bytes from seed " + seedURL + ", but found no routerInfo URLs.");
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
            log.error("Error reseeding", t);
        }
    }
    
    /* Since we don't return a value, we should always throw an exception if something fails. */
    private static void fetchSeed(String seedURL, String peer) throws Exception {
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(ReseedHandler.class);
        URL url = new URL(seedURL + (seedURL.endsWith("/") ? "" : "/") + "routerInfo-" + peer + ".dat");

        byte data[] = readURL(url);
        if (data == null) {
            log.error("Failed fetching seed: " + url.toString());
            throw new Exception ("Failed fetching seed.");
        }
        if (data.length < 1024) {
            log.error("Fetched data too small to contain a routerInfo: " + url.toString());
            throw new Exception ("Fetched data too small.");
        }
        //System.out.println("read: " + (data != null ? data.length : -1));
        writeSeed(peer, data);
    }
    
    private static byte[] readURL(URL url) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);
        
        // Do a non-proxied eepget into our ByteArrayOutputStream with 0 retries
        EepGet get = new EepGet( I2PAppContext.getGlobalContext(), false, null, -1, 0,
            null, baos, url.toString(), false, null, null);

        if (get.fetch()) return baos.toByteArray(); else return null;
    }
    
    private static void writeSeed(String name, byte data[]) throws Exception {
        String dirName = "netDb"; // _context.getProperty("router.networkDatabase.dbDir", "netDb");
        File netDbDir = new File(dirName);
        if (!netDbDir.exists()) {
            boolean ok = netDbDir.mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(new File(netDbDir, "routerInfo-" + name + ".dat"));
        fos.write(data);
        fos.close();
    }
    
    public static void main(String args[]) {
        if ( (args != null) && (args.length == 1) && (!Boolean.valueOf(args[0]).booleanValue()) ) {
            System.out.println("Not reseeding, as requested");
            return; // not reseeding on request
        }
        System.out.println("Reseeding");
        reseed(true);
    }
}
