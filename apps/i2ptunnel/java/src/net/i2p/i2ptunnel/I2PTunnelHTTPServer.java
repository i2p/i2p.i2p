/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLException;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.ByteCache;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Simple extension to the I2PTunnelServer that filters the HTTP
 * headers sent from the client to the server, replacing the Host
 * header with whatever this instance has been configured with, and
 * if the browser set Accept-Encoding: x-i2p-gzip, gzip the http 
 * message body and set Content-Encoding: x-i2p-gzip.
 *
 */
public class I2PTunnelHTTPServer extends I2PTunnelServer {

    /** all of these in SECONDS */
    public static final String OPT_POST_WINDOW = "postCheckTime";
    public static final String OPT_POST_BAN_TIME = "postBanTime";
    public static final String OPT_POST_TOTAL_BAN_TIME = "postTotalBanTime";
    public static final String OPT_POST_MAX = "maxPosts";
    public static final String OPT_POST_TOTAL_MAX = "maxTotalPosts";
    public static final String OPT_REJECT_INPROXY = "rejectInproxy";
    public static final String OPT_REJECT_REFERER = "rejectReferer";
    public static final String OPT_REJECT_USER_AGENTS = "rejectUserAgents";
    public static final String OPT_USER_AGENTS = "userAgentRejectList";
    public static final String OPT_KEEPALIVE = "keepalive.i2p";
    public static final int DEFAULT_POST_WINDOW = 5*60;
    public static final int DEFAULT_POST_BAN_TIME = 20*60;
    public static final int DEFAULT_POST_TOTAL_BAN_TIME = 10*60;
    public static final int DEFAULT_POST_MAX = 6;
    public static final int DEFAULT_POST_TOTAL_MAX = 20;
    private static final boolean DEFAULT_KEEPALIVE = true;

    /** what Host: should we seem to be to the webserver? */
    private String _spoofHost;
    private static final String HASH_HEADER = "X-I2P-DestHash";
    private static final String DEST64_HEADER = "X-I2P-DestB64";
    private static final String DEST32_HEADER = "X-I2P-DestB32";
    private static final String PROXY_CONN_HEADER = "proxy-connection";
    /** MUST ALL BE LOWER CASE */
    private static final String[] CLIENT_SKIPHEADERS = {HASH_HEADER.toLowerCase(Locale.US),
                                                        DEST64_HEADER.toLowerCase(Locale.US),
                                                        DEST32_HEADER.toLowerCase(Locale.US),
                                                        PROXY_CONN_HEADER};
    private static final String DATE_HEADER = "date";
    private static final String SERVER_HEADER = "server";
    private static final String X_POWERED_BY_HEADER = "x-powered-by";
    private static final String X_RUNTIME_HEADER = "x-runtime"; // Rails
    // https://httpoxy.org
    private static final String PROXY_HEADER = "proxy";
    /** MUST ALL BE LOWER CASE */
    private static final String[] SERVER_SKIPHEADERS = {DATE_HEADER, SERVER_HEADER, X_POWERED_BY_HEADER, X_RUNTIME_HEADER,
                                                        PROXY_HEADER, PROXY_CONN_HEADER};
    /** timeout for first request line */
    private static final long HEADER_TIMEOUT = 15*1000;
    /** timeout for the rest of the request headers */
    private static final long HEADER_FINISH_TIMEOUT = HEADER_TIMEOUT;
    private static final long START_INTERVAL = (60 * 1000) * 3;
    private static final int MAX_LINE_LENGTH = 8*1024;
    /** ridiculously long, just to prevent OOM DOS @since 0.7.13 */
    private static final int MAX_HEADERS = 60;
    /** Includes request, just to prevent OOM DOS @since 0.9.20 */
    private static final int MAX_TOTAL_HEADER_SIZE = 32*1024;
    // Does not apply to header reads.
    // We set it to forever so that it won't timeout when sending a large response.
    // The server will presumably have its own timeout implemented for POST
    private static final long DEFAULT_HTTP_READ_TIMEOUT = -1;
    // Set a relatively short timeout for GET/HEAD,
    // and a long failsafe timeout for POST/CONNECT, since the user
    // could be POSTing a massive file
    private static final int SERVER_READ_TIMEOUT_GET = 60*1000;
    private static final int SERVER_READ_TIMEOUT_MEDIUM = 5*60*1000;
    private static final int SERVER_READ_TIMEOUT_POST = 4*60*60*1000;
    
    private long _startedOn = 0L;
    private ConnThrottler _postThrottler;

    private final static String ERR_UNAVAILABLE =
         "HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>503 Service Unavailable</title></head>\n"+
         "<body><h2>503 Service Unavailable</h2>\n" +
         "<p>This I2P website is unavailable. It may be down or undergoing maintenance.</p>\n" +
         "</body></html>";

    // TODO https://stackoverflow.com/questions/16022624/examples-of-http-api-rate-limiting-http-response-headers
    private final static String ERR_DENIED =
         "HTTP/1.1 429 Denied\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>429 Denied</title></head>\n"+
         "<body><h2>429 Denied</h2>\n" +
         "<p>Denied due to excessive requests. Please try again later.</p>\n" +
         "</body></html>";

    private final static String ERR_INPROXY =
         "HTTP/1.1 403 Denied\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>403 Denied</title></head>\n"+
         "<body><h2>403 Denied</h2>\n" +
         "<p>Inproxy access denied. You must run <a href=\"https://geti2p.net/\">I2P</a> to access this site.</p>\n" +
         "</body></html>";

/*
    private final static String ERR_SSL =
         "HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>503 Service Unavailable</title></head>\n"+
         "<body><h2>503 Service Unavailable</h2>\n" +
         "<p>This I2P website is not configured for SSL.</p>\n" +
         "</body></html>";
*/

    private final static String ERR_REQUEST_URI_TOO_LONG =
         "HTTP/1.1 414 Request URI too long\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>414 Request URI Too Long</title></head>\n"+
         "<body><h2>414 Request URI too long</h2>\n" +
         "</body></html>";

    private final static String ERR_HEADERS_TOO_LARGE =
         "HTTP/1.1 431 Request header fields too large\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>431 Request Header Fields Too Large</title></head>\n"+
         "<body><h2>431 Request header fields too large</h2>\n" +
         "</body></html>";

    /** @since protected since 0.9.33 for I2PTunnelHTTPClientBase, was private */
    protected final static String ERR_REQUEST_TIMEOUT =
         "HTTP/1.1 408 Request timeout\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>408 Request Timeout</title></head>\n"+
         "<body><h2>408 Request timeout</h2>\n" +
         "</body></html>";

    private final static String ERR_BAD_REQUEST =
         "HTTP/1.1 400 Bad Request\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n"+
         "\r\n"+
         "<html><head><title>400 Bad Request</title></head>\n"+
         "<body><h2>400 Bad request</h2>\n" +
         "</body></html>";


    public I2PTunnelHTTPServer(InetAddress host, int port, String privData, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, File privkey, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privkey, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, InputStream privData, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    private void setupI2PTunnelHTTPServer(String spoofHost) {
        _spoofHost = (spoofHost != null && spoofHost.trim().length() > 0) ? spoofHost.trim() : null;
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpserver.blockingHandleTime", "how long the blocking handle takes to complete", "I2PTunnel.HTTPServer", new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
        readTimeout = DEFAULT_HTTP_READ_TIMEOUT;
    }

    @Override
    public void startRunning() {
        super.startRunning();
        // Would be better if this was set when the inbound tunnel becomes alive.
        _startedOn = getTunnel().getContext().clock().now();
        setupPostThrottle();
    }

    /** @since 0.9.9 */
    private void setupPostThrottle() {
        int pp = getIntOption(OPT_POST_MAX, 0);
        int pt = getIntOption(OPT_POST_TOTAL_MAX, 0);
        synchronized(this) {
            if (pp != 0 || pt != 0 || _postThrottler != null) {
                long pw = 1000L * getIntOption(OPT_POST_WINDOW, DEFAULT_POST_WINDOW);
                long pb = 1000L * getIntOption(OPT_POST_BAN_TIME, DEFAULT_POST_BAN_TIME);
                long px = 1000L * getIntOption(OPT_POST_TOTAL_BAN_TIME, DEFAULT_POST_TOTAL_BAN_TIME);
                if (_postThrottler == null)
                    _postThrottler = new ConnThrottler(pp, pt, pw, pb, px, "POST/PUT", _log);
                else
                    _postThrottler.updateLimits(pp, pt, pw, pb, px);
                _postThrottler.start();
            }
        }
    }

    /** @since 0.9.9 */
    private int getIntOption(String opt, int dflt) {
        Properties opts = getTunnel().getClientOptions();
        String o = opts.getProperty(opt);
        if (o != null) {
            try {
                return Integer.parseInt(o);
            } catch (NumberFormatException nfe) {}
        }
        return dflt;
    }

    /** @since 0.9.9 */
    @Override
    public boolean close(boolean forced) {
        synchronized(this) {
            if (_postThrottler != null)
                _postThrottler.stop();
        }
        return super.close(forced);
    }

    /** @since 0.9.9 */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel)
            return;
        setupPostThrottle();
        Properties props = tunnel.getClientOptions();
        // see TunnelController.setSessionOptions()
        String spoofHost = props.getProperty(TunnelController.PROP_SPOOFED_HOST);
        _spoofHost = (spoofHost != null && spoofHost.trim().length() > 0) ? spoofHost.trim() : null;
        super.optionsUpdated(tunnel);
    }


    /**
     * Called by the thread pool of I2PSocket handlers
     *
     */
    @Override
    protected void blockingHandle(I2PSocket socket) {
        Hash peerHash = socket.getPeerDestination().calculateHash();
        String peerB32 = socket.getPeerDestination().toBase32();
        if (_log.shouldLog(Log.INFO))
            _log.info("Incoming connection to '" + toString() + "' port " + socket.getLocalPort() +
                      " from: " + peerB32 + " port " + socket.getPort());
        //local is fast, so synchronously. Does not need that many
        //threads.
        try {
            if (socket.getLocalPort() == 443) {
                if (getTunnel().getClientOptions().getProperty("targetForPort.443") == null) {
                    try {
                        // can't write non-ssl error message
                        // client side already sent 200 to browser
                        socket.reset();
                    } catch (IOException ioe) {}
                    return;
                }
                // We don't know if this is GET or POST or what, set a huge
                // timeout and rely on the server to do the actual timeout
                socket.setReadTimeout(SERVER_READ_TIMEOUT_POST);
                Socket s = getSocket(socket.getPeerDestination().calculateHash(), 443);
                Runnable t = new I2PTunnelRunner(s, socket, slock, null, null,
                                                 null, (I2PTunnelRunner.FailCallback) null);
                // run in the server pool
                executeInPool(t);
                return;
            }

            long afterAccept = getTunnel().getContext().clock().now();
            int requestCount = 0;
            boolean keepalive = getBooleanOption(OPT_KEEPALIVE, DEFAULT_KEEPALIVE);

          // indent
          do {
          // indent

            if (requestCount > 0) {
                if (_log.shouldInfo())
                    _log.info("Keepalive, awaiting request #" + requestCount);
            }

            // The headers _should_ be in the first packet, but
            // may not be, depending on the client-side options

            StringBuilder command = new StringBuilder(128);
            Map<String, List<String>> headers;
            try {
                // catch specific exceptions thrown, to return a good
                // error to the client
                // Add 10s to client-side timeout so the client will timeout first and minimize races
                long timeout = requestCount > 0 ? I2PTunnelHTTPClient.BROWSER_KEEPALIVE_TIMEOUT + 10*1000 : HEADER_TIMEOUT;
                headers = readHeaders(socket, null, command,
                                      CLIENT_SKIPHEADERS, getTunnel().getContext(), timeout);
            } catch (SocketTimeoutException ste) {
                if (requestCount > 0) {
                    if (_log.shouldDebug())
                         _log.debug("Timeout awaiting request #" + requestCount);
                } else {
                    try {
                        sendError(socket, ERR_REQUEST_TIMEOUT);
                    } catch (IOException ioe) {}
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Error in the HTTP request from " + peerB32, ste);
                }
                try { socket.close(); } catch (IOException ioe) {}
                return;
            } catch (EOFException eofe) {
                if (requestCount > 0) {
                    if (_log.shouldDebug())
                         _log.debug("Client closed awaiting request #" + requestCount);
                } else {
                    try {
                        sendError(socket, ERR_BAD_REQUEST);
                    } catch (IOException ioe) {}
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Error in the HTTP request from " + peerB32, eofe);
                }
                try { socket.close(); } catch (IOException ioe) {}
                return;
            } catch (LineTooLongException ltle) {
                try {
                    sendError(socket, ERR_HEADERS_TOO_LARGE);
                } catch (IOException ioe) {
                } finally {
                     try { socket.close(); } catch (IOException ioe) {}
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error in the HTTP request from " + peerB32, ltle);
                return;
            } catch (RequestTooLongException rtle) {
                try {
                    sendError(socket, ERR_REQUEST_URI_TOO_LONG);
                } catch (IOException ioe) {
                } finally {
                     try { socket.close(); } catch (IOException ioe) {}
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error in the HTTP request from " + peerB32, rtle);
                return;
            } catch (BadRequestException bre) {
                try {
                    sendError(socket, ERR_BAD_REQUEST);
                } catch (IOException ioe) {
                } finally {
                     try { socket.close(); } catch (IOException ioe) {}
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error in the HTTP request from " + peerB32, bre);
                return;
            }
            long afterHeaders = getTunnel().getContext().clock().now();

            Properties opts = getTunnel().getClientOptions();
            if (Boolean.parseBoolean(opts.getProperty(OPT_REJECT_INPROXY)) &&
                (headers.containsKey("X-Forwarded-For") ||
                 headers.containsKey("X-Forwarded-Server") ||
                 headers.containsKey("Forwarded") ||  // RFC 7239
                 headers.containsKey("X-Forwarded-Host"))) {
                if (_log.shouldLog(Log.WARN)) {
                    StringBuilder buf = new StringBuilder();
                    buf.append("Refusing inproxy access: ").append(peerB32);
                    List<String> h = headers.get("X-Forwarded-For");
                    if (h != null)
                        buf.append(" from: ").append(h.get(0));
                    h = headers.get("X-Forwarded-Server");
                    if (h != null)
                        buf.append(" via: ").append(h.get(0));
                    h = headers.get("X-Forwarded-Host");
                    if (h != null)
                        buf.append(" for: ").append(h.get(0));
                    h = headers.get("Forwarded");
                    if (h != null)
                        buf.append(h.get(0));
                    _log.warn(buf.toString());
                }
                try {
                    // Send a 403, so the user doesn't get an HTTP Proxy error message
                    // and blame his router or the network.
                    sendError(socket, ERR_INPROXY);
                } catch (IOException ioe) {}
                try {
                    socket.close();
                } catch (IOException ioe) {}
                return;
            }

            if (Boolean.parseBoolean(opts.getProperty(OPT_REJECT_REFERER))) {
                // reject absolute URIs only
                List<String> h = headers.get("Referer");
                if (h != null) {
                    String referer = h.get(0);
                    if (referer.length() > 9) {
                        // "Referer: "
                        referer = referer.substring(9);
                        if (referer.startsWith("http://") || referer.startsWith("https://")) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Refusing access from: " + peerB32 +
                                          " with Referer: " + referer);
                            try {
                                sendError(socket, ERR_INPROXY);
                            } catch (IOException ioe) {}
                            try {
                                socket.close();
                            } catch (IOException ioe) {}
                            return;
                        }
                    }
                }
            }

            if (Boolean.parseBoolean(opts.getProperty(OPT_REJECT_USER_AGENTS))) {
                if (headers.containsKey("User-Agent")) {
                    String ua = headers.get("User-Agent").get(0);
                    if (!ua.startsWith("MYOB")) {
                        String blockAgents = opts.getProperty(OPT_USER_AGENTS);
                        if (blockAgents != null) {
                            String[] agents = DataHelper.split(blockAgents, ",");
                            for (int i = 0; i < agents.length; i++) {
                                String ag = agents[i].trim();
                                if (ag.equals("none"))
                                    continue;
                                if (ag.length() > 0 && ua.contains(ag)) {
                                    if (_log.shouldLog(Log.WARN))
                                        _log.warn("Refusing access from: " + peerB32 +
                                                  " with User-Agent: " + ua);
                                    try {
                                        sendError(socket, ERR_INPROXY);
                                    } catch (IOException ioe) {}
                                    try {
                                        socket.close();
                                    } catch (IOException ioe) {}
                                    return;
                                }
                            }
                        }
                    }
                } else {
                    // no user-agent, block if blocklist contains "none"
                    String blockAgents = opts.getProperty(OPT_USER_AGENTS);
                    if (blockAgents != null) {
                        String[] agents = DataHelper.split(blockAgents, ",");
                        for (int i = 0; i < agents.length; i++) {
                            String ag = agents[i].trim();
                            if (ag.equals("none")) {
                                if (_log.shouldLog(Log.WARN))
                                    _log.warn("Refusing access from: " + peerB32 +
                                              " with empty User-Agent");
                                try {
                                    sendError(socket, ERR_INPROXY);
                                } catch (IOException ioe) {}
                                try {
                                    socket.close();
                                } catch (IOException ioe) {}
                                return;
                            }
                        }
                    }
                }
            }

            if (_postThrottler != null &&
                command.length() >= 5 &&
                (command.substring(0, 5).toUpperCase(Locale.US).equals("POST ") ||
                 command.substring(0, 4).toUpperCase(Locale.US).equals("PUT "))) {
                if (_postThrottler.shouldThrottle(peerHash)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Refusing POST/PUT since peer is throttled: " + peerB32);
                    try {
                        // Send a 429, so the user doesn't get an HTTP Proxy error message
                        // and blame his router or the network.
                        sendError(socket, ERR_DENIED);
                    } catch (IOException ioe) {}
                    try {
                        socket.close();
                    } catch (IOException ioe) {}
                    return;
                }
            }
            
            addEntry(headers, HASH_HEADER, peerHash.toBase64());
            addEntry(headers, DEST32_HEADER, peerB32);
            addEntry(headers, DEST64_HEADER, socket.getPeerDestination().toBase64());

            // Port-specific spoofhost
            String spoofHost;
            int ourPort = socket.getLocalPort();
            if (ourPort != 80 && ourPort > 0 && ourPort <= 65535) {
                String portSpoof = opts.getProperty("spoofedHost." + ourPort);
                if (portSpoof != null)
                    spoofHost = portSpoof.trim();
                else
                    spoofHost = _spoofHost;
            } else {
                spoofHost = _spoofHost;
            }
            if (spoofHost != null)
                setEntry(headers, "Host", spoofHost);

            // Force Connection: close, unless websocket
            boolean upgrade = false;
            String conn = getEntryOrNull(headers, "Connection");
            if (conn == null) {
                setEntry(headers, "Connection", "close");
            } else {
                String connlc = conn.toLowerCase(Locale.US);
                if (connlc.contains("upgrade")) {
                    upgrade = true;
                    keepalive = false;
                } else {
                    if (!connlc.contains("keep-alive"))
                        keepalive = false;
                    setEntry(headers, "Connection", "close");
                }
            }

            // HTTP Persistent Connections (RFC 2616)
            // for the I2P socket.
            // Keep it very simple.
            // Will be set to false for non-GET/HEAD, non-HTTP/1.1,
            // Connection: close, InternalSocket,
            // or after analysis of the response headers in CompressedOutputStream,
            // or on errors in I2PTunnelRunner.
            // We do NOT support keepalive on the server socket.
            String cmd = command.toString().trim();
            boolean isGetOrHead = cmd.startsWith("GET ") || cmd.startsWith("HEAD ");
            if (!cmd.endsWith(" HTTP/1.1") || !isGetOrHead) {
                keepalive = false;
            }

            // we keep the enc sent by the browser before clobbering it, since it may have 
            // been x-i2p-gzip
            String enc = getEntryOrNull(headers, "Accept-Encoding");
            String altEnc = getEntryOrNull(headers, "X-Accept-Encoding");
            
            // according to rfc2616 s14.3, this *should* force identity, even if
            // "identity;q=1, *;q=0" didn't.  
            // as of 0.9.23, the client passes this header through, and we do the same,
            // so if the server and browser can do the compression/decompression, we don't have to
            //setEntry(headers, "Accept-Encoding", ""); 

            socket.setReadTimeout(readTimeout);
            Socket s = getSocket(socket.getPeerDestination().calculateHash(), socket.getLocalPort());
            long afterSocket = getTunnel().getContext().clock().now();
            // instead of i2ptunnelrunner, use something that reads the HTTP 
            // request from the socket, modifies the headers, sends the request to the 
            // server, reads the response headers, rewriting to include Content-Encoding: x-i2p-gzip
            // if it was one of the Accept-Encoding: values, and gzip the payload       
            boolean allowGZIP = true;
            String val = opts.getProperty(TunnelController.PROP_TUN_GZIP);
            if ( (val != null) && (!Boolean.parseBoolean(val)) ) 
                allowGZIP = false;
            if (_log.shouldDebug())
                _log.debug("HTTP server encoding header: " + enc + "/" + altEnc);
            boolean alt = (altEnc != null) && (altEnc.indexOf("x-i2p-gzip") >= 0);
            boolean useGZIP = alt || ( (enc != null) && (enc.indexOf("x-i2p-gzip") >= 0) );
            // Don't pass this on, outproxies should strip so I2P traffic isn't so obvious but they probably don't
            if (alt)
                headers.remove("X-Accept-Encoding");

            String modifiedHeader = formatHeaders(headers, command);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Modified headers: [\n" + modifiedHeader + "]");

            boolean compress = allowGZIP && useGZIP;
            // waiter is set to the return value when the CompressedRequestor is done
            AtomicInteger waiter = keepalive ? new AtomicInteger() : null;
            Runnable t = new CompressedRequestor(s, socket, modifiedHeader, getTunnel().getContext(),
                                                 _log, compress, upgrade, _clientExecutor, keepalive, waiter);
            if (keepalive || isGetOrHead) {
                // run inline
                t.run();
            } else {
                // run in the server pool
                executeInPool(t);
            }

            long afterHandle = getTunnel().getContext().clock().now();
            if (requestCount == 0) {
                long timeToHandle = afterHandle - afterAccept;
                getTunnel().getContext().statManager().addRateData("i2ptunnel.httpserver.blockingHandleTime", timeToHandle);
                if ( (timeToHandle > 1000) && (_log.shouldLog(Log.WARN)) )
                    _log.warn("Took a while to handle the request for " + remoteHost + ':' + remotePort +
                              " from: " + peerB32 +
                              " [" + timeToHandle +
                              ", read headers: " + (afterHeaders-afterAccept) +
                              ", socket create: " + (afterSocket-afterHeaders) +
                              ", start runners: " + (afterHandle-afterSocket) +
                              "]");
            }

            if (keepalive) {
                // wait for the response to finish, then determine
                // if we can receive another request on this socket

             /*
                Since we are now running the CompressedRequestor inline
                if keepalive is true, we don't need to wait.

                if (_log.shouldDebug())
                    _log.debug("Waiting for response " + requestCount + " to finish");
                try {
                    synchronized(waiter) {
                        if (waiter.get() == 0)
                            waiter.wait(30*1000);
                    }
                } catch (InterruptedException ie) {
                    if (_log.shouldWarn())
                        _log.warn("Interrupted waiting for response to finish");
                    break;
                }
             */

                if (_log.shouldInfo()) {
                    long timeToWait = getTunnel().getContext().clock().now() - afterAccept;
                    _log.info("Waited " + timeToWait + " for response " + requestCount + " to complete, code: " + waiter);
                }
                // 0: not done; 1: not keepalive-able response; 2: keepalive
                if (waiter.get() != 2)
                    break;
            }

            // go around again
            requestCount++;

          // indent
          } while (keepalive);
          // indent

        } catch (SocketException ex) {
            int port = socket.getLocalPort();
            try {
                // Send a 503, so the user doesn't get an HTTP Proxy error message
                // and blame his router or the network.
                sendError(socket, ERR_UNAVAILABLE);
            } catch (IOException ioe) {}
            try {
                socket.close();
            } catch (IOException ioe) {}
            // Don't complain too early, Jetty may not be ready.
            int level = getTunnel().getContext().clock().now() - _startedOn > START_INTERVAL ? Log.ERROR : Log.WARN;
            if (_log.shouldLog(level))
                _log.log(level, "Error connecting to HTTP server " + getSocketString(port));
        } catch (IOException ex) {
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error in the HTTP request from: " + peerB32, ex);
        } catch (OutOfMemoryError oom) {
            // Often actually a file handle limit problem so we can safely send a response
            // java.lang.OutOfMemoryError: unable to create new native thread
            try {
                // Send a 503, so the user doesn't get an HTTP Proxy error message
                // and blame his router or the network.
                sendError(socket, ERR_UNAVAILABLE);
            } catch (IOException ioe) {}
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.ERROR))
                _log.error("OOM in HTTP server", oom);
        }
    }

    /**
     *  Send the message, unless port 443, then just reset
     *  @since 0.9.62
     */
    private static void sendError(I2PSocket socket, String resp) throws IOException {
        if (socket.getLocalPort() == 443)
            socket.reset();
        else
            socket.getOutputStream().write(resp.getBytes("UTF-8"));
    }
    
    private static class CompressedRequestor implements Runnable {
        private final Socket _webserver;
        private final I2PSocket _browser;
        private final String _headers;
        private final I2PAppContext _ctx;
        // shadows _log in super()
        private final Log _log;
        private final boolean _shouldCompress;
        private final boolean _upgrade;
        private final ThreadPoolExecutor _tpe;
        private boolean _keepalive;
        private final AtomicInteger _waiter;

        private static final int BUF_SIZE = 8*1024;

        /**
         *  @param shouldCompress if false, don't compress, just filter server headers
         *  @param waiter to notify when done, if non-null; will set value to 1: not keepalive-able response, or 2: keepalive
         */
        public CompressedRequestor(Socket webserver, I2PSocket browser, String headers,
                                   I2PAppContext ctx, Log log, boolean shouldCompress, boolean upgrade,
                                   ThreadPoolExecutor tpe, boolean keepalive, AtomicInteger waiter) {
            _webserver = webserver;
            _browser = browser;
            _headers = headers;
            _ctx = ctx;
            _log = log;
            _shouldCompress = shouldCompress;
            _upgrade = upgrade;
            _tpe = tpe;
            _keepalive = keepalive;
            _waiter = waiter;
        }

        /**
         *  This thread handles the response from the server back to the browser.
         *  If the request was not GET or HEAD, (typically POST or CONNECT),
         *  it spawns another thread "Sender" to push the remaining request data from the browser to the server.
         *
         */
        public void run() {
            OutputStream serverout = null;
            OutputStream browserout = null;
            CompressedResponseOutputStream compressedout = null;
            InputStream browserin = null;
            InputStream serverin = null;
            Sender s = null;
            Sender sender = null;
            IOException ioex = null;
            try {
                serverout = _webserver.getOutputStream();
                
                serverout.write(DataHelper.getUTF8(_headers));
                browserin = _browser.getInputStream();
                // Don't spin off a thread for this except for POSTs and PUTs and Connection: Upgrade
                // beware interference with Shoutcast, etc.?
                boolean isHead = _headers.startsWith("HEAD ");
                boolean isGet = _headers.startsWith("GET ");
                boolean isPost = _headers.startsWith("POST ");
                if (!(isGet || isHead) ||
                    _upgrade ||
                    browserin.available() > 0) {  // just in case
                    // Unless this is POST, set a huge
                    // timeout and rely on the server to do the actual timeout
                    _browser.setReadTimeout(isPost ?
                                            SERVER_READ_TIMEOUT_MEDIUM :   // medium
                                            SERVER_READ_TIMEOUT_POST);     // long
                    _keepalive = false;
                    sender = new Sender(serverout, browserin, "server: browser to server", _log);
                    // run in the unlimited client pool
                    _tpe.execute(sender);
                }
                int timeout = (isGet || isHead) ?
                              SERVER_READ_TIMEOUT_GET :   // short
                              SERVER_READ_TIMEOUT_POST;   // long
                _webserver.setSoTimeout(timeout);
                browserout = _browser.getOutputStream();
                // NPE seen here in 0.7-7, caused by addition of socket.close() in the
                // catch (IOException ioe) block above in blockingHandle() ???
                // CRIT  [ad-130280.hc] net.i2p.util.I2PThread        : Killing thread Thread-130280.hc
                // java.lang.NullPointerException
                //     at java.io.FileInputStream.<init>(FileInputStream.java:131)
                //     at java.net.SocketInputStream.<init>(SocketInputStream.java:44)
                //     at java.net.PlainSocketImpl.getInputStream(PlainSocketImpl.java:401)
                //     at java.net.Socket$2.run(Socket.java:779)
                //     at java.security.AccessController.doPrivileged(Native Method)
                //     at java.net.Socket.getInputStream(Socket.java:776)
                //     at net.i2p.i2ptunnel.I2PTunnelHTTPServer$CompressedRequestor.run(I2PTunnelHTTPServer.java:174)
                //     at java.lang.Thread.run(Thread.java:619)
                //     at net.i2p.util.I2PThread.run(I2PThread.java:71)
                try {
                    serverin = new BufferedInputStream(_webserver.getInputStream(), BUF_SIZE);
                } catch (NullPointerException npe) {
                    throw new IOException("getInputStream NPE");
                }

                //Change headers to protect server identity
                StringBuilder command = new StringBuilder(128);
                Map<String, List<String>> headers = readHeaders(null, serverin, command,
                    SERVER_SKIPHEADERS, _ctx, timeout);
                String modifiedHeaders = formatHeaders(headers, command);
                // after the headers, set a short timeout
                _webserver.setSoTimeout(SERVER_READ_TIMEOUT_GET);

                if (_shouldCompress) {
                    compressedout = new CompressedResponseOutputStream(browserout, _keepalive);
                    compressedout.write(DataHelper.getUTF8(modifiedHeaders));
                    s = new Sender(compressedout, serverin, "server: server to browser compressor", _log);
                    browserout = compressedout;
                } else {
                    browserout.write(DataHelper.getUTF8(modifiedHeaders));
                    s = new Sender(browserout, serverin, "server: server to browser uncompressed", _log);
                }
                if (_log.shouldInfo())
                    _log.info("Running server-to-browser: compressed? " + _shouldCompress + " keepalive? " + _keepalive);
                s.run(); // same thread
            } catch (SSLException she) {
                _log.error("SSL error", she);
                try {
                    if (_browser.getLocalPort() == 443) {
                        _browser.reset();
                    } else {
                        if (browserout == null)
                            browserout = _browser.getOutputStream();
                        browserout.write(ERR_UNAVAILABLE.getBytes("UTF-8"));
                    }
                } catch (IOException ioe) {}
                _keepalive = false;
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("error compressing", ioe);
                ioex = ioe;
                _keepalive = false;
            } finally {
                if (ioex == null && s != null) {
                    ioex = s.getFailure();
                    if (ioex == null && sender != null)
                        ioex = sender.getFailure();
                }
                if (ioex != null) {
                    _keepalive = false;
                    // Reset propagation, simplified from I2PTunnelRunner
                    boolean i2pReset = false;
                    if (ioex instanceof I2PSocketException) {
                        I2PSocketException ise = (I2PSocketException) ioex;
                        int status = ise.getStatus();
                        i2pReset = status == I2PSocketException.STATUS_CONNECTION_RESET;
                        if (i2pReset) {
                            if (_log.shouldWarn())
                                _log.warn("Server got I2P reset, resetting socket", ioex);
                            try { 
                                _webserver.setSoLinger(true, 0);
                            } catch (IOException ioe) {}
                        }
                    }
                    if (!i2pReset && ioex instanceof SocketException) {
                        String msg = ioex.getMessage();
                        boolean sockReset = msg != null && msg.contains("reset");
                        if (sockReset) {
                            if (_log.shouldWarn())
                                _log.warn("Server got socket reset, resetting I2P socket");
                            try { 
                                _browser.reset();
                            } catch (IOException ioe) {}
                        }
                    }
                }
                if (_waiter != null) {
                    // We are now run inline, no need to notify()
                    //synchronized(_waiter) {
                        _waiter.set(_keepalive ? 2 : 1);
                    //    _waiter.notify();
                    //}
                }
                if (browserout != null) {
                    try {
                        if (_keepalive) {
                            if (compressedout != null)
                                compressedout.finish();
                            else
                                browserout.flush();
                        } else {
                            browserout.close();
                        }
                    } catch (IOException ioe) {}
                }
                if (serverout != null) try { serverout.close(); } catch (IOException ioe) {}
                if (!_keepalive && browserin != null) try { browserin.close(); } catch (IOException ioe) {}
                if (serverin != null) try { serverin.close(); } catch (IOException ioe) {}
                try { _webserver.close(); } catch (IOException ioe) {}
                if (!_keepalive) try { _browser.close(); } catch (IOException ioe) {}
                if (_log.shouldInfo())
                    _log.info("Finished server-to-browser: compressed? " + _shouldCompress + " keepalive? " + _keepalive);
            }
        }
    }

    private static class Sender implements Runnable {
        private final OutputStream _out;
        private final InputStream _in;
        private final String _name;
        // shadows _log in super()
        private final Log _log;
        private IOException _failure;

        /**
         *  Caller MUST close streams
         */
        public Sender(OutputStream out, InputStream in, String name, Log log) {
            _out = out;
            _in = in;
            _name = name;
            _log = log;
        }

        public void run() {
            if (_log.shouldDebug())
                _log.debug(_name + ": Begin sending");
            try {
                DataHelper.copy(_in, _out);
                if (_log.shouldDebug())
                    _log.debug(_name + ": Done sending");
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(_name + " Error sending", ioe);
                synchronized(this) {
                    _failure = ioe;
                }
            }
        }

        /**
         *  @since 0.9.33
         */
        public synchronized IOException getFailure() {
            return _failure;
        }
    }

    /**
     *  This plus a typ. HTTP response header will fit into a 1730-byte streaming message.
     */
    private static final int MIN_TO_COMPRESS = 1300;

    private static class CompressedResponseOutputStream extends HTTPResponseOutputStream {
        private InternalGZIPOutputStream _gzipOut;

        public CompressedResponseOutputStream(OutputStream o, boolean keepalive) {
            super(o, false, keepalive, false, null);
        }

        /**
         *  Finish gzipping but don't close the output stream,
         *  if keepalive is true.
         *
         *  @since 0.9.62
         */
        public void finish() throws IOException {
            if (getKeepAliveOut()) {
                if (_gzipOut != null)
                    _gzipOut.finish();
                else
                     flush();
            } else {
                close();
            }
        }
        
        /**
         *  Don't compress small responses or images.
         *  Don't compress things that are already compressed.
         *  Compression is inline, and decompression on the client side is now also,
         *  but it's still CPU.
         */
        @Override
        protected boolean shouldCompress() {
            return (_dataExpected < 0 || _dataExpected >= MIN_TO_COMPRESS) &&
                   // must be null as we write the header in finishHeaders(), can't have two
                   (_contentEncoding == null) &&
                   (_contentType == null ||
                    ((!_contentType.startsWith("audio/")) &&
                     (!_contentType.startsWith("image/")) &&
                     (!_contentType.startsWith("video/")) &&
                     (!_contentType.equals("application/compress")) &&
                     (!_contentType.equals("application/bzip2")) &&
                     (!_contentType.equals("application/gzip")) &&
                     (!_contentType.equals("application/x-bzip")) &&
                     (!_contentType.equals("application/x-bzip2")) &&
                     (!_contentType.equals("application/x-gzip")) &&
                     (!_contentType.equals("application/zip"))));
        }

        @Override
        protected void finishHeaders() throws IOException {
            // TODO if browser supports gzip, send as gzip
            if (shouldCompress())
                out.write(DataHelper.getASCII("Content-Encoding: x-i2p-gzip\r\n"));
            super.finishHeaders();
        }

        @Override
        protected void beginProcessing() throws IOException {
            //if (_log.shouldLog(Log.INFO))
            //    _log.info("Beginning compression processing");
            //out.flush();
            if (shouldCompress()) {
                _gzipOut = new InternalGZIPOutputStream(out);
                out = _gzipOut;
            }
        }

        public long getTotalRead() { 
            InternalGZIPOutputStream gzipOut = _gzipOut;
            if (gzipOut != null)
                return gzipOut.getTotalRead();
            else
                return 0;
        }

        public long getTotalCompressed() { 
            InternalGZIPOutputStream gzipOut = _gzipOut;
            if (gzipOut != null)
                return gzipOut.getTotalCompressed();
            else
                return 0;
        }
    }

    /** just a wrapper to provide stats for debugging */
    private static class InternalGZIPOutputStream extends GZIPOutputStream {
        public InternalGZIPOutputStream(OutputStream target) throws IOException {
            super(target);
        }
        public long getTotalRead() { 
            try {
                return def.getTotalIn();
            } catch (RuntimeException e) {
                // j2se 1.4.2_08 on linux is sometimes throwing an NPE in the getTotalIn() implementation
                return 0; 
            }
        }
        public long getTotalCompressed() { 
            try {
                return def.getTotalOut();
            } catch (RuntimeException e) {
                // j2se 1.4.2_08 on linux is sometimes throwing an NPE in the getTotalOut() implementation
                return 0;
            }
        }
    }

    /**
     *  @return the command followed by the header lines
     */
    protected static String formatHeaders(Map<String, List<String>> headers, StringBuilder command) {
        StringBuilder buf = new StringBuilder(command.length() + headers.size() * 64);
        buf.append(command.toString().trim()).append("\r\n");
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey();
            for(String val: e.getValue()) {
                buf.append(name.trim()).append(": ").append(val.trim()).append("\r\n");
            }
        }
        buf.append("\r\n");
        return buf.toString();
    }
    
    /**
     * Add an entry to the multimap.
     */
    private static void addEntry(Map<String, List<String>> headers, String key, String value) {
        List<String> entry = headers.get(key);
        if (entry == null) {
            headers.put(key, entry = new ArrayList<String>(1));
        }
        entry.add(value);    	
    }
    
    /**
     * Remove the other matching entries and set this entry as the only one.
     */
    private static void setEntry(Map<String, List<String>> headers, String key, String value) {
    	List<String> entry = headers.get(key);
    	if (entry == null) {
    	    headers.put(key, entry = new ArrayList<String>(1));
    	} else {
            entry.clear();
    	}
    	entry.add(value);
    }
    
    /**
     * Get the first matching entry in the multimap
     * @return the first matching entry or null
     */
    private static String getEntryOrNull(Map<String, List<String>> headers, String key) {
    	List<String> entries = headers.get(key);
    	if(entries == null || entries.size() < 1) {
    		return null;
    	}
    	else {
    		return entries.get(0);
    	}
    }

    /**
     *  From I2P to server: socket non-null, in null.
     *  From server to I2P: socket null, in non-null.
     *
     *  Note: This does not handle RFC 2616 header line splitting,
     *  which is obsoleted in RFC 7230.
     *
     *  @param socket if null, use in as InputStream
     *  @param in if null, use socket.getInputStream() as InputStream
     *  @param command out parameter, first line
     *  @param skipHeaders MUST be lower case
     *  @throws SocketTimeoutException if timeout is reached before newline
     *  @throws EOFException if EOF is reached before newline
     *  @throws LineTooLongException if one header too long, or too many headers, or total size too big
     *  @throws RequestTooLongException if too long
     *  @throws BadRequestException on bad headers
     *  @throws IOException on other errors in the underlying stream
     *  @since public since 0.9.57 for SOCKS
     */
    public static Map<String, List<String>> readHeaders(I2PSocket socket, InputStream in, StringBuilder command,
                                                        String[] skipHeaders, I2PAppContext ctx, long initialTimeout) throws IOException {
    	HashMap<String, List<String>> headers = new HashMap<String, List<String>>();
        StringBuilder buf = new StringBuilder(128);
        
        // slowloris / darkloris
        long expire = ctx.clock().now() + initialTimeout + HEADER_FINISH_TIMEOUT;
        if (socket != null) {
            try {
                readLine(socket, command, initialTimeout);
            } catch (LineTooLongException ltle) {
                // convert for first line
                throw new RequestTooLongException("Request too long - max " + MAX_LINE_LENGTH);
            }
        } else {
             boolean ok = DataHelper.readLine(in, command);
             if (!ok)
                 throw new EOFException("EOF reached before the end of the headers");
        }
        
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Read the http command [" + command.toString() + "]");
        
        int totalSize = command.length();
        int i = 0;
        while (true) {
            if (++i > MAX_HEADERS) {
                throw new LineTooLongException("Too many header lines - max " + MAX_HEADERS);
            }
            buf.setLength(0);
            if (socket != null) {
                readLine(socket, buf, expire - ctx.clock().now());
            } else {
                 boolean ok = DataHelper.readLine(in, buf);
                 if (!ok)
                     throw new BadRequestException("EOF reached before the end of the headers");
            }
            if ( (buf.length() == 0) || 
                 ((buf.charAt(0) == '\n') || (buf.charAt(0) == '\r')) ) {
                // end of headers reached
                return headers;
            } else {
                if (ctx.clock().now() > expire) {
                    throw new SocketTimeoutException("Headers took too long");
                }
                int split = buf.indexOf(":");
                if (split <= 0)
                    throw new BadRequestException("Invalid HTTP header, missing colon: \"" + buf +
                                                  "\" request: \"" + command + '"');
                totalSize += buf.length();
                if (totalSize > MAX_TOTAL_HEADER_SIZE)
                    throw new LineTooLongException("Req+headers too big");
                String name = buf.substring(0, split).trim();
                String value = null;
                if (buf.length() > split + 1)
                    value = buf.substring(split+1).trim(); // ":"
                else
                    value = "";

                String lcName = name.toLowerCase(Locale.US);
                if ("accept-encoding".equals(lcName))
                    name = "Accept-Encoding";
                else if ("x-accept-encoding".equals(lcName))
                    name = "X-Accept-Encoding";
                else if ("x-forwarded-for".equals(lcName))
                    name = "X-Forwarded-For";
                else if ("x-forwarded-server".equals(lcName))
                    name = "X-Forwarded-Server";
                else if ("x-forwarded-host".equals(lcName))
                    name = "X-Forwarded-Host";
                else if ("forwarded".equals(lcName))
                    name = "Forwarded";
                else if ("user-agent".equals(lcName))
                    name = "User-Agent";
                else if ("referer".equals(lcName))
                    name = "Referer";
                else if ("connection".equals(lcName))
                    name = "Connection";
                else if ("host".equals(lcName))
                    name = "Host";

                // For incoming, we remove certain headers to prevent spoofing.
                // For outgoing, we remove certain headers to improve anonymity.
                boolean skip = false;
                for (String skipHeader: skipHeaders) {
                    if (skipHeader.equals(lcName)) {
                        skip = true;
                        break;
                    }
                }
                if(skip) {
                    continue;
                }

                addEntry(headers, name, value);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Read the header [" + name + "] = [" + value + "]");
            }
        }
    }

    /**
     *  Read a line terminated by newline, with a total read timeout.
     *
     *  Warning - strips \n but not \r
     *  Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     *  Warning - not UTF-8
     *
     *  @param buf output
     *  @param timeout throws SocketTimeoutException immediately if zero or negative
     *  @throws SocketTimeoutException if timeout is reached before newline
     *  @throws EOFException if EOF is reached before newline
     *  @throws LineTooLongException if too long
     *  @throws IOException on other errors in the underlying stream
     *  @since 0.9.19 modified from DataHelper
     */
    private static void readLine(I2PSocket socket, StringBuilder buf, long timeout) throws IOException {
        if (timeout <= 0)
            throw new SocketTimeoutException();
        long expires = System.currentTimeMillis() + timeout;
        InputStream in = socket.getInputStream();
        int c;
        int i = 0;
        socket.setReadTimeout(timeout);
        while ( (c = in.read()) != -1) {
            if (++i > MAX_LINE_LENGTH)
                throw new LineTooLongException("Line too long - max " + MAX_LINE_LENGTH);
            if (c == '\n')
                break;
            long newTimeout = expires - System.currentTimeMillis();
            if (newTimeout <= 0)
                throw new SocketTimeoutException();
            buf.append((char)c);
            if (newTimeout != timeout) {
                timeout = newTimeout;
                socket.setReadTimeout(timeout);
            }
        }
        if (c == -1) {
            if (System.currentTimeMillis() >= expires)
                throw new SocketTimeoutException();
            else
                throw new EOFException();
        }
    }

    /**
     *  @since 0.9.19
     */
    private static class LineTooLongException extends IOException {
        public LineTooLongException(String s) {
            super(s);
        }
    }

    /**
     *  @since 0.9.20
     */
    private static class RequestTooLongException extends IOException {
        public RequestTooLongException(String s) {
            super(s);
        }
    }

    /**
     *  @since 0.9.20
     */
    private static class BadRequestException extends IOException {
        public BadRequestException(String s) {
            super(s);
        }
    }
}

