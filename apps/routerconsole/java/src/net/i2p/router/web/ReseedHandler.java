package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Handler to deal with reseed requests.  This reseed from the URL
 * http://dev.i2p.net/i2pdb/ unless the java env property "i2p.reseedURL" is
 * set.  It always writes to ./netDb/, so don't mess with that.
 *
 */
public class ReseedHandler {
    private static ReseedRunner _reseedRunner = new ReseedRunner();
    
    public void setReseedNonce(String nonce) { 
        if (nonce == null) return;
        if (nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.nonce")) ||
            nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.noncePrev"))) {
            synchronized (_reseedRunner) {
                if (_reseedRunner.isRunning()) {
                    return;
                } else {
                    System.setProperty("net.i2p.router.web.ReseedHandler.reseedInProgress", "true");
                    I2PThread reseed = new I2PThread(_reseedRunner, "Reseed");
                    reseed.start();
                }
            }
        }
    }

    public static class ReseedRunner implements Runnable {
        private boolean _isRunning;
        public ReseedRunner() { _isRunning = false; }
        public boolean isRunning() { return _isRunning; }
        public void run() {
            _isRunning = true;
            reseed();
            System.setProperty("net.i2p.router.web.ReseedHandler.reseedInProgress", "false");
            _isRunning = false;
        }
    }
    
    private static final String DEFAULT_SEED_URL = "http://dev.i2p.net/i2pdb/";
    /**
     * Reseed has been requested, so lets go ahead and do it.  Fetch all of
     * the routerInfo-*.dat files from the specified URL (or the default) and
     * save them into this router's netDb dir.
     *
     */
    private static void reseed() {
        String seedURL = System.getProperty("i2p.reseedURL", DEFAULT_SEED_URL);
        if ( (seedURL == null) || (seedURL.trim().length() <= 0) ) 
            seedURL = DEFAULT_SEED_URL;
        try {
            URL dir = new URL(seedURL);
            String content = new String(readURL(dir));
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

            int fetched = 0;
            int errors = 0;
            for (Iterator iter = urls.iterator(); iter.hasNext(); ) {
                try {
                    fetchSeed(seedURL, (String)iter.next());
                    fetched++;
                } catch (Exception e) {
                    errors++;
                }
            }
        } catch (Throwable t) {
            I2PAppContext.getGlobalContext().logManager().getLog(ReseedHandler.class).error("Error reseeding", t);
        }
    }
    
    private static void fetchSeed(String seedURL, String peer) throws Exception {
        URL url = new URL(seedURL + (seedURL.endsWith("/") ? "" : "/") + "routerInfo-" + peer + ".dat");

        byte data[] = readURL(url);
        //System.out.println("read: " + (data != null ? data.length : -1));
        writeSeed(peer, data);
    }
    
    private static byte[] readURL(URL url) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);
        String hostname = url.getHost();
        int port = url.getPort();
        if (port < 0)
            port = 80;
        Socket s = new Socket(hostname, port);
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        String request = getRequest(url);
        //System.out.println("Sending to " + hostname +":"+ port + ": " + request);
        out.write(request.getBytes());
        out.flush();
        // skip the HTTP response headers
        // (if we were smart, we'd check for HTTP 200, content-length, etc)
        int consecutiveNL = 0;
        while (true) {
            int cur = in.read();
            switch (cur) {
                case -1: 
                    return null;
                case '\n':
                case '\r':
                    consecutiveNL++;
                    break;
                default:
                    consecutiveNL = 0;
            }
            if (consecutiveNL == 4)
                break;
        }
        // ok, past the headers, grab the goods
        byte buf[] = new byte[1024];
        while (true) {
            int read = in.read(buf);
            if (read < 0)
                break;
            baos.write(buf, 0, read);
        }
        in.close();
        s.close();
        return baos.toByteArray();
    }
    
    private static String getRequest(URL url) {
        StringBuffer buf = new StringBuffer(512);
        String path = url.getPath();
        if ("".equals(path))
            path = "/";
        buf.append("GET ").append(path).append(" HTTP/1.0\n");
        buf.append("Host: ").append(url.getHost());
        int port = url.getPort();
        if ( (port > 0) && (port != 80) )
            buf.append(":").append(port);
        buf.append("\nConnection: close\n\n");
        return buf.toString();
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
        reseed();
        //System.out.println("Done reseeding");
    }
}
