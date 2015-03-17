package net.i2p.util;

import java.io.ByteArrayOutputStream;

import net.i2p.data.ByteArray;

/**
 * simple extension to the baos to try to use a ByteCache for its 
 * internal buffer.  This caching only works when the array size 
 * provided is sufficient for the entire buffer.  After doing what
 * needs to be done (e.g. write(foo); toByteArray();), call releaseBuffer
 * to put the buffer back into the cache.
 * 
 * @deprecated unused
 */
public class CachingByteArrayOutputStream extends ByteArrayOutputStream {
    private ByteCache _cache;
    private ByteArray _buf;
    
    public CachingByteArrayOutputStream(int cacheQuantity, int arraySize) {
        super(0);
        _cache = ByteCache.getInstance(cacheQuantity, arraySize);
        _buf = _cache.acquire();
        super.buf = _buf.getData();
    }
    
    public void releaseBuffer() { _cache.release(_buf); }
}
