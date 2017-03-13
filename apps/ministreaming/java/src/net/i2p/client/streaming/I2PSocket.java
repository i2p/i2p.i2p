package net.i2p.client.streaming;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SelectableChannel;

import net.i2p.data.Destination;

/**
 *  Streaming socket returned by {@link I2PSocketManager#connect(Destination)}.
 *<p>
 *  Note that this is not a standard Java {@link java.net.Socket},
 *  if you need one of those, use {@link I2PSocketManager#connectToSocket(Destination)} instead.
 */
public interface I2PSocket extends Closeable {
    /**
     * @return the Destination of this side of the socket.
     */
    public Destination getThisDestination();

    /**
     * @return the destination of the peer.
     */
    public Destination getPeerDestination();

    /**
     *  As of 0.9.9 will throw an IOE if socket is closed.
     *  Prior to that would return null instead of throwing IOE.
     *<p>
     *  Note that operations on the returned stream may return an
     *  {@link IOException} whose <i>cause</i> as returned by
     *  {@link IOException#getCause()} is an {@link I2PSocketException}.
     *  If so, the client may retrieve a status code via
     *  {@link I2PSocketException#getStatus()} to provide specific feedback to the user.
     *
     * @return an InputStream to read from the socket. Non-null since 0.9.9.
     * @throws IOException on failure
     */
    public InputStream getInputStream() throws IOException;

    /**
     *  As of 0.9.9 will throw an IOE if socket is closed.
     *  Prior to that would return null instead of throwing IOE.
     *<p>
     *  Note that operations on the returned stream may return an
     *  {@link IOException} whose <i>cause</i> as returned by
     *  {@link IOException#getCause()} is an {@link I2PSocketException}.
     *  If so, the client may retrieve a status code via
     *  {@link I2PSocketException#getStatus()} to provide specific feedback to the user.
     *
     * @return an OutputStream to write into the socket. Non-null since 0.9.9.
     * @throws IOException on failure
     */
    public OutputStream getOutputStream() throws IOException;

    /**
     *  Unimplemented, unlikely to ever be implemented.
     *
     *  @deprecated
     *  @return null always
     *  @since 0.8.9
     */
    @Deprecated
    public SelectableChannel getChannel() throws IOException;

    /** 
     * @return socket's configuration
     */
    public I2PSocketOptions getOptions();
    /** 
     * Configure the socket
     * @param options I2PSocketOptions to set
     */
    public void setOptions(I2PSocketOptions options);
    
    /**
     * How long we will wait blocked on a read() operation.  This is simply a
     * helper to query the I2PSocketOptions
     *
     * @return milliseconds to wait, or -1 if we will wait indefinitely
     */
    public long getReadTimeout();

    /**
     * Define how long we will wait blocked on a read() operation (-1 will make
     * the socket wait forever).  This is simply a helper to adjust the 
     * I2PSocketOptions
     *
     * @param ms timeout in ms
     */
    public void setReadTimeout(long ms);

    public boolean isClosed();

    /**
     *  Deprecated, unimplemented, does nothing
     */
    public void setSocketErrorListener(SocketErrorListener lsnr);

    /**
     *  The remote port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getPort();

    /**
     *  The local port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getLocalPort();
    
    /**
     *  Resets and closes this socket. Sends a RESET indication to the far-end.
     *  This is the equivalent of setSoLinger(true, 0) followed by close() on a Java Socket.
     *
     *  Nonblocking.
     *  Any thread currently blocked in an I/O operation upon this socket will throw an IOException.
     *  Once a socket has been reset, it is not available for further networking use
     *  (i.e. can't be reconnected or rebound). A new socket needs to be created.
     *  Resetting this socket will also close the socket's InputStream and OutputStream.
     *
     *  @since 0.9.30
     */
    public void reset() throws IOException;

    /**
     * Deprecated, unimplemented, does nothing. Original description:
     *
     * Allow notification of underlying errors communicating across I2P without
     * waiting for any sort of cleanup process.  For example, if some data could
     * not be sent, this listener is notified immediately, and while the input/output
     * streams are notified through IOExceptions, they are told only after the 
     * TCP-like stream is closed (which may be a minute later, if the close message
     * times out as well).  This is not fired on normal close() activity.
     *
     */
    public interface SocketErrorListener {
        /**
         * An error occurred communicating with the peer.
         */
        void errorOccurred();
    }
}
