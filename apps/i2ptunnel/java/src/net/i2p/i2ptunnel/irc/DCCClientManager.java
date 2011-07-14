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
public class DCCClientManager {

    private final I2PSocketManager sockMgr;
    private final EventDispatcher _dispatch;
    private final Logging l;
    private final I2PTunnel _tunnel;
    private final Log _log;

    private final ConcurrentHashMap<Integer, I2PAddress> _incoming;
    // list of client tunnels?
    private static long _id;

    private static final int MAX_INCOMING_PENDING = 10;
    private static final int MAX_INCOMING_ACTIVE = 10;
    private static final long INBOUND_EXPIRE = 30*60*1000;

    public DCCClientManager(I2PSocketManager sktMgr, Logging logging,
                            EventDispatcher dispatch, I2PTunnel tunnel) {
        sockMgr = sktMgr;
        l = logging;
        _dispatch = dispatch;
        _tunnel = tunnel;
        _log = tunnel.getContext().logManager().getLog(DCCClientManager.class);
        _incoming = new ConcurrentHashMap(8);
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
        if (_incoming.size() >= MAX_INCOMING_PENDING) {
            _log.error("Too many incoming DCC, max is " + MAX_INCOMING_PENDING);
            return -1;
        }
        I2PAddress client = new I2PAddress(b32, port, _tunnel.getContext().clock().now() + INBOUND_EXPIRE);
        try {
            // Transparent tunnel used for all types...
            // Do we need to do any filtering for chat?
            I2PTunnelDCCClient cTunnel = new I2PTunnelDCCClient(b32, port, l, sockMgr,
                                                                _dispatch, _tunnel, ++_id);
            int lport = cTunnel.getLocalPort();
            if (_log.shouldLog(Log.WARN))
                _log.warn("Opened client tunnel at port " + lport +
                          " pointing to " + b32 + ':' + port);
            _incoming.put(Integer.valueOf(lport), client);
            return lport;
        } catch (IllegalArgumentException uhe) {
            l.log("Could not find listen host to bind to [" + _tunnel.host + "]");
            _log.error("Error finding host to bind", uhe);
            return -1;
        }
    }

    private void expireInbound() {
        for (Iterator<I2PAddress> iter = _incoming.values().iterator(); iter.hasNext(); ) {
            I2PAddress a = iter.next();
            if (a.expire < _tunnel.getContext().clock().now())
                iter.remove();
        }
    }

    private static class I2PAddress {
        public final String dest;
        public final int port;
        public final long expire;

        public I2PAddress(String b32, int p, long exp) {
            dest = b32;
            port = p;
            expire = exp;
        }
    }
}
