/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel.irc;

import java.net.Socket;
import java.io.IOException;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelClientBase;
import net.i2p.i2ptunnel.I2PTunnelRunner;
import net.i2p.i2ptunnel.Logging;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 *  A standard client, using an existing socket manager.
 *  Targets a single destination and port.
 *  Naming resolution is delayed until connect time.
 *
 * @since 0.8.9
 */
public class I2PTunnelDCCClient extends I2PTunnelClientBase {

    // delay resolution until connect time
    private final String _dest;
    private final int _remotePort;

    /**
     * @param dest the target, presumably b32
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelDCCClient(String dest, int remotePort, Logging l,
                           I2PSocketManager sktMgr, EventDispatcher notifyThis, 
                           I2PTunnel tunnel, long clientId) throws IllegalArgumentException {
        super(0, l, sktMgr, tunnel, notifyThis, clientId);
        _dest = dest;
        _remotePort = remotePort;

        setName("DCC send -> " + dest + ':' + remotePort);

        startRunning();

        notifyEvent("openClientResult", "ok");
    }

    protected void clientConnectionRun(Socket s) {
        I2PSocket i2ps = null;
        if (_log.shouldLog(Log.INFO))
            _log.info("Opening DCC connection to " + _dest + ':' + _remotePort);
        Destination dest = _context.namingService().lookup(_dest);
        if (dest == null) {
            _log.error("Could not find leaseset for DCC connection to " + _dest + ':' + _remotePort);
            closeSocket(s);
            // shutdown?
            return;
        }

        I2PSocketOptions opts = sockMgr.buildOptions();
        opts.setPort(_remotePort);
        try {
            i2ps = createI2PSocket(dest, opts);
            new I2PTunnelRunner(s, i2ps, sockLock, null, mySockets);
        } catch (Exception ex) {
            _log.error("Could not make DCC connection to " + _dest + ':' + _remotePort, ex);
            closeSocket(s);
            if (i2ps != null) {
                try { i2ps.close(); } catch (IOException ioe) {}
            }
        }
    }
}
