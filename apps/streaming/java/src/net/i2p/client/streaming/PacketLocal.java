package net.i2p.client.streaming;

import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;

/**
 * coordinate local attributes about a packet - send time, ack time, number of
 * retries, etc.
 */
public class PacketLocal extends Packet implements MessageOutputStream.WriteStatus {
    private I2PAppContext _context;
    private Connection _connection;
    private Destination _to;
    private SessionKey _keyUsed;
    private Set _tagsSent;
    private long _createdOn;
    private int _numSends;
    private long _lastSend;
    private long _acceptedOn;
    private long _ackOn;
    private long _cancelledOn;
    
    public PacketLocal(I2PAppContext ctx, Destination to) {
        this(ctx, to, null);
    }
    public PacketLocal(I2PAppContext ctx, Destination to, Connection con) {
        _context = ctx;
        _createdOn = ctx.clock().now();
        _to = to;
        _connection = con;
        _lastSend = -1;
        _cancelledOn = -1;
    }
    
    public Destination getTo() { return _to; }
    public void setTo(Destination to) { _to = to; }
    
    public SessionKey getKeyUsed() { return _keyUsed; }
    public void setKeyUsed(SessionKey key) { _keyUsed = key; }
    
    public Set getTagsSent() { return _tagsSent; }
    public void setTagsSent(Set tags) { 
        if ( (_tagsSent != null) && (_tagsSent.size() > 0) && (tags.size() > 0) ) {
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
    }
    
    public long getCreatedOn() { return _createdOn; }
    public void incrementSends() { 
        _numSends++;
        _lastSend = _context.clock().now();
    }
    public void ackReceived() { 
        synchronized (this) {
            if (_ackOn <= 0)
                _ackOn = _context.clock().now(); 
            notifyAll();
        }
    }
    public void cancelled() { 
        synchronized (this) {
            _cancelledOn = _context.clock().now();
            notifyAll();
        }
    }
    
    /** how long after packet creation was it acked? */
    public int getAckTime() {
        if (_ackOn <= 0) 
            return -1;
        else
            return (int)(_ackOn - _createdOn);
    }
    public int getNumSends() { return _numSends; }
    public long getLastSend() { return _lastSend; }
    public Connection getConnection() { return _connection; }
    
    public String toString() {
        String str = super.toString();
        if (_ackOn > 0)
            return str + " ack after " + getAckTime();
        else
            return str;
    }
    
    public void waitForAccept(int maxWaitMs) {
        if (_connection == null) 
            throw new IllegalStateException("Cannot wait for accept with no connection");
        long expiration = _context.clock().now()+maxWaitMs;
        boolean accepted = _connection.packetSendChoke(maxWaitMs);
        if (accepted)
            _acceptedOn = _context.clock().now();
        else
            _acceptedOn = -1;
    }
    
    public void waitForCompletion(int maxWaitMs) {
        long expiration = _context.clock().now()+maxWaitMs;
        while ((maxWaitMs <= 0) || (expiration < _context.clock().now())) {
            synchronized (this) {
                if (_ackOn > 0)
                    return;
                if (_cancelledOn > 0)
                    return;
                try { wait(); } catch (InterruptedException ie) {}
            }
        }
    }
    
    public boolean writeAccepted() { return _acceptedOn > 0 && _cancelledOn <= 0; }
    public boolean writeFailed() { return _cancelledOn > 0; }
    public boolean writeSuccessful() { return _ackOn > 0 && _cancelledOn <= 0; }
}
