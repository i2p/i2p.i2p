/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.util.EepGet;
import net.i2p.util.EventDispatcher;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;
import net.i2p.util.PortMapper;
import net.i2p.util.Translate;
import net.i2p.util.TranslateReader;

/**
 * Common things for HTTPClient and ConnectClient
 * Retrofit over them in 0.8.2
 *
 * @since 0.8.2
 */
public abstract class I2PTunnelHTTPClientBase extends I2PTunnelClientBase implements Runnable {

    private static final int PROXYNONCE_BYTES = 8;
    private static final int MD5_BYTES = 16;
    /** 24 */
    private static final int NONCE_BYTES = DataHelper.DATE_LENGTH + MD5_BYTES;
    private static final long MAX_NONCE_AGE = 60*60*1000L;
    private static final int MAX_NONCE_COUNT = 1024;
    /** @since 0.9.11, moved to Base in 0.9.29 */
    public static final String PROP_USE_OUTPROXY_PLUGIN = "i2ptunnel.useLocalOutproxy";
    /**
     *  This is a standard soTimeout, not a total timeout.
     *  We have no slowloris protection on the client side.
     *  See I2PTunnelHTTPServer or SAM's ReadLine if we need that.
     *  @since 0.9.33
     */
    protected static final int INITIAL_SO_TIMEOUT = 15*1000;

    private static final String ERR_AUTH1 =
            "HTTP/1.1 407 Proxy Authentication Required\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "Accept-Charset: ISO-8859-1,utf-8;q=0.7,*;q=0.5\r\n" + // try to get a UTF-8-encoded response back for the password
            "Proxy-Authenticate: ";
    // put the auth type and realm in between
    private static final String ERR_AUTH2 =
            "\r\n" +
            "\r\n" +
            "<html><body><H1>I2P ERROR: PROXY AUTHENTICATION REQUIRED</H1>" +
            "This proxy is configured to require authentication.";

    protected final List<String> _proxyList;

    protected final static String ERR_NO_OUTPROXY =
         "HTTP/1.1 503 No Outproxy Configured\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-Control: no-cache\r\n"+
         "Connection: close\r\n"+
         "Proxy-Connection: close\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: No outproxy found</H1>"+
         "Your request was for a site outside of I2P, but you have no "+
         "outproxy configured.  Please configure an outproxy in I2PTunnel";
    
    protected final static String ERR_DESTINATION_UNKNOWN =
            "HTTP/1.1 503 Service Unavailable\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "Proxy-Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: DESTINATION NOT FOUND</H1>" +
            "That I2P Destination was not found. Perhaps you pasted in the " +
            "wrong BASE64 I2P Destination or the link you are following is " +
            "bad. The host (or the WWW proxy, if you're using one) could also " +
            "be temporarily offline.  You may want to <b>retry</b>.  " +
            "Could not find the following Destination:<BR><BR><div>";

    protected final static String SUCCESS_RESPONSE =
        "HTTP/1.1 200 Connection Established\r\n"+
         "Proxy-agent: I2P\r\n"+
         "\r\n";

    private final byte[] _proxyNonce;
    private final ConcurrentHashMap<String, NonceInfo> _nonces;
    private final AtomicInteger _nonceCleanCounter = new AtomicInteger();

    protected String getPrefix(long requestId) {
        return "HTTPClient[" + _clientId + '/' + requestId + "]: ";
    }
    
    protected String selectProxy() {
        synchronized (_proxyList) {
            int size = _proxyList.size();
            if (size <= 0)
                return null;
            int index = _context.random().nextInt(size);
            return _proxyList.get(index);
        }
    }

    protected static final int DEFAULT_READ_TIMEOUT = 5*60*1000;
    
    protected static final AtomicLong __requestId = new AtomicLong();

    public I2PTunnelHTTPClientBase(int localPort, boolean ownDest, Logging l, 
                               EventDispatcher notifyThis, String handlerName, 
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, handlerName, tunnel);
        _proxyList = new ArrayList<String>(4);
        _proxyNonce = new byte[PROXYNONCE_BYTES];
        _context.random().nextBytes(_proxyNonce);
        _nonces = new ConcurrentHashMap<String, NonceInfo>();
    }

    /**
     *  This constructor always starts the tunnel (ignoring the i2cp.delayOpen option).
     *  It is used to add a client to an existing socket manager.
     *
     *  @param sktMgr the existing socket manager
     */
    public I2PTunnelHTTPClientBase(int localPort, Logging l, I2PSocketManager sktMgr,
            I2PTunnel tunnel, EventDispatcher notifyThis, long clientId )
            throws IllegalArgumentException {
        super(localPort, l, sktMgr, tunnel, notifyThis, clientId);
        _proxyList = new ArrayList<String>(4);
        _proxyNonce = new byte[PROXYNONCE_BYTES];
        _context.random().nextBytes(_proxyNonce);
        _nonces = new ConcurrentHashMap<String, NonceInfo>();
    }

    //////// Authorization stuff

    /** all auth @since 0.8.2 */
    public static final String PROP_AUTH = "proxyAuth";
    public static final String PROP_USER = "proxyUsername";
    public static final String PROP_PW = "proxyPassword";
    /** additional users may be added with proxyPassword.user=pw */
    public static final String PROP_PW_PREFIX = PROP_PW + '.';
    public static final String PROP_OUTPROXY_AUTH = "outproxyAuth";
    public static final String PROP_OUTPROXY_USER = "outproxyUsername";
    public static final String PROP_OUTPROXY_PW = "outproxyPassword";
    /** passwords for specific outproxies may be added with outproxyUsername.fooproxy.i2p=user and outproxyPassword.fooproxy.i2p=pw */
    public static final String PROP_OUTPROXY_USER_PREFIX = PROP_OUTPROXY_USER + '.';
    public static final String PROP_OUTPROXY_PW_PREFIX = PROP_OUTPROXY_PW + '.';
    /** new style MD5 auth */
    public static final String PROP_PROXY_DIGEST_PREFIX = "proxy.auth.";
    public static final String PROP_PROXY_DIGEST_SUFFIX = ".md5";
    public static final String BASIC_AUTH = "basic";
    public static final String DIGEST_AUTH = "digest";

    protected abstract String getRealm();

    protected enum AuthResult {AUTH_BAD_REQ, AUTH_BAD, AUTH_STALE, AUTH_GOOD}

    /**
     *  @since 0.9.6
     */
    private static class NonceInfo {
        private final long expires;
        private final BitSet counts;

        public NonceInfo(long exp) {
            expires = exp;
            counts = new BitSet(MAX_NONCE_COUNT);
        }

        public long getExpires() {
            return expires;
        }

        public AuthResult isValid(int nc) {
            if (nc <= 0)
                return AuthResult.AUTH_BAD;
            if (nc >= MAX_NONCE_COUNT)
                return AuthResult.AUTH_STALE;
            synchronized(counts) {
                if (counts.get(nc))
                    return AuthResult.AUTH_BAD;
                counts.set(nc);
            }
            return AuthResult.AUTH_GOOD;
        }
    }

    /**
     *  Update the outproxy list then call super.
     *
     *  @since 0.9.12
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel)
            return;
        Properties props = tunnel.getClientOptions();
        // see TunnelController.setSessionOptions()
        String proxies = props.getProperty("proxyList");
        if (proxies != null) {
            StringTokenizer tok = new StringTokenizer(proxies, ",; \r\n\t");
            synchronized(_proxyList) {
                _proxyList.clear();
                while (tok.hasMoreTokens()) {
                    String p = tok.nextToken().trim();
                    if (p.length() > 0)
                        _proxyList.add(p);
                }
            }
        } else {
            synchronized(_proxyList) {
                _proxyList.clear();
            }
        }
        super.optionsUpdated(tunnel);
    }

    /**
     *  @since 0.9.4
     */
    protected boolean isDigestAuthRequired() {
        String authRequired = getTunnel().getClientOptions().getProperty(PROP_AUTH);
        if (authRequired == null)
            return false;
        return authRequired.toLowerCase(Locale.US).equals("digest");
    }

    /**
     *  Authorization
     *  Ref: RFC 2617
     *  If the socket is an InternalSocket, no auth required.
     *
     *  @param method GET, POST, etc.
     *  @param authorization may be null, the full auth line e.g. "Basic lskjlksjf"
     *  @return success
     */
    protected AuthResult authorize(Socket s, long requestId, String method, String authorization) {
        String authRequired = getTunnel().getClientOptions().getProperty(PROP_AUTH);
        if (authRequired == null)
            return AuthResult.AUTH_GOOD;
        authRequired = authRequired.toLowerCase(Locale.US);
        if (authRequired.equals("false"))
            return AuthResult.AUTH_GOOD;
        if (s instanceof InternalSocket) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix(requestId) + "Internal access, no auth required");
            return AuthResult.AUTH_GOOD;
        }
        if (authorization == null)
            return AuthResult.AUTH_BAD;
        if (_log.shouldLog(Log.INFO))
            _log.info(getPrefix(requestId) + "Auth: " + authorization);
        String authLC = authorization.toLowerCase(Locale.US);
        if (authRequired.equals("true") || authRequired.equals(BASIC_AUTH)) {
            if (!authLC.startsWith("basic "))
                return AuthResult.AUTH_BAD;
            authorization = authorization.substring(6);

            // hmm safeDecode(foo, true) to use standard alphabet is private in Base64
            byte[] decoded = Base64.decode(authorization.replace("/", "~").replace("+", "="));
            if (decoded != null) {
                // We send Accept-Charset: UTF-8 in the 407 so hopefully it comes back that way inside the B64 ?
                try {
                    String dec = new String(decoded, "UTF-8");
                    String[] parts = DataHelper.split(dec, ":");
                    String user = parts[0];
                    String pw = parts[1];
                    // first try pw for that user
                    String configPW = getTunnel().getClientOptions().getProperty(PROP_PW_PREFIX + user);
                    if (configPW == null) {
                        // if not, look at default user and pw
                        String configUser = getTunnel().getClientOptions().getProperty(PROP_USER);
                        if (user.equals(configUser))
                            configPW = getTunnel().getClientOptions().getProperty(PROP_PW);
                    }
                    if (configPW != null) {
                        if (pw.equals(configPW)) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info(getPrefix(requestId) + "Good auth - user: " + user + " pw: " + pw);
                            return AuthResult.AUTH_GOOD;
                        }
                    }
                    _log.logAlways(Log.WARN, "HTTP proxy authentication failed, user: " + user);
                } catch (UnsupportedEncodingException uee) {
                    _log.error(getPrefix(requestId) + "No UTF-8 support? B64: " + authorization, uee);
                } catch (ArrayIndexOutOfBoundsException aioobe) {
                    // no ':' in response
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getPrefix(requestId) + "Bad auth B64: " + authorization, aioobe);
                    return AuthResult.AUTH_BAD_REQ;
                }
                return AuthResult.AUTH_BAD;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getPrefix(requestId) + "Bad auth B64: " + authorization);
                return AuthResult.AUTH_BAD_REQ;
            }
        } else if (authRequired.equals(DIGEST_AUTH)) {
            if (!authLC.startsWith("digest "))
                return AuthResult.AUTH_BAD;
            authorization = authorization.substring(7);
            Map<String, String> args = parseArgs(authorization);
            AuthResult rv = validateDigest(method, args);
            return rv;
        } else {
            _log.error("Unknown proxy authorization type configured: " + authRequired);
            return AuthResult.AUTH_BAD_REQ;
        }
    }

    /**
     *  Verify all of it.
     *  Ref: RFC 2617
     *  @since 0.9.4
     */
    private AuthResult validateDigest(String method, Map<String, String> args) {
        String user = args.get("username");
        String realm = args.get("realm");
        String nonce = args.get("nonce");
        String qop = args.get("qop");
        String uri = args.get("uri");
        String cnonce = args.get("cnonce");
        String nc = args.get("nc");
        String response = args.get("response");
        if (user == null || realm == null || nonce == null || qop == null ||
            uri == null || cnonce == null || nc == null || response == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Bad digest request: " + DataHelper.toString(args));
            return AuthResult.AUTH_BAD_REQ;
        }
        // nonce check
        AuthResult check = verifyNonce(nonce, nc);
        if (check != AuthResult.AUTH_GOOD) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Bad digest nonce: " + check + ' ' + DataHelper.toString(args));
            return check;
        }
        // get H(A1) == stored password
        String ha1 = getTunnel().getClientOptions().getProperty(PROP_PROXY_DIGEST_PREFIX + user +
                                                                PROP_PROXY_DIGEST_SUFFIX);
        if (ha1 == null) {
            _log.logAlways(Log.WARN, "HTTP proxy authentication failed, user: " + user);
            return AuthResult.AUTH_BAD;
        }
        // get H(A2)
        String a2 = method + ':' + uri;
        String ha2 = PasswordManager.md5Hex(a2);
        // response check
        String kd = ha1 + ':' + nonce + ':' + nc + ':' + cnonce + ':' + qop + ':' + ha2;
        String hkd = PasswordManager.md5Hex(kd);
        if (!response.equals(hkd)) {
            _log.logAlways(Log.WARN, "HTTP proxy authentication failed, user: " + user);
            if (_log.shouldLog(Log.INFO))
                _log.info("Bad digest auth: " + DataHelper.toString(args));
            return AuthResult.AUTH_BAD;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Good digest auth - user: " + user);
        return AuthResult.AUTH_GOOD;
    }

    /**
     *  The Base 64 of 24 bytes: (now, md5 of (now, proxy nonce))
     *  @since 0.9.4
     */
    private String getNonce() {
        byte[] b = new byte[DataHelper.DATE_LENGTH + PROXYNONCE_BYTES];
        byte[] n = new byte[NONCE_BYTES];
        long now = _context.clock().now();
        DataHelper.toLong(b, 0, DataHelper.DATE_LENGTH, now);
        System.arraycopy(_proxyNonce, 0, b, DataHelper.DATE_LENGTH, PROXYNONCE_BYTES);
        System.arraycopy(b, 0, n, 0, DataHelper.DATE_LENGTH);
        byte[] md5 = PasswordManager.md5Sum(b);
        System.arraycopy(md5, 0, n, DataHelper.DATE_LENGTH, MD5_BYTES);
        String rv = Base64.encode(n);
        _nonces.putIfAbsent(rv, new NonceInfo(now + MAX_NONCE_AGE));
        return rv;
    }

    /**
     *  Verify the Base 64 of 24 bytes: (now, md5 of (now, proxy nonce))
     *  and the nonce count.
     *  @param b64 nonce non-null
     *  @param ncs nonce count string non-null
     *  @since 0.9.4
     */
    private AuthResult verifyNonce(String b64, String ncs) {
        if (_nonceCleanCounter.incrementAndGet() % 16 == 0)
            cleanNonces();
        byte[] n = Base64.decode(b64);
        if (n == null || n.length != NONCE_BYTES)
            return AuthResult.AUTH_BAD;
        long now = _context.clock().now();
        long stamp = DataHelper.fromLong(n, 0, DataHelper.DATE_LENGTH);
        if (now - stamp > MAX_NONCE_AGE) {
            _nonces.remove(b64);
            return AuthResult.AUTH_STALE;
        }
        NonceInfo info = _nonces.get(b64);
        if (info == null)
            return AuthResult.AUTH_STALE;
        byte[] b = new byte[DataHelper.DATE_LENGTH + PROXYNONCE_BYTES];
        System.arraycopy(n, 0, b, 0, DataHelper.DATE_LENGTH);
        System.arraycopy(_proxyNonce, 0, b, DataHelper.DATE_LENGTH, PROXYNONCE_BYTES);
        byte[] md5 = PasswordManager.md5Sum(b);
        if (!DataHelper.eq(md5, 0, n, DataHelper.DATE_LENGTH, MD5_BYTES))
            return AuthResult.AUTH_BAD;
        try {
            int nc = Integer.parseInt(ncs, 16);
            return info.isValid(nc);
        } catch (NumberFormatException nfe) {
            return AuthResult.AUTH_BAD;
        }
    }


    /**
     *  Remove expired nonces from map
     *  @since 0.9.6
     */
    private void cleanNonces() {
        long now = _context.clock().now();
        for (Iterator<NonceInfo> iter = _nonces.values().iterator(); iter.hasNext(); ) {
            NonceInfo info = iter.next();
            if (info.getExpires() <= now)
                iter.remove();
        }
    }

    /**
     *  What to send if digest auth fails
     *  @since 0.9.4
     */
    protected String getAuthError(boolean isStale) {
        boolean isDigest = isDigestAuthRequired();
        return
            ERR_AUTH1 +
            (isDigest ? "Digest" : "Basic") +
            " realm=\"" + getRealm() + '"' +
            (isDigest ? ", nonce=\"" + getNonce() + "\"," +
                        " algorithm=MD5," +
                        " charset=UTF-8," +     // RFC 7616/7617
                        " qop=\"auth\"" +
                        (isStale ? ", stale=true" : "")
                      : "") +
            ERR_AUTH2;
    }

    /**
     *  Modified from LoadClientAppsJob.
     *  All keys are mapped to lower case.
     *  Ref: RFC 2617
     *
     *  @param args non-null
     *  @since 0.9.4
     */
    private static Map<String, String> parseArgs(String args) {
        // moved to EepGet, since it needs this too
        return EepGet.parseAuthArgs(args);
    }

    //////// Error page stuff

    /**
     *  foo =&gt; errordir/foo-header_xx.ht for lang xx, or errordir/foo-header.ht,
     *  or the backup byte array on fail.
     *
     *  .ht files must be UTF-8 encoded and use \r\n terminators so the
     *  HTTP headers are conformant.
     *  We can't use FileUtil.readFile() because it strips \r
     *
     *  @return non-null
     *  @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    protected String getErrorPage(String base, String backup) {
        return getErrorPage(_context, base, backup);
    }

    /**
     *  foo =&gt; errordir/foo-header_xx.ht for lang xx, or errordir/foo-header.ht,
     *  or the backup byte array on fail.
     *
     *  .ht files must be UTF-8 encoded and use \r\n terminators so the
     *  HTTP headers are conformant.
     *  We can't use FileUtil.readFile() because it strips \r
     *
     *  @return non-null
     *  @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    protected static String getErrorPage(I2PAppContext ctx, String base, String backup) {
        File errorDir = new File(ctx.getBaseDir(), "docs");
        File file = new File(errorDir, base + "-header.ht");
        try {
            return readFile(ctx, file);
        } catch(IOException ioe) {
            return backup;
        }
    }

    /** these strings go in the jar, not the war */
    private static final String BUNDLE_NAME = "net.i2p.i2ptunnel.proxy.messages";

    /**
     *  @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    private static String readFile(I2PAppContext ctx, File file) throws IOException {
        Reader reader = null;
        char[] buf = new char[512];
        StringBuilder out = new StringBuilder(2048);
        try {
            boolean hasSusiDNS = ctx.portMapper().isRegistered(PortMapper.SVC_SUSIDNS);
            boolean hasI2PTunnel = ctx.portMapper().isRegistered(PortMapper.SVC_I2PTUNNEL);
            if (hasSusiDNS && hasI2PTunnel) {
                reader = new TranslateReader(ctx, BUNDLE_NAME, new FileInputStream(file));
            } else {
                // strip out the addressbook links
                reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
                int len;
                while((len = reader.read(buf)) > 0) {
                    out.append(buf, 0, len);
                }
                reader.close();
                if (!hasSusiDNS) {
                    DataHelper.replace(out, "<a href=\"http://127.0.0.1:7657/susidns/index\">_(\"Addressbook\")</a>", "");
                }
                if (!hasI2PTunnel) {
                    // there are also a couple in auth-header.ht that aren't worth stripping, for auth only
                    DataHelper.replace(out,
                                       "<span class=\"script\">_(\"You may want to {0}retry{1} as this will randomly reselect an outproxy from the pool you have defined {2}here{3} (if you have more than one configured).\", \"<a href=\\\"javascript:parent.window.location.reload()\\\">\", \"</a>\", \"<a href=\\\"http://127.0.0.1:7657/i2ptunnel/index.jsp\\\">\", \"</a>\")</span>",
                                       "");
                    DataHelper.replace(out,
                                       "<noscript>_(\"You may want to retry as this will randomly reselect an outproxy from the pool you have defined {0}here{1} (if you have more than one configured).\", \"<a href=\\\"http://127.0.0.1:7657/i2ptunnel/index.jsp\\\">\", \"</a>\")</noscript>",
                                       "");
                    DataHelper.replace(out,
                                       "_(\"If you continue to have trouble you may want to edit your outproxy list {0}here{1}.\", \"<a href=\\\"http://127.0.0.1:7657/i2ptunnel/edit.jsp?tunnel=0\\\">\", \"</a>\")",
                                       "");
                }
                String s = out.toString();
                out.setLength(0);
                reader = new TranslateReader(ctx, BUNDLE_NAME, new StringReader(s));
            }
            int len;
            while((len = reader.read(buf)) > 0) {
                out.append(buf, 0, len);
            }
            // Do we need to replace http://127.0.0.1:7657 console links in the error page?
            // Get the registered host and port from the PortMapper.
            String url = ctx.portMapper().getConsoleURL();
            if (!url.equals("http://127.0.0.1:7657/")) {
                DataHelper.replace(out, "http://127.0.0.1:7657/", url);
            }
            String rv = out.toString();
            return rv;
        } finally {
            try {
                if(reader != null)
                    reader.close();
            } catch(IOException foo) {}
        }
        // we won't ever get here
    }

    /**
     *  @since 0.9.14 moved from subclasses
     */
    protected class OnTimeout implements I2PTunnelRunner.FailCallback {
        private final Socket _socket;
        private final OutputStream _out;
        private final String _target;
        private final boolean _usingProxy;
        private final String _wwwProxy;
        private final long _requestId;

        public OnTimeout(Socket s, OutputStream out, String target, boolean usingProxy, String wwwProxy, long id) {
            _socket = s;
            _out = out;
            _target = target;
            _usingProxy = usingProxy;
            _wwwProxy = wwwProxy;
            _requestId = id;
        }

        /**
         *  @param ex may be null
         */
        public void onFail(Exception ex) {
            Throwable cause = ex != null ? ex.getCause() : null;
            if (cause != null && cause instanceof I2PSocketException) {
                I2PSocketException ise = (I2PSocketException) cause;
                handleI2PSocketException(ise, _out, _target, _usingProxy, _wwwProxy);
            } else {
                handleClientException(ex, _out, _target, _usingProxy, _wwwProxy, _requestId);
            }
            closeSocket(_socket);
        }
    }

    /**
     *  @param ex may be null
     *  @since 0.9.14 moved from subclasses
     */
    protected void handleClientException(Exception ex, OutputStream out, String targetRequest,
                                         boolean usingWWWProxy, String wwwProxy, long requestId) {
        if (out == null)
            return;
        String header;
        if (ex instanceof SocketTimeoutException)
            header = I2PTunnelHTTPServer.ERR_REQUEST_TIMEOUT;
        else if (usingWWWProxy)
            header = getErrorPage(I2PAppContext.getGlobalContext(), "dnfp", ERR_DESTINATION_UNKNOWN);
        else
            header = getErrorPage(I2PAppContext.getGlobalContext(), "dnf", ERR_DESTINATION_UNKNOWN);
        try {
            writeErrorMessage(header, out, targetRequest, usingWWWProxy, wwwProxy);
        } catch (IOException ioe) {}
    }

    /**
     *  Generate an error page based on the status code
     *  in our custom exception.
     *
     *  @param ise may be null
     *  @since 0.9.14
     */
    protected void handleI2PSocketException(I2PSocketException ise, OutputStream out, String targetRequest,
                                            boolean usingWWWProxy, String wwwProxy) {
        if (out == null)
            return;
        int status = ise != null ? ise.getStatus() : -1;
        String error;
        if (status == MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET) {
            // We won't get this one unless it is treated as a hard failure
            // in streaming. See PacketQueue.java
            error = usingWWWProxy ? "nolsp" : "nols";
        } else if (status == MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION) {
            error = usingWWWProxy ? "encp" : "enc";
        } else if (status == I2PSocketException.STATUS_CONNECTION_RESET) {
            error = usingWWWProxy ? "resetp" : "reset";
        } else {
            error = usingWWWProxy ? "dnfp" : "dnf";
        }
        String header = getErrorPage(error, ERR_DESTINATION_UNKNOWN);
        String message = ise != null ? ise.getLocalizedMessage() : "unknown error";
        try {
            writeErrorMessage(header, message, out, targetRequest, usingWWWProxy, wwwProxy);
        } catch(IOException ioe) {}
    }

    /**
     *  No jump servers or extra message
     *  @since 0.9.14
     */
    protected void writeErrorMessage(String errMessage, OutputStream out, String targetRequest,
                                     boolean usingWWWProxy, String wwwProxy) throws IOException {
        writeErrorMessage(errMessage, null, out, targetRequest, usingWWWProxy, wwwProxy, null);
    }

    /**
     *  No extra message
     *  @param jumpServers comma- or space-separated list, or null
     *  @since 0.9.14 moved from subclasses
     */
    protected void writeErrorMessage(String errMessage, OutputStream out, String targetRequest,
                                     boolean usingWWWProxy, String wwwProxy, String jumpServers) throws IOException {
        writeErrorMessage(errMessage, null, out, targetRequest, usingWWWProxy, wwwProxy, jumpServers);
    }

    /**
     *  No jump servers
     *  @param extraMessage extra message or null, will be HTML-escaped
     *  @since 0.9.14
     */
    protected void writeErrorMessage(String errMessage, String extraMessage,
                                     OutputStream out, String targetRequest,
                                     boolean usingWWWProxy, String wwwProxy) throws IOException {
        writeErrorMessage(errMessage, extraMessage, out, targetRequest, usingWWWProxy, wwwProxy, null);
    }

    /**
     *  @param jumpServers comma- or space-separated list, or null
     *  @param extraMessage extra message or null, will be HTML-escaped
     *  @since 0.9.14
     */
    protected void writeErrorMessage(String errMessage, String extraMessage,
                                     OutputStream outs, String targetRequest,
                                     boolean usingWWWProxy, String wwwProxy,
                                     String jumpServers) throws IOException {
        if (outs == null)
            return;
        Writer out = new BufferedWriter(new OutputStreamWriter(outs, "UTF-8"));
        out.write(errMessage);
        if (targetRequest != null) {
            String uri = DataHelper.escapeHTML(targetRequest);
            out.write("<a href=\"");
            out.write(uri);
            out.write("\">");
            // Long URLs are handled in CSS
            out.write(uri);
            out.write("</a>");
            if (usingWWWProxy) {
                out.write("<br><br><b>");
                out.write(_t("HTTP Outproxy"));
                out.write(":</b> " + wwwProxy);
            }
            if (extraMessage != null) {
                out.write("<br><br><b>" + DataHelper.escapeHTML(extraMessage) + "</b>");
            }
            if (jumpServers != null && jumpServers.length() > 0) {
                boolean first = true;
                if(uri.startsWith("http://")) {
                    uri = uri.substring(7);
                }
                StringTokenizer tok = new StringTokenizer(jumpServers, ", ");
                while(tok.hasMoreTokens()) {
                    String jurl = tok.nextToken();
                    String jumphost;
                    try {
                        URI jURI = new URI(jurl);
                        String proto = jURI.getScheme();
                        jumphost = jURI.getHost();
                        if (proto == null || jumphost == null ||
                            !proto.toLowerCase(Locale.US).equals("http"))
                            continue;
                        jumphost = jumphost.toLowerCase(Locale.US);
                        if (!jumphost.endsWith(".i2p"))
                            continue;
                    } catch(URISyntaxException use) {
                        continue;
                    }
                    // Skip jump servers we don't know
                    if (!jumphost.endsWith(".b32.i2p")) {
                        Destination dest = _context.namingService().lookup(jumphost);
                        if(dest == null) {
                            continue;
                        }
                    }

                    if (first) {
                        first = false;
                        out.write("<br><br>\n<div id=\"jumplinks\">\n<h4>");
                        out.write(_t("Click a link below for an address helper from a jump service"));
                        out.write("</h4>\n");
                    } else {
                        out.write("<br>");
                    }
                    out.write("<a href=\"");
                    out.write(jurl);
                    out.write(uri);
                    out.write("\">");
                    // Translators: parameter is a host name
                    out.write(_t("{0} jump service", jumphost));
                    out.write("</a>\n");
                }
                if (!first) { // We wrote out the opening <div>
                    out.write("</div>\n");
                }
            }
        }
        out.write("</div>\n");
        writeFooter(out);
    }

    /**
     *  Flushes.
     *
     *  Public only for LocalHTTPServer, not for general use
     *  @since 0.9.14 moved from I2PTunnelHTTPClient
     */
    public static void writeFooter(OutputStream out) throws IOException {
        out.write(getFooter().getBytes("UTF-8"));
        out.flush();
    }

    /**
     *  Flushes.
     *
     *  Public only for LocalHTTPServer, not for general use
     *  @since 0.9.19
     */
    public static void writeFooter(Writer out) throws IOException {
        out.write(getFooter());
        out.flush();
    }

    private static String getFooter() {
        // The css is hiding this div for now, but we'll keep it here anyway
        // Tag the strings below for translation if we unhide it.
        //StringBuilder buf = new StringBuilder(128);
        //buf.append("<div class=\"proxyfooter\"><p><i>I2P HTTP Proxy Server<br>Generated on: ")
        //   .append(new Date().toString())
        //   .append("</i></div>\n</body>\n</html>\n");
        //return buf.toString();
        return "</body>\n</html>\n";
    }

    /**
     *  Translate
     *  @since 0.9.14 moved from I2PTunnelHTTPClient
     */
    protected String _t(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    /**
     *  Translate
     *  {0}
     *  @since 0.9.14 moved from I2PTunnelHTTPClient
     */
    protected String _t(String key, Object o) {
        return Translate.getString(key, o, _context, BUNDLE_NAME);
    }

    /**
     *  Translate
     *  {0} and {1}
     *  @since 0.9.14 moved from I2PTunnelHTTPClient
     */
    protected String _t(String key, Object o, Object o2) {
        return Translate.getString(key, o, o2, _context, BUNDLE_NAME);
    }
}
