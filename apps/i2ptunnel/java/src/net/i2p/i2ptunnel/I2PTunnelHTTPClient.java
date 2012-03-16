/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.i2ptunnel.localServer.LocalHTTPServer;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import net.i2p.util.Translate;

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
 * "squid.i2p").  Only HTTP is supported (no HTTPS, ftp, mailto, etc).  Both GET
 * and POST have been tested, though other $methods should work.
 *
 */
public class I2PTunnelHTTPClient extends I2PTunnelHTTPClientBase implements Runnable {

    /**
     *  Map of host name to base64 destination for destinations collected
     *  via address helper links
     */
    private final ConcurrentHashMap<String, String> addressHelpers = new ConcurrentHashMap(8);

    /**
     *  Used to protect actions via http://proxy.i2p/
     */
    private final String _proxyNonce;

    /**
     *  These are backups if the xxx.ht error page is missing.
     */

    private final static byte[] ERR_REQUEST_DENIED =
        ("HTTP/1.1 403 Access Denied\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: REQUEST DENIED</H1>"+
         "You attempted to connect to a non-I2P website or location.<BR>")
        .getBytes();

    private final static byte[] ERR_DESTINATION_UNKNOWN =
        ("HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: DESTINATION NOT FOUND</H1>"+
         "That I2P Destination was not found. Perhaps you pasted in the "+
         "wrong BASE64 I2P Destination or the link you are following is "+
         "bad. The host (or the WWW proxy, if you're using one) could also "+
         "be temporarily offline.  You may want to <b>retry</b>.  "+
         "Could not find the following Destination:<BR><BR><div>")
        .getBytes();

/*****
    private final static byte[] ERR_TIMEOUT =
        ("HTTP/1.1 504 Gateway Timeout\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n\r\n"+
         "<html><body><H1>I2P ERROR: TIMEOUT</H1>"+
         "That Destination was reachable, but timed out getting a "+
         "response.  This is likely a temporary error, so you should simply "+
         "try to refresh, though if the problem persists, the remote "+
         "destination may have issues.  Could not get a response from "+
         "the following Destination:<BR><BR>")
        .getBytes();
*****/

    private final static byte[] ERR_NO_OUTPROXY =
        ("HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: No outproxy found</H1>"+
         "Your request was for a site outside of I2P, but you have no "+
         "HTTP outproxy configured.  Please configure an outproxy in I2PTunnel")
         .getBytes();

    private final static byte[] ERR_AHELPER_CONFLICT =
        ("HTTP/1.1 409 Conflict\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: Destination key conflict</H1>"+
         "The addresshelper link you followed specifies a different destination key "+
         "than a host entry in your host database. "+
         "Someone could be trying to impersonate another eepsite, "+
         "or people have given two eepsites identical names.<p>"+
         "You can resolve the conflict by considering which key you trust, "+
         "and either discarding the addresshelper link, "+
         "discarding the host entry from your host database, "+
         "or naming one of them differently.<p>")
         .getBytes();

    private final static byte[] ERR_AHELPER_NOTFOUND =
        ("HTTP/1.1 404 Not Found\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: Helper key not resolvable.</H1>"+
         "The helper key you put for i2paddresshelper= is not resolvable. "+
         "It seems to be garbage data, or a mistyped b32. Check your URL "+
         "to try and fix the helper key to be either a b32 or a base64.")
         .getBytes();

    private final static byte[] ERR_AHELPER_NEW =
        ("HTTP/1.1 409 New Address\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>New Host Name with Address Helper</H1>"+
         "The address helper link you followed is for a new host name that is not in your address book. " +
         "You may either save the destination for this host name to your address book, or remember it only until your router restarts. " +
         "If you save it to your address book, you will not see this message again. " +
         "If you do not wish to visit this host, click the \"back\" button on your browser.")
         .getBytes();

    private final static byte[] ERR_BAD_PROTOCOL =
        ("HTTP/1.1 403 Bad Protocol\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: NON-HTTP PROTOCOL</H1>"+
         "The request uses a bad protocol. "+
         "The I2P HTTP Proxy supports http:// requests ONLY. Other protocols such as https:// and ftp:// are not allowed.<BR>")
        .getBytes();

    private final static byte[] ERR_LOCALHOST =
        ("HTTP/1.1 403 Access Denied\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: REQUEST DENIED</H1>"+
         "Your browser is misconfigured. Do not use the proxy to access the router console or other localhost destinations.<BR>")
        .getBytes();

    private final static byte[] ERR_AUTH =
        ("HTTP/1.1 407 Proxy Authentication Required\r\n"+
         "Content-Type: text/html; charset=UTF-8\r\n"+
         "Cache-control: no-cache\r\n"+
         "Accept-Charset: ISO-8859-1,utf-8;q=0.7,*;q=0.5\r\n" +         // try to get a UTF-8-encoded response back for the password
         "Proxy-Authenticate: Basic realm=\"I2P HTTP Proxy\"\r\n" +
         "\r\n"+
         "<html><body><H1>I2P ERROR: PROXY AUTHENTICATION REQUIRED</H1>"+
         "This proxy is configured to require authentication.<BR>")
        .getBytes();

    /**
     *  This constructor always starts the tunnel (ignoring the i2cp.delayOpen option).
     *  It is used to add a client to an existing socket manager.
     *
     *  @param sockMgr the existing socket manager
     */
    public I2PTunnelHTTPClient(int localPort, Logging l, I2PSocketManager sockMgr, I2PTunnel tunnel, EventDispatcher notifyThis, long clientId) {
        super(localPort, l, sockMgr, tunnel, notifyThis, clientId);
        _proxyNonce = Long.toString(_context.random().nextLong());
       // proxyList = new ArrayList();

        setName("HTTP Proxy on " + getTunnel().listenHost + ':' + localPort);
        startRunning();

        notifyEvent("openHTTPClientResult", "ok");
    }
    /**
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelHTTPClient(int localPort, Logging l, boolean ownDest,
                               String wwwProxy, EventDispatcher notifyThis,
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, "HTTP Proxy on " + tunnel.listenHost + ':' + localPort + " #" + (++__clientId), tunnel);
        _proxyNonce = Long.toString(_context.random().nextLong());

        //proxyList = new ArrayList(); // We won't use outside of i2p
        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openHTTPClientResult", "error");
            return;
        }

        if (wwwProxy != null) {
            StringTokenizer tok = new StringTokenizer(wwwProxy, ", ");
            while (tok.hasMoreTokens())
                _proxyList.add(tok.nextToken().trim());
        }

        setName("HTTP Proxy on " + tunnel.listenHost + ':' + localPort);

        startRunning();

        notifyEvent("openHTTPClientResult", "ok");
    }

    /**
     * create the default options (using the default timeout, etc)
     * unused?
     */
    @Override
    protected I2PSocketOptions getDefaultOptions() {
        Properties defaultOpts = getTunnel().getClientOptions();
        if (!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT))
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, ""+DEFAULT_READ_TIMEOUT);
        //if (!defaultOpts.contains("i2p.streaming.inactivityTimeout"))
        //    defaultOpts.setProperty("i2p.streaming.inactivityTimeout", ""+DEFAULT_READ_TIMEOUT);
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        return opts;
    }

    /**
     * create the default options (using the default timeout, etc)
     *
     */
    @Override
    protected I2PSocketOptions getDefaultOptions(Properties overrides) {
        Properties defaultOpts = getTunnel().getClientOptions();
        defaultOpts.putAll(overrides);
        if (!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT))
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, ""+DEFAULT_READ_TIMEOUT);
        if (!defaultOpts.contains("i2p.streaming.inactivityTimeout"))
            defaultOpts.setProperty("i2p.streaming.inactivityTimeout", ""+DEFAULT_READ_TIMEOUT);
        // delayed start
        verifySocketManager();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
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
        super.startRunning();
        this.isr = new InternalSocketRunner(this);
        _context.portMapper().register(PortMapper.SVC_HTTP_PROXY, getLocalPort());
    }

    /**
     * Overridden to close internal socket too.
     */
    @Override
    public boolean close(boolean forced) {
        int reg = _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY);
        if (reg == getLocalPort())
            _context.portMapper().unregister(PortMapper.SVC_HTTP_PROXY);
        boolean rv = super.close(forced);
        if (this.isr != null)
            this.isr.stopRunning();
        return rv;
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

    protected void clientConnectionRun(Socket s) {
        InputStream in = null;
        OutputStream out = null;

        /**
         * The URL after fixup, always starting with http://
         */
        String targetRequest = null;

        boolean usingWWWProxy = false;
        boolean usingInternalServer = false;
        String internalPath = null;
        String internalRawQuery = null;
        String currentProxy = null;
        long requestId = ++__requestId;

        try {
            out = s.getOutputStream();
            InputReader reader = new InputReader(s.getInputStream());
            String line, method = null, protocol = null, host = null, destination = null;
            StringBuilder newRequest = new StringBuilder();
            boolean ahelperPresent = false;
            boolean ahelperNew = false;
            String ahelperKey = null;
            String userAgent = null;
            String authorization = null;
            while ((line = reader.readLine(method)) != null) {
                line = line.trim();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix(requestId) + "Line=[" + line + "]");

                String lowercaseLine = line.toLowerCase(Locale.US);
                if (lowercaseLine.startsWith("connection: ") ||
                    lowercaseLine.startsWith("keep-alive: ") ||
                    lowercaseLine.startsWith("proxy-connection: "))
                    continue;

                if (method == null) { // first line (GET /base64/realaddr)
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(getPrefix(requestId) + "First line [" + line + "]");

                    String[] params = line.split(" ", 3);
                    if (params.length != 3)
                        break;
                    String request = params[1];

                    // various obscure fixups
                    if (request.startsWith("/") && getTunnel().getClientOptions().getProperty("i2ptunnel.noproxy") != null) {
                        // what is this for ???
                        request = "http://i2p" + request;
                    } else if (request.startsWith("/eepproxy/")) {
                        // Deprecated
                        // /eepproxy/foo.i2p/bar/baz.html
                        String subRequest = request.substring("/eepproxy/".length());
                        if (subRequest.indexOf("/") == -1)
                                subRequest += "/";
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

                    // Now use the Java URI parser
                    // This will be the incoming URI but will then get modified
                    // to be the outgoing URI (with http:// if going to outproxy, otherwise without)
                    URI requestURI;
                    try {
                        requestURI = new URI(request);
                        if (requestURI.getRawUserInfo() != null || requestURI.getRawFragment() != null) {
                            // these should never be sent to the proxy in the request line
                            if (_log.shouldLog(Log.WARN))
                                _log.warn(getPrefix(requestId) + "Removing userinfo or fragment [" + request + "]");
                            requestURI = changeURI(requestURI, null, 0, null);
                        }
                        if (requestURI.getPath() == null || requestURI.getPath().length() <= 0) {
                            // Add a path
                            if (_log.shouldLog(Log.WARN))
                                _log.warn(getPrefix(requestId) + "Adding / path to [" + request + "]");
                            requestURI = changeURI(requestURI, null, 0, "/");
                        }
                    } catch (URISyntaxException use) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(getPrefix(requestId) + "Bad request [" + request + "]", use);
                        break;
                    }
                    method = params[0];
                    String protocolVersion = params[2];

                    protocol = requestURI.getScheme();
                    host = requestURI.getHost();
                    if (protocol == null || host == null) {
                        _log.warn("Null protocol or host: " + request);
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
                    if (hostLowerCase.equals(LOCAL_SERVER)) {
                        // so we don't do any naming service lookups
                        destination = host;
                        usingInternalServer = true;
                        internalPath = requestURI.getPath();
                        internalRawQuery = requestURI.getRawQuery();
                    } else if (hostLowerCase.equals("i2p")) {
                        // pull the b64 dest out of the first path element
                        String oldPath = requestURI.getPath().substring(1);
                        int slash = oldPath.indexOf("/");
                        if (slash < 0) {
                            slash = oldPath.length();
                            oldPath += "/";
                        }
                        String dest = oldPath.substring(0, slash);
                        if (slash >= 516 && !dest.contains(".")) {
                            // possible alternative:
                            // redirect to b32
                            destination = dest;
                            host = getHostName(destination);
                            targetRequest = requestURI.toASCIIString();
                            String newURI = oldPath.substring(slash);
                            String query = requestURI.getRawQuery();
                            if (query != null)
                                newURI += '?' + query;
                            try {
                                requestURI = new URI(newURI);
                            } catch (URISyntaxException use) {
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
                    } else if (hostLowerCase.endsWith(".i2p")) {
                        // Destination gets the host name
                        destination = host;
                        // Host becomes the destination's "{b32}.b32.i2p" string, or "i2p" on lookup failure
                        host = getHostName(destination);

                        if (requestURI.getPort() >= 0) {
                            // TODO support I2P ports someday
                            //if (port >= 0)
                            //    host = host + ':' + port;
                            if (_log.shouldLog(Log.WARN))
                                _log.warn(getPrefix(requestId) + "Removing port from [" + request + "]");
                            try {
                                requestURI = changeURI(requestURI, null, -1, null);
                            } catch (URISyntaxException use) {
                                _log.warn(request, use);
                                method = null;
                                break;
                            }
                        }

                        String query = requestURI.getRawQuery();
                        if (query != null) {
                            boolean ahelperConflict = false;

                            // Try to find an address helper in the query
                            String[] helperStrings = removeHelper(query);
                            if (helperStrings != null &&
                                !Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER)).booleanValue()) {
                                query = helperStrings[0];
                                if (query.equals(""))
                                    query = null;
                                try {
                                    requestURI = replaceQuery(requestURI, query);
                                } catch (URISyntaxException use) {
                                    // shouldn't happen
                                    _log.warn(request, use);
                                    method = null;
                                    break;
                                }
                                ahelperKey = helperStrings[1];
                                // Key contains data, lets not ignore it
                                if (ahelperKey.length() > 0) {
                                    if(ahelperKey.endsWith(".i2p")) {
                                        // allow i2paddresshelper=<b32>.b32.i2p syntax.
                                        /*
                                          also i2paddresshelper=name.i2p for aliases
                                          i.e. on your eepsite put 
                                          <a href="?i2paddresshelper=name.i2p">This is the name I want to be called.</a>
                                        */
                                        Destination dest = _context.namingService().lookup(ahelperKey);
                                        if(dest==null) {
                                            if (_log.shouldLog(Log.WARN))
                                                _log.warn(getPrefix(requestId) + "Could not find destination for "+ahelperKey);
                                            byte[] header = getErrorPage("ahelper-notfound", ERR_AHELPER_NOTFOUND);
                                            out.write(header);
                                            out.write(("<p>" + _("This seems to be a bad destination:") + " " + ahelperKey + " " + _("i2paddresshelper cannot help you with a destination like that!") + "</p>").getBytes("UTF-8"));
                                            writeFooter(out);
                                            // XXX: should closeSocket(s) be in a finally block?
                                            closeSocket(s);
                                            return;
                                        }
                                        ahelperKey = dest.toBase64();
                                    } 

                                    ahelperPresent = true;
                                    // ahelperKey will be validated later
                                    if (host == null || "i2p".equals(host)) {
                                        // Host lookup failed - resolvable only with addresshelper
                                        // Store in local HashMap unless there is conflict
                                        String old = addressHelpers.putIfAbsent(destination.toLowerCase(Locale.US), ahelperKey);
                                        ahelperNew = old == null;
                                        if ((!ahelperNew) && !old.equals(ahelperKey)) {
                                            // Conflict: handle when URL reconstruction done
                                            ahelperConflict = true;
                                            if (_log.shouldLog(Log.WARN))
                                                _log.warn(getPrefix(requestId) + "Addresshelper key conflict for site [" + destination +
                                                          "], trusted key [" + old + "], specified key [" + ahelperKey + "].");
                                        }
                                    } else {
                                        // If the host is resolvable from database, verify addresshelper key
                                        // Silently bypass correct keys, otherwise alert
                                        Destination hostDest = _context.namingService().lookup(destination);
                                        if (hostDest != null) {
                                            String destB64 = hostDest.toBase64();
                                            if (destB64 != null && !destB64.equals(ahelperKey)) {
                                                // Conflict: handle when URL reconstruction done
                                                ahelperConflict = true;
                                                if (_log.shouldLog(Log.WARN))
                                                    _log.warn(getPrefix(requestId) + "Addresshelper key conflict for site [" + destination +
                                                              "], trusted key [" + destB64 + "], specified key [" + ahelperKey + "].");
                                                
                                            }
                                        }
                                    }
                                } // ahelperKey
                            } // helperstrings

                            // Did addresshelper key conflict?
                            if (ahelperConflict) {
                                if (out != null) {
                                    // convert ahelperKey to b32
                                    String alias = getHostName(ahelperKey);
                                    if (alias.equals("i2p")) {
                                        // bad ahelperKey
                                        byte[] header = getErrorPage("dnfb", ERR_DESTINATION_UNKNOWN);
                                        writeErrorMessage(header, out, targetRequest, false, destination, null);
                                    } else {
                                        String trustedURL = requestURI.toASCIIString();
                                        URI conflictURI;
                                        try {
                                            conflictURI = changeURI(requestURI, alias, 0, null);
                                        } catch (URISyntaxException use) {
                                            // shouldn't happen
                                            _log.warn(request, use);
                                            method = null;
                                            break;
                                        }
                                        String conflictURL = conflictURI.toASCIIString();
                                        byte[] header = getErrorPage("ahelper-conflict", ERR_AHELPER_CONFLICT);
                                        out.write(header);
                                        out.write(_("To visit the destination in your host database, click <a href=\"{0}\">here</a>. To visit the conflicting addresshelper destination, click <a href=\"{1}\">here</a>.", trustedURL, conflictURL).getBytes("UTF-8"));
                                        out.write(("</p></div>").getBytes());
                                        writeFooter(out);
                                    }
                                }
                                s.close();
                                return;
                            }
                        }  // end query processing

                        String addressHelper = addressHelpers.get(destination);
                        if (addressHelper != null)
                            host = getHostName(addressHelper);

                        // now strip everything but path and query from URI
                        targetRequest = requestURI.toASCIIString();
                        String newURI = requestURI.getRawPath();
                        if (query != null)
                            newURI += '?' + query;
                        try {
                            requestURI = new URI(newURI);
                        } catch (URISyntaxException use) {
                            // shouldnt happen
                            _log.warn(request, use);
                            method = null;
                            break;
                        }

                        // end of (host endsWith(".i2p"))

                    } else if (hostLowerCase.equals("localhost") || host.equals("127.0.0.1") ||
                               host.startsWith("192.168.") || host.equals("[::1]")) {
                        // if somebody is trying to get to 192.168.example.com, oh well
                        if (out != null) {
                            out.write(getErrorPage("localhost", ERR_LOCALHOST));
                            writeFooter(out);
                        }
                        s.close();
                        return;
                    } else if (host.contains(".") || host.startsWith("[")) {
                        if (port >= 0)
                            host = host + ':' + port;
                        // The request must be forwarded to a WWW proxy
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Before selecting outproxy for " + host);
                        currentProxy = selectProxy();
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("After selecting outproxy for " + host + ": " + currentProxy);
                        if (currentProxy == null) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn(getPrefix(requestId) + "Host wants to be outproxied, but we dont have any!");
                            l.log("No HTTP outproxy found for the request.");
                            if (out != null) {
                                out.write(getErrorPage("noproxy", ERR_NO_OUTPROXY));
                                writeFooter(out);
                            }
                            s.close();
                            return;
                        }
                        destination = currentProxy;
                        usingWWWProxy = true;
                        targetRequest = requestURI.toASCIIString();
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(getPrefix(requestId) +  " [" + host + "]: wwwProxy!");
                    } else {
                        // what is left for here? a hostname with no dots, and != "i2p"
                        // and not a destination ???
                        // Perhaps something in privatehosts.txt ...
                        // Rather than look it up, just bail out.
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("NODOTS, NOI2P: " + request);
                        if (out != null) {
                            out.write(getErrorPage("denied", ERR_REQUEST_DENIED));
                            writeFooter(out);
                        }
                        s.close();
                        return;
                    }   // end host name processing

                    boolean isValid = usingWWWProxy || usingInternalServer || isSupportedAddress(host, protocol);
                    if (!isValid) {
                        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix(requestId) + "notValid(" + host + ")");
                        method = null;
                        destination = null;
                        break;
                    }

                    line = method + ' ' + requestURI.toASCIIString() + ' ' + protocolVersion;

                    if (_log.shouldLog(Log.DEBUG)) {
                        _log.debug(getPrefix(requestId) + "NEWREQ: \"" + line + "\"");
                        _log.debug(getPrefix(requestId) + "HOST  : \"" + host + "\"");
                        _log.debug(getPrefix(requestId) + "DEST  : \"" + destination + "\"");
                    }

                    // end first line processing

                } else {
                    if (lowercaseLine.startsWith("host: ") && !usingWWWProxy) {
                        // Note that we only pass the original Host: line through to the outproxy
                        // But we don't create a Host: line if it wasn't sent to us
                        line = "Host: " + host;
                        if (_log.shouldLog(Log.INFO))
                            _log.info(getPrefix(requestId) + "Setting host = " + host);
                    } else if (lowercaseLine.startsWith("user-agent: ")) {
                        // save for deciding whether to offer address book form
                        userAgent = lowercaseLine.substring(12);
                        if (!Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_USER_AGENT)).booleanValue()) {
                            line = null;
                            continue;
                        }
                    } else if (lowercaseLine.startsWith("accept")) {
                        // strip the accept-blah headers, as they vary dramatically from
                        // browser to browser
                        line = null;
                        continue;
                    } else if (lowercaseLine.startsWith("referer: ") &&
                               !Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_REFERER)).booleanValue()) {
                        // Shouldn't we be more specific, like accepting in-site referers ?
                        //line = "Referer: i2p";
                        line = null;
                        continue; // completely strip the line
                    } else if (lowercaseLine.startsWith("via: ") &&
                               !Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_VIA)).booleanValue()) {
                        //line = "Via: i2p";
                        line = null;
                        continue; // completely strip the line
                    } else if (lowercaseLine.startsWith("from: ")) {
                        //line = "From: i2p";
                        line = null;
                        continue; // completely strip the line
                    } else if (lowercaseLine.startsWith("authorization: ntlm ")) {
                        // Block Windows NTLM after 401
                        line = null;
                        continue;
                    } else if (lowercaseLine.startsWith("proxy-authorization: ")) {
                        // This should be for us. It is a
                        // hop-by-hop header, and we definitely want to block Windows NTLM after a far-end 407.
                        // Response to far-end shouldn't happen, as we
                        // strip Proxy-Authenticate from the response in HTTPResponseOutputStream
                        if (lowercaseLine.startsWith("proxy-authorization: basic "))
                            // save for auth check below
                            authorization = line.substring(27);  // "proxy-authorization: basic ".length()
                        line = null;
                        continue;
                    }
                }

                if (line.length() == 0) {
                    // No more headers, add our own and break out of the loop
                    String ok = getTunnel().getClientOptions().getProperty("i2ptunnel.gzip");
                    boolean gzip = DEFAULT_GZIP;
                    if (ok != null)
                        gzip = Boolean.valueOf(ok).booleanValue();
                    if (gzip && !usingInternalServer) {
                        // according to rfc2616 s14.3, this *should* force identity, even if
                        // an explicit q=0 for gzip doesn't.  tested against orion.i2p, and it
                        // seems to work.
                        newRequest.append("Accept-Encoding: \r\n");
                        newRequest.append("X-Accept-Encoding: x-i2p-gzip;q=1.0, identity;q=0.5, deflate;q=0, gzip;q=0, *;q=0\r\n");
                    }
                    if (!Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_USER_AGENT)).booleanValue()) {
                        // let's not advertise to external sites that we are from I2P
                        if (usingWWWProxy)
                            newRequest.append("User-Agent: Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.9.2.6) Gecko/20100625 Firefox/3.6.6\r\n");
                        else
                            newRequest.append("User-Agent: MYOB/6.66 (AN/ON)\r\n");
                    }
                    // Add Proxy-Authentication header for next hop (outproxy)
                    if (usingWWWProxy && Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_AUTH)).booleanValue()) {
                        // specific for this proxy
                        String user = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_USER_PREFIX + currentProxy);
                        String pw = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_PW_PREFIX + currentProxy);
                        if (user == null || pw == null) {
                            // if not, look at default user and pw
                            user = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_USER);
                            pw = getTunnel().getClientOptions().getProperty(PROP_OUTPROXY_PW);
                        }
                        if (user != null && pw != null) {
                            newRequest.append("Proxy-Authorization: Basic ")
                                      .append(Base64.encode((user + ':' + pw).getBytes(), true))    // true = use standard alphabet
                                      .append("\r\n");
                        }
                    }
	            newRequest.append("Connection: close\r\n\r\n");
                    break;
                } else {
                    newRequest.append(line).append("\r\n"); // HTTP spec
                }
            } // end header processing

            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix(requestId) + "NewRequest header: [" + newRequest.toString() + "]");

            if (method == null || destination == null) {
                //l.log("No HTTP method found in the request.");
                if (out != null) {
                    if (protocol != null && "http".equals(protocol.toLowerCase(Locale.US)))
                        out.write(getErrorPage("denied", ERR_REQUEST_DENIED));
                    else
                        out.write(getErrorPage("protocol", ERR_BAD_PROTOCOL));
                    writeFooter(out);
                }
                s.close();
                return;
            }

            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix(requestId) + "Destination: " + destination);

            // Authorization
            if (!authorize(s, requestId, authorization)) {
                if (_log.shouldLog(Log.WARN)) {
                    if (authorization != null)
                        _log.warn(getPrefix(requestId) + "Auth failed, sending 407 again");
                    else
                        _log.warn(getPrefix(requestId) + "Auth required, sending 407");
                }
                out.write(getErrorPage("auth", ERR_AUTH));
                writeFooter(out);
                s.close();
                return;
            }

            // Serve local proxy files (images, css linked from error pages)
            // Ignore all the headers
            if (usingInternalServer) {
                // disable the add form if address helper is disabled
                if (internalPath.equals("/add") &&
                    Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER)).booleanValue()) {
                    out.write(ERR_HELPER_DISABLED);
                } else {
                    LocalHTTPServer.serveLocalFile(out, method, internalPath, internalRawQuery, _proxyNonce);
                }
                s.close();
                return;
            }

            // LOOKUP
            // If the host is "i2p", the getHostName() lookup failed, don't try to
            // look it up again as the naming service does not do negative caching
            // so it will be slow.
            Destination clientDest = null;
            String addressHelper = addressHelpers.get(destination.toLowerCase(Locale.US));
            if (addressHelper != null) {
                clientDest = _context.namingService().lookup(addressHelper);
                if (clientDest == null) {
                    // remove bad entries
                    addressHelpers.remove(destination.toLowerCase(Locale.US));
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getPrefix(requestId) + "Could not find destination for " + addressHelper);
                    byte[] header = getErrorPage("ahelper-notfound", ERR_AHELPER_NOTFOUND);
                    writeErrorMessage(header, out, targetRequest, false, destination, null);
                    s.close();
                    return;
                }
            } else if ("i2p".equals(host)) {
                clientDest = null;
            } else if (destination.length() == 60 && destination.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
                // use existing session to look up for efficiency
                verifySocketManager();
                I2PSession sess = sockMgr.getSession();
                if (sess != null && !sess.isClosed()) {
                    byte[] hData = Base32.decode(destination.substring(0, 52));
                    if (hData != null) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("lookup in-session " + destination);
                        Hash hash = Hash.create(hData);
                        clientDest = sess.lookupDest(hash, 20*1000);
                    }
                } else {
                    clientDest = _context.namingService().lookup(destination);
                }
            } else {
                clientDest = _context.namingService().lookup(destination);
            }

            if (clientDest == null) {
                //l.log("Could not resolve " + destination + ".");
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to resolve " + destination + " (proxy? " + usingWWWProxy + ", request: " + targetRequest);
                byte[] header;
                String jumpServers = null;
                if (usingWWWProxy)
                    header = getErrorPage("dnfp", ERR_DESTINATION_UNKNOWN);
                else if (ahelperPresent)
                    header = getErrorPage("dnfb", ERR_DESTINATION_UNKNOWN);
                else if (destination.length() == 60 && destination.toLowerCase(Locale.US).endsWith(".b32.i2p"))
                    header = getErrorPage("dnf", ERR_DESTINATION_UNKNOWN);
                else {
                    header = getErrorPage("dnfh", ERR_DESTINATION_UNKNOWN);
                    jumpServers = getTunnel().getClientOptions().getProperty(PROP_JUMP_SERVERS);
                    if (jumpServers == null)
                        jumpServers = DEFAULT_JUMP_SERVERS;
                }
                writeErrorMessage(header, out, targetRequest, usingWWWProxy, destination, jumpServers);
                s.close();
                return;
            }

            // Address helper response form
            // This will only load once - the second time it won't be "new"
            // Don't do this for eepget, which uses a user-agent of "Wget"
            if (ahelperNew && "GET".equals(method) &&
                (userAgent == null || !userAgent.startsWith("Wget")) &&
                !Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER)).booleanValue()) {
                writeHelperSaveForm(out, destination, ahelperKey, targetRequest);
                s.close();
                return;
            }

            // Redirect to non-addresshelper URL to not clog the browser address bar
            // and not pass the parameter to the eepsite.
            // This also prevents the not-found error page from looking bad
            // Syndie can't handle a redirect of a POST
            if (ahelperPresent && !"POST".equals(method)) {
                String uri = targetRequest;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Auto redirecting to " + uri);
                out.write(("HTTP/1.1 301 Address Helper Accepted\r\n"+
                          "Location: " + uri + "\r\n"+
                          "\r\n").getBytes("UTF-8"));
                s.close();
                return;
            }

            Properties opts = new Properties();
            //opts.setProperty("i2p.streaming.inactivityTimeout", ""+120*1000);
            // 1 == disconnect.  see ConnectionOptions in the new streaming lib, which i
            // dont want to hard link to here
            //opts.setProperty("i2p.streaming.inactivityTimeoutAction", ""+1);
            I2PSocket i2ps = createI2PSocket(clientDest, getDefaultOptions(opts));
            byte[] data = newRequest.toString().getBytes("ISO-8859-1");
            Runnable onTimeout = new OnTimeout(s, s.getOutputStream(), targetRequest, usingWWWProxy, currentProxy, requestId);
            I2PTunnelRunner runner = new I2PTunnelHTTPClientRunner(s, i2ps, sockLock, data, mySockets, onTimeout);
        } catch (SocketException ex) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            //l.log("Error connecting: " + ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (IOException ex) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            //l.log("Error connecting: " + ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (I2PException ex) {
            if (_log.shouldLog(Log.INFO))
                _log.info("getPrefix(requestId) + Error trying to connect", ex);
            //l.log("Error connecting: " + ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (OutOfMemoryError oom) {
            IOException ex = new IOException("OOM");
            _log.error("getPrefix(requestId) + Error trying to connect", oom);
            //l.log("Error connecting: " + ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        }
    }

    /** @since 0.8.7 */
    private void writeHelperSaveForm(OutputStream out, String destination, String ahelperKey, String targetRequest) throws IOException {
        if (out == null)
            return;
        byte[] header = getErrorPage("ahelper-new", ERR_AHELPER_NEW);
        out.write(header);
        out.write(("<table><tr><td class=\"mediumtags\" align=\"right\">" + _("Host") +
                   "</td><td class=\"mediumtags\">" + destination + "</td></tr>\n").getBytes());
        try {
            String b32 = Base32.encode(SHA256Generator.getInstance().calculateHash(Base64.decode(ahelperKey)).getData());
            out.write(("<tr><td class=\"mediumtags\" align=\"right\">" + _("Base 32") + "</td>" +
                       "<td><a href=\"http://" + b32 + ".b32.i2p/\">" + b32 + ".b32.i2p</a></td></tr>").getBytes());
        } catch (Exception e) {}
        out.write(("<tr><td class=\"mediumtags\" align=\"right\">" + _("Destination") + "</td><td>" +
                   "<textarea rows=\"1\" style=\"height: 4em; min-width: 0; min-height: 0;\" cols=\"70\" wrap=\"off\" readonly=\"readonly\" >" +
                   ahelperKey + "</textarea></td></tr></table>\n" +
                   "<hr><div class=\"formaction\">"+
                   // FIXME if there is a query remaining it is lost
                   "<form method=\"GET\" action=\"" + targetRequest + "\">" +
                   "<button type=\"submit\" class=\"go\">" + _("Continue to {0} without saving", destination) + "</button>" +
                   "</form>\n<form method=\"GET\" action=\"http://" + LOCAL_SERVER + "/add\">" +
                   "<input type=\"hidden\" name=\"host\" value=\"" + destination + "\">\n" +
                   "<input type=\"hidden\" name=\"dest\" value=\"" + ahelperKey + "\">\n" +
                   "<input type=\"hidden\" name=\"nonce\" value=\"" + _proxyNonce + "\">\n" +
                   "<button type=\"submit\" class=\"accept\" name=\"router\" value=\"router\">" + _("Save {0} to router address book and continue to eepsite", destination) + "</button><br>\n").getBytes("UTF-8"));
        if (_context.namingService().getName().equals("BlockfileNamingService")) {
            // only blockfile supports multiple books
            out.write(("<br><button type=\"submit\" name=\"master\" value=\"master\">" + _("Save {0} to master address book and continue to eepsite", destination) + "</button><br>\n").getBytes("UTF-8"));
            out.write(("<button type=\"submit\" name=\"private\" value=\"private\">" + _("Save {0} to private address book and continue to eepsite", destination) + "</button>\n").getBytes("UTF-8"));
        }
        out.write(("<input type=\"hidden\" name=\"url\" value=\"" + targetRequest + "\">\n" +
                   "</form></div></div>").getBytes());
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
        BufferedReader _br;
        InputStream _s;
        public InputReader(InputStream s) {
            _br = null;
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
    }

    /**
     *  @return b32hash.b32.i2p, or "i2p" on lookup failure.
     *  Prior to 0.7.12, returned b64 key
     */
    private final String getHostName(String host) {
        if (host == null) return null;
        if (host.length() == 60 && host.toLowerCase(Locale.US).endsWith(".b32.i2p"))
            return host;
        Destination dest = _context.namingService().lookup(host);
        if (dest == null) return "i2p";
        return Base32.encode(dest.calculateHash().getData()) + ".b32.i2p";
    }

    /**
     *  foo => errordir/foo-header_xx.ht for lang xx, or errordir/foo-header.ht,
     *  or the backup byte array on fail.
     *
     *  .ht files must be UTF-8 encoded and use \r\n terminators so the
     *  HTTP headers are conformant.
     *  We can't use FileUtil.readFile() because it strips \r
     *
     *  @return non-null
     */
    private byte[] getErrorPage(String base, byte[] backup) {
        return getErrorPage(_context, base, backup);
    }

    private static byte[] getErrorPage(I2PAppContext ctx, String base, byte[] backup) {
         File errorDir = new File(ctx.getBaseDir(), "docs");
         String lang = ctx.getProperty("routerconsole.lang", Locale.getDefault().getLanguage());
         if (lang != null && lang.length() > 0 && !lang.equals("en")) {
             File file = new File(errorDir, base + "-header_" + lang + ".ht");
             try {
                 return readFile(file);
             } catch (IOException ioe) {
                 // try the english version now
             }
         }
         File file = new File(errorDir, base + "-header.ht");
         try {
             return readFile(file);
         } catch (IOException ioe) {
             return backup;
         }
    }

    private static byte[] readFile(File file) throws IOException {
         FileInputStream fis = null;
         byte[] buf = new byte[512];
         ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
         try {
             int len = 0;
             fis = new FileInputStream(file);
             while ((len = fis.read(buf)) > 0) {
                 baos.write(buf, 0, len);
             }
             return baos.toByteArray();
         } finally {
             try { if (fis != null) fis.close(); } catch (IOException foo) {}
         }
         // we won't ever get here
    }

    /**
     *  Public only for LocalHTTPServer, not for general use
     */
    public static void writeFooter(OutputStream out) throws IOException {
        // the css is hiding this div for now, but we'll keep it here anyway
        out.write("<div class=\"proxyfooter\"><p><i>I2P HTTP Proxy Server<br>Generated on: ".getBytes());
        out.write(new Date().toString().getBytes());
        out.write("</i></div></body></html>\n".getBytes());
        out.flush();
    }

    private static class OnTimeout implements Runnable {
        private Socket _socket;
        private OutputStream _out;
        private String _target;
        private boolean _usingProxy;
        private String _wwwProxy;
        private long _requestId;
        public OnTimeout(Socket s, OutputStream out, String target, boolean usingProxy, String wwwProxy, long id) {
            _socket = s;
            _out = out;
            _target = target;
            _usingProxy = usingProxy;
            _wwwProxy = wwwProxy;
            _requestId = id;
        }
        public void run() {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Timeout occured requesting " + _target);
            handleHTTPClientException(new RuntimeException("Timeout"), _out,
                                      _target, _usingProxy, _wwwProxy, _requestId);
            closeSocket(_socket);
        }
    }

    public static final String DEFAULT_JUMP_SERVERS =
                                           "http://i2host.i2p/cgi-bin/i2hostjump?," +
                                           "http://stats.i2p/cgi-bin/jump.cgi?a=," +
                                           "http://i2jump.i2p/";

    /**
     *  @param jumpServers comma- or space-separated list, or null
     */
    private static void writeErrorMessage(byte[] errMessage, OutputStream out, String targetRequest,
                                          boolean usingWWWProxy, String wwwProxy, String jumpServers) throws IOException {
        if (out != null) {
            out.write(errMessage);
            if (targetRequest != null) {
                String uri = targetRequest.replace("&", "&amp;");
                out.write("<a href=\"".getBytes());
                out.write(uri.getBytes());
                out.write("\">".getBytes());
                out.write(uri.getBytes());
                out.write("</a>".getBytes());
                if (usingWWWProxy) {
                    out.write(("<br><br><b>").getBytes());
                    out.write(_("HTTP Outproxy").getBytes("UTF-8"));
                    out.write((":</b> " + wwwProxy).getBytes());
                }
                if (jumpServers != null && jumpServers.length() > 0) {
                    out.write("<br><br>".getBytes());
                    out.write(_("Click a link below to look for an address helper by using a \"jump\" service:").getBytes("UTF-8"));
                    out.write("<br>\n".getBytes());

                    if (uri.startsWith("http://"))
                        uri = uri.substring(7);
                    StringTokenizer tok = new StringTokenizer(jumpServers, ", ");
                    while (tok.hasMoreTokens()) {
                        String jurl = tok.nextToken();
                        if (!jurl.startsWith("http://"))
                            continue;
                        // Skip jump servers we don't know
                        String jumphost = jurl.substring(7);  // "http://"
                        jumphost = jumphost.substring(0, jumphost.indexOf('/'));
                        if (!jumphost.endsWith(".i2p"))
                            continue;
                        if (!jumphost.endsWith(".b32.i2p")) {
                            Destination dest = I2PAppContext.getGlobalContext().namingService().lookup(jumphost);
                            if (dest == null) continue;
                        }

                        out.write("<br><a href=\"".getBytes());
                        out.write(jurl.getBytes());
                        out.write(uri.getBytes());
                        out.write("\">".getBytes());
                        out.write(jurl.substring(7).getBytes());
                        out.write(uri.getBytes());
                        out.write("</a>\n".getBytes());
                    }
                }
            }
            out.write("</div>".getBytes());
            writeFooter(out);
        }
    }

    private static void handleHTTPClientException(Exception ex, OutputStream out, String targetRequest,
                                                  boolean usingWWWProxy, String wwwProxy, long requestId) {

        // static
        //if (_log.shouldLog(Log.WARN))
        //    _log.warn(getPrefix(requestId) + "Error sending to " + wwwProxy + " (proxy? " + usingWWWProxy + ", request: " + targetRequest, ex);
        if (out != null) {
            try {
                byte[] header;
                if (usingWWWProxy)
                    header = getErrorPage(I2PAppContext.getGlobalContext(), "dnfp", ERR_DESTINATION_UNKNOWN);
                else
                    header = getErrorPage(I2PAppContext.getGlobalContext(), "dnf", ERR_DESTINATION_UNKNOWN);
                writeErrorMessage(header, out, targetRequest, usingWWWProxy, wwwProxy, null);
            } catch (IOException ioe) {
                // static
                //_log.warn(getPrefix(requestId) + "Error writing out the 'destination was unknown' " + "message", ioe);
            }
        } else {
            // static
            //_log.warn(getPrefix(requestId) + "Client disconnected before we could say that destination " + "was unknown", ex);
        }
    }

    private final static String SUPPORTED_HOSTS[] = { "i2p", "www.i2p.com", "i2p."};

    /** @param host ignored */
    private static boolean isSupportedAddress(String host, String protocol) {
        if ((host == null) || (protocol == null)) return false;

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
        return protocol.toLowerCase(Locale.US).equals("http");
    }

    private final static byte[] ERR_HELPER_DISABLED =
        ("HTTP/1.1 403 Disabled\r\n"+
         "Content-Type: text/plain\r\n"+
         "\r\n"+
         "Address helpers disabled")
        .getBytes();

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
        if (rv.getRawQuery() != null) {
            rv = new URI(rv.getScheme(),
                       null,
                       uri.getHost(),
                       uri.getPort(),
                       uri.getPath(),
                       null,
                       null);
        }
        if (query != null) {
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
        for (int i = 0; i <= query.length(); i++) {
            char c = i < query.length() ? query.charAt(i) : '&';
            if (c == ';' || c == '&') {
                // end of key or value
                if (valstart < 0)
                    key = query.substring(keystart, i);
                String decodedKey = LocalHTTPServer.decode(key);
                if (decodedKey.equals(HELPER_PARAM)) {
                    String newQuery = keystart > 0 ? query.substring(0, keystart - 1) : "";
                    if (i < query.length() - 1) {
                        if (keystart > 0)
                            newQuery += query.substring(i);
                        else
                            newQuery += query.substring(i + 1);
                    }
                    String value = valstart >= 0 ? query.substring(valstart, i) : "";
                    String helperValue = LocalHTTPServer.decode(value);
                    return new String[] { newQuery, helperValue };
                }
                keystart = i + 1;
                valstart = -1;
            } else if (c == '=') {
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
        "a=b&i2paddresshelper=foo&c"
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

    /** */
    private static final String BUNDLE_NAME = "net.i2p.i2ptunnel.web.messages";

    /** lang in routerconsole.lang property, else current locale */
    protected static String _(String key) {
        return Translate.getString(key, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /** {0} */
    protected static String _(String key, Object o) {
        return Translate.getString(key, o, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /** {0} and {1} */
    protected static String _(String key, Object o, Object o2) {
        return Translate.getString(key, o, o2, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

}
