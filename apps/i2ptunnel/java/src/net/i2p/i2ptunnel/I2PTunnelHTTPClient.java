/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.Date;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Clock;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Act as a mini HTTP proxy, handling various different types of requests,
 * forwarding them through I2P appropriately, and displaying the reply.  Supported
 * request formats are: <pre>
 *   $method http://$site[$port]/$path $protocolVersion
 * or
 *   $method $path $protocolVersion\nHost: $site
 * or
 *   $method http://i2p/$site/$path $protocolVersion
 * or 
 *   $method /$site/$path $protocolVersion
 * </pre>
 *
 * If the $site resolves with the I2P naming service, then it is directed towards
 * that eepsite, otherwise it is directed towards this client's outproxy (typically
 * "squid.i2p").  Only HTTP is supported (no HTTPS, ftp, mailto, etc).  Both GET
 * and POST have been tested, though other $methods should work.
 *
 */
public class I2PTunnelHTTPClient extends I2PTunnelClientBase implements Runnable {
    private static final Log _log = new Log(I2PTunnelHTTPClient.class);

    private String wwwProxy;

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
         "Could not find the following Destination:<BR><BR>")
        .getBytes();
    
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

    /** used to assign unique IDs to the threads / clients.  no logic or functionality */
    private static volatile long __clientId = 0;

    /**
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelHTTPClient(int localPort, Logging l, boolean ownDest, 
                               String wwwProxy, EventDispatcher notifyThis, 
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, "HTTPHandler " + (++__clientId), tunnel);

        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openHTTPClientResult", "error");
            return;
        }

        this.wwwProxy = wwwProxy;

        setName(getLocalPort() + " -> HTTPClient [WWW outproxy: " + this.wwwProxy + "]");

        startRunning();

        notifyEvent("openHTTPClientResult", "ok");
    }

    private String getPrefix() { return "Client[" + _clientId + "]: "; }
    
    protected void clientConnectionRun(Socket s) {
        OutputStream out = null;
        String targetRequest = null;
        boolean usingWWWProxy = false;
        InactivityTimeoutThread timeoutThread = null;
        try {
            out = s.getOutputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String line, method = null, protocol = null, host = null, destination = null;
            StringBuffer newRequest = new StringBuffer();
            while ((line = br.readLine()) != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix() + "Line=[" + line + "]");
                
                if (line.startsWith("Connection: ") || 
                    line.startsWith("Keep-Alive: ") || 
                    line.startsWith("Proxy-Connection: "))
                    continue;
                
                if (method == null) { // first line (GET /base64/realaddr)
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(getPrefix() + "Method is null for [" + line + "]");
                    
                    int pos = line.indexOf(" ");
                    if (pos == -1) break;
                    method = line.substring(0, pos);
                    String request = line.substring(pos + 1);
                    if (request.startsWith("/") && getTunnel().getClientOptions().getProperty("i2ptunnel.noproxy") != null) {
                        request = "http://i2p" + request;
                    }
                    pos = request.indexOf("//");
                    if (pos == -1) {
                        method = null;
                        break;
                    }
                    protocol = request.substring(0, pos + 2);
                    request = request.substring(pos + 2);

                    targetRequest = request;

                    pos = request.indexOf("/");
                    if (pos == -1) {
                        method = null;
                        break;
                    }
                    host = request.substring(0, pos);

                    // Quick hack for foo.bar.i2p
                    if (host.toLowerCase().endsWith(".i2p")) {
                        destination = host;
                        host = getHostName(destination);
                        line = method + " " + request.substring(pos);
                    } else if (host.indexOf(".") != -1) {
                        // The request must be forwarded to a WWW proxy
                        destination = wwwProxy;
                        usingWWWProxy = true;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(getPrefix() + "Host doesnt end with .i2p and it contains a period [" + host + "]: wwwProxy!");
                    } else {
                        request = request.substring(pos + 1);
                        pos = request.indexOf("/");
                        destination = request.substring(0, pos);
                        line = method + " " + request.substring(pos);
                    }

                    boolean isValid = usingWWWProxy || isSupportedAddress(host, protocol);
                    if (!isValid) {
                        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "notValid(" + host + ")");
                        method = null;
                        destination = null;
                        break;
                    } else if (!usingWWWProxy) {
                        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "host=getHostName(" + destination + ")");
                        host = getHostName(destination); // hide original host
                    }

                    if (_log.shouldLog(Log.DEBUG)) {
                        _log.debug(getPrefix() + "METHOD:" + method + ":");
                        _log.debug(getPrefix() + "PROTOC:" + protocol + ":");
                        _log.debug(getPrefix() + "HOST  :" + host + ":");
                        _log.debug(getPrefix() + "DEST  :" + destination + ":");
                    }
                    
                } else {
                    if (line.startsWith("Host: ") && !usingWWWProxy) {
                        line = "Host: " + host;
                        if (_log.shouldLog(Log.INFO)) 
                            _log.info(getPrefix() + "Setting host = " + host);
                    }
                }
                
                if (line.length() == 0) {
                    newRequest.append("Connection: close\r\n\r\n");
                    break;
                } else {
                    newRequest.append(line).append("\r\n"); // HTTP spec
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix() + "NewRequest header: [" + newRequest.toString() + "]");
            
            while (br.ready()) { // empty the buffer (POST requests)
                int i = br.read();
                if (i != -1) {
                    newRequest.append((char) i);
                }
            }
            if (method == null || destination == null) {
                l.log("No HTTP method found in the request.");
                if (out != null) {
                    out.write(ERR_REQUEST_DENIED);
                    out.write("<p /><i>Generated on: ".getBytes());
                    out.write(new Date().toString().getBytes());
                    out.write("</i></body></html>\n".getBytes());
                    out.flush();
                }
                s.close();
                return;
            }
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix() + "Destination: " + destination);
            
            Destination dest = I2PTunnel.destFromName(destination);
            if (dest == null) {
                l.log("Could not resolve " + destination + ".");
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to resolve " + destination + " (proxy? " + usingWWWProxy + ", request: " + targetRequest);
                writeErrorMessage(ERR_DESTINATION_UNKNOWN, out, targetRequest, usingWWWProxy, destination);
                s.close();
                return;
            }
            String remoteID;
            I2PSocket i2ps = createI2PSocket(dest);
            byte[] data = newRequest.toString().getBytes("ISO-8859-1");
            I2PTunnelRunner runner = new I2PTunnelRunner(s, i2ps, sockLock, data);
            timeoutThread = new InactivityTimeoutThread(runner, out, targetRequest, usingWWWProxy, s);
            timeoutThread.start();
        } catch (SocketException ex) {
            if (timeoutThread != null) timeoutThread.disable();
            _log.info(getPrefix() + "Error trying to connect", ex);
            l.log(ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, wwwProxy);
            closeSocket(s);
        } catch (IOException ex) {
            if (timeoutThread != null) timeoutThread.disable();
            _log.info(getPrefix() + "Error trying to connect", ex);
            l.log(ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, wwwProxy);
            closeSocket(s);
        } catch (I2PException ex) {
            if (timeoutThread != null) timeoutThread.disable();
            _log.info("getPrefix() + Error trying to connect", ex);
            l.log(ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, wwwProxy);
            closeSocket(s);
        }
    }

    private static final long INACTIVITY_TIMEOUT = 120 * 1000;
    private static volatile long __timeoutId = 0;

    private class InactivityTimeoutThread extends I2PThread {

        private Socket s;
        private I2PTunnelRunner _runner;
        private OutputStream _out;
        private String _targetRequest;
        private boolean _useWWWProxy;
        private boolean _disabled;
        private Object _disableLock = new Object();

        public InactivityTimeoutThread(I2PTunnelRunner runner, OutputStream out, String targetRequest,
                                       boolean useWWWProxy, Socket s) {
            this.s = s;
            _runner = runner;
            _out = out;
            _targetRequest = targetRequest;
            _useWWWProxy = useWWWProxy;
            _disabled = false;
            long timeoutId = ++__timeoutId;
            setName("InactivityThread " + getPrefix() + timeoutId);
        }

        public void disable() {
            _disabled = true;
            synchronized (_disableLock) {
                _disableLock.notifyAll();
            }
        }

        public void run() {
            while (!_disabled) {
                if (_runner.isFinished()) {
                    if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "HTTP client request completed prior to timeout");
                    return;
                }
                if (_runner.getLastActivityOn() < Clock.getInstance().now() - INACTIVITY_TIMEOUT) {
                    if (_runner.getStartedOn() < Clock.getInstance().now() - INACTIVITY_TIMEOUT) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(getPrefix() + "HTTP client request timed out (lastActivity: "
                                      + new Date(_runner.getLastActivityOn()) + ", startedOn: "
                                      + new Date(_runner.getStartedOn()) + ")");
                        timeout();
                        return;
                    } else {
                        // runner hasn't been going to long enough
                    }
                } else {
                    // there has been activity in the period
                }
                synchronized (_disableLock) {
                    try {
                        _disableLock.wait(INACTIVITY_TIMEOUT);
                    } catch (InterruptedException ie) {
                    }
                }
            }
        }

        private void timeout() {
            _log.info(getPrefix() + "Inactivity timeout reached");
            l.log("Inactivity timeout reached");
            if (_out != null) {
                try {
                    if (_runner.getLastActivityOn() > 0) {
                        // some  data has been sent, so don't 404 it
                    } else {
                        writeErrorMessage(ERR_TIMEOUT, _out, _targetRequest, _useWWWProxy, wwwProxy);
                    }
                } catch (IOException ioe) {
                    _log.warn(getPrefix() + "Error writing out the 'timeout' message", ioe);
                }
            } else {
                _log.warn(getPrefix() + "Client disconnected before we could say we timed out");
            }
            closeSocket(s);
        }
    }

    private final static String getHostName(String host) {
        try {
            Destination dest = I2PTunnel.destFromName(host);
            if (dest == null) return "i2p";
            return dest.toBase64();
        } catch (DataFormatException dfe) {
            return "i2p";
        }
    }

    private static void writeErrorMessage(byte[] errMessage, OutputStream out, String targetRequest,
                                          boolean usingWWWProxy, String wwwProxy) throws IOException {
        if (out != null) {
            out.write(errMessage);
            if (targetRequest != null) {
                out.write(targetRequest.getBytes());
                if (usingWWWProxy) out.write(("<br>WWW proxy: " + wwwProxy).getBytes());
            }
            out.write("<p /><i>Generated on: ".getBytes());
            out.write(new Date().toString().getBytes());
            out.write("</i></body></html>\n".getBytes());
            out.flush();
        }
    }

    private void handleHTTPClientException(Exception ex, OutputStream out, String targetRequest,
                                                  boolean usingWWWProxy, String wwwProxy) {
                                                      
        if (_log.shouldLog(Log.WARN))
            _log.warn("Error sending to " + wwwProxy + " (proxy? " + usingWWWProxy + ", request: " + targetRequest, ex);
        if (out != null) {
            try {
                writeErrorMessage(ERR_DESTINATION_UNKNOWN, out, targetRequest, usingWWWProxy, wwwProxy);
            } catch (IOException ioe) {
                _log.warn(getPrefix() + "Error writing out the 'destination was unknown' " + "message", ioe);
            }
        } else {
            _log.warn(getPrefix() + "Client disconnected before we could say that destination " + "was unknown", ex);
        }
    }

    private final static String SUPPORTED_HOSTS[] = { "i2p", "www.i2p.com", "i2p."};

    private boolean isSupportedAddress(String host, String protocol) {
        if ((host == null) || (protocol == null)) return false;
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
                Destination d = I2PTunnel.destFromName(host);
                if (d == null) return false;
            } catch (DataFormatException dfe) {
            }
        }

        return protocol.equalsIgnoreCase("http://");
    }
}
