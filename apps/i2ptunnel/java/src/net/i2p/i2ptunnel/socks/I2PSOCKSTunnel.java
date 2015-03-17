/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.net.Socket;
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
import net.i2p.util.EventDispatcher;

public class I2PSOCKSTunnel extends I2PTunnelClientBase {

    private HashMap<String, List<String>> proxies = null;  // port# + "" or "default" -> hostname list
    protected Destination outProxyDest = null;

    //public I2PSOCKSTunnel(int localPort, Logging l, boolean ownDest) {
    //	  I2PSOCKSTunnel(localPort, l, ownDest, (EventDispatcher)null);
    //}

    /** @param pkf private key file name or null for transient key */
    public I2PSOCKSTunnel(int localPort, Logging l, boolean ownDest, EventDispatcher notifyThis, I2PTunnel tunnel, String pkf) {
        super(localPort, ownDest, l, notifyThis, "SOCKS Proxy on " + tunnel.listenHost + ':' + localPort, tunnel, pkf);

        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openSOCKSTunnelResult", "error");
            return;
        }

        setName("SOCKS Proxy on " + tunnel.listenHost + ':' + localPort);
        parseOptions();
        startRunning();

        notifyEvent("openSOCKSTunnelResult", "ok");
    }

    protected void clientConnectionRun(Socket s) {
        try {
            SOCKSServer serv = SOCKSServerFactory.createSOCKSServer(s, getTunnel().getClientOptions());
            Socket clientSock = serv.getClientSocket();
            I2PSocket destSock = serv.getDestinationI2PSocket(this);
            new I2PTunnelRunner(clientSock, destSock, sockLock, null, mySockets);
        } catch (SOCKSException e) {
            _log.error("Error from SOCKS connection", e);
            closeSocket(s);
        }
    }

    /** add "default" or port number */
    public static final String PROP_PROXY_PREFIX = "i2ptunnel.socks.proxy.";
    public static final String DEFAULT = "default";
    public static final String PROP_PROXY_DEFAULT = PROP_PROXY_PREFIX + DEFAULT;

    private void parseOptions() {
        Properties opts = getTunnel().getClientOptions();
        proxies = new HashMap(1);
        for (Map.Entry e : opts.entrySet()) {
           String prop = (String)e.getKey();
           if ((!prop.startsWith(PROP_PROXY_PREFIX)) || prop.length() <= PROP_PROXY_PREFIX.length())
              continue;
           String port = prop.substring(PROP_PROXY_PREFIX.length());
           List proxyList = new ArrayList(1);
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
