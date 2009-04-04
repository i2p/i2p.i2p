package net.i2p.client.streaming;

import java.net.SocketTimeoutException;

import net.i2p.I2PAppContext;
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
     * accept(timeout) waits timeout ms for a socket connecting. If a socket is
     * not available during the timeout, return null. accept(0) behaves like accept() 
     *
     * @param timeout in ms
     *
     * @return a connected I2PSocket, or null
     *
     * @throws I2PException if there is a problem with reading a new socket
     *         from the data available (aka the I2PSession closed, etc)
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

	/**
	 * block until a SYN packet is detected or the timeout is reached. If timeout is 0,
	 * block until a SYN packet is detected.
	 * 
	 * @param timeoutMs
	 * @throws InterruptedException
	 * @throws I2PException
	 */
	public void waitIncoming(long timeoutMs) throws I2PException, InterruptedException {
		if (this._socketManager.getConnectionManager().getSession().isClosed())
			throw new I2PException("Session is closed");
        this._socketManager.getConnectionManager().getConnectionHandler().waitSyn(timeoutMs);
	}
}
