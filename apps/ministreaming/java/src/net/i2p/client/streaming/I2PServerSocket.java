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
     */
    public void close() throws I2PException;

    /**
     * Waits for the next socket connecting.  If a remote user tried to make a 
     * connection and the local application wasn't .accept()ing new connections,
     * they should get refused (if .accept() doesnt occur in some small period)
     *
     * @return a connected I2PSocket
     *
     * @throws I2PException if there is a problem with reading a new socket
     *         from the data available (aka the I2PSession closed, etc)
     * @throws ConnectException if the I2PServerSocket is closed
     * @throws SocketTimeoutException 
     */
    public I2PSocket accept() throws I2PException, ConnectException, SocketTimeoutException;

    /**
     * Set Sock Option accept timeout
     * @param x
     */
    public void setSoTimeout(long x);

    /**
     * Get Sock Option accept timeout
     * @return timeout
     */
    public long getSoTimeout();

    /**
     * Access the manager which is coordinating the server socket
     */
    public I2PSocketManager getManager();
}
