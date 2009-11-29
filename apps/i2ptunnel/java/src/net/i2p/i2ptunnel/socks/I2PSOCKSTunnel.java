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
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelClientBase;
import net.i2p.i2ptunnel.I2PTunnelRunner;
import net.i2p.i2ptunnel.Logging;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

public class I2PSOCKSTunnel extends I2PTunnelClientBase {

    private static final Log _log = new Log(I2PSOCKSTunnel.class);
    private HashMap<String, List<String>> proxies = null;  // port# + "" or "default" -> hostname list
    protected Destination outProxyDest = null;

    //public I2PSOCKSTunnel(int localPort, Logging l, boolean ownDest) {
    //	  I2PSOCKSTunnel(localPort, l, ownDest, (EventDispatcher)null);
    //}

    public I2PSOCKSTunnel(int localPort, Logging l, boolean ownDest, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(localPort, ownDest, l, notifyThis, "SOCKSHandler", tunnel);

        if (waitEventValue("openBaseClientResult").equals("error")) {
            notifyEvent("openSOCKSTunnelResult", "error");
            return;
        }

        setName(getLocalPort() + " -> SOCKSTunnel");
        parseOptions();
        startRunning();

        notifyEvent("openSOCKSTunnelResult", "ok");
    }

    protected void clientConnectionRun(Socket s) {
        try {
            SOCKSServer serv = SOCKSServerFactory.createSOCKSServer(s);
            Socket clientSock = serv.getClientSocket();
            I2PSocket destSock = serv.getDestinationI2PSocket(this);
            new I2PTunnelRunner(clientSock, destSock, sockLock, null, mySockets);
        } catch (SOCKSException e) {
            _log.error("Error from SOCKS connection", e);
            closeSocket(s);
        }
    }

    private static final String PROP_PROXY = "i2ptunnel.socks.proxy.";
    private void parseOptions() {
        Properties opts = getTunnel().getClientOptions();
        proxies = new HashMap(0);
        for (Map.Entry e : opts.entrySet()) {
           String prop = (String)e.getKey();
           if ((!prop.startsWith(PROP_PROXY)) || prop.length() <= PROP_PROXY.length())
              continue;
           String port = prop.substring(PROP_PROXY.length());
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
        return proxies.get("default");
    }

}
