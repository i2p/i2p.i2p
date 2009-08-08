/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.FileUtil;
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

    private final List proxyList;

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
         "Could not find the following Destination:<BR><BR><div>")
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
    
    private final static byte[] ERR_AHELPER_CONFLICT =
        ("HTTP/1.1 409 Conflict\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: Destination key conflict</H1>"+
         "The addresshelper link you followed specifies a different destination key "+
         "than a host entry in your host database. "+
         "Someone could be trying to impersonate another eepsite, "+
         "or people have given two eepsites identical names.<P/>"+
         "You can resolve the conflict by considering which key you trust, "+
         "and either discarding the addresshelper link, "+
         "discarding the host entry from your host database, "+
         "or naming one of them differently.<P/>")
         .getBytes();
    
    private final static byte[] ERR_BAD_PROTOCOL =
        ("HTTP/1.1 403 Bad Protocol\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: NON-HTTP PROTOCOL</H1>"+
         "The request uses a bad protocol. "+
         "The I2P HTTP Proxy supports http:// requests ONLY. Other protocols such as https:// and ftp:// are not allowed.<BR>")
        .getBytes();
    
    private final static byte[] ERR_LOCALHOST =
        ("HTTP/1.1 403 Access Denied\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: REQUEST DENIED</H1>"+
         "Your browser is misconfigured. Do not use the proxy to access the router console or other localhost destinations.<BR>")
        .getBytes();
    
    /** used to assign unique IDs to the threads / clients.  no logic or functionality */
    private static volatile long __clientId = 0;

    private static final File _errorDir = new File(I2PAppContext.getGlobalContext().getBaseDir(), "docs");


    /**
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelHTTPClient(int localPort, Logging l, boolean ownDest, 
                               String wwwProxy, EventDispatcher notifyThis, 
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, "HTTPHandler " + (++__clientId), tunnel);

        proxyList = new ArrayList();
        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openHTTPClientResult", "error");
            return;
        }

        if (wwwProxy != null) {
            StringTokenizer tok = new StringTokenizer(wwwProxy, ", ");
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
     * unused?
     */
    protected I2PSocketOptions getDefaultOptions() {
        Properties defaultOpts = getTunnel().getClientOptions();
        if (!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT))
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, ""+DEFAULT_READ_TIMEOUT);
        //if (!defaultOpts.contains("i2p.streaming.inactivityTimeout"))
        //    defaultOpts.setProperty("i2p.streaming.inactivityTimeout", ""+DEFAULT_READ_TIMEOUT);
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
        // delayed start
        verifySocketManager();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        return opts;
    }

    private static final boolean DEFAULT_GZIP = true;
    // all default to false
    public static final String PROP_REFERER = "i2ptunnel.httpclient.sendReferer";
    public static final String PROP_USER_AGENT = "i2ptunnel.httpclient.sendUserAgent";
    public static final String PROP_VIA = "i2ptunnel.httpclient.sendVia";
    
    private static long __requestId = 0;
    protected void clientConnectionRun(Socket s) {
        InputStream in = null;
        OutputStream out = null;
        String targetRequest = null;
        boolean usingWWWProxy = false;
        String currentProxy = null;
        long requestId = ++__requestId;
        try {
            out = s.getOutputStream();
            InputReader reader = new InputReader(s.getInputStream());
            String line, method = null, protocol = null, host = null, destination = null;
            StringBuilder newRequest = new StringBuilder();
            int ahelper = 0;
            while ((line = reader.readLine(method)) != null) {
                line = line.trim();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix(requestId) + "Line=[" + line + "]");
                
                String lowercaseLine = line.toLowerCase();
                if (lowercaseLine.startsWith("connection: ") || 
                    lowercaseLine.startsWith("keep-alive: ") || 
                    lowercaseLine.startsWith("proxy-connection: "))
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
                    } else if (request.startsWith("/eepproxy/")) {
                        // /eepproxy/foo.i2p/bar/baz.html HTTP/1.0
                        String subRequest = request.substring("/eepproxy/".length());
                        int protopos = subRequest.indexOf(" ");
                        String uri = subRequest.substring(0, protopos);
                        if (uri.indexOf("/") == -1) {
                                uri = uri + "/";
                        }
                        // "http://" + "foo.i2p/bar/baz.html" + " HTTP/1.0"
                        request = "http://" + uri + subRequest.substring(protopos);
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
                    
                    // parse port
                    int posPort = host.indexOf(":");
                    int port = 80;
                    if(posPort != -1) {
                        String[] parts = host.split(":");
                        host = parts[0];
                        try {
                            port = Integer.parseInt(parts[1]);
                        } catch(Exception exc) {
                            // TODO: log this
                        }
                    }
                    
                    // Quick hack for foo.bar.i2p
                    if (host.toLowerCase().endsWith(".i2p")) {
                        // Destination gets the host name
                        destination = host;
                        // Host becomes the destination key
                        host = getHostName(destination);

                        int pos2;
                        if ((pos2 = request.indexOf("?")) != -1) {
                            // Try to find an address helper in the fragments
                            // and split the request into it's component parts for rebuilding later
                            String ahelperKey = null;
                            boolean ahelperConflict = false;
                            
                            String fragments = request.substring(pos2 + 1);
                            String uriPath = request.substring(0, pos2);
                            pos2 = fragments.indexOf(" ");
                            String protocolVersion = fragments.substring(pos2 + 1);
                            String urlEncoding = "";
                            fragments = fragments.substring(0, pos2);
                            String initialFragments = fragments;
                            fragments = fragments + "&";
                            String fragment;
                            while(fragments.length() > 0) {
                                pos2 = fragments.indexOf("&");
                                fragment = fragments.substring(0, pos2);
                                fragments = fragments.substring(pos2 + 1);
                                
                                // Fragment looks like addresshelper key
                                if (fragment.startsWith("i2paddresshelper=")) {
                                    pos2 = fragment.indexOf("=");
                                    ahelperKey = fragment.substring(pos2 + 1);
                                    
                                    // Key contains data, lets not ignore it
                                    if (ahelperKey != null) {
                                        
                                        // Host resolvable only with addresshelper
                                        if ( (host == null) || ("i2p".equals(host)) )
                                        {
                                            // Cannot check, use addresshelper key
                                            addressHelpers.put(destination,ahelperKey);
                                        } else {
                                            // Host resolvable from database, verify addresshelper key
                                            // Silently bypass correct keys, otherwise alert
                                            if (!host.equals(ahelperKey))
                                            {
                                                // Conflict: handle when URL reconstruction done
                                                ahelperConflict = true;
                                                if (_log.shouldLog(Log.WARN))
                                                    _log.warn(getPrefix(requestId) + "Addresshelper key conflict for site [" + destination + "], trusted key [" + host + "], specified key [" + ahelperKey + "].");
                                            }
                                        }
                                    }
                                } else {
                                    // Other fragments, just pass along
                                    // Append each fragment to urlEncoding
                                    if ("".equals(urlEncoding)) {
                                        urlEncoding = "?" + fragment;
                                    } else {
                                        urlEncoding = urlEncoding + "&" + fragment; 
                                    }
                                }
                            }
                            // Reconstruct the request minus the i2paddresshelper GET var
                            request = uriPath + urlEncoding + " " + protocolVersion;
                            
                            // Did addresshelper key conflict?
                            if (ahelperConflict)
                            {
                                String str;
                                byte[] header;
                                str = FileUtil.readTextFile((new File(_errorDir, "ahelper-conflict-header.ht")).getAbsolutePath(), 100, true);
                                if (str != null) header = str.getBytes();
                                  else header = ERR_AHELPER_CONFLICT;

                                if (out != null) {
                                    long alias = I2PAppContext.getGlobalContext().random().nextLong();
                                    String trustedURL = protocol + uriPath + urlEncoding;
                                    String conflictURL = protocol + alias + ".i2p/?" + initialFragments;
                                    out.write(header);
                                    out.write(("To visit the destination in your host database, click <a href=\"" + trustedURL + "\">here</a>. To visit the conflicting addresshelper link by temporarily giving it a random alias, click <a href=\"" + conflictURL + "\">here</a>.<P/>").getBytes());
                                    out.write("</div><div class=\"proxyfooter\"><p><i>I2P HTTP Proxy Server<br>Generated on: ".getBytes());
                                    out.write(new Date().toString().getBytes());
                                    out.write("</i></div></body></html>\n".getBytes());
                                    out.flush();
                                }
                                s.close();
                                return;
                            }
                        }

                        String addressHelper = (String) addressHelpers.get(destination);
                        if (addressHelper != null) {
                            destination = addressHelper;
                            host = getHostName(destination);
                            ahelper = 1;
                        }
                        
                        line = method + " " + request.substring(pos);
                    } else if (host.toLowerCase().equals("localhost") || host.equals("127.0.0.1")) {
                        if (out != null) {
                            out.write(ERR_LOCALHOST);
                            out.write("<p /><i>Generated on: ".getBytes());
                            out.write(new Date().toString().getBytes());
                            out.write("</i></body></html>\n".getBytes());
                            out.flush();
                        }
                        s.close();
                        return;
                    } else if (host.indexOf(".") != -1) {
                        // rebuild host
                        host = host + ":" + port;
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
                        if (pos < 0) {
                            l.log("Invalid request url [" + request + "]");
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
                    if (lowercaseLine.startsWith("host: ") && !usingWWWProxy) {
                        line = "Host: " + host;
                        if (_log.shouldLog(Log.INFO)) 
                            _log.info(getPrefix(requestId) + "Setting host = " + host);
                    } else if (lowercaseLine.startsWith("user-agent: ") &&
                               !Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_USER_AGENT)).booleanValue()) {
                        line = null;
                        continue;
                    } else if (lowercaseLine.startsWith("accept")) {
                        // strip the accept-blah headers, as they vary dramatically from
                        // browser to browser
                        line = null;
                        continue;
                    } else if (lowercaseLine.startsWith("referer: ") &&
                               !Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_REFERER)).booleanValue()) {
                        // Shouldn't we be more specific, like accepting in-site referers ?
                        //line = "Referer: i2p";
                        line = null;
                        continue; // completely strip the line
                    } else if (lowercaseLine.startsWith("via: ") &&
                               !Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_VIA)).booleanValue()) {
                        //line = "Via: i2p";
                        line = null;
                        continue; // completely strip the line
                    } else if (lowercaseLine.startsWith("from: ")) {
                        //line = "From: i2p";
                        line = null;
                        continue; // completely strip the line
                    }
                }
                
                if (line.length() == 0) {
                    
                    String ok = getTunnel().getClientOptions().getProperty("i2ptunnel.gzip");
                    boolean gzip = DEFAULT_GZIP;
                    if (ok != null)
                        gzip = Boolean.valueOf(ok).booleanValue();
                    if (gzip) {
                        // according to rfc2616 s14.3, this *should* force identity, even if
                        // an explicit q=0 for gzip doesn't.  tested against orion.i2p, and it
                        // seems to work.
                        newRequest.append("Accept-Encoding: \r\n");
                        newRequest.append("X-Accept-Encoding: x-i2p-gzip;q=1.0, identity;q=0.5, deflate;q=0, gzip;q=0, *;q=0\r\n");
                    }
                    if (!Boolean.valueOf(getTunnel().getClientOptions().getProperty(PROP_USER_AGENT)).booleanValue())
                        newRequest.append("User-Agent: MYOB/6.66 (AN/ON)\r\n");
                    newRequest.append("Connection: close\r\n\r\n");
                    break;
                } else {
                    newRequest.append(line).append("\r\n"); // HTTP spec
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix(requestId) + "NewRequest header: [" + newRequest.toString() + "]");

            if (method == null || destination == null) {
                l.log("No HTTP method found in the request.");
                if (out != null) {
                    if ("http://".equalsIgnoreCase(protocol))
                        out.write(ERR_REQUEST_DENIED);
                    else
                        out.write(ERR_BAD_PROTOCOL);
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
            
            // Serve local proxy files (images, css linked from error pages)
            // Ignore all the headers
            if (destination.equals("proxy.i2p")) {
                serveLocalFile(out, method, targetRequest);
                s.close();
                return;
            }

            Destination dest = I2PTunnel.destFromName(destination);
            if (dest == null) {
                //l.log("Could not resolve " + destination + ".");
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to resolve " + destination + " (proxy? " + usingWWWProxy + ", request: " + targetRequest);
                String str;
                byte[] header;
                boolean showAddrHelper = false;
                if (usingWWWProxy)
                    str = FileUtil.readTextFile((new File(_errorDir, "dnfp-header.ht")).getAbsolutePath(), 100, true);
                else if(ahelper != 0)
                    str = FileUtil.readTextFile((new File(_errorDir, "dnfb-header.ht")).getAbsolutePath(), 100, true);
                else if (destination.length() == 60 && destination.endsWith(".b32.i2p"))
                    str = FileUtil.readTextFile((new File(_errorDir, "dnf-header.ht")).getAbsolutePath(), 100, true);
                else {
                    str = FileUtil.readTextFile((new File(_errorDir, "dnfh-header.ht")).getAbsolutePath(), 100, true);
                    showAddrHelper = true;
                }
                if (str != null)
                    header = str.getBytes();
                else
                    header = ERR_DESTINATION_UNKNOWN;
                writeErrorMessage(header, out, targetRequest, usingWWWProxy, destination, showAddrHelper);
                s.close();
                return;
            }
            String remoteID;
            
            Properties opts = new Properties();
            //opts.setProperty("i2p.streaming.inactivityTimeout", ""+120*1000);
            // 1 == disconnect.  see ConnectionOptions in the new streaming lib, which i
            // dont want to hard link to here
            //opts.setProperty("i2p.streaming.inactivityTimeoutAction", ""+1);
            I2PSocket i2ps = createI2PSocket(dest, getDefaultOptions(opts));
            byte[] data = newRequest.toString().getBytes("ISO-8859-1");
            Runnable onTimeout = new OnTimeout(s, s.getOutputStream(), targetRequest, usingWWWProxy, currentProxy, requestId);
            I2PTunnelRunner runner = new I2PTunnelHTTPClientRunner(s, i2ps, sockLock, data, mySockets, onTimeout);
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
        } catch (OutOfMemoryError oom) {
            IOException ex = new IOException("OOM");
            _log.info("getPrefix(requestId) + Error trying to connect", ex);
            l.log(ex.getMessage());
            handleHTTPClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
            closeSocket(s);
        }
    }

    /**
     *  Read the first line unbuffered.
     *  After that, switch to a BufferedReader, unless the method is "POST".
     *  We can't use BufferedReader for POST because we can't have readahead,
     *  since we are passing the stream on to I2PTunnelRunner for the POST data.
     *
     */
    private static class InputReader {
        BufferedReader _br;
        InputStream _s;
        public InputReader(InputStream s) {
            _br = null;
            _s = s;
        }
        String readLine(String method) throws IOException {
             if (method == null || "POST".equals(method))
                 return DataHelper.readLine(_s);
             if (_br == null)
                 _br = new BufferedReader(new InputStreamReader(_s, "ISO-8859-1"));
             return _br.readLine();
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

    private static class OnTimeout implements Runnable {
        private Socket _socket;
        private OutputStream _out;
        private String _target;
        private boolean _usingProxy;
        private String _wwwProxy;
        private long _requestId;
        public OnTimeout(Socket s, OutputStream out, String target, boolean usingProxy, String wwwProxy, long id) {
            _socket = s;
            _out = out;
            _target = target;
            _usingProxy = usingProxy;
            _wwwProxy = wwwProxy;
            _requestId = id;
        }
        public void run() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Timeout occured requesting " + _target);
            handleHTTPClientException(new RuntimeException("Timeout"), _out, 
                                      _target, _usingProxy, _wwwProxy, _requestId);
            closeSocket(_socket);
        }
    }
    
    private static String jumpServers[] = {
                                           "http://i2host.i2p/cgi-bin/i2hostjump?",
                                           // "http://orion.i2p/jump/",
                                           "http://stats.i2p/cgi-bin/jump.cgi?a=",
                                           // "http://trevorreznik.i2p/cgi-bin/jump.php?hostname=",
                                           "http://i2jump.i2p/"
                                          };
    private static void writeErrorMessage(byte[] errMessage, OutputStream out, String targetRequest,
                                          boolean usingWWWProxy, String wwwProxy, boolean showAddrHelper) throws IOException {
        if (out != null) {
            out.write(errMessage);
            if (targetRequest != null) {
                int protopos = targetRequest.indexOf(" ");
                String uri = null;
                if (protopos >= 0)
                    uri = targetRequest.substring(0, protopos);
                else
                    uri = targetRequest;
                out.write("<a href=\"http://".getBytes());
                out.write(uri.getBytes());
                out.write("\">http://".getBytes());
                out.write(uri.getBytes());
                out.write("</a>".getBytes());
                if (usingWWWProxy) out.write(("<br>WWW proxy: " + wwwProxy).getBytes());
                if (showAddrHelper) {
                    out.write("<br><br>Click a link below to look for an address helper by using a \"jump\" service:<br>".getBytes());
                    for (int i = 0; i < jumpServers.length; i++) {
                        // Skip jump servers we don't know
                        String jumphost = jumpServers[i].substring(7);  // "http://"
                        jumphost = jumphost.substring(0, jumphost.indexOf('/'));
                        try {
                            Destination dest = I2PTunnel.destFromName(jumphost);
                            if (dest == null) continue;
                        } catch (DataFormatException dfe) {
                            continue;
                        }

                        out.write("<br><a href=\"".getBytes());
                        out.write(jumpServers[i].getBytes());
                        out.write(uri.getBytes());
                        out.write("\">".getBytes());
                        out.write(jumpServers[i].getBytes());
                        out.write(uri.getBytes());
                        out.write("</a>".getBytes());
                    }
                }
            }
            out.write("</div><div class=\"proxyfooter\"><p><i>I2P HTTP Proxy Server<br>Generated on: ".getBytes());
            out.write(new Date().toString().getBytes());
            out.write("</i></div></body></html>\n".getBytes());
            out.flush();
        }
    }

    private static void handleHTTPClientException(Exception ex, OutputStream out, String targetRequest,
                                                  boolean usingWWWProxy, String wwwProxy, long requestId) {
                                                      
        // static
        //if (_log.shouldLog(Log.WARN))
        //    _log.warn(getPrefix(requestId) + "Error sending to " + wwwProxy + " (proxy? " + usingWWWProxy + ", request: " + targetRequest, ex);
        if (out != null) {
            try {
                String str;
                byte[] header;
                if (usingWWWProxy)
                    str = FileUtil.readTextFile((new File(_errorDir, "dnfp-header.ht")).getAbsolutePath(), 100, true);
                else
                    str = FileUtil.readTextFile((new File(_errorDir, "dnf-header.ht")).getAbsolutePath(), 100, true);
                if (str != null)
                    header = str.getBytes();
                else
                    header = ERR_DESTINATION_UNKNOWN;
                writeErrorMessage(header, out, targetRequest, usingWWWProxy, wwwProxy, false);
            } catch (IOException ioe) {
                // static
                //_log.warn(getPrefix(requestId) + "Error writing out the 'destination was unknown' " + "message", ioe);
            }
        } else {
            // static
            //_log.warn(getPrefix(requestId) + "Client disconnected before we could say that destination " + "was unknown", ex);
        }
    }

    private final static String SUPPORTED_HOSTS[] = { "i2p", "www.i2p.com", "i2p."};

    private static boolean isSupportedAddress(String host, String protocol) {
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

    private final static byte[] ERR_404 =
        ("HTTP/1.1 404 Not Found\r\n"+
         "Content-Type: text/plain\r\n"+
         "\r\n"+
         "HTTP Proxy local file not found")
        .getBytes();

    /**
     *  Very simple web server.
     *
     *  Serve local files in the docs/ directory, for CSS and images in
     *  error pages, using the reserved address proxy.i2p
     *  (similar to p.p in privoxy).
     *  This solves the problems with including links to the router console,
     *  as assuming the router console is at 127.0.0.1 leads to broken
     *  links if it isn't.
     *
     *  Ignore all request headers (If-Modified-Since, etc.)
     *
     *  There is basic protection here -
     *  FileUtil.readFile() prevents traversal above the base directory -
     *  but inproxy/gateway ops would be wise to block proxy.i2p to prevent
     *  exposing the docs/ directory or perhaps other issues through
     *  uncaught vulnerabilities.
     *  Restrict to the /themes/ directory for now.
     *
     *  @param targetRequest "proxy.i2p/themes/foo.png HTTP/1.1"
     */
    private static void serveLocalFile(OutputStream out, String method, String targetRequest) {
        // a home page message for the curious...
        if (targetRequest.startsWith("proxy.i2p/ ")) {
            try {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nCache-Control: max-age=86400\r\n\r\nI2P HTTP proxy OK").getBytes());
                out.flush();
            } catch (IOException ioe) {}
            return;
        }
        if ((method.equals("GET") || method.equals("HEAD")) &&
            targetRequest.startsWith("proxy.i2p/themes/") &&
            !targetRequest.contains("..")) {
            int space = targetRequest.indexOf(' ');
            String filename = null;
            try {
                filename = targetRequest.substring(17, space); // "proxy.i2p/themes/".length
            } catch (IndexOutOfBoundsException ioobe) {}
            // theme hack
            if (filename.startsWith("console/default/"))
                filename = filename.replaceFirst("default", I2PAppContext.getGlobalContext().getProperty("routerconsole.theme", "light"));
            File themesDir = new File(_errorDir, "themes");
            File file = new File(themesDir, filename);
            if (file.exists() && !file.isDirectory()) {
                String type;
                if (filename.endsWith(".css"))
                    type = "text/css";
                else if (filename.endsWith(".png"))
                    type = "image/png";
                else if (filename.endsWith(".jpg"))
                    type = "image/jpeg";
                else type = "text/html";
                try {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: ".getBytes());
                    out.write(type.getBytes());
                    out.write("\r\nCache-Control: max-age=86400\r\n\r\n".getBytes());
                    FileUtil.readFile(filename, themesDir.getAbsolutePath(), out);
                    return;
                } catch (IOException ioe) {}
            }
        }
        try {
            out.write(ERR_404);
            out.flush();
        } catch (IOException ioe) {}
    }
}
