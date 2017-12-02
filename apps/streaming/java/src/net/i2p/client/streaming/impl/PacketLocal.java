package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.SigningPrivateKey;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * This is the class used for outbound packets.
 *
 * coordinate local attributes about a packet - send time, ack time, number of
 * retries, etc.
 */
class PacketLocal extends Packet implements MessageOutputStream.WriteStatus {
    private final I2PAppContext _context;
    private final Log _log;
    private final Connection _connection;
    private final Destination _to;
    private SessionKey _keyUsed;
    private final long _createdOn;
    private final AtomicInteger _numSends = new AtomicInteger();
    private volatile long _lastSend;
    private long _acceptedOn;
    /** LOCKING: this */
    private long _ackOn; 
    private long _cancelledOn;
    private final AtomicInteger _nackCount = new AtomicInteger();
    private volatile boolean _retransmitted;
    private volatile SimpleTimer2.TimedEvent _resendEvent;
    
    /** not bound to a connection */
    public PacketLocal(I2PAppContext ctx, Destination to, I2PSession session) {
        super(session);
        _context = ctx;
        _createdOn = ctx.clock().now();
        _log = ctx.logManager().getLog(PacketLocal.class);
        _to = to;
        _connection = null;
        _lastSend = -1;
        _cancelledOn = -1;
    }

    /** bound to a connection */
    public PacketLocal(I2PAppContext ctx, Destination to, Connection con) {
        super(con.getSession());
        _context = ctx;
        _createdOn = ctx.clock().now();
        _log = ctx.logManager().getLog(PacketLocal.class);
        _to = to;
        _connection = con;
        _lastSend = -1;
        _cancelledOn = -1;
    }
    
    public Destination getTo() { return _to; }
    
    /**
     * @deprecated should always return null
     */
    @Deprecated
    public SessionKey getKeyUsed() { return _keyUsed; }

    /**
     * @deprecated I2PSession throws out the tags
     */
    @Deprecated
    public void setKeyUsed(SessionKey key) {
        if (key != null)
            _log.error("Who is sending tags thru the streaming lib?");
        _keyUsed = key;
    }
    
    /**
     * @deprecated should always return null or an empty set
     */
    @Deprecated
    public Set<SessionTag> getTagsSent() { return Collections.emptySet(); }

    /**
     * @deprecated I2PSession throws out the tags
     */
    @Deprecated
    public void setTagsSent(Set<SessionTag> tags) { 
        if (tags != null && !tags.isEmpty())
            _log.error("Who is sending tags thru the streaming lib? " + tags.size());
      /****
        if ( (_tagsSent != null) && (!_tagsSent.isEmpty()) && (!tags.isEmpty()) ) {
            //int old = _tagsSent.size();
            //_tagsSent.addAll(tags);
            if (!_tagsSent.equals(tags))
                System.out.println("ERROR: dup tags: old=" + _tagsSent.size() + " new=" + tags.size() + " packet: " + toString());
        } else {
            _tagsSent = tags;
        }
      ****/
    }
    
    public boolean shouldSign() { 
        return isFlagSet(FLAG_SIGNATURE_INCLUDED |
                         FLAG_SYNCHRONIZE |
                         FLAG_CLOSE |
                         FLAG_ECHO);
    }
    
    public long getCreatedOn() { return _createdOn; }
    public long getLifetime() { return _context.clock().now() - _createdOn; }
    public void incrementSends() { 
        _numSends.incrementAndGet();
        _lastSend = _context.clock().now();
    }
    
    private void cancelResend() {
        SimpleTimer2.TimedEvent ev = _resendEvent;
        if (ev != null) 
            ev.cancel();
    }
    
    public void ackReceived() {
        final long now = _context.clock().now();
        synchronized (this) {
            if (_ackOn <= 0)
                _ackOn = now;
            releasePayload();
            notifyAll();
        }
        cancelResend();
    }
    
    public void cancelled() { 
        synchronized (this) {
            _cancelledOn = _context.clock().now();
            releasePayload();
            notifyAll();
        }
       cancelResend();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Cancelled! " + toString(), new Exception("cancelled"));
    }

    public SimpleTimer2.TimedEvent getResendEvent() { return _resendEvent; }
    
    /** how long after packet creation was it acked?
     * @return how long after packet creation the packet was ACKed in ms
     */
    public synchronized int getAckTime() {
        if (_ackOn <= 0) 
            return -1;
        else
            return (int)(_ackOn - _createdOn);
    }
    public int getNumSends() { return _numSends.get(); }
    public long getLastSend() { return _lastSend; }

    /** @return null if not bound */
    public Connection getConnection() { return _connection; }

    /**
     *  Will force a fast restransmit on the 3rd call (FAST_RETRANSMIT_THRESHOLD)
     *  but only if it's the lowest unacked (see Connection.ResendPacketEvent)
     */
    public void incrementNACKs() { 
        final int cnt = _nackCount.incrementAndGet();
        SimpleTimer2.TimedEvent evt = _resendEvent;
        if (cnt >= Connection.FAST_RETRANSMIT_THRESHOLD && evt != null && (!_retransmitted) &&
            (_numSends.get() == 1 || _lastSend < _context.clock().now() - 4*1000)) {  // Don't fast retx if we recently resent it
            _retransmitted = true;
            evt.reschedule(0);
            // the predicate used to be '+', changing to '-' --zab
            
            if (_log.shouldLog(Log.DEBUG)) {
                final String log = String.format("%s nacks and retransmits. Criteria: nacks=%d, retransmitted=%b,"+
                    " numSends=%d, lastSend=%d, now=%d",
                    toString(), cnt, _retransmitted, _numSends.get(), _lastSend, _context.clock().now());
                    _log.debug(log);
            }
        } else if (_log.shouldLog(Log.DEBUG)) {
            final String log = String.format("%s nack but no retransmit.  Criteria: nacks=%d, retransmitted=%b,"+
                    " numSends=%d, lastSend=%d, now=%d",
                    toString(), cnt, _retransmitted, _numSends.get(), _lastSend, _context.clock().now());
                    _log.debug(log);
        }
    }
    public int getNACKs() { return _nackCount.get(); }
    
    public void setResendPacketEvent(SimpleTimer2.TimedEvent evt) { _resendEvent = evt; }

    /**
     * Sign and write the packet to the buffer (starting at the offset) and return
     * the number of bytes written.
     *
     * @param buffer data to be written
     * @param offset starting point in the buffer
     * @return Count of bytes written
     * @throws IllegalStateException if there is data missing or otherwise b0rked
     * @since 0.9.20 moved from Packet
     */
    public int writeSignedPacket(byte buffer[], int offset) throws IllegalStateException {
        setFlag(FLAG_SIGNATURE_INCLUDED);
        SigningPrivateKey key = _session.getPrivateKey();
        int size = writePacket(buffer, offset, key.getType().getSigLen());
        _optionSignature = _context.dsa().sign(buffer, offset, size, key);
        if (_optionSignature == null)
            throw new IllegalStateException("Signature failed");
        //if (false) {
        //    Log l = ctx.logManager().getLog(Packet.class);
        //    l.error("Signing: " + toString());
        //    l.error(Base64.encode(buffer, 0, size));
        //    l.error("Signature: " + Base64.encode(_optionSignature.getData()));
        //}
        // jump into the signed data and inject the signature where we 
        // previously placed a bunch of zeroes
        int signatureOffset = offset 
                              //+ 4 // sendStreamId
                              //+ 4 // receiveStreamId
                              //+ 4 // sequenceNum
                              //+ 4 // ackThrough
                              //+ 1 // resendDelay
                              //+ 2 // flags
                              //+ 2 // optionSize
                              + 21
                              + (_nacks != null ? 4*_nacks.length + 1 : 1)
                              + (isFlagSet(FLAG_DELAY_REQUESTED) ? 2 : 0)
                              + (isFlagSet(FLAG_FROM_INCLUDED) ? _optionFrom.size() : 0)
                              + (isFlagSet(FLAG_MAX_PACKET_SIZE_INCLUDED) ? 2 : 0);
        System.arraycopy(_optionSignature.getData(), 0, buffer, signatureOffset, _optionSignature.length());
        return size;
    }
    
    @Override
    public StringBuilder formatAsString() {
        StringBuilder buf = super.formatAsString();
        
        //if ( (_tagsSent != null) && (!_tagsSent.isEmpty()) ) 
        //    buf.append(" with tags");
        final int nackCount = _nackCount.get();
        if (nackCount > 0)
            buf.append(" nacked ").append(nackCount).append(" times");

        synchronized(this) {
            if (_ackOn > 0)
                buf.append(" ack after ").append(getAckTime());
        }
        
        int numSends = _numSends.get();
        if (numSends > 1)
            buf.append(" sent ").append(numSends).append(" times");
        
        if (isFlagSet(FLAG_SYNCHRONIZE |
                      FLAG_CLOSE |
                      FLAG_RESET)) {
         
            Connection con = _connection;
            if (con != null) {
                buf.append(" from ");
                Destination local = _session.getMyDestination();
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

    ////// begin WriteStatus methods

    /**
     * Blocks until outbound window is not full. See Connection.packetSendChoke().
     * @param maxWaitMs MessageOutputStream is the only caller, generally with -1
     */
    public void waitForAccept(int maxWaitMs) throws IOException, InterruptedException {
        long before = _context.clock().now();
        boolean accepted = false;
        try {
            // throws IOE or IE
            accepted = _connection.packetSendChoke(maxWaitMs);
        } finally {
            if (accepted) {
                _acceptedOn = _context.clock().now();
            } else {
                _acceptedOn = -1;
                releasePayload();
            }
            if ( (_acceptedOn - before > 1000) && (_log.shouldLog(Log.DEBUG)) )  {
                int queued = _connection.getUnackedPacketsSent();
                int window = _connection.getOptions().getWindowSize();
                int afterQueued = _connection.getUnackedPacketsSent();
                _log.debug("Took " + (_acceptedOn - before) + "ms to get " 
                           + (accepted ? "accepted" : "rejected")
                           + (_cancelledOn > 0 ? " and CANCELLED" : "")
                           + ", queued behind " + queued +" with a window size of " + window 
                           + ", finally accepted with " + afterQueued + " queued: " 
                           + toString());
            }
        }
    }
    
    /** block until the packet is acked from the far end */
    public void waitForCompletion(int maxWaitMs) throws IOException, InterruptedException {
        long expiration = _context.clock().now()+maxWaitMs;
        try {
            while (true) {
                long timeRemaining = expiration - _context.clock().now();
                if ( (timeRemaining <= 0) && (maxWaitMs > 0) ) break;
                synchronized (this) {
                    if (_ackOn > 0) break;
                    if (!_connection.getIsConnected()) {
                        if (_connection.getResetReceived())
                            throw new I2PSocketException(I2PSocketException.STATUS_CONNECTION_RESET);
                        throw new IOException("disconnected");
                    }
                    if (_cancelledOn > 0)
                        throw new IOException("cancelled");
                    if (timeRemaining > 60*1000)
                        timeRemaining = 60*1000;
                    else if (timeRemaining <= 0)
                        timeRemaining = 10*1000;
                    wait(timeRemaining);
                }
            }
        } finally {
            if (!writeSuccessful())
                releasePayload();
        }
    }
    
    public synchronized boolean writeAccepted() { return _acceptedOn > 0 && _cancelledOn <= 0; }
    public synchronized boolean writeFailed() { return _cancelledOn > 0; }
    public synchronized boolean writeSuccessful() { return _ackOn > 0 && _cancelledOn <= 0; }

    ////// end WriteStatus methods

    /** Generate a pcap/tcpdump-compatible format,
     *  so we can use standard debugging tools.
     */
    public void logTCPDump() {
            try {
                I2PSocketManagerFull.pcapWriter.write(this);
            } catch (IOException ioe) {
               _log.warn("pcap write ioe: " + ioe);
            }
    }
}
