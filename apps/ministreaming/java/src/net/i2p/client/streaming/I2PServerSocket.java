package net.i2p.client.streaming;

import java.net.ConnectException;

import java.net.SocketTimeoutException;
import net.i2p.I2PException;

/**
 * Defines how to listen for streaming peer connections
 *
 */
public interface I2PServerSocket {
    /**
     * Closes the socket.
     * @throws I2PException 
     */
    public void close() throws I2PException;

    /**
     * Waits for the next socket connecting.  If a remote user tried to make a 
     * connection and the local application wasn't .accept()ing new connections,
     * they should get refused (if .accept() doesnt occur in some small period).
     * Warning - unlike regular ServerSocket, may return null (through 0.9.16 only).
     *
     * @return a connected I2PSocket OR NULL through 0.9.16; never null as of 0.9.17
     *
     * @throws I2PException if there is a problem with reading a new socket
     *         from the data available (e.g. the I2PSession is closed)
     * @throws ConnectException if the I2PServerSocket is closed, or if interrupted.
     *         Not actually thrown through 0.9.16; thrown as of 0.9.17
     * @throws SocketTimeoutException if a timeout was previously set with setSoTimeout and the timeout has been reached.
     */
    public I2PSocket accept() throws I2PException, ConnectException, SocketTimeoutException;

    /**
     *  Unimplemented, unlikely to ever be implemented.
     *
     *  @deprecated
     *  @return null always
     *  @since 0.8.11
     */
    public AcceptingChannel getChannel();

    /**
     * Set Sock Option accept timeout
     * @param x timeout in ms
     */
    public void setSoTimeout(long x);

    /**
     * Get Sock Option accept timeout
     * @return timeout in ms
     */
    public long getSoTimeout();

    /**
     * Access the manager which is coordinating the server socket
     * @return I2PSocketManager
     */
    public I2PSocketManager getManager();
}
