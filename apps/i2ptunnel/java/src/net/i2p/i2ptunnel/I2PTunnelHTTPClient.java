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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;
import java.util.HashMap;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
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

    private List proxyList;

    private HashMap addressHelpers = new HashMap();

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

    private final static byte[] ERR_NO_OUTPROXY =
        ("HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: No outproxy found</H1>"+
         "Your request was for a site outside of I2P, but you have no "+
         "HTTP outproxy configured.  Please configure an outproxy in I2PTunnel")
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

        proxyList = new ArrayList();
        if (wwwProxy != null) {
            StringTokenizer tok = new StringTokenizer(wwwProxy, ",");
            while (tok.hasMoreTokens())
                proxyList.add(tok.nextToken().trim());
        }

        setName(getLocalPort() + " -> HTTPClient [WWW outproxy list: " + wwwProxy + "]");

        startRunning();

        notifyEvent("openHTTPClientResult", "ok");
    }

    private String getPrefix(long requestId) { return "Client[" + _clientId + "/" + requestId + "]: "; }
    
    private String selectProxy() {
        synchronized (proxyList) {
            int size = proxyList.size();
            if (size <= 0) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Proxy list is empty - no outproxy available");
                l.log("Proxy list is emtpy - no outproxy available");
                return null;
            }
            int index = I2PAppContext.getGlobalContext().random().nextInt(size);
            String proxy = (String)proxyList.get(index);
            return proxy;
        }
    }

    private static final int DEFAULT_READ_TIMEOUT = 60*1000;
    
    
    /** 
     * create the default options (using the default timeout, etc)
     *
     */
    protected I2PSocketOptions getDefaultOptions() {
        Properties defaultOpts = getTunnel().getClientOptions();
        if (!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT))
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, ""+DEFAULT_READ_TIMEOUT);
        if (!defaultOpts.contains("i2p.streaming.inactivityTimeout"))
            defaultOpts.setProperty("i2p.streaming.inactivityTimeout", ""+DEFAULT_READ_TIMEOUT);
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        return opts;
    }
    
    /** 
     * create the default options (using the default timeout, etc)
     *
     */
    protected I2PSocketOptions getDefaultOptions(Properties overrides) {
        Properties defaultOpts = getTunnel().getClientOptions();
        defaultOpts.putAll(overrides);
        if (!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT))
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, ""+DEFAULT_READ_TIMEOUT);
        if (!defaultOpts.contains("i2p.streaming.inactivityTimeout"))
            defaultOpts.setProperty("i2p.streaming.inactivityTimeout", ""+DEFAULT_READ_TIMEOUT);
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        return opts;
    }

    private static long __requestId = 0;
    protected void clientConnectionRun(Socket s) {
        OutputStream out = null;
        String targetRequest = null;
        boolean usingWWWProxy = false;
        String currentProxy = null;
        long requestId = ++__requestId;
        try {
            out = s.getOutputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String line, method = null, protocol = null, host = null, destination = null;
            StringBuffer newRequest = new StringBuffer();
            while ((line = br.readLine()) != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix(requestId) + "Line=[" + line + "]");
                
                if (line.startsWith("Connection: ") || 
                    line.startsWith("Keep-Alive: ") || 
                    line.startsWith("Proxy-Connection: "))
                    continue;
                
                if (method == null) { // first line (GET /base64/realaddr)
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(getPrefix(requestId) + "Method is null for [" + line + "]");
                    
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
                        if ( (host != null) && ("i2p".equals(host)) ) {
                            int pos2;
                            if ((pos2 = request.indexOf("?")) != -1) {
                                // Try to find an address helper in the fragments
                                // and split the request into it's component parts for rebuilding later
                                String fragments = request.substring(pos2 + 1);
                                String uriPath = request.substring(0, pos2);
                                pos2 = fragments.indexOf(" ");
                                String protocolVersion = fragments.substring(pos2 + 1);
                                String urlEncoding = "";
                                fragments = fragments.substring(0, pos2);
                                fragments = fragments + "&";
                                String fragment;
                                while(fragments.length() > 0) {
                                    pos2 = fragments.indexOf("&");
                                    fragment = fragments.substring(0, pos2);
                                    fragments = fragments.substring(pos2 + 1);
                                    if (fragment.startsWith("i2paddresshelper")) {
                                        pos2 = fragment.indexOf("=");
                                        if (pos2 >= 0) {
                                            addressHelpers.put(destination,fragment.substring(pos2 + 1));
                                        }
                                    } else {
                                        // append each fragment unless it's the address helper
                                        if ("".equals(urlEncoding)) {
                                            urlEncoding = "?" + fragment;
                                        } else {
                                            urlEncoding = urlEncoding + "&" + fragment; 
                                        }
				    }
                                }
                                // reconstruct the request minus the i2paddresshelper GET var
                                request = uriPath + urlEncoding + " " + protocolVersion;
                            }

                            String addressHelper = (String) addressHelpers.get(destination);
                            if (addressHelper != null) {
                                destination = addressHelper;
                                host = getHostName(destination);
                            }
                        }
                        line = method + " " + request.substring(pos);
                    } else if (host.indexOf(".") != -1) {
                        // The request must be forwarded to a WWW proxy
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Before selecting outproxy for " + host);
                        currentProxy = selectProxy();
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("After selecting outproxy for " + host + ": " + currentProxy);
                        if (currentProxy == null) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn(getPrefix(requestId) + "Host wants to be outproxied, but we dont have any!");
                            l.log("No HTTP outproxy found for the request.");
                            if (out != null) {
                                out.write(ERR_NO_OUTPROXY);
                                out.write("<p /><i>Generated on: ".getBytes());
                                out.write(new Date().toString().getBytes());
                                out.write("</i></body></html>\n".getBytes());
                                out.flush();
                            }
                            s.close();
                            return;
                        }
                        destination = currentProxy;
                        usingWWWProxy = true;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(getPrefix(requestId) + "Host doesnt end with .i2p and it contains a period [" + host + "]: wwwProxy!");
                    } else {
                        request = request.substring(pos + 1);
                        pos = request.indexOf("/");
                        destination = request.substring(0, pos);
                        line = method + " " + request.substring(pos);
                    }

                    boolean isValid = usingWWWProxy || isSupportedAddress(host, protocol);
                    if (!isValid) {
                        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix(requestId) + "notValid(" + host + ")");
                        method = null;
                        destination = null;
                        break;
                    } else if (!usingWWWProxy) {
                        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix(requestId) + "host=getHostName(" + destination + ")");
                        host = getHostName(destination); // hide original host
                    }

                    if (_log.shouldLog(Log.DEBUG)) {
                        _log.debug(getPrefix(requestId) + "METHOD:" + method + ":");
                        _log.debug(getPrefix(requestId) + "PROTOC:" + protocol + ":");
                        _log.debug(getPrefix(requestId) + "HOST  :" + host + ":");
                        _log.debug(getPrefix(requestId) + "DEST  :" + destination + ":");
                    }
                    
                } else {
                    if (line.startsWith("Host: ") && !usingWWWProxy) {
                        line = "Host: " + host;
                        if (_log.shouldLog(Log.INFO)) 
                            _log.info(getPrefix(requestId) + "Setting host = " + host);
                    } else if (line.startsWith("User-Agent: ")) {
                        // always stripped, added back at the end
                        line = null;
                        continue;
                    } else if (line.startsWith("Accept")) {
                        // strip the accept-blah headers, as they vary dramatically from
                        // browser to browser
                        line = null;
                        continue;
                    } else if (line.startsWith("Referer: ")) {
                        // Shouldn't we be more specific, like accepting in-site referers ?
                        //line = "Referer: i2p";
                        line = null;
                        continue; // completely strip the line
                    } else if (line.startsWith("Via: ")) {
                        //line = "Via: i2p";
                        line = null;
                        continue; // completely strip the line
                    } else if (line.startsWith("From: ")) {
                        //line = "From: i2p";
                        line = null;
                        continue; // completely strip the line
                    }
                }
                
                if (line.length() == 0) {
                    newRequest.append("User-Agent: MYOB/6.66 (AN/ON)\r\n");
                    newRequest.append("Connection: close\r\n\r\n");
                    break;
                } else {
                    newRequest.append(line).append("\r\n"); // HTTP spec
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix(requestId) + "NewRequest header: [" + newRequest.toString() + "]");
            
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
                _log.debug(getPrefix(requestId) + "Destination: " + destination);
            
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
            
            Properties opts = new Properties();
            opts.setProperty("i2p.streaming.inactivityTimeout", ""+120*1000);
            // 1 == disconnect.  see ConnectionOptions in the new streaming lib, which i
            // dont want to hard link to here
            opts.setProperty("i2p.streaming.inactivityTimeoutAction", ""+1);
            I2PSocket i2ps = createI2PSocket(dest, getDefaultOptions(opts));
            byte[] data = newRequest.toString().getBytes("ISO-8859-1");
            I2PTunnelRunner runner = new I2PTunnelRunner(s, i2ps, sockLock, data, mySockets);
        } catch (SocketException ex) {
            _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            l.log(ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (IOException ex) {
            _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            l.log(ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        } catch (I2PException ex) {
            _log.info("getPrefix(requestId) + Error trying to connect", ex);
            l.log(ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        }
    }

    private final static String getHostName(String host) {
        if (host == null) return null;
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
                                                  boolean usingWWWProxy, String wwwProxy, long requestId) {
                                                      
        if (_log.shouldLog(Log.WARN))
            _log.warn(getPrefix(requestId) + "Error sending to " + wwwProxy + " (proxy? " + usingWWWProxy + ", request: " + targetRequest, ex);
        if (out != null) {
            try {
                writeErrorMessage(ERR_DESTINATION_UNKNOWN, out, targetRequest, usingWWWProxy, wwwProxy);
            } catch (IOException ioe) {
                _log.warn(getPrefix(requestId) + "Error writing out the 'destination was unknown' " + "message", ioe);
            }
        } else {
            _log.warn(getPrefix(requestId) + "Client disconnected before we could say that destination " + "was unknown", ex);
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
