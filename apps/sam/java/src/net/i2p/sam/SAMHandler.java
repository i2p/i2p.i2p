package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Base class for SAM protocol handlers.  It implements common
 * methods, but is not able to actually parse the protocol itself:
 * this task is delegated to subclasses.
 *
 * @author human
 */
public abstract class SAMHandler implements Runnable {

    private final static Log _log = new Log(SAMHandler.class);

    protected I2PThread thread = null;

    private Object socketWLock = new Object(); // Guards writings on socket
    private OutputStream socketOS = null; // Stream associated to socket
    protected Socket socket = null;

    protected int verMajor = 0;
    protected int verMinor = 0;

    private boolean stopHandler = false;
    private Object  stopLock = new Object();

    /**
     * Start handling the SAM connection, detaching an handling thread.
     *
     */
    public void startHandling() {
	thread = new I2PThread(this, "SAMHandler");
	thread.start();
    }
    
    /**
     * Actually handle the SAM protocol.
     *
     */
    protected abstract void handle();

    /**
     * Write a byte array on the handler's socket.  This method must
     * always be used when writing data, unless you really know what
     * you're doing.
     *
     * @param data A byte array to be written
     */
    protected void writeBytes(byte[] data)  throws IOException {
	synchronized (socketWLock) {
	    if (socketOS == null) {
		socketOS = socket.getOutputStream();
	    }
	    socketOS.write(data);
	    socketOS.flush();
	}
    }

    /**
     * Stop the SAM handler
     *
     */
    public void stopHandling() {
	synchronized (stopLock) {
	    stopHandler = true;
	}
    }

    /**
     * Should the handler be stopped?
     *
     * @return True if the handler should be stopped, false otherwise
     */
    protected boolean shouldStop() {
	synchronized (stopLock) {
	    return stopHandler;
	}
    }

    /**
     * Get a string describing the handler.
     *
     * @return A String describing the handler;
     */
    public abstract String toString();

    public final void run() {
	handle();
    }
}
