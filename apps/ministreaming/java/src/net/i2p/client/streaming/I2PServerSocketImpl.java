package net.i2p.client.streaming;

import java.net.ConnectException;

import net.i2p.I2PException;
import net.i2p.util.Log;

/**
 * Initial stub implementation for the server socket
 *
 */
class I2PServerSocketImpl implements I2PServerSocket {
    private final static Log _log = new Log(I2PServerSocketImpl.class);
    private I2PSocketManager mgr;
    private I2PSocket cached = null; // buffer one socket here

    private boolean closing = false; // Are we being closed?

    private Object acceptLock = new Object();

    public I2PServerSocketImpl(I2PSocketManager mgr) {
        this.mgr = mgr;
    }

    public synchronized I2PSocket accept() throws I2PException, ConnectException {
	I2PSocket ret;
	
	synchronized (acceptLock) {
	    while ((cached == null) && !closing) {
		myWait();
	    }

	    if (closing) {
		throw new ConnectException("I2PServerSocket closed");
	    }

	    ret = cached;
	    cached = null;
	    acceptLock.notifyAll();
	}
	
        _log.debug("TIMING: handed out accept result " + ret.hashCode());
        return ret;
    }

    public boolean getNewSocket(I2PSocket s) {
	synchronized (acceptLock) {
	    while (cached != null) {
		myWait();
	    }
	    cached = s;
	    acceptLock.notifyAll();
	}

        return true;
    }

    public void close() throws I2PException {
	synchronized (acceptLock) {
	    closing = true;
	    acceptLock.notifyAll();
	}
    }

    public I2PSocketManager getManager() {
        return mgr;
    }

    private void myWait() {
	try {
	    acceptLock.wait();
	} catch (InterruptedException ex) {}
    }
}
