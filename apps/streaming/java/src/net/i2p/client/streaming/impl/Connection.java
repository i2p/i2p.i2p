package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;
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
    private final I2PSession _session;
    private Destination _remotePeer;
    private final AtomicLong _sendStreamId = new AtomicLong();
    private final AtomicLong _receiveStreamId = new AtomicLong();
    private volatile long _lastSendTime;
    private final AtomicLong _lastSendId;
    private final AtomicBoolean _resetReceived = new AtomicBoolean();
    private final AtomicLong _resetSentOn = new AtomicLong();
    private final AtomicBoolean _connected = new AtomicBoolean(true);
    private final AtomicBoolean _finalDisconnect = new AtomicBoolean();
    private boolean _hardDisconnected;
    private final MessageInputStream _inputStream;
    private final MessageOutputStream _outputStream;
    private final SchedulerChooser _chooser;
    /** Locking: _nextSendLock */
    private long _nextSendTime;
    private final AtomicLong _ackedPackets = new AtomicLong();
    private final long _createdOn;
    private final AtomicLong _closeSentOn = new AtomicLong();
    private final AtomicLong _closeReceivedOn = new AtomicLong();
    private final AtomicInteger _unackedPacketsReceived = new AtomicInteger();
    private long _congestionWindowEnd;
    private volatile long _highestAckedThrough;
    private final boolean _isInbound;
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
    private final AtomicLong _disconnectScheduledOn = new AtomicLong();
    private long _lastReceivedOn;
    private final ActivityTimer _activityTimer;
    /** window size when we last saw congestion */
    private int _lastCongestionSeenAt;
    private long _lastCongestionTime;
    private volatile long _lastCongestionHighestUnacked;
    /** has the other side choked us? */
    private volatile boolean _isChoked;
    /** are we choking the other side? */
    private volatile boolean _isChoking;
    private final AtomicInteger _unchokesToSend = new AtomicInteger();
    private final AtomicBoolean _ackSinceCongestion;
    /** Notify this on connection (or connection failure) */
    private final Object _connectLock;
    /** Locking for _nextSendTime */
    private final Object _nextSendLock;
    /** how many messages have been resent and not yet ACKed? */
    private final AtomicInteger _activeResends = new AtomicInteger();
    private final ConEvent _connectionEvent;
    private final int _randomWait;
    private final int _localPort;
    private final int _remotePort;
    private final SimpleTimer2 _timer;
    
    private final AtomicLong _lifetimeBytesSent = new AtomicLong();
    /** TBD for tcpdump-compatible ack output */
    private long _lowestBytesAckedThrough;
    private final AtomicLong _lifetimeBytesReceived = new AtomicLong();
    private final AtomicLong _lifetimeDupMessageSent = new AtomicLong();
    private final AtomicLong _lifetimeDupMessageReceived = new AtomicLong();
    
    public static final long MAX_RESEND_DELAY = 45*1000;
    public static final long MIN_RESEND_DELAY = 100;

    /**
     *  Wait up to 5 minutes after disconnection so we can ack/close packets.
     *  Roughly equal to the TIME-WAIT time in RFC 793, where the recommendation is 4 minutes (2 * MSL)
     */
    public static final int DISCONNECT_TIMEOUT = 5*60*1000;
    
    public static final int DEFAULT_CONNECT_TIMEOUT = 60*1000;
    private static final long MAX_CONNECT_TIMEOUT = 2*60*1000;

    public static final int MAX_WINDOW_SIZE = 128;
    private static final int UNCHOKES_TO_SEND = 8;
    
/****
    public Connection(I2PAppContext ctx, ConnectionManager manager, SchedulerChooser chooser,
                      PacketQueue queue, ConnectionPacketHandler handler) {
        this(ctx, manager, chooser, queue, handler, null);
    }
****/

    /**
     *  @param opts may be null
     */
    public Connection(I2PAppContext ctx, ConnectionManager manager,
                      I2PSession session, SchedulerChooser chooser,
                      SimpleTimer2 timer,
                      PacketQueue queue, ConnectionPacketHandler handler, ConnectionOptions opts,
                      boolean isInbound) {
        _context = ctx;
        _connectionManager = manager;
        _session = session;
        _chooser = chooser;
        _outboundQueue = queue;
        _handler = handler;
        _isInbound = isInbound;
        _log = _context.logManager().getLog(Connection.class);
        _receiver = new ConnectionDataReceiver(_context, this);
        _options = (opts != null ? opts : new ConnectionOptions());
        _inputStream = new MessageInputStream(_context, _options.getMaxMessageSize(),
                                              _options.getMaxWindowSize(), _options.getInboundBufferSize());
        // FIXME pass through a passive flush delay setting as the 4th arg
        _outputStream = new MessageOutputStream(_context, timer, _receiver, _options.getMaxMessageSize());
        _timer = timer;
        _outboundPackets = new TreeMap<Long, PacketLocal>();
        if (opts != null) {
            _localPort = opts.getLocalPort();
            _remotePort = opts.getPort();
        } else {
            _localPort = 0;
            _remotePort = 0;
        }
        _outputStream.setWriteTimeout((int)_options.getWriteTimeout());
        _inputStream.setReadTimeout((int)_options.getReadTimeout());
        _lastSendId = new AtomicLong(-1);
        _nextSendTime = -1;
        _createdOn = _context.clock().now();
        _congestionWindowEnd = _options.getWindowSize()-1;
        _highestAckedThrough = -1;
        _lastCongestionSeenAt = MAX_WINDOW_SIZE*2; // lets allow it to grow
        _lastCongestionTime = -1;
        _lastCongestionHighestUnacked = -1;
        _lastReceivedOn = -1;
        _activityTimer = new ActivityTimer();
        _ackSinceCongestion = new AtomicBoolean(true);
        _connectLock = new Object();
        _nextSendLock = new Object();
        _connectionEvent = new ConEvent();
        _randomWait = _context.random().nextInt(10*1000); // just do this once to reduce usage
        // all createRateStats in ConnectionManager
        if (_log.shouldLog(Log.INFO))
            _log.info("New connection created with options: " + _options);
    }
    
    public long getNextOutboundPacketNum() { 
        return _lastSendId.incrementAndGet();
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
     *         will return false after 5 minutes even if timeoutMs is &lt;= 0.
     */
    public boolean packetSendChoke(long timeoutMs) throws IOException, InterruptedException {
        long start = _context.clock().now();
        long writeExpire = start + timeoutMs;  // only used if timeoutMs > 0
        boolean started = false;
        while (true) {
            long timeLeft = writeExpire - _context.clock().now();
            synchronized (_outboundPackets) {
                if (!started)
                    _context.statManager().addRateData("stream.chokeSizeBegin", _outboundPackets.size());
                if (start + 5*60*1000 < _context.clock().now()) // ok, 5 minutes blocking?  I dont think so
                    return false;
                
                // no need to wait until the other side has ACKed us before sending the first few wsize
                // packets through
		// Incorrect assumption, the constructor defaults _connected to true --Sponge
                if (!_connected.get()) {
                    if (getResetReceived())
                        throw new I2PSocketException(I2PSocketException.STATUS_CONNECTION_RESET);
                    throw new IOException("Socket closed");
                }
                if (_outputStream.getClosed())
                    throw new IOException("Output stream closed");
                started = true;
                // Try to keep things moving even during NACKs and retransmissions...
                // Limit unacked packets to the window
                // Limit active resends to half the window
                // Limit (highest-lowest) to twice the window (if far end doesn't like it, it can send a choke)
                int unacked = _outboundPackets.size();
                int wsz = _options.getWindowSize();
                if (_isChoked || unacked >= wsz ||
                    _activeResends.get() >= (wsz + 1) / 2 ||
                    _lastSendId.get() - _highestAckedThrough >= Math.max(MAX_WINDOW_SIZE, 2 * wsz)) {
                    if (timeoutMs > 0) {
                        if (timeLeft <= 0) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Outbound window is full (choked? " + _isChoked + ' ' + unacked
                                          + " unacked with " + _activeResends + " active resends"
                                          + " and we've waited too long (" + (0-(timeLeft - timeoutMs)) + "ms): " 
                                          + toString());
                            return false;
                        }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound window is full (choked? " + _isChoked + ' ' + unacked + '/' + wsz + '/' 
                                       + _activeResends + "), waiting " + timeLeft);
                        try {
                            _outboundPackets.wait(Math.min(timeLeft,250l));
                        } catch (InterruptedException ie) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("InterruptedException while Outbound window is full (" + _outboundPackets.size() + "/" + _activeResends +")");
                            throw ie;
                        }
                    } else {
                        //if (_log.shouldLog(Log.DEBUG))
                        //    _log.debug("Outbound window is full (" + _outboundPackets.size() + "/" + _activeResends 
                        //               + "), waiting indefinitely");
                        try {
                            _outboundPackets.wait(250);
                        } catch (InterruptedException ie) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("InterruptedException while Outbound window is full (" + _outboundPackets.size() + "/" + _activeResends + ")");
                            throw ie;
                        } //10*1000
                    }
                } else {
                    _context.statManager().addRateData("stream.chokeSizeEnd", _outboundPackets.size());
                    return true;
                }
            }
        }
    }

    /**
     *  Notify all threads waiting in packetSendChoke()
     */
    void windowAdjusted() {
        synchronized (_outboundPackets) {
            _outboundPackets.notifyAll();
        }
    }
    
    void ackImmediately() {
        PacketLocal packet;
/*** why would we do this?
     was it to force a congestion indication at the other end?
     an expensive way to do that...
     One big user was via SchedulerClosing to resend a CLOSE packet,
     but why do that either...

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
                // fixme should we set a flag and reschedule instead? or synch?
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
***/
        // if we don't have anything to retransmit, send a small ack
        // this calls sendPacket() below
        packet = _receiver.send(null, 0, 0);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("sending new ack: " + packet);
        //packet.releasePayload();
    }

    /**
     * Got a packet we shouldn't have, send 'em a reset.
     * More than one reset may be sent.
     */
    private void sendReset() {
        long now = _context.clock().now();
        if (_resetSentOn.get() + 10*1000 > now) return; // don't send resets too fast
        if (_resetReceived.get()) return;
        // Unconditionally set
        _resetSentOn.set(now);
        if ( (_remotePeer == null) || (_sendStreamId.get() <= 0) ) return;
        PacketLocal reply = new PacketLocal(_context, _remotePeer, this);
        reply.setFlag(Packet.FLAG_RESET);
        reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        reply.setSendStreamId(_sendStreamId.get());
        reply.setReceiveStreamId(_receiveStreamId.get());
        // TODO remove this someday, as of 0.9.20 we do not require it
        reply.setOptionalFrom();
        reply.setLocalPort(_localPort);
        reply.setRemotePort(_remotePort);
        // this just sends the packet - no retries or whatnot
        if (_outboundQueue.enqueue(reply)) {
            _unackedPacketsReceived.set(0);
            _lastSendTime = _context.clock().now();
            resetActivityTimer();
        }
    }
    
    /**
     * Flush any data that we can. Non-blocking.
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
    
    /**
     *  This sends all 'normal' packets (acks and data) for the first time.
     *  Retransmits are done in ResendPacketEvent below.
     *  Resets, pings, and pongs are done elsewhere in this class,
     *  or in ConnectionManager or ConnectionHandler.
     */
    void sendPacket(PacketLocal packet) {
        if (packet == null) return;
        
        setNextSendTime(-1);
        if (_options.getRequireFullySigned()) {
            packet.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
            packet.setFlag(Packet.FLAG_SIGNATURE_REQUESTED);
        }
        
        if ( (packet.getSequenceNum() == 0) && (!packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) ) {
            // ACK-only
            if (_isChoking) {
                packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            } else if (_unchokesToSend.decrementAndGet() > 0) {
                // don't worry about wrapping around
                packet.setOptionalDelay(0);
                packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            }
        } else {
            int windowSize;
            int remaining;
            synchronized (_outboundPackets) {
                _outboundPackets.put(Long.valueOf(packet.getSequenceNum()), packet);
                windowSize = _options.getWindowSize();
                remaining = windowSize - _outboundPackets.size() ;
                _outboundPackets.notifyAll();
            }

            if (_isChoking) {
                packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            } else if (packet.isFlagSet(Packet.FLAG_CLOSE) ||
                _unchokesToSend.decrementAndGet() > 0 ||
                // the other end has no idea what our window size is, so
                // help him out by requesting acks below the 1/3 point,
                // if remaining < 3, and every 8 minimum.
                (remaining < 3) ||
                (remaining < (windowSize + 2) / 3) /* ||
                (packet.getSequenceNum() % 8 == 0) */ ) {
                packet.setOptionalDelay(0);
                packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Requesting no ack delay for packet " + packet);
            } else {
                // This is somewhat of a waste of time, unless the RTT < 4000,
                // since the other end limits it to getSendAckDelay()
                // which is always 2000, but it's good for diagnostics to see what the other end thinks
                // the RTT is.
/**
                int delay = _options.getRTT() / 2;
                packet.setOptionalDelay(delay);
                if (delay > 0)
                    packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Requesting ack delay of " + delay + "ms for packet " + packet);
**/
            }
            
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
        
        if (_outboundQueue.enqueue(packet)) {        
            _unackedPacketsReceived.set(0);
            _lastSendTime = _context.clock().now();
            resetActivityTimer();
        }        

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
     *  @return List of packets acked for the first time, or null if none
     */
    public List<PacketLocal> ackPackets(long ackThrough, long nacks[]) {
        // FIXME synch this part too?
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
            if (!_outboundPackets.isEmpty()) {  // short circuit iterator
              for (Iterator<Map.Entry<Long, PacketLocal>> iter = _outboundPackets.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<Long, PacketLocal> e = iter.next();
                long id = e.getKey().longValue();
                if (id <= ackThrough) {
                    boolean nacked = false;
                    if (nacks != null) {
                        // linear search since its probably really tiny
                        for (int i = 0; i < nacks.length; i++) {
                            if (nacks[i] == id) {
                                nacked = true;
                                PacketLocal nackedPacket = e.getValue();
                                // this will do a fast retransmit if appropriate
                                nackedPacket.incrementNACKs();
                                break; // NACKed
                            }
                        }
                    }
                    if (!nacked) { // aka ACKed
                        if (acked == null) 
                            acked = new ArrayList<PacketLocal>(8);
                        PacketLocal ackedPacket = e.getValue();
                        ackedPacket.ackReceived();
                        acked.add(ackedPacket);
                        iter.remove();
                    }
                } else {
                    // TODO
                    // we do not currently do an "implicit nack" of the packets higher
                    // than ackThrough, so those will not be fast retransmitted
                    // we could incrementNACK them here... but we may need to set the fastRettransmit
                    // threshold back to 3 for that.
                    // this will do a fast retransmit if appropriate
                    // This doesn't work because every packet has an ACK in it, so we hit the
                    // FAST_TRANSMIT threshold in a heartbeat and retransmit everything,
                    // even with the threshold at 3. (we never set the NO_ACK field in the header)
                    // Also, we may need to track that we
                    // have the same ackThrough for 3 or 4 consecutive times.
                    // See https://secure.wikimedia.org/wikipedia/en/wiki/Fast_retransmit
                    //if (_log.shouldLog(Log.INFO))
                    //    _log.info("ACK thru " + ackThrough + " implicitly NACKs " + id);
                    //PacketLocal nackedPacket = e.getValue();
                    //nackedPacket.incrementNACKs();
                    break; // _outboundPackets is ordered
                }
              }   // for
            }   // !isEmpty()
            if (acked != null) {
                _ackedPackets.addAndGet(acked.size());
                for (int i = 0; i < acked.size(); i++) {
                    PacketLocal p = acked.get(i);
                    // removed from _outboundPackets above in iterator
                    if (p.getNumSends() > 1) {
                        _activeResends.decrementAndGet();
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Active resend of " + p + " successful, # active left: " + _activeResends);
                    }
                }
            }
            if ( (_outboundPackets.isEmpty()) && (_activeResends.get() != 0) ) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("All outbound packets acked, clearing " + _activeResends);
                _activeResends.set(0);
            }
            _outboundPackets.notifyAll();
        }
        if ((acked != null) && (!acked.isEmpty()) )
            _ackSinceCongestion.set(true);
        return acked;
    }

    //private long _occurredTime;
    //private long _occurredEventCount;

    void eventOccurred() {
        //long now = System.currentTimeMillis();
        
        TaskScheduler sched = _chooser.getScheduler(this);
        
        //now = now - now % 1000;
        //if (_occurredTime == now) {
        //    _occurredEventCount++;
        //} else {
        //    _occurredTime = now;
        //    if ( (_occurredEventCount > 1000) && (_log.shouldLog(Log.WARN)) ) {
        //        _log.warn("More than 1000 events (" + _occurredEventCount + ") in a second on " 
        //                  + toString() + ": scheduler = " + sched);
        //    }
        //    _occurredEventCount = 0;
        //}
        
        long before = System.currentTimeMillis();
            
        sched.eventOccurred(this);
        long elapsed = System.currentTimeMillis() - before;
        // 250 and warn for debugging
        if ( (elapsed > 250) && (_log.shouldLog(Log.WARN)) )
            _log.warn("Took " + elapsed + "ms to pump through " + sched + " on " + toString());
    }

    /**
     *  Notify that a close was sent.
     *  Called by CPH.
     *  May be called multiple times... but shouldn't be.
     */
    public void notifyCloseSent() { 
        if (!_closeSentOn.compareAndSet(0, _context.clock().now())) {
            // TODO ackImmediately() after sending CLOSE causes this. Bad?
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sent more than one CLOSE: " + toString());
        }
        // that's it, wait for notifyLastPacketAcked() or closeReceived()
    }
    
    /**
     *  Notify that a close was received.
     *  Called by CPH.
     *  May be called multiple times.
     */
    public void closeReceived() {
        if (_closeReceivedOn.compareAndSet(0, _context.clock().now())) {
            _inputStream.closeReceived();
            // TODO if outbound && no SYN received, treat like a reset? Could this happen?
            if (_closeSentOn.get() > 0) {
                // received after sent
                disconnect(true);
            } else {
                synchronized (_connectLock) { _connectLock.notifyAll(); }
            }
        }
    }
    
    /**
     *  Notify that a close that we sent, and all previous packets, were acked.
     *  Called by CPH. Only call this once.
     *  @since 0.9.9
     */
    public void notifyLastPacketAcked() {
        long cso = _closeSentOn.get();
        if (cso <= 0)
            throw new IllegalStateException();
        // we only create one CLOSE packet so we will only get called once,
        // no need to check
        long cro = _closeReceivedOn.get();
        if (cro > 0 && cro < cso)
            // received before sent
            disconnect(true);
    }
    
    /**
     *  Notify that a reset was received.
     *  May be called multiple times.
     */
    public void resetReceived() {
        if (!_resetReceived.compareAndSet(false, true))
            return;
        IOException ioe = new I2PSocketException(I2PSocketException.STATUS_CONNECTION_RESET);
        _outputStream.streamErrorOccurred(ioe);
        _inputStream.streamErrorOccurred(ioe);
        _connectionError = "Connection reset";
        synchronized (_connectLock) { _connectLock.notifyAll(); }
        // RFC 793 end of section 3.4: We are completely done.
        disconnectComplete();
    }

    public boolean getResetReceived() { return _resetReceived.get(); }
    
    public boolean isInbound() { return _isInbound; }

    /**
     *  Always true at the start, even if we haven't gotten a reply on an
     *  outbound connection. Only set to false on disconnect.
     *  For outbound, use getHighestAckedThrough() &gt;= 0 also,
     *  to determine if the connection is up.
     *
     *  In general, this is true until either:
     *  - CLOSE received and CLOSE sent and our CLOSE is acked
     *  - RESET received or sent
     *  - closed on the socket side
     */
    public boolean getIsConnected() { return _connected.get(); }

    public boolean getHardDisconnected() { return _hardDisconnected; }

    public boolean getResetSent() { return _resetSentOn.get() > 0; }

    /** @return 0 if not sent */
    public long getResetSentOn() { return _resetSentOn.get(); }

    /** @return 0 if not scheduled */
    public long getDisconnectScheduledOn() { return _disconnectScheduledOn.get(); }

    /**
     *  Must be called when we are done with this connection.
     *  Enters TIME-WAIT if necessary, and removes from connection manager.
     *  May be called multiple times.
     *  This closes the socket side.
     *  In normal operation, this is called when a CLOSE has been received,
     *  AND a CLOSE has been sent, AND EITHER:
     *  received close before sent close AND our CLOSE has been acked
     *  OR
     *  received close after sent close.
     *
     *  @param cleanDisconnect if true, normal close; if false, send a RESET
     */
    public void disconnect(boolean cleanDisconnect) {
        disconnect(cleanDisconnect, true);
    }

    /**
     *  Must be called when we are done with this connection.
     *  May be called multiple times.
     *  This closes the socket side.
     *  In normal operation, this is called when a CLOSE has been received,
     *  AND a CLOSE has been sent, AND EITHER:
     *  received close before sent close AND our CLOSE has been acked
     *  OR
     *  received close after sent close.
     *
     *  @param cleanDisconnect if true, normal close; if false, send a RESET
     *  @param removeFromConMgr if true, enters TIME-WAIT if necessary.
     *                          if false, MUST call disconnectComplete() later.
     *                          Should always be true unless called from ConnectionManager.
     */
    public void disconnect(boolean cleanDisconnect, boolean removeFromConMgr) {
        if (!_connected.compareAndSet(true, false)) {
            return;
        }
        synchronized (_connectLock) { _connectLock.notifyAll(); }

        if (_closeReceivedOn.get() <= 0) {
            // should have already been called from closeReceived() above
            _inputStream.closeReceived();
        }

        if (cleanDisconnect) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Clean disconnecting, remove? " + removeFromConMgr +
                           ": " + toString(), new Exception("discon"));
            _outputStream.closeInternal();
        } else {
            _hardDisconnected = true;
            if (_inputStream.getHighestBlockId() >= 0 && !getResetReceived()) {
                // only send a RESET if we ever got something (and he didn't RESET us),
                // otherwise don't waste the crypto and tags
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Hard disconnecting and sending reset, remove? " + removeFromConMgr +
                              " on " + toString(), new Exception("cause"));
                sendReset();
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Hard disconnecting, remove? " + removeFromConMgr +
                              " on " + toString(), new Exception("cause"));
            }
            _outputStream.streamErrorOccurred(new IOException("Hard disconnect"));
        }

        if (removeFromConMgr) {
            if (!cleanDisconnect) {
                disconnectComplete();
            } else {
                long cro = _closeReceivedOn.get();
                long cso = _closeSentOn.get();
                if (cro > 0 && cro < cso && getUnackedPacketsSent() <= 0) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Rcv close -> send close -> last acked, skip TIME-WAIT for " + toString());
                    // They sent the first CLOSE.
                    // We do not need to enter TIME-WAIT, we are done.
                    // clean disconnect, don't schedule TIME-WAIT
                    // remove conn
                    disconnectComplete();
                } else {
                    scheduleDisconnectEvent();
                }
            }
        }
    }
    
    /**
     *  Must be called when we are done with this connection.
     *  Final disconnect. Remove from conn manager.
     *  May be called multiple times.
     */
    public void disconnectComplete() {
        if (!_finalDisconnect.compareAndSet(false, true))
            return;
        _connected.set(false);
        I2PSocketFull s = _socket;
        if (s != null) {
            s.destroy2();
            _socket = null;
        }
        _outputStream.destroy();
        _receiver.destroy();
        _activityTimer.cancel();
        _inputStream.streamErrorOccurred(new IOException("Socket closed"));
        
        if (_log.shouldLog(Log.INFO))
            _log.info("Connection disconnect complete: "
                          + toString());
        _connectionManager.removeConnection(this);
        killOutstandingPackets();
    }
    
    /**
     *  Cancel and remove all packets awaiting ack
     */
    private void killOutstandingPackets() {
        synchronized (_outboundPackets) {
            if (_outboundPackets.isEmpty())
                return;  // short circuit iterator
            for (PacketLocal pl : _outboundPackets.values()) {
                pl.cancelled();
            }
            _outboundPackets.clear();
            _outboundPackets.notifyAll();
        }            
    }
    
    /**
     *  Schedule the end of the TIME-WAIT state,
     *  but only if not previously scheduled.
     *  Must call either this or disconnectComplete()
     *
     *  @return true if a new event was scheduled; false if already scheduled
     *  @since 0.9.9
     */
    private boolean scheduleDisconnectEvent() {
        if (!_disconnectScheduledOn.compareAndSet(0, _context.clock().now()))
            return false;
        schedule(new DisconnectEvent(), DISCONNECT_TIMEOUT);
        return true;
    }

    private class DisconnectEvent implements SimpleTimer.TimedEvent {
        public DisconnectEvent() {
            if (_log.shouldLog(Log.INFO))
                _log.info("Connection disconnect timer initiated: 5 minutes to drop " 
                          + Connection.this.toString(), new Exception());
        }
        public void timeReached() {
            disconnectComplete();
        }
    }
    
    /**
     *  Called from SchedulerImpl
     *
     *  @since 0.9.23 moved here so we can use our timer
     */
    public void scheduleConnectionEvent(long msToWait) {
        schedule(_connectionEvent, msToWait);
    }
    
    /**
     *  Schedule something on our timer.
     *
     *  @since 0.9.23
     */
    public void schedule(SimpleTimer.TimedEvent event, long msToWait) {
        _timer.addEvent(event, msToWait);
    }

    /** who are we talking with
     * @return peer Destination or null if unset
     */
    public synchronized Destination getRemotePeer() { return _remotePeer; }

    /**
     *  @param peer non-null
     */
    public void setRemotePeer(Destination peer) { 
        if (peer == null)
            throw new NullPointerException();
        synchronized(this) {
            if (_remotePeer != null)
                throw new RuntimeException("Remote peer already set [" + _remotePeer + ", " + peer + "]");
            _remotePeer = peer; 
        }
        // now that we know who the other end is, get the rtt etc. from the cache
        _connectionManager.updateOptsFromShare(this);
    }
    
    /**
     *  What stream do we send data to the peer on?
     *  @return non-global stream sending ID, or 0 if unknown
     */
    public long getSendStreamId() { return _sendStreamId.get(); }

    /**
     *  @param id 0 to 0xffffffff
     *  @throws RuntimeException if already set to nonzero
     */
    public void setSendStreamId(long id) { 
        if (!_sendStreamId.compareAndSet(0, id))
            throw new RuntimeException("Send stream ID already set [" + _sendStreamId + ", " + id + "]");
    }
    
    /**
     *  The stream ID of a peer connection that sends data to us, or zero if unknown.
     *  @return receive stream ID, or 0 if unknown
     */
    public long getReceiveStreamId() { return _receiveStreamId.get(); }

    /**
     *  @param id 0 to 0xffffffff
     *  @throws RuntimeException if already set to nonzero
     */
    public void setReceiveStreamId(long id) { 
        if (!_receiveStreamId.compareAndSet(0, id))
            throw new RuntimeException("Receive stream ID already set [" + _receiveStreamId + ", " + id + "]");
        synchronized (_connectLock) { _connectLock.notifyAll(); }
    }
    
    /** When did we last send anything to the peer?
     * @return Last time we sent data
     */
    public long getLastSendTime() { return _lastSendTime; }

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
     * @return the current ConnectionOptions, non-null
     */
    public ConnectionOptions getOptions() { return _options; }
    /**
     * Set the ConnectionOptions.
     * @param opts ConnectionOptions non-null
     */
    public void setOptions(ConnectionOptions opts) { _options = opts; }
        
    /** @since 0.9.21 */
    public ConnectionManager getConnectionManager() { return _connectionManager; }

    public I2PSession getSession() { return _session; }
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
        long cso = _closeSentOn.get();
        if (cso <= 0)
            return _context.clock().now() - _createdOn; 
        else
            return cso - _createdOn;
    }
    
    public ConnectionPacketHandler getPacketHandler() { return _handler; }
    
    public long getLifetimeBytesSent() { return _lifetimeBytesSent.get(); }
    public long getLifetimeBytesReceived() { return _lifetimeBytesReceived.get(); }
    public long getLifetimeDupMessagesSent() { return _lifetimeDupMessageSent.get(); }
    public long getLifetimeDupMessagesReceived() { return _lifetimeDupMessageReceived.get(); }
    public void incrementBytesSent(int bytes) { _lifetimeBytesSent.addAndGet(bytes); }
    public void incrementDupMessagesSent(int msgs) { _lifetimeDupMessageSent.addAndGet(msgs); }
    public void incrementBytesReceived(int bytes) { _lifetimeBytesReceived.addAndGet(bytes); }
    public void incrementDupMessagesReceived(int msgs) { _lifetimeDupMessageReceived.addAndGet(msgs); }
    
    /** 
     * Time when the scheduler next want to send a packet, or -1 if 
     * never.  This should be set when we want to send on timeout, for 
     * instance, or want to delay an ACK.
     * @return the next time the scheduler will want to send a packet, or -1 if never.
     */
    public long getNextSendTime() {
        synchronized(_nextSendLock) {
            return _nextSendTime;
        }
    }

    /**
     *  If the next send time is currently &gt;= 0 (i.e. not "never"),
     *  this may make the next time sooner but will not make it later.
     *  If the next send time is currently &lt; 0 (i.e. "never"),
     *  this will set it to the time specified, but not later than
     *  options.getSendAckDelay() from now (1000 ms)
     */
    public void setNextSendTime(long when) { 
        synchronized(_nextSendLock) {
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
        }
    }
    
    /**
     *  Set or clear if we are choking the other side.
     *  If on is true or the value has changed, this will call ackImmediately().
     *  @param on true for choking
     *  @since 0.9.29
     */
    public void setChoking(boolean on) {
        if (on != _isChoking) {
            _isChoking = on;
           if (_log.shouldWarn())
               _log.warn("Choking changed to " + on + " on " + this);
           if (!on)
               _unchokesToSend.set(UNCHOKES_TO_SEND);
           ackImmediately();
        } else if (on) {
           ackImmediately();
        }
    }
    
    /**
     *  Set or clear if we are being choked by the other side.
     *  @param on true for choked
     *  @since 0.9.29
     */
    public void setChoked(boolean on) {
        if (on != _isChoked) {
           _isChoked = on;
           if (_log.shouldWarn())
               _log.warn("Choked changed to " + on + " on " + this);
        }
        if (on) {
            congestionOccurred();
            // https://en.wikipedia.org/wiki/Transmission_Control_Protocol
            // When a receiver advertises a window size of 0, the sender stops sending data and starts the persist timer.
            // The persist timer is used to protect TCP from a deadlock situation that could arise
            // if a subsequent window size update from the receiver is lost,
            // and the sender cannot send more data until receiving a new window size update from the receiver.
            // When the persist timer expires, the TCP sender attempts recovery by sending a small packet
            // so that the receiver responds by sending another acknowledgement containing the new window size.
            // ...
            // We don't do any of that, but we set the window size to 1, and let the retransmission
            // of packets do the "attempted recovery".
            getOptions().setWindowSize(1);
        }
    }
    
    /**
     *  Is the other side choking us?
     *  @return if choked
     *  @since 0.9.29
     */
    public boolean isChoked() {
        return _isChoked;
    }

    /** how many packets have we sent and the other side has ACKed?
     * @return Count of how many packets ACKed.
     */
    public long getAckedPackets() { return _ackedPackets.get(); }
    public long getCreatedOn() { return _createdOn; }

    /** @return 0 if not sent */
    public long getCloseSentOn() { return _closeSentOn.get(); }

    /** @return 0 if not received */
    public long getCloseReceivedOn() { return _closeReceivedOn.get(); }

    public void updateShareOpts() {
        if (_closeSentOn.get() > 0 && !_updatedShareOpts) {
            _connectionManager.updateShareOpts(this);
            _updatedShareOpts = true;
        }
    }

    public void incrementUnackedPacketsReceived() { _unackedPacketsReceived.incrementAndGet(); }
    public int getUnackedPacketsReceived() { return _unackedPacketsReceived.get(); }

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
    
    public long getLastActivityOn() {
        return (_lastSendTime > _lastReceivedOn ? _lastSendTime : _lastReceivedOn);
    }
    
    public int getLastCongestionSeenAt() { return _lastCongestionSeenAt; }

    private void congestionOccurred() {
        // if we hit congestion and e.g. 5 packets are resent,
        // dont set the size to (winSize >> 4).  only set the
        if (_ackSinceCongestion.compareAndSet(true,false)) {
            _lastCongestionSeenAt = _options.getWindowSize();
            _lastCongestionTime = _context.clock().now();
            _lastCongestionHighestUnacked = _lastSendId.get();
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
            if (_connected.get() && (_receiveStreamId.get() > 0) && (_sendStreamId.get() > 0) ) {
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
            if (!_connected.get()) {
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
            if (timeLeft > MAX_CONNECT_TIMEOUT)
                timeLeft = MAX_CONNECT_TIMEOUT;
            else if (_options.getConnectTimeout() <= 0)
                timeLeft = DEFAULT_CONNECT_TIMEOUT;
            
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
        long howLong = _options.getInactivityTimeout();
        if (howLong <= 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Resetting the inactivity timer, but its gone!", new Exception("where did it go?"));
            return;
        }
        howLong += _randomWait; // randomize it a bit, so both sides don't do it at once
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Resetting the inactivity timer to " + howLong);
        // this will get rescheduled, and rescheduled, and rescheduled...
        _activityTimer.reschedule(howLong, false); // use the later of current and previous timeout
    }
    
    private class ActivityTimer extends SimpleTimer2.TimedEvent {
        public ActivityTimer() { 
            super(_timer);
            setFuzz(5*1000); // sloppy timer, don't reschedule unless at least 5s later
        }
        public void timeReached() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Fire inactivity timer on " + Connection.this.toString());
            // uh, nothing more to do...
            if (!_connected.get()) {
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
            // this shouldn't have been scheduled
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
                        _log.warn("Inactivity timer expired, not doing anything");
                    break;
                case ConnectionOptions.INACTIVITY_ACTION_SEND:
                    if (_closeSentOn.get() <= 0 && _closeReceivedOn.get() <= 0) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Sending some data due to inactivity");
                        _receiver.send(null, 0, 0, true);
                        break;
                    } // else fall through
                case ConnectionOptions.INACTIVITY_ACTION_DISCONNECT:
                    // fall through
                default:
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Closing (inactivity) " + toString());
                    if (_log.shouldLog(Log.DEBUG)) {
                        StringBuilder buf = new StringBuilder(128);
                        buf.append("last sent was: ").append(_context.clock().now() - _lastSendTime);
                        buf.append("ms ago, last received was: ").append(_context.clock().now()-_lastReceivedOn);
                        buf.append("ms ago, inactivity timeout is: ").append(_options.getInactivityTimeout());
                        _log.debug(buf.toString());
                    }
                    
                    IOException ioe = new IOException("Inactivity timeout");
                    _inputStream.streamErrorOccurred(ioe);
                    _outputStream.streamErrorOccurred(ioe);
                    // Clean disconnect if we have already scheduled one
                    // (generally because we already sent a close)
                    disconnect(_disconnectScheduledOn.get() > 0);
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
     * @return the inbound message stream, non-null
     */
    public MessageInputStream getInputStream() { return _inputStream; }

    /** stream that the local peer sends data to the remote peer on
     * @return the outbound message stream, non-null
     */
    public MessageOutputStream getOutputStream() { return _outputStream; }

    @Override
    public String toString() { 
        StringBuilder buf = new StringBuilder(256);
        buf.append("[Connection ");
        long id = _receiveStreamId.get();
        if (id > 0)
            buf.append(Packet.toId(id));
        else
            buf.append("unknown");
        buf.append('/');
        id = _sendStreamId.get();
        if (id > 0)
            buf.append(Packet.toId(id));
        else
            buf.append("unknown");
        if (_isInbound)
            buf.append(" from ");
        else
            buf.append(" to ");
        if (_remotePeer != null)
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
        long nacks[] = _inputStream.getNacks();
        if (nacks != null) {
            missing = nacks.length;
            buf.append(" [").append(missing).append(" missing]");
        }
        
        if (getResetSent())
            buf.append(" reset sent ").append(DataHelper.formatDuration(_context.clock().now() - getResetSentOn())).append(" ago");
        if (getResetReceived())
            buf.append(" reset rcvd ").append(DataHelper.formatDuration(_context.clock().now() - getDisconnectScheduledOn())).append(" ago");
        if (getCloseSentOn() > 0) {
            buf.append(" close sent ");
            long timeSinceClose = _context.clock().now() - getCloseSentOn();
            buf.append(DataHelper.formatDuration(timeSinceClose));
            buf.append(" ago");
        }
        if (getCloseReceivedOn() > 0)
            buf.append(" close rcvd ").append(DataHelper.formatDuration(_context.clock().now() - getCloseReceivedOn())).append(" ago");
        buf.append(" sent: ").append(1 + _lastSendId.get());
        buf.append(" rcvd: ").append(1 + _inputStream.getHighestBlockId() - missing);
        buf.append(" ackThru ").append(_highestAckedThrough);
        
        buf.append(" maxWin ").append(getOptions().getMaxWindowSize());
        buf.append(" MTU ").append(getOptions().getMaxMessageSize());
        
        buf.append("]");
        return buf.toString();
    }

    /**
     * fired to reschedule event notification
     */
    class ConEvent implements SimpleTimer.TimedEvent {
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
    static final int FAST_RETRANSMIT_THRESHOLD = 3;
    
    /**
     * Coordinate the resends of a given packet
     */
    class ResendPacketEvent extends SimpleTimer2.TimedEvent {
        private final PacketLocal _packet;
        private long _nextSend;

        public ResendPacketEvent(PacketLocal packet, long delay) {
            super(_timer);
            _packet = packet;
            _nextSend = delay + _context.clock().now();
            packet.setResendPacketEvent(ResendPacketEvent.this);
            schedule(delay);
        }
        
        public long getNextSendTime() { return _nextSend; }

        public void timeReached() { retransmit(); }

        /**
         * Retransmit the packet if we need to.  
         *
         * ackImmediately() above calls directly in here, so
         * we have to use forceReschedule() instead of schedule() below,
         * to prevent duplicates in the timer queue.
         *
         * don't synchronize this, deadlock with ackPackets-&gt;ackReceived-&gt;SimpleTimer2.cancel
         *
         * @return true if the packet was sent, false if it was not
         */
        private boolean retransmit() {
            if (_packet.getAckTime() > 0) 
                return false;
            
            if (_resetSentOn.get() > 0 || _resetReceived.get() || _finalDisconnect.get()) {
                _packet.cancelled();
                return false;
            }
            
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Resend period reached for " + _packet);
            boolean resend = false;
            boolean isLowest = false;
            synchronized (_outboundPackets) {
                // allow appx. half the window to be "lowest" and be active resends, minimum of 3
                // Note: we should really pick the N lowest, not the lowest one + N more who
                // happen to get here next, as the timers get out-of-order esp. after fast retx
                if (_packet.getSequenceNum() == _highestAckedThrough + 1 ||
                    _packet.getNumSends() > 1 ||
                    _activeResends.get() < Math.max(3, (_options.getWindowSize() + 1) / 2))
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
                        _log.info("Delaying resend of " + _packet + " with " 
                                  + _activeResends + " active resend, "
                                  + _outboundPackets.size() + " unacked, window size = " + _options.getWindowSize());
                    forceReschedule(1333);
                    _nextSend = 1333 + _context.clock().now();
                    return false;
                }
                
                // It's the lowest, or it's fast retransmit time. Resend the packet.

                if (fastRetransmit)
                    _context.statManager().addRateData("stream.fastRetransmit", _packet.getLifetime(), _packet.getLifetime());
                
                // revamp various fields, in case we need to ack more, etc
                // updateAcks done in enqueue()
                //_inputStream.updateAcks(_packet);
                if (_isChoking) {
                    _packet.setOptionalDelay(Packet.SEND_DELAY_CHOKE);
                    _packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                } else if (_unchokesToSend.decrementAndGet() > 0) {
                    // don't worry about wrapping around
                    _packet.setOptionalDelay(0);
                    _packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
                } else {
                    // clear flag
                    _packet.setFlag(Packet.FLAG_DELAY_REQUESTED, false);
                }

                // this seems unnecessary to send the MSS again:
                //_packet.setOptionalMaxSize(getOptions().getMaxMessageSize());
                // bugfix release 0.7.8, we weren't dividing by 1000
                _packet.setResendDelay(getOptions().getResendDelay() / 1000);
                if (_packet.getReceiveStreamId() <= 0)
                    _packet.setReceiveStreamId(_receiveStreamId.get());
                if (_packet.getSendStreamId() <= 0)
                    _packet.setSendStreamId(_sendStreamId.get());
                
                int newWindowSize = getOptions().getWindowSize();

                if (_isChoked) {
                    congestionOccurred();
                    getOptions().setWindowSize(1);
                } else if (_ackSinceCongestion.get()) {
                    // only shrink the window once per window
                    if (_packet.getSequenceNum() > _lastCongestionHighestUnacked) {
                        congestionOccurred();
                        _context.statManager().addRateData("stream.con.windowSizeAtCongestion", newWindowSize, _packet.getLifetime());
                        newWindowSize /= 2;
                        if (newWindowSize <= 0)
                            newWindowSize = 1;
                        
                        // The timeout for _this_ packet will be doubled below, but we also
                        // need to double the RTO for the _next_ packets.
                        // See RFC 6298 section 5 item 5.5
                        // This prevents being stuck at a window size of 1, retransmitting every packet,
                        // never updating the RTT or RTO.
                        getOptions().doubleRTO();
                        getOptions().setWindowSize(newWindowSize);

                        if (_log.shouldLog(Log.INFO))
                            _log.info("Congestion, resending packet " + _packet.getSequenceNum() + " (new windowSize " + newWindowSize 
                                      + "/" + getOptions().getWindowSize() + ") for " + Connection.this.toString());

                        windowAdjusted();
                    }
                }
                
                int numSends = _packet.getNumSends() + 1;
                
                
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
                        _log.debug("Disconnecting, too many resends of " + _packet);
                    _packet.cancelled();
                    disconnect(false);
                } else if (numSends >= 3 &&
                           _packet.isFlagSet(Packet.FLAG_CLOSE) &&
                           _packet.getPayloadSize() <= 0 &&
                           _outboundPackets.size() <= 1 &&
                           getCloseReceivedOn() > 0) {
                    // Bug workaround to prevent 5 minutes of retransmission
                    // Routers before 0.9.9 have bugs, they won't ack anything after
                    // they sent a close. Only send 3 CLOSE packets total, then
                    // shut down normally.
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Too many CLOSE resends, disconnecting: " + Connection.this.toString());
                    _packet.cancelled();
                    disconnect(true);
                } else {
                    //long timeout = _options.getResendDelay() << numSends;
                    long rto = _options.getRTO();
                    if (rto < MIN_RESEND_DELAY)
                        rto = MIN_RESEND_DELAY;
                    long timeout = rto << (numSends-1);
                    if ( (timeout > MAX_RESEND_DELAY) || (timeout <= 0) )
                        timeout = MAX_RESEND_DELAY;
                    // set this before enqueue() as it passes it on to the router
                    _nextSend = timeout + _context.clock().now();

                    if (_outboundQueue.enqueue(_packet)) {
                        // first resend for this packet ?
                        if (numSends == 2)
                            _activeResends.incrementAndGet();
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Resent packet " +
                                  (fastRetransmit ? "(fast) " : "(timeout) ") +
                                  _packet +
                                  " next resend in " + timeout + "ms" +
                                  " activeResends: " + _activeResends + 
                                  " (wsize "
                                  + newWindowSize + " lifetime " 
                                  + (_context.clock().now() - _packet.getCreatedOn()) + "ms)");
                        _unackedPacketsReceived.set(0);
                        _lastSendTime = _context.clock().now();
                        // timer reset added 0.9.1
                        resetActivityTimer();
                    }

                    forceReschedule(timeout);
                }
                
                // acked during resending (... or somethin') ????????????
                if ( (_packet.getAckTime() > 0) && (_packet.getNumSends() > 1) ) {
                    _activeResends.decrementAndGet();
                    synchronized (_outboundPackets) {
                        _outboundPackets.notifyAll();
                    }
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
