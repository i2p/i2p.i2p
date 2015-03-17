/**
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 * Everyone is permitted to copy and distribute verbatim or modified
 * copies of this license document, and changing it is allowed as long
 * as the name is changed.
 *
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION
 *
 *  0. You just DO WHAT THE FUCK YOU WANT TO.
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and liscense questions.
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
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelHTTPBidirProxy(int localPort, Logging l, I2PSocketManager sockMgr, I2PTunnel tunnel, EventDispatcher notifyThis, long clientId) {
        super(localPort, l, sockMgr, tunnel, notifyThis, clientId);
        // proxyList = new ArrayList();

        setName(getLocalPort() + " -> HTTPClient [NO PROXIES]");
        startRunning();

        notifyEvent("openHTTPClientResult", "ok");
    }
}
