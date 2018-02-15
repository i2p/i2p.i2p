package net.i2p.client.streaming;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.EepGet;
import net.i2p.util.SocketTimeout;

/**
 *  Fetch a URL using a socket from the supplied I2PSocketManager.
 *  Hostname must resolve to an i2p destination - no routing to an outproxy.
 *  Does not support response gzip decompression (unlike I2PTunnelHTTPProxy) (yet),
 *  but of course there is still gzip at the I2CP layer.
 *
 *  This is designed for Java apps such as bittorrent clients that wish to
 *  do HTTP fetches and use other protocols on a single set of tunnels.
 *  This may provide anonymity benefits over using the shared clients HTTP proxy,
 *  preventing inadvertent outproxy usage, reduce resource usage by eliminating
 *  a second set of tunnels, and eliminate the requirement to
 *  to separately configure the proxy host and port.
 *
 *  For additional documentation see the superclass.
 *
 *  Supports http://example.i2p/blah
 *  Supports http://B32KEY.b32.i2p/blah
 *  Supports http://i2p/B64KEY/blah for compatibility with the eepproxy
 *  Supports http://B64KEY/blah for compatibility with the eepproxy
 *  Warning - does not support /eepproxy/blah, address helpers, http://B64KEY.i2p/blah,
 *  or other odd things that may be found in the HTTP proxy.
 *
 *  @author zzz
 */
public class I2PSocketEepGet extends EepGet {
    private final I2PSocketManager _socketManager;
    /** this replaces _proxy in the superclass. Sadly, I2PSocket does not extend Socket. */
    private I2PSocket _socket;
    
    /** from ConnectionOptions */
    private static final String PROP_CONNECT_DELAY = "i2p.streaming.connectDelay";
    private static final String CONNECT_DELAY = "500";

    public I2PSocketEepGet(I2PAppContext ctx, I2PSocketManager mgr, int numRetries, String outputFile, String url) {
        this(ctx, mgr, numRetries, -1, -1, outputFile, null, url);
    }

    public I2PSocketEepGet(I2PAppContext ctx, I2PSocketManager mgr, int numRetries, long minSize, long maxSize,
                           String outputFile, OutputStream outputStream, String url) {
        // we're using this constructor:
        // public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        super(ctx, false, null, -1, numRetries, minSize, maxSize, outputFile, outputStream, url, true, null, null);
        _socketManager = mgr;
    }
   
    /**
     *  We have to override this to close _socket, since we can't use _proxy in super as the I2PSocket.
     */
    @Override
    public boolean fetch(long fetchHeaderTimeout, long totalTimeout, long inactivityTimeout) {
        boolean rv = super.fetch(fetchHeaderTimeout, totalTimeout, inactivityTimeout);
        if (_socket != null) {
            try { 
                _socket.close(); 
                _socket = null;
            } catch (IOException ioe) {}
        }
        return rv;
    }

    /**
     *  Overridden to disable inline gunzipping
     *  @since 0.8.10
     */
    @Override
    protected void readHeaders() throws IOException {
        try {
            super.readHeaders();
        } finally {
            _isGzippedResponse = false;
        }
    }

    /**
     *  Look up the address, get a socket from the I2PSocketManager supplied in the constructor,
     *  and send the request.
     *
     *  @param timeout ignored 
     */
    @Override
    protected void sendRequest(SocketTimeout timeout) throws IOException {
        if (_outputStream == null) {
            File outFile = new File(_outputFile);
            if (outFile.exists())
                _alreadyTransferred = outFile.length();
        }

        if (_proxyIn != null) try { _proxyIn.close(); } catch (IOException ioe) {}
        if (_proxyOut != null) try { _proxyOut.close(); } catch (IOException ioe) {}
        if (_socket != null) try { _socket.close(); } catch (IOException ioe) {}

        try {
            URI url = new URI(_actualURL);
            if ("http".equals(url.getScheme())) {
                String host = url.getHost();
                if (host == null)
                    throw new MalformedURLException("no hostname: " + _actualURL);
                int port = url.getPort();
                if (port <= 0 || port > 65535)
                    port = 80;

                // HTTP Proxy compatibility http://i2p/B64KEY/blah
                // Rewrite the url to strip out the /i2p/,
                // as the naming service accepts B64KEY (but not B64KEY.i2p atm)
                if ("i2p".equals(host)) {
                    String file = url.getRawPath();
                    try {
                        int slash = 1 + file.substring(1).indexOf('/');
                        host = file.substring(1, slash);
                        _actualURL = "http://" + host + file.substring(slash);
                        String query = url.getRawQuery();
                        if (query != null)
                            _actualURL = _actualURL + '?' + query;
                    } catch (IndexOutOfBoundsException ioobe) {
                        throw new MalformedURLException("Bad /i2p/ format: " + _actualURL);
                    }
                }

                // Use existing I2PSession for lookups.
                // This is much more efficient than using the naming service
                Destination dest;
                I2PSession sess = _socketManager.getSession();
                if (sess != null && !sess.isClosed()) {
                    try {
                        if (host.length() == 60 && host.endsWith(".b32.i2p")) {
                            byte[] b = Base32.decode(host.substring(0, 52));
                            if (b != null) {
                                Hash h = Hash.create(b);
                                dest = sess.lookupDest(h, 20*1000);
                            } else {
                                dest = null;
                            }
                        } else {
                            dest = sess.lookupDest(host, 20*1000);
                        }
                    } catch (I2PSessionException ise) {
                        dest = null;
                    }
                } else {
                    dest = _context.namingService().lookup(host);
                }
                if (dest == null)
                    throw new UnknownHostException("Unknown or non-i2p host: " + host);

                // Set the timeouts, using the other existing options in the socket manager
                // This currently duplicates what SocketTimeout is doing in EepGet,
                // but when that's ripped out of EepGet to use setsotimeout, we'll need this.
                Properties props = new Properties();
                props.setProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT, "" + CONNECT_TIMEOUT);
                props.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, "" + INACTIVITY_TIMEOUT);
                // This is important - even if the underlying socket doesn't have a connect delay,
                // we want to set it for this connection, so the request headers will go out
                // in the SYN packet, saving one RTT.
                props.setProperty(PROP_CONNECT_DELAY, CONNECT_DELAY);
                I2PSocketOptions opts = _socketManager.buildOptions(props);
                opts.setPort(port);
                _socket = _socketManager.connect(dest, opts);
            } else {
                throw new MalformedURLException("Unsupported protocol: " + _actualURL);
            }
        } catch (URISyntaxException use) {
            IOException ioe = new MalformedURLException("Bad URL");
            ioe.initCause(use);
            throw ioe;
        } catch (I2PException ie) {
            throw new IOException("I2P error", ie);
        }

        _proxyIn = _socket.getInputStream();
        _proxyOut = _socket.getOutputStream();
        
        // SocketTimeout doesn't take an I2PSocket, but no matter, because we
        // always close our socket in fetch() above.
        //timeout.setSocket(_socket);
        
        String req = getRequest();
        _proxyOut.write(DataHelper.getUTF8(req));
        _proxyOut.flush();
    }

    /**
     *  Guess we have to override this since
     *  super doesn't strip the http://host from the GET line
     *  which hoses some servers (opentracker)
     *  HTTP proxy was kind enough to do this for us
     */
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
        //String host = url.getHost();
        String path = url.getRawPath();
        String query = url.getRawQuery();
        if (query != null)
            path = path + '?' + query;
        if (!path.startsWith("/"))
	    path = '/' + path;
        buf.append("GET ").append(path).append(" HTTP/1.1\r\n" +
                   "Host: ").append(url.getHost()).append("\r\n");
        if (_alreadyTransferred > 0) {
            buf.append("Range: bytes=");
            buf.append(_alreadyTransferred);
            buf.append("-\r\n");
        }
        buf.append("Accept-Encoding: \r\n" +
                   "Cache-Control: no-cache\r\n" +
                   "Pragma: no-cache\r\n" +
                   "Connection: close\r\n");
        boolean uaOverridden = false;
        if (_extraHeaders != null) {
            for (String hdr : _extraHeaders) {
                if (hdr.toLowerCase(Locale.US).startsWith("user-agent: "))
                    uaOverridden = true;
                buf.append(hdr).append("\r\n");
            }
        }
        if(!uaOverridden)
            buf.append("User-Agent: " + USER_AGENT + "\r\n");
        buf.append("\r\n");
        if (_log.shouldDebug())
            _log.debug("Request: [" + buf.toString() + "]");
        return buf.toString();
    }

    /**
     * I2PSocketEepGet [-n #retries] [-t timeout] url
     * Uses I2CP at localhost:7654 with a single 1-hop tunnel each direction.
     * Tunnel build time not included in the timeout.
     *
     * This is just for testing, it will be commented out someday.
     * Real command line apps should use EepGet.main(),
     * which has more options, and you don't have to wait for tunnels to be built.
     */ 
/****
    public static void main(String args[]) {
        int numRetries = 0;
        long inactivityTimeout = INACTIVITY_TIMEOUT;
        String url = null;
        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-n")) {
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
        } catch (RuntimeException e) {
            e.printStackTrace();
            usage();
            return;
        }
        
        if (url == null) {
            usage();
            return;
        }

        Properties opts = new Properties();
        opts.setProperty("i2cp.dontPublishLeaseSet", "true");
        opts.setProperty("inbound.quantity", "1");
        opts.setProperty("outbound.quantity", "1");
        opts.setProperty("inbound.length", "1");
        opts.setProperty("outbound.length", "1");
        opts.setProperty("inbound.nickname", "I2PSocketEepGet");
        I2PSocketManager mgr = I2PSocketManagerFactory.createManager(opts);
        if (mgr == null) {
            System.err.println("Error creating the socket manager");
            return;
        }
        I2PSocketEepGet get = new I2PSocketEepGet(I2PAppContext.getGlobalContext(),
                                                  mgr, numRetries, suggestName(url), url);
        get.addStatusListener(get.new CLIStatusListener(1024, 40));
        get.fetch(inactivityTimeout, -1, inactivityTimeout);
        mgr.destroySocketManager();
    }
    
    private static void usage() {
        System.err.println("I2PSocketEepGet [-n #retries] [-t timeout] url");
    }
****/
}
