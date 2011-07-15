package net.i2p.i2ptunnel.irc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelRunner;
import net.i2p.i2ptunnel.I2PTunnelServer;
import net.i2p.i2ptunnel.Logging;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 * A standard server that only answers for registered ports,
 * and each port can only be used once.
 *
 * <pre>
 *
 *                <---  I2PTunnelDCCServer <--------------- I2PTunnelDCCClient <----
 *   originating                                                                     responding
 *   chat client                                                                     chat client
 *                ---> I2PTunnelIRCClient --> IRC server --> I2TunnelIRCClient ----->
 *
 * </pre>
 *
 * @since 0.8.9
 */
public class I2PTunnelDCCServer extends I2PTunnelServer {

    private final ConcurrentHashMap<Integer, LocalAddress> _outgoing;
    private final ConcurrentHashMap<Integer, I2PSocket> _active;

    // list of client tunnels?
    private static long _id;

    /** just to keep super() happy */
    private static final InetAddress DUMMY;
    static {
        InetAddress dummy = null;
        try {
            dummy = InetAddress.getByAddress(new byte[4]);
        } catch (UnknownHostException uhe) {}
        DUMMY = dummy;
    }

    private static final int MIN_I2P_PORT = 1;
    private static final int MAX_I2P_PORT = 65535;
    private static final int MAX_OUTGOING_PENDING = 20;
    private static final int MAX_OUTGOING_ACTIVE = 20;
    private static final long OUTBOUND_EXPIRE = 30*60*1000;
    private static final long ACTIVE_EXPIRE = 60*60*1000;

    /**
     * There's no support for unsolicited incoming I2P connections,
     * so there's no server host or port parameters.
     *
     * @param sktMgr an existing socket manager
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelDCCServer(I2PSocketManager sktMgr, Logging l,
                              EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(DUMMY, 0, sktMgr, l, notifyThis, tunnel);
        _outgoing = new ConcurrentHashMap(8);
        _active = new ConcurrentHashMap(8);
    }

    /**
     *  An incoming DCC connection, only accept for a known port.
     *  Passed through without filtering.
     */
    @Override
    protected void blockingHandle(I2PSocket socket) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Incoming connection to '" + toString() + "' from: " + socket.getPeerDestination().calculateHash().toBase64());

        try {
            expireOutbound();
            int myPort = socket.getLocalPort();
            // Port is a one-time-use only
            LocalAddress local = _outgoing.remove(Integer.valueOf(myPort));
            if (local == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Rejecting incoming DCC connection for unknown port " + myPort);
                try {
                    socket.close();
                } catch (IOException ioe) {}
                return;
            }
            if (_log.shouldLog(Log.WARN))
                _log.warn("Incoming DCC connection for I2P port " + myPort +
                          " sending to " + local.ia + ':' + local.port);
            Socket s = new Socket(local.ia, local.port);
            new I2PTunnelRunner(s, socket, slock, null, null);
            _active.put(Integer.valueOf(myPort), socket);
        } catch (SocketException ex) {
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error connecting to server " + remoteHost + ':' + remotePort, ex);
        } catch (IOException ex) {
            _log.error("Error while waiting for I2PConnections", ex);
        }
    }

    @Override
    public boolean close(boolean forced) {
        _outgoing.clear();
        _active.clear();
        return super.close(forced);
    }

    /**
     *  An outgoing DCC request
     *
     *  @param ip local irc client IP
     *  @param port local irc client port
     *  @param type ignored
     *  @return i2p port or -1 on error
     */
    public int newOutgoing(byte[] ip, int port, String type) {
        expireOutbound();
        if (_outgoing.size() >= MAX_OUTGOING_PENDING ||
            _active.size() >= MAX_OUTGOING_ACTIVE) {
            _log.error("Too many outgoing DCC, max is " + MAX_OUTGOING_PENDING +
                       '/' + MAX_OUTGOING_ACTIVE + " pending/active");
            return -1;
        }
        InetAddress ia;
        try {
            ia = InetAddress.getByAddress(ip);
        } catch (UnknownHostException uhe) {
            return -1;
        }
        LocalAddress client = new LocalAddress(ia, port, getTunnel().getContext().clock().now() + OUTBOUND_EXPIRE);
        for (int i = 0; i < 10; i++) {
            int iport = MIN_I2P_PORT + getTunnel().getContext().random().nextInt(1 + MAX_I2P_PORT - MIN_I2P_PORT);
            if (_active.containsKey(Integer.valueOf(iport)))
                continue;
            LocalAddress old = _outgoing.putIfAbsent(Integer.valueOf(iport), client);
            if (old != null)
                continue;
            // TODO expire in a few minutes
            return iport;
        }
        // couldn't find an unused i2p port
        return -1;
    }

    private InetAddress getListenHost(Logging l) {
        try {
            return InetAddress.getByName(getTunnel().listenHost);
        } catch (UnknownHostException uhe) {
            l.log("Could not find listen host to bind to [" + getTunnel().host + "]");
            _log.error("Error finding host to bind", uhe);
            notifyEvent("openBaseClientResult", "error");
            return null;
        }
    }

    private void expireOutbound() {
        for (Iterator<LocalAddress> iter = _outgoing.values().iterator(); iter.hasNext(); ) {
            LocalAddress a = iter.next();
            if (a.expire < getTunnel().getContext().clock().now())
                iter.remove();
        }
        for (Iterator<I2PSocket> iter = _active.values().iterator(); iter.hasNext(); ) {
            I2PSocket s = iter.next();
            if (s.isClosed())
                iter.remove();
        }
    }

    private static class LocalAddress {
        public final InetAddress ia;
        public final int port;
        public final long expire;

        public LocalAddress(InetAddress a, int p, long exp) {
            ia = a;
            port = p;
            expire = exp;
        }
    }
}
