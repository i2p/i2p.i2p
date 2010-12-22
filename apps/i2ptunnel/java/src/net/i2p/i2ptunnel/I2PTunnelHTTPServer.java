/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Iterator;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.data.Base32;

/**
 * Simple extension to the I2PTunnelServer that filters the HTTP
 * headers sent from the client to the server, replacing the Host
 * header with whatever this instance has been configured with, and
 * if the browser set Accept-encoding: x-i2p-gzip, gzip the http 
 * message body and set Content-encoding: x-i2p-gzip.
 *
 */
public class I2PTunnelHTTPServer extends I2PTunnelServer {
    private final static Log _log = new Log(I2PTunnelHTTPServer.class);
    /** what Host: should we seem to be to the webserver? */
    private String _spoofHost;
    private static final String HASH_HEADER = "X-I2P-DestHash";
    private static final String DEST64_HEADER = "X-I2P-DestB64";
    private static final String DEST32_HEADER = "X-I2P-DestB32";
    private static final String[] CLIENT_SKIPHEADERS = {HASH_HEADER, DEST64_HEADER, DEST32_HEADER};
    private static final String SERVER_HEADER = "Server";
    private static final String[] SERVER_SKIPHEADERS = {SERVER_HEADER};

    public I2PTunnelHTTPServer(InetAddress host, int port, String privData, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, File privkey, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privkey, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, InputStream privData, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, privkeyname, l, notifyThis, tunnel);
        setupI2PTunnelHTTPServer(spoofHost);
    }

    private void setupI2PTunnelHTTPServer(String spoofHost) {
        _spoofHost = spoofHost;
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpserver.blockingHandleTime", "how long the blocking handle takes to complete", "I2PTunnel.HTTPServer", new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpNullWorkaround", "How often an http server works around a streaming lib or i2ptunnel bug", "I2PTunnel.HTTPServer", new long[] { 60*1000, 10*60*1000 });
    }

    /**
     * Called by the thread pool of I2PSocket handlers
     *
     */
    @Override
    protected void blockingHandle(I2PSocket socket) {
        long afterAccept = getTunnel().getContext().clock().now();
        long afterSocket = -1;
        //local is fast, so synchronously. Does not need that many
        //threads.
        try {
            // give them 5 seconds to send in the HTTP request
            socket.setReadTimeout(5*1000);

            InputStream in = socket.getInputStream();

            StringBuilder command = new StringBuilder(128);
            Properties headers = readHeaders(in, command,
                CLIENT_SKIPHEADERS, getTunnel().getContext());
            headers.setProperty(HASH_HEADER, socket.getPeerDestination().calculateHash().toBase64());
            headers.setProperty(DEST32_HEADER, Base32.encode(socket.getPeerDestination().calculateHash().getData()) + ".b32.i2p" );
            headers.setProperty(DEST64_HEADER, socket.getPeerDestination().toBase64());

            if ( (_spoofHost != null) && (_spoofHost.trim().length() > 0) )
                headers.setProperty("Host", _spoofHost);
            headers.setProperty("Connection", "close");
            // we keep the enc sent by the browser before clobbering it, since it may have 
            // been x-i2p-gzip
            String enc = headers.getProperty("Accept-encoding");
            String altEnc = headers.getProperty("X-Accept-encoding");
            
            // according to rfc2616 s14.3, this *should* force identity, even if
            // "identity;q=1, *;q=0" didn't.  
            headers.setProperty("Accept-encoding", ""); 
            String modifiedHeader = formatHeaders(headers, command);
            
            //String modifiedHeader = getModifiedHeader(socket);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Modified header: [" + modifiedHeader + "]");

            socket.setReadTimeout(readTimeout);
            Socket s = new Socket(remoteHost, remotePort);
            afterSocket = getTunnel().getContext().clock().now();
            // instead of i2ptunnelrunner, use something that reads the HTTP 
            // request from the socket, modifies the headers, sends the request to the 
            // server, reads the response headers, rewriting to include Content-encoding: x-i2p-gzip
            // if it was one of the Accept-encoding: values, and gzip the payload       
            Properties opts = getTunnel().getClientOptions();
            boolean allowGZIP = true;
            if (opts != null) {
                String val = opts.getProperty("i2ptunnel.gzip");
                if ( (val != null) && (!Boolean.valueOf(val).booleanValue()) ) 
                    allowGZIP = false;
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("HTTP server encoding header: " + enc + "/" + altEnc);
            boolean useGZIP = ( (enc != null) && (enc.indexOf("x-i2p-gzip") >= 0) );
            if ( (!useGZIP) && (altEnc != null) && (altEnc.indexOf("x-i2p-gzip") >= 0) )
                useGZIP = true;
            
            if (allowGZIP && useGZIP) {
                I2PAppThread req = new I2PAppThread(
                    new CompressedRequestor(s, socket, modifiedHeader, getTunnel().getContext()),
                        Thread.currentThread().getName()+".hc");
                req.start();
            } else {
                new I2PTunnelRunner(s, socket, slock, null, modifiedHeader.getBytes(), null);
            }
        } catch (SocketException ex) {
            try {
                socket.close();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error while closing the received i2p con", ex);
            }
        } catch (IOException ex) {
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error while receiving the new HTTP request", ex);
        } catch (OutOfMemoryError oom) {
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.ERROR))
                _log.error("OOM in HTTP server", oom);
        }

        long afterHandle = getTunnel().getContext().clock().now();
        long timeToHandle = afterHandle - afterAccept;
        getTunnel().getContext().statManager().addRateData("i2ptunnel.httpserver.blockingHandleTime", timeToHandle, 0);
        if ( (timeToHandle > 1000) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Took a while to handle the request [" + timeToHandle + ", socket create: " + (afterSocket-afterAccept) + "]");
    }
    
    private static class CompressedRequestor implements Runnable {
        private Socket _webserver;
        private I2PSocket _browser;
        private String _headers;
        private I2PAppContext _ctx;
        public CompressedRequestor(Socket webserver, I2PSocket browser, String headers, I2PAppContext ctx) {
            _webserver = webserver;
            _browser = browser;
            _headers = headers;
            _ctx = ctx;
        }
        public void run() {
            if (_log.shouldLog(Log.INFO))
                _log.info("Compressed requestor running");
            OutputStream serverout = null;
            OutputStream browserout = null;
            InputStream browserin = null;
            InputStream serverin = null;
            try {
                serverout = _webserver.getOutputStream();
                
                if (_log.shouldLog(Log.INFO))
                    _log.info("request headers: " + _headers);
                serverout.write(_headers.getBytes());
                browserin = _browser.getInputStream();
                I2PAppThread sender = new I2PAppThread(new Sender(serverout, browserin, "server: browser to server"), Thread.currentThread().getName() + "hcs");
                sender.start();
                
                browserout = _browser.getOutputStream();
                // NPE seen here in 0.7-7, caused by addition of socket.close() in the
                // catch (IOException ioe) block above in blockingHandle() ???
                // CRIT  [ad-130280.hc] net.i2p.util.I2PThread        : Killing thread Thread-130280.hc
                // java.lang.NullPointerException
                //     at java.io.FileInputStream.<init>(FileInputStream.java:131)
                //     at java.net.SocketInputStream.<init>(SocketInputStream.java:44)
                //     at java.net.PlainSocketImpl.getInputStream(PlainSocketImpl.java:401)
                //     at java.net.Socket$2.run(Socket.java:779)
                //     at java.security.AccessController.doPrivileged(Native Method)
                //     at java.net.Socket.getInputStream(Socket.java:776)
                //     at net.i2p.i2ptunnel.I2PTunnelHTTPServer$CompressedRequestor.run(I2PTunnelHTTPServer.java:174)
                //     at java.lang.Thread.run(Thread.java:619)
                //     at net.i2p.util.I2PThread.run(I2PThread.java:71)
                try {
                    serverin = _webserver.getInputStream();
                } catch (NullPointerException npe) {
                    throw new IOException("getInputStream NPE");
                }
                CompressedResponseOutputStream compressedOut = new CompressedResponseOutputStream(browserout);

                //Change headers to protect server identity
                StringBuilder command = new StringBuilder(128);
                Properties headers = readHeaders(serverin, command,
                    SERVER_SKIPHEADERS, _ctx);
                String modifiedHeaders = formatHeaders(headers, command);
                compressedOut.write(modifiedHeaders.getBytes());

                Sender s = new Sender(compressedOut, serverin, "server: server to browser");
                if (_log.shouldLog(Log.INFO))
                    _log.info("Before pumping the compressed response");
                s.run(); // same thread
                if (_log.shouldLog(Log.INFO))
                    _log.info("After pumping the compressed response: " + compressedOut.getTotalRead() + "/" + compressedOut.getTotalCompressed());
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("error compressing", ioe);
            } finally {
                if (browserout != null) try { browserout.close(); } catch (IOException ioe) {}
                if (serverout != null) try { serverout.close(); } catch (IOException ioe) {}
                if (browserin != null) try { browserin.close(); } catch (IOException ioe) {}
                if (serverin != null) try { serverin.close(); } catch (IOException ioe) {}
            }
        }
    }

    private static class Sender implements Runnable {
        private OutputStream _out;
        private InputStream _in;
        private String _name;
        public Sender(OutputStream out, InputStream in, String name) {
            _out = out;
            _in = in;
            _name = name;
        }
        public void run() {
            if (_log.shouldLog(Log.INFO))
                _log.info(_name + ": Begin sending");
            try {
                byte buf[] = new byte[16*1024];
                int read = 0;
                int total = 0;
                while ( (read = _in.read(buf)) != -1) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(_name + ": read " + read + " and sending through the stream");
                    _out.write(buf, 0, read);
                    total += read;
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info(_name + ": Done sending: " + total);
                //_out.flush();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Error sending", ioe);
            } finally {
                if (_out != null) try { _out.close(); } catch (IOException ioe) {}
                if (_in != null) try { _in.close(); } catch (IOException ioe) {}
            }
        }
    }

    private static class CompressedResponseOutputStream extends HTTPResponseOutputStream {
        private InternalGZIPOutputStream _gzipOut;
        public CompressedResponseOutputStream(OutputStream o) {
            super(o);
        }
        
        @Override
        protected boolean shouldCompress() { return true; }
        @Override
        protected void finishHeaders() throws IOException {
            if (_log.shouldLog(Log.INFO))
                _log.info("Including x-i2p-gzip as the content encoding in the response");
            out.write("Content-encoding: x-i2p-gzip\r\n".getBytes());
            super.finishHeaders();
        }

        @Override
        protected void beginProcessing() throws IOException {
            if (_log.shouldLog(Log.INFO))
                _log.info("Beginning compression processing");
            //out.flush();
            _gzipOut = new InternalGZIPOutputStream(out);
            out = _gzipOut;
        }
        public long getTotalRead() { 
            InternalGZIPOutputStream gzipOut = _gzipOut;
            if (gzipOut != null)
                return gzipOut.getTotalRead();
            else
                return 0;
        }
        public long getTotalCompressed() { 
            InternalGZIPOutputStream gzipOut = _gzipOut;
            if (gzipOut != null)
                return gzipOut.getTotalCompressed();
            else
                return 0;
        }
    }

    /** just a wrapper to provide stats for debugging */
    private static class InternalGZIPOutputStream extends GZIPOutputStream {
        public InternalGZIPOutputStream(OutputStream target) throws IOException {
            super(target);
        }
        public long getTotalRead() { 
            try {
                return def.getTotalIn();
            } catch (Exception e) {
                // j2se 1.4.2_08 on linux is sometimes throwing an NPE in the getTotalIn() implementation
                return 0; 
            }
        }
        public long getTotalCompressed() { 
            try {
                return def.getTotalOut();
            } catch (Exception e) {
                // j2se 1.4.2_08 on linux is sometimes throwing an NPE in the getTotalOut() implementation
                return 0;
            }
        }
    }

    private static String formatHeaders(Properties headers, StringBuilder command) {
        StringBuilder buf = new StringBuilder(command.length() + headers.size() * 64);
        buf.append(command.toString().trim()).append("\r\n");
        for (Iterator iter = headers.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            String val  = headers.getProperty(name);
            buf.append(name.trim()).append(": ").append(val.trim()).append("\r\n");
        }
        buf.append("\r\n");
        return buf.toString();
    }
    
    /** ridiculously long, just to prevent OOM DOS @since 0.7.13 */
    private static final int MAX_HEADERS = 60;

    private static Properties readHeaders(InputStream in, StringBuilder command, String[] skipHeaders, I2PAppContext ctx) throws IOException {
        Properties headers = new Properties();
        StringBuilder buf = new StringBuilder(128);
        
        boolean ok = DataHelper.readLine(in, command);
        if (!ok) throw new IOException("EOF reached while reading the HTTP command [" + command.toString() + "]");
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read the http command [" + command.toString() + "]");
        
        // FIXME we probably don't need or want this in the outgoing direction
        int trimmed = 0;
        if (command.length() > 0) {
            for (int i = 0; i < command.length(); i++) {
                if (command.charAt(i) == 0) {
                    command = command.deleteCharAt(i);
                    i--;
                    trimmed++;
                }
            }
        }
        if (trimmed > 0)
            ctx.statManager().addRateData("i2ptunnel.httpNullWorkaround", trimmed, 0);
        
        int i = 0;
        while (true) {
            if (++i > MAX_HEADERS)
                throw new IOException("Too many header lines - max " + MAX_HEADERS);
            buf.setLength(0);
            ok = DataHelper.readLine(in, buf);
            if (!ok) throw new IOException("EOF reached before the end of the headers [" + buf.toString() + "]");
            if ( (buf.length() == 0) || 
                 ((buf.charAt(0) == '\n') || (buf.charAt(0) == '\r')) ) {
                // end of headers reached
                return headers;
            } else {
                int split = buf.indexOf(":");
                if (split <= 0) throw new IOException("Invalid HTTP header, missing colon [" + buf.toString() + "]");
                String name = buf.substring(0, split).trim();
                String value = null;
                if (buf.length() > split + 1)
                    value = buf.substring(split+1).trim(); // ":"
                else
                    value = "";

                if ("Accept-encoding".equalsIgnoreCase(name))
                    name = "Accept-encoding";
                else if ("X-Accept-encoding".equalsIgnoreCase(name))
                    name = "X-Accept-encoding";

                // For incoming, we remove certain headers to prevent spoofing.
                // For outgoing, we remove certain headers to improve anonymity.
                boolean skip = false;
                for (String skipHeader: skipHeaders) {
                    if (skipHeader.equalsIgnoreCase(name)) {
                        skip = true;
                        break;
                    }
                }
                if(skip) {
                    continue;
                }

                headers.setProperty(name, value);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Read the header [" + name + "] = [" + value + "]");
            }
        }
    }
}

