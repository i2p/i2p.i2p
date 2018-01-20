package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
//import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Stream that can be given messages out of order 
 * yet present them in order.
 *<p>
 * I2PSession -&gt; MessageHandler -&gt; PacketHandler -&gt; ConnectionPacketHandler -&gt; MessageInputStream
 *<p>
 * This buffers unlimited data via messageReceived() -
 * limiting / blocking is done in ConnectionPacketHandler.receivePacket().
 *
 */
class MessageInputStream extends InputStream {
    private final Log _log;
    /** 
     * List of ByteArray objects of data ready to be read,
     * with the first ByteArray at index 0, and the next
     * actual byte to be read at _readyDataBlockIndex of 
     * that array.
     *
     */
    private final List<ByteArray> _readyDataBlocks;
    /** current byte index into _readyDataBlocks.get(0) */
    private int _readyDataBlockIndex;
    /** highest message ID used in the readyDataBlocks */
    private long _highestReadyBlockId;
    /** highest overall message ID */
    private long _highestBlockId;
    /** 
     * Message ID (Long) to ByteArray for blocks received
     * out of order when there are lower IDs not yet 
     * received
     */
    private final Map<Long, ByteArray> _notYetReadyBlocks;
    /** 
     * if we have received a flag saying there won't be later messages, EOF
     * after we have cleared what we have received.
     */
    private boolean _closeReceived;
    /** if we don't want any more data, ignore the data */
    private boolean _locallyClosed;
    private int _readTimeout;
    private IOException _streamError;
    private long _readTotal;
    //private ByteCache _cache;
    private final int _maxMessageSize;
    private final int _maxWindowSize;
    private final int _maxBufferSize;
    private final byte[] _oneByte = new byte[1];
    private final Object _dataLock;

    /** only in _notYetReadyBlocks, never in _readyDataBlocks */
    private static final ByteArray DUMMY_BA = new ByteArray(null);
    
    private static final int MIN_READY_BUFFERS = 16;


    public MessageInputStream(I2PAppContext ctx, int maxMessageSize, int maxWindowSize, int maxBufferSize) {
        _log = ctx.logManager().getLog(MessageInputStream.class);
        _readyDataBlocks = new ArrayList<ByteArray>(4);
        _highestReadyBlockId = -1;
        _highestBlockId = -1;
        _readTimeout = -1;
        _notYetReadyBlocks = new HashMap<Long, ByteArray>(4);
        _dataLock = new Object();
        _maxMessageSize = maxMessageSize;
        _maxWindowSize = maxWindowSize;
        _maxBufferSize = maxBufferSize;
        //_cache = ByteCache.getInstance(128, Packet.MAX_PAYLOAD_SIZE);
    }
    
    /** What is the highest block ID we've completely received through?
     * @return highest data block ID completely received or -1 for none
     */
    public long getHighestReadyBlockId() { 
        synchronized (_dataLock) {
            return _highestReadyBlockId; 
        }
    }
    
    /**
     * @return highest data block ID received  or -1 for none
     */
    public long getHighestBlockId() { 
        synchronized (_dataLock) {
            return _highestBlockId;
        }
    }
    
    /**
     * @return true if this has been closed on the read side with close()
     */
    public boolean isLocallyClosed() { 
        synchronized (_dataLock) {
            return _locallyClosed;
        }
    }
    
    /**
     *  Determine if this packet will fit in our buffering limits.
     *
     *  Always returns true for zero payloadSize and dups, even if locally closed.
     *  Returns false if there is no room, OR it's not a dup and the stream has been closed on
     *  the read side with close().
     *  If this returns false, you probably want to call isLocallyClosed() to find out why.
     *
     *  @return true if we have room. If false, do not call messageReceived()
     *  @since 0.9.20 moved from ConnectionPacketHandler.receivePacket() so it can all be under one lock,
     *         and we can efficiently do several checks
     */
    public boolean canAccept(long messageId, int payloadSize) { 
        if (payloadSize <= 0)
            return true;
        synchronized (_dataLock) {
            // ready dup check
            // we always allow sequence numbers less than or equal to highest received
            if (messageId <= _highestReadyBlockId)
                return true;
            // We do this after the above dup check.
            if (_locallyClosed) {
                // return true if a not-ready dup, false if not
                return _notYetReadyBlocks.containsKey(Long.valueOf(messageId));
            }
            if (messageId < MIN_READY_BUFFERS)
                return true;
            // shortcut test, assuming all ready and not ready blocks are max size,
            // to avoid iterating through all the ready blocks in getTotalReadySize()
            if ((_readyDataBlocks.size() + _notYetReadyBlocks.size()) * _maxMessageSize < _maxBufferSize)
                return true;
            // not ready dup check
            if (_notYetReadyBlocks.containsKey(Long.valueOf(messageId)))
                return true;
            // less efficient starting here
            // Here, for the purposes of calculating whether the input stream is full,
            // we assume all the not-ready blocks are the max message size.
            // This prevents us from getting DoSed by accepting unlimited out-of-order small messages
            int available = _maxBufferSize - getTotalReadySize();
            if (available <= 0) {
                if (_log.shouldWarn())
                    _log.warn("Dropping message " + messageId + ", inbound buffer exceeded: available = " +
                              available);
                return false;
            }
            // following code screws up if available < 0
            int allowedBlocks = available / _maxMessageSize;
            if (messageId > _highestReadyBlockId + allowedBlocks) {
                if (_log.shouldWarn())
                    _log.warn("Dropping message " + messageId + ", inbound buffer exceeded: " +
                              _highestReadyBlockId + '/' + (_highestReadyBlockId + allowedBlocks) + '/' + available);
                return false;
            }
            // This prevents us from getting DoSed by accepting unlimited in-order small messages
            if (_readyDataBlocks.size() >= 4 * _maxWindowSize) {
                if (_log.shouldWarn())
                    _log.warn("Dropping message " + messageId + ", too many ready blocks");
                return false;
            }
        }
        return true;
    }

    /**
     * Retrieve the message IDs that are holes in our sequence - ones 
     * past the highest ready ID and below the highest received message 
     * ID.  This may return null if there are no such IDs.
     *
     * @return array of message ID holes, or null if none
     */
    public long[] getNacks() {
        synchronized (_dataLock) {
            return locked_getNacks();
        }
    }
    private long[] locked_getNacks() {
        List<Long> ids = null;
        for (long i = _highestReadyBlockId + 1; i < _highestBlockId; i++) {
            Long l = Long.valueOf(i);
            if (_notYetReadyBlocks.containsKey(l)) {
                // ACK
            } else {
                if (ids == null)
                    ids = new ArrayList<Long>(4);
                ids.add(l);
            }
        }
        if (ids != null) {
            long rv[] = new long[ids.size()];
            for (int i = 0; i < rv.length; i++)
                rv[i] = ids.get(i).longValue();
            return rv;
        } else {
            return null;
        }
    }
    
    /**
     *  Adds the ack-through and nack fields to a packet we are building for transmission
     */
    public void updateAcks(PacketLocal packet) {
        synchronized (_dataLock) {
            packet.setAckThrough(_highestBlockId);
            packet.setNacks(locked_getNacks());
        }
    }
    
    /**
     * Ascending list of block IDs greater than the highest
     * ready block ID, or null if there aren't any.
     *
     * @return block IDs greater than the highest ready block ID, or null if there aren't any.
     */
/***
    public long[] getOutOfOrderBlocks() {
        long blocks[] = null;
        synchronized (_dataLock) {
            int num = _notYetReadyBlocks.size();
            if (num <= 0) return null;
            blocks = new long[num];
            int i = 0;
            for (Long id : _notYetReadyBlocks.keySet()) {
                blocks[i++] = id.longValue();
            }
        }
        Arrays.sort(blocks);
        return blocks;
    }
***/
    
    /** how many blocks have we received that we still have holes before?
     * @return Count of blocks received that still have holes
     */
/***
    public int getOutOfOrderBlockCount() { 
        synchronized (_dataLock) { 
            return _notYetReadyBlocks.size(); 
        }
    }
***/
  
    /** 
     * how long a read() call should block (if less than 0, block indefinitely,
     * but if it is 0, do not block at all)
     * @return how long read calls should block, 0 for nonblocking, negative to indefinitely block
     */
    public int getReadTimeout() { return _readTimeout; }

    /** 
     * how long a read() call should block (if less than 0, block indefinitely,
     * but if it is 0, do not block at all)
     * @param timeout how long read calls should block, 0 for nonblocking, negative to indefinitely block
     */
    public void setReadTimeout(int timeout) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Changing read timeout from " + _readTimeout + " to " + timeout);
        _readTimeout = timeout; 
    }
    
    /** 
     *  There is no more data coming from the I2P side.
     *  Does NOT clear pending data.
     *  messageReceived() MUST have been called previously with the messageId of the CLOSE packet.
     */
    public void closeReceived() {
        synchronized (_dataLock) {
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("Close received, ready bytes: ");
                long available = 0;
                for (int i = 0; i < _readyDataBlocks.size(); i++) 
                    available += _readyDataBlocks.get(i).getValid();
                available -= _readyDataBlockIndex;
                buf.append(available);
                buf.append(" blocks: ").append(_readyDataBlocks.size());
                
                buf.append(" not ready blocks: ");
                long notAvailable = 0;
                for (Long id : _notYetReadyBlocks.keySet()) {
                    ByteArray ba = _notYetReadyBlocks.get(id);
                    buf.append(id).append(" ");
                    
                    if (ba != null)
                        notAvailable += ba.getValid();
                }
                
                buf.append("not ready bytes: ").append(notAvailable);
                buf.append(" highest ready block: ").append(_highestReadyBlockId);
                
                _log.debug(buf.toString(), new Exception("Input stream closed"));
            }
            _closeReceived = true;
            _dataLock.notifyAll();
        }
    }
    
    public void notifyActivity() { synchronized (_dataLock) { _dataLock.notifyAll(); } }
    
    /**
     * A new message has arrived - toss it on the appropriate queue (moving 
     * previously pending messages to the ready queue if it fills the gap, etc).
     * This does no limiting of pending data - see canAccept() for limiting.
     *
     * Warning - returns true if locally closed.
     *
     * @param messageId ID of the message
     * @param payload message payload, may be null or have null or zero-length data
     * @return true if this is a new packet, false if it is a dup
     */
    public boolean messageReceived(long messageId, ByteArray payload) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("received msg ID " + messageId + " with " +
                       (payload != null ? payload.getValid() + " bytes" : "no payload"));
        synchronized (_dataLock) {
            if (messageId <= _highestReadyBlockId) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("ignoring dup message " + messageId);
                _dataLock.notifyAll();
                return false; // already received
            }
            if (messageId > _highestBlockId)
                _highestBlockId = messageId;
            
            if (_highestReadyBlockId + 1 == messageId) {
                if (!_locallyClosed && payload.getValid() > 0) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("accepting bytes as ready: " + payload.getValid());
                    _readyDataBlocks.add(payload);
                }
                _highestReadyBlockId = messageId;
                long cur = _highestReadyBlockId + 1;
                // now pull in any previously pending blocks
                ByteArray ba;
                while ((ba = _notYetReadyBlocks.remove(Long.valueOf(cur))) != null) {
                    if (ba.getData() != null && ba.getValid() > 0) {
                        _readyDataBlocks.add(ba);
                    }
                    
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("making ready the block " + cur);
                    cur++;
                    _highestReadyBlockId++;
                }
                                        // FIXME Javadocs for setReadTimeout() say we will throw
                                        // an InterruptedIOException.
                                        // Java throws a SocketTimeoutException.
                                        // We do neither.
            } else {
                // _notYetReadyBlocks size is limited in canAccept()
                if (_locallyClosed) {
                    if (_log.shouldInfo())
                        _log.info("Message received on closed stream: " + messageId);
                    // dont need the payload, just the msgId in order
                    _notYetReadyBlocks.put(Long.valueOf(messageId), DUMMY_BA);
                } else {
                    if (_log.shouldInfo())
                        _log.info("Message is out of order: " + messageId);
                    _notYetReadyBlocks.put(Long.valueOf(messageId), payload);
                }
            }
            _dataLock.notifyAll();
        }
        return true;
    }
    
    /**
     *  On a read timeout, this returns -1
     *  (doesn't throw SocketTimeoutException like Socket)
     *  (doesn't throw InterruptedIOException like our javadocs say)
     */
    public int read() throws IOException {
        int read = read(_oneByte, 0, 1);
        if (read <= 0)
            return -1;
        return _oneByte[0] & 0xff;
    }
    
    /**
     *  On a read timeout, this returns 0
     *  (doesn't throw SocketTimeoutException like Socket)
     *  (doesn't throw InterruptedIOException like our javadocs say)
     */
    @Override
    public int read(byte target[]) throws IOException {
        return read(target, 0, target.length);
    }
    
    /**
     *  On a read timeout, this returns 0
     *  (doesn't throw SocketTimeoutException like Socket)
     *  (doesn't throw InterruptedIOException like our javadocs say)
     */
    @Override
    public int read(byte target[], int offset, int length) throws IOException {
        int readTimeout = _readTimeout;
        long expiration;
        if (readTimeout > 0)
            expiration = readTimeout + System.currentTimeMillis();
        else
            expiration = -1;
        synchronized (_dataLock) {
            if (_locallyClosed) throw new IOException("Input stream closed");
            throwAnyError();
            for (int i = 0; i < length; i++) {
                if ( (_readyDataBlocks.isEmpty()) && (i == 0) ) {
                    // ok, we havent found anything, so lets block until we get 
                    // at least one byte
                    
                    while (_readyDataBlocks.isEmpty()) {
                        if (_locallyClosed)
                            throw new IOException("Input stream closed");
                        
                        if ( (_notYetReadyBlocks.isEmpty()) && (_closeReceived) ) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info("read(...," + offset + ", " + length + ")[" + i 
                                           + "] got EOF after " + _readTotal + " " + toString());
                            return -1;
                        } else {
                            if (readTimeout < 0) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + "] with no timeout: " + toString());
                                try {
                                    _dataLock.wait();
                                } catch (InterruptedException ie) {
                                    IOException ioe2 = new InterruptedIOException("Interrupted read");
                                    ioe2.initCause(ie);
                                    throw ioe2;
                                }
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + "] with no timeout complete: " + toString());
                                throwAnyError();
                            } else if (readTimeout > 0) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + "] with timeout: " + readTimeout + ": " + toString());
                                try {
                                    _dataLock.wait(readTimeout);
                                } catch (InterruptedException ie) {
                                    IOException ioe2 = new InterruptedIOException("Interrupted read");
                                    ioe2.initCause(ie);
                                    throw ioe2;
                                }
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + "] with timeout complete: " + readTimeout + ": " + toString());
                                throwAnyError();
                            } else { // readTimeout == 0
                                // noop, don't block
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + "] with nonblocking setup: " + toString());
                                return i;
                            }
                            if (_readyDataBlocks.isEmpty()) {
                                if (readTimeout > 0) {
                                    long remaining = expiration - System.currentTimeMillis();
                                    if (remaining <= 0) {
                                        // FIXME Javadocs for setReadTimeout() say we will throw
                                        // an InterruptedIOException.
                                        // Java throws a SocketTimeoutException.
                                        // We do neither.
                                        if (_log.shouldLog(Log.INFO))
                                            _log.info("read(...," + offset+", " + length+ ")[" + i 
                                                       + "] expired: " + toString());
                                        return i;
                                    } else {
                                        readTimeout = (int) remaining;
                                    }
                                }
                            }
                        }
                    }
                    // we looped a few times then got data, so this pass doesnt count
                    i--;
                } else if (_readyDataBlocks.isEmpty()) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                   + "] no more ready blocks, returning");
                    return i;
                } else {
                    // either was already ready, or we wait()ed and it arrived
                    ByteArray cur = _readyDataBlocks.get(0);
                    byte rv = cur.getData()[cur.getOffset()+_readyDataBlockIndex];
                    _readyDataBlockIndex++;
                    if (cur.getValid() <= _readyDataBlockIndex) {
                        _readyDataBlockIndex = 0;
                        _readyDataBlocks.remove(0);
                    }
                    _readTotal++;
                    target[offset + i] = rv; // rv < 0 ? rv + 256 : rv
                    if ( (_readyDataBlockIndex <= 3) || (_readyDataBlockIndex >= cur.getValid() - 5) ) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                       + "] after ready data: readyDataBlockIndex=" + _readyDataBlockIndex 
                                       + " readyBlocks=" + _readyDataBlocks.size()
                                       + " readTotal=" + _readTotal);
                    }
                    //if (removed) 
                    //    _cache.release(cur);
                }
            } // for (int i = 0; i < length; i++) {
        }  // synchronized (_dataLock)
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("read(byte[]," + offset + ',' + length + ") read fully; total read: " +_readTotal);

        return length;
    }
    
    @Override
    public int available() throws IOException {
        int numBytes = 0;
        synchronized (_dataLock) {
            if (_locallyClosed) throw new IOException("Input stream closed");
            throwAnyError();
            for (int i = 0; i < _readyDataBlocks.size(); i++) {
                ByteArray cur = _readyDataBlocks.get(i);
                if (i == 0)
                    numBytes += cur.getValid() - _readyDataBlockIndex;
                else
                    numBytes += cur.getValid();
            }
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("available(): " + numBytes);
        
        return numBytes;
    }
    
    /**
     * How many bytes are queued up for reading (or sitting in the out-of-order
     * buffer)?
     *
     * @return Count of bytes waiting to be read
     */
/***
    public int getTotalQueuedSize() {
        synchronized (_dataLock) {
            if (_locallyClosed) return 0;
            int numBytes = 0;
            for (int i = 0; i < _readyDataBlocks.size(); i++) {
                ByteArray cur = _readyDataBlocks.get(i);
                if (i == 0)
                    numBytes += cur.getValid() - _readyDataBlockIndex;
                else
                    numBytes += cur.getValid();
            }
            for (ByteArray cur : _notYetReadyBlocks.values()) {
                numBytes += cur.getValid();
            }
            return numBytes;
        }
    }
***/
    
    /**
     *  Same as available() but doesn't throw IOE
     */
    public int getTotalReadySize() {
        synchronized (_dataLock) {
            if (_locallyClosed) return 0;
            int numBytes = 0;
            for (int i = 0; i < _readyDataBlocks.size(); i++) {
                ByteArray cur = _readyDataBlocks.get(i);
                numBytes += cur.getValid();
                if (i == 0)
                    numBytes -= _readyDataBlockIndex;
            }
            return numBytes;
        }
    }
    
    @Override
    public void close() {
        synchronized (_dataLock) {
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("close(), ready bytes: ");
                long available = 0;
                for (int i = 0; i < _readyDataBlocks.size(); i++) 
                    available += _readyDataBlocks.get(i).getValid();
                available -= _readyDataBlockIndex;
                buf.append(available);
                buf.append(" blocks: ").append(_readyDataBlocks.size());
                buf.append(" not ready blocks: ");
                long notAvailable = 0;
                for (Long id : _notYetReadyBlocks.keySet()) {
                    ByteArray ba = _notYetReadyBlocks.get(id);
                    buf.append(id).append(" ");
                    if (ba != null)
                        notAvailable += ba.getValid();
                }
                buf.append("not ready bytes: ").append(notAvailable);
                buf.append(" highest ready block: ").append(_highestReadyBlockId);
                _log.debug(buf.toString());
            }
            //while (_readyDataBlocks.size() > 0)
            //    _cache.release((ByteArray)_readyDataBlocks.remove(0));
            _readyDataBlocks.clear();
             
            // we don't need the data, but we do need to keep track of the messageIds
            // received, so we can ACK accordingly
            for (ByteArray ba : _notYetReadyBlocks.values()) {
                ba.setData(null);
                //_cache.release(ba);
            }
            _locallyClosed = true;
            _dataLock.notifyAll();
        }
    }
    
    /**
     * Stream b0rked, die with the given error
     *
     */
    void streamErrorOccurred(IOException ioe) {
        synchronized (_dataLock) {
            if (_streamError == null)
                _streamError = ioe;
            _locallyClosed = true;
            _dataLock.notifyAll();
        }
    }
    
    /** Caller must lock _dataLock */
    private void throwAnyError() throws IOException {
        IOException ioe = _streamError;
        if (ioe != null) {
            _streamError = null;
            // constructor with cause not until Java 6
            IOException ioe2 = new IOException("Input stream error");
            ioe2.initCause(ioe);
            throw ioe2;
        }
    }
}
