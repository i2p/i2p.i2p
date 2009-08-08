package net.i2p.client.streaming;

import java.util.Properties;

/**
 * Define the configuration for streaming and verifying data on the socket.
 *
 */
public class I2PSocketOptionsImpl implements I2PSocketOptions {
    private long _connectTimeout;
    private long _readTimeout;
    private long _writeTimeout;
    private int _maxBufferSize;
    
    public static final int DEFAULT_BUFFER_SIZE = 1024*64;
    public static final int DEFAULT_WRITE_TIMEOUT = -1;
    public static final int DEFAULT_CONNECT_TIMEOUT = 60*1000;
    
    public I2PSocketOptionsImpl() {
        this(System.getProperties());
    }
    
    public I2PSocketOptionsImpl(I2PSocketOptions opts) {
        this(System.getProperties());
        if (opts != null) {
            _connectTimeout = opts.getConnectTimeout();
            _readTimeout = opts.getReadTimeout();
            _writeTimeout = opts.getWriteTimeout();
            _maxBufferSize = opts.getMaxBufferSize();
        }
    }

    public I2PSocketOptionsImpl(Properties opts) {
        init(opts);
    }
    
    public void setProperties(Properties opts) {
        if (opts == null) return;
        if (opts.containsKey(PROP_BUFFER_SIZE))
            _maxBufferSize = getInt(opts, PROP_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        if (opts.containsKey(PROP_CONNECT_TIMEOUT))
            _connectTimeout = getInt(opts, PROP_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
        if (opts.containsKey(PROP_READ_TIMEOUT))
            _readTimeout = getInt(opts, PROP_READ_TIMEOUT, -1);
        if (opts.containsKey(PROP_WRITE_TIMEOUT))
            _writeTimeout = getInt(opts, PROP_WRITE_TIMEOUT, DEFAULT_WRITE_TIMEOUT);
    }
    
    protected void init(Properties opts) {
        _maxBufferSize = getInt(opts, PROP_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        _connectTimeout = getInt(opts, PROP_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
        _readTimeout = getInt(opts, PROP_READ_TIMEOUT, -1);
        _writeTimeout = getInt(opts, PROP_WRITE_TIMEOUT, DEFAULT_WRITE_TIMEOUT);
    }
    
    protected static int getInt(Properties opts, String name, int defaultVal) {
        if (opts == null) return defaultVal;
        String val = opts.getProperty(name);
        if (val == null) {
            return defaultVal;
        } else {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                return defaultVal;
            }
        }
    }
    
    /**
     * How long we will wait for the ACK from a SYN, in milliseconds.
     *
     * @return milliseconds to wait, or -1 if we will wait indefinitely
     */
    public long getConnectTimeout() {
        return _connectTimeout;
    }

    /**
     * Define how long we will wait for the ACK from a SYN, in milliseconds.
     *
     */
    public void setConnectTimeout(long ms) {
        _connectTimeout = ms;
    }
    
    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws 
     * InterruptedIOException
     */
    public long getReadTimeout() {
        return _readTimeout;
    }

    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws 
     * InterruptedIOException
     */
    public void setReadTimeout(long ms) {
        _readTimeout = ms;
    }
    
    /**
     * How much data will we accept that hasn't been written out yet.  After 
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is 
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     * @return buffer size limit, in bytes
     */
    public int getMaxBufferSize() {
        return _maxBufferSize; 
    }
    
    /**
     * How much data will we accept that hasn't been written out yet.  After 
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is 
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     */
    public void setMaxBufferSize(int numBytes) {
        _maxBufferSize = numBytes; 
    }
    
    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws 
     * InterruptedIOException.  If this is less than or equal to zero, there 
     * is no timeout.
     */
    public long getWriteTimeout() {
        return _writeTimeout;
    }

    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws 
     * InterruptedIOException.  If this is less than or equal to zero, there 
     * is no timeout.
     */
    public void setWriteTimeout(long ms) {
        _writeTimeout = ms;
    }
}
