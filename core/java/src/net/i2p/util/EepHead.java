package net.i2p.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

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
        String url = null;
        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-p")) {
                    proxyHost = args[i+1].substring(0, args[i+1].indexOf(':'));
                    String port = args[i+1].substring(args[i+1].indexOf(':')+1);
                    proxyPort = Integer.parseInt(port);
                    i++;
                } else if (args[i].equals("-n")) {
                    numRetries = Integer.parseInt(args[i+1]);
                    i++;
                } else if (args[i].equals("-t")) {
                    inactivityTimeout = 1000 * Integer.parseInt(args[i+1]);
                    i++;
                } else if (args[i].startsWith("-")) {
                    usage();
                    return;
                } else {
                    url = args[i];
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            usage();
            return;
        }
        
        if (url == null) {
            usage();
            return;
        }

        EepHead get = new EepHead(I2PAppContext.getGlobalContext(), proxyHost, proxyPort, numRetries, url);
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
        System.err.println("EepHead [-p 127.0.0.1:4444] [-n #retries] [-t timeout] url");
    }
    
    /** return true if the URL was completely retrieved */
    @Override
    protected void doFetch(SocketTimeout timeout) throws IOException {
        _headersRead = false;
        _aborted = false;
        try {
            readHeaders();
        } finally {
            _headersRead = true;
        }
        if (_aborted)
            throw new IOException("Timed out reading the HTTP headers");
        
        timeout.resetTimer();
        if (_fetchInactivityTimeout > 0)
            timeout.setInactivityTimeout(_fetchInactivityTimeout);
        else
            timeout.setInactivityTimeout(60*1000);
        
        // Should we even follow redirects for HEAD?
        if (_redirectLocation != null) {
            //try {
                if (_redirectLocation.startsWith("http://")) {
                    _actualURL = _redirectLocation;
                } else { 
                    // the Location: field has been required to be an absolute URI at least since
                    // RFC 1945 (HTTP/1.0 1996), so it isn't clear what the point of this is.
                    // This oddly adds a ":" even if no port, but that seems to work.
                    URL url = new URL(_actualURL);
		    if (_redirectLocation.startsWith("/"))
                        _actualURL = "http://" + url.getHost() + ":" + url.getPort() + _redirectLocation;
                    else
                        // this blows up completely on a redirect to https://, for example
                        _actualURL = "http://" + url.getHost() + ":" + url.getPort() + "/" + _redirectLocation;
                }
            // an MUE is an IOE
            //} catch (MalformedURLException mue) {
            //    throw new IOException("Redirected from an invalid URL");
            //}
            _redirects++;
            if (_redirects > 5)
                throw new IOException("Too many redirects: to " + _redirectLocation);
            if (_log.shouldLog(Log.INFO)) _log.info("Redirecting to " + _redirectLocation);

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
        URL url = new URL(_actualURL);
        String proto = url.getProtocol();
        String host = url.getHost();
        int port = url.getPort();
        String path = url.getPath();
        String query = url.getQuery();
        if (query != null)
            path = path + "?" + query;
        if (!path.startsWith("/"))
	    path = "/" + path;
        if ( (port == 80) || (port == 443) || (port <= 0) ) path = proto + "://" + host + path;
        else path = proto + "://" + host + ":" + port + path;
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Requesting " + path);
        buf.append("HEAD ").append(_actualURL).append(" HTTP/1.1\r\n");
        buf.append("Host: ").append(url.getHost()).append("\r\n");
        buf.append("Accept-Encoding: \r\n");
        // This will be replaced if we are going through I2PTunnelHTTPClient
        buf.append("User-Agent: " + USER_AGENT + "\r\n");
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
