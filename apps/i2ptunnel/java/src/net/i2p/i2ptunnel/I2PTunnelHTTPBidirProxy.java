/**
 *                    WTFPL
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and license questions.
 */
package net.i2p.i2ptunnel;

// import java.util.ArrayList;

import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.util.EventDispatcher;


/**
 * Reuse HTTP server's I2PSocketManager for a proxy with no outproxy capability.
 *
 * @author sponge
 */
public class I2PTunnelHTTPBidirProxy extends I2PTunnelHTTPClient implements Runnable {
  

    /**
     *  As of 0.9.20 this is fast, and does NOT connect the manager to the router,
     *  or open the local socket. You MUST call startRunning() for that.
     *
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelHTTPBidirProxy(int localPort, Logging l, I2PSocketManager sockMgr, I2PTunnel tunnel, EventDispatcher notifyThis, long clientId) {
        super(localPort, l, sockMgr, tunnel, notifyThis, clientId);
        // proxyList = new ArrayList();

        setName(getLocalPort() + " -> HTTPClient [NO PROXIES]");
        notifyEvent("openHTTPClientResult", "ok");
    }
}
