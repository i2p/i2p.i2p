package net.i2p.router.transport.udp;

import java.util.Arrays;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.I2PAppContext;
import net.i2p.router.OutNetMessage;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Maintain the outbound fragmentation for resending
 *
 */
public class OutboundMessageState {
    private I2PAppContext _context;
    private Log _log;
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
    
    public static final int MAX_FRAGMENTS = 32;
    private static final ByteCache _cache = ByteCache.getInstance(64, MAX_FRAGMENTS*1024);
    
    public OutboundMessageState(I2PAppContext context) {
        _context = context;
        _log = _context.logManager().getLog(OutboundMessageState.class);
        _pushCount = 0;
        _maxSends = 0;
    }
    
    public synchronized boolean initialize(OutNetMessage msg) {
        try {
            initialize(msg, msg.getMessage(), null);
            return true;
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error initializing " + msg, e);
            return false;
        }
    }
    
    public boolean initialize(I2NPMessage msg, PeerState peer) {
        try {
            initialize(null, msg, peer);
            return true;
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error initializing " + msg, e);
            return false;
        }
    }
    
    private void initialize(OutNetMessage m, I2NPMessage msg, PeerState peer) {
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
        int len = msg.toRawByteArray(_messageBuf.getData());
        _messageBuf.setValid(len);
        _messageId = msg.getUniqueId();
        
        _startedOn = _context.clock().now();
        _nextSendTime = _startedOn;
        _expiration = _startedOn + 10*1000;
        //_expiration = msg.getExpiration();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Raw byte array for " + _messageId + ": " + Base64.encode(_messageBuf.getData(), 0, len));
    }
    
    public OutNetMessage getMessage() { return _message; }
    public long getMessageId() { return _messageId; }
    public PeerState getPeer() { return _peer; }
    public boolean isExpired() {
        return _expiration < _context.clock().now(); 
    }
    public boolean isComplete() {
        if (_fragmentSends == null) return false;
        for (int i = 0; i < _fragmentSends.length; i++)
            if (_fragmentSends[i] >= 0)
                return false;
        // nothing else pending ack
        return true;
    }
    public long getLifetime() { return _context.clock().now() - _startedOn; }
    
    /**
     * Ack all the fragments in the ack list
     */
    public void acked(int ackedFragments[]) {
        // stupid brute force, but the cardinality should be trivial
        for (int i = 0; i < ackedFragments.length; i++) {
            if ( (ackedFragments[i] < 0) || (ackedFragments[i] >= _fragmentSends.length) )
                continue;
            _fragmentSends[ackedFragments[i]] = -1;
        }
    }
    
    public long getNextSendTime() { return _nextSendTime; }
    public void setNextSendTime(long when) { _nextSendTime = when; }
    public int getMaxSends() { return _maxSends; }
    public int getPushCount() { return _pushCount; }
    /** note that we have pushed the message fragments */
    public void push() { _pushCount++; }
    public boolean isFragmented() { return _fragmentSends != null; }
    /**
     * Prepare the message for fragmented delivery, using no more than
     * fragmentSize bytes per fragment.
     *
     */
    public void fragment(int fragmentSize) {
        int totalSize = _messageBuf.getValid();
        int numFragments = totalSize / fragmentSize;
        if (numFragments * fragmentSize != totalSize)
            numFragments++;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Fragmenting a " + totalSize + " message into " + numFragments + " fragments");
        
        //_fragmentEnd = new int[numFragments];
        _fragmentSends = new short[numFragments];
        //Arrays.fill(_fragmentEnd, -1);
        Arrays.fill(_fragmentSends, (short)0);
        
        _fragmentSize = fragmentSize;
    }
    /** how many fragments in the message */
    public int getFragmentCount() { 
        if (_fragmentSends == null) 
            return -1;
        else
            return _fragmentSends.length; 
    }
    /** should we continue sending this fragment? */
    public boolean shouldSend(int fragmentNum) { return _fragmentSends[fragmentNum] >= (short)0; }
    public int fragmentSize(int fragmentNum) {
        if (fragmentNum + 1 == _fragmentSends.length)
            return _messageBuf.getValid() % _fragmentSize;
        else
            return _fragmentSize;
    }

    /**
     * Pick a fragment that we still need to send.  Current implementation 
     * picks the fragment which has been sent the least (randomly choosing 
     * among equals), incrementing the # sends of the winner in the process.
     *
     * @return fragment index, or -1 if all of the fragments were acked
     */
    public int pickNextFragment() {
        short minValue = -1;
        int minIndex = -1;
        int startOffset = _context.random().nextInt(_fragmentSends.length);
        for (int i = 0; i < _fragmentSends.length; i++) {
            int cur = (i + startOffset) % _fragmentSends.length;
            if (_fragmentSends[cur] < (short)0)
                continue;
            else if ( (minValue < (short)0) || (_fragmentSends[cur] < minValue) ) {
                minValue = _fragmentSends[cur];
                minIndex = cur;
            }
        }
        if (minIndex >= 0) {
            _fragmentSends[minIndex]++;
            if (_fragmentSends[minIndex] > _maxSends)
                _maxSends = _fragmentSends[minIndex];
        }
        
        // if all fragments have now been sent an equal number of times,
        // lets give pause for an ACK
        boolean endOfVolley = true;
        for (int i = 0; i < _fragmentSends.length; i++) {
            if (_fragmentSends[i] < (short)0)
                continue;
            if (_fragmentSends[i] != (short)_pushCount+1) {
                endOfVolley = false;
                break;
            }
        }
        if (endOfVolley)
            _pushCount++;
        
        
        if (_log.shouldLog(Log.DEBUG)) {
            StringBuffer buf = new StringBuffer(64);
            buf.append("Next fragment is ").append(minIndex);
            if (minIndex >= 0) {
                buf.append(" (#sends: ").append(_fragmentSends[minIndex]-1);
                buf.append(" #fragments: ").append(_fragmentSends.length);
                buf.append(")");
            }
            _log.debug(buf.toString());
        }
        return minIndex;
    }
    
    /**
     * Write a part of the the message onto the specified buffer.
     *
     * @param out target to write
     * @param outOffset into outOffset to begin writing
     * @param fragmentNum fragment to write (0 indexed)
     * @return bytesWritten
     */
    public synchronized int writeFragment(byte out[], int outOffset, int fragmentNum) {
        int start = _fragmentSize * fragmentNum;
        int end = start + _fragmentSize;
        if (end > _messageBuf.getValid())
            end = _messageBuf.getValid();
        int toSend = end - start;
        System.arraycopy(_messageBuf.getData(), start, out, outOffset, toSend);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Raw fragment[" + fragmentNum + "] for " + _messageId + ": " 
                       + Base64.encode(_messageBuf.getData(), start, toSend));
        return toSend;
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(64);
        buf.append("Message ").append(_messageId);
        if (_fragmentSends != null)
            buf.append(" with ").append(_fragmentSends.length).append(" fragments");
        if (_messageBuf != null)
            buf.append(" of size ").append(_messageBuf.getValid());
        buf.append(" volleys: ").append(_maxSends);
        buf.append(" lifetime: ").append(getLifetime());
        return buf.toString();
    }
}
