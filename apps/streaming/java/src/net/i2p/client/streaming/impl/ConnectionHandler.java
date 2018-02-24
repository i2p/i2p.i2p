package net.i2p.client.streaming.impl;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.RouterRestartException;
import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

/**
 * Receive new connection attempts
 *
 * Use a bounded queue to limit the damage from SYN floods,
 * router overload, or a slow client
 *
 * @author zzz modded to use concurrent and bound queue size
 */
class ConnectionHandler {
    private final I2PAppContext _context;
    private final Log _log;
    private final ConnectionManager _manager;
    private final LinkedBlockingQueue<Packet> _synQueue;
    private final SimpleTimer2 _timer;
    private volatile boolean _active;
    private int _acceptTimeout;
    private boolean _restartPending;
    
    /** max time after receiveNewSyn() and before the matched accept() */
    private static final int DEFAULT_ACCEPT_TIMEOUT = 3*1000;

    /**
     *  This is both SYNs and subsequent packets, and with an initial window size of 12,
     *  this is a backlog of 5 to 64 Syns, which seems like plenty for now
     *  Don't make this too big because the removal by all the TimeoutSyns is O(n**2) - sortof.
     */
    private static final int MAX_QUEUE_SIZE = 64;
    
    /** Creates a new instance of ConnectionHandler */
    public ConnectionHandler(I2PAppContext context, ConnectionManager mgr, SimpleTimer2 timer) {
        _context = context;
        _log = context.logManager().getLog(ConnectionHandler.class);
        _manager = mgr;
        _timer = timer;
        _synQueue = new LinkedBlockingQueue<Packet>(MAX_QUEUE_SIZE);
        _acceptTimeout = DEFAULT_ACCEPT_TIMEOUT;
    }
    
    /**
     * The router told us it's going to restart.
     * Call instead of setActive(false).
     *
     * @since 0.9.34
     */
    public synchronized void setRestartPending() { 
        _restartPending = true;
        setActive(false);
    }

    public synchronized void setActive(boolean active) { 
        // FIXME active=false this only kills for one thread in accept()
        // if there are more, they won't get a poison packet.
        if (_log.shouldLog(Log.WARN))
            _log.warn("setActive(" + active + ") called, previously " + _active, new Exception("I did it"));
        // if starting, clear any old poison
        if (active && !_active) {
            _restartPending = false;
            _synQueue.clear();
        }
        boolean wasActive = _active;
        _active = active; 
        if (wasActive && !active) {
            // stopping, clear any pending sockets
            _synQueue.clear();
            _synQueue.offer(new PoisonPacket());
        }
    }

    public boolean getActive() { return _active; }
    
    /**
     * Non-SYN packets with a zero SendStreamID may also be queued here so 
     * that they don't get thrown away while the SYN packet before it is queued.
     *
     * Additional overload protection may be required here...
     * We don't have a 3-way handshake, so the SYN fully opens a connection.
     * Does that make us more or less vulnerable to SYN flooding?
     *
     */
    public void receiveNewSyn(Packet packet) {
        if (!_active) {
            if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping new SYN request, as we're not listening");
                sendReset(packet);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropping non-SYN packet - not listening");
            }
            return;
        }
        if (_manager.wasRecentlyClosed(packet.getSendStreamId())) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping packet for recently closed stream: " + packet);
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Receive new SYN: " + packet + ": timeout in " + _acceptTimeout);
        // also check if expiration of the head is long past for overload detection with peek() ?
        boolean success = _synQueue.offer(packet); // fail immediately if full
        if (success) {
            _timer.addEvent(new TimeoutSyn(packet), _acceptTimeout);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping new SYN request, as the queue is full");
            if (packet.isFlagSet(Packet.FLAG_SYNCHRONIZE))
                sendReset(packet);
        }
    }
    
    /**
     * Receive an incoming connection (built from a received SYN)
     * Non-SYN packets with a zero SendStreamID may also be queued here so 
     * that they don't get thrown away while the SYN packet before it is queued.
     *
     * @param timeoutMs max amount of time to wait for a connection (if less 
     *                  than 1ms, wait indefinitely)
     * @return connection received. Prior to 0.9.17, or null if there was a timeout or the 
     *                  handler was shut down. As of 0.9.17, never null.
     * @throws RouterRestartException (extends I2PException) if the router is apparently restarting, since 0.9.34
     * @throws ConnectException since 0.9.17, returned null before;
     *                  if the I2PServerSocket is closed, or if interrupted.
     * @throws SocketTimeoutException since 0.9.17, returned null before;
     *                  if a timeout was previously set with setSoTimeout and the timeout has been reached.
     */
    public Connection accept(long timeoutMs) throws RouterRestartException, ConnectException, SocketTimeoutException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Accept("+ timeoutMs+") called");

        long expiration = timeoutMs + _context.clock().now();
        while (true) {
            if ( (timeoutMs > 0) && (expiration < _context.clock().now()) )
                throw new SocketTimeoutException("accept() timed out");
            if (!_active) {
                // fail all the ones we had queued up
                while(true) {
                    Packet packet = _synQueue.poll(); // fails immediately if empty
                    if (packet == null || packet.getOptionalDelay() == PoisonPacket.POISON_MAX_DELAY_REQUEST)
                        break;
                    sendReset(packet);
                }
                if (_restartPending)
                    throw new RouterRestartException();
                throw new ConnectException("ServerSocket closed");
            }
            
            Packet syn = null;
            while ( _active && syn == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Accept("+ timeoutMs+"): active=" + _active + " queue: " 
                               + _synQueue.size());
                if (timeoutMs <= 0) {
                    try {
                       syn = _synQueue.take(); // waits forever
                    } catch (InterruptedException ie) {
                       ConnectException ce = new ConnectException("Interrupted accept()");
                       ce.initCause(ie);
                       throw ce;
                    }
                } else {
                    long remaining = expiration - _context.clock().now();
                    // (dont think this applies anymore for LinkedBlockingQueue)
                    // BUGFIX
                    // The specified amount of real time has elapsed, more or less. 
                    // If timeout is zero, however, then real time is not taken into consideration 
                    // and the thread simply waits until notified.
                    if (remaining < 1)
                        break;
                    try {
                        syn = _synQueue.poll(remaining, TimeUnit.MILLISECONDS); // waits the specified time max
                    } catch (InterruptedException ie) {
                       ConnectException ce = new ConnectException("Interrupted accept()");
                       ce.initCause(ie);
                       throw ce;
                    }
                    break;
                }
            }

            if (syn != null) {
                if (syn.getOptionalDelay() == PoisonPacket.POISON_MAX_DELAY_REQUEST) {
                    if (_restartPending)
                        throw new RouterRestartException();
                    throw new ConnectException("ServerSocket closed");
                }

                // deal with forged / invalid syn packets in _manager.receiveConnection()

                // Handle both SYN and non-SYN packets in the queue
                if (syn.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    // We are single-threaded here, so this is
                    // a good place to check for dup SYNs and drop them
                    Destination from = syn.getOptionalFrom();
                    if (from == null) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Dropping SYN packet with no FROM: " + syn);
                        // drop it
                        continue;
                    }
                    Connection oldcon = _manager.getConnectionByOutboundId(syn.getReceiveStreamId());
                    if (oldcon != null) {
                        // His ID not guaranteed to be unique to us, but probably is...
                        // only drop it on a destination match too
                        if (from.equals(oldcon.getRemotePeer())) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Dropping dup SYN: " + syn);
                            continue;
                        }
                    }
                    Connection con = _manager.receiveConnection(syn);
                    if (con != null)
                        return con;
                } else {
                    reReceivePacket(syn);
                    // ... and keep looping
                }
            }
            // keep looping...
        }
    }

    /**
     *  We found a non-SYN packet that was queued in the syn queue,
     *  check to see if it has a home now, else drop it ...
     */
    private void reReceivePacket(Packet packet) {
        Connection con = _manager.getConnectionByOutboundId(packet.getReceiveStreamId());
        if (con != null) {
            // Send it through the packet handler again
            if (_log.shouldLog(Log.WARN))
                _log.warn("Found con for queued non-syn packet: " + packet);
            // false -> don't requeue, fixes a race where a SYN gets dropped
            // between here and PacketHandler, causing the packet to loop forever....
            _manager.getPacketHandler().receivePacketDirect(packet, false);
        } else {
            // log it here, just before we kill it - dest will be unknown
            if (I2PSocketManagerFull.pcapWriter != null &&
                _context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP))
                packet.logTCPDump(null);

            // goodbye
            if (_log.shouldLog(Log.WARN))
                _log.warn("Did not find con for queued non-syn packet, dropping: " + packet);
            packet.releasePayload();
        }
    }

    private void sendReset(Packet packet) {
        boolean ok = packet.verifySignature(_context, packet.getOptionalFrom(), null);
        if (!ok) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Received a spoofed SYN packet: they said they were " + packet.getOptionalFrom());
            return;
        }
        PacketLocal reply = new PacketLocal(_context, packet.getOptionalFrom(), packet.getSession());
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setAckThrough(packet.getSequenceNum());
        reply.setSendStreamId(packet.getReceiveStreamId());
        reply.setReceiveStreamId(0);
        // TODO remove this someday, as of 0.9.20 we do not require it
        reply.setOptionalFrom();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Sending RST: " + reply + " because of " + packet);
        // this just sends the packet - no retries or whatnot
        _manager.getPacketQueue().enqueue(reply);
    }
    
    private class TimeoutSyn implements SimpleTimer.TimedEvent {
        private final Packet _synPacket;

        public TimeoutSyn(Packet packet) {
            _synPacket = packet;
        }
        
        public void timeReached() {
            boolean removed = _synQueue.remove(_synPacket);
            
            if (removed) {
                if (_synPacket.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Expired on the SYN queue: " + _synPacket);
                    // timeout - send RST
                    sendReset(_synPacket);
                } else {
                    // non-syn packet got stranded on the syn queue, send it to the con
                    reReceivePacket(_synPacket);
                }
            } else {
                // handled.  noop
            }
        }
    }

    /**
     * Simple end-of-queue marker.
     * The standard class limits the delay to POISON_MAX_DELAY_REQUEST so
     * an evil user can't use this to shut us down
     */
    private static class PoisonPacket extends Packet {
        public static final int POISON_MAX_DELAY_REQUEST = Packet.MAX_DELAY_REQUEST + 1;

        public PoisonPacket() {
            super(null);
        }

        @Override
        public int getOptionalDelay() { return POISON_MAX_DELAY_REQUEST; }

        @Override
        public String toString() {
            return "POISON";
        }
    }
}
