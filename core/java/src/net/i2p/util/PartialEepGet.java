package net.i2p.util;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;

/**
 * Fetch exactly the first 'size' bytes into a stream
 * Anything less or more will throw an IOException
 * No retries, no min and max size options, no timeout option
 * If the server does not return a Content-Length header of the correct size,
 * the fetch will fail.
 *
 * Useful for checking .sud versions
 *
 * @since 0.7.12
 * @author zzz
 */
public class PartialEepGet extends EepGet {
    private final long _fetchSize;

    /**
     * Instantiate an EepGet that will fetch exactly size bytes when fetch() is called.
     *
     * @param proxyHost use null or "" for no proxy
     * @param proxyPort use 0 for no proxy
     * @param size fetch exactly this many bytes
     */
    public PartialEepGet(I2PAppContext ctx, String proxyHost, int proxyPort,
                         OutputStream outputStream,  String url, long size) {
        // we're using this constructor:
        // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries,
        //               long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        super(ctx, proxyHost != null && proxyPort > 0, proxyHost, proxyPort, 0,
              size, size, null, outputStream, url, true, null, null);
        _fetchSize = size;
    }
   
    /**
     * PartialEepGet [-p 127.0.0.1:4444] [-l #bytes] url
     *
     */ 
    public static void main(String args[]) {
        String proxyHost = "127.0.0.1";
        int proxyPort = 4444;
        // 40 sig + 16 version for .suds
        long size = 56;
        String saveAs = null;
        String username = null;
        String password = null;
        boolean error = false;
        Getopt g = new Getopt("partialeepget", args, "p:cl:o:u:x:");
        try {
            int c;
            while ((c = g.getopt()) != -1) {
              switch (c) {
                case 'p':
                    String s = g.getOptarg();
                    int colon = s.indexOf(':');
                    if (colon >= 0) {
                        // Todo IPv6 [a:b:c]:4444
                        proxyHost = s.substring(0, colon);
                        String port = s.substring(colon + 1);
                        proxyPort = Integer.parseInt(port);
                    } else {
                        proxyHost = s;
                        // proxyPort remains default
                    }
                    break;

                case 'c':
                    // no proxy, same as -p :0
                    proxyHost = "";
                    proxyPort = 0;
                    break;

                case 'l':
                    size = Long.parseLong(g.getOptarg());
                    break;

                case 'o':
                    saveAs = g.getOptarg();
                    break;

                case 'u':
                    username = g.getOptarg();
                    break;

                case 'x':
                    password = g.getOptarg();
                    break;

                case '?':
                case ':':
                default:
                    error = true;
                    break;
              }  // switch
            } // while
        } catch (RuntimeException e) {
            e.printStackTrace();
            error = true;
        }

        if (error || args.length - g.getOptind() != 1) {
            usage();
            System.exit(1);
        }
        String url = args[g.getOptind()];

        if (saveAs == null)
            saveAs = suggestName(url);
        OutputStream out;
        try {
            // resume from a previous eepget won't work right doing it this way
            out = new FileOutputStream(saveAs);
        } catch (IOException ioe) {
            System.err.println("Failed to create output file " + saveAs);
            out = null; // dummy for compiler
            System.exit(1);
        }

        EepGet get = new PartialEepGet(I2PAppContext.getGlobalContext(), proxyHost, proxyPort, out, url, size);
        if (username != null) {
            if (password == null) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
                    do {
                        System.err.print("Proxy password: ");
                        password = r.readLine();
                        if (password == null)
                            throw new IOException();
                        password = password.trim();
                    } while (password.length() <= 0);
                } catch (IOException ioe) {
                    System.exit(1);
                }
            }
            get.addAuthorization(username, password);
        }
        get.addStatusListener(get.new CLIStatusListener(1024, 40));
        if (get.fetch(45*1000, -1, 60*1000)) {
            System.err.println("Last-Modified: " + get.getLastModified());
            System.err.println("Etag: " + get.getETag());
        } else {
            System.err.println("Failed " + url);
            System.exit(1);
        }
    }
    
    private static void usage() {
        System.err.println("PartialEepGet [-p 127.0.0.1[:4444]] [-c] [-o outputFile]\n" +
                           "              [-l #bytes] (default 56)\n" +
                           "              [-u username] [-x password] url\n" +
                           "              (use -c or -p :0 for no proxy)");
    }
    
    @Override
    protected String getRequest() throws IOException {
        StringBuilder buf = new StringBuilder(2048);
        URI url;
        try {
            url = new URI(_actualURL);
        } catch (URISyntaxException use) {
            IOException ioe = new MalformedURLException("Bad URL");
            ioe.initCause(use);
            throw ioe;
        }
        String host = url.getHost();
        if (host == null || host.length() <= 0)
            throw new MalformedURLException("Bad URL, no host");
        int port = url.getPort();
        String path = url.getRawPath();
        String query = url.getRawQuery();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Requesting " + _actualURL);
        // RFC 2616 sec 5.1.2 - full URL if proxied, absolute path only if not proxied
        String urlToSend;
        if (_shouldProxy) {
            urlToSend = _actualURL;
            if ((path == null || path.length()<= 0) &&
                (query == null || query.length()<= 0))
                urlToSend += "/";
        } else {
            urlToSend = path;
            if (urlToSend == null || urlToSend.length()<= 0)
                urlToSend = "/";
            if (query != null)
                urlToSend += '?' + query;
        }
        buf.append("GET ").append(urlToSend).append(" HTTP/1.1\r\n");
        // RFC 2616 sec 5.1.2 - host + port (NOT authority, which includes userinfo)
        buf.append("Host: ").append(host);
        if (port >= 0)
            buf.append(':').append(port);
        buf.append("\r\n");
        buf.append("Range: bytes=");
        buf.append(_alreadyTransferred);
        buf.append('-');
        buf.append(_fetchSize - 1);
        buf.append("\r\n");

        buf.append("Cache-Control: no-cache\r\n" +
                   "Pragma: no-cache\r\n" +
                   "Accept-Encoding: \r\n" +
                   "Connection: close\r\n");
        boolean uaOverridden = false;
        if (_extraHeaders != null) {
            for (String hdr : _extraHeaders) {
                if (hdr.toLowerCase(Locale.US).startsWith("user-agent: "))
                    uaOverridden = true;
                buf.append(hdr).append("\r\n");
            }
        }
        // This will be replaced if we are going through I2PTunnelHTTPClient
        if(!uaOverridden)
            buf.append("User-Agent: " + USER_AGENT + "\r\n");
        if (_authState != null && _shouldProxy && _authState.authMode != AUTH_MODE.NONE) {
            buf.append("Proxy-Authorization: ");
            buf.append(_authState.getAuthHeader("GET", urlToSend));
            buf.append("\r\n");
        }
        buf.append("\r\n");

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request: [" + buf.toString() + "]");
        return buf.toString();
    }
}
