package net.i2p.client.streaming;

import java.net.ConnectException;
import net.i2p.I2PException;

/**
 * Bridge to allow accepting new connections
 *
 */
public class I2PServerSocketFull implements I2PServerSocket {
    private I2PSocketManagerFull _socketManager;
    
    public I2PServerSocketFull(I2PSocketManagerFull mgr) {
        _socketManager = mgr;
    }
    
    public I2PSocket accept() throws I2PException {
        return _socketManager.receiveSocket();
    }
    
    public void close() { _socketManager.getConnectionManager().setAllowIncomingConnections(false); }
    
    public I2PSocketManager getManager() { return _socketManager; }
}
