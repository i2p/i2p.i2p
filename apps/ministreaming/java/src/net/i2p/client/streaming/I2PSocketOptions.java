package net.i2p.client.streaming;

/**
 * Define the configuration for streaming and verifying data on the socket.
 * No options available...
 *
 */
public class I2PSocketOptions {
    private long _connectTimeout;

    public I2PSocketOptions() {
        _connectTimeout = -1;
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
}
