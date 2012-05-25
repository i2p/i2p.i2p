package net.i2p.client.streaming;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

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
    private TCBShare _tcbShare;
    /** Inbound stream ID (Long) to Connection map */
    private Map _connectionByInboundId;
    /** Ping ID (Long) to PingRequest */
    private final Map _pendingPings;
    private boolean _allowIncoming;
    private int _maxConcurrentStreams;
    private ConnectionOptions _defaultOptions;
    private volatile int _numWaiting;
    private final Object _connectionLock;
    private long SoTimeout;
    
    public ConnectionManager(I2PAppContext context, I2PSession session, int maxConcurrent, ConnectionOptions defaultOptions) {
        _context = context;
        _session = session;
        _maxConcurrentStreams = maxConcurrent;
        _defaultOptions = defaultOptions;
        _log = _context.logManager().getLog(ConnectionManager.class);
        _connectionByInboundId = new HashMap(32);
        _pendingPings = new HashMap(4);
        _connectionLock = new Object();
        _messageHandler = new MessageHandler(_context, this);
        _packetHandler = new PacketHandler(_context, this);
        _connectionHandler = new ConnectionHandler(_context, this);
        _schedulerChooser = new SchedulerChooser(_context);
        _conPacketHandler = new ConnectionPacketHandler(_context);
        _tcbShare = new TCBShare(_context);
        _session.setSessionListener(_messageHandler);
        _outboundQueue = new PacketQueue(_context, _session, this);
        _allowIncoming = false;
        _numWaiting = 0;
        /** Socket timeout for accept() */
        SoTimeout = -1;

        _context.statManager().createRateStat("stream.con.lifetimeMessagesSent", "How many messages do we send on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeMessagesReceived", "How many messages do we receive on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeBytesSent", "How many bytes do we send on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeBytesReceived", "How many bytes do we receive on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesSent", "How many duplicate messages do we send on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesReceived", "How many duplicate messages do we receive on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeRTT", "What is the final RTT when a stream closes?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeCongestionSeenAt", "When was the last congestion seen at when a stream closes?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeSendWindowSize", "What is the final send window size when a stream closes?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.receiveActive", "How many streams are active when a new one is received (period being not yet dropped)", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
    }
    
    Connection getConnectionByInboundId(long id) {
        synchronized (_connectionLock) {
            return (Connection)_connectionByInboundId.get(new Long(id));
        }
    }
    /** 
     * not guaranteed to be unique, but in case we receive more than one packet
     * on an inbound connection that we havent ack'ed yet...
     */
    Connection getConnectionByOutboundId(long id) {
        synchronized (_connectionLock) {
            for (Iterator iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
                Connection con = (Connection)iter.next();
                if (DataHelper.eq(con.getSendStreamId(), id))
                    return con;
            }
        }
        return null;
    }
    
    /**
     * Set the socket accept() timeout.
     * @param x
     */
    public void MsetSoTimeout(long x) {
        SoTimeout = x;
    }

    /**
     * Get the socket accept() timeout.
     * @return accept timeout in ms.
     */
    public long MgetSoTimeout() {
        return SoTimeout;
    }

    public void setAllowIncomingConnections(boolean allow) { 
        _connectionHandler.setActive(allow);
    }
    /** @return if we should accept connections */
    public boolean getAllowIncomingConnections() {
        return _connectionHandler.getActive();
    }
    
    /**
     * Create a new connection based on the SYN packet we received.
     *
     * @param synPacket SYN packet to process
     * @return created Connection with the packet's data already delivered to
     *         it, or null if the syn's streamId was already taken
     */
    public Connection receiveConnection(Packet synPacket) {
        Connection con = new Connection(_context, this, _schedulerChooser, _outboundQueue, _conPacketHandler, new ConnectionOptions(_defaultOptions));
        _tcbShare.updateOptsFromShare(con);
        con.setInbound();
        long receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
        boolean reject = false;
        int active = 0;
        int total = 0;
        synchronized (_connectionLock) {
            total = _connectionByInboundId.size();
            for (Iterator iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
                if ( ((Connection)iter.next()).getIsConnected() )
                    active++;
            }
            if (locked_tooManyStreams()) {
                reject = true;
            } else { 
                while (true) {
                    Connection oldCon = (Connection)_connectionByInboundId.put(new Long(receiveId), con);
                    if (oldCon == null) {
                        break;
                    } else { 
                        _connectionByInboundId.put(new Long(receiveId), oldCon);
                        // receiveId already taken, try another
                        receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
                    }
                }
            }
        }
        
        _context.statManager().addRateData("stream.receiveActive", active, total);
        
        if (reject) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Refusing connection since we have exceeded our max of " 
                          + _maxConcurrentStreams + " connections");
            PacketLocal reply = new PacketLocal(_context, synPacket.getOptionalFrom());
            reply.setFlag(Packet.FLAG_RESET);
            reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
            reply.setAckThrough(synPacket.getSequenceNum());
            reply.setSendStreamId(synPacket.getReceiveStreamId());
            reply.setReceiveStreamId(0);
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
                _connectionByInboundId.remove(new Long(receiveId));
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
     * @param peer Destination to contact
     * @param opts Connection's options
     * @return new connection, or null if we have exceeded our limit
     */
    public Connection connect(Destination peer, ConnectionOptions opts) {
        Connection con = null;
        long receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
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
            
                    while (_connectionByInboundId.containsKey(new Long(receiveId))) {
                        receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
                    }
                    _connectionByInboundId.put(new Long(receiveId), con);
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
        if (_maxConcurrentStreams <= 0) return false;
        if (_connectionByInboundId.size() < _maxConcurrentStreams) return false;
        int active = 0;
        for (Iterator iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
            Connection con = (Connection)iter.next();
            if (con.getIsConnected())
                active++;
        }
        
        if ( (_connectionByInboundId.size() > 100) && (_log.shouldLog(Log.INFO)) )
            _log.info("More than 100 connections!  " + active
                      + " total: " + _connectionByInboundId.size());

        return (active >= _maxConcurrentStreams);
    }
    
    public MessageHandler getMessageHandler() { return _messageHandler; }
    public PacketHandler getPacketHandler() { return _packetHandler; }
    public I2PSession getSession() { return _session; }
    public void updateOptsFromShare(Connection con) { _tcbShare.updateOptsFromShare(con); }
    public void updateShareOpts(Connection con) { _tcbShare.updateShareOpts(con); }
    // Both of these methods are 
    // exporting non-public type through public API, this is a potential bug.
    public ConnectionHandler getConnectionHandler() { return _connectionHandler; }
    public PacketQueue getPacketQueue() { return _outboundQueue; }
    /** do we respond to pings that aren't on an existing connection? */
    public boolean answerPings() { return _defaultOptions.getAnswerPings(); }
    
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
        _tcbShare.stop();
    }
    
    /**
     * Drop the (already closed) connection on the floor.
     *
     * @param con Connection to drop.
     */
    public void removeConnection(Connection con) {
        boolean removed = false;
        synchronized (_connectionLock) {
            Object o = _connectionByInboundId.remove(new Long(con.getReceiveStreamId()));
            removed = (o == con);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Connection removed? " + removed + " remaining: " 
                           + _connectionByInboundId.size() + ": " + con);
            if (!removed && _log.shouldLog(Log.DEBUG))
                _log.debug("Failed to remove " + con +"\n" + _connectionByInboundId.values());
            _connectionLock.notifyAll();
        }
        if (removed) {
            _context.statManager().addRateData("stream.con.lifetimeMessagesSent", 1+con.getLastSendId(), con.getLifetime());
            MessageInputStream stream = con.getInputStream();
            if (stream != null) {
                long rcvd = 1 + stream.getHighestBlockId();
                long nacks[] = stream.getNacks();
                if (nacks != null)
                    rcvd -= nacks.length;
                _context.statManager().addRateData("stream.con.lifetimeMessagesReceived", rcvd, con.getLifetime());
            }
            _context.statManager().addRateData("stream.con.lifetimeBytesSent", con.getLifetimeBytesSent(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeBytesReceived", con.getLifetimeBytesReceived(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeDupMessagesSent", con.getLifetimeDupMessagesSent(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeDupMessagesReceived", con.getLifetimeDupMessagesReceived(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeRTT", con.getOptions().getRTT(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeCongestionSeenAt", con.getLastCongestionSeenAt(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeSendWindowSize", con.getOptions().getWindowSize(), con.getLifetime());
        }
    }
    
    /** return a set of Connection objects
     * @return set of Connection objects
     */
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
        Long id = new Long(_context.random().nextLong(Packet.MAX_STREAM_ID-1)+1);
        PacketLocal packet = new PacketLocal(_context, peer);
        packet.setSendStreamId(id.longValue());
        packet.setFlag(Packet.FLAG_ECHO);
        packet.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        packet.setOptionalFrom(_session.getMyDestination());
        if ( (keyToUse != null) && (tagsToSend != null) ) {
            packet.setKeyUsed(keyToUse);
            packet.setTagsSent(tagsToSend);
        }
        
        PingRequest req = new PingRequest(peer, packet, notifier);
        
        synchronized (_pendingPings) {
            _pendingPings.put(id, req);
        }
        
        _outboundQueue.enqueue(packet);
        packet.releasePayload();
        
        if (blocking) {
            synchronized (req) {
                if (!req.pongReceived())
                    try { req.wait(timeoutMs); } catch (InterruptedException ie) {}
            }
            
            synchronized (_pendingPings) {
                _pendingPings.remove(id);
            }
        } else {
            SimpleTimer.getInstance().addEvent(new PingFailed(id, notifier), timeoutMs);
        }
        
        boolean ok = req.pongReceived();
        return ok;
    }

    public interface PingNotifier {
        public void pingComplete(boolean ok);
    }
    
    private class PingFailed implements SimpleTimer.TimedEvent {
        private Long _id;
        private PingNotifier _notifier;
        public PingFailed(Long id, PingNotifier notifier) { 
            _id = id;
            _notifier = notifier;
        }
        
        public void timeReached() {
            boolean removed = false;
            synchronized (_pendingPings) {
                Object o = _pendingPings.remove(_id);
                if (o != null)
                    removed = true;
            }
            if (removed) {
                if (_notifier != null)
                    _notifier.pingComplete(false);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Ping failed");
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
    
    void receivePong(long pingId) {
        PingRequest req = null;
        synchronized (_pendingPings) {
            req = (PingRequest)_pendingPings.remove(new Long(pingId));
        }
        if (req != null) 
            req.pong();
    }
}
