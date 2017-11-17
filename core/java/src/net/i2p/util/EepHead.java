package net.i2p.util;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;

/**
 * This is a quick hack to get a working EepHead, primarily for the following usage:
 * <pre>
 * EepHead foo = new EepHead(...);
 * if (foo.fetch()) {
 *     String lastmod = foo.getLastModified();
 *     if (lastmod != null) {
 *         parse the string...
 *         ...
 *     }
 * }
 * </pre>
 * Other use cases (command line, listeners, etc...) lightly- or un-tested.
 * Note that this follows redirects! This may not be what you want or expect.
 *
 * Writing from scratch rather than extending EepGet would maybe have been less bloated memory-wise.
 * This way gets us redirect handling, among other benefits.
 *
 * @since 0.7.7
 * @author zzz
 */
public class EepHead extends EepGet {
    /** EepGet needs either a non-null file or a stream... shouldn't actually be written to... */
    static final OutputStream _dummyStream = new ByteArrayOutputStream(0);

    public EepHead(I2PAppContext ctx, String proxyHost, int proxyPort, int numRetries, String url) {
        // we're using this constructor:
        // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        super(ctx, true, proxyHost, proxyPort, numRetries, -1, -1, null, _dummyStream, url, true, null, null);
    }
   
    /**
     * EepHead [-p 127.0.0.1:4444] [-n #retries] url
     *
     * This doesn't really do much since it doesn't register a listener.
     * EepGet doesn't have a method to store and return all the headers, so just print
     * out the ones we have methods for.
     * Turn on logging to use it for a decent test.
     */ 
    public static void main(String args[]) {
        String proxyHost = "127.0.0.1";
        int proxyPort = 4444;
        int numRetries = 0;
        int inactivityTimeout = 60*1000;
        String username = null;
        String password = null;
        boolean error = false;
        Getopt g = new Getopt("eephead", args, "p:cn:t:u:x:");
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

                case 'n':
                    numRetries = Integer.parseInt(g.getOptarg());
                    break;

                case 't':
                    inactivityTimeout = 1000 * Integer.parseInt(g.getOptarg());
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
        
        EepHead get = new EepHead(I2PAppContext.getGlobalContext(), proxyHost, proxyPort, numRetries, url);
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
        if (get.fetch(45*1000, -1, inactivityTimeout)) {
            System.err.println("Content-Type: " + get.getContentType());
            System.err.println("Content-Length: " + get.getContentLength());
            System.err.println("Last-Modified: " + get.getLastModified());
            System.err.println("Etag: " + get.getETag());
        } else {
            System.err.println("Failed " + url);
            System.exit(1);
        }
    }
    
    private static void usage() {
        System.err.println("EepHead [-p 127.0.0.1[:4444]] [-c]\n" +
                           "        [-n #retries] (default 0)\n" +
                           "        [-t timeout]  (default 60 sec)\n" +
                           "        [-u username] [-x password] url\n" +
                           "        (use -c or -p :0 for no proxy)");
    }
    
    /** return true if the URL was completely retrieved */
    @Override
    protected void doFetch(SocketTimeout timeout) throws IOException {
        _aborted = false;
        readHeaders();
        if (_aborted)
            throw new IOException("Timed out reading the HTTP headers");
        
        timeout.resetTimer();
        if (_fetchInactivityTimeout > 0)
            timeout.setInactivityTimeout(_fetchInactivityTimeout);
        else
            timeout.setInactivityTimeout(60*1000);
        
        // Should we even follow redirects for HEAD?
        if (_redirectLocation != null) {
            try {
                if (_redirectLocation.startsWith("http://")) {
                    _actualURL = _redirectLocation;
                } else { 
                    // the Location: field has been required to be an absolute URI at least since
                    // RFC 1945 (HTTP/1.0 1996), so it isn't clear what the point of this is.
                    // This oddly adds a ":" even if no port, but that seems to work.
                    URI url = new URI(_actualURL);
                    String host = url.getHost();
                    if (host == null)
                        throw new MalformedURLException("Redirected to invalid URL");
                    int port = url.getPort();
                    if (port < 0)
                        port = 80;
                    if (_redirectLocation.startsWith("/"))
                        _actualURL = "http://" + host + ":" + port + _redirectLocation;
                    else
                        // this blows up completely on a redirect to https://, for example
                        _actualURL = "http://" + host+ ":" + port + "/" + _redirectLocation;
                }
            } catch (URISyntaxException use) {
                IOException ioe = new MalformedURLException("Redirected to invalid URL");
                ioe.initCause(use);
                throw ioe;
            }
            AuthState as = _authState;
            if (_responseCode == 407) {
                if (!_shouldProxy)
                    throw new IOException("Proxy auth response from non-proxy");
                if (as == null)
                    throw new IOException("Proxy requires authentication");
                if (as.authSent)
                    throw new IOException("Proxy authentication failed");  // ignore stale
                if (_log.shouldLog(Log.INFO)) _log.info("Adding auth");
                // actually happens in getRequest()
            } else {
                _redirects++;
                if (_redirects > 5)
                    throw new IOException("Too many redirects: to " + _redirectLocation);
                if (_log.shouldLog(Log.INFO)) _log.info("Redirecting to " + _redirectLocation);
                if (as != null)
                    as.authSent = false;
            }

            // reset some important variables, we don't want to save the values from the redirect
            _bytesRemaining = -1;
            _redirectLocation = null;
            _etag = null;
            _lastModified = null;
            _contentType = null;
            _encodingChunked = false;

            sendRequest(timeout);
            doFetch(timeout);
            return;
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Headers read completely");
            
        if (_out != null)
            _out.close();
        _out = null;
        
        if (_aborted)
            throw new IOException("Timed out reading the HTTP data");
        
        timeout.cancel();
        
        if (_transferFailed) {
            // 404, etc - transferFailed is called after all attempts fail, by fetch() above
            for (int i = 0; i < _listeners.size(); i++) 
                _listeners.get(i).attemptFailed(_url, 0, 0, _currentAttempt, _numRetries, new Exception("Attempt failed"));
        } else {
            for (int i = 0; i < _listeners.size(); i++) 
                _listeners.get(i).transferComplete(
                        0, 0, 0, _url, "dummy", false);
        }
    }

    @Override
    protected String getRequest() throws IOException {
        StringBuilder buf = new StringBuilder(512);
        URI url;
        try {
            url = new URI(_actualURL);
        } catch (URISyntaxException use) {
            IOException ioe = new MalformedURLException("Bad URL");
            ioe.initCause(use);
            throw ioe;
        }
        String host = url.getHost();
        if (host == null)
            throw new MalformedURLException("Bad URL");
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
        buf.append("HEAD ").append(urlToSend).append(" HTTP/1.1\r\n");
        // RFC 2616 sec 5.1.2 - host + port (NOT authority, which includes userinfo)
        buf.append("Host: ").append(host);
        if (port >= 0)
            buf.append(':').append(port);
        buf.append("\r\n");
        buf.append("Accept-Encoding: \r\n");
        // This will be replaced if we are going through I2PTunnelHTTPClient
        buf.append("User-Agent: " + USER_AGENT + "\r\n");
        if (_authState != null && _shouldProxy && _authState.authMode != AUTH_MODE.NONE) {
            buf.append("Proxy-Authorization: ");
            buf.append(_authState.getAuthHeader("HEAD", urlToSend));
            buf.append("\r\n");
        }
        buf.append("Connection: close\r\n\r\n");
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request: [" + buf.toString() + "]");
        return buf.toString();
    }

    /** We don't decrement the variable (unlike in EepGet), so this is valid */
    public long getContentLength() {
        return _bytesRemaining;
    }
}
