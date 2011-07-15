package net.i2p.i2ptunnel.irc;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 *  Start, track, and expire the I2PTunnelDCCClients.
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
public class DCCClientManager extends EventReceiver {
    private final I2PSocketManager sockMgr;
    private final EventDispatcher _dispatch;
    private final Logging l;
    private final I2PTunnel _tunnel;
    private final Log _log;

    private final ConcurrentHashMap<Integer, I2PTunnelDCCClient> _incoming;
    private final ConcurrentHashMap<Integer, I2PTunnelDCCClient> _active;

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
        return true;
    }

    /**
     *  An incoming DCC request
     *
     *  @param b32 remote dcc server address
     *  @param port remote dcc server port
     *  @param type ignored
     *  @return local server port or -1 on error
     */
    public int newIncoming(String b32, int port, String type) {
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
            I2PTunnelDCCClient cTunnel = new I2PTunnelDCCClient(b32, port, l, sockMgr,
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
                          " active count now: " + _active.size());
        }
    }

    private void connStopped(Integer lport) {
        _incoming.remove(lport);
        _active.remove(lport);
        if (_log.shouldLog(Log.WARN))
            _log.warn("Removed client tunnel for port " + lport +
                      " pending count now: " + _incoming.size() +
                      " active count now: " + _active.size());
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
    }
}
