package net.i2p.client.streaming;

import net.i2p.I2PException;
import net.i2p.util.Log;

/**
 * Initial stub implementation for the server socket
 *
 */
class I2PServerSocketImpl implements I2PServerSocket {
    private final static Log _log = new Log(I2PServerSocketImpl.class);
    private I2PSocketManager mgr;
    private I2PSocket cached=null; // buffer one socket here
    
    public I2PServerSocketImpl(I2PSocketManager mgr) {
	this.mgr = mgr;
    }
    
    public synchronized I2PSocket accept() throws I2PException {
	while(cached == null) {
	    myWait();
	}
	I2PSocket ret=cached;
	cached=null;
	notifyAll();
	_log.debug("TIMING: handed out accept result "+ret.hashCode());
	return ret;
    }
    
    public synchronized boolean getNewSocket(I2PSocket s){
	while(cached != null) {
	    myWait();
	}
	cached=s;
	notifyAll();
	return true;
    }
    
    public void close() throws I2PException {
	//noop
    }
    
    private void myWait() {
	try{
	    wait();
	} catch (InterruptedException ex) {}
    }

    public I2PSocketManager getManager() { return mgr; }
}
