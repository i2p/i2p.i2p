package net.i2p.router.transport.udp;

import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.util.CDPQEntry;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Maintain the outbound fragmentation for resending, for a single message.
 *
 */
class OutboundMessageState implements CDPQEntry {
    private final I2PAppContext _context;
    private final Log _log;
    /** may be null if we are part of the establishment */
    private final OutNetMessage _message;
    private final I2NPMessage _i2npMessage;
    private final long _messageId;
    /** will be null, unless we are part of the establishment */
    private final PeerState _peer;
    private final long _expiration;
    private ByteArray _messageBuf;
    /** fixed fragment size across the message */
    private int _fragmentSize;
    /** size of the I2NP message */
    private int _totalSize;
    /** sends[i] is how many times the fragment has been sent, or -1 if ACKed */
    private short _fragmentSends[];
    private final long _startedOn;
    private long _nextSendTime;
    private int _pushCount;
    private short _maxSends;
    // private int _nextSendFragment;
    /** for tracking use-after-free bugs */
    private boolean _released;
    private Exception _releasedBy;
    // we can't use the ones in _message since it is null for injections
    private long _enqueueTime;
    private long _seqNum;
    
    public static final int MAX_MSG_SIZE = 32 * 1024;
    private static final int CACHE4_BYTES = MAX_MSG_SIZE;
    private static final int CACHE3_BYTES = CACHE4_BYTES / 4;
    private static final int CACHE2_BYTES = CACHE3_BYTES / 4;
    private static final int CACHE1_BYTES = CACHE2_BYTES / 4;

    private static final int CACHE1_MAX = 256;
    private static final int CACHE2_MAX = CACHE1_MAX / 4;
    private static final int CACHE3_MAX = CACHE2_MAX / 4;
    private static final int CACHE4_MAX = CACHE3_MAX / 4;

    private static final ByteCache _cache1 = ByteCache.getInstance(CACHE1_MAX, CACHE1_BYTES);
    private static final ByteCache _cache2 = ByteCache.getInstance(CACHE2_MAX, CACHE2_BYTES);
    private static final ByteCache _cache3 = ByteCache.getInstance(CACHE3_MAX, CACHE3_BYTES);
    private static final ByteCache _cache4 = ByteCache.getInstance(CACHE4_MAX, CACHE4_BYTES);

    private static final long EXPIRATION = 10*1000;
    

    /**
     *  Called from UDPTransport
     *  @throws IAE if too big or if msg or peer is null
     */
    public OutboundMessageState(I2PAppContext context, I2NPMessage msg, PeerState peer) {
        this(context, null, msg, peer);
    }
    
    /**
     *  Called from OutboundMessageFragments
     *  @throws IAE if too big or if msg or peer is null
     */
    public OutboundMessageState(I2PAppContext context, OutNetMessage m, PeerState peer) {
        this(context, m, m.getMessage(), peer);
    }
    
    /**
     *  Internal.
     *  @param m null if msg is "injected"
     *  @throws IAE if too big or if msg or peer is null
     */
    private OutboundMessageState(I2PAppContext context, OutNetMessage m, I2NPMessage msg, PeerState peer) {
        if (msg == null || peer == null)
            throw new IllegalArgumentException();
        _context = context;
        _log = _context.logManager().getLog(OutboundMessageState.class);
        _message = m;
        _i2npMessage = msg;
        _peer = peer;
        _messageId = msg.getUniqueId();
        _startedOn = _context.clock().now();
        _nextSendTime = _startedOn;
        _expiration = _startedOn + EXPIRATION;
        //_expiration = msg.getExpiration();

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Raw byte array for " + _messageId + ": " + Base64.encode(_messageBuf.getData(), 0, len));
    }
    
    /**
     * lazily inits the message buffer unless already inited
     */
    private synchronized void initBuf() {
        if (_messageBuf != null)
            return;
        final int size = _i2npMessage.getRawMessageSize();
        acquireBuf(size);
        _totalSize = _i2npMessage.toRawByteArray(_messageBuf.getData());
        _messageBuf.setValid(_totalSize);
    }
    
    /**
     *  @throws IAE if too big
     *  @since 0.9.3
     */
    private void acquireBuf(int size) {
        if (_messageBuf != null)
            releaseBuf();
        if (size <= CACHE1_BYTES)
            _messageBuf =  _cache1.acquire();
        else if (size <= CACHE2_BYTES)
            _messageBuf = _cache2.acquire();
        else if (size <= CACHE3_BYTES)
            _messageBuf = _cache3.acquire();
        else if (size <= CACHE4_BYTES)
            _messageBuf = _cache4.acquire();
        else
            throw new IllegalArgumentException("Size too large! " + size);
    }
    
    /**
     *  @since 0.9.3
     */
    private void releaseBuf() {
        if (_messageBuf == null)
            return;
        int size = _messageBuf.getData().length;
        if (size == CACHE1_BYTES)
            _cache1.release(_messageBuf);
        else if (size == CACHE2_BYTES)
            _cache2.release(_messageBuf);
        else if (size == CACHE3_BYTES)
            _cache3.release(_messageBuf);
        else if (size == CACHE4_BYTES)
            _cache4.release(_messageBuf);
        _messageBuf = null;
        _released = true;
    }

    /**
     *  This is synchronized with writeFragment(),
     *  so we do not release (probably due to an ack) while we are retransmitting.
     *  Also prevent double-free
     */
    public synchronized void releaseResources() { 
        if (_messageBuf != null && !_released) {
            releaseBuf();
            if (_log.shouldLog(Log.WARN))
                _releasedBy = new Exception ("Released on " + new Date() + " by:");
        }
        //_messageBuf = null;
    }
    
    public OutNetMessage getMessage() { return _message; }
    public long getMessageId() { return _messageId; }
    public PeerState getPeer() { return _peer; }

    public boolean isExpired() {
        return _expiration < _context.clock().now(); 
    }

    public boolean isComplete() {
        short sends[] = _fragmentSends;
        if (sends == null) return false;
        for (int i = 0; i < sends.length; i++)
            if (sends[i] >= 0)
                return false;
        // nothing else pending ack
        return true;
    }

    public synchronized int getUnackedSize() {
        short fragmentSends[] = _fragmentSends;
        ByteArray messageBuf = _messageBuf;
        int rv = 0;
        if ( (messageBuf != null) && (fragmentSends != null) ) {
            int lastSize = _totalSize % _fragmentSize;
            if (lastSize == 0)
                lastSize = _fragmentSize;
            for (int i = 0; i < fragmentSends.length; i++) {
                if (fragmentSends[i] >= (short)0) {
                    if (i + 1 == fragmentSends.length)
                        rv += lastSize;
                    else
                        rv += _fragmentSize;
                }
            }
        }
        return rv;
    }

    public boolean needsSending(int fragment) {
        
        short sends[] = _fragmentSends;
        if ( (sends == null) || (fragment >= sends.length) || (fragment < 0) )
            return false;
        return (sends[fragment] >= (short)0);
    }

    public long getLifetime() { return _context.clock().now() - _startedOn; }
    
    /**
     * Ack all the fragments in the ack list.  As a side effect, if there are
     * still unacked fragments, the 'next send' time will be updated under the
     * assumption that that all of the packets within a volley would reach the
     * peer within that ack frequency (2-400ms).
     *
     * @return true if the message was completely ACKed
     */
    public boolean acked(ACKBitfield bitfield) {
        // stupid brute force, but the cardinality should be trivial
        short sends[] = _fragmentSends;
        if (sends != null)
            for (int i = 0; i < bitfield.fragmentCount() && i < sends.length; i++)
                if (bitfield.received(i))
                    sends[i] = (short)-1;
        
        boolean rv = isComplete();
      /****
        if (!rv && false) { // don't do the fast retransmit... lets give it time to get ACKed
            long nextTime = _context.clock().now() + Math.max(_peer.getRTT(), ACKSender.ACK_FREQUENCY);
            //_nextSendTime = Math.max(now, _startedOn+PeerState.MIN_RTO);
            if (_nextSendTime <= 0)
                _nextSendTime = nextTime;
            else
                _nextSendTime = Math.min(_nextSendTime, nextTime);
            
            //if (now + 100 > _nextSendTime)
            //    _nextSendTime = now + 100;
            //_nextSendTime = now;
        }
      ****/
        return rv;
    }
    
    public long getNextSendTime() { return _nextSendTime; }
    public void setNextSendTime(long when) { _nextSendTime = when; }

    /**
     *  The max number of sends for any fragment, which is the
     *  same as the push count, at least as it's coded now.
     */
    public int getMaxSends() { return _maxSends; }

    /**
     *  The number of times we've pushed some fragments, which is the
     *  same as the max sends, at least as it's coded now.
     */
    public int getPushCount() { return _pushCount; }

    /** note that we have pushed the message fragments */
    public void push() { 
        // these will never be different...
        _pushCount++; 
        if (_pushCount > _maxSends)
            _maxSends = (short)_pushCount;
        if (_fragmentSends != null)
            for (int i = 0; i < _fragmentSends.length; i++)
                if (_fragmentSends[i] >= (short)0)
                    _fragmentSends[i] = (short)(1 + _fragmentSends[i]);
        
    }

    /**
     * Whether fragment() has been called.
     * NOT whether it has more than one fragment.
     *
     * Caller should synchronize
     *
     * @return true iff fragment() has been called previously
     */
    public boolean isFragmented() { return _fragmentSends != null; }

    /**
     * Prepare the message for fragmented delivery, using no more than
     * fragmentSize bytes per fragment.
     *
     * Caller should synchronize
     *
     * @throws IllegalStateException if called more than once
     */
    public void fragment(int fragmentSize) {
        if (_fragmentSends != null)
            throw new IllegalStateException();
        initBuf();
        int numFragments = _totalSize / fragmentSize;
        if (numFragments * fragmentSize < _totalSize)
            numFragments++;
        // This should never happen, as 534 bytes * 64 fragments > 32KB, and we won't bid on > 32KB
        if (numFragments > InboundMessageState.MAX_FRAGMENTS)
            throw new IllegalArgumentException("Fragmenting a " + _totalSize + " message into " + numFragments + " fragments - too many!");
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fragmenting a " + _totalSize + " message into " + numFragments + " fragments");
        
        //_fragmentEnd = new int[numFragments];
        _fragmentSends = new short[numFragments];
        //Arrays.fill(_fragmentEnd, -1);
        //Arrays.fill(_fragmentSends, (short)0);
        
        _fragmentSize = fragmentSize;
    }

    /**
     * How many fragments in the message.
     * Only valid after fragment() has been called.
     * Returns -1 before then.
     *
     * Caller should synchronize
     */
    public int getFragmentCount() { 
        if (_fragmentSends == null) 
            return -1;
        else
            return _fragmentSends.length; 
    }

    /**
     * The size of the I2NP message. Does not include any SSU overhead.
     *
     * Caller should synchronize
     */
    public int getMessageSize() { return _totalSize; }

    /**
     * Should we continue sending this fragment?
     * Only valid after fragment() has been called.
     * Throws NPE before then.
     *
     * Caller should synchronize
     */
    public boolean shouldSend(int fragmentNum) { return _fragmentSends[fragmentNum] >= (short)0; }

    /**
     * This assumes fragment(int size) has been called
     * @param fragmentNum the number of the fragment 
     * @return the size of the fragment specified by the number
     */
    public int fragmentSize(int fragmentNum) {
        if (_messageBuf == null) return -1;
        if (fragmentNum + 1 == _fragmentSends.length) {
            int valid = _totalSize;
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
     * See releaseResources() above for synchronization information.
     * This assumes fragment(int size) has been called.
     *
     * @param out target to write
     * @param outOffset into outOffset to begin writing
     * @param fragmentNum fragment to write (0 indexed)
     * @return bytesWritten
     */
    public synchronized int writeFragment(byte out[], int outOffset, int fragmentNum) {
        if (_messageBuf == null) return -1;
        if (_released) {
            /******
                Solved by synchronization with releaseResources() and simply returning -1.
                Previous output:

                23:50:57.013 ERROR [acket pusher] sport.udp.OutboundMessageState: SSU OMS Use after free
                java.lang.Exception: Released on Wed Dec 23 23:50:57 GMT 2009 by:
                	at net.i2p.router.transport.udp.OutboundMessageState.releaseResources(OutboundMessageState.java:133)
                	at net.i2p.router.transport.udp.PeerState.acked(PeerState.java:1391)
                	at net.i2p.router.transport.udp.OutboundMessageFragments.acked(OutboundMessageFragments.java:404)
                	at net.i2p.router.transport.udp.InboundMessageFragments.receiveACKs(InboundMessageFragments.java:191)
                	at net.i2p.router.transport.udp.InboundMessageFragments.receiveData(InboundMessageFragments.java:77)
                	at net.i2p.router.transport.udp.PacketHandler$Handler.handlePacket(PacketHandler.java:485)
                	at net.i2p.router.transport.udp.PacketHandler$Handler.receivePacket(PacketHandler.java:282)
                	at net.i2p.router.transport.udp.PacketHandler$Handler.handlePacket(PacketHandler.java:231)
                	at net.i2p.router.transport.udp.PacketHandler$Handler.run(PacketHandler.java:136)
                	at java.lang.Thread.run(Thread.java:619)
                	at net.i2p.util.I2PThread.run(I2PThread.java:71)
                23:50:57.014 ERROR [acket pusher] ter.transport.udp.PacketPusher: SSU Output Queue Error
                java.lang.RuntimeException: SSU OMS Use after free: Message 2381821417 with 4 fragments of size 0 volleys: 2 lifetime: 1258 pending fragments: 0 1 2 3 
                	at net.i2p.router.transport.udp.OutboundMessageState.writeFragment(OutboundMessageState.java:298)
                	at net.i2p.router.transport.udp.PacketBuilder.buildPacket(PacketBuilder.java:170)
                	at net.i2p.router.transport.udp.OutboundMessageFragments.preparePackets(OutboundMessageFragments.java:332)
                	at net.i2p.router.transport.udp.OutboundMessageFragments.getNextVolley(OutboundMessageFragments.java:297)
                	at net.i2p.router.transport.udp.PacketPusher.run(PacketPusher.java:38)
                	at java.lang.Thread.run(Thread.java:619)
                	at net.i2p.util.I2PThread.run(I2PThread.java:71)
            *******/
            if (_log.shouldLog(Log.WARN))
                _log.log(Log.WARN, "SSU OMS Use after free: " + toString(), _releasedBy);
            return -1;
            //throw new RuntimeException("SSU OMS Use after free: " + toString());
        }
        int start = _fragmentSize * fragmentNum;
        int end = start + fragmentSize(fragmentNum);
        int toSend = end - start;
        byte buf[] = _messageBuf.getData();
        if ( (buf != null) && (start + toSend <= buf.length) && (outOffset + toSend <= out.length) ) {
            System.arraycopy(buf, start, out, outOffset, toSend);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Raw fragment[" + fragmentNum + "] for " + _messageId 
                           + "[" + start + "-" + (start+toSend) + "/" + _totalSize + "/" + _fragmentSize + "]: " 
                           + Base64.encode(out, outOffset, toSend));
            return toSend;
        } else if (buf == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error: null buf");
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
        releaseResources();
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
        short sends[] = _fragmentSends;
        StringBuilder buf = new StringBuilder(256);
        buf.append("OB Message ").append(_messageId);
        if (sends != null)
            buf.append(" with ").append(sends.length).append(" fragments");
        buf.append(" of size ").append(_totalSize);
        buf.append(" volleys: ").append(_maxSends);
        buf.append(" lifetime: ").append(getLifetime());
        if (sends != null) {
            buf.append(" pending fragments: ");
            for (int i = 0; i < sends.length; i++)
                if (sends[i] >= 0)
                    buf.append(i).append(' ');
        }
        return buf.toString();
    }
}
