package net.i2p.router.transport;

import java.io.InputStream;

/**
 * Read up to a specified number of bytes, then EOF.
 * Uses pretty much no memory.
 *
 */
public class FakeInputStream extends InputStream {
    private volatile int _numRead;
    private int _size;
    
    public FakeInputStream(int size) {
        _size = size;
        _numRead = 0;
    }
    public int read() {
        int rv = 0;
        if (_numRead >= _size) 
            rv = -1;
        else
            rv = 42;
        _numRead++;
        return rv;
    }
}