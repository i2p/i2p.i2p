package net.i2p.router.transport.udp;

import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Maintain the outbound fragmentation for resending, for a single message.
 *
 */
class OutboundMessageState {
    private final I2PAppContext _context;
    private final Log _log;
    /** may be null if we are part of the establishment */
    private OutNetMessage _message;
    private long _messageId;
    /** will be null, unless we are part of the establishment */
    private PeerState _peer;
    private long _expiration;
    private ByteArray _messageBuf;
    /** fixed fragment size across the message */
    private int _fragmentSize;
    /** sends[i] is how many times the fragment has been sent, or -1 if ACKed */
    private short _fragmentSends[];
    private long _startedOn;
    private long _nextSendTime;
    private int _pushCount;
    private short _maxSends;
    // private int _nextSendFragment;
    /** for tracking use-after-free bugs */
    private boolean _released;
    private Exception _releasedBy;
    
    public static final int MAX_MSG_SIZE = 32 * 1024;
    /** is this enough for a high-bandwidth router? */
    private static final int MAX_ENTRIES = 64;
    /** would two caches, one for small and one for large messages, be better? */
    private static final ByteCache _cache = ByteCache.getInstance(MAX_ENTRIES, MAX_MSG_SIZE);
    
    public OutboundMessageState(I2PAppContext context) {
        _context = context;
        _log = _context.logManager().getLog(OutboundMessageState.class);
    }
    
/****
    public boolean initialize(OutNetMessage msg) {
        if (msg == null) return false;
        try {
            return initialize(msg, msg.getMessage(), null);
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error initializing " + msg, e);
            return false;
        }
    }
****/
    
    /**
     *  Called from UDPTransport
     */
    public boolean initialize(I2NPMessage msg, PeerState peer) {
        if (msg == null) 
            return false;
        
        try {
            return initialize(null, msg, peer);
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error initializing " + msg, e);
            return false;
        }
    }
    
    /**
     *  Called from OutboundMessageFragments
     */
    public boolean initialize(OutNetMessage m, I2NPMessage msg) {
        if ( (m == null) || (msg == null) ) 
            return false;
        
        try {
            return initialize(m, msg, null);
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error initializing " + msg, e);
            return false;
        }
    }
    
    private boolean initialize(OutNetMessage m, I2NPMessage msg, PeerState peer) {
        _message = m;
        _peer = peer;
        if (_messageBuf != null) {
            _cache.release(_messageBuf);
            _messageBuf = null;
        }

        _messageBuf = _cache.acquire();
        int size = msg.getRawMessageSize();
        if (size > _messageBuf.getData().length)
            throw new IllegalArgumentException("Size too large!  " + size + " in " + msg);
        try {
            int len = msg.toRawByteArray(_messageBuf.getData());
            _messageBuf.setValid(len);
            _messageId = msg.getUniqueId();

            _startedOn = _context.clock().now();
            _nextSendTime = _startedOn;
            _expiration = _startedOn + 10*1000;
            //_expiration = msg.getExpiration();

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Raw byte array for " + _messageId + ": " + Base64.encode(_messageBuf.getData(), 0, len));
            return true;
        } catch (IllegalStateException ise) {
            _cache.release(_messageBuf);
            _messageBuf = null;
            _released = true;
            return false;
        }
    }
    
    /**
     *  This is synchronized with writeFragment(),
     *  so we do not release (probably due to an ack) while we are retransmitting.
     *  Also prevent double-free
     */
    public synchronized void releaseResources() { 
        if (_messageBuf != null && !_released) {
            _cache.release(_messageBuf);
            _released = true;
            _releasedBy = new Exception ("Released on " + new Date() + " by:");
        }
        //_messageBuf = null;
    }
    
    public OutNetMessage getMessage() { return _message; }
    public long getMessageId() { return _messageId; }
    public PeerState getPeer() { return _peer; }
    public void setPeer(PeerState peer) { _peer = peer; }

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

    public int getUnackedSize() {
        short fragmentSends[] = _fragmentSends;
        ByteArray messageBuf = _messageBuf;
        int rv = 0;
        if ( (messageBuf != null) && (fragmentSends != null) ) {
            int totalSize = messageBuf.getValid();
            int lastSize = totalSize % _fragmentSize;
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
    public int getMaxSends() { return _maxSends; }
    public int getPushCount() { return _pushCount; }

    /** note that we have pushed the message fragments */
    public void push() { 
        _pushCount++; 
        if (_pushCount > _maxSends)
            _maxSends = (short)_pushCount;
        if (_fragmentSends != null)
            for (int i = 0; i < _fragmentSends.length; i++)
                if (_fragmentSends[i] >= (short)0)
                    _fragmentSends[i] = (short)(1 + _fragmentSends[i]);
        
    }

    public boolean isFragmented() { return _fragmentSends != null; }

    /**
     * Prepare the message for fragmented delivery, using no more than
     * fragmentSize bytes per fragment.
     *
     */
    public void fragment(int fragmentSize) {
        int totalSize = _messageBuf.getValid();
        int numFragments = totalSize / fragmentSize;
        if (numFragments * fragmentSize < totalSize)
            numFragments++;
        // This should never happen, as 534 bytes * 64 fragments > 32KB, and we won't bid on > 32KB
        if (numFragments > InboundMessageState.MAX_FRAGMENTS)
            throw new IllegalArgumentException("Fragmenting a " + totalSize + " message into " + numFragments + " fragments - too many!");
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fragmenting a " + totalSize + " message into " + numFragments + " fragments");
        
        //_fragmentEnd = new int[numFragments];
        _fragmentSends = new short[numFragments];
        //Arrays.fill(_fragmentEnd, -1);
        //Arrays.fill(_fragmentSends, (short)0);
        
        _fragmentSize = fragmentSize;
    }

    /** how many fragments in the message */
    public int getFragmentCount() { 
        if (_fragmentSends == null) 
            return -1;
        else
            return _fragmentSends.length; 
    }

    public int getFragmentSize() { return _fragmentSize; }

    /** should we continue sending this fragment? */
    public boolean shouldSend(int fragmentNum) { return _fragmentSends[fragmentNum] >= (short)0; }

    public int fragmentSize(int fragmentNum) {
        if (_messageBuf == null) return -1;
        if (fragmentNum + 1 == _fragmentSends.length) {
            int valid = _messageBuf.getValid();
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
     * See releaseResources() above for synchhronization information.
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
        if ( (buf != null) && (start + toSend < buf.length) && (out != null) && (outOffset + toSend < out.length) ) {
            System.arraycopy(_messageBuf.getData(), start, out, outOffset, toSend);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Raw fragment[" + fragmentNum + "] for " + _messageId 
                           + "[" + start + "-" + (start+toSend) + "/" + _messageBuf.getValid() + "/" + _fragmentSize + "]: " 
                           + Base64.encode(out, outOffset, toSend));
            return toSend;
        } else {
            return -1;
        }
    }
    
    @Override
    public String toString() {
        short sends[] = _fragmentSends;
        ByteArray messageBuf = _messageBuf;
        StringBuilder buf = new StringBuilder(256);
        buf.append("OB Message ").append(_messageId);
        if (sends != null)
            buf.append(" with ").append(sends.length).append(" fragments");
        if (messageBuf != null)
            buf.append(" of size ").append(messageBuf.getValid());
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
