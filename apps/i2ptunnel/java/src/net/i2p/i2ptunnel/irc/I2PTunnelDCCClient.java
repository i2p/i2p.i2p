/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel.irc;

import java.net.Socket;
import java.io.IOException;

import net.i2p.I2PException;
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
    private long _expires;

    private static final long INBOUND_EXPIRE = 30*60*1000;
    private static final long INBOUND_STOP_EXPIRE = 30*60*1000;
    public static final String CONNECT_START_EVENT = "connectionStarted";
    public static final String CONNECT_STOP_EVENT = "connectionStopped";

    /**
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router,
     * or open the local socket. You MUST call startRunning() for that.
     *
     * @param dest the target, presumably b32
     * @param localPort if 0, use any port, get actual port selected with getLocalPort()
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelDCCClient(String dest, int localPort, int remotePort, Logging l,
                           I2PSocketManager sktMgr, EventDispatcher notifyThis, 
                           I2PTunnel tunnel, long clientId) throws IllegalArgumentException {
        super(localPort, l, sktMgr, tunnel, notifyThis, clientId);
        _dest = dest;
        _remotePort = remotePort;
        _expires = tunnel.getContext().clock().now() + INBOUND_EXPIRE;

        setName("DCC send -> " + dest + ':' + remotePort);
    }

    /**
     *  Accept one connection only.
     */
    protected void clientConnectionRun(Socket s) {
        I2PSocket i2ps = null;
        if (_log.shouldLog(Log.INFO))
            _log.info("Opening DCC connection to " + _dest + ':' + _remotePort);
        Destination dest = _context.namingService().lookup(_dest);
        if (dest == null) {
            _log.error("Could not find leaseset for DCC connection to " + _dest + ':' + _remotePort);
            closeSocket(s);
            stop();
            notifyEvent(CONNECT_STOP_EVENT, Integer.valueOf(getLocalPort()));
            return;
        }

        I2PSocketOptions opts = sockMgr.buildOptions();
        opts.setPort(_remotePort);
        try {
            i2ps = createI2PSocket(dest, opts);
            Thread t = new Runner(s, i2ps);
            // we are called from an unlimited thread pool, so run inline
            //t.start();
            t.run();
        } catch (IOException ex) {
            _log.error("Could not make DCC connection to " + _dest + ':' + _remotePort, ex);
            notifyEvent(CONNECT_STOP_EVENT, Integer.valueOf(getLocalPort()));
        } catch (I2PException ex) {
            _log.error("Could not make DCC connection to " + _dest + ':' + _remotePort, ex);
            notifyEvent(CONNECT_STOP_EVENT, Integer.valueOf(getLocalPort()));
        } finally {
            // only because we are running it inline
            closeSocket(s);
            if (i2ps != null) {
                try { i2ps.close(); } catch (IOException ioe) {}
            }
        }
        stop();
    }

    public long getExpires() {
        return _expires;
    }

    public String getDest() {
        return _dest;
    }

    public int getRemotePort() {
        return _remotePort;
    }

    /**
     *  Stop listening for new sockets.
     *  We can't call super.close() as it kills all sockets in the sockMgr
     */
    public void stop() {
        open = false;
        try {
            ss.close();
        } catch (IOException ioe) {}
    }

    /**
     *  Just so we can do the callbacks
     */
    private class Runner extends I2PTunnelRunner {

        /**
         *  Does NOT start itself. Caller must call start().
         */
        public Runner(Socket s, I2PSocket i2ps) {
            super(s, i2ps, sockLock, null, null, mySockets, (FailCallback) null);
        }

        @Override
        public void run() {
            _expires = getTunnel().getContext().clock().now() + INBOUND_STOP_EXPIRE;
            notifyEvent(CONNECT_START_EVENT, I2PTunnelDCCClient.this);
            super.run();
            _expires = getTunnel().getContext().clock().now() + INBOUND_STOP_EXPIRE;
            notifyEvent(CONNECT_STOP_EVENT, Integer.valueOf(getLocalPort()));
        }
    }
}
