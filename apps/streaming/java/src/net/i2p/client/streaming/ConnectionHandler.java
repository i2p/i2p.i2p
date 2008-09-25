package net.i2p.client.streaming;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Receive new connection attempts
 */
class ConnectionHandler {
    private I2PAppContext _context;
    private Log _log;
    private ConnectionManager _manager;
    private List _synQueue;
    private boolean _active;
    private int _acceptTimeout;
    
    /** max time after receiveNewSyn() and before the matched accept() */
    private static final int DEFAULT_ACCEPT_TIMEOUT = 3*1000;
    
    /** Creates a new instance of ConnectionHandler */
    public ConnectionHandler(I2PAppContext context, ConnectionManager mgr) {
        _context = context;
        _log = context.logManager().getLog(ConnectionHandler.class);
        _manager = mgr;
        _synQueue = new ArrayList(5);
        _active = false;
        _acceptTimeout = DEFAULT_ACCEPT_TIMEOUT;
    }
    
    public void setActive(boolean active) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("setActive(" + active + ") called");
        synchronized (_synQueue) {
            _active = active; 
            _synQueue.notifyAll(); // so we break from the accept()
        }
    }
    public boolean getActive() { return _active; }
    
    public void receiveNewSyn(Packet packet) {
        if (!_active) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping new SYN request, as we're not listening");
            sendReset(packet);
            return;
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive new SYN: " + packet + ": timeout in " + _acceptTimeout);
        RetransmissionTimer.getInstance().addEvent(new TimeoutSyn(packet), _acceptTimeout);
        synchronized (_synQueue) {
            _synQueue.add(packet);
            _synQueue.notifyAll();
        }
    }
    
    /**
     * Receive an incoming connection (built from a received SYN)
     *
     * @param timeoutMs max amount of time to wait for a connection (if less 
     *                  than 1ms, wait indefinitely)
     * @return connection received, or null if there was a timeout or the 
     *                    handler was shut down
     */
    public Connection accept(long timeoutMs) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Accept("+ timeoutMs+") called");

        long expiration = timeoutMs + _context.clock().now();
        while (true) {
            if ( (timeoutMs > 0) && (expiration < _context.clock().now()) )
                return null;
            if (!_active) {
                // fail all the ones we had queued up
                synchronized (_synQueue) {
                    for (int i = 0; i < _synQueue.size(); i++) {
                        Packet packet = (Packet)_synQueue.get(i);
                        sendReset(packet);
                    }
                    _synQueue.clear();
                }
                return null;
            }
            
            Packet syn = null;
            synchronized (_synQueue) {
                while ( _active && (_synQueue.size() <= 0) ) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Accept("+ timeoutMs+"): active=" + _active + " queue: " 
                                   + _synQueue.size());
                    if (timeoutMs <= 0) {
                        try { _synQueue.wait(); } catch (InterruptedException ie) {}
                    } else {
                        long remaining = expiration - _context.clock().now();
                        if (remaining < 0)
                            break;
                        try { _synQueue.wait(remaining); } catch (InterruptedException ie) {}
                    }
                }
                if (_active && _synQueue.size() > 0) {
                    syn = (Packet)_synQueue.remove(0);
                }
            }

            if (syn != null) {
                // deal with forged / invalid syn packets
                Connection con = _manager.receiveConnection(syn);
                if (con != null)
                    return con;
            }
            // keep looping...
        }
    }
    
    private void sendReset(Packet packet) {
        boolean ok = packet.verifySignature(_context, packet.getOptionalFrom(), null);
        if (!ok) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received a spoofed SYN packet: they said they were " + packet.getOptionalFrom());
            return;
        }
        PacketLocal reply = new PacketLocal(_context, packet.getOptionalFrom());
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setAckThrough(packet.getSequenceNum());
        reply.setSendStreamId(packet.getReceiveStreamId());
        reply.setReceiveStreamId(0);
        reply.setOptionalFrom(_manager.getSession().getMyDestination());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending RST: " + reply + " because of " + packet);
        // this just sends the packet - no retries or whatnot
        _manager.getPacketQueue().enqueue(reply);
    }
    
    private class TimeoutSyn implements SimpleTimer.TimedEvent {
        private Packet _synPacket;
        public TimeoutSyn(Packet packet) {
            _synPacket = packet;
        }
        
        public void timeReached() {
            boolean removed = false;
            synchronized (_synQueue) {
                removed = _synQueue.remove(_synPacket);
            }
            
            if (removed) {
                // timeout - send RST
                sendReset(_synPacket);
            } else {
                // handled.  noop
            }
        }
    }
}
