package net.i2p.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

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
    private I2PAppContext _context;
    protected Log _log;
    protected boolean _shouldProxy;
    private String _proxyHost;
    private int _proxyPort;
    protected int _numRetries;
    private long _minSize; // minimum and maximum acceptable response size, -1 signifies unlimited,
    private long _maxSize; // applied both against whole responses and chunks
    private String _outputFile;
    private OutputStream _outputStream;
    /** url we were asked to fetch */
    protected String _url;
    /** the URL we actually fetch from (may differ from the _url in case of redirect) */
    protected String _actualURL;
    private String _postData;
    private boolean _allowCaching;
    protected List _listeners;
    
    private boolean _keepFetching;
    private Socket _proxy;
    private OutputStream _proxyOut;
    private InputStream _proxyIn;
    protected OutputStream _out;
    private long _alreadyTransferred;
    private long _bytesTransferred;
    protected long _bytesRemaining;
    protected int _currentAttempt;
    private String _etag;
    private String _lastModified;
    private boolean _encodingChunked;
    private boolean _notModified;
    private String _contentType;
    protected boolean _transferFailed;
    protected boolean _headersRead;
    protected boolean _aborted;
    private long _fetchHeaderTimeout;
    private long _fetchEndTime;
    protected long _fetchInactivityTimeout;
    protected int _redirects;
    protected String _redirectLocation;
    
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
        _log = ctx.logManager().getLog(EepGet.class);
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
        _alreadyTransferred = 0;
        _bytesTransferred = 0;
        _bytesRemaining = -1;
        _currentAttempt = 0;
        _transferFailed = false;
        _headersRead = false;
        _aborted = false;
        _fetchHeaderTimeout = 45*1000;
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
        int inactivityTimeout = 60*1000;
        String etag = null;
        String saveAs = null;
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
                } else if (args[i].equals("-e")) {
                    etag = "\"" + args[i+1] + "\"";
                    i++;
                } else if (args[i].equals("-o")) {
                    saveAs = args[i+1];
                    i++;
                } else if (args[i].equals("-m")) {
                    markSize = Integer.parseInt(args[i+1]);
                    lineLen = Integer.parseInt(args[i+2]);
                    i += 2;
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
        get.addStatusListener(get.new CLIStatusListener(markSize, lineLen));
        get.fetch(45*1000, -1, inactivityTimeout);
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
    
    private static final String _safeChars = "abcdefghijklmnopqrstuvwxyz" +
                                             "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                                             "01234567890.,_=@#:";
    private static String sanitize(String name) {
        name = name.replace('/', '_');
        StringBuilder buf = new StringBuilder(name);
        for (int i = 0; i < name.length(); i++)
            if (_safeChars.indexOf(buf.charAt(i)) == -1)
                buf.setCharAt(i, '_');
        return buf.toString();
    }
    
    protected static void usage() {
        System.err.println("EepGet [-p 127.0.0.1:4444] [-n #retries] [-o outputFile] [-m markSize lineLen] [-t timeout] url");
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
        public void headerReceived(String url, int currentAttempt, String key, String val);
        public void attempting(String url);
    }
    private class CLIStatusListener implements StatusListener {
        private int _markSize;
        private int _lineSize;
        private long _startedOn;
        private long _written;
        private long _previousWritten;
        private long _discarded;
        private long _lastComplete;
        private boolean _firstTime;
        private DecimalFormat _pct = new DecimalFormat("00.0%");
        private DecimalFormat _kbps = new DecimalFormat("###,000.00");
        public CLIStatusListener() { 
            this(1024, 40);
        }
        public CLIStatusListener(int markSize, int lineSize) { 
            _markSize = markSize;
            _lineSize = lineSize;
            _written = 0;
            _previousWritten = 0;
            _discarded = 0;
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
                            buf.append(" ");
                            if ( bytesRemaining > 0 ) {
                                double pct = ((double)_written + _previousWritten) /
                                             ((double)alreadyTransferred + (double)currentWrite + (double)bytesRemaining);
                                synchronized (_pct) {
                                    buf.append(_pct.format(pct));
                                }
                                buf.append(": ");
                            }
                            buf.append(_written);
                            buf.append(" @ ");
                            double lineKBytes = ((double)_markSize * (double)_lineSize)/1024.0d;
                            double kbps = lineKBytes/((double)timeToSend/1000.0d);
                            synchronized (_kbps) {
                                buf.append(_kbps.format(kbps));
                            }
                            buf.append("KBps");
                            
                            buf.append(" / ");
                            long lifetime = _context.clock().now() - _startedOn;
                            double lifetimeKBps = (1000.0d*(double)(_written)/((double)lifetime*1024.0d));
                            synchronized (_kbps) {
                                buf.append(_kbps.format(lifetimeKBps));
                            }
                            buf.append("KBps");
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
                double kbps = (1000.0d*(double)(transferred)/((double)timeToSend*1024.0d));
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
            double kbps = (timeToSend > 0 ? (1000.0d*(double)(bytesTransferred)/((double)timeToSend*1024.0d)) : 0);
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
     * Blocking fetch, returning true if the URL was retrieved, false if all retries failed
     *
     */
    public boolean fetch() { return fetch(_fetchHeaderTimeout); }
    /**
     * Blocking fetch, timing out individual attempts if the HTTP response headers
     * don't come back in the time given.  If the timeout is zero or less, this will
     * wait indefinitely.
     */
    public boolean fetch(long fetchHeaderTimeout) {
        return fetch(fetchHeaderTimeout, -1, -1);
    }
    public boolean fetch(long fetchHeaderTimeout, long totalTimeout, long inactivityTimeout) {
        _fetchHeaderTimeout = fetchHeaderTimeout;
        _fetchEndTime = (totalTimeout > 0 ? System.currentTimeMillis() + totalTimeout : -1);
        _fetchInactivityTimeout = inactivityTimeout;
        _keepFetching = true;

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fetching (proxied? " + _shouldProxy + ") url=" + _actualURL);
        while (_keepFetching) {
            SocketTimeout timeout = null;
            if (_fetchHeaderTimeout > 0)
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
            try {
                for (int i = 0; i < _listeners.size(); i++) 
                    ((StatusListener)_listeners.get(i)).attempting(_url);
                sendRequest(timeout);
                timeout.resetTimer();
                doFetch(timeout);
                timeout.cancel();
                if (!_transferFailed)
                    return true;
                break;
            } catch (IOException ioe) {
                timeout.cancel();
                for (int i = 0; i < _listeners.size(); i++) 
                    ((StatusListener)_listeners.get(i)).attemptFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt, _numRetries, ioe);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("ERR: doFetch failed " +  ioe);
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
            if (_currentAttempt > _numRetries || !_keepFetching) 
                break;
            try { 
                long delay = _context.random().nextInt(60*1000);
                Thread.sleep(5*1000+delay); 
            } catch (InterruptedException ie) {}
        }

        for (int i = 0; i < _listeners.size(); i++) 
            ((StatusListener)_listeners.get(i)).transferFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt);
        if (_log.shouldLog(Log.WARN))
            _log.warn("All attempts failed for " + _url);
        return false;
    }

    /** return true if the URL was completely retrieved */
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
        
        if (_redirectLocation != null) {
            try {
                URL oldURL = new URL(_actualURL);
                String query = oldURL.getQuery();
                if (query == null) query = "";
                if (_redirectLocation.startsWith("http://")) {
                    if ( (_redirectLocation.indexOf('?') < 0) && (query.length() > 0) )
                        _actualURL = _redirectLocation + "?" + query;
                    else
                        _actualURL = _redirectLocation;
                } else { 
                    URL url = new URL(_actualURL);
		    if (_redirectLocation.startsWith("/"))
                        _actualURL = "http://" + url.getHost() + ":" + url.getPort() + _redirectLocation;
                    else
                        _actualURL = "http://" + url.getHost() + ":" + url.getPort() + "/" + _redirectLocation;
                    if ( (_actualURL.indexOf('?') < 0) && (query.length() > 0) )
                        _actualURL = _actualURL + "?" + query;
                }
            } catch (MalformedURLException mue) {
                throw new IOException("Redirected from an invalid URL");
            }
            _redirects++;
            if (_redirects > 5)
                throw new IOException("Too many redirects: to " + _redirectLocation);
            if (_log.shouldLog(Log.INFO)) _log.info("Redirecting to " + _redirectLocation);
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

        int remaining = (int)_bytesRemaining;
        byte buf[] = new byte[1024];
        while (_keepFetching && ( (remaining > 0) || !strictSize ) && !_aborted) {
            int toRead = buf.length;
            if (strictSize && toRead > remaining)
                toRead = remaining;
            int read = _proxyIn.read(buf, 0, toRead);
            if (read == -1)
                break;
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
            timeout.resetTimer();
            if (_bytesRemaining >= read) // else chunked?
                _bytesRemaining -= read;
            if (read > 0) {
                for (int i = 0; i < _listeners.size(); i++) 
                    ((StatusListener)_listeners.get(i)).bytesTransferred(
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
        
        if (_aborted)
            throw new IOException("Timed out reading the HTTP data");
        
        timeout.cancel();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Done transferring " + _bytesTransferred + " (ok? " + !_transferFailed + ")");


        if (_transferFailed) {
            // 404, etc - transferFailed is called after all attempts fail, by fetch() above
            for (int i = 0; i < _listeners.size(); i++) 
                ((StatusListener)_listeners.get(i)).attemptFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt, _numRetries, new Exception("Attempt failed"));
        } else if ((_minSize > 0) && (_alreadyTransferred < _minSize)) {
            throw new IOException("Bytes transferred " + _alreadyTransferred + " violates minimum of " + _minSize + " bytes");
        } else if ( (_bytesRemaining == -1) || (remaining == 0) ) {
            for (int i = 0; i < _listeners.size(); i++) 
                ((StatusListener)_listeners.get(i)).transferComplete(
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
        int responseCode = handleStatus(buf.toString());
        boolean redirect = false;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("rc: " + responseCode + " for " + _actualURL);
        boolean rcOk = false;
        switch (responseCode) {
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
                _keepFetching = false;
                _transferFailed = true;
                // maybe we should throw instead of return to get the return code back to the user
                return;
            case 416: // completed (or range out of reach)
                _bytesRemaining = 0;
                _keepFetching = false;
                return;
            case 504: // gateway timeout
                // throw out of doFetch() to fetch() and try again
                throw new IOException("HTTP Proxy timeout");
            default:
                rcOk = false;
                _keepFetching = false;
                _transferFailed = true;
        }

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
                            throw new IOException("Invalid HTTP response code: " + responseCode);
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
    
    private long readChunkLength() throws IOException {
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
        for (int i = 0; i < _listeners.size(); i++) 
            ((StatusListener)_listeners.get(i)).headerReceived(_url, _currentAttempt, key.trim(), val.trim());
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Header line: [" + key + "] = [" + val + "]");
        if (key.equalsIgnoreCase("Content-length")) {
            try {
                _bytesRemaining = Long.parseLong(val.trim());
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
            }
        } else if (key.equalsIgnoreCase("ETag")) {
            _etag = val.trim();
        } else if (key.equalsIgnoreCase("Last-Modified")) {
            _lastModified = val.trim();
        } else if (key.equalsIgnoreCase("Transfer-encoding")) {
            if (val.indexOf("chunked") != -1)
                _encodingChunked = true;
        } else if (key.equalsIgnoreCase("Content-Type")) {
            _contentType=val;
        } else if (key.equalsIgnoreCase("Location")) {
            _redirectLocation=val.trim();
        } else {
            // ignore the rest
        }
    }

    private void increment(byte[] lookahead, int cur) {
        lookahead[0] = lookahead[1];
        lookahead[1] = lookahead[2];
        lookahead[2] = (byte)cur;
    }
    private boolean isEndOfHeaders(byte lookahead[]) {
        byte first = lookahead[0];
        byte second = lookahead[1];
        byte third = lookahead[2];
        return (isNL(second) && isNL(third)) || //   \n\n
               (isNL(first) && isNL(third));    // \n\r\n
    }

    /** we ignore any potential \r, since we trim it on write anyway */
    private static final byte NL = '\n';
    private boolean isNL(byte b) { return (b == NL); }

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
            _proxy = new Socket(_proxyHost, _proxyPort);
        } else {
            try {
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
            } catch (MalformedURLException mue) {
                throw new IOException("Request URL is invalid");
            }
        }
        _proxyIn = _proxy.getInputStream();
        _proxyOut = _proxy.getOutputStream();
        
        timeout.setSocket(_proxy);
        
        _proxyOut.write(DataHelper.getUTF8(req));
        _proxyOut.flush();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request flushed");
    }
    
    protected String getRequest() throws IOException {
        StringBuilder buf = new StringBuilder(512);
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
            path = path + "?" + query;
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
        buf.append("Accept-Encoding: \r\n");
        if (_shouldProxy)
            buf.append("X-Accept-Encoding: x-i2p-gzip;q=1.0, identity;q=0.5, deflate;q=0, gzip;q=0, *;q=0\r\n");
        if (!_allowCaching) {
            buf.append("Cache-control: no-cache\r\n");
            buf.append("Pragma: no-cache\r\n");
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
        buf.append("Connection: close\r\n\r\n");
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
}
