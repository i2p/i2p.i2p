package net.i2p.client.streaming;

import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.i2p.I2PException;

/**
 * Bridge to allow accepting new connections
 *
 */
public class I2PServerSocketFull implements I2PServerSocket {

    private I2PSocketManagerFull _socketManager;
    
	/**
	 * 
	 * @param mgr
	 */
    public I2PServerSocketFull(I2PSocketManagerFull mgr) {
        _socketManager = mgr;
    }
    
	/**
	 * 
	 * @return
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
