package net.i2p.client.streaming;

/**
 * Define the configuration for streaming and verifying data on the socket.
 *
 */
public interface I2PSocketOptions {
    public static final String PROP_BUFFER_SIZE = "i2p.streaming.bufferSize";
    public static final String PROP_CONNECT_TIMEOUT = "i2p.streaming.connectTimeout";
    public static final String PROP_READ_TIMEOUT = "i2p.streaming.readTimeout";
    public static final String PROP_WRITE_TIMEOUT = "i2p.streaming.writeTimeout";
    
    /**
     * How long we will wait for the ACK from a SYN, in milliseconds.
     *
     * @return milliseconds to wait, or -1 if we will wait indefinitely
     */
    public long getConnectTimeout();

    /**
     * Define how long we will wait for the ACK from a SYN, in milliseconds.
     *
     */
    public void setConnectTimeout(long ms);
    
    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws 
     * InterruptedIOException
     */
    public long getReadTimeout();

    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws 
     * InterruptedIOException
     */
    public void setReadTimeout(long ms);
    
    /**
     * How much data will we accept that hasn't been written out yet.  After 
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is 
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     * @return buffer size limit, in bytes
     */
    public int getMaxBufferSize();
    
    /**
     * How much data will we accept that hasn't been written out yet.  After 
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is 
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     */
    public void setMaxBufferSize(int numBytes);
    
    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws 
     * InterruptedIOException.  If this is less than or equal to zero, there 
     * is no timeout.
     */
    public long getWriteTimeout();

    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws 
     * InterruptedIOException.  If this is less than or equal to zero, there 
     * is no timeout.
     */
    public void setWriteTimeout(long ms);
}
