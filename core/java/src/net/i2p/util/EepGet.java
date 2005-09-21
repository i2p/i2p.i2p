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
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * EepGet [-p localhost:4444] 
 *        [-n #retries] 
 *        [-o outputFile] 
 *        [-m markSize lineLen]
 *        url
 */
public class EepGet {
    private I2PAppContext _context;
    private Log _log;
    private boolean _shouldProxy;
    private String _proxyHost;
    private int _proxyPort;
    private int _numRetries;
    private String _outputFile;
    private String _url;
    private String _postData;
    private boolean _allowCaching;
    private List _listeners;
    
    private boolean _keepFetching;
    private Socket _proxy;
    private OutputStream _proxyOut;
    private InputStream _proxyIn;
    private OutputStream _out;
    private long _alreadyTransferred;
    private long _bytesTransferred;
    private long _bytesRemaining;
    private int _currentAttempt;
    private String _etag;
    private boolean _encodingChunked;
    
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
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, outputFile, url, true, null, postData);
    }
    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, boolean allowCaching, String etag) {
        this(ctx, shouldProxy, proxyHost, proxyPort, numRetries, outputFile, url, allowCaching, etag, null);
    }
    public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, String outputFile, String url, boolean allowCaching, String etag, String postData) {
        _context = ctx;
        _log = ctx.logManager().getLog(EepGet.class);
        _shouldProxy = shouldProxy;
        _proxyHost = proxyHost;
        _proxyPort = proxyPort;
        _numRetries = numRetries;
        _outputFile = outputFile;
        _url = url;
        _postData = postData;
        _alreadyTransferred = 0;
        _bytesTransferred = 0;
        _bytesRemaining = -1;
        _currentAttempt = 0;
        _listeners = new ArrayList(1);
        _etag = etag;
    }
   
    /**
     * EepGet [-p localhost:4444] [-n #retries] [-e etag] [-o outputFile] [-m markSize lineLen] url
     *
     */ 
    public static void main(String args[]) {
        String proxyHost = "localhost";
        int proxyPort = 4444;
        int numRetries = 5;
        int markSize = 1024;
        int lineLen = 40;
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
        get.fetch();
    }
    
    public static String suggestName(String url) {
        String name = null;
        if (url.lastIndexOf('/') >= 0)
            name = sanitize(url.substring(url.lastIndexOf('/')+1));
        if (name != null) 
            return name;
        else
            return sanitize(url);
    }
    
    private static final String _safeChars = "abcdefghijklmnopqrstuvwxyz" +
                                             "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                                             "01234567890.,_=@#:";
    private static String sanitize(String name) {
        name = name.replace('/', '_');
        StringBuffer buf = new StringBuffer(name);
        for (int i = 0; i < name.length(); i++)
            if (_safeChars.indexOf(buf.charAt(i)) == -1)
                buf.setCharAt(i, '_');
        return buf.toString();
    }
    
    private static void usage() {
        System.err.println("EepGet [-p localhost:4444] [-n #retries] [-o outputFile] [-m markSize lineLen] url");
    }
    
    public static interface StatusListener {
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url);
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile);
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause);
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt);
        public void headerReceived(String url, int currentAttempt, String key, String val);
    }
    private class CLIStatusListener implements StatusListener {
        private int _markSize;
        private int _lineSize;
        private long _startedOn;
        private long _written;
        private long _lastComplete;
        private DecimalFormat _pct = new DecimalFormat("00.0%");
        private DecimalFormat _kbps = new DecimalFormat("###,000.00");
        public CLIStatusListener() { 
            this(1024, 40);
        }
        public CLIStatusListener(int markSize, int lineSize) { 
            _markSize = markSize;
            _lineSize = lineSize;
            _written = 0;
            _lastComplete = _context.clock().now();
            _startedOn = _lastComplete;
        }
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
            for (int i = 0; i < currentWrite; i++) {
                _written++;
                if ( (_markSize > 0) && (_written % _markSize == 0) ) {
                    System.out.print("#");
                    
                    if ( (_lineSize > 0) && (_written % ((long)_markSize*(long)_lineSize) == 0l) ) {
                        long now = _context.clock().now();
                        long timeToSend = now - _lastComplete;
                        if (timeToSend > 0) {
                            StringBuffer buf = new StringBuffer(50);
                            buf.append(" ");
                            double pct = ((double)alreadyTransferred + (double)_written) / ((double)alreadyTransferred + (double)bytesRemaining);
                            synchronized (_pct) {
                                buf.append(_pct.format(pct));
                            }
                            buf.append(": ");
                            buf.append(_written+alreadyTransferred);
                            buf.append(" @ ");
                            double lineKBytes = ((double)_markSize * (double)_lineSize)/1024.0d;
                            double kbps = lineKBytes/((double)timeToSend/1000.0d);
                            synchronized (_kbps) {
                                buf.append(_kbps.format(kbps));
                            }
                            buf.append("KBps");
                            
                            buf.append(" / ");
                            long lifetime = _context.clock().now() - _startedOn;
                            double lifetimeKBps = (1000.0d*(double)(_written+alreadyTransferred)/((double)lifetime*1024.0d));
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
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile) {
            System.out.println();
            System.out.println("== " + new Date());
            System.out.println("== Transfer of " + url + " completed with " + (alreadyTransferred+bytesTransferred)
                               + " and " + (bytesRemaining - bytesTransferred) + " remaining");
            System.out.println("== Output saved to " + outputFile);
            long timeToSend = _context.clock().now() - _startedOn;
            System.out.println("== Transfer time: " + DataHelper.formatDuration(timeToSend));
            System.out.println("== ETag: " + _etag);            
            StringBuffer buf = new StringBuffer(50);
            buf.append("== Transfer rate: ");
            double kbps = (1000.0d*(double)(_written)/((double)timeToSend*1024.0d));
            synchronized (_kbps) {
                buf.append(_kbps.format(kbps));
            }
            buf.append("KBps");
            System.out.println(buf.toString());
        }
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            System.out.println();
            System.out.println("** " + new Date());
            System.out.println("** Attempt " + currentAttempt + " of " + url + " failed");
            System.out.println("** Transfered " + bytesTransferred
                               + " with " + (bytesRemaining < 0 ? "unknown" : ""+bytesRemaining) + " remaining");
            System.out.println("** " + cause.getMessage());
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
            StringBuffer buf = new StringBuffer(50);
            buf.append("== Transfer rate: ");
            synchronized (_kbps) {
                buf.append(_kbps.format(kbps));
            }
            buf.append("KBps");
            System.out.println(buf.toString());
        }
        public void headerReceived(String url, int currentAttempt, String key, String val) {}
    }
    
    public void addStatusListener(StatusListener lsnr) {
        synchronized (_listeners) { _listeners.add(lsnr); }
    }
    
    public void stopFetching() { _keepFetching = false; }
    public void fetch() { 
        _keepFetching = true;

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fetching (proxied? " + _shouldProxy + ") url=" + _url);
        while (_keepFetching) {
            try {
                sendRequest();
                doFetch();
                return;
            } catch (IOException ioe) {
                for (int i = 0; i < _listeners.size(); i++) 
                    ((StatusListener)_listeners.get(i)).attemptFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt, _numRetries, ioe);
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
            if (_currentAttempt > _numRetries) 
                break;
            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        }

        for (int i = 0; i < _listeners.size(); i++) 
            ((StatusListener)_listeners.get(i)).transferFailed(_url, _bytesTransferred, _bytesRemaining, _currentAttempt);
    }

    /** return true if the URL was completely retrieved */
    private void doFetch() throws IOException {
        readHeaders();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Headers read completely, reading " + _bytesRemaining);
        
        boolean strictSize = (_bytesRemaining >= 0);
            
        int remaining = (int)_bytesRemaining;
        byte buf[] = new byte[1024];
        while (_keepFetching && ( (remaining > 0) || !strictSize )) {
            int toRead = buf.length;
            if (strictSize && toRead > remaining)
                toRead = remaining;
            int read = _proxyIn.read(buf, 0, toRead);
            if (read == -1)
                break;
            _out.write(buf, 0, read);
            _bytesTransferred += read;
            remaining -= read;
            if (read > 0) 
                for (int i = 0; i < _listeners.size(); i++) 
                    ((StatusListener)_listeners.get(i)).bytesTransferred(_alreadyTransferred, read, _bytesTransferred, _bytesRemaining, _url);
        }

        if (_out != null)
            _out.close();
        _out = null;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Done transferring " + _bytesTransferred);

        if ( (_bytesRemaining == -1) || (remaining == 0) ){
            for (int i = 0; i < _listeners.size(); i++) 
                ((StatusListener)_listeners.get(i)).transferComplete(_alreadyTransferred, _bytesTransferred, _bytesRemaining, _url, _outputFile);
        } else {
            throw new IOException("Disconnection on attempt " + _currentAttempt + " after " + _bytesTransferred);
        }
    }

    private void readHeaders() throws IOException {
        String key = null;
        StringBuffer buf = new StringBuffer(32);

        boolean read = DataHelper.readLine(_proxyIn, buf);
        if (!read) throw new IOException("Unable to read the first line");
        int responseCode = handleStatus(buf.toString());

        boolean rcOk = false;
        switch (responseCode) {
            case 200: // full
                _out = new FileOutputStream(_outputFile, false);
                rcOk = true;
                break;
            case 206: // partial
                _out = new FileOutputStream(_outputFile, true);
                rcOk = true;
                break;
            case 304: // not modified
                _bytesRemaining = 0;
                _keepFetching = false;
                return;              
            case 416: // completed (or range out of reach)
                _bytesRemaining = 0;
                _keepFetching = false;
                return;
            default:
                rcOk = false;
        }

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
                            readChunkLength();
                        }
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
    
    private void readChunkLength() throws IOException {
        StringBuffer buf = new StringBuffer(8);
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
            _bytesRemaining = bytes;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Chunked length: " + bytes);
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
            System.err.println("ERR: status "+  line);
            return -1;
        }
        String protocol = tok.nextToken(); // ignored
        if (!tok.hasMoreTokens()) {
            System.err.println("ERR: status "+  line);
            return -1;
        }
        String rc = tok.nextToken();
        try {
            return Integer.parseInt(rc); 
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
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
        } else if (key.equalsIgnoreCase("Transfer-encoding")) {
            if (val.indexOf("chunked") != -1)
                _encodingChunked = true;
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

    private void sendRequest() throws IOException {
        File outFile = new File(_outputFile);
        if (outFile.exists())
            _alreadyTransferred = outFile.length();

        String req = getRequest();

        if (_shouldProxy) {
            _proxy = new Socket(_proxyHost, _proxyPort);
        } else {
            try {
                URL url = new URL(_url);
                String host = url.getHost();
                int port = url.getPort();
                if (port == -1)
                    port = 80;
                _proxy = new Socket(host, port);
            } catch (MalformedURLException mue) {
                throw new IOException("Request URL is invalid");
            }
        }
        _proxyIn = _proxy.getInputStream();
        _proxyOut = _proxy.getOutputStream();

        _proxyOut.write(req.toString().getBytes());
        _proxyOut.flush();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Request flushed");
    }
    
    private String getRequest() {
        StringBuffer buf = new StringBuffer(512);
        boolean post = false;
        if ( (_postData != null) && (_postData.length() > 0) )
            post = true;
        if (post) {
            buf.append("POST ").append(_url).append(" HTTP/1.1\r\n");
        } else {
            buf.append("GET ").append(_url).append(" HTTP/1.1\r\n");
        }
        try {
            URL url = new URL(_url);
            buf.append("Host: ").append(url.getHost()).append("\r\n");
        } catch (MalformedURLException mue) {
            mue.printStackTrace();
        }
        if (_alreadyTransferred > 0) {
            buf.append("Range: bytes=");
            buf.append(_alreadyTransferred);
            buf.append("-\r\n");
        }
        buf.append("Accept-Encoding: identity;q=1, *;q=0\r\n");
        if (!_allowCaching) {
            buf.append("Cache-control: no-cache\r\n");
            buf.append("Pragma: no-cache\r\n");
        }
        if (_etag != null) {
            buf.append("If-None-Match: ");
            buf.append(_etag);
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

}
