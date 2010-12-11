package net.i2p.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/*
 * public domain
 */

/**
 * Implement multiplexing with a 1-byte 'protocol' and a two-byte 'port'.
 * Listeners register with either addListener() or addMuxedListener(),
 * depending on whether they want to hear about the
 * protocol, from port, and to port for every received message.
 *
 * This only calls one listener, not all that apply.
 *
 * @author zzz
 */
public class I2PSessionDemultiplexer implements I2PSessionMuxedListener {
    private Log _log;
    private Map<Integer, I2PSessionMuxedListener> _listeners;

    public I2PSessionDemultiplexer(I2PAppContext ctx) {
        _log = ctx.logManager().getLog(I2PSessionDemultiplexer.class);
        _listeners = new ConcurrentHashMap();
    }

    /** unused */
    public void messageAvailable(I2PSession session, int msgId, long size) {}

    public void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromport, int toport ) {
        I2PSessionMuxedListener l = findListener(proto, toport);
        if (l != null)
            l.messageAvailable(session, msgId, size, proto, fromport, toport);
        else {
            // no listener, throw it out
            if (_listeners.isEmpty()) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("No listeners for incoming message");
            } else {
                _log.error("No listener found for proto: " + proto + " port: " + toport + " msg id: " + msgId +
                           " from pool of " + _listeners.size() + " listeners");
            }
            try {
                session.receiveMessage(msgId);
            } catch (I2PSessionException ise) {}
        }
    }

    public void reportAbuse(I2PSession session, int severity) {
        for (I2PSessionMuxedListener l : _listeners.values())
            l.reportAbuse(session, severity);
    }

    public void disconnected(I2PSession session) {
        for (I2PSessionMuxedListener l : _listeners.values())
            l.disconnected(session);
    }

    public void errorOccurred(I2PSession session, String message, Throwable error) {
        for (I2PSessionMuxedListener l : _listeners.values())
            l.errorOccurred(session, message, error);
    }

    /**
     *  For those that don't need to hear about the protocol and ports
     *  in messageAvailable()
     *  (Streaming lib)
     */
    public void addListener(I2PSessionListener l, int proto, int port) {
        _listeners.put(key(proto, port), new NoPortsListener(l));
    }

    /**
     *  For those that do care
     *  UDP perhaps
     */
    public void addMuxedListener(I2PSessionMuxedListener l, int proto, int port) {
        _listeners.put(key(proto, port), l);
    }

    public void removeListener(int proto, int port) {
        _listeners.remove(key(proto, port));
    }

    /** find the one listener that most specifically matches the request */
    private I2PSessionMuxedListener findListener(int proto, int port) {
        I2PSessionMuxedListener rv = getListener(proto, port);
        if (rv != null) return rv;
        if (port != I2PSession.PORT_ANY) { // try any port
            rv = getListener(proto, I2PSession.PORT_ANY);
            if (rv != null) return rv;
        }
        if (proto != I2PSession.PROTO_ANY) { // try any protocol
            rv = getListener(I2PSession.PROTO_ANY, port);
            if (rv != null) return rv;
        }
        if (proto != I2PSession.PROTO_ANY && port != I2PSession.PORT_ANY) { // try default
            rv = getListener(I2PSession.PROTO_ANY, I2PSession.PORT_ANY);
        }
        return rv;
    }

    private I2PSessionMuxedListener getListener(int proto, int port) {
        return _listeners.get(key(proto, port));
    }

    private static Integer key(int proto, int port) {
        return Integer.valueOf(((port << 8) & 0xffff00) | proto);
    }

    /** for those that don't care about proto and ports */
    private static class NoPortsListener implements I2PSessionMuxedListener {
        private I2PSessionListener _l;

        public NoPortsListener(I2PSessionListener l) {
            _l = l;
        }

        public void messageAvailable(I2PSession session, int msgId, long size) {
            throw new IllegalArgumentException("no");
        }
        public void messageAvailable(I2PSession session, int msgId, long size, int proto, int fromport, int toport) {
            _l.messageAvailable(session, msgId, size);
        }
        public void reportAbuse(I2PSession session, int severity) {
            _l.reportAbuse(session, severity);
        }
        public void disconnected(I2PSession session) {
            _l.disconnected(session);
        }
        public void errorOccurred(I2PSession session, String message, Throwable error) {
            _l.errorOccurred(session, message, error);
        }
    }
}
