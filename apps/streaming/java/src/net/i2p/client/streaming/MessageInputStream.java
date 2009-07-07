package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Stream that can be given messages out of order 
 * yet present them in order.
 *
 */
public class MessageInputStream extends InputStream {
    private I2PAppContext _context;
    private Log _log;
    /** 
     * List of ByteArray objects of data ready to be read,
     * with the first ByteArray at index 0, and the next
     * actual byte to be read at _readyDataBlockIndex of 
     * that array.
     *
     */
    private List _readyDataBlocks;
    private int _readyDataBlockIndex;
    /** highest message ID used in the readyDataBlocks */
    private volatile long _highestReadyBlockId;
    /** highest overall message ID */
    private volatile long _highestBlockId;
    /** 
     * Message ID (Long) to ByteArray for blocks received
     * out of order when there are lower IDs not yet 
     * received
     */
    private Map _notYetReadyBlocks;
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
    private ByteCache _cache;
    
    private byte[] _oneByte = new byte[1];
    
    private final Object _dataLock;
    
    public MessageInputStream(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageInputStream.class);
        _readyDataBlocks = new ArrayList(4);
        _readyDataBlockIndex = 0;
        _highestReadyBlockId = -1;
        _highestBlockId = -1;
        _readTimeout = -1;
        _readTotal = 0;
        _notYetReadyBlocks = new HashMap(4);
        _dataLock = new Object();
        _closeReceived = false;
        _locallyClosed = false;
        _cache = ByteCache.getInstance(128, Packet.MAX_PAYLOAD_SIZE);
    }
    
    /** What is the highest block ID we've completely received through?
     * @return highest data block ID completely received
     */
    public long getHighestReadyBockId() { 
        // not synchronized as it doesnt hurt to read a too-low value
        return _highestReadyBlockId; 
    }
    
    public long getHighestBlockId() { 
        // not synchronized as it doesnt hurt to read a too-low value
        return _highestBlockId;
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
        List ids = null;
        for (long i = _highestReadyBlockId + 1; i < _highestBlockId; i++) {
            Long l = new Long(i);
            if (_notYetReadyBlocks.containsKey(l)) {
                // ACK
            } else {
                if (ids == null)
                    ids = new ArrayList(4);
                ids.add(l);
            }
        }
        if (ids != null) {
            long rv[] = new long[ids.size()];
            for (int i = 0; i < rv.length; i++)
                rv[i] = ((Long)ids.get(i)).longValue();
            return rv;
        } else {
            return null;
        }
    }
    
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
    public long[] getOutOfOrderBlocks() {
        long blocks[] = null;
        synchronized (_dataLock) {
            int num = _notYetReadyBlocks.size();
            if (num <= 0) return null;
            blocks = new long[num];
            int i = 0;
            for (Iterator iter = _notYetReadyBlocks.keySet().iterator(); iter.hasNext(); ) {
                Long id = (Long)iter.next();
                blocks[i] = id.longValue();
                i++;
            }
        }
        Arrays.sort(blocks);
        return blocks;
    }
    
    /** how many blocks have we received that we still have holes before?
     * @return Count of blocks received that still have holes
     */
    public int getOutOfOrderBlockCount() { 
        synchronized (_dataLock) { 
            return _notYetReadyBlocks.size(); 
        }
    }
  
    /** 
     * how long a read() call should block (if less than 0, block indefinitely,
     * but if it is 0, do not block at all)
     * @return how long read calls should block, 0 or less indefinitely block
     */
    public int getReadTimeout() { return _readTimeout; }
    public void setReadTimeout(int timeout) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Changing read timeout from " + _readTimeout + " to " + timeout);
        _readTimeout = timeout; 
    }
    
    public void closeReceived() {
        synchronized (_dataLock) {
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("Close received, ready bytes: ");
                long available = 0;
                for (int i = 0; i < _readyDataBlocks.size(); i++) 
                    available += ((ByteArray)_readyDataBlocks.get(i)).getValid();
                available -= _readyDataBlockIndex;
                buf.append(available);
                buf.append(" blocks: ").append(_readyDataBlocks.size());
                
                buf.append(" not ready blocks: ");
                long notAvailable = 0;
                for (Iterator iter = _notYetReadyBlocks.keySet().iterator(); iter.hasNext(); ) {
                    Long id = (Long)iter.next();
                    ByteArray ba = (ByteArray)_notYetReadyBlocks.get(id);
                    buf.append(id).append(" ");
                    
                    if (ba != null)
                        notAvailable += ba.getValid();
                }
                
                buf.append("not ready bytes: ").append(notAvailable);
                buf.append(" highest ready block: ").append(_highestReadyBlockId);
                
                _log.debug(buf.toString(), new Exception("closed"));
            }
            _closeReceived = true;
            _dataLock.notifyAll();
        }
    }
    
    public void notifyActivity() { synchronized (_dataLock) { _dataLock.notifyAll(); } }
    
    /**
     * A new message has arrived - toss it on the appropriate queue (moving 
     * previously pending messages to the ready queue if it fills the gap, etc).
     *
     * @param messageId ID of the message
     * @param payload message payload
     * @return true if this is a new packet, false if it is a dup
     */
    public boolean messageReceived(long messageId, ByteArray payload) {
        synchronized (_dataLock) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("received " + messageId + " with " + (payload != null ? payload.getValid()+"" : "no payload"));
            if (messageId <= _highestReadyBlockId) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ignoring dup message " + messageId);
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
                while (_notYetReadyBlocks.containsKey(new Long(cur))) {
                    ByteArray ba = (ByteArray)_notYetReadyBlocks.remove(new Long(cur));
                    if ( (ba != null) && (ba.getData() != null) && (ba.getValid() > 0) ) {
                        _readyDataBlocks.add(ba);
                    }
                    
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("making ready the block " + cur);
                    cur++;
                    _highestReadyBlockId++;
                }
                _dataLock.notifyAll();
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("message is out of order: " + messageId);
                if (_locallyClosed) // dont need the payload, just the msgId in order
                    _notYetReadyBlocks.put(new Long(messageId), new ByteArray(null));
                else
                    _notYetReadyBlocks.put(new Long(messageId), payload);
                _dataLock.notifyAll();
            }
        }
        return true;
    }
    
    public int read() throws IOException {
        int read = read(_oneByte, 0, 1);
        if (read < 0)
            return -1;
        else
            return _oneByte[0];
    }
    
	@Override
    public int read(byte target[]) throws IOException {
        return read(target, 0, target.length);
    }
    
	@Override
    public int read(byte target[], int offset, int length) throws IOException {
        if (_locallyClosed) throw new IOException("Already locally closed");
        throwAnyError();
        long expiration = -1;
        if (_readTimeout > 0)
            expiration = _readTimeout + System.currentTimeMillis();
        synchronized (_dataLock) {
            for (int i = 0; i < length; i++) {
                if ( (_readyDataBlocks.size() <= 0) && (i == 0) ) {
                    // ok, we havent found anything, so lets block until we get 
                    // at least one byte
                    
                    while (_readyDataBlocks.size() <= 0) {
                        if (_locallyClosed)
                            throw new IOException("Already closed, you wanker");
                        
                        if ( (_notYetReadyBlocks.size() <= 0) && (_closeReceived) ) {
                            if (_log.shouldLog(Log.INFO))
                                _log.info("read(...," + offset + ", " + length + ")[" + i 
                                           + "] got EOF after " + _readTotal + " " + toString());
                            return -1;
                        } else {
                            if (_readTimeout < 0) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + ") with no timeout: " + toString());
                                try { _dataLock.wait(); } catch (InterruptedException ie) { }
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + ") with no timeout complete: " + toString());
                                throwAnyError();
                            } else if (_readTimeout > 0) {
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + ") with timeout: " + _readTimeout + ": " + toString());
                                try { _dataLock.wait(_readTimeout); } catch (InterruptedException ie) { }
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + ") with timeout complete: " + _readTimeout + ": " + toString());
                                throwAnyError();
                            } else { // readTimeout == 0
                                // noop, don't block
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                               + ") with nonblocking setup: " + toString());
                                return i;
                            }
                            if (_readyDataBlocks.size() <= 0) {
                                if ( (_readTimeout > 0) && (expiration < System.currentTimeMillis()) ) {
                                    if (_log.shouldLog(Log.INFO))
                                        _log.info("read(...," + offset+", " + length+ ")[" + i 
                                                   + ") expired: " + toString());
                                    return i;
                                }
                            }
                        }
                    }
                    // we looped a few times then got data, so this pass doesnt count
                    i--;
                } else if (_readyDataBlocks.size() <= 0) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("read(...," + offset+", " + length+ ")[" + i 
                                   + "] no more ready blocks, returning");
                    return i;
                } else {
                    // either was already ready, or we wait()ed and it arrived
                    ByteArray cur = (ByteArray)_readyDataBlocks.get(0);
                    byte rv = cur.getData()[cur.getOffset()+_readyDataBlockIndex];
                    _readyDataBlockIndex++;
                    boolean removed = false;
                    if (cur.getValid() <= _readyDataBlockIndex) {
                        _readyDataBlockIndex = 0;
                        _readyDataBlocks.remove(0);
                        removed = true;
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
            _log.debug("read(...," + offset+", " + length+ ") read fully total read: " +_readTotal);

        return length;
    }
    
	@Override
    public int available() throws IOException {
        if (_locallyClosed) throw new IOException("Already closed, you wanker");
        throwAnyError();
        int numBytes = 0;
        synchronized (_dataLock) {
            for (int i = 0; i < _readyDataBlocks.size(); i++) {
                ByteArray cur = (ByteArray)_readyDataBlocks.get(i);
                if (i == 0)
                    numBytes += cur.getValid() - _readyDataBlockIndex;
                else
                    numBytes += cur.getValid();
            }
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("available(): " + numBytes + " " + toString());
        
        return numBytes;
    }
    
    /**
     * How many bytes are queued up for reading (or sitting in the out-of-order
     * buffer)?
     *
     * @return Count of bytes waiting to be read
     */
    public int getTotalQueuedSize() {
        synchronized (_dataLock) {
            if (_locallyClosed) return 0;
            int numBytes = 0;
            for (int i = 0; i < _readyDataBlocks.size(); i++) {
                ByteArray cur = (ByteArray)_readyDataBlocks.get(i);
                if (i == 0)
                    numBytes += cur.getValid() - _readyDataBlockIndex;
                else
                    numBytes += cur.getValid();
            }
            for (Iterator iter = _notYetReadyBlocks.values().iterator(); iter.hasNext(); ) {
                ByteArray cur = (ByteArray)iter.next();
                numBytes += cur.getValid();
            }
            return numBytes;
        }
    }
    
    public int getTotalReadySize() {
        synchronized (_dataLock) {
            if (_locallyClosed) return 0;
            int numBytes = 0;
            for (int i = 0; i < _readyDataBlocks.size(); i++) {
                ByteArray cur = (ByteArray)_readyDataBlocks.get(i);
                if (i == 0)
                    numBytes += cur.getValid() - _readyDataBlockIndex;
                else
                    numBytes += cur.getValid();
            }
            return numBytes;
        }
    }
    
	@Override
    public void close() {
        synchronized (_dataLock) {
            //while (_readyDataBlocks.size() > 0)
            //    _cache.release((ByteArray)_readyDataBlocks.remove(0));
            _readyDataBlocks.clear();
             
            // we don't need the data, but we do need to keep track of the messageIds
            // received, so we can ACK accordingly
            for (Iterator iter = _notYetReadyBlocks.values().iterator(); iter.hasNext(); ) {
                ByteArray ba = (ByteArray)iter.next();
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
        if (_streamError == null)
            _streamError = ioe;
        _locallyClosed = true;
        synchronized (_dataLock) {
            _dataLock.notifyAll();
        }
    }
    
    private void throwAnyError() throws IOException {
        if (_streamError != null) {
            IOException ioe = _streamError;
            _streamError = null;
            throw ioe;
        }
    }
}
