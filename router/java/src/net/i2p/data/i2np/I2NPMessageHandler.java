package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Handle messages from router to router.  This class is NOT threadsafe
 *
 */
public class I2NPMessageHandler {
    private final Log _log;
    private final I2PAppContext _context;
    private long _lastReadBegin;
    private long _lastReadEnd;
    private int _lastSize;
    private byte _messageBuffer[];
    private I2NPMessage _lastRead;
    
    public I2NPMessageHandler(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(I2NPMessageHandler.class);
        _lastSize = -1;
    }
    
    /** clear the last message read from a byte array with an offset */
    public I2NPMessage lastRead() { 
        I2NPMessage rv = _lastRead;
        _lastRead = null;
        return rv;
    }
    
    /**
     * Read an I2NPMessage from the byte array and return the fully populated object.
     *
     * @throws I2NPMessageException if there is a problem handling the particular
     *          message - if it is an unknown type or has improper formatting, etc.
     */
    public I2NPMessage readMessage(byte data[]) throws I2NPMessageException {
        readMessage(data, 0, data.length);
        return lastRead();
    }

    /**
     *  Result is retreived with lastRead()
     */
    public int readMessage(byte data[], int offset) throws I2NPMessageException {
        return readMessage(data, offset, data.length - offset);
    }

    /**
     *  Set a limit on the max to read from the data buffer, so that
     *  we can use a large buffer but prevent the reader from reading off the end.
     *
     *  Result is retreived with lastRead()
     *
     *  @param maxLen read no more than this many bytes from data starting at offset, even if it is longer
     *                must be at least 16
     *  @since 0.8.12
     */
    public int readMessage(byte data[], int offset, int maxLen) throws I2NPMessageException {
        int cur = offset;
        // we will assume that maxLen is >= 1 here. It's checked to be >= 16 in readBytes()
        int type = data[cur] & 0xff;
        cur++;
        _lastReadBegin = System.currentTimeMillis();
        I2NPMessage msg = I2NPMessageImpl.createMessage(_context, type);
        try {
            _lastSize = msg.readBytes(data, type, cur, maxLen - 1);
            cur += _lastSize;
        } catch (I2NPMessageException ime) {
            throw ime;
        } catch (RuntimeException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error reading the stream", e);
            throw new I2NPMessageException("Unknown error reading the " + msg.getClass().getSimpleName(), e); 
        }
        _lastReadEnd = System.currentTimeMillis();
        _lastRead = msg;
        return cur - offset;
    }
    
    public long getLastReadTime() { return _lastReadEnd - _lastReadBegin; }
    public int getLastSize() { return _lastSize; }
    
}
