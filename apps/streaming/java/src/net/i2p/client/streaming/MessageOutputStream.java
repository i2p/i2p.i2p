package net.i2p.client.streaming;

import java.io.IOException;
import java.io.OutputStream;

/**
 *
 */
public class MessageOutputStream extends OutputStream {
    private byte _buf[];
    private int _valid;
    private Object _dataLock;
    private DataReceiver _dataReceiver;
    private IOException _streamError;
    
    public MessageOutputStream(DataReceiver receiver) {
        this(receiver, 64*1024);
    }
    public MessageOutputStream(DataReceiver receiver, int bufSize) {
        super();
        _buf = new byte[bufSize];
        _dataReceiver = receiver;
        _dataLock = new Object();
    }
    
    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }
    
    public void write(byte b[], int off, int len) throws IOException {
        synchronized (_dataLock) {
            int remaining = len;
            while (remaining > 0) {
                if (_valid + remaining < _buf.length) {
                    // simply buffer the data, no flush
                    System.arraycopy(b, off, _buf, _valid, remaining);
                    remaining = 0;
                } else {
                    // buffer whatever we can fit then flush,
                    // repeating until we've pushed all of the
                    // data through
                    int toWrite = _buf.length - _valid;
                    System.arraycopy(b, off, _buf, _valid, toWrite);
                    remaining -= toWrite;
                    _valid = _buf.length;
                    _dataReceiver.writeData(_buf, 0, _valid);
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
            _dataReceiver.writeData(_buf, 0, _valid);
            _valid = 0;
        }
        throwAnyError();
    }
    
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
    void flushAvailable(DataReceiver target) {
        synchronized (_dataLock) {
            target.writeData(_buf, 0, _valid);
            _valid = 0;
        }
    }
    
    public interface DataReceiver {
        public void writeData(byte buf[], int off, int size);
    }
}
