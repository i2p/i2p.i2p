package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
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
    private long _highestReadyBlockId;
    /** highest overall message ID */
    private long _highestBlockId;
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
    
    private Object _dataLock;
    
    public MessageInputStream(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageInputStream.class);
        _readyDataBlocks = new ArrayList(4);
        _readyDataBlockIndex = 0;
        _highestReadyBlockId = -1;
        _highestBlockId = -1;
        _readTimeout = -1;
        _notYetReadyBlocks = new HashMap(4);
        _dataLock = new Object();
        _closeReceived = false;
        _locallyClosed = false;
    }
    
    /** What is the highest block ID we've completely received through? */
    public long getHighestReadyBockId() { 
        synchronized (_dataLock) {
            return _highestReadyBlockId; 
        }
    }
    
    public long getHighestBlockId() { 
        synchronized (_dataLock) {
            return _highestBlockId;
        }
    }
    
    /**
     * Retrieve the message IDs that are holes in our sequence - ones 
     * past the highest ready ID and below the highest received message 
     * ID.  This may return null if there are no such IDs.
     *
     */
    public long[] getNacks() {
        List ids = null;
        synchronized (_dataLock) {
            for (long i = _highestReadyBlockId + 1; i < _highestBlockId; i++) {
                Long l = new Long(i);
                if (_notYetReadyBlocks.containsKey(l)) {
                    // ACK
                } else {
                    if (ids != null)
                        ids = new ArrayList(4);
                    ids.add(l);
                }
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
    
    /**
     * Ascending list of block IDs greater than the highest
     * ready block ID, or null if there aren't any.
     *
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
    
    /** how many blocks have we received that we still have holes before? */
    public int getOutOfOrderBlockCount() { 
        synchronized (_dataLock) { 
            return _notYetReadyBlocks.size(); 
        }
    }
  
    /** 
     * how long a read() call should block (if less than 0, block indefinitely,
     * but if it is 0, do not block at all)
     */
    public int getReadTimeout() { return _readTimeout; }
    public void setReadTimeout(int timeout) { _readTimeout = timeout; }
    
    public void closeReceived() {
        synchronized (_dataLock) {
            _closeReceived = true;
        }
    }
    
    /**
     * A new message has arrived - toss it on the appropriate queue (moving 
     * previously pending messages to the ready queue if it fills the gap, etc).
     *
     * @return true if this is a new packet, false if it is a dup
     */
    public boolean messageReceived(long messageId, byte payload[]) {
        synchronized (_dataLock) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("received " + messageId + " with " + payload.length);
            if (messageId <= _highestReadyBlockId) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("ignoring dup message " + messageId);
                return false; // already received
            }
            if (messageId > _highestBlockId)
                _highestBlockId = messageId;
            
            if (_highestReadyBlockId + 1 == messageId) {
                if (!_locallyClosed && payload.length > 0) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("accepting bytes as ready: " + payload.length);
                    _readyDataBlocks.add(new ByteArray(payload));
                }
                _highestReadyBlockId = messageId;
                // now pull in any previously pending blocks
                while (_notYetReadyBlocks.containsKey(new Long(_highestReadyBlockId + 1))) {
                    ByteArray ba = (ByteArray)_notYetReadyBlocks.get(new Long(_highestReadyBlockId + 1));
                    if ( (ba != null) && (ba.getData() != null) && (ba.getData().length > 0) ) {
                        _readyDataBlocks.add(ba);
                    }
                    
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("making ready the block " + _highestReadyBlockId);
                    _highestReadyBlockId++;
                }
                _dataLock.notifyAll();
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("message is out of order: " + messageId);
                if (_locallyClosed) // dont need the payload, just the msgId in order
                    _notYetReadyBlocks.put(new Long(messageId), new ByteArray(null));
                else
                    _notYetReadyBlocks.put(new Long(messageId), new ByteArray(payload));
            }
        }
        return true;
    }
    
    public int read() throws IOException {
        if (_locallyClosed) throw new IOException("Already locally closed");
        throwAnyError();
        long expiration = -1;
        if (_readTimeout > 0)
            expiration = _readTimeout + System.currentTimeMillis();
        synchronized (_dataLock) {
            while (_readyDataBlocks.size() <= 0) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("read() with readyBlocks.size = " + _readyDataBlocks.size() + " on " + toString());
                
                if ( (_notYetReadyBlocks.size() <= 0) && (_closeReceived) ) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("read() got EOF: " + toString());
                    return -1;
                } else {
                    if (_readTimeout < 0) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("read() with no timeout: " + toString());
                        try { _dataLock.wait(); } catch (InterruptedException ie) { }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("read() with no timeout complete: " + toString());
                        throwAnyError();
                    } else if (_readTimeout > 0) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("read() with timeout: " + _readTimeout + ": " + toString());
                        try { _dataLock.wait(_readTimeout); } catch (InterruptedException ie) { }
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("read() with timeout complete: " + _readTimeout + ": " + toString());
                        throwAnyError();
                    } else { // readTimeout == 0
                        // noop, don't block
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("read() with nonblocking setup: " + toString());
                    }
                    if (_readyDataBlocks.size() <= 0) {
                        if ( (_readTimeout > 0) && (expiration > System.currentTimeMillis()) )
                        throw new InterruptedIOException("Timeout reading (timeout=" + _readTimeout + ")");
                    }
                }
            }
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("read() readyBlocks = " + _readyDataBlocks.size() + ": " + toString());
            
            // either was already ready, or we wait()ed and it arrived
            ByteArray cur = (ByteArray)_readyDataBlocks.get(0);
            byte rv = cur.getData()[_readyDataBlockIndex];
            _readyDataBlockIndex++;
            if (cur.getData().length <= _readyDataBlockIndex) {
                _readyDataBlockIndex = 0;
                _readyDataBlocks.remove(0);
            }
            return (rv < 0 ? rv + 256 : rv);
        }
    }
    
    public int available() throws IOException {
        if (_locallyClosed) throw new IOException("Already closed, you wanker");
        throwAnyError();
        synchronized (_dataLock) {
            if (_readyDataBlocks.size() <= 0) 
                return 0;
            int numBytes = 0;
            for (int i = 0; i < _readyDataBlocks.size(); i++) {
                ByteArray cur = (ByteArray)_readyDataBlocks.get(i);
                if (i == 0)
                    numBytes += cur.getData().length - _readyDataBlockIndex;
                else
                    numBytes += cur.getData().length;
            }
            return numBytes;
        }
    }
    
    /**
     * How many bytes are queued up for reading (or sitting in the out-of-order
     * buffer)?
     *
     */
    public int getTotalQueuedSize() {
        synchronized (_dataLock) {
            if (_locallyClosed) return 0;
            int numBytes = 0;
            for (int i = 0; i < _readyDataBlocks.size(); i++) {
                ByteArray cur = (ByteArray)_readyDataBlocks.get(i);
                if (i == 0)
                    numBytes += cur.getData().length - _readyDataBlockIndex;
                else
                    numBytes += cur.getData().length;
            }
            for (Iterator iter = _notYetReadyBlocks.values().iterator(); iter.hasNext(); ) {
                ByteArray cur = (ByteArray)iter.next();
                numBytes += cur.getData().length;
            }
            return numBytes;
        }
    }
    
    public void close() {
        synchronized (_dataLock) {
            _readyDataBlocks.clear();
             
            // we don't need the data, but we do need to keep track of the messageIds
            // received, so we can ACK accordingly
            for (Iterator iter = _notYetReadyBlocks.values().iterator(); iter.hasNext(); ) {
                ByteArray ba = (ByteArray)iter.next();
                ba.setData(null);
            }
            _locallyClosed = true;
        }
    }
    
    /**
     * Stream b0rked, die with the given error
     *
     */
    void streamErrorOccurred(IOException ioe) {
        _streamError = ioe;
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
