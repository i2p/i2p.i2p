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
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.SendMessageOptions;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
//import net.i2p.util.HexDump;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Base abstract class for SAM message-based sessions.
 *
 * @author human
 */
abstract class SAMMessageSession implements SAMMessageSess {

    protected final Log _log;
    private final I2PSession session;
    protected final boolean _isOwnSession;
    private final SAMMessageSessionHandler handler;
    private final int listenProtocol;
    private final int listenPort;

    /**
     * Initialize a new SAM message-based session.
     *
     * @param dest Base64-encoded destination and private keys,
     *             and optional offline signature section (same format as PrivateKeyFile)
     * @param props Properties to setup the I2P session
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    protected SAMMessageSession(String dest, Properties props) throws IOException, DataFormatException, I2PSessionException {
        this(new ByteArrayInputStream(Base64.decode(dest)), props);
    }

    /**
     * Initialize a new SAM message-based session.
     *
     * @param destStream Input stream containing the binary destination and private keys,
     *                   and optional offline signature section (same format as PrivateKeyFile)
     * @param props Properties to setup the I2P session
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     */
    protected SAMMessageSession(InputStream destStream, Properties props) throws IOException, DataFormatException, I2PSessionException {
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Initializing SAM message-based session");
        listenProtocol = I2PSession.PROTO_ANY;
        listenPort = I2PSession.PORT_ANY;
        _isOwnSession = true;
        handler = new SAMMessageSessionHandler(destStream, props);
        session = handler.getSession();
    }

    /**
     * Initialize a new SAM message-based session using an existing I2PSession.
     *
     * @throws IOException
     * @throws DataFormatException
     * @throws I2PSessionException 
     * @since 0.9.25
     */
    protected SAMMessageSession(I2PSession sess, int listenProtocol, int listenPort)
                            throws IOException, DataFormatException, I2PSessionException {
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Initializing SAM message-based session");
        this.listenProtocol = listenProtocol;
        this.listenPort = listenPort;
        _isOwnSession = false;
        session = sess;
        handler = new SAMMessageSessionHandler(session);
    }

    /*
     * @since 0.9.25
     */
    public void start() {
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
     * @since 0.9.25
     */
    public int getListenProtocol() {
        return listenProtocol;
    }

    /**
     * @since 0.9.25
     */
    public int getListenPort() {
        return listenPort;
    }

    /**
     * Send bytes through a SAM message-based session.
     *
     * @param dest Destination
     * @param data Bytes to be sent
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException on unknown / bad dest
     * @throws I2PSessionException on serious error, probably session closed
     */
    public abstract boolean sendBytes(String dest, byte[] data, int proto,
                                      int fromPort, int toPort) throws DataFormatException, I2PSessionException;

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
     * @throws I2PSessionException on serious error, probably session closed
     */
    protected boolean sendBytesThroughMessageSession(String dest, byte[] data,
                                        int proto, int fromPort, int toPort)
                                        throws DataFormatException, I2PSessionException {
	Destination d = SAMUtils.getDest(dest);

	if (_log.shouldLog(Log.DEBUG)) {
	    _log.debug("Sending " + data.length + " bytes to " + dest);
	}

	return session.sendMessage(d, data, proto, fromPort, toPort);
    }

    /**
     * Actually send bytes through the SAM message-based session I2PSession,
     * using per-message extended options.
     * For efficiency, use the method without all the extra options if they are all defaults.
     *
     * @param dest Destination
     * @param data Bytes to be sent
     * @param proto I2CP protocol
     * @param fromPort I2CP from port
     * @param toPort I2CP to port
     * @param sendLeaseSet true is the usual setting and the I2CP default
     * @param sendTags 0 to leave as default
     * @param tagThreshold 0 to leave as default
     * @param expiration SECONDS from now, NOT absolute time, 0 to leave as default
     *
     * @return True if the data was sent, false otherwise
     * @throws DataFormatException on unknown / bad dest
     * @throws I2PSessionException on serious error, probably session closed
     * @since 0.9.24
     */
    protected boolean sendBytesThroughMessageSession(String dest, byte[] data,
                                        int proto, int fromPort, int toPort,
                                        boolean sendLeaseSet, int sendTags,
                                        int tagThreshold, int expiration)
                                        throws DataFormatException, I2PSessionException {
	Destination d = SAMUtils.getDest(dest);

	if (_log.shouldLog(Log.DEBUG)) {
	    _log.debug("Sending " + data.length + " bytes to " + dest);
	}
	SendMessageOptions opts = new SendMessageOptions();
	if (!sendLeaseSet)
	    opts.setSendLeaseSet(false);
	if (sendTags > 0)
	    opts.setTagsToSend(sendTags);
	if (tagThreshold > 0)
	    opts.setTagThreshold(tagThreshold);
	if (expiration > 0)
	    opts.setDate(I2PAppContext.getGlobalContext().clock().now() + (expiration * 1000));

	return session.sendMessage(d, data, 0, data.length, proto, fromPort, toPort, opts);
    }

    /**
     * Close a SAM message-based session.
     */
    public void close() {
        handler.stopRunning();
    }

    /**
     * Handle a new received message
     * @param msg Message payload
     */
    protected abstract void messageReceived(byte[] msg, int proto, int fromPort, int toPort);
    
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
    private class SAMMessageSessionHandler implements Runnable, I2PSessionMuxedListener {

        private final I2PSession _session;
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
            String name = props.getProperty("inbound.nickname");
            if (name == null || name.trim().isEmpty()) {
                name = "SAM UDP Client";
                props.setProperty("inbound.nickname", name);
            }
            String name2 = props.getProperty("outbound.nickname");
            if (name2 == null || name2.trim().isEmpty())
                props.setProperty("outbound.nickname", name);
            _session = client.createSession(destStream, props);

            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Connecting I2P session...");
            _session.connect();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("I2P session connected");

            _session.addMuxedSessionListener(this, listenProtocol, listenPort);
        }
                
        /**
         * Create a new SAM message-based session handler on an existing I2PSession
         *
         * @since 0.9.25
         */
        public SAMMessageSessionHandler(I2PSession sess) throws I2PSessionException {
            _session = sess;
            _session.addMuxedSessionListener(this, listenProtocol, listenPort);
        }

        /**
         * The session.
         * @since 0.9.25
         */
        public final I2PSession getSession() {
            return _session;
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
            session.removeListener(listenProtocol, listenPort);
            
            if (_isOwnSession) {
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
            
        public void messageAvailable(I2PSession session, int msgId, long size) {
            messageAvailable(session, msgId, size, I2PSession.PROTO_UNSPECIFIED,
                             I2PSession.PORT_UNSPECIFIED, I2PSession.PORT_UNSPECIFIED);
        }

        /** @since 0.9.24 */
        public void messageAvailable(I2PSession session, int msgId, long size,
                                     int proto, int fromPort, int toPort) {

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("I2P message available (id: " + msgId
                           + "; size: " + size + ")");
            }
            try {
                byte msg[] = session.receiveMessage(msgId);
                if (msg == null)
                    return;
                //if (_log.shouldLog(Log.DEBUG)) {
                //    _log.debug("Content of message " + msgId + ":\n"
                //               + HexDump.dump(msg));
                //}
                
                messageReceived(msg, proto, fromPort, toPort);
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
