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
import java.io.InputStream;

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
    
    /**
     * Read an I2NPMessage from the stream and return the fully populated object.
     *
     * This is only called by I2NPMessageReader which is unused.
     * All transports provide encapsulation and so we have byte arrays available.
     *
     * @deprecated use the byte array method to avoid an extra copy if you have it
     *
     * @throws I2NPMessageException if there is a problem handling the particular
     *          message - if it is an unknown type or has improper formatting, etc.
     */
    public I2NPMessage readMessage(InputStream in) throws IOException, I2NPMessageException {
        if (_messageBuffer == null) _messageBuffer = new byte[38*1024]; // more than necessary
        try {
            int type = (int)DataHelper.readLong(in, 1);
            _lastReadBegin = System.currentTimeMillis();
            I2NPMessage msg = I2NPMessageImpl.createMessage(_context, type);
            // can't be null
            //if (msg == null)
            //    throw new I2NPMessageException("The type "+ type + " is an unknown I2NP message");
            try {
                _lastSize = msg.readBytes(in, type, _messageBuffer);
            } catch (I2NPMessageException ime) {
                throw ime;
            } catch (Exception e) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error reading the stream", e);
                throw new I2NPMessageException("Unknown error reading the " + msg.getClass().getSimpleName(), e); 
            }
            _lastReadEnd = System.currentTimeMillis();
            return msg;
        } catch (DataFormatException dfe) {
            throw new I2NPMessageException("Error reading the message", dfe);
        }
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
        int type = (int)DataHelper.fromLong(data, cur, 1);
        cur++;
        _lastReadBegin = System.currentTimeMillis();
        I2NPMessage msg = I2NPMessageImpl.createMessage(_context, type);
        // can't be null
        //if (msg == null) {
        //    int sz = data.length-offset;
        //   boolean allZero = false;
        //    for (int i = offset; i < data.length; i++) {
        //        if (data[i] != 0) {
        //            allZero = false;
        //            break;
        //        }
        //    }
        //    throw new I2NPMessageException("The type "+ type + " is an unknown I2NP message (remaining sz=" 
        //                                   + sz + " all zeros? " + allZero + ")");
        //}
        try {
            _lastSize = msg.readBytes(data, type, cur, maxLen - 1);
            cur += _lastSize;
        } catch (I2NPMessageException ime) {
            throw ime;
        } catch (Exception e) {
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
    
/****
    public static void main(String args[]) {
        try {
            I2NPMessage msg = new I2NPMessageHandler(I2PAppContext.getGlobalContext()).readMessage(new FileInputStream(args[0]));
            System.out.println(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
****/
}
