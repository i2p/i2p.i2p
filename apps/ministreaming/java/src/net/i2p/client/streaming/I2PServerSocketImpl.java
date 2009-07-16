package net.i2p.client.streaming;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Server socket implementation, allowing multiple threads to accept I2PSockets 
 * and pull from a queue populated by various threads (each of whom have their own
 * timeout)
 *
 */
class I2PServerSocketImpl implements I2PServerSocket {
    private final static Log _log = new Log(I2PServerSocketImpl.class);
    private I2PSocketManager mgr;
    /** list of sockets waiting for the client to accept them */
    private final List<I2PSocket> pendingSockets = Collections.synchronizedList(new ArrayList<I2PSocket>(4));
    
    /** have we been closed */
    private volatile boolean closing = false;
    
    /** lock on this when accepting a pending socket, and wait on it for notification of acceptance */
    private final Object socketAcceptedLock = new Object();
    /** lock on this when adding a new socket to the pending list, and wait on it accordingly */
    private final Object socketAddedLock = new Object();
    
    /**
     * Set Sock Option accept timeout stub, does nothing in ministreaming
     * @param x
     */
    public void setSoTimeout(long x) {
    }

    /**
     * Get Sock Option accept timeout stub, does nothing in ministreaming
     * @return timeout
     */
    public long getSoTimeout() {
        return -1;
    }

    public I2PServerSocketImpl(I2PSocketManager mgr) {
        this.mgr = mgr;
    }
    
    /**
     * Waits for the next socket connecting.  If a remote user tried to make a 
     * connection and the local application wasn't .accept()ing new connections,
     * they should get refused (if .accept() doesnt occur in some small period -
     * currently 5 seconds)
     *
     * @return a connected I2PSocket
     *
     * @throws I2PException if there is a problem with reading a new socket
     *         from the data available (aka the I2PSession closed, etc)
     * @throws ConnectException if the I2PServerSocket is closed
     */
    public I2PSocket accept() throws I2PException, ConnectException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("accept() called, pending: " + pendingSockets.size());
        
        I2PSocket ret = null;
        
        while ( (ret == null) && (!closing) ){
            while (pendingSockets.size() <= 0) {
                if (closing) throw new ConnectException("I2PServerSocket closed");
                try {
                    synchronized(socketAddedLock) {
                        socketAddedLock.wait();
                    }
                } catch (InterruptedException ie) {}
            }
            synchronized (pendingSockets) {
                if (pendingSockets.size() > 0) {
                    ret = (I2PSocket)pendingSockets.remove(0);
                }
            } 
            if (ret != null) {
                synchronized (socketAcceptedLock) {
                    socketAcceptedLock.notifyAll();
                }
            }
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("TIMING: handed out accept result " + ret.hashCode());
        return ret;
    }
    
    /**
     * Make the socket available and wait until the client app accepts it, or until
     * the given timeout elapses.  This doesn't have any limits on the queue size -
     * perhaps it should add some choking (e.g. after 5 waiting for accept, refuse)
     *
     * @param timeoutMs how long to wait until accept
     * @return true if the socket was accepted, false if the timeout expired 
     *         or the socket was closed
     */
    public boolean addWaitForAccept(I2PSocket s, long timeoutMs) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("addWaitForAccept [new socket arrived [" + s.toString() + "], pending: " + pendingSockets.size());
        
        if (closing) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Already closing the socket");
            return false;
        }
        
        Clock clock = I2PAppContext.getGlobalContext().clock();
        long start = clock.now();
        long end = start + timeoutMs;
        pendingSockets.add(s);
        synchronized (socketAddedLock) {
            socketAddedLock.notifyAll();
        }
        
        // keep looping until the socket has been grabbed by the accept()
        // (or the expiration passes, or the socket is closed)
        while (pendingSockets.contains(s)) {
            long now = clock.now();
            if (now >= end) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Expired while waiting for accept (time elapsed =" + (now - start) + "ms) for socket " + s.toString());
                pendingSockets.remove(s);
                return false;
            }
            if (closing) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Server socket closed while waiting for accept");
                pendingSockets.remove(s);
                return false;
            }
            long remaining = end - now;
            try {
                synchronized (socketAcceptedLock) {
                    socketAcceptedLock.wait(remaining);
                }
            } catch (InterruptedException ie) {}
        }
        long now = clock.now();
        if (_log.shouldLog(Log.DEBUG))
            _log.info("Socket accepted after " + (now-start) + "ms for socket " + s.toString());
        return true;
    }
    
    public void close() {
        closing = true;
        // let anyone .accept()ing know to fsck off
        synchronized (socketAddedLock) {
            socketAddedLock.notifyAll();
        }
        // let anyone addWaitForAccept()ing know to fsck off
        synchronized (socketAcceptedLock) {
            socketAcceptedLock.notifyAll();
        }
    }
    
    public I2PSocketManager getManager() { return mgr; }
}
