package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.HexDump;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * SAM RAW session class.
 *
 * @author human
 */
public class SAMRawSession {

    private final static Log _log = new Log(SAMRawSession.class);

    private I2PSession session = null;

    private SAMRawReceiver recv = null;

    private SAMRawSessionHandler handler = null;

    /**
     * Create a new SAM RAW session.
     *
     * @param dest Base64-encoded destination (private key)
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     */
    public SAMRawSession(String dest, Properties props,
			 SAMRawReceiver recv) throws DataFormatException, I2PSessionException {
	ByteArrayInputStream bais;

	bais = new ByteArrayInputStream(Base64.decode(dest));

	initSAMRawSession(bais, props, recv);
    }

    /**
     * Create a new SAM RAW session.
     *
     * @param destStream Input stream containing the destination keys
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     */
    public SAMRawSession(InputStream destStream, Properties props,
			 SAMRawReceiver recv) throws I2PSessionException {
	initSAMRawSession(destStream, props, recv);
    }

    private void initSAMRawSession(InputStream destStream, Properties props,
				   SAMRawReceiver recv) throws I2PSessionException {
	this.recv = recv;

	_log.debug("SAM RAW session instantiated");

	handler = new SAMRawSessionHandler(destStream, props);
	Thread t = new I2PThread(handler, "SAMRawSessionHandler");

	t.start();
    }

    /**
     * Send bytes through a SAM RAW session.
     *
     * @param data Bytes to be sent
     *
     * @return True if the data was sent, false otherwise
     */
    public boolean sendBytes(String dest, byte[] data) throws DataFormatException {
	Destination d = new Destination();
	d.fromBase64(dest);

	try {
	    return session.sendMessage(d, data);
	} catch (I2PSessionException e) {
	    _log.error("I2PSessionException while sending data", e);
	    return false;
	}
    }

    /**
     * Close a SAM RAW session.
     *
     */
    public void close() {
	handler.stopRunning();
    }

    /**
     * SAM RAW session handler, running in its own thread
     *
     * @author human
     */
    public class SAMRawSessionHandler implements Runnable, I2PSessionListener {

	private Object runningLock = new Object();
	private boolean stillRunning = true;
		
	/**
	 * Create a new SAM RAW session handler
	 *
	 * @param destStream Input stream containing the destination keys
	 * @param props Properties to setup the I2P session
	 */
	public SAMRawSessionHandler(InputStream destStream, Properties props) throws I2PSessionException {
	    _log.debug("Instantiating new SAM RAW session handler");

	    I2PClient client = I2PClientFactory.createClient();
	    session = client.createSession(destStream, props);

	    _log.debug("Connecting I2P session...");
	    session.connect();
	    _log.debug("I2P session connected");

	    session.setSessionListener(this);
	}

	/**
	 * Stop a SAM RAW session handling thread
	 *
	 */
	public void stopRunning() {
	    synchronized (runningLock) {
		stillRunning = false;
		runningLock.notify();
	    }
	}

	public void run() {

	    _log.debug("SAM RAW session handler running");

	    synchronized (runningLock) {
		while (stillRunning) {
		    try {
			runningLock.wait();
		    } catch (InterruptedException ie) {}
		}
		_log.debug("Shutting down SAM RAW session handler");

		recv.stopReceiving();

		try {
		    _log.debug("Destroying I2P session...");
		    session.destroySession();
		    _log.debug("I2P session destroyed");
		} catch (I2PSessionException e) {
		    _log.error("Error destroying I2P session", e);
		}
	    }
	}
	
	public void disconnected(I2PSession session) {
	    _log.debug("I2P session disconnected");
	    stopRunning();
	}

	public void errorOccurred(I2PSession session, String message,
				  Throwable error) {
	    _log.debug("I2P error: " + message, error);
	    stopRunning();
	}
	
	
	public void messageAvailable(I2PSession session, int msgId, long size){
	    _log.debug("I2P message available (id: " + msgId
		       + "; size: " + size + ")");
	    try {
		byte msg[] = session.receiveMessage(msgId);
		if (_log.shouldLog(Log.DEBUG)) {
		    _log.debug("Content of message " + msgId + ":\n"
			       + HexDump.dump(msg));
		}
		
		recv.receiveRawBytes(msg);
	    } catch (IOException e) {
		_log.error("Error forwarding message to receiver", e);
		stopRunning();
	    } catch (I2PSessionException e) {
		_log.error("Error fetching I2P message", e);
		stopRunning();
	    }
	    
	}
	
	public void reportAbuse(I2PSession session, int severity) {
	    _log.warn("Abuse reported (severity: " + severity + ")");
	    stopRunning();
	}
    }
}
