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
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.HexDump;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Base abstract class for SAM message-based sessions.
 *
 * @author human
 */
abstract class SAMMessageSession {

    protected final Log _log;
    private I2PSession session;
    private SAMMessageSessionHandler handler;

    /**
     * Initialize a new SAM message-based session.
     *
     * @param dest Base64-encoded destination and private keys (same format as PrivateKeyFile)
     * @param props Properties to setup the I2P session
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    protected SAMMessageSession(String dest, Properties props) throws IOException, DataFormatException, I2PSessionException {
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        ByteArrayInputStream bais = new ByteArrayInputStream(Base64.decode(dest));
        initSAMMessageSession(bais, props);
    }

    /**
     * Initialize a new SAM message-based session.
     *
     * @param destStream Input stream containing the destination and private keys (same format as PrivateKeyFile)
     * @param props Properties to setup the I2P session
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    protected SAMMessageSession(InputStream destStream, Properties props) throws IOException, DataFormatException, I2PSessionException {
        _log = new Log(getClass());
        initSAMMessageSession(destStream, props);
    }

    private void initSAMMessageSession (InputStream destStream, Properties props) throws IOException, DataFormatException, I2PSessionException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Initializing SAM message-based session");

        handler = new SAMMessageSessionHandler(destStream, props);

        Thread t = new I2PAppThread(handler, "SAMMessageSessionHandler");
        t.start();
    }

    /**
     * Get the SAM message-based session Destination.
     *
     * @return The SAM message-based session Destination.
     */
    public Destination getDestination() {
        return session.getMyDestination();
    }

    /**
     * Send bytes through a SAM message-based session.
     *
     * @param dest Destination
     * @param data Bytes to be sent
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException 
     */
    public abstract boolean sendBytes(String dest, byte[] data) throws DataFormatException;

    /**
     * Actually send bytes through the SAM message-based session I2PSession
     * (er...).
     *
     * @param dest Destination
     * @param data Bytes to be sent
     * @param proto I2CP protocol
     * @param fromPort I2CP from port
     * @param toPort I2CP to port
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException on unknown / bad dest
     */
    protected boolean sendBytesThroughMessageSession(String dest, byte[] data,
                                                     int proto, int fromPort, int toPort) throws DataFormatException {
	Destination d = SAMUtils.getDest(dest);

	if (_log.shouldLog(Log.DEBUG)) {
	    _log.debug("Sending " + data.length + " bytes to " + dest);
	}

	try {
	    return session.sendMessage(d, data, proto, fromPort, toPort);
	} catch (I2PSessionException e) {
	    _log.error("I2PSessionException while sending data", e);
	    return false;
	}
    }

    /**
     * Close a SAM message-based session.
     *
     */
    public void close() {
        handler.stopRunning();
    }

    /**
     * Handle a new received message
     * @param msg Message payload
     */
    protected abstract void messageReceived(byte[] msg);
    
    /**
     * Do whatever is needed to shutdown the SAM session
     */
    protected abstract void shutDown();


    /**
     * Get the I2PSession object used by the SAM message-based session.
     *
     * @return The I2PSession of the SAM message-based session
     */
    protected I2PSession getI2PSession() {
        return session;
    }

    /**
     * SAM message-based session handler, running in its own thread
     *
     * @author human
     */
    class SAMMessageSessionHandler implements Runnable, I2PSessionListener {

        private final Object runningLock = new Object();
        private volatile boolean stillRunning = true;
                
        /**
         * Create a new SAM message-based session handler
         *
         * @param destStream Input stream containing the destination keys
	 * @param props Properties to setup the I2P session
	 * @throws I2PSessionException 
         */
        public SAMMessageSessionHandler(InputStream destStream, Properties props) throws I2PSessionException {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Instantiating new SAM message-based session handler");

            I2PClient client = I2PClientFactory.createClient();
            if (!props.containsKey("inbound.nickname") && !props.containsKey("outbound.nickname")) {
                props.setProperty("inbound.nickname", "SAM UDP Client");
                props.setProperty("outbound.nickname", "SAM UDP Client");
            }
            session = client.createSession(destStream, props);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Connecting I2P session...");
            session.connect();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("I2P session connected");

            session.setSessionListener(this);
        }

        /**
         * Stop a SAM message-based session handling thread
         *
         */
        public final void stopRunning() {
            synchronized (runningLock) {
                stillRunning = false;
                runningLock.notify();
            }
        }

        public void run() {

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("SAM message-based session handler running");

            synchronized (runningLock) {
                while (stillRunning) {
                    try {
                        runningLock.wait();
                    } catch (InterruptedException ie) {}
                }
            }

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Shutting down SAM message-based session handler");
            
            shutDown();
            
            try {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Destroying I2P session...");
                session.destroySession();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("I2P session destroyed");
            } catch (I2PSessionException e) {
                    _log.error("Error destroying I2P session", e);
            }
        }
        
        public void disconnected(I2PSession session) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("I2P session disconnected");
            stopRunning();
        }

        public void errorOccurred(I2PSession session, String message,
                                  Throwable error) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("I2P error: " + message, error);
            stopRunning();
        }
            
        public void messageAvailable(I2PSession session, int msgId, long size){
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("I2P message available (id: " + msgId
                           + "; size: " + size + ")");
            }
            try {
                byte msg[] = session.receiveMessage(msgId);
                if (msg == null)
                    return;
                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug("Content of message " + msgId + ":\n"
                               + HexDump.dump(msg));
                }
                
                messageReceived(msg);
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
