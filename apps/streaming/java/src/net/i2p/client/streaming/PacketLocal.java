package net.i2p.client.streaming;

import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * coordinate local attributes about a packet - send time, ack time, number of
 * retries, etc.
 */
class PacketLocal extends Packet implements MessageOutputStream.WriteStatus {
    private final I2PAppContext _context;
    private final Log _log;
    private final Connection _connection;
    private Destination _to;
    private SessionKey _keyUsed;
    private Set _tagsSent;
    private final long _createdOn;
    private int _numSends;
    private long _lastSend;
    private long _acceptedOn;
    private long _ackOn;
    private long _cancelledOn;
    private volatile int _nackCount;
    private volatile boolean _retransmitted;
    private SimpleTimer2.TimedEvent _resendEvent;
    
    /** not bound to a connection */
    public PacketLocal(I2PAppContext ctx, Destination to) {
        this(ctx, to, null);
    }

    public PacketLocal(I2PAppContext ctx, Destination to, Connection con) {
        _context = ctx;
        _createdOn = ctx.clock().now();
        _log = ctx.logManager().getLog(PacketLocal.class);
        _to = to;
        _connection = con;
        _lastSend = -1;
        _cancelledOn = -1;
    }
    
    public Destination getTo() { return _to; }
    public void setTo(Destination to) { _to = to; }
    
    /**
     * @deprecated should always return null
     */
    public SessionKey getKeyUsed() { return _keyUsed; }

    /**
     * @deprecated I2PSession throws out the tags
     */
    public void setKeyUsed(SessionKey key) {
        if (key != null)
            _log.error("Who is sending tags thru the streaming lib?");
        _keyUsed = key;
    }
    
    /**
     * @deprecated should always return null or an empty set
     */
    public Set getTagsSent() { return _tagsSent; }

    /**
     * @deprecated I2PSession throws out the tags
     */
    public void setTagsSent(Set tags) { 
        if (tags != null && !tags.isEmpty())
            _log.error("Who is sending tags thru the streaming lib? " + tags.size());
        if ( (_tagsSent != null) && (!_tagsSent.isEmpty()) && (!tags.isEmpty()) ) {
            //int old = _tagsSent.size();
            //_tagsSent.addAll(tags);
            if (!_tagsSent.equals(tags))
                System.out.println("ERROR: dup tags: old=" + _tagsSent.size() + " new=" + tags.size() + " packet: " + toString());
        } else {
            _tagsSent = tags;
        }
    }
    
    public boolean shouldSign() { 
        return isFlagSet(FLAG_SIGNATURE_INCLUDED) ||
               isFlagSet(FLAG_SYNCHRONIZE) ||
               isFlagSet(FLAG_CLOSE) ||
               isFlagSet(FLAG_ECHO);
    }
    
    /** last minute update of ack fields, just before write/sign  */
    public void prepare() {
        if (_connection != null)
            _connection.getInputStream().updateAcks(this);
        if (_numSends > 0) {
            // so we can debug to differentiate resends
            setOptionalDelay(_numSends * 1000);
            setFlag(FLAG_DELAY_REQUESTED);
        }
    }
    
    public long getCreatedOn() { return _createdOn; }
    public long getLifetime() { return _context.clock().now() - _createdOn; }
    public void incrementSends() { 
        _numSends++;
        _lastSend = _context.clock().now();
    }
    public void ackReceived() {
        synchronized (this) {
            if (_ackOn <= 0)
                _ackOn = _context.clock().now();
            releasePayload();
            notifyAll();
        }
        _resendEvent.cancel();
    }
    public void cancelled() { 
        synchronized (this) {
            _cancelledOn = _context.clock().now();
            releasePayload();
            notifyAll();
        }
        _resendEvent.cancel();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Cancelled! " + toString(), new Exception("cancelled"));
    }
    public SimpleTimer2.TimedEvent getResendEvent() { return _resendEvent; }
    
    /** how long after packet creation was it acked?
     * @return how long after packet creation the packet was ACKed in ms
     */
    public int getAckTime() {
        if (_ackOn <= 0) 
            return -1;
        else
            return (int)(_ackOn - _createdOn);
    }
    public int getNumSends() { return _numSends; }
    public long getLastSend() { return _lastSend; }

    /** @return null if not bound */
    public Connection getConnection() { return _connection; }

    public void incrementNACKs() { 
        int cnt = ++_nackCount;
        SimpleTimer2.TimedEvent evt = _resendEvent;
        if ( (cnt >= Connection.FAST_RETRANSMIT_THRESHOLD) && (evt != null) && (!_retransmitted)) {
            _retransmitted = true;
            evt.reschedule(0);
        }
    }
    public int getNACKs() { return _nackCount; }
    
    public void setResendPacketEvent(SimpleTimer2.TimedEvent evt) { _resendEvent = evt; }
    
	@Override
    public StringBuilder formatAsString() {
        StringBuilder buf = super.formatAsString();
        
        Connection con = _connection;
        if (con != null)
            buf.append(" rtt ").append(con.getOptions().getRTT());
        
        if ( (_tagsSent != null) && (!_tagsSent.isEmpty()) ) 
            buf.append(" with tags");

        if (_ackOn > 0)
            buf.append(" ack after ").append(getAckTime());
        
        if (_numSends > 1)
            buf.append(" sent ").append(_numSends).append(" times");
        
        if (isFlagSet(Packet.FLAG_SYNCHRONIZE) ||
            isFlagSet(Packet.FLAG_CLOSE) ||
            isFlagSet(Packet.FLAG_RESET)) {
         
            if (con != null) {
                buf.append(" from ");
                Destination local = con.getSession().getMyDestination();
                if (local != null)
                    buf.append(local.calculateHash().toBase64().substring(0,4));
                else
                    buf.append("unknown");
                
                buf.append(" to ");
                Destination remote = con.getRemotePeer();
                if (remote != null)
                    buf.append(remote.calculateHash().toBase64().substring(0,4));
                else
                    buf.append("unknown");
                
            }
        }
        return buf;
    }
    
    /**
     * Blocks until outbound window is not full. See Connection.packetSendChoke().
     * @param maxWaitMs MessageOutputStream is the only caller, generally with -1
     */
    public void waitForAccept(int maxWaitMs) {
        if (_connection == null) 
            throw new IllegalStateException("Cannot wait for accept with no connection");
        long before = _context.clock().now();
        int queued = _connection.getUnackedPacketsSent();
        int window = _connection.getOptions().getWindowSize();
        boolean accepted = _connection.packetSendChoke(maxWaitMs);
        long after = _context.clock().now();
        if (accepted) {
            _acceptedOn = after;
        } else {
            _acceptedOn = -1;
            releasePayload();
        }
        int afterQueued = _connection.getUnackedPacketsSent();
        if ( (after - before > 1000) && (_log.shouldLog(Log.DEBUG)) )
            _log.debug("Took " + (after-before) + "ms to get " 
                       + (accepted ? " accepted" : " rejected")
                       + (_cancelledOn > 0 ? " and CANCELLED" : "")
                       + ", queued behind " + queued +" with a window size of " + window 
                       + ", finally accepted with " + afterQueued + " queued: " 
                       + toString());
    }
    
    /** block until the packet is acked from the far end */
    public void waitForCompletion(int maxWaitMs) {
        long expiration = _context.clock().now()+maxWaitMs;
        while (true) {
            long timeRemaining = expiration - _context.clock().now();
            if ( (timeRemaining <= 0) && (maxWaitMs > 0) ) break;
            try {
                synchronized (this) {
                    if (_ackOn > 0) break;
                    if (_cancelledOn > 0) break;
                    if (!_connection.getIsConnected()) break;
                    if (timeRemaining > 60*1000)
                        timeRemaining = 60*1000;
                    else if (timeRemaining <= 0)
                        timeRemaining = 10*1000;
                    wait(timeRemaining);
                }
            } catch (InterruptedException ie) { }//{ break; }
        }
        if (!writeSuccessful())
            releasePayload();
    }
    
    public boolean writeAccepted() { return _acceptedOn > 0 && _cancelledOn <= 0; }
    public boolean writeFailed() { return _cancelledOn > 0; }
    public boolean writeSuccessful() { return _ackOn > 0 && _cancelledOn <= 0; }
}
