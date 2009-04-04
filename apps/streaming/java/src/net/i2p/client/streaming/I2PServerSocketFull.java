package net.i2p.client.streaming;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Bridge to allow accepting new connections
 *
 */
public class I2PServerSocketFull implements I2PServerSocket {
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

    /**
     * accept(true) has the same behaviour as accept().
     * accept(false) does not wait for a socket connecting. If a socket is
     * available in the queue, it is accepted. Else, null is returned. 
     *
     * @param true if the call should block until a socket is available
     *
     * @return a connected I2PSocket, or null
     *
     * @throws I2PException if there is a problem with reading a new socket
     *         from the data available (aka the I2PSession closed, etc)
     * @throws SocketTimeoutException if the timeout has been reached
     */

	public I2PSocket accept(long timeout)  throws I2PException {
		long reset_timeout = this.getSoTimeout();

		try {
			this.setSoTimeout(timeout);
			return this.accept();
		} catch (SocketTimeoutException e) {
			return null ;
		} finally {
			this.setSoTimeout(reset_timeout);
		}
	}

	public void waitIncoming(long timeoutMs) throws InterruptedException {
        this._socketManager.getConnectionManager().getConnectionHandler().waitSyn(timeoutMs);
	}
}
