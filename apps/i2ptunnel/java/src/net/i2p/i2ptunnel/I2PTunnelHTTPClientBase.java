/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base64;
import net.i2p.util.EventDispatcher;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;

/**
 * Common things for HTTPClient and ConnectClient
 * Retrofit over them in 0.8.2
 *
 * @since 0.8.2
 */
public abstract class I2PTunnelHTTPClientBase extends I2PTunnelClientBase implements Runnable {

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
    
    /** used to assign unique IDs to the threads / clients.  no logic or functionality */
    protected static volatile long __clientId = 0;

    protected static final File _errorDir = new File(I2PAppContext.getGlobalContext().getBaseDir(), "docs");

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

    protected static final int DEFAULT_READ_TIMEOUT = 60*1000;
    
    protected static long __requestId = 0;

    public I2PTunnelHTTPClientBase(int localPort, boolean ownDest, Logging l, 
                               EventDispatcher notifyThis, String handlerName, 
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, handlerName, tunnel);
        _proxyList = new ArrayList(4);
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
        _proxyList = new ArrayList(4);
    }

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

    /**
     *  @param authorization may be null
     *  @return success
     */
    protected boolean authorize(Socket s, long requestId, String authorization) {
        // Authorization
        // Ref: RFC 2617
        // If the socket is an InternalSocket, no auth required.
        String authRequired = getTunnel().getClientOptions().getProperty(PROP_AUTH);
        if (Boolean.valueOf(authRequired).booleanValue() ||
            (authRequired != null && "basic".equals(authRequired.toLowerCase(Locale.US)))) {
            if (s instanceof InternalSocket) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(getPrefix(requestId) + "Internal access, no auth required");
                return true;
            } else if (authorization != null) {
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
                                return true;
                            } else {
                                if (_log.shouldLog(Log.WARN))
                                    _log.warn(getPrefix(requestId) + "Bad auth, pw mismatch - user: " + user + " pw: " + pw + " expected: " + configPW);
                            }
                        } else {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn(getPrefix(requestId) + "Bad auth, no stored pw for user: " + user + " pw: " + pw);
                        }
                    } catch (UnsupportedEncodingException uee) {
                        _log.error(getPrefix(requestId) + "No UTF-8 support? B64: " + authorization, uee);
                    } catch (ArrayIndexOutOfBoundsException aioobe) {
                        // no ':' in response
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(getPrefix(requestId) + "Bad auth B64: " + authorization, aioobe);
                    }
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getPrefix(requestId) + "Bad auth B64: " + authorization);
                }
            }
            return false;
        } else {
            return true;
        }
    }
}
