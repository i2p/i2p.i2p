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
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.EventDispatcher;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
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
    }

    /**
     * Overridden to close internal socket too.
     */
    @Override
    public boolean close(boolean forced) {
        boolean rv = super.close(forced);
        if (this.isr != null)
            this.isr.stopRunning();
        return rv;
    }

    private static final String LOCAL_SERVER = "proxy.i2p";
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
        String targetRequest = null;
        boolean usingWWWProxy = false;
        boolean usingInternalServer = false;
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

                    int pos = line.indexOf(" ");
                    if (pos == -1) break;
                    method = line.substring(0, pos);
                    // TODO use Java URL class to make all this simpler and more robust
                    // That will also fix IPV6 [a:b:c]
                    String request = line.substring(pos + 1);
                    if (request.startsWith("/") && getTunnel().getClientOptions().getProperty("i2ptunnel.noproxy") != null) {
                        // what is this for ???
                        request = "http://i2p" + request;
                    } else if (request.startsWith("/eepproxy/")) {
                        // /eepproxy/foo.i2p/bar/baz.html HTTP/1.0
                        String subRequest = request.substring("/eepproxy/".length());
                        int protopos = subRequest.indexOf(" ");
                        String uri = subRequest.substring(0, protopos);
                        if (uri.indexOf("/") == -1) {
                                uri = uri + "/";
                        }
                        // "http://" + "foo.i2p/bar/baz.html" + " HTTP/1.0"
                        request = "http://" + uri + subRequest.substring(protopos);
                    } else if (request.toLowerCase(Locale.US).startsWith("http://i2p/")) {
                        // http://i2p/b64key/bar/baz.html HTTP/1.0
                        String subRequest = request.substring("http://i2p/".length());
                        int protopos = subRequest.indexOf(" ");
                        String uri = subRequest.substring(0, protopos);
                        if (uri.indexOf("/") == -1) {
                                uri = uri + "/";
                        }
                        // "http://" + "b64key/bar/baz.html" + " HTTP/1.0"
                        request = "http://" + uri + subRequest.substring(protopos);
                    }

                    pos = request.indexOf("//");
                    if (pos == -1) {
                        method = null;
                        break;
                    }
                    protocol = request.substring(0, pos + 2);
                    request = request.substring(pos + 2);

                    // "foo.i2p/bar/baz HTTP/1.1", with any i2paddresshelper parameter removed
                    targetRequest = request;

                    // pos is the start of the path
                    pos = request.indexOf("/");
                    if (pos == -1) {
                        //pos = request.length();
                        method = null;
                        break;
                    }
                    host = request.substring(0, pos);

                    // parse port
                    int posPort = host.indexOf(":");
                    int port = 80;
                    if(posPort != -1) {
                        String[] parts = host.split(":");
                        try {
                        host = parts[0];
                        } catch (ArrayIndexOutOfBoundsException ex) {
                        if (out != null) {
                            out.write(getErrorPage("denied", ERR_REQUEST_DENIED));
                            writeFooter(out);
                        }
                        s.close();
                        return;

                        }
                        try {
                            port = Integer.parseInt(parts[1]);
                        } catch(Exception exc) {
                            // TODO: log this
                        }
                    }

                    // Go through the various types of host names, set
                    // the host and destination variables accordingly,
                    // and transform the first line.
                    // For all i2p network hosts, ensure that the host is a
                    // Base 32 hostname so that we do not reveal our name for it
                    // in our addressbook (all naming is local),
                    // and it is removed from the request line.

                    if (host.length() >= 516 && host.indexOf(".") < 0) {
                        // http://b64key/bar/baz.html
                        destination = host;
                        host = getHostName(destination);
                        line = method + ' ' + request.substring(pos);
                    } else if (host.toLowerCase(Locale.US).equals(LOCAL_SERVER)) {
                        // so we don't do any naming service lookups
                        destination = host;
                        usingInternalServer = true;
                    } else if (host.toLowerCase(Locale.US).endsWith(".i2p")) {
                        // Destination gets the host name
                        destination = host;
                        // Host becomes the destination's "{b32}.b32.i2p" string, or "i2p" on lookup failure
                        host = getHostName(destination);

                        int pos2;
                        if ((pos2 = request.indexOf("?")) != -1) {
                            // Try to find an address helper in the fragments
                            // and split the request into it's component parts for rebuilding later
                            boolean ahelperConflict = false;

                            String fragments = request.substring(pos2 + 1);
                            String uriPath = request.substring(0, pos2);
                            pos2 = fragments.indexOf(" ");
                            String protocolVersion = fragments.substring(pos2 + 1);
                            String urlEncoding = "";
                            fragments = fragments.substring(0, pos2);
                            String initialFragments = fragments;
                            // FIXME split on ';' also
                            fragments = fragments + "&";
                            String fragment;
                            while(fragments.length() > 0) {
                                pos2 = fragments.indexOf("&");
                                fragment = fragments.substring(0, pos2);
                                fragments = fragments.substring(pos2 + 1);

                                // Fragment looks like addresshelper key
                                if (fragment.startsWith("i2paddresshelper=") &&
                                    !Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER)).booleanValue()) {
                                    pos2 = fragment.indexOf("=");
                                    ahelperKey = fragment.substring(pos2 + 1);
                                    // Key contains data, lets not ignore it
                                    if (ahelperKey != null) {
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
                                } else {
                                    // Other fragments, just pass along
                                    // Append each fragment to urlEncoding
                                    if ("".equals(urlEncoding)) {
                                        urlEncoding = "?" + fragment;
                                    } else {
                                        urlEncoding = urlEncoding + "&" + fragment;
                                    }
                                }
                            }
                            // Reconstruct the request minus the i2paddresshelper GET var
                            request = uriPath + urlEncoding + " " + protocolVersion;
                            targetRequest = request;

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
                                        String trustedURL = protocol + uriPath + urlEncoding;
                                        // Fixme - any path is lost
                                        String conflictURL = protocol + alias + '/' + urlEncoding;
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

                        line = method + " " + request.substring(pos);
                        // end of (host endsWith(".i2p"))

                    } else if (host.toLowerCase(Locale.US).equals("localhost") || host.equals("127.0.0.1") ||
                               host.startsWith("192.168.")) {
                        // if somebody is trying to get to 192.168.example.com, oh well
                        if (out != null) {
                            out.write(getErrorPage("localhost", ERR_LOCALHOST));
                            writeFooter(out);
                        }
                        s.close();
                        return;
                    } else if (host.indexOf(".") != -1) {
                        // rebuild host
                        host = host + ":" + port;
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
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(getPrefix(requestId) + "Host doesnt end with .i2p and it contains a period [" + host + "]: wwwProxy!");
                    } else {
                        // what is left for here? a hostname with no dots, and != "i2p"
                        // and not a destination ???
                        // Perhaps something in privatehosts.txt ...
                        request = request.substring(pos + 1);
                        pos = request.indexOf("/");
                        if (pos < 0) {
                            l.log("Invalid request url [" + request + "]");
                            if (out != null) {
                                out.write(getErrorPage("denied", ERR_REQUEST_DENIED));
                                writeFooter(out);
                            }
                            s.close();
                            return;
                        }
                        destination = request.substring(0, pos);
                        host = getHostName(destination);
                        line = method + " " + request.substring(pos);
                    }   // end host name processing

                    if (port != 80 && !usingWWWProxy) {
                        if (out != null) {
                            out.write(getErrorPage("denied", ERR_REQUEST_DENIED));
                            writeFooter(out);
                        }
                        s.close();
                        return;
                    }

                    boolean isValid = usingWWWProxy || usingInternalServer || isSupportedAddress(host, protocol);
                    if (!isValid) {
                        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix(requestId) + "notValid(" + host + ")");
                        method = null;
                        destination = null;
                        break;
                    }

                    // don't do this, it forces yet another hostname lookup,
                    // and in all cases host was already set above
                    //if ((!usingWWWProxy) && (!usingInternalServer)) {
                    //    String oldhost = host;
                    //    host = getHostName(destination); // hide original host
                    //    if (_log.shouldLog(Log.INFO))
                    //        _log.info(getPrefix(requestId) + " oldhost " + oldhost + " newhost " + host + " dest " + destination);
                    //}

                    if (_log.shouldLog(Log.DEBUG)) {
                        _log.debug(getPrefix(requestId) + "METHOD: \"" + method + "\"");
                        _log.debug(getPrefix(requestId) + "PROTOC: \"" + protocol + "\"");
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
                    if (protocol != null && "http://".equals(protocol.toLowerCase(Locale.US)))
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
                if (targetRequest.startsWith(LOCAL_SERVER + "/add?") &&
                    Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER)).booleanValue()) {
                    out.write(ERR_HELPER_DISABLED);
                } else {
                    serveLocalFile(out, method, targetRequest, _proxyNonce);
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
                // remove bad entries
                if (clientDest == null)
                    addressHelpers.remove(destination.toLowerCase(Locale.US));
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
                writeHelperSaveForm(out, destination, ahelperKey, protocol + targetRequest);
                s.close();
                return;
            }

            // Redirect to non-addresshelper URL to not clog the browser address bar
            // and not pass the parameter to the eepsite.
            // This also prevents the not-found error page from looking bad
            // Syndie can't handle a redirect of a POST
            if (ahelperPresent && !"POST".equals(method)) {
                String uri = protocol + targetRequest;
                int spc = uri.indexOf(" ");
                if (spc >= 0)
                    uri = uri.substring(0, spc);
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
        // strip HTTP/1.1
        int protopos = targetRequest.indexOf(" ");
        if (protopos >= 0)
            targetRequest = targetRequest.substring(0, protopos);
        byte[] header = getErrorPage("ahelper-new", ERR_AHELPER_NEW);
        out.write(header);
        out.write(("<table><tr><td class=\"mediumtags\" align=\"right\">" + _("Host") + "</td><td class=\"mediumtags\">" + destination + "</td></tr>\n" +
                   "<tr><td class=\"mediumtags\" align=\"right\">" + _("Destination") + "</td><td>" +
                   "<textarea rows=\"1\" style=\"height: 4em; min-width: 0; min-height: 0;\" cols=\"70\" wrap=\"off\" readonly=\"readonly\" >" +
                   ahelperKey + "</textarea></td></tr></table>\n" +
                   "<hr><div class=\"formaction\">"+
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

    private static void writeFooter(OutputStream out) throws IOException {
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
                int protopos = targetRequest.indexOf(" ");
                String uri;
                if (protopos >= 0)
                    uri = targetRequest.substring(0, protopos);
                else
                    uri = targetRequest;
                out.write("<a href=\"http://".getBytes());
                out.write(uri.getBytes());
                out.write("\">http://".getBytes());
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
                    out.write("<br>".getBytes());

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
                        out.write(jurl.getBytes());
                        out.write(uri.getBytes());
                        out.write("</a>".getBytes());
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
        return protocol.toLowerCase(Locale.US).equals("http://");
    }

    private final static byte[] ERR_404 =
        ("HTTP/1.1 404 Not Found\r\n"+
         "Content-Type: text/plain\r\n"+
         "\r\n"+
         "HTTP Proxy local file not found")
        .getBytes();

    private final static byte[] ERR_ADD =
        ("HTTP/1.1 409 Bad\r\n"+
         "Content-Type: text/plain\r\n"+
         "\r\n"+
         "Add to addressbook failed - bad parameters")
        .getBytes();

    private final static byte[] ERR_HELPER_DISABLED =
        ("HTTP/1.1 403 Disabled\r\n"+
         "Content-Type: text/plain\r\n"+
         "\r\n"+
         "Address helpers disabled")
        .getBytes();

    /**
     *  Very simple web server.
     *
     *  Serve local files in the docs/ directory, for CSS and images in
     *  error pages, using the reserved address proxy.i2p
     *  (similar to p.p in privoxy).
     *  This solves the problems with including links to the router console,
     *  as assuming the router console is at 127.0.0.1 leads to broken
     *  links if it isn't.
     *
     *  Ignore all request headers (If-Modified-Since, etc.)
     *
     *  There is basic protection here -
     *  FileUtil.readFile() prevents traversal above the base directory -
     *  but inproxy/gateway ops would be wise to block proxy.i2p to prevent
     *  exposing the docs/ directory or perhaps other issues through
     *  uncaught vulnerabilities.
     *  Restrict to the /themes/ directory for now.
     *
     *  @param targetRequest "proxy.i2p/themes/foo.png HTTP/1.1"
     */
    private static void serveLocalFile(OutputStream out, String method, String targetRequest, String proxyNonce) {
        //System.err.println("targetRequest: \"" + targetRequest + "\"");
        // a home page message for the curious...
        if (targetRequest.startsWith(LOCAL_SERVER + "/ ")) {
            try {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nCache-Control: max-age=86400\r\n\r\nI2P HTTP proxy OK").getBytes());
                out.flush();
            } catch (IOException ioe) {}
            return;
        }
        if ((method.equals("GET") || method.equals("HEAD")) &&
            targetRequest.startsWith(LOCAL_SERVER + "/themes/") &&
            !targetRequest.contains("..")) {
            int space = targetRequest.indexOf(' ');
            String filename = null;
            try {
                filename = targetRequest.substring(LOCAL_SERVER.length() + 8, space); // "/themes/".length
            } catch (IndexOutOfBoundsException ioobe) {
                 return;
            }
            // theme hack
            if (filename.startsWith("console/default/"))
                filename = filename.replaceFirst("default", I2PAppContext.getGlobalContext().getProperty("routerconsole.theme", "light"));
            File themesDir = new File(_errorDir, "themes");
            File file = new File(themesDir, filename);
            if (file.exists() && !file.isDirectory()) {
                String type;
                if (filename.endsWith(".css"))
                    type = "text/css";
                else if (filename.endsWith(".ico"))
                    type = "image/x-icon";
                else if (filename.endsWith(".png"))
                    type = "image/png";
                else if (filename.endsWith(".jpg"))
                    type = "image/jpeg";
                else type = "text/html";
                try {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: ".getBytes());
                    out.write(type.getBytes());
                    out.write("\r\nCache-Control: max-age=86400\r\n\r\n".getBytes());
                    FileUtil.readFile(filename, themesDir.getAbsolutePath(), out);
                } catch (IOException ioe) {}
                return;
            }
        }

        // Add to addressbook (form submit)
        // Parameters are url, host, dest, nonce, and master | router | private.
        // Do the add and redirect.
        if (targetRequest.startsWith(LOCAL_SERVER + "/add?")) {
            int spc = targetRequest.indexOf(' ');
            String query = targetRequest.substring(LOCAL_SERVER.length() + 5, spc);   // "/add?".length()
            Map<String, String> opts = new HashMap(8);
            StringTokenizer tok = new StringTokenizer(query, "=&;");
            while (tok.hasMoreTokens()) {
                String k = tok.nextToken();
                if (!tok.hasMoreTokens())
                    break;
                String v = tok.nextToken();
                opts.put(decode(k), decode(v));
            }

            String url = opts.get("url");
            String host = opts.get("host");
            String b64Dest = opts.get("dest");
            String nonce = opts.get("nonce");
            String book = "privatehosts.txt";
            if (opts.get("master") != null)
                book = "userhosts.txt";
            else if (opts.get("router") != null)
                book = "hosts.txt";
            Destination dest = null;
            if (b64Dest != null) {
                try {
                    dest = new Destination(b64Dest);
                } catch (DataFormatException dfe) {
                    System.err.println("Bad dest to save?" + b64Dest);
                }
            }
            //System.err.println("url          : \"" + url           + "\"");
            //System.err.println("host         : \"" + host          + "\"");
            //System.err.println("b64dest      : \"" + b64Dest       + "\"");
            //System.err.println("book         : \"" + book          + "\"");
            //System.err.println("nonce        : \"" + nonce         + "\"");
            if (proxyNonce.equals(nonce) && url != null && host != null && dest != null) {
                try {
                    NamingService ns = I2PAppContext.getGlobalContext().namingService();
                    Properties nsOptions = new Properties();
                    nsOptions.setProperty("list", book);
                    nsOptions.setProperty("s", _("Added via address helper"));
                    boolean success = ns.put(host, dest, nsOptions);
                    writeRedirectPage(out, success, host, book, url);
                    return;
                } catch (IOException ioe) {}
            }
            try {
                out.write(ERR_ADD);
                out.flush();
            } catch (IOException ioe) {}
            return;
        }
        try {
            out.write(ERR_404);
            out.flush();
        } catch (IOException ioe) {}
    }

    /** @since 0.8.7 */
    private static void writeRedirectPage(OutputStream out, boolean success, String host, String book, String url) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\n"+
                  "Content-Type: text/html; charset=UTF-8\r\n"+
                  "\r\n"+
                  "<html><head>"+
                  "<title>" + _("Redirecting to {0}", host) + "</title>\n" +
                  "<link rel=\"shortcut icon\" href=\"http://proxy.i2p/themes/console/images/favicon.ico\" >\n" +
                  "<link href=\"http://proxy.i2p/themes/console/default/console.css\" rel=\"stylesheet\" type=\"text/css\" >\n" +
                  "<meta http-equiv=\"Refresh\" content=\"1; url=" + url + "\">\n" +
                  "</head><body>\n" +
                  "<div class=logo>\n" +
                  "<a href=\"http://127.0.0.1:7657/\" title=\"" + _("Router Console") + "\"><img src=\"http://proxy.i2p/themes/console/images/i2plogo.png\" alt=\"I2P Router Console\" border=\"0\"></a><hr>\n" +
                  "<a href=\"http://127.0.0.1:7657/config\">" + _("Configuration") + "</a> <a href=\"http://127.0.0.1:7657/help.jsp\">" + _("Help") + "</a> <a href=\"http://127.0.0.1:7657/susidns/index\">" + _("Addressbook") + "</a>\n" +
                  "</div>" +
                  "<div class=warning id=warning>\n" +
                  "<h3>" +
                  (success ?
                           _("Saved {0} to the {1} addressbook, redirecting now.", host, book) :
                           _("Failed to save {0} to the {1} addressbook, redirecting now.", host, book)) +
                  "</h3>\n<p><a href=\"" + url + "\">" +
                  _("Click here if you are not redirected automatically.") +
                  "</a></p></div>").getBytes("UTF-8"));
        writeFooter(out);
        out.flush();
    }

    /**
     *  Decode %xx encoding
     *  @since 0.8.7
     */
    private static String decode(String s) {
        if (!s.contains("%"))
            return s;
        StringBuilder buf = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '%') {
                buf.append(c);
            } else {
                try {
                    buf.append((char) Integer.parseInt(s.substring(++i, (++i) + 1), 16));
                } catch (IndexOutOfBoundsException ioobe) {
                    break;
                } catch (NumberFormatException nfe) {
                    break;
                }
            }
        }
        return buf.toString();
    }

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
