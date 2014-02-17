/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.util.ArrayList;
import java.io.File;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.EepGet;
import net.i2p.util.EventDispatcher;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;
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

    private static final String ERR_AUTH1 =
            "HTTP/1.1 407 Proxy Authentication Required\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Cache-control: no-cache\r\n" +
            "Accept-Charset: ISO-8859-1,utf-8;q=0.7,*;q=0.5\r\n" + // try to get a UTF-8-encoded response back for the password
            "Proxy-Authenticate: ";
    // put the auth type and realm in between
    private static final String ERR_AUTH2 =
            "\r\n" +
            "\r\n" +
            "<html><body><H1>I2P ERROR: PROXY AUTHENTICATION REQUIRED</H1>" +
            "This proxy is configured to require authentication.";

    protected final List<String> _proxyList;

    protected final static byte[] ERR_NO_OUTPROXY =
        ("HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: No outproxy found</H1>"+
         "Your request was for a site outside of I2P, but you have no "+
         "HTTP outproxy configured.  Please configure an outproxy in I2PTunnel")
         .getBytes();
    
    private final byte[] _proxyNonce;
    private final ConcurrentHashMap<String, NonceInfo> _nonces;
    private final AtomicInteger _nonceCleanCounter = new AtomicInteger();

    protected String getPrefix(long requestId) { return "Client[" + _clientId + "/" + requestId + "]: "; }
    
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
                    String[] parts = dec.split(":");
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
                    _log.logAlways(Log.WARN, "PROXY AUTH FAILURE: user " + user);
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
            _log.logAlways(Log.WARN, "PROXY AUTH FAILURE: user " + user);
            return AuthResult.AUTH_BAD;
        }
        // get H(A2)
        String a2 = method + ':' + uri;
        String ha2 = PasswordManager.md5Hex(a2);
        // response check
        String kd = ha1 + ':' + nonce + ':' + nc + ':' + cnonce + ':' + qop + ':' + ha2;
        String hkd = PasswordManager.md5Hex(kd);
        if (!response.equals(hkd)) {
            _log.logAlways(Log.WARN, "PROXY AUTH FAILURE: user " + user);
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
     *  foo => errordir/foo-header_xx.ht for lang xx, or errordir/foo-header.ht,
     *  or the backup byte array on fail.
     *
     *  .ht files must be UTF-8 encoded and use \r\n terminators so the
     *  HTTP headers are conformant.
     *  We can't use FileUtil.readFile() because it strips \r
     *
     *  @return non-null
     *  @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    protected byte[] getErrorPage(String base, byte[] backup) {
        return getErrorPage(_context, base, backup);
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
     *  @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    protected static byte[] getErrorPage(I2PAppContext ctx, String base, byte[] backup) {
        File errorDir = new File(ctx.getBaseDir(), "docs");
        File file = new File(errorDir, base + "-header.ht");
        try {
            return readFile(ctx, file);
        } catch(IOException ioe) {
            return backup;
        }
    }

    private static final String BUNDLE_NAME = "net.i2p.i2ptunnel.proxy.messages";

    /**
     *  @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    private static byte[] readFile(I2PAppContext ctx, File file) throws IOException {
        Reader reader = null;
        char[] buf = new char[512];
        StringBuilder out = new StringBuilder(2048);
        try {
            int len;
            reader = new TranslateReader(ctx, BUNDLE_NAME, new FileInputStream(file));
            while((len = reader.read(buf)) > 0) {
                out.append(buf, 0, len);
            }
            return out.toString().getBytes("UTF-8");
        } finally {
            try {
                if(reader != null)
                    reader.close();
            } catch(IOException foo) {}
        }
        // we won't ever get here
    }
}
