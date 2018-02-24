package net.i2p.client.streaming.impl;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import net.i2p.I2PException;
import net.i2p.client.streaming.AcceptingChannel;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;

/**
 * Bridge to allow accepting new connections
 *
 */
class I2PServerSocketFull implements I2PServerSocket {
    private final I2PSocketManagerFull _socketManager;
    
    public I2PServerSocketFull(I2PSocketManagerFull mgr) {
        _socketManager = mgr;
    }
    
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
     * @throws RouterRestartException (extends I2PException) if the router is apparently restarting, since 0.9.34
     * @throws ConnectException if the I2PServerSocket is closed, or if interrupted.
     *         Not actually thrown through 0.9.16; thrown as of 0.9.17
     * @throws SocketTimeoutException if a timeout was previously set with setSoTimeout and the timeout has been reached.
     */
    public I2PSocket accept() throws I2PException, ConnectException, SocketTimeoutException {
        return _socketManager.receiveSocket();
    }

    /**
     *  Unimplemented, unlikely to ever be implemented.
     *
     *  @deprecated
     *  @return null always
     *  @since 0.8.11
     */
    @Deprecated
    public synchronized AcceptingChannel getChannel() {
        return null;
    }
    
    public long getSoTimeout() {
        return _socketManager.getConnectionManager().getSoTimeout();
    }
    
    public void setSoTimeout(long x) {
        _socketManager.getConnectionManager().setSoTimeout(x);
    }
    /**
     * Close the connection.
     */
    public void close() {
        _socketManager.getConnectionManager().setAllowIncomingConnections(false);
    }

    /**
     * 
     * @return _socketManager
     */
    public I2PSocketManager getManager() {
        return _socketManager;
    }
}
