package net.i2p.client.streaming;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.data.SessionTag;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Maintain the state controlling a streaming connection between two 
 * destinations.
 *
 */
public class Connection {
    private I2PAppContext _context;
    private Log _log;
    private ConnectionManager _connectionManager;
    private Destination _remotePeer;
    private byte _sendStreamId[];
    private byte _receiveStreamId[];
    private long _lastSendTime;
    private long _lastSendId;
    private boolean _resetReceived;
    private boolean _connected;
    private MessageInputStream _inputStream;
    private MessageOutputStream _outputStream;
    private SchedulerChooser _chooser;
    private long _nextSendTime;
    private long _ackedPackets;
    private long _createdOn;
    private long _closeSentOn;
    private long _closeReceivedOn;
    private int _unackedPacketsReceived;
    private long _congestionWindowEnd;
    private long _highestAckedThrough;
    /** Packet ID (Long) to PacketLocal for sent but unacked packets */
    private Map _outboundPackets;
    private PacketQueue _outboundQueue;
    private ConnectionPacketHandler _handler;
    private ConnectionOptions _options;
    private ConnectionDataReceiver _receiver;
    private I2PSocketFull _socket;
    /** set to an error cause if the connection could not be established */
    private String _connectionError;
    
    public Connection(I2PAppContext ctx, ConnectionManager manager, SchedulerChooser chooser, PacketQueue queue, ConnectionPacketHandler handler) {
        this(ctx, manager, chooser, queue, handler, null);
    }
    public Connection(I2PAppContext ctx, ConnectionManager manager, SchedulerChooser chooser, PacketQueue queue, ConnectionPacketHandler handler, ConnectionOptions opts) {
        _context = ctx;
        _log = ctx.logManager().getLog(Connection.class);
        _receiver = new ConnectionDataReceiver(ctx, this);
        _inputStream = new MessageInputStream(ctx);
        _outputStream = new MessageOutputStream(ctx, _receiver);
        _chooser = chooser;
        _outboundPackets = new TreeMap();
        _outboundQueue = queue;
        _handler = handler;
        _options = (opts != null ? opts : new ConnectionOptions());
        _lastSendId = -1;
        _nextSendTime = -1;
        _ackedPackets = 0;
        _createdOn = ctx.clock().now();
        _closeSentOn = -1;
        _closeReceivedOn = -1;
        _unackedPacketsReceived = 0;
        _congestionWindowEnd = 0;
        _highestAckedThrough = -1;
        _connectionManager = manager;
        _resetReceived = false;
        _connected = true;
    }
    
    public long getNextOutboundPacketNum() { 
        synchronized (this) {
            return ++_lastSendId;
        }
    }
    
    void closeReceived() {
        setCloseReceivedOn(_context.clock().now());
        _inputStream.closeReceived();
    }
    
    /**
     * Block until there is an open outbound packet slot or the write timeout 
     * expires.  
     *
     * @return true if the packet should be sent
     */
    boolean packetSendChoke(long timeoutMs) {
        if (false) return true;
        long writeExpire = timeoutMs;
        while (true) {
            long timeLeft = writeExpire - _context.clock().now();
            synchronized (_outboundPackets) {
                if (_outboundPackets.size() >= _options.getWindowSize()) {
                    if (writeExpire > 0) {
                        if (timeLeft <= 0) {
                            _log.error("Outbound window is full of " + _outboundPackets.size() 
                                       + " and we've waited too long (" + writeExpire + "ms)");
                            return false;
                        }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound window is full (" + _outboundPackets.size() + "/" + _options.getWindowSize() + "), waiting " + timeLeft);
                        try { _outboundPackets.wait(timeLeft); } catch (InterruptedException ie) {}
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Outbound window is full (" + _outboundPackets.size() + "), waiting indefinitely");
                        try { _outboundPackets.wait(); } catch (InterruptedException ie) {}
                    }
                } else {
                    return true;
                }
            }
        }
    }
    
    void ackImmediately() {
        _receiver.send(null, 0, 0);
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
        setNextSendTime(-1);
        _unackedPacketsReceived = 0;
        if (_options.getRequireFullySigned()) {
            packet.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
            packet.setFlag(Packet.FLAG_SIGNATURE_REQUESTED);
        }
        
        boolean ackOnly = false;
                
        if ( (packet.getSequenceNum() == 0) && (!packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) ) {
            ackOnly = true;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("No resend for " + packet);
        } else {
            int remaining = 0;
            synchronized (_outboundPackets) {
                _outboundPackets.put(new Long(packet.getSequenceNum()), packet);
                remaining = _options.getWindowSize() - _outboundPackets.size() ;
                _outboundPackets.notifyAll();
            }
            if (remaining < 0) 
                remaining = 0;
            if (packet.isFlagSet(Packet.FLAG_CLOSE) || (remaining < 2)) {
                packet.setOptionalDelay(0);
            } else {
                int delay = _options.getRTT() / 2;
                packet.setOptionalDelay(delay);
                _log.debug("Requesting ack delay of " + delay + "ms for packet " + packet);
            }
            packet.setFlag(Packet.FLAG_DELAY_REQUESTED);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Resend in " + (_options.getRTT()*2) + " for " + packet);
            SimpleTimer.getInstance().addEvent(new ResendPacketEvent(packet), (_options.getRTT()*2 < 5000 ? 5000 : _options.getRTT()*2));
        }

        _lastSendTime = _context.clock().now();
        _outboundQueue.enqueue(packet);        
        
        if (ackOnly) {
            // ACK only, don't schedule this packet for retries
            // however, if we are running low on sessionTags we want to send
            // something that will get a reply so that we can deliver some new tags -
            // ACKs don't get ACKed, but pings do.
            if (packet.getTagsSent().size() > 0) {
                _log.warn("Sending a ping since the ACK we just sent has " + packet.getTagsSent().size() + " tags");
                _connectionManager.ping(_remotePeer, _options.getRTT()*2, false, packet.getKeyUsed(), packet.getTagsSent(), new PingNotifier());
            }
        }
    }
    
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
    
    List ackPackets(long ackThrough, long nacks[]) {
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
        
        List acked = null;
        synchronized (_outboundPackets) {
            for (Iterator iter = _outboundPackets.keySet().iterator(); iter.hasNext(); ) {
                Long id = (Long)iter.next();
                if (id.longValue() <= ackThrough) {
                    boolean nacked = false;
                    if (nacks != null) {
                        // linear search since its probably really tiny
                        for (int i = 0; i < nacks.length; i++) {
                            if (nacks[i] == id.longValue()) {
                                nacked = true;
                                break; // NACKed
                            }
                        }
                    }
                    if (!nacked) { // aka ACKed
                        if (acked == null) 
                            acked = new ArrayList(1);
                        PacketLocal ackedPacket = (PacketLocal)_outboundPackets.get(id);
                        ackedPacket.ackReceived();
                        acked.add(ackedPacket);
                    }
                } else {
                    break; // _outboundPackets is ordered
                }
            }
            if (acked != null) {
                for (int i = 0; i < acked.size(); i++) {
                    PacketLocal p = (PacketLocal)acked.get(i);
                    _outboundPackets.remove(new Long(p.getSequenceNum()));
                    _ackedPackets++;
                }
            }
            _outboundPackets.notifyAll();
        }
        return acked;
    }

    void eventOccurred() {
        _chooser.getScheduler(this).eventOccurred(this);
    }
    
    void resetReceived() {
        _resetReceived = true;
        _outputStream.streamErrorOccurred(new IOException("Reset received"));
        _inputStream.streamErrorOccurred(new IOException("Reset received"));
    }
    public boolean getResetReceived() { return _resetReceived; }
    
    public boolean getIsConnected() { return _connected; }

    void disconnect(boolean cleanDisconnect) {
        disconnect(cleanDisconnect, true);
    }
    void disconnect(boolean cleanDisconnect, boolean removeFromConMgr) {
        if (!_connected) return;
        _connected = false;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Disconnecting " + toString(), new Exception("discon"));
        
        if (cleanDisconnect) {
            // send close packets and schedule stuff...
            _outputStream.closeInternal();
            _inputStream.close();
        } else {
            doClose();
            synchronized (_outboundPackets) {
                for (Iterator iter = _outboundPackets.values().iterator(); iter.hasNext(); ) {
                    PacketLocal pl = (PacketLocal)iter.next();
                    pl.cancelled();
                }
                _outboundPackets.clear();
                _outboundPackets.notifyAll();
            }
            if (removeFromConMgr)
                _connectionManager.removeConnection(this);
        }
    }
    
    void disconnectComplete() {
        _connectionManager.removeConnection(this);
    }
    
    private void doClose() {
        _outputStream.streamErrorOccurred(new IOException("Hard disconnect"));
        _inputStream.closeReceived();
    }
    
    /** who are we talking with */
    public Destination getRemotePeer() { return _remotePeer; }
    public void setRemotePeer(Destination peer) { _remotePeer = peer; }
    
    /** what stream do we send data to the peer on? */
    public byte[] getSendStreamId() { return _sendStreamId; }
    public void setSendStreamId(byte[] id) { _sendStreamId = id; }
    
    /** what stream does the peer send data to us on? (may be null) */
    public byte[] getReceiveStreamId() { return _receiveStreamId; }
    public void setReceiveStreamId(byte[] id) { _receiveStreamId = id; }
    
    /** when did we last send anything to the peer? */
    public long getLastSendTime() { return _lastSendTime; }
    public void setLastSendTime(long when) { _lastSendTime = when; }
    
    /** what was the last packet Id sent to the peer? */
    public long getLastSendId() { return _lastSendId; }
    public void setLastSendId(long id) { _lastSendId = id; }
    
    public ConnectionOptions getOptions() { return _options; }
    public void setOptions(ConnectionOptions opts) { _options = opts; }
        
    public I2PSession getSession() { return _connectionManager.getSession(); }
    public I2PSocketFull getSocket() { return _socket; }
    public void setSocket(I2PSocketFull socket) { _socket = socket; }
    
    public String getConnectionError() { return _connectionError; }
    public void setConnectionError(String err) { _connectionError = err; }
    
    public ConnectionPacketHandler getPacketHandler() { return _handler; }
    
    /** 
     * when does the scheduler next want to send a packet?   -1 if never.
     * This should be set when we want to send on timeout, for instance, or
     * want to delay an ACK.
     */
    public long getNextSendTime() { return _nextSendTime; }
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
        
        if (_log.shouldLog(Log.DEBUG) && false) {
            if (_nextSendTime <= 0) 
                _log.debug("set next send time to an unknown time", new Exception(toString()));
            else
                _log.debug("set next send time to " + (_nextSendTime-_context.clock().now()) + "ms from now", new Exception(toString()));
        }
    }
    
    public long getAckedPackets() { return _ackedPackets; }
    public long getCreatedOn() { return _createdOn; }
    public long getCloseSentOn() { return _closeSentOn; }
    public void setCloseSentOn(long when) { _closeSentOn = when; }
    public long getCloseReceivedOn() { return _closeReceivedOn; }
    public void setCloseReceivedOn(long when) { _closeReceivedOn = when; }
    
    public void incrementUnackedPacketsReceived() { _unackedPacketsReceived++; }
    public int getUnackedPacketsReceived() { return _unackedPacketsReceived; }
    public int getUnackedPacketsSent() { 
        synchronized (_outboundPackets) { 
            return _outboundPackets.size(); 
        } 
    }
    
    public long getCongestionWindowEnd() { return _congestionWindowEnd; }
    public void setCongestionWindowEnd(long endMsg) { _congestionWindowEnd = endMsg; }
    public long getHighestAckedThrough() { return _highestAckedThrough; }
    public void setHighestAckedThrough(long msgNum) { _highestAckedThrough = msgNum; }
    
    /** stream that the local peer receives data on */
    public MessageInputStream getInputStream() { return _inputStream; }
    /** stream that the local peer sends data to the remote peer on */
    public MessageOutputStream getOutputStream() { return _outputStream; }
    
    public String toString() { 
        StringBuffer buf = new StringBuffer(128);
        buf.append("[Connection ");
        if (_receiveStreamId != null)
            buf.append(Base64.encode(_receiveStreamId));
        else
            buf.append("unknown");
        buf.append("<-->");
        if (_sendStreamId != null)
            buf.append(Base64.encode(_sendStreamId));
        else
            buf.append("unknown");
        buf.append(" wsize: ").append(_options.getWindowSize());
        buf.append(" cwin: ").append(_congestionWindowEnd - _highestAckedThrough);
        buf.append(" rtt: ").append(_options.getRTT());
        buf.append(" unacked outbound: ");
        synchronized (_outboundPackets) {
            buf.append(_outboundPackets.size()).append(" [");
            for (Iterator iter = _outboundPackets.keySet().iterator(); iter.hasNext(); ) {
                buf.append(((Long)iter.next()).longValue()).append(" ");
            }
            buf.append("] ");
        }
        buf.append("unacked inbound? ").append(getUnackedPacketsReceived());
        buf.append(" [high ").append(_inputStream.getHighestBlockId());
        long nacks[] = _inputStream.getNacks();
        if (nacks != null)
            for (int i = 0; i < nacks.length; i++)
                buf.append(" ").append(nacks[i]);
        buf.append("]");
        buf.append("]");
        return buf.toString();
    }
    
    /**
     * Coordinate the resends of a given packet
     */
    private class ResendPacketEvent implements SimpleTimer.TimedEvent {
        private PacketLocal _packet;
        public ResendPacketEvent(PacketLocal packet) {
            _packet = packet;
        }
        
        public void timeReached() {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Resend period reached for " + _packet);
            boolean resend = false;
            synchronized (_outboundPackets) {
                if (_outboundPackets.containsKey(new Long(_packet.getSequenceNum())))
                    resend = true;
            }
            if ( (resend) && (_packet.getAckTime() < 0) ) {
                // revamp various fields, in case we need to ack more, etc
                _inputStream.updateAcks(_packet);
                _packet.setOptionalDelay(getOptions().getChoke());
                _packet.setOptionalMaxSize(getOptions().getMaxMessageSize());
                _packet.setResendDelay(getOptions().getResendDelay());
                _packet.setReceiveStreamId(_receiveStreamId);
                _packet.setSendStreamId(_sendStreamId);
                
                // shrink the window
                int newWindowSize = getOptions().getWindowSize();
                newWindowSize /= 2;
                if (newWindowSize <= 0)
                    newWindowSize = 1;
                getOptions().setWindowSize(newWindowSize);
                
                int numSends = _packet.getNumSends() + 1;
                
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Resend packet " + _packet + " time " + numSends + " (wsize "
                              + newWindowSize + " lifetime " 
                              + (_context.clock().now() - _packet.getCreatedOn()) + "ms)");
                _outboundQueue.enqueue(_packet);
                
                if (numSends > _options.getMaxResends()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Too many resends");
                    disconnect(false);
                } else {
                    //long timeout = _options.getResendDelay() << numSends;
                    long timeout = _options.getRTT() << (numSends-1);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Scheduling resend in " + timeout + "ms for " + _packet);
                    SimpleTimer.getInstance().addEvent(ResendPacketEvent.this, timeout);
                }
            } else {
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("Packet acked before resend (resend="+ resend + "): " 
                //               + _packet + " on " + Connection.this);
            }
        }
    }
}
