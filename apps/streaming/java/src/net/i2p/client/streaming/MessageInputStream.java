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

import net.i2p.data.ByteArray;

/**
 * Stream that can be given messages out of order 
 * yet present them in order.
 *
 */
public class MessageInputStream extends InputStream {
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
    
    private Object _dataLock;
    
    public MessageInputStream() {
        _readyDataBlocks = new ArrayList(4);
        _readyDataBlockIndex = 0;
        _highestReadyBlockId = -1;
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
    
    /**
     * A new message has arrived - toss it on the appropriate queue (moving 
     * previously pending messages to the ready queue if it fills the gap, etc)
     *
     */
    public void messageReceived(long messageId, byte payload[]) {
        synchronized (_dataLock) {
            if (messageId <= _highestReadyBlockId) return; // already received
            if (_highestReadyBlockId + 1 == messageId) {
                if (!_locallyClosed)
                    _readyDataBlocks.add(new ByteArray(payload));
                _highestReadyBlockId = messageId;
                // now pull in any previously pending blocks
                while (_notYetReadyBlocks.containsKey(new Long(_highestReadyBlockId + 1))) {
                    _readyDataBlocks.add(_notYetReadyBlocks.get(new Long(_highestReadyBlockId + 1)));
                    _highestReadyBlockId++;
                }
                _dataLock.notifyAll();
            } else {
                if (_locallyClosed) // dont need the payload, just the msgId in order
                    _notYetReadyBlocks.put(new Long(messageId), new ByteArray(null));
                else
                    _notYetReadyBlocks.put(new Long(messageId), new ByteArray(payload));
            }
        }
    }
    
    public int read() throws IOException {
        if (_locallyClosed) throw new IOException("Already locally closed");
        synchronized (_dataLock) {
            if (_readyDataBlocks.size() <= 0) {
                if ( (_notYetReadyBlocks.size() <= 0) && (_closeReceived) ) {
                    return -1;
                } else {
                    if (_readTimeout < 0) {
                        try { _dataLock.wait(); } catch (InterruptedException ie) { }
                    } else if (_readTimeout > 0) {
                        try { _dataLock.wait(_readTimeout); } catch (InterruptedException ie) { }
                    } else { // readTimeout == 0
                        // noop, don't block
                    }
                    if (_readyDataBlocks.size() <= 0) {
                        throw new InterruptedIOException("Timeout reading");
                    }
                }
            }
            
            // either was already ready, or we wait()ed and it arrived
            ByteArray cur = (ByteArray)_readyDataBlocks.get(0);
            byte rv = cur.getData()[_readyDataBlockIndex++];
            if (cur.getData().length <= _readyDataBlockIndex) {
                _readyDataBlockIndex = 0;
                _readyDataBlocks.remove(0);
            }
            return (rv < 0 ? rv + 256 : rv);
        }
    }
    
    public int available() throws IOException {
        if (_locallyClosed) throw new IOException("Already closed, you wanker");
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
}
