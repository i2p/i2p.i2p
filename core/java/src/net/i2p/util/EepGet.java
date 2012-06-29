package net.i2p.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.InternalSocket;

/**
 * EepGet [-p 127.0.0.1:4444]
 *        [-n #retries]
 *        [-o outputFile]
 *        [-m markSize lineLen]
 *        url
 *
 * Bug: a malformed url http://example.i2p (no trailing '/') fails cryptically
 */
public class EepGet {
    protected final I2PAppContext _context;
    protected final Log _log;
    protected final boolean _shouldProxy;
    private final String _proxyHost;
    private final int _proxyPort;
    protected final int _numRetries;
    private final long _minSize; // minimum and maximum acceptable response size, -1 signifies unlimited,
    private final long _maxSize; // applied both against whole responses and chunks
    protected final String _outputFile;
    protected final OutputStream _outputStream;
    /** url we were asked to fetch */
    protected final String _url;
    /** the URL we actually fetch from (may differ from the _url in case of redirect) */
    protected String _actualURL;
    private final String _postData;
    private boolean _allowCaching;
    protected final List<StatusListener> _listeners;
    protected List<String> _extraHeaders;

    protected boolean _keepFetching;
    protected Socket _proxy;
    protected OutputStream _proxyOut;
    protected InputStream _proxyIn;
    protected OutputStream _out;
    protected long _alreadyTransferred;
    protected long _bytesTransferred;
    protected long _bytesRemaining;
    protected int _currentAttempt;
    protected int _responseCode = -1;
    protected boolean _shouldWriteErrorToOutput;
    protected String _etag;
    protected String _lastModified;
    protected boolean _encodingChunked;
    protected boolean _notModified;
    protected String _contentType;
    protected boolean _transferFailed;
    protected boolean _headersRead;
    protected boolean _aborted;
    private long _fetchHeaderTimeout;
    private long _fetchEndTime;
    protected long _fetchInactivityTimeout;
    protected int _redirects;
    protected String _redirectLocation;
    protected boolean _isGzippedResponse;
    protected IOException _decompressException;

    /** this will be replaced by the HTTP Proxy if we are using it */
    protected static final String USER_AGENT = "Wget/1.11.4";
    protected static final long CONNECT_TIMEOUT = 45*1000;
    protected static final long INACTIVITY_TIMEOUT = 60*1000;
    /** maximum times to try without getting any data at all, even if numRetries is higher @since 0.7.14 */
    protected static final int MAX_COMPLETE_FAILS = 5;

    public EepGet(I2PAppContext ctx, String proxyHost, int proxyPort, int numRetries, String outputFile, String url) {
        this(ctx, true, proxyHost, proxyPort, numRetries, outputFile, url);
    }

    public EepGet(I2PAppContext ctx, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, boolean allowCaching) {
        this(ctx, true, proxyHost, proxyPort, numRetries, outputFile, url, allowCaching, null);
    }

    public EepGet(I2PAppContext ctx, int numRetries, String outputFile, String url) {
        this(ctx, false, null, -1, numRetries, outputFile, url);
    }

    public EepGet(I2PAppContext ctx, int numRetries, String outputFile, String url, boolean allowCaching) {
        this(ctx, false, null, -1, numRetries, outputFile, url, allowCaching, null);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, outputFile, url, true, null);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, String postData) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, -1, -1, outputFile, null, url, true, null, postData);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, boolean allowCaching, String etag) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, -1, -1, outputFile, null, url, allowCaching, etag, null);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, boolean allowCaching, String etag, String lastModified) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, -1, -1, outputFile, null, url, allowCaching, etag, lastModified, null);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag, String postData) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, minSize, maxSize, outputFile, outputStream, url, allowCaching, etag, null, postData);
    }

    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize, long maxSize,
                  String outputFile, OutputStream outputStream, String url, boolean allowCaching,
                  String etag, String lastModified, String postData) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _shouldProxy = (proxyHost != null) && (proxyHost.length() > 0) && (proxyPort > 0) && shouldProxy;
        _proxyHost = proxyHost;
        _proxyPort = proxyPort;
        _numRetries = numRetries;
        _minSize = minSize;
        _maxSize = maxSize;
        _outputFile = outputFile;     // if outputFile is set, outputStream must be null
        _outputStream = outputStream; // if both are set, outputStream overrides outputFile
        _url = url;
        _actualURL = url;
        _postData = postData;
        _bytesRemaining = -1;
        _fetchHeaderTimeout = CONNECT_TIMEOUT;
        _listeners = new ArrayList(1);
        _etag = etag;
        _lastModified = lastModified;
    }

    /**
     * EepGet [-p 127.0.0.1:4444] [-n #retries] [-e etag] [-o outputFile] [-m markSize lineLen] url
     *
     */
    public static void main(String args[]) {
        String proxyHost = "127.0.0.1";
        int proxyPort = 4444;
        int numRetries = 5;
        int markSize = 1024;
        int lineLen = 40;
        long inactivityTimeout = INACTIVITY_TIMEOUT;
        String etag = null;
        String saveAs = null;
        String url = null;
        List<String> extra = null;
        String username = null;
        String password = null;
        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-p")) {
                    proxyHost = args[++i].substring(0, args[i].indexOf(':'));
                    String port = args[i].substring(args[i].indexOf(':')+1);
                    proxyPort = Integer.parseInt(port);
                } else if (args[i].equals("-n")) {
                    numRetries = Integer.parseInt(args[i+1]);
                    i++;
                } else if (args[i].equals("-t")) {
                    inactivityTimeout = 1000 * Integer.parseInt(args[i+1]);
                    i++;
                } else if (args[i].equals("-e")) {
                    etag = "\"" + args[i+1] + "\"";
                    i++;
                } else if (args[i].equals("-o")) {
                    saveAs = args[i+1];
                    i++;
                } else if (args[i].equals("-m")) {
                    markSize = Integer.parseInt(args[++i]);
                    lineLen = Integer.parseInt(args[++i]);
                } else if (args[i].equals("-h")) {
                    if (extra == null)
                        extra = new ArrayList(2);
                    extra.add(args[++i]);
                    extra.add(args[++i]);
                } else if (args[i].equals("-u")) {
                    username = args[++i];
                    password = args[++i];
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
        if (saveAs == null)
            saveAs = suggestName(url);

        EepGet get = new EepGet(I2PAppContext.getGlobalContext(), true, proxyHost, proxyPort, numRetries, saveAs, url, true, etag);
        if (extra != null) {
            for (int i = 0; i < extra.size(); i += 2) {
                get.addHeader(extra.get(i), extra.get(i + 1));
            }
        }
        if (username != null && password != null)
            get.addAuthorization(username, password);
        get.addStatusListener(get.new CLIStatusListener(markSize, lineLen));
        if (!get.fetch(CONNECT_TIMEOUT, -1, inactivityTimeout))
            System.exit(1);
    }

    public static String suggestName(String url) {
        int last = url.lastIndexOf('/');
        if ((last < 0) || (url.lastIndexOf('#') > last))
            last = url.lastIndexOf('#');
        if ((last < 0) || (url.lastIndexOf('?') > last))
            last = url.lastIndexOf('?');
        if ((last < 0) || (url.lastIndexOf('=') > last))
            last = url.lastIndexOf('=');

        String name = null;
        if (last >= 0)
            name = sanitize(url.substring(last+1));
        if ( (name != null) && (name.length() > 0) )
            return name;
        else
            return sanitize(url);
    }


/* Blacklist borrowed from snark */

  private static final char[] ILLEGAL = new char[] {
        '<', '>', ':', '"', '/', '\\', '|', '?', '*',
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
        0x7f };

  /**
   * Removes 'suspicious' characters from the given file name.
   * http://msdn.microsoft.com/en-us/library/aa365247%28VS.85%29.aspx
   */


    private static String sanitize(String name) {
    if (name.equals(".") || name.equals(" "))
        return "_";
    String rv = name;
    if (rv.startsWith("."))
        rv = '_' + rv.substring(1);
    if (rv.endsWith(".") || rv.endsWith(" "))
        rv = rv.substring(0, rv.length() - 1) + '_';
    for (int i = 0; i < ILLEGAL.length; i++) {
        if (rv.indexOf(ILLEGAL[i]) >= 0)
            rv = rv.replace(ILLEGAL[i], '_');
    }
    return rv;
    }

    private static void usage() {
        System.err.println("EepGet [-p 127.0.0.1:4444] [-n #retries] [-o outputFile]\n" +
                           "       [-m markSize lineLen] [-t timeout] [-h headerKey headerValue]\n" +
                           "       [-u username password] url]\n" +
                           "       (use -p :0 for no proxy)");
    }
    
    public static interface StatusListener {
        /**
         *  alreadyTransferred - total of all attempts, not including currentWrite
         *                       If nonzero on the first call, a partial file of that length was found,
         *                       _and_ the server supports resume.
         *                       If zero on a subsequent call after some bytes are transferred
         *                       (and presumably after an attemptFailed), the server does _not_
         *                       support resume and we had to start over.
         *                       To track _actual_ transfer if the output file could already exist,
         *                       the listener should keep its own counter,
         *                       or subtract the initial alreadyTransferred value.
         *                       And watch out for alreadyTransferred resetting if a resume failed...
         *  currentWrite - since last call to the listener
         *  bytesTransferred - includes headers, retries, redirects, discarded partial downloads, ...
         *  bytesRemaining - on this attempt only, currentWrite already subtracted -
         *                   or -1 if chunked encoding or server does not return a length
         *
         *  Total length should be == alreadyTransferred + currentWrite + bytesRemaining for all calls
         *
         */
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url);
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified);
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause);
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt);

        /**
         *  Note: Headers are not processed, and this is not called, for most error response codes,
         *  unless setWriteErrorToOutput() is called before fetch().
         *  To be changed?
         */
        public void headerReceived(String url, int currentAttempt, String key, String val);

        public void attempting(String url);
    }
    protected class CLIStatusListener implements StatusListener {
        private final int _markSize;
        private final int _lineSize;
        private final long _startedOn;
        private long _written;
        private long _previousWritten;
        private long _discarded;
        private long _lastComplete;
        private boolean _firstTime;
        private final DecimalFormat _pct = new DecimalFormat("00.0%");
        private final DecimalFormat _kbps = new DecimalFormat("###,000.00");
        public CLIStatusListener() { 
            this(1024, 40);
        }
        public CLIStatusListener(int markSize, int lineSize) { 
            _markSize = markSize;
            _lineSize = lineSize;
            _lastComplete = _context.clock().now();
            _startedOn = _lastComplete;
            _firstTime = true;
        }
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
            if (_firstTime) {
                if (alreadyTransferred > 0) {
                    _previousWritten = alreadyTransferred;
                    System.out.println("File found with length " + alreadyTransferred + ", resuming");
                }
                _firstTime = false;
            }
            if (_written == 0 && alreadyTransferred == 0 && _previousWritten > 0) {
                // boo
                System.out.println("Server does not support resume, discarding " + _previousWritten + " bytes");
                _discarded += _previousWritten;
                _previousWritten = 0;
            }
            for (int i = 0; i < currentWrite; i++) {
                _written++;
                if ( (_markSize > 0) && (_written % _markSize == 0) ) {
                    System.out.print("#");
                    
                    if ( (_lineSize > 0) && (_written % ((long)_markSize*(long)_lineSize) == 0l) ) {
                        long now = _context.clock().now();
                        long timeToSend = now - _lastComplete;
                        if (timeToSend > 0) {
                            StringBuilder buf = new StringBuilder(50);
                            Formatter fmt = new Formatter(buf);
                            buf.append(" ");
                            if ( bytesRemaining > 0 ) {
                                double pct = 100 * ((double)_written + _previousWritten) /
                                             ((double)alreadyTransferred + (double)currentWrite + bytesRemaining);
                                fmt.format("%4.1f", Double.valueOf(pct));
                                buf.append("%: ");
                            }
                            fmt.format("%8d", Long.valueOf(_written));
                            buf.append(" @ ");
                            double lineKBytes = ((double)_markSize * (double)_lineSize)/1024.0d;
                            double kbps = lineKBytes/(timeToSend/1000.0d);
                            fmt.format("%7.2f", Double.valueOf(kbps));
                            buf.append(" KBps");
                            
                            buf.append(" / ");
                            long lifetime = _context.clock().now() - _startedOn;
                            double lifetimeKBps = (1000.0d*(_written)/(lifetime*1024.0d));
                            fmt.format("%7.2f", Double.valueOf(lifetimeKBps));
                            buf.append(" KBps");
                            System.out.println(buf.toString());
                        }
                        _lastComplete = now;
                    }
                }
            }
        }
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            long transferred;
            if (_firstTime)
                transferred = 0;
            else
                transferred = alreadyTransferred - _previousWritten;
            System.out.println();
            System.out.println("== " + new Date());
            if (notModified) {
                System.out.println("== Source not modified since last download");
            } else {
                if ( bytesRemaining > 0 ) {
                    System.out.println("== Transfer of " + url + " completed with " + transferred
                            + " transferred and " + (bytesRemaining - bytesTransferred) + " remaining" +
                            (_discarded > 0 ? (" and " + _discarded + " bytes discarded") : ""));
                } else {
                    System.out.println("== Transfer of " + url + " completed with " + transferred
                            + " bytes transferred" +
                            (_discarded > 0 ? (" and " + _discarded + " bytes discarded") : ""));
                }
                if (transferred > 0)
                    System.out.println("== Output saved to " + outputFile + " (" + alreadyTransferred + " bytes)");
            }
            long timeToSend = _context.clock().now() - _startedOn;
            System.out.println("== Transfer time: " + DataHelper.formatDuration(timeToSend));
            if (_etag != null)
                System.out.println("== ETag: " + _etag);            
            if (transferred > 0) {
                StringBuilder buf = new StringBuilder(50);
                buf.append("== Transfer rate: ");
                double kbps = (1000.0d*(transferred)/(timeToSend*1024.0d));
                synchronized (_kbps) {
                    buf.append(_kbps.format(kbps));
                }
                buf.append("KBps");
                System.out.println(buf.toString());
            }
        }
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            System.out.println();
            System.out.println("** " + new Date());
            System.out.println("** Attempt " + currentAttempt + " of " + url + " failed");
            System.out.println("** Transfered " + bytesTransferred
                               + " with " + (bytesRemaining < 0 ? "unknown" : ""+bytesRemaining) + " remaining");
            System.out.println("** " + cause.getMessage());
            _previousWritten += _written;
            _written = 0;
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            System.out.println("== " + new Date());
            System.out.println("== Transfer of " + url + " failed after " + currentAttempt + " attempts");
            System.out.println("== Transfer size: " + bytesTransferred + " with "
                               + (bytesRemaining < 0 ? "unknown" : ""+bytesRemaining) + " remaining");
            long timeToSend = _context.clock().now() - _startedOn;
            System.out.println("== Transfer time: " + DataHelper.formatDuration(timeToSend));
            double kbps = (timeToSend > 0 ? (1000.0d*(bytesTransferred)/(timeToSend*1024.0d)) : 0);
            StringBuilder buf = new StringBuilder(50);
            buf.append("== Transfer rate: ");
            synchronized (_kbps) {
                buf.append(_kbps.format(kbps));
            }
            buf.append("KBps");
            System.out.println(buf.toString());
        }
        public void attempting(String url) {}
        public void headerReceived(String url, int currentAttempt, String key, String val) {}
    }
    
    public void addStatusListener(StatusListener lsnr) {
        synchronized (_listeners) { _listeners.add(lsnr); }
    }
    
    public void stopFetching() { _keepFetching = false; }

    /**
     * Blocking fetch, returning true if the URL was retrieved, false if all retries failed.
     *
     * Header timeout default 45 sec, total timeout default none, inactivity timeout default 60 sec.
     */
    public boolean fetch() { return fetch(_fetchHeaderTimeout); }

    /**
     * Blocking fetch, timing out individual attempts if the HTTP response headers
     * don't come back in the time given.  If the timeout is zero or less, this will
     * wait indefinitely.
     *
     * Total timeout default none, inactivity timeout default 60 sec.
     */
    public boolean fetch(long fetchHeaderTimeout) {
        return fetch(fetchHeaderTimeout, -1, -1);
    }

    /**
     * Blocking fetch.
     *
     * @param fetchHeaderTimeout <= 0 for none (proxy will timeout if none, none isn't recommended if no proxy)
     * @param totalTimeout <= 0 for default none
     * @param inactivityTimeout <= 0 for default 60 sec
     */
    public boolean fetch(long fetchHeaderTimeout, long totalTimeout, long inactivityTimeout) {
        _fetchHeaderTimeout = fetchHeaderTimeout;
        _fetchEndTime = (totalTimeout > 0 ? System.currentTimeMillis() + totalTimeout : -1);
        _fetchInactivityTimeout = inactivityTimeout;
        _keepFetching = true;

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fetching (proxied? " + _shouldProxy + ") url=" + _actualURL);
        while (_keepFetching) {
            SocketTimeout timeout = null;
            if (_fetchHeaderTimeout > 0) {
                timeout = new SocketTimeout(_fetchHeaderTimeout);
                final SocketTimeout stimeout = timeout; // ugly - why not use sotimeout?
                timeout.setTimeoutCommand(new Runnable() {
                    public void run() {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("timeout reached on " + _url + ": " + stimeout);
                        _aborted = true;
                    }
                });
                timeout.setTotalTimeoutPeriod(_fetchEndTime);
            }
            try {
                for (int i = 0; i < _listeners.size(); i++) 
                    _listeners.get(i).attempting(_url);
                sendRequest(timeout);
                if (timeout != null)
                    timeout.resetTimer();
                doFetch(timeout);
                if (timeout != null)
                    timeout.cancel();
                if (!_transferFailed)
                    return true;
                break;
            } catch (IOException ioe) {
                if (timeout != null)
                    timeout.cancel();
                for (int i = 0; i < _listeners.size(); i++) 
                    _listeners.get(i).attemptFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt, _numRetries, ioe);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("ERR: doFetch failed ", ioe);
                if (ioe instanceof MalformedURLException)
                    _keepFetching = false;
            } finally {
                if (_out != null) {
                    try {
                        _out.close();
                    } catch (IOException cioe) {}
                    _out = null;
                }
                if (_proxy != null) {
                    try { 
                        _proxy.close(); 
                        _proxy = null;
                    } catch (IOException ioe) {}
                }
            }

            _currentAttempt++;
            if (_currentAttempt > _numRetries ||
                (_alreadyTransferred == 0 && _currentAttempt > MAX_COMPLETE_FAILS) ||
                !_keepFetching) 
                break;
            _redirects = 0;
            try { 
                long delay = _context.random().nextInt(60*1000);
                Thread.sleep(5*1000+delay); 
            } catch (InterruptedException ie) {}
        }

        for (int i = 0; i < _listeners.size(); i++) 
            _listeners.get(i).transferFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt);
        if (_log.shouldLog(Log.WARN))
            _log.warn("All attempts failed for " + _url);
        return false;
    }

    /**
     *  single fetch
     *  @param timeout may be null
     */
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
        
        if (timeout != null) {
            timeout.resetTimer();
            if (_fetchInactivityTimeout > 0)
                timeout.setInactivityTimeout(_fetchInactivityTimeout);
            else
                timeout.setInactivityTimeout(INACTIVITY_TIMEOUT);
        }
        
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
            _log.debug("Headers read completely, reading " + _bytesRemaining);
        
        boolean strictSize = (_bytesRemaining >= 0);

        // If minimum or maximum size defined, ensure they aren't exceeded
        if ((_minSize > 0) && (_bytesRemaining < _minSize))
            throw new IOException("HTTP response size " + _bytesRemaining + " violates minimum of " + _minSize + " bytes");
        if ((_maxSize > -1) && (_bytesRemaining > _maxSize))
            throw new IOException("HTTP response size " + _bytesRemaining + " violates maximum of " + _maxSize + " bytes");

        Thread pusher = null;
        _decompressException = null;
        if (_isGzippedResponse) {
            PipedInputStream pi = BigPipedInputStream.getInstance();
            PipedOutputStream po = new PipedOutputStream(pi);
            pusher = new I2PAppThread(new Gunzipper(pi, _out), "EepGet Decompressor");
            _out = po;
            pusher.start();
        }

        int remaining = (int)_bytesRemaining;
        byte buf[] = new byte[16*1024];
        while (_keepFetching && ( (remaining > 0) || !strictSize ) && !_aborted) {
            int toRead = buf.length;
            if (strictSize && toRead > remaining)
                toRead = remaining;
            int read = _proxyIn.read(buf, 0, toRead);
            if (read == -1)
                break;
            if (timeout != null)
                timeout.resetTimer();
            _out.write(buf, 0, read);
            _bytesTransferred += read;
            if ((_maxSize > -1) && (_alreadyTransferred + read > _maxSize)) // could transfer a little over maxSize
                throw new IOException("Bytes transferred " + (_alreadyTransferred + read) + " violates maximum of " + _maxSize + " bytes");
            remaining -= read;
            if (remaining==0 && _encodingChunked) {
                int char1 = _proxyIn.read();
                if (char1 == '\r') {
                    int char2 = _proxyIn.read();
                    if (char2 == '\n') {
                        remaining = (int) readChunkLength();
                    } else {
                        _out.write(char1);
                        _out.write(char2);
                        _bytesTransferred += 2;
                        remaining -= 2;
                        read += 2;
                    }
                } else {
                    _out.write(char1);
                    _bytesTransferred++;
                    remaining--;
                    read++;
                }
            }
            if (timeout != null)
                timeout.resetTimer();
            if (_bytesRemaining >= read) // else chunked?
                _bytesRemaining -= read;
            if (read > 0) {
                for (int i = 0; i < _listeners.size(); i++) 
                    _listeners.get(i).bytesTransferred(
                            _alreadyTransferred, 
                            read, 
                            _bytesTransferred, 
                            _encodingChunked?-1:_bytesRemaining, 
                            _url);
                // This seems necessary to properly resume a partial download into a stream,
                // as nothing else increments _alreadyTransferred, and there's no file length to check.
                // Do this after calling the listeners to keep the total correct
                _alreadyTransferred += read;
            }
        }
            
        if (_out != null)
            _out.close();
        _out = null;
        
        if (_isGzippedResponse) {
            try {
                pusher.join();
            } catch (InterruptedException ie) {}
            pusher = null;
            if (_decompressException != null) {
                // we can't resume from here
                _keepFetching = false;
                throw _decompressException;
            }
        }

        if (_aborted)
            throw new IOException("Timed out reading the HTTP data");
        
        if (timeout != null)
            timeout.cancel();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Done transferring " + _bytesTransferred + " (ok? " + !_transferFailed + ")");


        if (_transferFailed) {
            // 404, etc - transferFailed is called after all attempts fail, by fetch() above
            for (int i = 0; i < _listeners.size(); i++) 
                _listeners.get(i).attemptFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt, _numRetries, new Exception("Attempt failed"));
        } else if ((_minSize > 0) && (_alreadyTransferred < _minSize)) {
            throw new IOException("Bytes transferred " + _alreadyTransferred + " violates minimum of " + _minSize + " bytes");
        } else if ( (_bytesRemaining == -1) || (remaining == 0) ) {
            for (int i = 0; i < _listeners.size(); i++) 
                _listeners.get(i).transferComplete(
                        _alreadyTransferred, 
                        _bytesTransferred, 
                        _encodingChunked?-1:_bytesRemaining, 
                        _url, 
                        _outputFile, 
                        _notModified);
        } else {
            throw new IOException("Disconnection on attempt " + _currentAttempt + " after " + _bytesTransferred);
        }
    }

    protected void readHeaders() throws IOException {
        String key = null;
        StringBuilder buf = new StringBuilder(32);

        boolean read = DataHelper.readLine(_proxyIn, buf);
        if (!read) throw new IOException("Unable to read the first line");
        _responseCode = handleStatus(buf.toString());
        boolean redirect = false;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("rc: " + _responseCode + " for " + _actualURL);
        boolean rcOk = false;
        switch (_responseCode) {
            case 200: // full
                if (_outputStream != null)
                    _out = _outputStream;
		else
		    _out = new FileOutputStream(_outputFile, false);
                _alreadyTransferred = 0;
                rcOk = true;
                break;
            case 206: // partial
                if (_outputStream != null)
                    _out = _outputStream;
		else
		    _out = new FileOutputStream(_outputFile, true);
                rcOk = true;
                break;
            case 301: // various redirections
            case 302:
            case 303:
            case 307:
                _alreadyTransferred = 0;
                rcOk = true;
                redirect = true;
                break;
            case 304: // not modified
                _bytesRemaining = 0;
                _keepFetching = false;
                _notModified = true;
                return; 
            case 403: // bad req
            case 404: // not found
            case 409: // bad addr helper
            case 503: // no outproxy
                _transferFailed = true;
                if (_alreadyTransferred > 0 || !_shouldWriteErrorToOutput) {
                    _keepFetching = false;
                    return;
                }
                // output the error data to the stream
                rcOk = true;
                if (_out == null) {
                    if (_outputStream != null)
                        _out = _outputStream;
                    else
                        _out = new FileOutputStream(_outputFile, true);
                }
                break;
            case 416: // completed (or range out of reach)
                _bytesRemaining = 0;
                if (_alreadyTransferred > 0 || !_shouldWriteErrorToOutput) {
                    _keepFetching = false;
                    return;
                }
                // output the error data to the stream
                rcOk = true;
                if (_out == null) {
                    if (_outputStream != null)
                        _out = _outputStream;
                    else
                        _out = new FileOutputStream(_outputFile, true);
                }
                break;
            case 504: // gateway timeout
                if (_alreadyTransferred > 0 || (!_shouldWriteErrorToOutput) ||
                    _currentAttempt < _numRetries) {
                    // throw out of doFetch() to fetch() and try again
                    // why throw???
                    throw new IOException("HTTP Proxy timeout");
                }
                // output the error data to the stream
                rcOk = true;
                if (_out == null) {
                    if (_outputStream != null)
                        _out = _outputStream;
                    else
                        _out = new FileOutputStream(_outputFile, true);
                }
                _transferFailed = true;
                break;
            default:
                if (_alreadyTransferred > 0 || !_shouldWriteErrorToOutput) {
                    _keepFetching = false;
                } else {
                    // output the error data to the stream
                    rcOk = true;
                    if (_out == null) {
                        if (_outputStream != null)
                            _out = _outputStream;
                        else
                            _out = new FileOutputStream(_outputFile, true);
                    }
                }
                _transferFailed = true;
        }

        _isGzippedResponse = false;
        // clear out the arguments, as we use the same variables for return values
        _etag = null;
        _lastModified = null;

        buf.setLength(0);
        byte lookahead[] = new byte[3];
        while (true) {
            int cur = _proxyIn.read();
            switch (cur) {
                case -1: 
                    throw new IOException("Headers ended too soon");
                case ':':
                    if (key == null) {
                        key = buf.toString();
                        buf.setLength(0);
                        increment(lookahead, cur);
                        break;
                    } else {
                        buf.append((char)cur);
                        increment(lookahead, cur);
                        break;
                    }
                case '\n':
                case '\r':
                    if (key != null)
                        handle(key, buf.toString());

                    buf.setLength(0);
                    key = null;
                    increment(lookahead, cur);
                    if (isEndOfHeaders(lookahead)) {
                        if (!rcOk)
                            throw new IOException("Invalid HTTP response code: " + _responseCode);
                        if (_encodingChunked) {
                            _bytesRemaining = readChunkLength();
                        }
                        if (!redirect) _redirectLocation = null;
                        return;
                    }
                    break;
                default:
                    buf.append((char)cur);
                    increment(lookahead, cur);
            }
            
            if (buf.length() > 1024)
                throw new IOException("Header line too long: " + buf.toString());
        }
    }
    
    protected long readChunkLength() throws IOException {
        StringBuilder buf = new StringBuilder(8);
        int nl = 0;
        while (true) {
            int cur = _proxyIn.read();
            switch (cur) {
                case -1: 
                    throw new IOException("Chunk ended too soon");
                case '\n':
                case '\r':
                    nl++;
                default:
                    buf.append((char)cur);
            }
            
            if (nl >= 2)
                break;
        }
        
        String len = buf.toString().trim();
        try {
            long bytes = Long.parseLong(len, 16);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Chunked length: " + bytes);
            return bytes;
        } catch (NumberFormatException nfe) {
            throw new IOException("Invalid chunk length [" + len + "]");
        }
    }

    /**
     * parse the first status line and grab the response code.
     * e.g. "HTTP/1.1 206 OK" vs "HTTP/1.1 200 OK" vs 
     * "HTTP/1.1 404 NOT FOUND", etc.  
     *
     * @return HTTP response code (200, 206, other)
     */
    private int handleStatus(String line) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Status line: [" + line + "]");
        StringTokenizer tok = new StringTokenizer(line, " ");
        if (!tok.hasMoreTokens()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("ERR: status "+  line);
            return -1;
        }
        tok.nextToken(); // ignored (protocol)
        if (!tok.hasMoreTokens()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("ERR: status "+  line);
            return -1;
        }
        String rc = tok.nextToken();
        try {
            return Integer.parseInt(rc); 
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("ERR: status is invalid: " + line, nfe);
            return -1;
        }
    }

    private void handle(String key, String val) {
        key = key.trim();
        val = val.trim();
        for (int i = 0; i < _listeners.size(); i++) 
            _listeners.get(i).headerReceived(_url, _currentAttempt, key, val);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Header line: [" + key + "] = [" + val + "]");
        key = key.toLowerCase(Locale.US);
        if (key.equals("content-length")) {
            try {
                _bytesRemaining = Long.parseLong(val);
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
            }
        } else if (key.equals("etag")) {
            _etag = val;
        } else if (key.equals("last-modified")) {
            _lastModified = val;
        } else if (key.equals("transfer-encoding")) {
            _encodingChunked = val.toLowerCase(Locale.US).contains("chunked");
        } else if (key.equals("content-encoding")) {
            // This is kindof a hack, but if we are downloading a gzip file
            // we don't want to transparently gunzip it and save it as a .gz file.
            // A query string will also mess this up
            if ((!_actualURL.endsWith(".gz")) && (!_actualURL.endsWith(".tgz")))
                _isGzippedResponse = val.toLowerCase(Locale.US).contains("gzip");
        } else if (key.equals("content-type")) {
            _contentType=val;
        } else if (key.equals("location")) {
            _redirectLocation=val;
        } else {
            // ignore the rest
        }
    }

    private static void increment(byte[] lookahead, int cur) {
        lookahead[0] = lookahead[1];
        lookahead[1] = lookahead[2];
        lookahead[2] = (byte)cur;
    }
    private static boolean isEndOfHeaders(byte lookahead[]) {
        byte first = lookahead[0];
        byte second = lookahead[1];
        byte third = lookahead[2];
        return (isNL(second) && isNL(third)) || //   \n\n
               (isNL(first) && isNL(third));    // \n\r\n
    }

    /** we ignore any potential \r, since we trim it on write anyway */
    private static final byte NL = '\n';
    private static boolean isNL(byte b) { return (b == NL); }

    /**
     *  @param timeout may be null
     */
    protected void sendRequest(SocketTimeout timeout) throws IOException {
        if (_outputStream != null) {
            // We are reading into a stream supplied by a caller,
            // for which we cannot easily determine how much we've written.
            // Assume that _alreadyTransferred holds the right value
            // (we should never be restarted to work on an old stream).
	} else {
            File outFile = new File(_outputFile);
            if (outFile.exists())
                _alreadyTransferred = outFile.length();
        }

        String req = getRequest();

        if (_proxyIn != null) try { _proxyIn.close(); } catch (IOException ioe) {}
        if (_proxyOut != null) try { _proxyOut.close(); } catch (IOException ioe) {}
        if (_proxy != null) try { _proxy.close(); } catch (IOException ioe) {}

        if (_shouldProxy) {
            _proxy = InternalSocket.getSocket(_proxyHost, _proxyPort);
        } else {
            //try {
                URL url = new URL(_actualURL);
                if ("http".equals(url.getProtocol())) {
                    String host = url.getHost();
                    int port = url.getPort();
                    if (port == -1)
                        port = 80;
                    _proxy = new Socket(host, port);
                } else {
                    throw new IOException("URL is not supported:" + _actualURL);
                }
            // an MUE is an IOE
            //} catch (MalformedURLException mue) {
            //    throw new IOException("Request URL is invalid");
            //}
        }
        _proxyIn = _proxy.getInputStream();
        _proxyOut = _proxy.getOutputStream();
        
        if (timeout != null)
            timeout.setSocket(_proxy);
        
        _proxyOut.write(DataHelper.getUTF8(req));
        _proxyOut.flush();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request flushed");
    }
    
    protected String getRequest() throws IOException {
        StringBuilder buf = new StringBuilder(2048);
        boolean post = false;
        if ( (_postData != null) && (_postData.length() > 0) )
            post = true;
        URL url = new URL(_actualURL);
        String proto = url.getProtocol();
        String host = url.getHost();
        int port = url.getPort();
        String path = url.getPath();
        String query = url.getQuery();
        if (query != null)
            path = path + '?' + query;
        if (!path.startsWith("/"))
	    path = "/" + path;
        if ( (port == 80) || (port == 443) || (port <= 0) ) path = proto + "://" + host + path;
        else path = proto + "://" + host + ":" + port + path;
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Requesting " + path);
        if (post) {
            buf.append("POST ").append(_actualURL).append(" HTTP/1.1\r\n");
        } else {
            buf.append("GET ").append(_actualURL).append(" HTTP/1.1\r\n");
        }
        buf.append("Host: ").append(url.getHost()).append("\r\n");
        if (_alreadyTransferred > 0) {
            buf.append("Range: bytes=");
            buf.append(_alreadyTransferred);
            buf.append("-\r\n");
        }
        if (!_allowCaching) {
            buf.append("Cache-control: no-cache\r\n" +
                       "Pragma: no-cache\r\n");
        }
        if ((_etag != null) && (_alreadyTransferred <= 0)) {
            buf.append("If-None-Match: ");
            buf.append(_etag);
            buf.append("\r\n");
        }
        if ((_lastModified != null) && (_alreadyTransferred <= 0)) {
            buf.append("If-Modified-Since: ");
            buf.append(_lastModified);
            buf.append("\r\n");
        }
        if (post)
            buf.append("Content-length: ").append(_postData.length()).append("\r\n");
        // This will be replaced if we are going through I2PTunnelHTTPClient
        buf.append("Accept-Encoding: ");
        if ((!_shouldProxy) &&
            // This is kindof a hack, but if we are downloading a gzip file
            // we don't want to transparently gunzip it and save it as a .gz file.
            (!path.endsWith(".gz")) && (!path.endsWith(".tgz")))
            buf.append("gzip");
        buf.append("\r\nUser-Agent: " + USER_AGENT + "\r\n" +
                   "Connection: close\r\n");
        if (_extraHeaders != null) {
            for (String hdr : _extraHeaders) {
                buf.append(hdr).append("\r\n");
            }
        }
        buf.append("\r\n");
        if (post)
            buf.append(_postData);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request: [" + buf.toString() + "]");
        return buf.toString();
    }

    public String getETag() {
        return _etag;
    }
    
    public String getLastModified() {
        return _lastModified;
    }
    
    public boolean getNotModified() {
        return _notModified;
    }
    
    public String getContentType() {
        return _contentType;
    }
    
    /**
     *  The server response (200, etc).
     *  @return -1 if invalid, or if the proxy never responded,
     *  or if no proxy was used and the server never responded.
     *  If a non-proxied request partially succeeded (for example a redirect followed
     *  by a fail, or a partial fetch followed by a fail), this will
     *  be the last status code received.
     *  Note that fetch() may return false even if this returns 200.
     *
     *  @since 0.8.8
     */
    public int getStatusCode() {
        return _responseCode;
    }

    /**
     *  If called (before calling fetch()),
     *  data from the server or proxy will be written to the
     *  output file or stream even on an error response code (4xx, 5xx, etc).
     *  The error data will only be written if no previous data was written
     *  on an earlier try.
     *  Caller must of course check getStatusCode() or the
     *  fetch() return value.
     *
     *  @since 0.8.8
     */
    public void setWriteErrorToOutput() {
        _shouldWriteErrorToOutput = true;
    }

    /**
     *  Add an extra header to the request.
     *  Must be called before fetch().
     *
     *  @since 0.8.8
     */
    public void addHeader(String name, String value) {
        if (_extraHeaders == null)
            _extraHeaders = new ArrayList();
        _extraHeaders.add(name + ": " + value);
    }

    /**
     *  Add basic authorization header for the proxy.
     *  Only added if the request is going through a proxy.
     *  Must be called before fetch().
     *
     *  @since 0.8.9
     */
    public void addAuthorization(String userName, String password) {
        if (_shouldProxy)
            addHeader("Proxy-Authorization", 
                      "Basic " + Base64.encode((userName + ':' + password).getBytes(), true));  // true = use standard alphabet
    }

    /**
     *  Decompressor thread.
     *  Copied / modified from i2ptunnel HTTPResponseOutputStream (GPL)
     *
     *  @since 0.8.10
     */
    protected class Gunzipper implements Runnable {
        private final InputStream _inRaw;
        private final OutputStream _out;

        public Gunzipper(InputStream in, OutputStream out) {
            _inRaw = in;
            _out = out;
        }

        public void run() {
            ReusableGZIPInputStream in = null;
            long written = 0;
            try {
                in = ReusableGZIPInputStream.acquire();
                // blocking
                in.initialize(_inRaw);
                byte buf[] = new byte[8*1024];
                int read = -1;
                while ( (read = in.read(buf)) != -1) {
                    _out.write(buf, 0, read);
                }
            } catch (IOException ioe) {
                _decompressException = ioe;
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error decompressing: " + written + ", " + (in != null ? in.getTotalRead() + "/" + in.getTotalExpanded() : ""), ioe);
            } catch (OutOfMemoryError oom) {
                _decompressException = new IOException("OOM in HTTP Decompressor");
                _log.error("OOM in HTTP Decompressor", oom);
            } finally {
                if (_out != null) try { 
                    _out.close(); 
                } catch (IOException ioe) {}
                if (in != null)
                    ReusableGZIPInputStream.release(in);
            }
        }
    }
}
