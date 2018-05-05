/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.Outproxy;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.i2ptunnel.localServer.LocalHTTPServer;
import net.i2p.util.ConvertToHash;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

/**
 * Act as a mini HTTP proxy, handling various different types of requests,
 * forwarding them through I2P appropriately, and displaying the reply.  Supported
 * request formats are: <pre>
 *   $method http://$site[$port]/$path $protocolVersion
 * or
 *   $method $path $protocolVersion\nHost: $site
 * or
 *   $method http://i2p/$b64key/$path $protocolVersion
 * or
 *   $method /$site/$path $protocolVersion
 * or (deprecated)
 *   $method /eepproxy/$site/$path $protocolVersion
 * </pre>
 *
 * CONNECT (https) supported as of release 0.9.11.
 *
 * Note that http://i2p/$b64key/... and /eepproxy/$site/... are not recommended
 * in browsers or other user-visible applications, as relative links will not
 * resolve correctly, cookies won't work, etc.
 *
 * Note that http://$b64key/... and http://$b64key.i2p/... are NOT supported, as
 * a b64 key may contain '=' and '~', both of which are illegal host name characters.
 * Rewrite as http://i2p/$b64key/...
 *
 * If the $site resolves with the I2P naming service, then it is directed towards
 * that eepsite, otherwise it is directed towards this client's outproxy (typically
 * "squid.i2p").  Only HTTP and HTTPS are supported (no ftp, mailto, etc).  Both GET
 * and POST have been tested, though other $methods should work.
 *
 */
public class I2PTunnelHTTPClient extends I2PTunnelHTTPClientBase implements Runnable {

    /**
     *  Map of host name to base64 destination for destinations collected
     *  via address helper links
     */
    private final ConcurrentHashMap<String, String> addressHelpers = new ConcurrentHashMap<String, String>(8);

    /**
     *  Used to protect actions via http://proxy.i2p/
     */
    private final String _proxyNonce;

    public static final String AUTH_REALM = "I2P HTTP Proxy";
    private static final String UA_I2P = "User-Agent: " +
                                         "MYOB/6.66 (AN/ON)" +
                                         "\r\n";
    // ESR version of Firefox, same as Tor Browser
    private static final String UA_CLEARNET = "User-Agent: " +
                                              "Mozilla/5.0 (Windows NT 6.1; rv:52.0) Gecko/20100101 Firefox/52.0" +
                                              "\r\n";

    /**
     *  These are backups if the xxx.ht error page is missing.
     */
    private final static String ERR_REQUEST_DENIED =
            "HTTP/1.1 403 Access Denied\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: REQUEST DENIED</H1>" +
            "You attempted to connect to a non-I2P website or location.<BR>";

    /*****
    private final static byte[] ERR_TIMEOUT =
    ("HTTP/1.1 504 Gateway Timeout\r\n"+
    "Content-Type: text/html; charset=iso-8859-1\r\n"+
    "Cache-Control: no-cache\r\n\r\n"+
    "<html><body><H1>I2P ERROR: TIMEOUT</H1>"+
    "That Destination was reachable, but timed out getting a "+
    "response.  This is likely a temporary error, so you should simply "+
    "try to refresh, though if the problem persists, the remote "+
    "destination may have issues.  Could not get a response from "+
    "the following Destination:<BR><BR>")
    .getBytes();
     *****/
    private final static String ERR_NO_OUTPROXY =
            "HTTP/1.1 503 Service Unavailable\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: No outproxy found</H1>" +
            "Your request was for a site outside of I2P, but you have no " +
            "HTTP outproxy configured.  Please configure an outproxy in I2PTunnel";

    private final static String ERR_AHELPER_CONFLICT =
            "HTTP/1.1 409 Conflict\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: Destination key conflict</H1>" +
            "The addresshelper link you followed specifies a different destination key " +
            "than a host entry in your host database. " +
            "Someone could be trying to impersonate another website, " +
            "or people have given two websites identical names.<p>" +
            "You can resolve the conflict by considering which key you trust, " +
            "and either discarding the addresshelper link, " +
            "discarding the host entry from your host database, " +
            "or naming one of them differently.<p>";

    private final static String ERR_AHELPER_NOTFOUND =
            "HTTP/1.1 404 Not Found\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: Helper key not resolvable.</H1>" +
            "The helper key you put for i2paddresshelper= is not resolvable. " +
            "It seems to be garbage data, or a mistyped b32. Check your URL " +
            "to try and fix the helper key to be either a b32 or a base64.";

    private final static String ERR_AHELPER_NEW =
            "HTTP/1.1 409 New Address\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>New Host Name with Address Helper</H1>" +
            "The address helper link you followed is for a new host name that is not in your address book. " +
            "You may either save the destination for this host name to your address book, or remember it only until your router restarts. " +
            "If you save it to your address book, you will not see this message again. " +
            "If you do not wish to visit this host, click the \"back\" button on your browser.";

    private final static String ERR_BAD_PROTOCOL =
            "HTTP/1.1 403 Bad Protocol\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: NON-HTTP PROTOCOL</H1>" +
            "The request uses a bad protocol. " +
            "The I2P HTTP Proxy supports HTTP and HTTPS requests only. Other protocols such as FTP are not allowed.<BR>";

    private final static String ERR_BAD_URI =
            "HTTP/1.1 403 Bad URI\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: INVALID REQUEST URI</H1>" +
            "The request URI is invalid, and probably contains illegal characters. " +
            "If you clicked e.g. a forum link, check the end of the URI for any characters the browser has mistakenly added on.<BR>";

    private final static String ERR_LOCALHOST =
            "HTTP/1.1 403 Access Denied\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: REQUEST DENIED</H1>" +
            "Your browser is misconfigured. Do not use the proxy to access the router console or other localhost destinations.<BR>";

    private final static String ERR_INTERNAL_SSL =
            "HTTP/1.1 403 SSL Rejected\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: SSL to I2P address rejected</H1>" +
            "SSL to .i2p addresses denied by configuration." +
            "You may change the configuration in I2PTunnel";

    /**
     *  This constructor always starts the tunnel (ignoring the i2cp.delayOpen option).
     *  It is used to add a client to an existing socket manager.
     *
     *  As of 0.9.20 this is fast, and does NOT connect the manager to the router,
     *  or open the local socket. You MUST call startRunning() for that.
     *
     *  @param sockMgr the existing socket manager
     */
    public I2PTunnelHTTPClient(int localPort, Logging l, I2PSocketManager sockMgr, I2PTunnel tunnel, EventDispatcher notifyThis, long clientId) {
        super(localPort, l, sockMgr, tunnel, notifyThis, clientId);
        _proxyNonce = Long.toString(_context.random().nextLong());
        // proxyList = new ArrayList();
        if (tunnel.getClientOptions().getProperty("i2p.streaming.connectDelay") == null)
            tunnel.getClientOptions().setProperty("i2p.streaming.connectDelay", "1000");

        setName("HTTP Proxy on " + getTunnel().listenHost + ':' + localPort);
        notifyEvent("openHTTPClientResult", "ok");
    }

    /**
     *  As of 0.9.20 this is fast, and does NOT connect the manager to the router,
     *  or open the local socket. You MUST call startRunning() for that.
     *
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelHTTPClient(int localPort, Logging l, boolean ownDest,
                               String wwwProxy, EventDispatcher notifyThis,
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, "HTTP Proxy on " + tunnel.listenHost + ':' + localPort, tunnel);
        _proxyNonce = Long.toString(_context.random().nextLong());

        //proxyList = new ArrayList(); // We won't use outside of i2p

        if(wwwProxy != null) {
            StringTokenizer tok = new StringTokenizer(wwwProxy, ", ");
            while(tok.hasMoreTokens()) {
                _proxyList.add(tok.nextToken().trim());
            }
        }
        if (tunnel.getClientOptions().getProperty("i2p.streaming.connectDelay") == null)
            tunnel.getClientOptions().setProperty("i2p.streaming.connectDelay", "1000");

        setName("HTTP Proxy on " + tunnel.listenHost + ':' + localPort);
        notifyEvent("openHTTPClientResult", "ok");
    }

    /**
     * Create the default options (using the default timeout, etc).
     * Warning, this does not make a copy of I2PTunnel's client options,
     * it modifies them directly.
     * unused?
     */
    @Override
    protected I2PSocketOptions getDefaultOptions() {
        Properties defaultOpts = getTunnel().getClientOptions();
        if(!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT)) {
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, "" + DEFAULT_READ_TIMEOUT);
        }
        //if (!defaultOpts.contains("i2p.streaming.inactivityTimeout"))
        //    defaultOpts.setProperty("i2p.streaming.inactivityTimeout", ""+DEFAULT_READ_TIMEOUT);
        // delayed start
        verifySocketManager();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if(!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT)) {
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        }
        return opts;
    }

    /**
     * Create the default options (using the default timeout, etc).
     * Warning, this does not make a copy of I2PTunnel's client options,
     * it modifies them directly.
     * Do not use overrides for per-socket options.
     */
    @Override
    protected I2PSocketOptions getDefaultOptions(Properties overrides) {
        Properties defaultOpts = getTunnel().getClientOptions();
        defaultOpts.putAll(overrides);
        if(!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT)) {
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, "" + DEFAULT_READ_TIMEOUT);
        }
        if(!defaultOpts.contains("i2p.streaming.inactivityTimeout")) {
            defaultOpts.setProperty("i2p.streaming.inactivityTimeout", "" + DEFAULT_READ_TIMEOUT);
        }
        // delayed start
        verifySocketManager();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if(!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT)) {
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        }
        return opts;
    }
    private InternalSocketRunner isr;

    /**
     * Actually start working on incoming connections.
     * Overridden to start an internal socket too.
     *
     */
    @Override
    public void startRunning() {
        // following are for HTTPResponseOutputStream
        //_context.statManager().createRateStat("i2ptunnel.httpCompressionRatio", "ratio of compressed size to decompressed size after transfer", "I2PTunnel", new long[] { 60*60*1000 });
        //_context.statManager().createRateStat("i2ptunnel.httpCompressed", "compressed size transferred", "I2PTunnel", new long[] { 60*60*1000 });
        //_context.statManager().createRateStat("i2ptunnel.httpExpanded", "size transferred after expansion", "I2PTunnel", new long[] { 60*60*1000 });
        super.startRunning();
        if (open) {
            this.isr = new InternalSocketRunner(this);
            this.isr.start();
            int port = getLocalPort();
            _context.portMapper().register(PortMapper.SVC_HTTP_PROXY, getTunnel().listenHost, port);
            _context.portMapper().register(PortMapper.SVC_HTTPS_PROXY, getTunnel().listenHost, port);
        }
    }

    /**
     * Overridden to close internal socket too.
     */
    @Override
    public boolean close(boolean forced) {
        int port = getLocalPort();
        int reg = _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY);
        if (reg == port) {
            _context.portMapper().unregister(PortMapper.SVC_HTTP_PROXY);
        }
        reg = _context.portMapper().getPort(PortMapper.SVC_HTTPS_PROXY);
        if (reg == port) {
            _context.portMapper().unregister(PortMapper.SVC_HTTPS_PROXY);
        }
        boolean rv = super.close(forced);
        if(this.isr != null) {
            this.isr.stopRunning();
        }
        return rv;
    }

    /** @since 0.9.4 */
    protected String getRealm() {
        return AUTH_REALM;
    }

    private static final String HELPER_PARAM = "i2paddresshelper";
    public static final String LOCAL_SERVER = "proxy.i2p";
    private static final boolean DEFAULT_GZIP = true;
    /** all default to false */
    public static final String PROP_REFERER = "i2ptunnel.httpclient.sendReferer";
    public static final String PROP_USER_AGENT = "i2ptunnel.httpclient.sendUserAgent";
    public static final String PROP_VIA = "i2ptunnel.httpclient.sendVia";
    public static final String PROP_JUMP_SERVERS = "i2ptunnel.httpclient.jumpServers";
    public static final String PROP_DISABLE_HELPER = "i2ptunnel.httpclient.disableAddressHelper";
    /** @since 0.9.11 */
    public static final String PROP_SSL_OUTPROXIES = "i2ptunnel.httpclient.SSLOutproxies";
    /** @since 0.9.14 */
    public static final String PROP_ACCEPT = "i2ptunnel.httpclient.sendAccept";
    /** @since 0.9.14, overridden to true as of 0.9.35 unlesss PROP_SSL_SET is set */
    public static final String PROP_INTERNAL_SSL = "i2ptunnel.httpclient.allowInternalSSL";
    /** @since 0.9.35 */
    public static final String PROP_SSL_SET = "sslManuallySet";

    /**
     *
     *  Note: This does not handle RFC 2616 header line splitting,
     *  which is obsoleted in RFC 7230.
     */
    protected void clientConnectionRun(Socket s) {
        OutputStream out = null;

        /**
         * The URL after fixup, always starting with http:// or https://
         */
        String targetRequest = null;

        // in-net outproxy
        boolean usingWWWProxy = false;
        // local outproxy plugin
        boolean usingInternalOutproxy = false;
        Outproxy outproxy = null;
        boolean usingInternalServer = false;
        String internalPath = null;
        String internalRawQuery = null;
        String currentProxy = null;
        long requestId = __requestId.incrementAndGet();
        boolean shout = false;
        I2PSocket i2ps = null;
        try {
            s.setSoTimeout(INITIAL_SO_TIMEOUT);
            out = s.getOutputStream();
            InputReader reader = new InputReader(s.getInputStream());
            String line, method = null, protocol = null, host = null, destination = null;
            StringBuilder newRequest = new StringBuilder();
            boolean ahelperPresent = false;
            boolean ahelperNew = false;
            String ahelperKey = null;
            String userAgent = null;
            String authorization = null;
            int remotePort = 0;
            String referer = null;
            URI origRequestURI = null;
            while((line = reader.readLine(method)) != null) {
                line = line.trim();
                if(_log.shouldLog(Log.DEBUG)) {
                    _log.debug(getPrefix(requestId) + "Line=[" + line + "]");
                }

                String lowercaseLine = line.toLowerCase(Locale.US);
                if(lowercaseLine.startsWith("connection: ") ||
                        lowercaseLine.startsWith("keep-alive: ") ||
                        lowercaseLine.startsWith("proxy-connection: ")) {
                    continue;
                }

                if(method == null) { // first line (GET /base64/realaddr)
                    if(_log.shouldLog(Log.DEBUG)) {
                        _log.debug(getPrefix(requestId) + "First line [" + line + "]");
                    }

                    String[] params = DataHelper.split(line, " ", 3);
                    if(params.length != 3) {
                        break;
                    }
                    String request = params[1];

                    // various obscure fixups
                    if(request.startsWith("/") && getTunnel().getClientOptions().getProperty("i2ptunnel.noproxy") != null) {
                        // what is this for ???
                        request = "http://i2p" + request;
                    } else if(request.startsWith("/eepproxy/")) {
                        // Deprecated
                        // /eepproxy/foo.i2p/bar/baz.html
                        String subRequest = request.substring("/eepproxy/".length());
                        if(subRequest.indexOf('/') == -1) {
                            subRequest += '/';
                        }
                        request = "http://" + subRequest;
                    /****
                    } else if (request.toLowerCase(Locale.US).startsWith("http://i2p/")) {
                    // http://i2p/b64key/bar/baz.html
                    // we can't do this now by setting the URI host to the b64key, as
                    // it probably contains '=' and '~' which are illegal,
                    // and a host may not include escaped octets
                    // This will get undone below.
                    String subRequest = request.substring("http://i2p/".length());
                    if (subRequest.indexOf("/") == -1)
                    subRequest += "/";
                    "http://" + "b64key/bar/baz.html"
                    request = "http://" + subRequest;
                    } else if (request.toLowerCase(Locale.US).startsWith("http://")) {
                    // Unsupported
                    // http://$b64key/...
                    // This probably used to work, rewrite it so that
                    // we can create a URI without illegal characters
                    // This will get undone below.
                    String  oldPath = request.substring(7);
                    int slash = oldPath.indexOf("/");
                    if (slash < 0)
                    slash = oldPath.length();
                    if (slash >= 516 && !oldPath.substring(0, slash).contains("."))
                    request = "http://i2p/" + oldPath;
                     ****/
                    }

                    method = params[0];
                    if (method.toUpperCase(Locale.US).equals("CONNECT")) {
                        // this makes things easier later, by spoofing a
                        // protocol so the URI parser find the host and port
                        // For in-net outproxy, will be fixed up below
                        request = "https://" + request + '/';
                    }

                    // Now use the Java URI parser
                    // This will be the incoming URI but will then get modified
                    // to be the outgoing URI (with http:// if going to outproxy, otherwise without)
                    URI requestURI = null;
                    try {
                        try {
                            requestURI = new URI(request);
                        } catch(URISyntaxException use) {
                            // fixup []| in path/query not escaped by browsers, see ticket #2130
                            boolean error = true;
                            // find 3rd /
                            int idx = 0;
                            for (int i = 0; i < 2; i++) {
                                idx = request.indexOf('/', idx);
                                if (idx < 0)
                                    break;
                                idx++;
                            }
                            if (idx > 0) {
                                String schemeHostPort = request.substring(0, idx);
                                String rest = request.substring(idx);
                                rest = rest.replace("[", "%5B");
                                rest = rest.replace("]", "%5D");
                                rest = rest.replace("|", "%7C");
                                String testRequest = schemeHostPort + rest;
                                if (!testRequest.equals(request)) {
                                    try {
                                        requestURI = new URI(testRequest);
                                        request = testRequest;
                                        error = false;
                                    } catch(URISyntaxException use2) {
                                        // didn't work, give up
                                    }
                                }
                            }
                            // guess it wasn't []|
                            if (error)
                                throw use;
                        }
                        origRequestURI = requestURI;
                        if(requestURI.getRawUserInfo() != null || requestURI.getRawFragment() != null) {
                            // these should never be sent to the proxy in the request line
                            if(_log.shouldLog(Log.WARN)) {
                                _log.warn(getPrefix(requestId) + "Removing userinfo or fragment [" + request + "]");
                            }
                            requestURI = changeURI(requestURI, null, 0, null);
                        }
                        if(requestURI.getPath() == null || requestURI.getPath().length() <= 0) {
                            // Add a path
                            if(_log.shouldLog(Log.WARN)) {
                                _log.warn(getPrefix(requestId) + "Adding / path to [" + request + "]");
                            }
                            requestURI = changeURI(requestURI, null, 0, "/");
                        }
                    } catch(URISyntaxException use) {
                        if(_log.shouldLog(Log.WARN)) {
                            _log.warn(getPrefix(requestId) + "Bad request [" + request + "]", use);
                        }
                        try {
                            out.write(getErrorPage("baduri", ERR_BAD_URI).getBytes("UTF-8"));
                            String msg = use.getLocalizedMessage();
                            if (msg != null) {
                                out.write(DataHelper.getASCII("<p>\n"));
                                out.write(DataHelper.getUTF8(DataHelper.escapeHTML(msg)));
                                out.write(DataHelper.getASCII("</p>\n"));
                            }
                            out.write(DataHelper.getASCII("</div>\n"));
                            writeFooter(out);
                            reader.drain();
                        } catch (IOException ioe) {
                            // ignore
                        }
                        return;
                    }

                    String protocolVersion = params[2];

                    protocol = requestURI.getScheme();
                    host = requestURI.getHost();
                    if(protocol == null || host == null) {
                        _log.warn("Null protocol or host: " + request + ' ' + protocol + ' ' + host);
                        method = null;
                        break;
                    }

                    int port = requestURI.getPort();

                    // Go through the various types of host names, set
                    // the host and destination variables accordingly,
                    // and transform the first line.
                    // For all i2p network hosts, ensure that the host is a
                    // Base 32 hostname so that we do not reveal our name for it
                    // in our addressbook (all naming is local),
                    // and it is removed from the request line.

                    String hostLowerCase = host.toLowerCase(Locale.US);
                    if(hostLowerCase.equals(LOCAL_SERVER)) {
                        // so we don't do any naming service lookups
                        destination = host;
                        usingInternalServer = true;
                        internalPath = requestURI.getPath();
                        internalRawQuery = requestURI.getRawQuery();
                    } else if(hostLowerCase.equals("i2p")) {
                        // pull the b64 _dest out of the first path element
                        String oldPath = requestURI.getPath().substring(1);
                        int slash = oldPath.indexOf('/');
                        if(slash < 0) {
                            slash = oldPath.length();
                            oldPath += '/';
                        }
                        String _dest = oldPath.substring(0, slash);
                        if(slash >= 516 && !_dest.contains(".")) {
                            // possible alternative:
                            // redirect to b32
                            destination = _dest;
                            host = getHostName(destination);
                            targetRequest = requestURI.toASCIIString();
                            String newURI = oldPath.substring(slash);
                            String query = requestURI.getRawQuery();
                            if(query != null) {
                                newURI += '?' + query;
                            }
                            try {
                                requestURI = new URI(newURI);
                            } catch(URISyntaxException use) {
                                // shouldnt happen
                                _log.warn(request, use);
                                method = null;
                                break;
                            }
                        } else {
                            _log.warn("Bad http://i2p/b64dest " + request);
                            host = null;
                            break;
                        }
                    } else if(hostLowerCase.endsWith(".i2p")) {
                        // Destination gets the host name
                        destination = host;
                        // Host becomes the destination's "{b32}.b32.i2p" string, or "i2p" on lookup failure
                        host = getHostName(destination);

                        int rPort = requestURI.getPort();
                        if (rPort > 0) {
                            // Save it to put in the I2PSocketOptions,
                            remotePort = rPort;
                         /********
                            // but strip it from the URL
                            if(_log.shouldLog(Log.WARN)) {
                                _log.warn(getPrefix(requestId) + "Removing port from [" + request + "]");
                            }
                            try {
                                requestURI = changeURI(requestURI, null, -1, null);
                            } catch(URISyntaxException use) {
                                _log.warn(request, use);
                                method = null;
                                break;
                            }
                          ******/
                        } else if ("https".equals(protocol) ||
                                   method.toUpperCase(Locale.US).equals("CONNECT")) {
                            remotePort = 443;
                        } else {
                            remotePort = 80;
                        }

                        String query = requestURI.getRawQuery();
                        if(query != null) {
                            boolean ahelperConflict = false;

                            // Try to find an address helper in the query
                            String[] helperStrings = removeHelper(query);
                            if(helperStrings != null &&
                                    !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER))) {
                                query = helperStrings[0];
                                if(query.equals("")) {
                                    query = null;
                                }
                                try {
                                    requestURI = replaceQuery(requestURI, query);
                                } catch(URISyntaxException use) {
                                    // shouldn't happen
                                    _log.warn(request, use);
                                    method = null;
                                    break;
                                }
                                ahelperKey = helperStrings[1];
                                // Key contains data, lets not ignore it
                                if(ahelperKey.length() > 0) {
                                    if(ahelperKey.endsWith(".i2p")) {
                                        // allow i2paddresshelper=<b32>.b32.i2p syntax.
                                        /*
                                        also i2paddresshelper=name.i2p for aliases
                                        i.e. on your eepsite put
                                        <a href="?i2paddresshelper=name.i2p">This is the name I want to be called.</a>
                                         */
                                        Destination _dest = _context.namingService().lookup(ahelperKey);
                                        if(_dest == null) {
                                            if(_log.shouldLog(Log.WARN)) {
                                                _log.warn(getPrefix(requestId) + "Could not find destination for " + ahelperKey);
                                            }
                                            String header = getErrorPage("ahelper-notfound", ERR_AHELPER_NOTFOUND);
                                            try {
                                                out.write(header.getBytes("UTF-8"));
                                                out.write(("<p>" + _t("This seems to be a bad destination:") + " " + ahelperKey + " " +
                                                           _t("i2paddresshelper cannot help you with a destination like that!") +
                                                           "</p>").getBytes("UTF-8"));
                                                writeFooter(out);
                                                reader.drain();
                                            } catch (IOException ioe) {
                                                // ignore
                                            }
                                            return;
                                        }
                                        ahelperKey = _dest.toBase64();
                                    }

                                    ahelperPresent = true;
                                    // ahelperKey will be validated later
                                    if(host == null || "i2p".equals(host)) {
                                        // Host lookup failed - resolvable only with addresshelper
                                        // Store in local HashMap unless there is conflict
                                        String old = addressHelpers.putIfAbsent(destination.toLowerCase(Locale.US), ahelperKey);
                                        ahelperNew = old == null;
                                        // inr address helper links without trailing '=', so omit from comparison
                                        if ((!ahelperNew) && !old.replace("=", "").equals(ahelperKey.replace("=", ""))) {
                                            // Conflict: handle when URL reconstruction done
                                            ahelperConflict = true;
                                            if(_log.shouldLog(Log.WARN)) {
                                                _log.warn(getPrefix(requestId) + "Addresshelper key conflict for site [" + destination +
                                                        "], trusted key [" + old + "], specified key [" + ahelperKey + "].");
                                            }
                                        }
                                    } else {
                                        // If the host is resolvable from database, verify addresshelper key
                                        // Silently bypass correct keys, otherwise alert
                                        Destination hostDest = _context.namingService().lookup(destination);
                                        if(hostDest != null) {
                                            String destB64 = hostDest.toBase64();
                                            if(destB64 != null && !destB64.equals(ahelperKey)) {
                                                // Conflict: handle when URL reconstruction done
                                                ahelperConflict = true;
                                                if(_log.shouldLog(Log.WARN)) {
                                                    _log.warn(getPrefix(requestId) + "Addresshelper key conflict for site [" + destination +
                                                            "], trusted key [" + destB64 + "], specified key [" + ahelperKey + "].");
                                                }

                                            }
                                        }
                                    }
                                } // ahelperKey
                            } // helperstrings

                            // Did addresshelper key conflict?
                            if(ahelperConflict) {
                               try {
                                    // convert ahelperKey to b32
                                    String alias = getHostName(ahelperKey);
                                    if(alias.equals("i2p")) {
                                        // bad ahelperKey
                                        String header = getErrorPage("dnfb", ERR_DESTINATION_UNKNOWN);
                                        writeErrorMessage(header, out, targetRequest, false, destination);
                                    } else {
                                        String trustedURL = requestURI.toASCIIString();
                                        URI conflictURI;
                                        try {
                                            conflictURI = changeURI(requestURI, alias, 0, null);
                                        } catch(URISyntaxException use) {
                                            // shouldn't happen
                                            _log.warn(request, use);
                                            method = null;
                                            break;
                                        }
                                        String conflictURL = conflictURI.toASCIIString();
                                        String header = getErrorPage("ahelper-conflict", ERR_AHELPER_CONFLICT);
                                        out.write(header.getBytes("UTF-8"));
                                        out.write("<p>".getBytes("UTF-8"));
                                        out.write(_t("To visit the destination in your address book, click <a href=\"{0}\">here</a>. To visit the conflicting addresshelper destination, click <a href=\"{1}\">here</a>.",
                                                    trustedURL, conflictURL).getBytes("UTF-8"));
                                        out.write("</p>".getBytes("UTF-8"));
                                        Hash h1 = ConvertToHash.getHash(requestURI.getHost());
                                        Hash h2 = ConvertToHash.getHash(ahelperKey);
                                        if (h1 != null && h2 != null) {
                                            String conURL = _context.portMapper().getConsoleURL();
                                            out.write(("\n<table class=\"conflict\"><tr><th align=\"center\">" +
                                                       "<a href=\"" + trustedURL + "\">").getBytes("UTF-8"));
                                            out.write(_t("Destination for {0} in address book", requestURI.getHost()).getBytes("UTF-8"));
                                            out.write(("</a></th>\n<th align=\"center\">" +
                                                       "<a href=\"" + conflictURL + "\">").getBytes("UTF-8"));
                                            out.write(_t("Conflicting address helper destination").getBytes("UTF-8"));
                                            out.write(("</a></th></tr>\n").getBytes("UTF-8"));
                                            if (_context.portMapper().isRegistered(PortMapper.SVC_IMAGEGEN)) {
                                                out.write(("<tr><td align=\"center\">" +
                                                       "<a href=\"" + trustedURL + "\">" +
                                                       "<img src=\"" +
                                                       conURL + "imagegen/id?s=160&amp;c=" +
                                                       h1.toBase64().replace("=", "%3d") +
                                                      "\" width=\"160\" height=\"160\"></a>\n" +
                                                      "</td>\n<td align=\"center\">" +
                                                       "<a href=\"" + conflictURL + "\">" +
                                                       "<img src=\"" +
                                                       conURL + "imagegen/id?s=160&amp;c=" +
                                                       h2.toBase64().replace("=", "%3d") +
                                                       "\" width=\"160\" height=\"160\"></a>\n" +
                                                       "</td></tr>").getBytes("UTF-8"));
                                            }
                                            out.write("</table>".getBytes("UTF-8"));
                                        }
                                        out.write("</div>".getBytes("UTF-8"));
                                        writeFooter(out);
                                    }
                                    reader.drain();
                                } catch (IOException ioe) {
                                    // ignore
                                }
                                return;
                            }
                        }  // end query processing

                        String addressHelper = addressHelpers.get(destination);
                        if(addressHelper != null) {
                            host = getHostName(addressHelper);
                        }

                        // now strip everything but path and query from URI
                        targetRequest = requestURI.toASCIIString();
                        String newURI = requestURI.getRawPath();
                        if(query != null) {
                            newURI += '?' + query;
                        }
                        try {
                            requestURI = new URI(newURI);
                        } catch(URISyntaxException use) {
                            // shouldnt happen
                            _log.warn(request, use);
                            method = null;
                            break;
                        }

                    // end of (host endsWith(".i2p"))

                    } else if(hostLowerCase.equals("localhost") || host.equals("127.0.0.1") ||
                            host.startsWith("192.168.") || host.equals("[::1]")) {
                        // if somebody is trying to get to 192.168.example.com, oh well
                        try {
                            out.write(getErrorPage("localhost", ERR_LOCALHOST).getBytes("UTF-8"));
                            writeFooter(out);
                            reader.drain();
                        } catch (IOException ioe) {
                            // ignore
                        }
                        return;
                    } else if(host.contains(".") || host.startsWith("[")) {
                        if (Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_USE_OUTPROXY_PLUGIN, "true"))) {
                            ClientAppManager mgr = _context.clientAppManager();
                            if (mgr != null) {
                                ClientApp op = mgr.getRegisteredApp(Outproxy.NAME);
                                if (op != null) {
                                    outproxy = (Outproxy) op;
                                    int rPort = requestURI.getPort();
                                    if (rPort > 0)
                                        remotePort = rPort;
                                    else if ("https".equals(protocol) ||
                                             method.toUpperCase(Locale.US).equals("CONNECT"))
                                        remotePort = 443;
                                    else
                                        remotePort = 80;
                                    usingInternalOutproxy = true;
                                    targetRequest = requestURI.toASCIIString();
                                    if(_log.shouldLog(Log.DEBUG))
                                        _log.debug(getPrefix(requestId) + " [" + host + "]: outproxy!");
                                }
                            }
                        }
                        if (!usingInternalOutproxy) {
                            if(port >= 0) {
                                host = host + ':' + port;
                            }
                            // The request must be forwarded to a WWW proxy
                            if(_log.shouldLog(Log.DEBUG)) {
                                _log.debug("Before selecting outproxy for " + host);
                            }
                            if ("https".equals(protocol) ||
                                method.toUpperCase(Locale.US).equals("CONNECT"))
                                currentProxy = selectSSLProxy();
                            else
                                currentProxy = selectProxy();
                            if(_log.shouldLog(Log.DEBUG)) {
                                _log.debug("After selecting outproxy for " + host + ": " + currentProxy);
                            }
                            if(currentProxy == null) {
                                if(_log.shouldLog(Log.WARN)) {
                                    _log.warn(getPrefix(requestId) + "Host wants to be outproxied, but we dont have any!");
                                }
                                l.log("No outproxy found for the request.");
                                try {
                                    out.write(getErrorPage("noproxy", ERR_NO_OUTPROXY).getBytes("UTF-8"));
                                    writeFooter(out);
                                    reader.drain();
                                } catch (IOException ioe) {
                                    // ignore
                                }
                                return;
                            }
                            destination = currentProxy;
                            usingWWWProxy = true;
                            targetRequest = requestURI.toASCIIString();
                            if(_log.shouldLog(Log.DEBUG)) {
                                _log.debug(getPrefix(requestId) + " [" + host + "]: wwwProxy!");
                            }
                        }
                    } else {
                        // what is left for here? a hostname with no dots, and != "i2p"
                        // and not a destination ???
                        // Perhaps something in privatehosts.txt ...
                        // Rather than look it up, just bail out.
                        if(_log.shouldLog(Log.WARN)) {
                            _log.warn("NODOTS, NOI2P: " + request);
                        }
                        try {
                            out.write(getErrorPage("denied", ERR_REQUEST_DENIED).getBytes("UTF-8"));
                            writeFooter(out);
                            reader.drain();
                        } catch (IOException ioe) {
                            // ignore
                        }
                        return;
                    }   // end host name processing

                    boolean isValid = usingInternalOutproxy || usingWWWProxy ||
                                      usingInternalServer || isSupportedAddress(host, protocol);
                    if(!isValid) {
                        if(_log.shouldLog(Log.INFO)) {
                            _log.info(getPrefix(requestId) + "notValid(" + host + ")");
                        }
                        method = null;
                        destination = null;
                        break;
                    }

                    if (method.toUpperCase(Locale.US).equals("CONNECT")) {
                        // fix up the change to requestURI above to get back to the original host:port
                        line = method + ' ' + requestURI.getHost() + ':' + requestURI.getPort() + ' ' + protocolVersion;
                    } else {
                        line = method + ' ' + requestURI.toASCIIString() + ' ' + protocolVersion;
                    }

                    if(_log.shouldLog(Log.DEBUG)) {
                        _log.debug(getPrefix(requestId) + "NEWREQ: \"" + line + "\"");
                        _log.debug(getPrefix(requestId) + "HOST  : \"" + host + "\"");
                        _log.debug(getPrefix(requestId) + "DEST  : \"" + destination + "\"");
                    }

                // end first line processing

                } else {
                    if(lowercaseLine.startsWith("host: ") && !usingWWWProxy && !usingInternalOutproxy) {
                        // Note that we only pass the original Host: line through to the outproxy
                        // But we don't create a Host: line if it wasn't sent to us
                        line = "Host: " + host;
                        if(_log.shouldLog(Log.INFO)) {
                            _log.info(getPrefix(requestId) + "Setting host = " + host);
                        }
                    } else if(lowercaseLine.startsWith("user-agent: ")) {
                        // save for deciding whether to offer address book form
                        userAgent = lowercaseLine.substring(12);
                        if(!Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_USER_AGENT))) {
                            line = null;
                            continue;
                        }
                    } else if(lowercaseLine.startsWith("accept: ")) {
                        if (!Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_ACCEPT))) {
                            // Replace with a standard one if possible
                            boolean html = lowercaseLine.indexOf("text/html") > 0;
                            boolean css = lowercaseLine.indexOf("text/css") > 0;
                            boolean img = lowercaseLine.indexOf("image") > 0;
                            if (html && !img && !css) {
                                // firefox, tor browser
                                line = "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
                            } else if (img && !html && !css) {
                                // chrome
                                line = "Accept: image/webp,image/apng,image/*,*/*;q=0.8";
                            } else if (css && !html && !img) {
                                // chrome, firefox
                                line = "Accept: text/css,*/*;q=0.1";
                            }  // else allow as-is
                        }
                    } else if(lowercaseLine.startsWith("accept")) {
                        // strip the accept-blah headers, as they vary dramatically from
                        // browser to browser
                        // But allow Accept-Encoding: gzip, deflate
                        if(!lowercaseLine.startsWith("accept-encoding: ") &&
                           !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_ACCEPT))) {
                            line = null;
                            continue;
                        }
                    } else if (lowercaseLine.startsWith("referer: ")) {
                        // save for address helper form below
                        referer = line.substring(9);
                        if (!Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_REFERER))) {
                            try {
                                // Either strip or rewrite the referer line
                                URI refererURI = new URI(referer);
                                String refererHost = refererURI.getHost();
                                if (refererHost != null) {
                                    String origHost = origRequestURI.getHost();
                                    if (!refererHost.equals(origHost) ||
                                        refererURI.getPort() != origRequestURI.getPort() ||
                                        !DataHelper.eq(refererURI.getScheme(), origRequestURI.getScheme())) {
                                        line = null;
                                        continue; // completely strip the line if everything doesn't match
                                    }
                                    // Strip to a relative URI, to hide the original host name
                                    StringBuilder buf = new StringBuilder();
                                    buf.append("Referer: ");
                                    String refererPath = refererURI.getRawPath();
                                    buf.append(refererPath != null ? refererPath : "/");
                                    String refererQuery = refererURI.getRawQuery();
                                    if (refererQuery != null)
                                        buf.append('?').append(refererQuery);
                                    line = buf.toString();
                                } // else relative URI, leave in
                            } catch (URISyntaxException use) {
                                line = null;
                                continue; // completely strip the line
                            }
                        } // else allow
                    } else if(lowercaseLine.startsWith("via: ") &&
                            !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_VIA))) {
                        //line = "Via: i2p";
                        line = null;
                        continue; // completely strip the line
                    } else if(lowercaseLine.startsWith("from: ")) {
                        //line = "From: i2p";
                        line = null;
                        continue; // completely strip the line
                    } else if(lowercaseLine.startsWith("authorization: ntlm ")) {
                        // Block Windows NTLM after 401
                        line = null;
                        continue;
                    } else if(lowercaseLine.startsWith("proxy-authorization: ")) {
                        // This should be for us. It is a
                        // hop-by-hop header, and we definitely want to block Windows NTLM after a far-end 407.
                        // Response to far-end shouldn't happen, as we
                        // strip Proxy-Authenticate from the response in HTTPResponseOutputStream
                        authorization = line.substring(21);  // "proxy-authorization: ".length()
                        line = null;
                        continue;
                    } else if(lowercaseLine.startsWith("icy")) {
                        // icecast/shoutcast, We need to leave the user-agent alone.
                        shout = true;
                    }
                }

                if(line.length() == 0) {
                    // No more headers, add our own and break out of the loop
                    String ok = getTunnel().getClientOptions().getProperty("i2ptunnel.gzip");
                    boolean gzip = DEFAULT_GZIP;
                    if(ok != null) {
                        gzip = Boolean.parseBoolean(ok);
                    }
                    if(gzip && !usingInternalServer &&
                       !method.toUpperCase(Locale.US).equals("CONNECT")) {
                        // according to rfc2616 s14.3, this *should* force identity, even if
                        // an explicit q=0 for gzip doesn't.  tested against orion.i2p, and it
                        // seems to work.
                        //if (!Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_ACCEPT)))
                        //    newRequest.append("Accept-Encoding: \r\n");
                        if (!usingInternalOutproxy)
                            newRequest.append("X-Accept-Encoding: x-i2p-gzip;q=1.0, identity;q=0.5, deflate;q=0, gzip;q=0, *;q=0\r\n");
                    }
                    if(!shout && !method.toUpperCase(Locale.US).equals("CONNECT")) {
                        if(!Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_USER_AGENT))) {
                            // let's not advertise to external sites that we are from I2P
                            if(usingWWWProxy || usingInternalOutproxy) {
                                newRequest.append(UA_CLEARNET);
                            } else {
                                newRequest.append(UA_I2P);
                            }
                        }
                    }
                    // Add Proxy-Authentication header for next hop (outproxy)
                    if(usingWWWProxy && Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_AUTH))) {
                        // specific for this proxy
                        String user = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_USER_PREFIX + currentProxy);
                        String pw = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_PW_PREFIX + currentProxy);
                        if(user == null || pw == null) {
                            // if not, look at default user and pw
                            user = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_USER);
                            pw = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_PW);
                        }
                        if(user != null && pw != null) {
                            newRequest.append("Proxy-Authorization: Basic ")
                                    .append(Base64.encode((user + ':' + pw).getBytes("UTF-8"), true)) // true = use standard alphabet
                                    .append("\r\n");
                        }
                    }
                    newRequest.append("Connection: close\r\n\r\n");
                    s.setSoTimeout(0);
                    break;
                } else {
                    newRequest.append(line).append("\r\n"); // HTTP spec
                }
            } // end header processing

            if(_log.shouldLog(Log.DEBUG)) {
                _log.debug(getPrefix(requestId) + "NewRequest header: [" + newRequest.toString() + "]");
            }

            if(method == null || (destination == null && !usingInternalOutproxy)) {
                //l.log("No HTTP method found in the request.");
                try {
                    if (protocol != null && "http".equals(protocol.toLowerCase(Locale.US))) {
                        out.write(getErrorPage("denied", ERR_REQUEST_DENIED).getBytes("UTF-8"));
                    } else {
                        out.write(getErrorPage("protocol", ERR_BAD_PROTOCOL).getBytes("UTF-8"));
                    }
                    writeFooter(out);
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            if(_log.shouldLog(Log.DEBUG)) {
                _log.debug(getPrefix(requestId) + "Destination: " + destination);
            }

            // Authorization
            AuthResult result = authorize(s, requestId, method, authorization);
            if (result != AuthResult.AUTH_GOOD) {
                if(_log.shouldLog(Log.WARN)) {
                    if(authorization != null) {
                        _log.warn(getPrefix(requestId) + "Auth failed, sending 407 again");
                    } else {
                        _log.warn(getPrefix(requestId) + "Auth required, sending 407");
                    }
                }
                try {
                    out.write(getAuthError(result == AuthResult.AUTH_STALE).getBytes("UTF-8"));
                    writeFooter(out);
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            // Serve local proxy files (images, css linked from error pages)
            // Ignore all the headers
            if (usingInternalServer) {
                try {
                    // disable the add form if address helper is disabled
                    if(internalPath.equals("/add") &&
                            Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER))) {
                        out.write(ERR_HELPER_DISABLED.getBytes("UTF-8"));
                    } else {
                        LocalHTTPServer.serveLocalFile(out, method, internalPath, internalRawQuery, _proxyNonce);
                    }
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            // no destination, going to outproxy plugin
            if (usingInternalOutproxy) {
                Socket outSocket = outproxy.connect(host, remotePort);
                OnTimeout onTimeout = new OnTimeout(s, s.getOutputStream(), targetRequest, usingWWWProxy, currentProxy, requestId);
                byte[] data;
                byte[] response;
                if (method.toUpperCase(Locale.US).equals("CONNECT")) {
                    data = null;
                    response = SUCCESS_RESPONSE.getBytes("UTF-8");
                } else {
                    data = newRequest.toString().getBytes("ISO-8859-1");
                    response = null;
                }
                Thread t = new I2PTunnelOutproxyRunner(s, outSocket, sockLock, data, response, onTimeout);
                // we are called from an unlimited thread pool, so run inline
                //t.start();
                t.run();
                return;
            }

            // LOOKUP
            // If the host is "i2p", the getHostName() lookup failed, don't try to
            // look it up again as the naming service does not do negative caching
            // so it will be slow.
            Destination clientDest = null;
            String addressHelper = addressHelpers.get(destination.toLowerCase(Locale.US));
            if(addressHelper != null) {
                clientDest = _context.namingService().lookup(addressHelper);
                if(clientDest == null) {
                    // remove bad entries
                    addressHelpers.remove(destination.toLowerCase(Locale.US));
                    if(_log.shouldLog(Log.WARN)) {
                        _log.warn(getPrefix(requestId) + "Could not find destination for " + addressHelper);
                    }
                    String header = getErrorPage("ahelper-notfound", ERR_AHELPER_NOTFOUND);
                    try {
                        writeErrorMessage(header, out, targetRequest, false, destination);
                    } catch (IOException ioe) {
                        // ignore
                    }
                    return;
                }
            } else if("i2p".equals(host)) {
                clientDest = null;
            } else if(destination.length() == 60 && destination.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
                // use existing session to look up for efficiency
                verifySocketManager();
                I2PSession sess = sockMgr.getSession();
                if(!sess.isClosed()) {
                    byte[] hData = Base32.decode(destination.substring(0, 52));
                    if(hData != null) {
                        if(_log.shouldLog(Log.INFO)) {
                            _log.info("lookup in-session " + destination);
                        }
                        Hash hash = Hash.create(hData);
                        clientDest = sess.lookupDest(hash, 20 * 1000);
                    }
                } else {
                    clientDest = _context.namingService().lookup(destination);
                }
            } else {
                clientDest = _context.namingService().lookup(destination);
            }

            if(clientDest == null) {
                //l.log("Could not resolve " + destination + ".");
                if(_log.shouldLog(Log.WARN)) {
                    _log.warn("Unable to resolve " + destination + " (proxy? " + usingWWWProxy + ", request: " + targetRequest);
                }
                String header;
                String jumpServers = null;
                String extraMessage = null;
                if(usingWWWProxy) {
                    header = getErrorPage("dnfp", ERR_DESTINATION_UNKNOWN);
                } else if(ahelperPresent) {
                    header = getErrorPage("dnfb", ERR_DESTINATION_UNKNOWN);
                } else if(destination.length() == 60 && destination.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
                    header = getErrorPage("nols", ERR_DESTINATION_UNKNOWN);
                    extraMessage = _t("Destination lease set not found");
                } else {
                    header = getErrorPage("dnfh", ERR_DESTINATION_UNKNOWN);
                    jumpServers = getTunnel().getClientOptions().getProperty(PROP_JUMP_SERVERS);
                    if(jumpServers == null) {
                        jumpServers = DEFAULT_JUMP_SERVERS;
                    }
                    int jumpDelay = 400 + _context.random().nextInt(256);
                    try {
                        Thread.sleep(jumpDelay);
                    } catch (InterruptedException ie) {}
                }
                try {
                    writeErrorMessage(header, extraMessage, out, targetRequest, usingWWWProxy, destination, jumpServers);
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            // as of 0.9.35, allowInternalSSL defaults to true, and overridden to true unless PROP_SSL_SET is set
            if (method.toUpperCase(Locale.US).equals("CONNECT") &&
                !usingWWWProxy &&
                getTunnel().getClientOptions().getProperty(PROP_SSL_SET) != null &&
                !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_INTERNAL_SSL, "true"))) {
                try {
                    writeErrorMessage(ERR_INTERNAL_SSL, out, targetRequest, false, destination);
                } catch (IOException ioe) {
                    // ignore
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("SSL to i2p destinations denied by configuration: " + targetRequest);
                return;
            }

            // Address helper response form
            // This will only load once - the second time it won't be "new"
            // Don't do this for eepget, which uses a user-agent of "Wget"
            if(ahelperNew && "GET".equals(method) &&
                    (userAgent == null || !userAgent.startsWith("Wget")) &&
                    !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER))) {
                try {
                    writeHelperSaveForm(out, destination, ahelperKey, targetRequest, referer);
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            // Redirect to non-addresshelper URL to not clog the browser address bar
            // and not pass the parameter to the eepsite.
            // This also prevents the not-found error page from looking bad
            // Syndie can't handle a redirect of a POST
            if(ahelperPresent && !"POST".equals(method)) {
                String uri = targetRequest;
                if(_log.shouldLog(Log.DEBUG)) {
                    _log.debug("Auto redirecting to " + uri);
                }
                try {
                    out.write(("HTTP/1.1 301 Address Helper Accepted\r\n" +
                        "Location: " + uri + "\r\n" +
                        "Connection: close\r\n"+
                        "Proxy-Connection: close\r\n"+
                        "\r\n").getBytes("UTF-8"));
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            Properties opts = new Properties();
            //opts.setProperty("i2p.streaming.inactivityTimeout", ""+120*1000);
            // 1 == disconnect.  see ConnectionOptions in the new streaming lib, which i
            // dont want to hard link to here
            //opts.setProperty("i2p.streaming.inactivityTimeoutAction", ""+1);
            I2PSocketOptions sktOpts = getDefaultOptions(opts);
            if (remotePort > 0)
                sktOpts.setPort(remotePort);
            i2ps = createI2PSocket(clientDest, sktOpts);
            OnTimeout onTimeout = new OnTimeout(s, s.getOutputStream(), targetRequest, usingWWWProxy, currentProxy, requestId);
            Thread t;
            if (method.toUpperCase(Locale.US).equals("CONNECT")) {
                byte[] data;
                byte[] response;
                if (usingWWWProxy) {
                    data = newRequest.toString().getBytes("ISO-8859-1");
                    response = null;
                } else {
                    data = null;
                    response = SUCCESS_RESPONSE.getBytes("UTF-8");
                }
                t = new I2PTunnelRunner(s, i2ps, sockLock, data, response, mySockets, onTimeout);
            } else {
                byte[] data = newRequest.toString().getBytes("ISO-8859-1");
                t = new I2PTunnelHTTPClientRunner(s, i2ps, sockLock, data, mySockets, onTimeout);
            }
            // we are called from an unlimited thread pool, so run inline
            //t.start();
            t.run();
        } catch(IOException ex) {
            if(_log.shouldLog(Log.INFO)) {
                _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            }
            handleClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
        } catch(I2PException ex) {
            if(_log.shouldLog(Log.INFO)) {
                _log.info("getPrefix(requestId) + Error trying to connect", ex);
            }
            handleClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
        } catch(OutOfMemoryError oom) {
            IOException ex = new IOException("OOM");
            _log.error("getPrefix(requestId) + Error trying to connect", oom);
            handleClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
        } finally {
            // only because we are running it inline
            closeSocket(s);
            if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Unlike selectProxy(), we parse the option on the fly so it
     *  can be changed. selectProxy() requires restart...
     *  @return null if none
     *  @since 0.9.11
     */
    private String selectSSLProxy() {
        String s = getTunnel().getClientOptions().getProperty(PROP_SSL_OUTPROXIES);
        if (s == null)
            return null;
        String[] p = DataHelper.split(s, "[,; \r\n\t]");
        if (p.length == 0)
            return null;
        // todo doesn't check for ""
        if (p.length == 1)
            return p[0];
        int i = _context.random().nextInt(p.length);
        return p[i];
    }

    /** @since 0.8.7 */
    private void writeHelperSaveForm(OutputStream outs, String destination, String ahelperKey,
                                     String targetRequest, String referer) throws IOException {
        if(outs == null)
            return;
        Writer out = new BufferedWriter(new OutputStreamWriter(outs, "UTF-8"));
        String header = getErrorPage("ahelper-new", ERR_AHELPER_NEW);
        out.write(header);
        out.write("<table id=\"proxyNewHost\">\n<tr><td align=\"right\">" + _t("Host") +
                "</td><td>" + destination + "</td></tr>\n");
        try {
            String b32 = Base32.encode(SHA256Generator.getInstance().calculateHash(Base64.decode(ahelperKey)).getData());
            out.write("<tr><td align=\"right\">" + _t("Base 32") + "</td>" +
                    "<td><a href=\"http://" + b32 + ".b32.i2p/\">" + b32 + ".b32.i2p</a></td></tr>");
        } catch(Exception e) {
        }
        out.write("<tr><td align=\"right\">" + _t("Destination") + "</td><td>" +
                  "<textarea rows=\"1\" style=\"height: 6em; min-width: 0; min-height: 0;\" cols=\"70\" wrap=\"off\" readonly=\"readonly\" >" + ahelperKey + "</textarea>" +
                  "</td></tr>\n</table>\n" + "<hr>\n" +

                // FIXME if there is a query remaining it is lost
                "<form method=\"GET\" action=\"" + targetRequest + "\">\n" +
                "<h4>" + _t("Continue to {0} without saving", destination) + "</h4>\n<p>" +
                _t("You can browse to the site without saving it to the address book. The address will be remembered until you restart your I2P router.") +
                "</p>\n<div class=\"formaction\"><button type=\"submit\" class=\"go\">" + _t("Continue without saving") + "</button></div>" + "\n</form>\n" +

                "<form method=\"GET\" action=\"http://" + LOCAL_SERVER + "/add\">\n" +
                "<input type=\"hidden\" name=\"host\" value=\"" + destination + "\">\n" +
                "<input type=\"hidden\" name=\"dest\" value=\"" + ahelperKey + "\">\n" +
                "<input type=\"hidden\" name=\"nonce\" value=\"" + _proxyNonce + "\">\n" +

                "<h4>" + _t("Save {0} to router address book and continue to website", destination) + "</h4>\n<p>" +
                _t("This address will be saved to your Router address book where your subscription-based addresses are stored."));
        if(_context.namingService().getName().equals("BlockfileNamingService")) {
            out.write(" " + _t("If you want to keep track of sites you have added manually, add to your Master or Private address book instead."));
        }
        // FIXME wasn't escaped
        String label = _t("Save & continue").replace("&", "&amp;");
        out.write("</p>\n<div class=\"formaction\"><button type=\"submit\" class=\"accept\" name=\"router\" value=\"router\">" +
                  label + "</button></div>\n");

        if(_context.namingService().getName().equals("BlockfileNamingService")) {
            // only blockfile supports multiple books

            out.write("<h4>" + _t("Save {0} to master address book and continue to website", destination) + "</h4>\n<p>" +
            _t("This address will be saved to your Master address book. Select this option for addresses you wish to keep separate from the main router address book, but don't mind publishing.") +
            "</p>\n<div class=\"formaction\"><button type=\"submit\" class=\"accept\" name=\"master\" value=\"master\">" +
            label + "</button></div>\n");

            out.write("<h4>" + _t("Save {0} to private address book and continue to website", destination) + "</h4>\n<p>" +
            _t("This address will be saved to your Private address book, ensuring it is never published.") +
            "</p>\n<div class=\"formaction\"><button type=\"submit\" class=\"accept\" name=\"private\" value=\"private\">" +
            label + "</button></div>\n");

        }
        // Firefox (and others?) don't send referer to meta refresh target, which is
        // what the jump servers use, so this isn't that useful.
        if (referer != null)
            out.write("<input type=\"hidden\" name=\"referer\" value=\"" + referer + "\">\n");
        out.write("<input type=\"hidden\" name=\"url\" value=\"" + targetRequest + "\">\n" +
                "</form>\n</div>\n");
        writeFooter(out);
    }

    /**
     *  Read the first line unbuffered.
     *  After that, switch to a BufferedReader, unless the method is "POST".
     *  We can't use BufferedReader for POST because we can't have readahead,
     *  since we are passing the stream on to I2PTunnelRunner for the POST data.
     *
     *  Warning - BufferedReader removes \r, DataHelper does not
     *  Warning - DataHelper limits line length, BufferedReader does not
     *  Todo: Limit line length for buffered reads, or go back to unbuffered for all
     */
    private static class InputReader {
        InputStream _s;

        public InputReader(InputStream s) {
            _s = s;
        }

        String readLine(String method) throws IOException {
            //  Use unbuffered until we can find a BufferedReader that limits line length
            //if (method == null || "POST".equals(method))
            return DataHelper.readLine(_s);
        //if (_br == null)
        //    _br = new BufferedReader(new InputStreamReader(_s, "ISO-8859-1"));
        //return _br.readLine();
        }

        /**
         *  Read the rest of the headers, which keeps firefox
         *  from complaining about connection reset after
         *  an error on the first line.
         *  @since 0.9.14
         */
        public void drain() {
            try {
                String line;
                do {
                    line = DataHelper.readLine(_s);
                    // \r not stripped so length == 1 is empty
                } while (line != null && line.length() > 1);
            } catch (IOException ioe) {}
        }
    }

    /**
     *  @return b32hash.b32.i2p, or "i2p" on lookup failure.
     *  Prior to 0.7.12, returned b64 key
     */
    private final String getHostName(String host) {
        if(host == null) {
            return null;
        }
        if(host.length() == 60 && host.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
            return host;
        }
        Destination dest = _context.namingService().lookup(host);
        if (dest == null)
            return "i2p";
        return dest.toBase32();
    }

    public static final String DEFAULT_JUMP_SERVERS =
            //"http://i2host.i2p/cgi-bin/i2hostjump?," +
            "http://stats.i2p/cgi-bin/jump.cgi?a=," +
	    "http://no.i2p/jump/," +
	    "http://i2pjump.i2p/jump/";
            //"http://i2jump.i2p/";

    /** @param host ignored */
    private static boolean isSupportedAddress(String host, String protocol) {
        if((host == null) || (protocol == null)) {
            return false;
        }

        /****
         *  Let's not look up the name _again_
         *  and now that host is a b32, this was failing
         *
        boolean found = false;
        String lcHost = host.toLowerCase();
        for (int i = 0; i < SUPPORTED_HOSTS.length; i++) {
        if (SUPPORTED_HOSTS[i].equals(lcHost)) {
        found = true;
        break;
        }
        }

        if (!found) {
        try {
        Destination d = _context.namingService().lookup(host);
        if (d == null) return false;
        } catch (DataFormatException dfe) {
        }
        }
         ****/
        String lc = protocol.toLowerCase(Locale.US);
        return lc.equals("http") || lc.equals("https");
    }

    private final static String ERR_HELPER_DISABLED =
            "HTTP/1.1 403 Disabled\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "Address helpers disabled";

    /**
     *  Change various parts of the URI.
     *  String parameters are all non-encoded.
     *
     *  Scheme always preserved.
     *  Userinfo always cleared.
     *  Host changed if non-null.
     *  Port changed if non-zero.
     *  Path changed if non-null.
     *  Query always preserved.
     *  Fragment always cleared.
     *
     *  @since 0.9
     */
    private static URI changeURI(URI uri, String host, int port, String path) throws URISyntaxException {
        return new URI(uri.getScheme(),
                null,
                host != null ? host : uri.getHost(),
                port != 0 ? port : uri.getPort(),
                path != null ? path : uri.getPath(),
                // FIXME this breaks encoded =, &
                uri.getQuery(),
                null);
    }

    /**
     *  Replace query in the URI.
     *  Userinfo cleared if uri contained a query.
     *  Fragment cleared if uri contained a query.
     *
     *  @param query an ENCODED query, removed if null
     *  @since 0.9
     */
    private static URI replaceQuery(URI uri, String query) throws URISyntaxException {
        URI rv = uri;
        if(rv.getRawQuery() != null) {
            rv = new URI(rv.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null);
        }
        if(query != null) {
            String newURI = rv.toASCIIString() + '?' + query;
            rv = new URI(newURI);
        }
        return rv;
    }

    /**
     *  Remove the address helper from an encoded query.
     *
     *  @param query an ENCODED query, removed if null
     *  @return rv[0] is ENCODED query with helper removed, non-null but possibly empty;
     *          rv[1] is DECODED helper value, non-null but possibly empty;
     *          rv null if no helper present
     *  @since 0.9
     */
    private static String[] removeHelper(String query) {
        int keystart = 0;
        int valstart = -1;
        String key = null;
        for(int i = 0; i <= query.length(); i++) {
            char c = i < query.length() ? query.charAt(i) : '&';
            if(c == ';' || c == '&') {
                // end of key or value
                if(valstart < 0) {
                    key = query.substring(keystart, i);
                }
                String decodedKey = LocalHTTPServer.decode(key);
                if(decodedKey.equals(HELPER_PARAM)) {
                    String newQuery = keystart > 0 ? query.substring(0, keystart - 1) : "";
                    if(i < query.length() - 1) {
                        if(keystart > 0) {
                            newQuery += query.substring(i);
                        } else {
                            newQuery += query.substring(i + 1);
                        }
                    }
                    String value = valstart >= 0 ? query.substring(valstart, i) : "";
                    String helperValue = LocalHTTPServer.decode(value);
                    return new String[] {newQuery, helperValue};
                }
                keystart = i + 1;
                valstart = -1;
            } else if (c == '=' && valstart < 0) {
                // end of key
                key = query.substring(keystart, i);
                valstart = i + 1;
            }
        }
        return null;
    }
    /****
    private static String[] tests = {
        "", "foo", "foo=bar", "&", "&=&", "===", "&&",
        "i2paddresshelper=foo",
        "i2paddresshelpe=foo",
        "2paddresshelper=foo",
        "i2paddresshelper=%66oo",
        "%692paddresshelper=foo",
        "i2paddresshelper=foo&a=b",
        "a=b&i2paddresshelper=foo",
        "a=b&i2paddresshelper&c=d",
        "a=b&i2paddresshelper=foo&c=d",
        "a=b;i2paddresshelper=foo;c=d",
        "a=b&i2paddresshelper=foo&c",
        "a=b&i2paddresshelper=foo==&c",
        "a=b&i2paddresshelper=foo%3d%3d&c",
        "a=b&i2paddresshelper=f%6f%6F==&c",
        "a=b&i2paddresshelper=foo&i2paddresshelper=bar&c",
        "a=b&i2paddresshelper=foo&c%3F%3f%26%3b%3B%3d%3Dc=x%3F%3f%26%3b%3B%3d%3Dx"
    };

    public static void main(String[] args) {
        for (int i = 0; i < tests.length; i++) {
            String[] s = removeHelper(tests[i]);
            if (s != null)
                System.out.println("Test \"" + tests[i] + "\" q=\"" + s[0] + "\" h=\"" + s[1] + "\"");
            else
                System.out.println("Test \"" + tests[i] + "\" no match");
        }
    }
     ****/
}
