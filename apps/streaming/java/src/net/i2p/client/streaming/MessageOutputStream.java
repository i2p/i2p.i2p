package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 *
 */
public class MessageOutputStream extends OutputStream {
    private I2PAppContext _context;
    private Log _log;
    private byte _buf[];
    private int _valid;
    private Object _dataLock;
    private DataReceiver _dataReceiver;
    private IOException _streamError;
    private boolean _closed;
    private long _written;
    private int _writeTimeout;
    private ByteCache _dataCache;
    
    public MessageOutputStream(I2PAppContext ctx, DataReceiver receiver) {
        this(ctx, receiver, Packet.MAX_PAYLOAD_SIZE);
    }
    public MessageOutputStream(I2PAppContext ctx, DataReceiver receiver, int bufSize) {
        super();
        _dataCache = ByteCache.getInstance(128, bufSize);
        _context = ctx;
        _log = ctx.logManager().getLog(MessageOutputStream.class);
        _buf = _dataCache.acquire().getData(); // new byte[bufSize];
        _dataReceiver = receiver;
        _dataLock = new Object();
        _written = 0;
        _closed = false;
        _writeTimeout = -1;
    }
    
    public void setWriteTimeout(int ms) { _writeTimeout = ms; }
    public int getWriteTimeout() { return _writeTimeout; }
    
    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }
    
    public void write(byte b[], int off, int len) throws IOException {
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("write(b[], " + off + ", " + len + ")");
        int cur = off;
        int remaining = len;
        while (remaining > 0) {
            WriteStatus ws = null;
            // we do any waiting outside the synchronized() block because we
            // want to allow other threads to flushAvailable() whenever they want.  
            // this is the only method that *adds* to the _buf, and all 
            // code that reads from it is synchronized
            synchronized (_dataLock) {
                if (_buf == null) throw new IOException("closed (buffer went away)");
                if (_valid + remaining < _buf.length) {
                    // simply buffer the data, no flush
                    System.arraycopy(b, cur, _buf, _valid, remaining);
                    _valid += remaining;
                    cur += remaining;
                    _written += remaining;
                    remaining = 0;
                } else {
                    // buffer whatever we can fit then flush,
                    // repeating until we've pushed all of the
                    // data through
                    int toWrite = _buf.length - _valid;
                    System.arraycopy(b, cur, _buf, _valid, toWrite);
                    remaining -= toWrite;
                    cur += toWrite;
                    _valid = _buf.length;
                    ws = _dataReceiver.writeData(_buf, 0, _valid);
                    _written += _valid;
                    _valid = 0;                       
                    throwAnyError();
                }
            }
            if (ws != null) {
                // ok, we've actually added a new packet - lets wait until
                // its accepted into the queue before moving on (so that we 
                // dont fill our buffer instantly)
                ws.waitForAccept(_writeTimeout);
                if (!ws.writeAccepted()) {
                    if (_writeTimeout > 0)
                        throw new InterruptedIOException("Write not accepted within timeout");
                    else
                        throw new IOException("Write not accepted into the queue");
                }
            }
        }
        throwAnyError();
    }
    
    public void write(int b) throws IOException {
        write(new byte[] { (byte)b }, 0, 1);
        throwAnyError();
    }
    
    public void flush() throws IOException {
        WriteStatus ws = null;
        synchronized (_dataLock) {
            if (_buf == null) throw new IOException("closed (buffer went away)");
            ws = _dataReceiver.writeData(_buf, 0, _valid);
            _written += _valid;
            _valid = 0;
            _dataLock.notifyAll();
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("before waiting " + _writeTimeout + "ms for completion of " + ws);
        if (_closed && 
            ( (_writeTimeout > Connection.DISCONNECT_TIMEOUT) ||
              (_writeTimeout <= 0) ) )
            ws.waitForCompletion(Connection.DISCONNECT_TIMEOUT);
        else
            ws.waitForCompletion(_writeTimeout);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("after waiting " + _writeTimeout + "ms for completion of " + ws);
        if (ws.writeFailed() && (_writeTimeout > 0) )
            throw new InterruptedIOException("Timed out during write");
        else if (ws.writeFailed())
            throw new IOException("Write failed");
        throwAnyError();
    }
    
    public void close() throws IOException {
        if (_closed) return;
        _closed = true;
        flush();
        _log.debug("Output stream closed after writing " + _written);
        ByteArray ba = null;
        synchronized (_dataLock) {
            if (_buf != null) {
                ba = new ByteArray(_buf);
                _buf = null;
                _valid = 0;
            }
        }
        if (ba != null) {
            _dataCache.release(ba);
        }
    }
    public void closeInternal() {
        _closed = true;
        _streamError = new IOException("Closed internally");
        ByteArray ba = null;
        synchronized (_dataLock) {
            // flush any data, but don't wait for it
            _dataReceiver.writeData(_buf, 0, _valid);
            _written += _valid;
            _valid = 0;
            
            if (_buf != null) {
                ba = new ByteArray(_buf);
                _buf = null;
                _valid = 0;
            }
            _dataLock.notifyAll();
        }
        if (ba != null) {
            _dataCache.release(ba);
        }
    }
    
    public boolean getClosed() { return _closed; }
    
    private void throwAnyError() throws IOException {
        if (_streamError != null) {
            IOException ioe = _streamError;
            _streamError = null;
            throw ioe;
        }
    }
    
    void streamErrorOccurred(IOException ioe) {
        _streamError = ioe;
    }
    
    /** 
     * called whenever the engine wants to push more data to the
     * peer
     *
     * @return true if the data was flushed
     */
    void flushAvailable(DataReceiver target) throws IOException {
        flushAvailable(target, true);
    }
    void flushAvailable(DataReceiver target, boolean blocking) throws IOException {
        WriteStatus ws = null;
        synchronized (_dataLock) {
            // _buf may be null, but the data receiver can handle that just fine,
            // deciding whether or not to send a packet
            ws = target.writeData(_buf, 0, _valid);
            _written += _valid;
            _valid = 0;
            _dataLock.notifyAll();
        }
        if (blocking && ws != null) {
            ws.waitForAccept(_writeTimeout);
            if (ws.writeFailed())
                throw new IOException("Flush available failed");
            else if (!ws.writeAccepted())
                throw new InterruptedIOException("Flush available timed out");
        }
        return;
    }
    
    void destroy() {
        _dataReceiver = null;
    }
    
    public interface DataReceiver {
        /**
         * Nonblocking write
         */
        public WriteStatus writeData(byte buf[], int off, int size);
    }
    
    public interface WriteStatus {
        /** wait until the data written either fails or succeeds */
        public void waitForCompletion(int maxWaitMs);
        /** 
         * wait until the data written is accepted into the outbound pool,
         * which we throttle rather than accept arbitrary data and queue 
         */
        public void waitForAccept(int maxWaitMs);
        /** the write was accepted.  aka did the socket not close? */
        public boolean writeAccepted();
        /** did the write fail?  */
        public boolean writeFailed();
        /** did the write succeed? */
        public boolean writeSuccessful();
    }
}
