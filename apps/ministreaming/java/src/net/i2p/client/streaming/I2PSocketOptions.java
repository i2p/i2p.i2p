package net.i2p.client.streaming;

import java.util.Iterator;
import java.util.Properties;

/**
 * Define the configuration for streaming and verifying data on the socket.
 *
 */
public class I2PSocketOptions {
    private long _connectTimeout;
    private long _readTimeout;
    private long _writeTimeout;
    private int _maxBufferSize;

    public static final int DEFAULT_BUFFER_SIZE = 1024*64;
    public static final int DEFAULT_WRITE_TIMEOUT = 60*1000;
    public static final int DEFAULT_CONNECT_TIMEOUT = 60*1000;
    
    public static final String PROP_BUFFER_SIZE = "i2p.streaming.bufferSize";
    public static final String PROP_CONNECT_TIMEOUT = "i2p.streaming.connectTimeout";
    public static final String PROP_READ_TIMEOUT = "i2p.streaming.readTimeout";
    public static final String PROP_WRITE_TIMEOUT = "i2p.streaming.writeTimeout";
    
    public I2PSocketOptions() {
        this(System.getProperties());
    }
    
    public I2PSocketOptions(I2PSocketOptions opts) {
        this(System.getProperties());
        _connectTimeout = opts.getConnectTimeout();
        _readTimeout = opts.getReadTimeout();
        _writeTimeout = opts.getWriteTimeout();
        _maxBufferSize = opts.getMaxBufferSize();
    }

    public I2PSocketOptions(Properties opts) {
        init(opts);
    }
    
    protected void init(Properties opts) {
        _maxBufferSize = getInt(opts, PROP_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        _connectTimeout = getInt(opts, PROP_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
        _readTimeout = getInt(opts, PROP_READ_TIMEOUT, -1);
        _writeTimeout = getInt(opts, PROP_WRITE_TIMEOUT, DEFAULT_WRITE_TIMEOUT);
    }
    /*
    
    protected Properties getEnvProps() {
        Properties rv = new Properties();
        for (Iterator iter = System.getProperties().keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            rv.setProperty(name, System.getProperty(name));
        }
        return rv;
    }
    
    public static void main(String args[]) {
        System.out.println("System props: " + System.getProperties());
        System.out.println("Env props:    " + new I2PSocketOptions().getEnvProps());
    }

    */
    protected int getInt(Properties opts, String name, int defaultVal) {
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
