/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelClientBase;
import net.i2p.i2ptunnel.I2PTunnelRunner;
import net.i2p.i2ptunnel.Logging;
import net.i2p.socks.SOCKSException;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

public class I2PSOCKSTunnel extends I2PTunnelClientBase {

    /**
     *  This is a standard soTimeout, not a total timeout.
     *  We have no slowloris protection on the client side.
     *  See I2PTunnelHTTPServer or SAM's ReadLine if we need that.
     *  @since 0.9.33
     */
    protected static final int INITIAL_SO_TIMEOUT = 15*1000;

    private HashMap<String, List<String>> proxies = null;  // port# + "" or "default" -> hostname list

    //public I2PSOCKSTunnel(int localPort, Logging l, boolean ownDest) {
    //	  I2PSOCKSTunnel(localPort, l, ownDest, (EventDispatcher)null);
    //}

    /**
     *  As of 0.9.20 this is fast, and does NOT connect the manager to the router,
     *  or open the local socket. You MUST call startRunning() for that.
     *
     *  @param pkf private key file name or null for transient key
     */
    public I2PSOCKSTunnel(int localPort, Logging l, boolean ownDest, EventDispatcher notifyThis, I2PTunnel tunnel, String pkf) {
        super(localPort, ownDest, l, notifyThis, "SOCKS Proxy on " + tunnel.listenHost + ':' + localPort, tunnel, pkf);

        setName("SOCKS Proxy on " + tunnel.listenHost + ':' + localPort);
        parseOptions();
        notifyEvent("openSOCKSTunnelResult", "ok");
    }

    protected void clientConnectionRun(Socket s) {
        I2PSocket destSock = null;
        try {
            try {
                s.setSoTimeout(INITIAL_SO_TIMEOUT);
            } catch (SocketException ioe) {}
            SOCKSServer serv = SOCKSServerFactory.createSOCKSServer(_context, s, getTunnel().getClientOptions());
            Socket clientSock = serv.getClientSocket();
            try {
                s.setSoTimeout(0);
            } catch (SocketException ioe) {}
            destSock = serv.getDestinationI2PSocket(this);
            Thread t = new I2PTunnelRunner(clientSock, destSock, sockLock, null, null, mySockets,
                                           (I2PTunnelRunner.FailCallback) null);
            // we are called from an unlimited thread pool, so run inline
            //t.start();
            t.run();
        } catch (SOCKSException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error from SOCKS connection", e);
        } finally {
            // only because we are running it inline
            closeSocket(s);
            if (destSock != null) try { destSock.close(); } catch (IOException ioe) {}
        }
    }

    /** add "default" or port number */
    public static final String PROP_PROXY_PREFIX = "i2ptunnel.socks.proxy.";
    public static final String DEFAULT = "default";
    public static final String PROP_PROXY_DEFAULT = PROP_PROXY_PREFIX + DEFAULT;

    private void parseOptions() {
        Properties opts = getTunnel().getClientOptions();
        proxies = new HashMap<String, List<String>>(1);
        for (Map.Entry<Object, Object> e : opts.entrySet()) {
           String prop = (String)e.getKey();
           if ((!prop.startsWith(PROP_PROXY_PREFIX)) || prop.length() <= PROP_PROXY_PREFIX.length())
              continue;
           String port = prop.substring(PROP_PROXY_PREFIX.length());
           List<String> proxyList = new ArrayList<String>(1);
           StringTokenizer tok = new StringTokenizer((String)e.getValue(), ", \t");
           while (tok.hasMoreTokens()) {
               String proxy = tok.nextToken().trim();
               if (proxy.endsWith(".i2p"))
                   proxyList.add(proxy);
               else
                   _log.error("Non-i2p SOCKS outproxy: " + proxy);
           }
           proxies.put(port, proxyList);
        }
    }

    public HashMap<String, List<String>> getProxyMap() {
        return proxies;
    }

    public List<String> getProxies(int port) {
        List<String> rv = proxies.get(port + "");
        if (rv == null)
            rv = getDefaultProxies();
        return rv;
    }

    public List<String> getDefaultProxies() {
        return proxies.get(DEFAULT);
    }

    /** 
     * Because getDefaultOptions() in super() is protected
     * @since 0.8.2
     */
    public I2PSocketOptions buildOptions(Properties overrides) {
        Properties defaultOpts = getTunnel().getClientOptions();
        defaultOpts.putAll(overrides);
        // delayed start
        verifySocketManager();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(60 * 1000);
        return opts;
    }

}
