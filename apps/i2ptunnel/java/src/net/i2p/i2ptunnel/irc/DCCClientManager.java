package net.i2p.i2ptunnel.irc;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base32;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 *  Start, track, and expire the I2PTunnelDCCClients.
 *
 * <pre>
 *
 *                                            direct conn
 *                <---> I2PTunnelDCCServer <--------------->I2PTunnelDCCClient <---->
 *   originating                                                                     responding
 *   chat client                                                                     chat client
 *        CHAT    ---> I2PTunnelIRCClient --> IRC server --> I2TunnelIRCClient ----->
 *        SEND    ---> I2PTunnelIRCClient --> IRC server --> I2TunnelIRCClient ----->
 *        RESUME  <--- I2PTunnelIRCClient <-- IRC server <-- I2TunnelIRCClient <-----
 *        ACCEPT  ---> I2PTunnelIRCClient --> IRC server --> I2TunnelIRCClient ----->
 *
 * </pre>
 *
 * @since 0.8.9
 */
public class DCCClientManager extends EventReceiver {
    private final I2PSocketManager sockMgr;
    private final EventDispatcher _dispatch;
    private final Logging l;
    private final I2PTunnel _tunnel;
    private final Log _log;

    /** key is the DCC client's local port */
    private final ConcurrentHashMap<Integer, I2PTunnelDCCClient> _incoming;
    /** key is the DCC client's local port */
    private final ConcurrentHashMap<Integer, I2PTunnelDCCClient> _active;
    /** key is the DCC client's local port */
    private final ConcurrentHashMap<Integer, I2PTunnelDCCClient> _complete;

    // list of client tunnels?
    private static long _id;

    private static final int MAX_INCOMING_PENDING = 10;
    private static final int MAX_INCOMING_ACTIVE = 10;
    private static final long ACTIVE_EXPIRE = 60*60*1000;

    public DCCClientManager(I2PSocketManager sktMgr, Logging logging,
                            EventDispatcher dispatch, I2PTunnel tunnel) {
        sockMgr = sktMgr;
        l = logging;
        _dispatch = dispatch;
        _tunnel = tunnel;
        _log = tunnel.getContext().logManager().getLog(DCCClientManager.class);
        _incoming = new ConcurrentHashMap(8);
        _active = new ConcurrentHashMap(8);
        _complete = new ConcurrentHashMap(8);
    }

    public boolean close(boolean forced) {
        for (I2PTunnelDCCClient c : _incoming.values()) {
            c.stop();
        }
        _incoming.clear();
        for (I2PTunnelDCCClient c : _active.values()) {
            c.stop();
        }
        _active.clear();
        _complete.clear();
        return true;
    }

    /**
     *  An incoming DCC request
     *
     *  @param b32 remote dcc server b32 address
     *  @param port remote dcc server I2P port
     *  @param type ignored
     *  @return local DCC client tunnel port or -1 on error
     */
    public int newIncoming(String b32, int port, String type) {
        return newIncoming(b32, port, type, 0);
    }

    /**
     *  @param localPort bind to port or 0; if nonzero it will be the rv
     */
    private int newIncoming(String b32, int port, String type, int localPort) {
        b32 = b32.toLowerCase(Locale.US);
        // do some basic verification before starting the client
        if (b32.length() != 60 || !b32.endsWith(".b32.i2p"))
            return -1;
        byte[] dec = Base32.decode(b32.substring(0, 52));
        if (dec == null || dec.length != 32)
            return -1;
        expireInbound();
        if (_incoming.size() >= MAX_INCOMING_PENDING ||
            _active.size() >= MAX_INCOMING_PENDING) {
            _log.error("Too many incoming DCC, max is " + MAX_INCOMING_PENDING +
                       '/' + MAX_INCOMING_ACTIVE + " pending/active");
            return -1;
        }
        try {
            // Transparent tunnel used for all types...
            // Do we need to do any filtering for chat?
            I2PTunnelDCCClient cTunnel = new I2PTunnelDCCClient(b32, localPort, port, l, sockMgr,
                                                                _dispatch, _tunnel, ++_id);
            cTunnel.attachEventDispatcher(this);
            int lport = cTunnel.getLocalPort();
            if (_log.shouldLog(Log.WARN))
                _log.warn("Opened client tunnel at port " + lport +
                          " pointing to " + b32 + ':' + port);
            _incoming.put(Integer.valueOf(lport), cTunnel);
            return lport;
        } catch (IllegalArgumentException uhe) {
            l.log("Could not find listen host to bind to [" + _tunnel.host + "]");
            _log.error("Error finding host to bind", uhe);
            return -1;
        }
    }

    /**
     *  An outgoing RESUME request
     *
     *  @param port local DCC client tunnel port
     *  @return remote DCC server i2p port or -1 on error
     */
    public int resumeOutgoing(int port) {
        Integer lport = Integer.valueOf(port);
        I2PTunnelDCCClient tun = _complete.get(lport);
        if (tun == null) {
            tun = _active.get(lport);
            if (tun == null)
                // shouldn't happen
                tun = _incoming.get(lport);
        }
        if (tun != null) {
            tun.stop();
            return tun.getLocalPort();
        }
        return -1;
    }

    /**
     *  An incoming ACCEPT response
     *
     *  @param port remote dcc server I2P port
     *  @return local DCC client tunnel port or -1 on error
     */
    public int acceptIncoming(int port) {
        // do a reverse lookup
        for (I2PTunnelDCCClient tun : _complete.values()) {
            if (tun.getRemotePort() == port)
                return newIncoming(tun.getDest(), port, "ACCEPT", tun.getLocalPort());
        }
        for (I2PTunnelDCCClient tun : _active.values()) {
            if (tun.getRemotePort() == port)
                return newIncoming(tun.getDest(), port, "ACCEPT", tun.getLocalPort());
        }
        for (I2PTunnelDCCClient tun : _incoming.values()) {
            if (tun.getRemotePort() == port) {
                // shouldn't happen
                tun.stop();
                return newIncoming(tun.getDest(), port, "ACCEPT", tun.getLocalPort());
            }
        }
        return -1;
    }

    /**
     *  The EventReceiver callback
     */
    public void notifyEvent(String eventName, Object args) {
        if (eventName.equals(I2PTunnelDCCClient.CONNECT_START_EVENT)) {
            try {
                I2PTunnelDCCClient client = (I2PTunnelDCCClient) args;
                connStarted(client);
            } catch (ClassCastException cce) {}
        } else if (eventName.equals(I2PTunnelDCCClient.CONNECT_STOP_EVENT)) {
            try {
                Integer port = (Integer) args;
                connStopped(port);
            } catch (ClassCastException cce) {}
        }
    }

    private void connStarted(I2PTunnelDCCClient client) {
        Integer lport = Integer.valueOf(client.getLocalPort());
        I2PTunnelDCCClient c = _incoming.remove(lport);
        if (c != null) {
            _active.put(lport, client);
            if (_log.shouldLog(Log.WARN))
                _log.warn("Added client tunnel for port " + lport +
                          " pending count now: " + _incoming.size() +
                          " active count now: " + _active.size() +
                          " complete count now: " + _complete.size());
        }
    }

    private void connStopped(Integer lport) {
        I2PTunnelDCCClient tun = _incoming.remove(lport);
        if (tun != null)
            _complete.put(lport, tun);
        tun = _active.remove(lport);
        if (tun != null)
            _complete.put(lport, tun);
        if (_log.shouldLog(Log.WARN))
            _log.warn("Removed client tunnel for port " + lport +
                      " pending count now: " + _incoming.size() +
                      " active count now: " + _active.size() +
                      " complete count now: " + _complete.size());
    }

    private void expireInbound() {
        for (Iterator<I2PTunnelDCCClient> iter = _incoming.values().iterator(); iter.hasNext(); ) {
            I2PTunnelDCCClient c = iter.next();
            if (c.getExpires() < _tunnel.getContext().clock().now()) {
                iter.remove();
                c.stop();
            }
        }
        // shouldn't need to expire active
        for (Iterator<I2PTunnelDCCClient> iter = _complete.values().iterator(); iter.hasNext(); ) {
            I2PTunnelDCCClient c = iter.next();
            if (c.getExpires() < _tunnel.getContext().clock().now()) {
                iter.remove();
                c.stop();
            }
        }
    }
}
