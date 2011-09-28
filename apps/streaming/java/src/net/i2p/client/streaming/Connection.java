package net.i2p.client.streaming;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

/**
 * Maintain the state controlling a streaming connection between two 
 * destinations.
 *
 */
class Connection {
    private final I2PAppContext _context;
    private final Log _log;
    private final ConnectionManager _connectionManager;
    private Destination _remotePeer;
    private long _sendStreamId;
    private long _receiveStreamId;
    private long _lastSendTime;
    private final AtomicLong _lastSendId;
    private boolean _resetReceived;
    private boolean _resetSent;
    private long _resetSentOn;
    private boolean _connected;
    private boolean _hardDisconnected;
    private final MessageInputStream _inputStream;
    private final MessageOutputStream _outputStream;
    private final SchedulerChooser _chooser;
    private long _nextSendTime;
    private long _ackedPackets;
    private final long _createdOn;
    private long _closeSentOn;
    private long _closeReceivedOn;
    private int _unackedPacketsReceived;
    private long _congestionWindowEnd;
    private long _highestAckedThrough;
    private boolean _isInbound;
    private boolean _updatedShareOpts;
    /** Packet ID (Long) to PacketLocal for sent but unacked packets */
    private final Map<Long, PacketLocal> _outboundPackets;
    private final PacketQueue _outboundQueue;
    private final ConnectionPacketHandler _handler;
    private ConnectionOptions _options;
    private final ConnectionDataReceiver _receiver;
    private I2PSocketFull _socket;
    /** set to an error cause if the connection could not be established */
    private String _connectionError;
    private long _disconnectScheduledOn;
    private long _lastReceivedOn;
    private ActivityTimer _activityTimer;
    /** window size when we last saw congestion */
    private int _lastCongestionSeenAt;
    private long _lastCongestionTime;
    private long _lastCongestionHighestUnacked;
    private boolean _ackSinceCongestion;
    /** Notify this on connection (or connection failure) */
    private final Object _connectLock;
    /** how many messages have been resent and not yet ACKed? */
    private int _activeResends;
    private final ConEvent _connectionEvent;
    private final int _randomWait;
    private int _localPort;
    private int _remotePort;
    
    private long _lifetimeBytesSent;
    private long _lifetimeBytesReceived;
    private long _lifetimeDupMessageSent;
    private long _lifetimeDupMessageReceived;
    
    public static final long MAX_RESEND_DELAY = 45*1000;
    public static final long MIN_RESEND_DELAY = 2*1000;

    /** wait up to 5 minutes after disconnection so we can ack/close packets */
    public static final int DISCONNECT_TIMEOUT = 5*60*1000;
    
    public static final int MAX_WINDOW_SIZE = 128;
    
    public Connection(I2PAppContext ctx, ConnectionManager manager, SchedulerChooser chooser,
                      PacketQueue queue, ConnectionPacketHandler handler) {
        this(ctx, manager, chooser, queue, handler, null);
    }

    public Connection(I2PAppContext ctx, ConnectionManager manager, SchedulerChooser chooser,
                      PacketQueue queue, ConnectionPacketHandler handler, ConnectionOptions opts) {
        _context = ctx;
        _connectionManager = manager;
        _chooser = chooser;
        _outboundQueue = queue;
        _handler = handler;
        _log = _context.logManager().getLog(Connection.class);
        _receiver = new ConnectionDataReceiver(_context, this);
        _inputStream = new MessageInputStream(_context);
        // FIXME pass through a passive flush delay setting as the 4th arg
        _outputStream = new MessageOutputStream(_context, _receiver, (opts == null ? Packet.MAX_PAYLOAD_SIZE : opts.getMaxMessageSize()));
        _outboundPackets = new TreeMap();
        if (opts != null) {
            _localPort = opts.getLocalPort();
            _remotePort = opts.getPort();
        }
        _options = (opts != null ? opts : new ConnectionOptions());
        _outputStream.setWriteTimeout((int)_options.getWriteTimeout());
        _inputStream.setReadTimeout((int)_options.getReadTimeout());
        _lastSendId = new AtomicLong(-1);
        _nextSendTime = -1;
        _createdOn = _context.clock().now();
        _closeSentOn = -1;
        _closeReceivedOn = -1;
        _congestionWindowEnd = _options.getWindowSize()-1;
        _highestAckedThrough = -1;
        _lastCongestionSeenAt = MAX_WINDOW_SIZE*2; // lets allow it to grow
        _lastCongestionTime = -1;
        _lastCongestionHighestUnacked = -1;
        _connected = true;
        _disconnectScheduledOn = -1;
        _lastReceivedOn = -1;
        _activityTimer = new ActivityTimer();
        _ackSinceCongestion = true;
        _connectLock = new Object();
        _resetSentOn = -1;
        _connectionEvent = new ConEvent();
        _randomWait = _context.random().nextInt(10*1000); // just do this once to reduce usage
        _context.statManager().createRateStat("stream.con.windowSizeAtCongestion", "How large was our send window when we send a dup?", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.chokeSizeBegin", "How many messages were outstanding when we started to choke?", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.chokeSizeEnd", "How many messages were outstanding when we stopped being choked?", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.fastRetransmit", "How long a packet has been around for if it has been resent per the fast retransmit timer?", "Stream", new long[] { 60*1000, 10*60*1000 });
        if (_log.shouldLog(Log.INFO))
            _log.info("New connection created with options: " + _options);
    }
    
    public long getNextOutboundPacketNum() { 
        return _lastSendId.incrementAndGet();
    }
    
    void closeReceived() {
        setCloseReceivedOn(_context.clock().now());
        _inputStream.closeReceived();
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    
    /**
     * This doesn't "send a choke". Rather, it blocks if the outbound window is full,
     * thus choking the sender that calls this.
     *
     * Block until there is an open outbound packet slot or the write timeout 
     * expires.  
     * PacketLocal is the only caller, generally with -1.
     *
     * @param timeoutMs 0 or negative means wait forever, 5 minutes max
     * @return true if the packet should be sent, false for a fatal error
     *         will return false after 5 minutes even if timeoutMs is <= 0.
     */
    boolean packetSendChoke(long timeoutMs) {
        // if (false) return true; // <--- what the fuck??
        long start = _context.clock().now();
        long writeExpire = start + timeoutMs;  // only used if timeoutMs > 0
        boolean started = false;
        while (true) {
            long timeLeft = writeExpire - _context.clock().now();
            synchronized (_outboundPackets) {
                if (!started)
                    _context.statManager().addRateData("stream.chokeSizeBegin", _outboundPackets.size(), timeoutMs);
                if (start + 5*60*1000 < _context.clock().now()) // ok, 5 minutes blocking?  I dont think so
                    return false;
                
                // no need to wait until the other side has ACKed us before sending the first few wsize
                // packets through
		// Incorrect assumption, the constructor defaults _connected to true --Sponge
                    if (!_connected)
                       return false;
                started = true;
                if ( (_outboundPackets.size() >= _options.getWindowSize()) || (_activeResends > 0) ||
                     (_lastSendId.get() - _highestAckedThrough > _options.getWindowSize()) ) {
                    if (timeoutMs > 0) {
                        if (timeLeft <= 0) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Outbound window is full of " + _outboundPackets.size() 
                                          + " with " + _activeResends + " active resends"
                                          + " and we've waited too long (" + (0-(timeLeft - timeoutMs)) + "ms): " 
                                          + toString());
                            return false;
                        }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound window is full (" + _outboundPackets.size() + "/" + _options.getWindowSize() + "/" 
                                       + _activeResends + "), waiting " + timeLeft);
                        try { _outboundPackets.wait(Math.min(timeLeft,250l)); } catch (InterruptedException ie) { if (_log.shouldLog(Log.DEBUG)) _log.debug("InterruptedException while Outbound window is full (" + _outboundPackets.size() + "/" + _activeResends +")"); return false;}
                    } else {
                        //if (_log.shouldLog(Log.DEBUG))
                        //    _log.debug("Outbound window is full (" + _outboundPackets.size() + "/" + _activeResends 
                        //               + "), waiting indefinitely");
                        try { _outboundPackets.wait(250); } catch (InterruptedException ie) {if (_log.shouldLog(Log.DEBUG)) _log.debug("InterruptedException while Outbound window is full (" + _outboundPackets.size() + "/" + _activeResends + ")"); return false;} //10*1000
                    }
                } else {
                    _context.statManager().addRateData("stream.chokeSizeEnd", _outboundPackets.size(), _context.clock().now() - start);
                    return true;
                }
            }
        }
    }
    void windowAdjusted() {
        synchronized (_outboundPackets) {
            _outboundPackets.notifyAll();
        }
    }
    
    void ackImmediately() {
        PacketLocal packet = null;
        synchronized (_outboundPackets) {
            if (!_outboundPackets.isEmpty()) {
                // ordered, so pick the lowest to retransmit
                Iterator<PacketLocal> iter = _outboundPackets.values().iterator();
                packet = iter.next();
                //iter.remove();
            }
        }
        if (packet != null) {
            if (packet.isFlagSet(Packet.FLAG_RESET)) {
                // sendReset takes care to prevent too-frequent RSET transmissions
                sendReset();
                return;
            }
            ResendPacketEvent evt = (ResendPacketEvent)packet.getResendEvent();
            if (evt != null) {
                boolean sent = evt.retransmit(false);
                if (sent) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Retransmitting " + packet + " as an ack");
                    return;
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Not retransmitting " + packet + " as an ack");
                    //SimpleTimer.getInstance().addEvent(evt, evt.getNextSendTime());
                }
            }
        }
        // if we don't have anything to retransmit, send a small ack
        packet = _receiver.send(null, 0, 0);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending new ack: " + packet);
        //packet.releasePayload();
    }

    /**
     * got a packet we shouldn't have, send 'em a reset
     *
     */
    void sendReset() {
        if (_disconnectScheduledOn < 0) {
            _disconnectScheduledOn = _context.clock().now();
            SimpleScheduler.getInstance().addEvent(new DisconnectEvent(), DISCONNECT_TIMEOUT);
        }
        long now = _context.clock().now();
        if (_resetSentOn + 10*1000 > now) return; // don't send resets too fast
        if (_resetReceived) return;
        _resetSent = true;
        if (_resetSentOn <= 0)
            _resetSentOn = now;
        if ( (_remotePeer == null) || (_sendStreamId <= 0) ) return;
        PacketLocal reply = new PacketLocal(_context, _remotePeer);
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setSendStreamId(_sendStreamId);
        reply.setReceiveStreamId(_receiveStreamId);
        reply.setOptionalFrom(_connectionManager.getSession().getMyDestination());
        // this just sends the packet - no retries or whatnot
        _outboundQueue.enqueue(reply);
    }
    
    /**
     * Flush any data that we can
     */
    void sendAvailable() {
        // this grabs the data, builds a packet, and queues it up via sendPacket
        try {
            _outputStream.flushAvailable(_receiver, false);
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error flushing available", ioe);
        }
    }
    
    void sendPacket(PacketLocal packet) {
        if (packet == null) return;
        
        setNextSendTime(-1);
        _unackedPacketsReceived = 0;
        if (_options.getRequireFullySigned()) {
            packet.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
            packet.setFlag(Packet.FLAG_SIGNATURE_REQUESTED);
        }
        
        boolean ackOnly = false;
                
        if ( (packet.getSequenceNum() == 0) && (!packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) ) {
            ackOnly = true;
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("No resend for " + packet);
        } else {
            int windowSize;
            int remaining;
            synchronized (_outboundPackets) {
                _outboundPackets.put(Long.valueOf(packet.getSequenceNum()), packet);
                windowSize = _options.getWindowSize();
                remaining = windowSize - _outboundPackets.size() ;
                _outboundPackets.notifyAll();
            }
            // the other end has no idea what our window size is, so
            // help him out by requesting acks below the 1/3 point,
            // if remaining < 3, and every 8 minimum.
            if (packet.isFlagSet(Packet.FLAG_CLOSE) ||
                (remaining < (windowSize + 2) / 3) ||
                (remaining < 3) ||
                (packet.getSequenceNum() % 8 == 0)) {
                packet.setOptionalDelay(0);
                packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Requesting no ack delay for packet " + packet);
            } else {
                // This is somewhat of a waste of time, unless the RTT < 4000,
                // since the other end limits it to getSendAckDelay()
                // which is always 2000, but it's good for diagnostics to see what the other end thinks
                // the RTT is.
                int delay = _options.getRTT() / 2;
                packet.setOptionalDelay(delay);
                if (delay > 0)
                    packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Requesting ack delay of " + delay + "ms for packet " + packet);
            }
            // WHY always set?
            packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            
            long timeout = _options.getRTO();
            if (timeout > MAX_RESEND_DELAY)
                timeout = MAX_RESEND_DELAY;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Resend in " + timeout + " for " + packet);

            // schedules itself
            new ResendPacketEvent(packet, timeout);
        }

        // warning, getStatLog() can be null
        //_context.statManager().getStatLog().addData(Packet.toId(_sendStreamId), "stream.rtt", _options.getRTT(), _options.getWindowSize());
        
        _lastSendTime = _context.clock().now();
        _outboundQueue.enqueue(packet);        
        resetActivityTimer();
        
        /*
        if (ackOnly) {
            // ACK only, don't schedule this packet for retries
            // however, if we are running low on sessionTags we want to send
            // something that will get a reply so that we can deliver some new tags -
            // ACKs don't get ACKed, but pings do.
            if ( (packet.getTagsSent() != null) && (packet.getTagsSent().size() > 0) ) {
                _log.warn("Sending a ping since the ACK we just sent has " + packet.getTagsSent().size() + " tags");
                _connectionManager.ping(_remotePeer, _options.getRTT()*2, false, packet.getKeyUsed(), packet.getTagsSent(), new PingNotifier());
            }
        }
         */
    }
    
/*********
    private class PingNotifier implements ConnectionManager.PingNotifier {
        private long _startedPingOn;
        public PingNotifier() {
            _startedPingOn = _context.clock().now();
        }
        public void pingComplete(boolean ok) {
            long time = _context.clock().now()-_startedPingOn;
            if (ok)
                _options.updateRTT((int)time);
            else
                _options.updateRTT((int)time*2);
        }
    }
*********/
    
    /**
     *  Process the acks and nacks received in a packet
     *  @return List of packets acked or null
     */
    List<PacketLocal> ackPackets(long ackThrough, long nacks[]) {
        if (ackThrough < _highestAckedThrough) {
            // dupack which won't tell us anything
        } else {
           if (nacks == null) {
                _highestAckedThrough = ackThrough;
            } else {
                long lowest = -1;
                for (int i = 0; i < nacks.length; i++) {
                    if ( (lowest < 0) || (nacks[i] < lowest) )
                        lowest = nacks[i];
                }
                if (lowest - 1 > _highestAckedThrough)
                    _highestAckedThrough = lowest - 1;
            }
        }
        
        List<PacketLocal> acked = null;
        synchronized (_outboundPackets) {
            for (Iterator<Long> iter = _outboundPackets.keySet().iterator(); iter.hasNext(); ) {
                Long id = iter.next();
                if (id.longValue() <= ackThrough) {
                    boolean nacked = false;
                    if (nacks != null) {
                        // linear search since its probably really tiny
                        for (int i = 0; i < nacks.length; i++) {
                            if (nacks[i] == id.longValue()) {
                                nacked = true;
                                PacketLocal nackedPacket = _outboundPackets.get(id);
                                nackedPacket.incrementNACKs();
                                break; // NACKed
                            }
                        }
                    }
                    if (!nacked) { // aka ACKed
                        if (acked == null) 
                            acked = new ArrayList(1);
                        PacketLocal ackedPacket = _outboundPackets.get(id);
                        ackedPacket.ackReceived();
                        acked.add(ackedPacket);
                    }
                } else {
                    break; // _outboundPackets is ordered
                }
            }
            if (acked != null) {
                for (int i = 0; i < acked.size(); i++) {
                    PacketLocal p = acked.get(i);
                    _outboundPackets.remove(Long.valueOf(p.getSequenceNum()));
                    _ackedPackets++;
                    if (p.getNumSends() > 1) {
                        _activeResends--;
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Active resend of " + p + " successful, # active left: " + _activeResends);
                    }
                }
            }
            if ( (_outboundPackets.isEmpty()) && (_activeResends != 0) ) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("All outbound packets acked, clearing " + _activeResends);
                _activeResends = 0;
            }
            _outboundPackets.notifyAll();
        }
        if ((acked != null) && (!acked.isEmpty()) )
            _ackSinceCongestion = true;
        return acked;
    }

    private long _occurredTime;
    private long _occurredEventCount;
    void eventOccurred() {
        long now = System.currentTimeMillis();
        
        TaskScheduler sched = _chooser.getScheduler(this);
        
        now = now - now % 1000;
        if (_occurredTime == now) {
            _occurredEventCount++;
        } else {
            _occurredTime = now;
            if ( (_occurredEventCount > 1000) && (_log.shouldLog(Log.WARN)) ) {
                _log.warn("More than 1000 events (" + _occurredEventCount + ") in a second on " 
                          + toString() + ": scheduler = " + sched);
            }
            _occurredEventCount = 0;
        }
        
        long before = System.currentTimeMillis();
            
        sched.eventOccurred(this);
        long elapsed = System.currentTimeMillis() - before;
        if ( (elapsed > 1000) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Took " + elapsed + "ms to pump through " + sched);
    }
    
    void resetReceived() {
        if (_disconnectScheduledOn < 0) {
            _disconnectScheduledOn = _context.clock().now();
            SimpleScheduler.getInstance().addEvent(new DisconnectEvent(), DISCONNECT_TIMEOUT);
        }
        _resetReceived = true;
        MessageOutputStream mos = _outputStream;
        MessageInputStream mis = _inputStream;
        if (mos != null)
            mos.streamErrorOccurred(new IOException("Reset received"));
        if (mis != null)
            mis.streamErrorOccurred(new IOException("Reset received"));
        _connectionError = "Connection reset";
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    public boolean getResetReceived() { return _resetReceived; }
    
    public void setInbound() { _isInbound = true; }
    public boolean isInbound() { return _isInbound; }
    public boolean getIsConnected() { return _connected; }
    public boolean getHardDisconnected() { return _hardDisconnected; }
    public boolean getResetSent() { return _resetSent; }
    public long getResetSentOn() { return _resetSentOn; }
    public long getDisconnectScheduledOn() { return _disconnectScheduledOn; }

    void disconnect(boolean cleanDisconnect) {
        disconnect(cleanDisconnect, true);
    }
    void disconnect(boolean cleanDisconnect, boolean removeFromConMgr) {
        synchronized (_connectLock) { _connectLock.notifyAll(); }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Disconnecting " + toString(), new Exception("discon"));
        if (!cleanDisconnect) {
            _hardDisconnected = true;
            if (_log.shouldLog(Log.WARN))
                _log.warn("Hard disconnecting and sending a reset on " + toString(), new Exception("cause"));
            sendReset();
        }
        
        if (cleanDisconnect && _connected) {
            // send close packets and schedule stuff...
            _outputStream.closeInternal();
            _inputStream.close();
        } else {
            if (_connected)
                doClose();
            killOutstandingPackets();
        }
        if (removeFromConMgr) {
            if (_disconnectScheduledOn < 0) {
                _disconnectScheduledOn = _context.clock().now();
                SimpleScheduler.getInstance().addEvent(new DisconnectEvent(), DISCONNECT_TIMEOUT);
            }
        }
        _connected = false;
    }
    
    void disconnectComplete() {
        _connected = false;
        if (_socket != null)
            _socket.destroy();
        _socket = null;
        if (_outputStream != null)
            _outputStream.destroy();
        if (_receiver != null)
            _receiver.destroy();
        if (_activityTimer != null)
            _activityTimer.cancel();
        //_activityTimer = null;
        if (_inputStream != null)
            _inputStream.streamErrorOccurred(new IOException("disconnected!"));
        
        if (_disconnectScheduledOn < 0) {
            _disconnectScheduledOn = _context.clock().now();
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Connection disconnect complete from dead, drop the con "
                          + toString());
            _connectionManager.removeConnection(this);
        }

        killOutstandingPackets();
    }
    
    /** ignore tag issues */
    private void killOutstandingPackets() {
        //boolean tagsCancelled = false;
        synchronized (_outboundPackets) {
            for (Iterator<PacketLocal> iter = _outboundPackets.values().iterator(); iter.hasNext(); ) {
                PacketLocal pl = iter.next();
                //if ( (pl.getTagsSent() != null) && (pl.getTagsSent().size() > 0) )
                //    tagsCancelled = true;
                pl.cancelled();
            }
            _outboundPackets.clear();
            _outboundPackets.notifyAll();
        }            
        //if (tagsCancelled)
        //    _context.sessionKeyManager().failTags(_remotePeer.getPublicKey());
    }
    
    private class DisconnectEvent implements SimpleTimer.TimedEvent {
        public DisconnectEvent() {
            if (_log.shouldLog(Log.INFO))
                _log.info("Connection disconnect timer initiated: 5 minutes to drop " 
                          + Connection.this.toString());
        }
        public void timeReached() {
            killOutstandingPackets();
            if (_log.shouldLog(Log.INFO))
                _log.info("Connection disconnect timer complete, drop the con "
                          + Connection.this.toString());
            _connectionManager.removeConnection(Connection.this);
        }
    }
    
    private void doClose() {
        _outputStream.streamErrorOccurred(new IOException("Hard disconnect"));
        _inputStream.closeReceived();
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    
    private boolean _remotePeerSet = false;
    /** who are we talking with
     * @return peer Destination
     */
    public Destination getRemotePeer() { return _remotePeer; }
    public void setRemotePeer(Destination peer) { 
        if (_remotePeerSet) throw new RuntimeException("Remote peer already set [" + _remotePeer + ", " + peer + "]");
        _remotePeerSet = true;
        _remotePeer = peer; 
        // now that we know who the other end is, get the rtt etc. from the cache
        _connectionManager.updateOptsFromShare(this);
    }
    
    private boolean _sendStreamIdSet = false;
    /** what stream do we send data to the peer on?
     * @return non-global stream sending ID
     */
    public long getSendStreamId() { return _sendStreamId; }
    public void setSendStreamId(long id) { 
        if (_sendStreamIdSet) throw new RuntimeException("Send stream ID already set [" + _sendStreamId + ", " + id + "]");
        _sendStreamIdSet = true;
        _sendStreamId = id; 
    }
    
    private boolean _receiveStreamIdSet = false;
    /** The stream ID of a peer connection that sends data to us. (may be null)
     * @return receive stream ID, or null if there isn't one
     */
    public long getReceiveStreamId() { return _receiveStreamId; }
    public void setReceiveStreamId(long id) { 
        if (_receiveStreamIdSet) throw new RuntimeException("Receive stream ID already set [" + _receiveStreamId + ", " + id + "]");
        _receiveStreamIdSet = true;
        _receiveStreamId = id; 
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    
    /** When did we last send anything to the peer?
     * @return Last time we sent data
     */
    public long getLastSendTime() { return _lastSendTime; }
    /** Set the time we sent data.
     * @param when The time we sent data
     */
    public void setLastSendTime(long when) { _lastSendTime = when; }
    
    /** What was the last packet Id sent to the peer?
     * @return The last sent packet ID
     */
    public long getLastSendId() { return _lastSendId.get(); }
    /** Set the packet Id that was sent to a peer.
     * @param id The packet ID
     */
    public void setLastSendId(long id) { _lastSendId.set(id); }
    
    /**
     * Retrieve the current ConnectionOptions.
     * @return the current ConnectionOptions
     */
    public ConnectionOptions getOptions() { return _options; }
    /**
     * Set the ConnectionOptions.
     * @param opts ConnectionOptions
     */
    public void setOptions(ConnectionOptions opts) { _options = opts; }
        
    public I2PSession getSession() { return _connectionManager.getSession(); }
    public I2PSocketFull getSocket() { return _socket; }
    public void setSocket(I2PSocketFull socket) { _socket = socket; }
    
    /**
     * The remote port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getPort() {
        return _remotePort;
    }

    /**
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getLocalPort() {
        return _localPort;
    }

    public String getConnectionError() { return _connectionError; }
    public void setConnectionError(String err) { _connectionError = err; }
    
    public long getLifetime() { 
        if (_closeSentOn <= 0)
            return _context.clock().now() - _createdOn; 
        else
            return _closeSentOn - _createdOn;
    }
    
    public ConnectionPacketHandler getPacketHandler() { return _handler; }
    
    public long getLifetimeBytesSent() { return _lifetimeBytesSent; }
    public long getLifetimeBytesReceived() { return _lifetimeBytesReceived; }
    public long getLifetimeDupMessagesSent() { return _lifetimeDupMessageSent; }
    public long getLifetimeDupMessagesReceived() { return _lifetimeDupMessageReceived; }
    public void incrementBytesSent(int bytes) { _lifetimeBytesSent += bytes; }
    public void incrementDupMessagesSent(int msgs) { _lifetimeDupMessageSent += msgs; }
    public void incrementBytesReceived(int bytes) { _lifetimeBytesReceived += bytes; }
    public void incrementDupMessagesReceived(int msgs) { _lifetimeDupMessageReceived += msgs; }
    
    /** 
     * Time when the scheduler next want to send a packet, or -1 if 
     * never.  This should be set when we want to send on timeout, for 
     * instance, or want to delay an ACK.
     * @return the next time the scheduler will want to send a packet, or -1 if never.
     */
    public long getNextSendTime() { return _nextSendTime; }

    /**
     *  If the next send time is currently >= 0 (i.e. not "never"),
     *  this may make the next time sooner but will not make it later.
     *  If the next send time is currently < 0 (i.e. "never"),
     *  this will set it to the time specified, but not later than
     *  options.getSendAckDelay() from now (2000 ms)
     */
    public void setNextSendTime(long when) { 
        if (_nextSendTime >= 0) {
            if (when < _nextSendTime)
                _nextSendTime = when;
        } else {
            _nextSendTime = when; 
        }

        if (_nextSendTime >= 0) {
            long max = _context.clock().now() + _options.getSendAckDelay();
            if (max < _nextSendTime)
                _nextSendTime = max;
        }
        
        //if (_log.shouldLog(Log.DEBUG) && false) {
        //    if (_nextSendTime <= 0) 
        //        _log.debug("set next send time to an unknown time", new Exception(toString()));
        //    else
        //        _log.debug("set next send time to " + (_nextSendTime-_context.clock().now()) + "ms from now", new Exception(toString()));
        //}
    }
    
    /** how many packets have we sent and the other side has ACKed?
     * @return Count of how many packets ACKed.
     */
    public long getAckedPackets() { return _ackedPackets; }
    public long getCreatedOn() { return _createdOn; }
    public long getCloseSentOn() { return _closeSentOn; }
    public void setCloseSentOn(long when) { 
        _closeSentOn = when;
        if (_disconnectScheduledOn < 0) {
            _disconnectScheduledOn = _context.clock().now();
            SimpleScheduler.getInstance().addEvent(new DisconnectEvent(), DISCONNECT_TIMEOUT);
        }
    }
    public long getCloseReceivedOn() { return _closeReceivedOn; }
    public void setCloseReceivedOn(long when) { _closeReceivedOn = when; }

    public void updateShareOpts() {
        if (_closeSentOn > 0 && !_updatedShareOpts) {
            _connectionManager.updateShareOpts(this);
            _updatedShareOpts = true;
        }
    }
    public void incrementUnackedPacketsReceived() { _unackedPacketsReceived++; }
    public int getUnackedPacketsReceived() { return _unackedPacketsReceived; }
    /** how many packets have we sent but not yet received an ACK for?
     * @return Count of packets in-flight.
     */
    public int getUnackedPacketsSent() { 
        synchronized (_outboundPackets) { 
            return _outboundPackets.size(); 
        } 
    }
    
    public long getCongestionWindowEnd() { return _congestionWindowEnd; }
    public void setCongestionWindowEnd(long endMsg) { _congestionWindowEnd = endMsg; }
    /** @return the highest outbound packet we have recieved an ack for */
    public long getHighestAckedThrough() { return _highestAckedThrough; }
    /** @deprecated unused */
    public void setHighestAckedThrough(long msgNum) { _highestAckedThrough = msgNum; }
    
    public long getLastActivityOn() {
        return (_lastSendTime > _lastReceivedOn ? _lastSendTime : _lastReceivedOn);
    }
    
    public int getLastCongestionSeenAt() { return _lastCongestionSeenAt; }

    void congestionOccurred() {
        // if we hit congestion and e.g. 5 packets are resent,
        // dont set the size to (winSize >> 4).  only set the
        if (_ackSinceCongestion) {
            _lastCongestionSeenAt = _options.getWindowSize();
            _lastCongestionTime = _context.clock().now();
            _lastCongestionHighestUnacked = _lastSendId.get();
            _ackSinceCongestion = false;
        }
    }
    
    void packetReceived() {
        _lastReceivedOn = _context.clock().now();
        resetActivityTimer();
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    
    /** 
     * wait until a connection is made or the connection fails within the 
     * timeout period, setting the error accordingly.
     */
    void waitForConnect() {
        long expiration = _context.clock().now() + _options.getConnectTimeout();
        while (true) {
            if (_connected && (_receiveStreamId > 0) && (_sendStreamId > 0) ) {
                // w00t
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("waitForConnect(): Connected and we have stream IDs");
                return;
            }
            if (_connectionError != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("waitForConnect(): connection error found: " + _connectionError);
                return;
            }
            if (!_connected) {
                _connectionError = "Connection failed";
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("waitForConnect(): not connected");
                return;
            }
            
            long timeLeft = expiration - _context.clock().now();
            if ( (timeLeft <= 0) && (_options.getConnectTimeout() > 0) ) {
                if (_connectionError == null) {
                    _connectionError = "Connection timed out";
                    disconnect(false);
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("waitForConnect(): timed out: " + _connectionError);
                return;
            }
            if (timeLeft > 60*1000)
                timeLeft = 60*1000;
            if (_options.getConnectTimeout() <= 0)
                timeLeft = 60*1000;
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("waitForConnect(): wait " + timeLeft);
            try { 
                synchronized (_connectLock) {
                    _connectLock.wait(timeLeft); 
                }
            } catch (InterruptedException ie) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("waitForConnect(): InterruptedException");
                _connectionError = "InterruptedException";
                return;
            }
        }
    }
    
    private void resetActivityTimer() {
        if (_options.getInactivityTimeout() <= 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Resetting the inactivity timer, but its gone!", new Exception("where did it go?"));
            return;
        }
        if (_activityTimer == null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Resetting the inactivity timer, but its gone!", new Exception("where did it go?"));
            return;
        }
        long howLong = _options.getInactivityTimeout();
        howLong += _randomWait; // randomize it a bit, so both sides don't do it at once
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Resetting the inactivity timer to " + howLong);
        // this will get rescheduled, and rescheduled, and rescheduled...
        _activityTimer.reschedule(howLong, false); // use the later of current and previous timeout
    }
    
    private class ActivityTimer extends SimpleTimer2.TimedEvent {
        public ActivityTimer() { 
            super(RetransmissionTimer.getInstance());
            setFuzz(5*1000); // sloppy timer, don't reschedule unless at least 5s later
        }
        public void timeReached() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Fire inactivity timer on " + Connection.this.toString());
            // uh, nothing more to do...
            if (!_connected) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Inactivity timeout reached, but we are already closed");
                return;
            }
            // we got rescheduled already
            long left = getTimeLeft();
            if (left > 0) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Inactivity timeout reached, but there is time left (" + left + ")");
                schedule(left);
                return;
            }
            // these are either going to time out or cause further rescheduling
            if (getUnackedPacketsSent() > 0) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Inactivity timeout reached, but there are unacked packets");
                return;
            }
            // wtf, this shouldn't have been scheduled
            if (_options.getInactivityTimeout() <= 0) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Inactivity timeout reached, but there is no timer...");
                return;
            }
            // if one of us can't talk...
            // No - not true - data and acks are still going back and forth.
            // Prevent zombie connections by keeping the inactivity timer.
            // Not sure why... receiving a close but never sending one?
            // If so we can probably re-enable this for _closeSentOn.
            // For further investigation...
            //if ( (_closeSentOn > 0) || (_closeReceivedOn > 0) ) {
            //    if (_log.shouldLog(Log.DEBUG)) _log.debug("Inactivity timeout reached, but we are closing");
            //    return;
            //}
            
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Inactivity timeout reached, with action=" + _options.getInactivityAction());
            
            // bugger it, might as well do the hard work now
            switch (_options.getInactivityAction()) {
                case ConnectionOptions.INACTIVITY_ACTION_NOOP:
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Inactivity timer expired, but we aint doin' shit");
                    break;
                case ConnectionOptions.INACTIVITY_ACTION_SEND:
                    if (_closeSentOn <= 0 && _closeReceivedOn <= 0) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Sending some data due to inactivity");
                        _receiver.send(null, 0, 0, true);
                        break;
                    } // else fall through
                case ConnectionOptions.INACTIVITY_ACTION_DISCONNECT:
                    // fall through
                default:
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Closing connection due to inactivity");
                    if (_log.shouldLog(Log.DEBUG)) {
                        StringBuilder buf = new StringBuilder(128);
                        buf.append("last sent was: ").append(_context.clock().now() - _lastSendTime);
                        buf.append("ms ago, last received was: ").append(_context.clock().now()-_lastReceivedOn);
                        buf.append("ms ago, inactivity timeout is: ").append(_options.getInactivityTimeout());
                        _log.debug(buf.toString());
                    }
                    
                    _inputStream.streamErrorOccurred(new IOException("Inactivity timeout"));
                    _outputStream.streamErrorOccurred(new IOException("Inactivity timeout"));
                    // Clean disconnect if we have already scheduled one
                    // (generally because we already sent a close)
                    disconnect(_disconnectScheduledOn >= 0);
                    break;
            }
        }
        
        public final long getTimeLeft() {
            if (getLastActivityOn() > 0)
                return getLastActivityOn() + _options.getInactivityTimeout() - _context.clock().now();
            else
                return _createdOn + _options.getInactivityTimeout() - _context.clock().now();
        }
    }
    
    /** stream that the local peer receives data on
     * @return the inbound message stream
     */
    public MessageInputStream getInputStream() { return _inputStream; }

    /** stream that the local peer sends data to the remote peer on
     * @return the outbound message stream
     */
    public MessageOutputStream getOutputStream() { return _outputStream; }

    @Override
    public String toString() { 
        StringBuilder buf = new StringBuilder(128);
        buf.append("[Connection ");
        if (_receiveStreamId > 0)
            buf.append(Packet.toId(_receiveStreamId));
        else
            buf.append("unknown");
        buf.append('/');
        if (_sendStreamId > 0)
            buf.append(Packet.toId(_sendStreamId));
        else
            buf.append("unknown");
        if (_isInbound)
            buf.append(" from ");
        else
            buf.append(" to ");
        if (_remotePeerSet)
            buf.append(_remotePeer.calculateHash().toBase64().substring(0,4));
        else
            buf.append("unknown");
        buf.append(" up ").append(DataHelper.formatDuration(_context.clock().now() - _createdOn));
        buf.append(" wsize: ").append(_options.getWindowSize());
        buf.append(" cwin: ").append(_congestionWindowEnd - _highestAckedThrough);
        buf.append(" rtt: ").append(_options.getRTT());
        buf.append(" rto: ").append(_options.getRTO());
        // not synchronized to avoid some kooky races
        buf.append(" unacked out: ").append(_outboundPackets.size()).append(" ");
        /*
        buf.append(" unacked outbound: ");
        synchronized (_outboundPackets) {
            buf.append(_outboundPackets.size()).append(" [");
            for (Iterator iter = _outboundPackets.keySet().iterator(); iter.hasNext(); ) {
                buf.append(((Long)iter.next()).longValue()).append(" ");
            }
            buf.append("] ");
        }
         */
        buf.append("unacked in: ").append(getUnackedPacketsReceived());
        int missing = 0;
        if (_inputStream != null) {
            long nacks[] = _inputStream.getNacks();
            if (nacks != null) {
                missing = nacks.length;
                buf.append(" [").append(missing).append(" missing]");
            }
        }
        
        if (getResetSent())
            buf.append(" reset sent ").append(DataHelper.formatDuration(_context.clock().now() - getResetSentOn())).append(" ago");
        if (getResetReceived())
            buf.append(" reset received ").append(DataHelper.formatDuration(_context.clock().now() - getDisconnectScheduledOn())).append(" ago");
        if (getCloseSentOn() > 0) {
            buf.append(" close sent ");
            long timeSinceClose = _context.clock().now() - getCloseSentOn();
            buf.append(DataHelper.formatDuration(timeSinceClose));
            buf.append(" ago");
        }
        if (getCloseReceivedOn() > 0)
            buf.append(" close received ").append(DataHelper.formatDuration(_context.clock().now() - getCloseReceivedOn())).append(" ago");
        buf.append(" sent: ").append(1 + _lastSendId.get());
        if (_inputStream != null)
            buf.append(" rcvd: ").append(1 + _inputStream.getHighestBlockId() - missing);
        
        buf.append(" maxWin ").append(getOptions().getMaxWindowSize());
        buf.append(" MTU ").append(getOptions().getMaxMessageSize());
        
        buf.append("]");
        return buf.toString();
    }

    public SimpleTimer.TimedEvent getConnectionEvent() { return _connectionEvent; }
    
    /**
     * fired to reschedule event notification
     */
    class ConEvent implements SimpleTimer.TimedEvent {
        private Exception _addedBy; // unused?
        public ConEvent() { 
            //_addedBy = new Exception("added by");
        }
        public void timeReached() {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("firing event on " + _connection, _addedBy);
            eventOccurred(); 
        }
        @Override
        public String toString() { return "event on " + Connection.this.toString(); }
    }
    
    /**
     * If we have been explicitly NACKed three times, retransmit the packet even if
     * there are other packets in flight. 3 takes forever, let's try 2.
     *
     */
    static final int FAST_RETRANSMIT_THRESHOLD = 2;
    
    /**
     * Coordinate the resends of a given packet
     */
    class ResendPacketEvent extends SimpleTimer2.TimedEvent {
        private PacketLocal _packet;
        private long _nextSendTime;
        public ResendPacketEvent(PacketLocal packet, long delay) {
            super(RetransmissionTimer.getInstance());
            _packet = packet;
            _nextSendTime = delay + _context.clock().now();
            packet.setResendPacketEvent(ResendPacketEvent.this);
            schedule(delay);
        }
        
        public long getNextSendTime() { return _nextSendTime; }
        public void timeReached() { retransmit(true); }
        /**
         * Retransmit the packet if we need to.  
         *
         * ackImmediately() above calls directly in here, so
         * we have to use forceReschedule() instead of schedule() below,
         * to prevent duplicates in the timer queue.
         *
         * @param penalize true if this retransmission is caused by a timeout, false if we
         *                 are just sending this packet instead of an ACK
         * @return true if the packet was sent, false if it was not
         */
        public boolean retransmit(boolean penalize) {
            if (_packet.getAckTime() > 0) 
                return false;
            
            if (_resetSent || _resetReceived || !_connected) {
                if(_log.shouldLog(Log.WARN) && (!_resetSent) && (!_resetReceived))
                     _log.warn("??? no resets but not connected: " + _packet); // don't think this is possible
                _packet.cancelled();
                return false;
            }
            
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Resend period reached for " + _packet);
            boolean resend = false;
            boolean isLowest = false;
            synchronized (_outboundPackets) {
                if (_packet.getSequenceNum() == _highestAckedThrough + 1)
                    isLowest = true;
                if (_outboundPackets.containsKey(Long.valueOf(_packet.getSequenceNum())))
                    resend = true;
            }
            if ( (resend) && (_packet.getAckTime() <= 0) ) {
                boolean fastRetransmit = ( (_packet.getNACKs() >= FAST_RETRANSMIT_THRESHOLD) && (_packet.getNumSends() == 1));
                if ( (!isLowest) && (!fastRetransmit) ) {
                    // we want to resend this packet, but there are already active
                    // resends in the air and we dont want to make a bad situation 
                    // worse.  wait another second
                    // BUG? seq# = 0, activeResends = 0, loop forever - why?
                    // also seen with seq# > 0. Is the _activeResends count reliable?
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Delaying resend of " + _packet + " as there are " 
                                  + _activeResends + " active resends already in play");
                    forceReschedule(1000);
                    _nextSendTime = 1000 + _context.clock().now();
                    return false;
                }
                
                if (fastRetransmit)
                    _context.statManager().addRateData("stream.fastRetransmit", _packet.getLifetime(), _packet.getLifetime());
                
                // revamp various fields, in case we need to ack more, etc
                _inputStream.updateAcks(_packet);
                int choke = getOptions().getChoke();
                _packet.setOptionalDelay(choke);
                if (choke > 0)
                    _packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                // this seems unnecessary to send the MSS again:
                _packet.setOptionalMaxSize(getOptions().getMaxMessageSize());
                // bugfix release 0.7.8, we weren't dividing by 1000
                _packet.setResendDelay(getOptions().getResendDelay() / 1000);
                if (_packet.getReceiveStreamId() <= 0)
                    _packet.setReceiveStreamId(_receiveStreamId);
                if (_packet.getSendStreamId() <= 0)
                    _packet.setSendStreamId(_sendStreamId);
                
                int newWindowSize = getOptions().getWindowSize();

                if (penalize && _ackSinceCongestion) {
                    // only shrink the window once per window
                    if (_packet.getSequenceNum() > _lastCongestionHighestUnacked) {
                        congestionOccurred();
                        _context.statManager().addRateData("stream.con.windowSizeAtCongestion", newWindowSize, _packet.getLifetime());
                        newWindowSize /= 2;
                        if (newWindowSize <= 0)
                            newWindowSize = 1;
                        
                        // setRTT has its own ceiling
                        //getOptions().setRTT(getOptions().getRTT() + 10*1000);
                        getOptions().setWindowSize(newWindowSize);

                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Congestion resending packet " + _packet.getSequenceNum() + ": new windowSize " + newWindowSize 
                                      + "/" + getOptions().getWindowSize() + ") for " + Connection.this.toString());

                        windowAdjusted();
                    }
                }
                
                int numSends = _packet.getNumSends() + 1;
                
                if (numSends == 2) {
                    // first resend for this packet
                    _activeResends++;
                }
                
                // in case things really suck, the other side may have lost thier
                // session tags (e.g. they restarted), so jump back to ElGamal.
                //int failTagsAt = _options.getMaxResends() - 2;
                //if ( (newWindowSize == 1) && (numSends == failTagsAt) ) {
                //    if (_log.shouldLog(Log.WARN))
                //        _log.warn("Optimistically failing tags at resend " + numSends);
                //    _context.sessionKeyManager().failTags(_remotePeer.getPublicKey());
                //}
                
                if (numSends - 1 > _options.getMaxResends()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Too many resends");
                    _packet.cancelled();
                    disconnect(false);
                } else {
                    //long timeout = _options.getResendDelay() << numSends;
                    long rto = _options.getRTO();
                    if (rto < MIN_RESEND_DELAY)
                        rto = MIN_RESEND_DELAY;
                    long timeout = rto << (numSends-1);
                    if ( (timeout > MAX_RESEND_DELAY) || (timeout <= 0) )
                        timeout = MAX_RESEND_DELAY;
                    // set this before enqueue() as it passes it on to the router
                    _nextSendTime = timeout + _context.clock().now();

                    if (_log.shouldLog(Log.INFO))
                        _log.info("Resend packet " + _packet + " time " + numSends + 
                                  " activeResends: " + _activeResends + 
                                  " (wsize "
                                  + newWindowSize + " lifetime " 
                                  + (_context.clock().now() - _packet.getCreatedOn()) + "ms)");
                    _outboundQueue.enqueue(_packet);
                    _lastSendTime = _context.clock().now();

                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Scheduling resend in " + timeout + "ms for " + _packet);
                    forceReschedule(timeout);
                }
                
                // acked during resending (... or somethin')
                if ( (_packet.getAckTime() > 0) && (_packet.getNumSends() > 1) ) {
                    _activeResends--;
                    synchronized (_outboundPackets) {
                        _outboundPackets.notifyAll();
                    }
                    return true;
                }

                return true;
            } else {
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Packet acked before resend (resend="+ resend + "): " 
                //               + _packet + " on " + Connection.this);
                return false;
            }
        }
    }
}
