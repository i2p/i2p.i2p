/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

import java.net.Socket;

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

        startRunning();

        notifyEvent("openSOCKSTunnelResult", "ok");
    }

    protected void clientConnectionRun(Socket s) {
        try {
            SOCKSServer serv = SOCKSServerFactory.createSOCKSServer(s);
            Socket clientSock = serv.getClientSocket();
            I2PSocket destSock = serv.getDestinationI2PSocket();
            new I2PTunnelRunner(clientSock, destSock, sockLock, null);
        } catch (SOCKSException e) {
            _log.error("Error from SOCKS connection: " + e.getMessage());
            closeSocket(s);
        }
    }
}