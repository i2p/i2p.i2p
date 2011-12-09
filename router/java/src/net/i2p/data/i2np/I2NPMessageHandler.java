package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FileInputStream;
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
     * @throws IOException if there is an IO problem reading from the stream
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
            } catch (IOException ioe) {
                throw ioe;
            } catch (I2NPMessageException ime) {
                throw ime;
            } catch (Exception e) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error reading the stream", e);
                throw new IOException("Unknown error reading the " + msg.getClass().getName() 
                                      + ": " + e.getMessage());
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
     * Read an I2NPMessage from the stream and return the fully populated object.
     *
     * @throws IOException if there is an IO problem reading from the stream
     * @throws I2NPMessageException if there is a problem handling the particular
     *          message - if it is an unknown type or has improper formatting, etc.
     */
    public I2NPMessage readMessage(byte data[]) throws IOException, I2NPMessageException {
        readMessage(data, 0);
        return lastRead();
    }

    public int readMessage(byte data[], int offset) throws IOException, I2NPMessageException {
        int cur = offset;
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
            _lastSize = msg.readBytes(data, type, cur);
            cur += _lastSize;
        } catch (IOException ioe) {
            throw ioe;
        } catch (I2NPMessageException ime) {
            throw ime;
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error reading the stream", e);
            throw new IOException("Unknown error reading the " + msg.getClass().getName() 
                                  + ": " + e.getMessage());
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
