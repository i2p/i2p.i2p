package net.i2p.client.streaming;

import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;

/**
 * coordinate local attributes about a packet - send time, ack time, number of
 * retries, etc.
 */
public class PacketLocal extends Packet {
    private I2PAppContext _context;
    private Destination _to;
    private SessionKey _keyUsed;
    private Set _tagsSent;
    private long _createdOn;
    private int _numSends;
    private long _lastSend;
    private long _ackOn;
    
    public PacketLocal(I2PAppContext ctx, Destination to) {
        _context = ctx;
        _createdOn = ctx.clock().now();
        _to = to;
        _lastSend = -1;
    }
    
    public Destination getTo() { return _to; }
    public void setTo(Destination to) { _to = to; }
    
    public SessionKey getKeyUsed() { return _keyUsed; }
    public void setKeyUsed(SessionKey key) { _keyUsed = key; }
    
    public Set getTagsSent() { return _tagsSent; }
    public void setTagsSent(Set tags) { _tagsSent = tags; }
    
    public boolean shouldSign() { 
        return isFlagSet(FLAG_SIGNATURE_INCLUDED) ||
               isFlagSet(FLAG_SYNCHRONIZE) ||
               isFlagSet(FLAG_CLOSE);
    }
    
    public long getCreatedOn() { return _createdOn; }
    public void incrementSends() { 
        _numSends++;
        _lastSend = _context.clock().now();
    }
    public void ackReceived() { 
        if (_ackOn <= 0)
            _ackOn = _context.clock().now(); 
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
}
