/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Iterator;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataHelper;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Simple extension to the I2PTunnelServer that filters the HTTP
 * headers sent from the client to the server, replacing the Host
 * header with whatever this instance has been configured with.
 *
 */
public class I2PTunnelHTTPServer extends I2PTunnelServer {
    private final static Log _log = new Log(I2PTunnelHTTPServer.class);
    /** what Host: should we seem to be to the webserver? */
    private String _spoofHost;

    public I2PTunnelHTTPServer(InetAddress host, int port, String privData, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, l, notifyThis, tunnel);
        _spoofHost = spoofHost;
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpserver.blockingHandleTime", "how long the blocking handle takes to complete", "I2PTunnel.HTTPServer", new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, File privkey, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privkey, privkeyname, l, notifyThis, tunnel);
        _spoofHost = spoofHost;
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpserver.blockingHandleTime", "how long the blocking handle takes to complete", "I2PTunnel.HTTPServer", new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
    }

    public I2PTunnelHTTPServer(InetAddress host, int port, InputStream privData, String privkeyname, String spoofHost, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privData, privkeyname, l, notifyThis, tunnel);
        _spoofHost = spoofHost;        
        getTunnel().getContext().statManager().createRateStat("i2ptunnel.httpserver.blockingHandleTime", "how long the blocking handle takes to complete", "I2PTunnel.HTTPServer", new long[] { 60*1000, 10*60*1000, 3*60*60*1000 });
    }

    /**
     * Called by the thread pool of I2PSocket handlers
     *
     */
    protected void blockingHandle(I2PSocket socket) {
        long afterAccept = getTunnel().getContext().clock().now();
        long afterSocket = -1;
        //local is fast, so synchronously. Does not need that many
        //threads.
        try {
            socket.setReadTimeout(readTimeout);
            String modifiedHeader = getModifiedHeader(socket);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Modified header: [" + modifiedHeader + "]");

            Socket s = new Socket(remoteHost, remotePort);
            afterSocket = getTunnel().getContext().clock().now();
            new I2PTunnelRunner(s, socket, slock, null, modifiedHeader.getBytes(), null);
        } catch (SocketException ex) {
            try {
                socket.close();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error while closing the received i2p con", ex);
            }
        } catch (IOException ex) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error while receiving the new HTTP request", ex);
        }

        long afterHandle = getTunnel().getContext().clock().now();
        long timeToHandle = afterHandle - afterAccept;
        getTunnel().getContext().statManager().addRateData("i2ptunnel.httpserver.blockingHandleTime", timeToHandle, 0);
        if ( (timeToHandle > 1000) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Took a while to handle the request [" + timeToHandle + ", socket create: " + (afterSocket-afterAccept) + "]");
    }

    private String getModifiedHeader(I2PSocket handleSocket) throws IOException {
        InputStream in = handleSocket.getInputStream();

        StringBuffer command = new StringBuffer(128);
        Properties headers = readHeaders(in, command);
        headers.setProperty("Host", _spoofHost);
        headers.setProperty("Connection", "close");
        return formatHeaders(headers, command);
    }
    
    private String formatHeaders(Properties headers, StringBuffer command) {
        StringBuffer buf = new StringBuffer(command.length() + headers.size() * 64);
        buf.append(command.toString()).append('\n');
        for (Iterator iter = headers.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            String val  = headers.getProperty(name);
            buf.append(name).append(": ").append(val).append('\n');
        }
        buf.append('\n');
        return buf.toString();
    }
    
    private Properties readHeaders(InputStream in, StringBuffer command) throws IOException {
        Properties headers = new Properties();
        StringBuffer buf = new StringBuffer(128);
        
        boolean ok = DataHelper.readLine(in, command);
        if (!ok) throw new IOException("EOF reached while reading the HTTP command [" + command.toString() + "]");
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read the http command [" + command.toString() + "]");
        
        while (true) {
            buf.setLength(0);
            ok = DataHelper.readLine(in, buf);
            if (!ok) throw new IOException("EOF reached before the end of the headers [" + buf.toString() + "]");
            if ( (buf.length() <= 1) && ( (buf.charAt(0) == '\n') || (buf.charAt(0) == '\r') ) ) {
                // end of headers reached
                return headers;
            } else {
                int split = buf.indexOf(": ");
                if (split <= 0) throw new IOException("Invalid HTTP header, missing colon [" + buf.toString() + "]");
                String name = buf.substring(0, split);
                String value = buf.substring(split+2); // ": "
                headers.setProperty(name, value);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Read the header [" + name + "] = [" + value + "]");
            }
        }
    }
}

