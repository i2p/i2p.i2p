package net.i2p.client.streaming;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.util.SimpleTimer;
import net.i2p.util.Log;

/**
 * Coordinate all of the connections for a single local destination.
 *
 *
 */
public class ConnectionManager {
    private I2PAppContext _context;
    private Log _log;
    private I2PSession _session;
    private MessageHandler _messageHandler;
    private PacketHandler _packetHandler;
    private ConnectionHandler _connectionHandler;
    private PacketQueue _outboundQueue;
    private SchedulerChooser _schedulerChooser;
    private ConnectionPacketHandler _conPacketHandler;
    /** Inbound stream ID (ByteArray) to Connection map */
    private Map _connectionByInboundId;
    /** Ping ID (ByteArray) to PingRequest */
    private Map _pendingPings;
    private boolean _allowIncoming;
    private int _maxConcurrentStreams;
    private volatile int _numWaiting;
    private Object _connectionLock;
    
    public ConnectionManager(I2PAppContext context, I2PSession session, int maxConcurrent) {
        _context = context;
        _log = context.logManager().getLog(ConnectionManager.class);
        _connectionByInboundId = new HashMap(32);
        _pendingPings = new HashMap(4);
        _connectionLock = new Object();
        _messageHandler = new MessageHandler(context, this);
        _packetHandler = new PacketHandler(context, this);
        _connectionHandler = new ConnectionHandler(context, this);
        _schedulerChooser = new SchedulerChooser(context);
        _conPacketHandler = new ConnectionPacketHandler(context);
        _session = session;
        session.setSessionListener(_messageHandler);
        _outboundQueue = new PacketQueue(context, session, this);
        _allowIncoming = false;
        _maxConcurrentStreams = maxConcurrent;
        _numWaiting = 0;
        _context.statManager().createRateStat("stream.con.lifetimeMessagesSent", "How many messages do we send on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeMessagesReceived", "How many messages do we receive on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeBytesSent", "How many bytes do we send on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeBytesReceived", "How many bytes do we receive on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesSent", "How many duplicate messages do we send on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesReceived", "How many duplicate messages do we receive on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeRTT", "What is the final RTT when a stream closes?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeCongestionSeenAt", "When was the last congestion seen at when a stream closes?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeSendWindowSize", "What is the final send window size when a stream closes?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
    }
    
    Connection getConnectionByInboundId(byte[] id) {
        synchronized (_connectionLock) {
            return (Connection)_connectionByInboundId.get(new ByteArray(id));
        }
    }
    /** 
     * not guaranteed to be unique, but in case we receive more than one packet
     * on an inbound connection that we havent ack'ed yet...
     */
    Connection getConnectionByOutboundId(byte[] id) {
        synchronized (_connectionLock) {
            for (Iterator iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
                Connection con = (Connection)iter.next();
                if (DataHelper.eq(con.getSendStreamId(), id))
                    return con;
            }
        }
        return null;
    }
    
    public void setAllowIncomingConnections(boolean allow) { 
        _connectionHandler.setActive(allow);
    }
    public boolean getAllowIncomingConnections() {
        return _connectionHandler.getActive();
    }
    
    /**
     * Create a new connection based on the SYN packet we received.
     *
     * @return created Connection with the packet's data already delivered to
     *         it, or null if the syn's streamId was already taken
     */
    public Connection receiveConnection(Packet synPacket) {
        Connection con = new Connection(_context, this, _schedulerChooser, _outboundQueue, _conPacketHandler);
        byte receiveId[] = new byte[4];
        _context.random().nextBytes(receiveId);
        boolean reject = false;
        synchronized (_connectionLock) {
            if (locked_tooManyStreams()) {
                reject = true;
            } else { 
                while (true) {
                    ByteArray ba = new ByteArray(receiveId);
                    Connection oldCon = (Connection)_connectionByInboundId.put(ba, con);
                    if (oldCon == null) {
                        break;
                    } else { 
                        _connectionByInboundId.put(ba, oldCon);
                        // receiveId already taken, try another
                        _context.random().nextBytes(receiveId);
                    }
                }
            }
        }
        
        if (reject) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Refusing connection since we have exceeded our max of " 
                          + _maxConcurrentStreams + " connections");
            PacketLocal reply = new PacketLocal(_context, synPacket.getOptionalFrom());
            reply.setFlag(Packet.FLAG_RESET);
            reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
            reply.setAckThrough(synPacket.getSequenceNum());
            reply.setSendStreamId(synPacket.getReceiveStreamId());
            reply.setReceiveStreamId(null);
            reply.setOptionalFrom(_session.getMyDestination());
            // this just sends the packet - no retries or whatnot
            _outboundQueue.enqueue(reply);
            return null;
        }
        
        con.setReceiveStreamId(receiveId);
        try {
            con.getPacketHandler().receivePacket(synPacket, con);
        } catch (I2PException ie) {
            synchronized (_connectionLock) {
                _connectionByInboundId.remove(new ByteArray(receiveId));
            }
            return null;
        }
        
        _context.statManager().addRateData("stream.connectionReceived", 1, 0);
        return con;
    }
    
    private static final long DEFAULT_STREAM_DELAY_MAX = 10*1000;
    
    /**
     * Build a new connection to the given peer.  This blocks if there is no
     * connection delay, otherwise it returns immediately.
     *
     * @return new connection, or null if we have exceeded our limit 
     */
    public Connection connect(Destination peer, ConnectionOptions opts) {
        Connection con = null;
        byte receiveId[] = new byte[4];
        _context.random().nextBytes(receiveId);
        long expiration = _context.clock().now() + opts.getConnectTimeout();
        if (opts.getConnectTimeout() <= 0)
            expiration = _context.clock().now() + DEFAULT_STREAM_DELAY_MAX;
        _numWaiting++;
        while (true) {
            long remaining = expiration - _context.clock().now();
            if (remaining <= 0) { 
                if (_log.shouldLog(Log.WARN))
                _log.warn("Refusing to connect since we have exceeded our max of " 
                          + _maxConcurrentStreams + " connections");
                _numWaiting--;
                return null;
            }
            boolean reject = false;
            synchronized (_connectionLock) {
                if (locked_tooManyStreams()) {
                    // allow a full buffer of pending/waiting streams
                    if (_numWaiting > _maxConcurrentStreams) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Refusing connection since we have exceeded our max of "
                                      + _maxConcurrentStreams + " and there are " + _numWaiting
                                      + " waiting already");
                        _numWaiting--;
                        return null;
                    }
                    
                    // no remaining streams, lets wait a bit
                    try { _connectionLock.wait(remaining); } catch (InterruptedException ie) {}
                } else { 
                    con = new Connection(_context, this, _schedulerChooser, _outboundQueue, _conPacketHandler, opts);
                    con.setRemotePeer(peer);
            
                    ByteArray ba = new ByteArray(receiveId);
                    while (_connectionByInboundId.containsKey(ba)) {
                        _context.random().nextBytes(receiveId);
                    }
                    _connectionByInboundId.put(ba, con);
                    break; // stop looping as a psuedo-wait
                }
            }
        }

        // ok we're in...
        con.setReceiveStreamId(receiveId);        
        con.eventOccurred();
        
        _log.debug("Connect() conDelay = " + opts.getConnectDelay());
        if (opts.getConnectDelay() <= 0) {
            con.waitForConnect();
        }
        if (_numWaiting > 0)
            _numWaiting--;
        
        _context.statManager().addRateData("stream.connectionCreated", 1, 0);
        return con;
    }

    private boolean locked_tooManyStreams() {
        int active = 0;
        for (Iterator iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
            Connection con = (Connection)iter.next();
            if (con.getIsConnected())
                active++;
        }
        
        if ( (_connectionByInboundId.size() > 100) && (_log.shouldLog(Log.INFO)) )
            _log.info("More than 100 connections!  " + active
                      + " total: " + _connectionByInboundId.size());

        if (_maxConcurrentStreams <= 0) return false;
        if (_connectionByInboundId.size() < _maxConcurrentStreams) return false;
        return (active >= _maxConcurrentStreams);
    }
    
    public MessageHandler getMessageHandler() { return _messageHandler; }
    public PacketHandler getPacketHandler() { return _packetHandler; }
    public ConnectionHandler getConnectionHandler() { return _connectionHandler; }
    public I2PSession getSession() { return _session; }
    public PacketQueue getPacketQueue() { return _outboundQueue; }
    
    /**
     * Something b0rked hard, so kill all of our connections without mercy.
     * Don't bother sending close packets.
     *
     */
    public void disconnectAllHard() {
        synchronized (_connectionLock) {
            for (Iterator iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
                Connection con = (Connection)iter.next();
                con.disconnect(false, false);
            }
            _connectionByInboundId.clear();
            _connectionLock.notifyAll();
        }
    }
    
    public void removeConnection(Connection con) {
        boolean removed = false;
        synchronized (_connectionLock) {
            Object o = _connectionByInboundId.remove(new ByteArray(con.getReceiveStreamId()));
            removed = (o == con);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Connection removed? " + removed + " remaining: " 
                           + _connectionByInboundId.size() + ": " + con);
            if (!removed && _log.shouldLog(Log.DEBUG))
                _log.debug("Failed to remove " + con +"\n" + _connectionByInboundId.values());
            _connectionLock.notifyAll();
        }
        if (removed) {
            _context.statManager().addRateData("stream.con.lifetimeMessagesSent", con.getLastSendId(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeMessagesReceived", con.getHighestAckedThrough(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeBytesSent", con.getLifetimeBytesSent(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeBytesReceived", con.getLifetimeBytesReceived(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeDupMessagesSent", con.getLifetimeDupMessagesSent(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeDupMessagesReceived", con.getLifetimeDupMessagesReceived(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeRTT", con.getOptions().getRTT(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeCongestionSeenAt", con.getLastCongestionSeenAt(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeSendWindowSize", con.getOptions().getWindowSize(), con.getLifetime());
        }
    }
    
    public Set listConnections() {
        synchronized (_connectionLock) {
            return new HashSet(_connectionByInboundId.values());
        }
    }
    
    public boolean ping(Destination peer, long timeoutMs) {
        return ping(peer, timeoutMs, true);
    }
    public boolean ping(Destination peer, long timeoutMs, boolean blocking) {
        return ping(peer, timeoutMs, blocking, null, null, null);
    }
    public boolean ping(Destination peer, long timeoutMs, boolean blocking, SessionKey keyToUse, Set tagsToSend, PingNotifier notifier) {
        byte id[] = new byte[4];
        _context.random().nextBytes(id);
        ByteArray ba = new ByteArray(id);
        PacketLocal packet = new PacketLocal(_context, peer);
        packet.setSendStreamId(id);
        packet.setFlag(Packet.FLAG_ECHO);
        packet.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        packet.setOptionalFrom(_session.getMyDestination());
        if ( (keyToUse != null) && (tagsToSend != null) ) {
            packet.setKeyUsed(keyToUse);
            packet.setTagsSent(tagsToSend);
        }
        
        PingRequest req = new PingRequest(peer, packet, notifier);
        
        synchronized (_pendingPings) {
            _pendingPings.put(ba, req);
        }
        
        _outboundQueue.enqueue(packet);
        
        if (blocking) {
            synchronized (req) {
                if (!req.pongReceived())
                    try { req.wait(timeoutMs); } catch (InterruptedException ie) {}
            }
            
            synchronized (_pendingPings) {
                _pendingPings.remove(ba);
            }
        } else {
            SimpleTimer.getInstance().addEvent(new PingFailed(ba, notifier), timeoutMs);
        }
        
        boolean ok = req.pongReceived();
        return ok;
    }

    interface PingNotifier {
        public void pingComplete(boolean ok);
    }
    
    private class PingFailed implements SimpleTimer.TimedEvent {
        private ByteArray _ba;
        private PingNotifier _notifier;
        public PingFailed(ByteArray ba, PingNotifier notifier) { 
            _ba = ba; 
            _notifier = notifier;
        }
        
        public void timeReached() {
            boolean removed = false;
            synchronized (_pendingPings) {
                Object o = _pendingPings.remove(_ba);
                if (o != null)
                    removed = true;
            }
            if (removed) {
                if (_notifier != null)
                    _notifier.pingComplete(false);
                _log.error("Ping failed");
            }
        }
    }
    
    private class PingRequest {
        private boolean _ponged;
        private Destination _peer;
        private PacketLocal _packet;
        private PingNotifier _notifier;
        public PingRequest(Destination peer, PacketLocal packet, PingNotifier notifier) { 
            _ponged = false; 
            _peer = peer;
            _packet = packet;
            _notifier = notifier;
        }
        public void pong() { 
            _log.debug("Ping successful");
            _context.sessionKeyManager().tagsDelivered(_peer.getPublicKey(), _packet.getKeyUsed(), _packet.getTagsSent());
            synchronized (ConnectionManager.PingRequest.this) {
                _ponged = true; 
                ConnectionManager.PingRequest.this.notifyAll();
            }
            if (_notifier != null)
                _notifier.pingComplete(true);
        }
        public boolean pongReceived() { return _ponged; }
    }
    
    void receivePong(byte pingId[]) {
        ByteArray ba = new ByteArray(pingId);
        PingRequest req = null;
        synchronized (_pendingPings) {
            req = (PingRequest)_pendingPings.remove(ba);
        }
        if (req != null) 
            req.pong();
    }
}
