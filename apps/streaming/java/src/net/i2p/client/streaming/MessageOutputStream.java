package net.i2p.client.streaming;

import java.io.IOException;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
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
    
    public MessageOutputStream(I2PAppContext ctx, DataReceiver receiver) {
        this(ctx, receiver, Packet.MAX_PAYLOAD_SIZE);
    }
    public MessageOutputStream(I2PAppContext ctx, DataReceiver receiver, int bufSize) {
        super();
        _context = ctx;
        _log = ctx.logManager().getLog(MessageOutputStream.class);
        _buf = new byte[bufSize];
        _dataReceiver = receiver;
        _dataLock = new Object();
        _written = 0;
        _closed = false;
    }
    
    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }
    
    public void write(byte b[], int off, int len) throws IOException {
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("write(b[], " + off + ", " + len + ")");
        synchronized (_dataLock) {
            int cur = off;
            int remaining = len;
            while (remaining > 0) {
                if (_valid + remaining < _buf.length) {
                    // simply buffer the data, no flush
                    System.arraycopy(b, cur, _buf, _valid, remaining);
                    _valid += remaining;
                    cur += remaining;
                    _written += remaining;
                    remaining = 0;
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug("write(...): appending valid = " + _valid + " remaining=" + remaining);
                } else {
                    // buffer whatever we can fit then flush,
                    // repeating until we've pushed all of the
                    // data through
                    int toWrite = _buf.length - _valid;
                    System.arraycopy(b, cur, _buf, _valid, toWrite);
                    remaining -= toWrite;
                    cur += toWrite;
                    _valid = _buf.length;
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug("write(...): flushing valid = " + _valid + " remaining=" + remaining);
                    // this blocks until the packet is ack window is open.  it 
                    // also throws InterruptedIOException if the write timeout 
                    // expires
                    _dataReceiver.writeData(_buf, 0, _valid);
                    //if (_log.shouldLog(Log.DEBUG))
                    //    _log.debug("write(...): flushing complete valid = " + _valid + " remaining=" + remaining);
                    _written += _valid;
                    _valid = 0;                       
                    throwAnyError();
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
        synchronized (_dataLock) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("flush(): valid = " + _valid);
            // this blocks until the packet is ack window is open.  it 
            // also throws InterruptedIOException if the write timeout 
            // expires
            _dataReceiver.writeData(_buf, 0, _valid);
            _written += _valid;
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("flush(): valid = " + _valid + " complete");
            _valid = 0;
            _dataLock.notifyAll();
        }
        throwAnyError();
    }
    
    public void close() throws IOException {
        _closed = true;
        flush();
        _log.debug("Output stream closed after writing " + _written);
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
     */
    void flushAvailable(DataReceiver target) throws IOException {
        synchronized (_dataLock) {
            target.writeData(_buf, 0, _valid);
            _written += _valid;
            _valid = 0;
            _dataLock.notifyAll();
        }
    }
    
    public interface DataReceiver {
        public void writeData(byte buf[], int off, int size) throws IOException;
    }
}
