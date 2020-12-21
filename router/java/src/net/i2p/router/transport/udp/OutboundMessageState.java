package net.i2p.router.transport.udp;

import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.transport.udp.PacketBuilder.Fragment;
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
    /** sends for each fragment, or null if only one fragment */
    private final byte _fragmentSends[];
    private final long _startedOn;
    private int _pushCount;
    private int _maxSends;
    // we can't use the ones in _message since it is null for injections
    private long _enqueueTime;
    private long _seqNum;
    /** how many bytes push() is allowed to send */
    private int _allowedSendBytes;
    
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
        _fragmentSends = (numFragments > 1) ? new byte[numFragments] : null;
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

    /**
     *  As of 0.9.49, includes packet overhead
     */
    public synchronized int getUnackedSize() {
        int rv = 0;
        if (isComplete())
            return rv;
        int lastSize = _messageBuf.length % _fragmentSize;
        if (lastSize == 0)
            lastSize = _fragmentSize;
        int overhead = _peer.fragmentOverhead();
        for (int i = 0; i < _numFragments; i++) {
            if (needsSending(i)) {
                if (i + 1 == _numFragments)
                    rv += lastSize;
                else
                    rv += _fragmentSize;
                rv += overhead;
            }
        }
        return rv;
    }

    /**
     * @return count of unacked fragments
     * @since 0.9.49
     */
    public synchronized int getUnackedFragments() {
        if (isComplete())
            return 0;
        if (_numFragments == 1)
            return 1;
        int rv = 0;
        for (int i = 0; i < _numFragments; i++) {
            if (needsSending(i))
                rv++;
        }
        return rv;
    }

    /**
     *  Is any fragment unsent?
     *
     *  @since 0.9.49
     */
    public synchronized boolean hasUnsentFragments() {
        if (isComplete())
            return false;
        if (_numFragments == 1)
            return _maxSends == 0;
        for (int i = _numFragments - 1; i >= 0; i--) {
            if (_fragmentSends[i] == 0)
                return true;
        }
        return false;
    }

    /**
     *  The min send count of unacked fragments.
     *  Only call if not complete and _numFragments greater than 1.
     *  Caller must synch.
     *
     *  @since 0.9.49
     */
    private int getMinSendCount() {
        int rv = 127;
        for (int i = 0; i < _numFragments; i++) {
            if (needsSending(i)) {
                int count = _fragmentSends[i];
                if (count < rv)
                    rv = count;
            }
        }
        return rv;
    }

    /**
     *  The minimum number of bytes we can send, which is the smallest unacked fragment we will send next.
     *  Includes packet overhead.
     *
     *  @return 0 to total size
     *  @since 0.9.49
     */
    public synchronized int getMinSendSize() {
        if (isComplete())
            return 0;
        int overhead = _peer.fragmentOverhead();
        if (_numFragments == 1)
            return _messageBuf.length + overhead;
        if (_pushCount == 0)
            return fragmentSize(_numFragments - 1) + overhead;
        int minSendCount = getMinSendCount();
        int rv = _fragmentSize;
        for (int i = 0; i < _numFragments; i++) {
            if (needsSending(i) && _fragmentSends[i] == minSendCount) {
                int sz = fragmentSize(i) + overhead;
                if (sz < rv) {
                    rv = sz;
                }
            }
        }
        return rv;
    }

    /**
     *  How many bytes we can send under the max given.
     *  Side effect: if applicable, amount to send will be saved for the push() call.
     *  Note: With multiple fragments, this will allocate only the fragments with the lowest push count.
     *  Example: If push counts are 1 1 1 0 0, this will only return the size of the last two fragments,
     *  even if any of the first three need to be retransmitted.
     *  Includes packet overhead.
     *
     *  @param max the maximum number of bytes we can send, including packet overhead
     *  @return 0 to max bytes
     *  @since 0.9.49
     */
    public synchronized int getSendSize(int max) {
        if (isComplete())
            return 0;
        int overhead = _peer.fragmentOverhead();
        if (_numFragments == 1) {
            int rv = _messageBuf.length + overhead;
            return rv <= max ? rv : 0;
        }
        // find the fragments we've sent the least
        int minSendCount = getMinSendCount();
        // Allow only the fragments we've sent the least
        int rv = 0;
        // see below for why we go in reverse order
        for (int i = _numFragments - 1; i >= 0; i--) {
            if (needsSending(i) && _fragmentSends[i] == minSendCount) {
                int sz = fragmentSize(i) + overhead;
                int tot = rv + sz;
                if (tot <= max) {
                    rv = tot;
                } else {
                    if (_log.shouldInfo())
                        _log.info("Send window limited to " + (max - rv) + ", not sending fragment " + i + " for " + toString());
                }
            }
        }
        if (rv > 0)
            _allowedSendBytes = rv;
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
    
    /**
     *  The max number of sends for any fragment.
     *  As of 0.9.49, may be less than getPushCount() if we pushed only some fragments
     */
    public synchronized int getMaxSends() { return _maxSends; }

    /**
     *  The number of times we've pushed some fragments.
     *  As of 0.9.49, may be greater than getMaxSends() if we pushed only some fragments.
     */
    public synchronized int getPushCount() { return _pushCount; }

    /**
     *  Add fragments up to the number of bytes allowed by setAllowedSendBytes()
     *  Side effects: Clears setAllowedSendBytes. Increments pushCount. Increments maxSends if applicable.
     *  Note: With multiple fragments, this will send only the fragments with the lowest push count.
     *  Example: If push counts are 1 1 1 0 0, this will only send the last two fragments,
     *  even if any of the first three need to be retransmitted.
     *
     *  @param toSend out parameter
     *  @return the number of Fragments added
     *  @since 0.9.49
     */
    public synchronized int push(List<Fragment> toSend) { 
        int rv = 0;
        if (_allowedSendBytes <= 0 || _numFragments == 1) {
            // easy way
            // send all, or only one fragment
            for (int i = 0; i < _numFragments; i++) {
                if (needsSending(i)) {
                    toSend.add(new Fragment(this, i));
                    rv++;
                    if (_fragmentSends != null) {
                        _fragmentSends[i]++;
                        if (_fragmentSends[i] > _maxSends)
                            _maxSends = _fragmentSends[i];
                    }
                }
            }
            if (_fragmentSends == null)
                _maxSends++;
        } else {
            // hard way.
            // send the fragments we've sent the least, up to the max size
            int minSendCount = getMinSendCount();
            int sent = 0;
            int overhead = _peer.fragmentOverhead();
            // Send in reverse order,
            // receiver bug workaround.
            // Through 0.9.48, InboundMessageState.PartialBitfield.isComplete() would return true
            // if consecutive fragments were complete, and we would not receive partial acks.
            // Also, if we send the last fragment first, receiver can be more efficient
            // because it knows the size.
            for (int i = _numFragments - 1; i >= 0; i--) {
                if (needsSending(i)) {
                    int count = _fragmentSends[i];
                    if (count == minSendCount) {
                        int sz = fragmentSize(i) + overhead;
                        if (sz <= _allowedSendBytes - sent) {
                            sent += sz;
                            toSend.add(new Fragment(this, i));
                            rv++;
                            _fragmentSends[i]++;
                            if (_fragmentSends[i] > _maxSends)
                                _maxSends = _fragmentSends[i];
                            if (_allowedSendBytes - sent <= overhead)
                                break;
                        }
                    }
                }
            }
        }
        if (rv > 0) {
            _pushCount++;
            if (_log.shouldDebug())
                _log.debug("Pushed " + rv + " fragments for " + toString());
        } else {
            if (_log.shouldDebug())
                _log.debug("Nothing pushed??? allowedSendBytes=" + _allowedSendBytes + " for " + toString());
        }
        _allowedSendBytes = 0;
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
     * The size in bytes of the fragment.
     * Does NOT include any SSU overhead.
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
     * @return bytesWritten, NOT including packet overhead
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
        buf.append(" size ").append(_messageBuf.length);
        if (_numFragments > 1)
            buf.append(" fragments: ").append(_numFragments);
        buf.append(" volleys: ").append(_maxSends);
        buf.append(" lifetime: ").append(getLifetime());
        if (!isComplete()) {
            if (_fragmentSends != null) {
                buf.append(" unacked fragments: ");
                for (int i = 0; i < _numFragments; i++) {
                    if (needsSending(i))
                        buf.append(i).append(' ');
                }
                buf.append("sizes: ");
                for (int i = 0; i < _numFragments; i++) {
                    buf.append(fragmentSize(i)).append(' ');
                }
                buf.append("send counts: ");
                for (int i = 0; i < _numFragments; i++) {
                    buf.append(_fragmentSends[i]).append(' ');
                }
            } else {
                buf.append(" unacked");
            }
        }
        //buf.append(" to: ").append(_peer.toString());
        return buf.toString();
    }
}
