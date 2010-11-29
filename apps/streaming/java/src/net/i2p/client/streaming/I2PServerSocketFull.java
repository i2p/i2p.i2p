package net.i2p.client.streaming;

import java.net.SocketTimeoutException;
import net.i2p.I2PException;

/**
 * Bridge to allow accepting new connections
 *
 */
class I2PServerSocketFull implements I2PServerSocket {
    private I2PSocketManagerFull _socketManager;
    
    public I2PServerSocketFull(I2PSocketManagerFull mgr) {
        _socketManager = mgr;
    }
    
    /**
     * 
     * @return I2PSocket
     * @throws net.i2p.I2PException
     * @throws SocketTimeoutException 
     */
    public I2PSocket accept() throws I2PException, SocketTimeoutException {
        return _socketManager.receiveSocket();
    }
    
    public long getSoTimeout() {
        return _socketManager.getConnectionManager().MgetSoTimeout();
    }
    
    public void setSoTimeout(long x) {
        _socketManager.getConnectionManager().MsetSoTimeout(x);
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
