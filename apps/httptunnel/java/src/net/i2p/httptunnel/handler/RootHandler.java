package net.i2p.httptunnel.handler;

import java.io.IOException;
import java.io.OutputStream;

import net.i2p.httptunnel.HTTPListener;
import net.i2p.httptunnel.Request;
import net.i2p.util.Log;

/**
 * Main handler for all requests. Dispatches requests to other handlers.
 */
public class RootHandler {

    private static final Log _log = new Log(RootHandler.class); /* UNUSED */

    private RootHandler() {
        errorHandler = new ErrorHandler();
        localHandler = new LocalHandler();
        proxyHandler = new ProxyHandler(errorHandler);
        eepHandler = new EepHandler(errorHandler);
    }

    private ErrorHandler errorHandler;
    private ProxyHandler proxyHandler;
    private LocalHandler localHandler;
    private EepHandler eepHandler;

    private static RootHandler instance;

    /**
     * Singleton stuff
     * @return the one and only instance, yay!
     */
    public static synchronized RootHandler getInstance() {
        if (instance == null) {
            instance = new RootHandler();
        }
        return instance;
    }

    /**
     * The _ROOT_ handler:  it passes its workload off to the other handlers.
     * @param req a Request
     * @param httpl an HTTPListener
     * @param out where to write the results
     * @throws IOException
     */
    public void handle(Request req, HTTPListener httpl, OutputStream out) throws IOException {
        String url = req.getURL();
        System.out.println(url);
        /* boolean byProxy = false; */
        int pos;
        if (url.startsWith("http://")) { // access via proxy
            /* byProxy=true; */
            if (httpl.firstProxyUse()) {
                localHandler.handleProxyConfWarning(req, httpl, out);
                return;
            }
            url = url.substring(7);
            pos = url.indexOf("/");
            String host;

            if (pos == -1) {
                errorHandler.handle(req, httpl, out, "No host end in URL");
                return;
            }
            
            host = url.substring(0, pos);
            url = url.substring(pos);
            if ("i2p".equals(host) || "i2p.i2p".equals(host)) {
                // normal request; go on below...
            } else if (host.endsWith(".i2p")) {
                // "old" service request, send a redirect...
                out.write(("HTTP/1.1 302 Moved\r\nLocation: " + "http://i2p.i2p/" + host + url + "\r\n\r\n").getBytes("ISO-8859-1"));
                return;
            } else {
                // this is for proxying to the real web
                proxyHandler.handle(req, httpl, out /*, true */);
                return;
            }
        }
        if (url.equals("/")) { // main page
            url = "/_/local/index";
        } else if (!url.startsWith("/")) {
            errorHandler.handle(req, httpl, out, "No leading slash in URL: " + url);
            return;
        }
        String dest;
        url = url.substring(1);
        pos = url.indexOf("/");
        if (pos == -1) {
            dest = url;
            url = "/";
        } else {
            dest = url.substring(0, pos);
            url = url.substring(pos);
        }
        req.setURL(url);
        if (dest.equals("_")) { // no eepsite
            if (url.startsWith("/local/")) { // local request
                req.setURL(url.substring(6));
                localHandler.handle(req, httpl, out /*, byProxy */);
            } else if (url.startsWith("/http/")) { // http warning
                localHandler.handleHTTPWarning(req, httpl, out /*, byProxy */);
            } else if (url.startsWith("/proxy/")) { // http proxying
                req.setURL("http://" + url.substring(7));
                proxyHandler.handle(req, httpl, out /*, byProxy */);
            } else {
                errorHandler.handle(req, httpl, out, "No local handler for this URL: " + url);
            }
        } else {
            eepHandler.handle(req, httpl, out, /* byProxy, */dest);
        }
    }
}