package net.i2p.router.transport.udp;

import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.util.CDPQEntry;
import net.i2p.util.Log;

/**
 * Maintain the outbound fragmentation for resending, for a single message.
 *
 * All methods are thread-safe.
 *
 */
class OutboundMessageState implements CDPQEntry {
    private final I2PAppContext _context;
    private final Log _log;
    /** may be null if we are part of the establishment */
    private final OutNetMessage _message;
    private final I2NPMessage _i2npMessage;
    /** will be null, unless we are part of the establishment */
    private final PeerState _peer;
    private final long _expiration;
    private final byte[] _messageBuf;
    /** fixed fragment size across the message */
    private final int _fragmentSize;
    /** bitmask, 0 if acked, all 0 = complete */
    private long _fragmentAcks;
    private final int _numFragments;
    private final long _startedOn;
    private long _nextSendTime;
    private int _pushCount;
    private int _maxSends;
    // we can't use the ones in _message since it is null for injections
    private long _enqueueTime;
    private long _seqNum;
    
    public static final int MAX_MSG_SIZE = 32 * 1024;

    private static final long EXPIRATION = 10*1000;
    

    /**
     *  "injected" message from the establisher.
     *
     *  Called from UDPTransport.
     *  @throws IllegalArgumentException if too big or if msg or peer is null
     */
    public OutboundMessageState(I2PAppContext context, I2NPMessage msg, PeerState peer) {
        this(context, null, msg, peer);
    }
    
    /**
     *  Normal constructor.
     *
     *  Called from OutboundMessageFragments.
     *  @throws IllegalArgumentException if too big or if msg or peer is null
     */
    public OutboundMessageState(I2PAppContext context, OutNetMessage m, PeerState peer) {
        this(context, m, m.getMessage(), peer);
    }
    
    /**
     *  Internal.
     *  @param m null if msg is "injected"
     *  @throws IllegalArgumentException if too big or if msg or peer is null
     */
    private OutboundMessageState(I2PAppContext context, OutNetMessage m, I2NPMessage msg, PeerState peer) {
        if (msg == null || peer == null)
            throw new IllegalArgumentException();
        _context = context;
        _log = _context.logManager().getLog(OutboundMessageState.class);
        _message = m;
        _i2npMessage = msg;
        _peer = peer;
        _startedOn = _context.clock().now();
        _nextSendTime = _startedOn;
        _expiration = _startedOn + EXPIRATION;
        //_expiration = msg.getExpiration();

        // now "fragment" it
        int totalSize = _i2npMessage.getRawMessageSize();
        if (totalSize > MAX_MSG_SIZE)
            throw new IllegalArgumentException("Size too large! " + totalSize);
        _messageBuf = new byte[totalSize];
        _i2npMessage.toRawByteArray(_messageBuf);
        _fragmentSize = _peer.fragmentSize();
        int numFragments = totalSize / _fragmentSize;
        if (numFragments * _fragmentSize < totalSize)
            numFragments++;
        // This should never happen, as 534 bytes * 64 fragments > 32KB, and we won't bid on > 32KB
        if (numFragments > InboundMessageState.MAX_FRAGMENTS)
            throw new IllegalArgumentException("Fragmenting a " + totalSize + " message into " + numFragments + " fragments - too many!");
        _numFragments = numFragments;
        // all 1's where we care
        _fragmentAcks = _numFragments < 64 ? mask(_numFragments) - 1L : -1L;
    }
    
    /**
     *  @param fragment 0-63
     */
    private static long mask(int fragment) {
        return 1L << fragment;
    }

    public OutNetMessage getMessage() { return _message; }

    public long getMessageId() { return _i2npMessage.getUniqueId(); }

    public PeerState getPeer() { return _peer; }

    public boolean isExpired() {
        return _expiration < _context.clock().now(); 
    }

    /**
     * @since 0.9.38
     */
    public boolean isExpired(long now) {
        return _expiration < now; 
    }

    public synchronized boolean isComplete() {
        return _fragmentAcks == 0;
    }

    public synchronized int getUnackedSize() {
        int rv = 0;
        if (isComplete())
            return rv;
        int lastSize = _messageBuf.length % _fragmentSize;
        if (lastSize == 0)
            lastSize = _fragmentSize;
        for (int i = 0; i < _numFragments; i++) {
            if (needsSending(i)) {
                if (i + 1 == _numFragments)
                    rv += lastSize;
                else
                    rv += _fragmentSize;
            }
        }
        return rv;
    }

    public synchronized boolean needsSending(int fragment) {
        return (_fragmentAcks & mask(fragment)) != 0;
    }

    public long getLifetime() { return _context.clock().now() - _startedOn; }
    
    /**
     * Ack all the fragments in the ack list.
     *
     * @return true if the message was completely ACKed
     */
    public synchronized boolean acked(ACKBitfield bitfield) {
        // stupid brute force, but the cardinality should be trivial
        int highest = bitfield.highestReceived();
        for (int i = 0; i <= highest && i < _numFragments; i++) {
            if (bitfield.received(i))
                _fragmentAcks &= ~mask(i);
        }
        return isComplete();
    }
    
    public long getNextSendTime() { return _nextSendTime; }
    public void setNextSendTime(long when) { _nextSendTime = when; }

    /**
     *  The max number of sends for any fragment, which is the
     *  same as the push count, at least as it's coded now.
     */
    public synchronized int getMaxSends() { return _maxSends; }

    /**
     *  The number of times we've pushed some fragments, which is the
     *  same as the max sends, at least as it's coded now.
     */
    public synchronized int getPushCount() { return _pushCount; }

    /**
     * Note that we have pushed the message fragments.
     * Increments push count (and max sends... why?)
     * @return true if this is the first push
     */
    public synchronized boolean push() { 
        boolean rv = _pushCount == 0;
        // these will never be different...
        _pushCount++; 
        _maxSends = _pushCount;
        return rv;
    }

    /**
     * How many fragments in the message.
     */
    public int getFragmentCount() { 
            return _numFragments; 
    }

    /**
     * The size of the I2NP message. Does not include any SSU overhead.
     */
    public int getMessageSize() { return _messageBuf.length; }

    /**
     * The size in bytes of the fragment
     *
     * @param fragmentNum the number of the fragment 
     * @return the size of the fragment specified by the number
     */
    public int fragmentSize(int fragmentNum) {
        if (fragmentNum + 1 == _numFragments) {
            int valid = _messageBuf.length;
            if (valid <= _fragmentSize)
                return valid;
            // bugfix 0.8.12
            int mod = valid % _fragmentSize;
            return mod == 0 ? _fragmentSize : mod;
        } else {
            return _fragmentSize;
        }
    }

    /**
     * Write a part of the the message onto the specified buffer.
     *
     * @param out target to write
     * @param outOffset into outOffset to begin writing
     * @param fragmentNum fragment to write (0 indexed)
     * @return bytesWritten
     */
    public int writeFragment(byte out[], int outOffset, int fragmentNum) {
        int start = _fragmentSize * fragmentNum;
        int toSend = fragmentSize(fragmentNum);
        int end = start + toSend;
        if (end <= _messageBuf.length && outOffset + toSend <= out.length) {
            System.arraycopy(_messageBuf, start, out, outOffset, toSend);
            return toSend;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error: " + start + '/' + end + '/' + outOffset + '/' + out.length);
        }
        return -1;
    }
    
    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void setEnqueueTime(long now) {
        _enqueueTime = now;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public long getEnqueueTime() {
        return _enqueueTime;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void drop() {
        _peer.getTransport().failed(this, false);
    }

    /**
     *  For CDPQ
     *  @since 0.9.3
     */
    public void setSeqNum(long num) {
        _seqNum = num;
    }

    /**
     *  For CDPQ
     *  @since 0.9.3
     */
    public long getSeqNum() {
        return _seqNum;
    }

    /**
     *  For CDPQ
     *  @return OutNetMessage priority or 1000 for injected
     *  @since 0.9.3
     */
    public int getPriority() {
        return _message != null ? _message.getPriority() : 1000;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("OB Message ").append(_i2npMessage.getUniqueId());
        buf.append(" type ").append(_i2npMessage.getType());
        buf.append(" with ").append(_numFragments).append(" fragments");
        buf.append(" of size ").append(_messageBuf.length);
        buf.append(" volleys: ").append(_maxSends);
        buf.append(" lifetime: ").append(getLifetime());
        if (!isComplete()) {
            buf.append(" pending fragments: ");
            for (int i = 0; i < _numFragments; i++) {
                if (needsSending(i))
                    buf.append(i).append(' ');
            }
        }
        //buf.append(" to: ").append(_peer.toString());
        return buf.toString();
    }
}
