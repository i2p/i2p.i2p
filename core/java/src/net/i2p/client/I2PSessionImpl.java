package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2cp.GetDateMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.SessionId;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.I2PAppContext;

/**
 * Implementation of an I2P session running over TCP.  This class is NOT thread safe -
 * only one thread should send messages at any given time
 *
 * @author jrandom
 */
abstract class I2PSessionImpl implements I2PSession, I2CPMessageReader.I2CPMessageEventListener {
    private Log _log;
    /** who we are */
    private Destination _myDestination;
    /** private key for decryption */
    private PrivateKey _privateKey;
    /** private key for signing */
    private SigningPrivateKey _signingPrivateKey;
    /** configuration options */
    private Properties _options;
    /** this session's Id */
    private SessionId _sessionId;
    /** currently granted lease set, or null */
    private LeaseSet _leaseSet;

    /** hostname of router */
    private String _hostname;
    /** port num to router */
    private int _portNum;
    /** socket for comm */
    private Socket _socket;
    /** reader that always searches for messages */
    private I2CPMessageReader _reader;
    /** where we pipe our messages */
    private OutputStream _out;

    /** who we send events to */
    private I2PSessionListener _sessionListener;

    /** class that generates new messages */
    protected I2CPMessageProducer _producer;
    /** map of integer --> MessagePayloadMessage */
    Map _availableMessages;
    
    protected I2PClientMessageHandlerMap _handlerMap;
    
    /** used to seperate things out so we can get rid of singletons */
    protected I2PAppContext _context;

    /** MessageStatusMessage status from the most recent send that hasn't been consumed */
    private List _receivedStatus;
    private int _totalReconnectAttempts;

    /** monitor for waiting until a lease set has been granted */
    private Object _leaseSetWait = new Object();

    /** whether the session connection has already been closed (or not yet opened) */
    private boolean _closed;

    /** have we received the current date from the router yet? */
    private boolean _dateReceived;
    /** lock that we wait upon, that the SetDateMessageHandler notifies */
    private Object _dateReceivedLock = new Object();

    void dateUpdated() {
        _dateReceived = true;
        synchronized (_dateReceivedLock) {
            _dateReceivedLock.notifyAll();
        }
    }

    /**
     * Create a new session, reading the Destination, PrivateKey, and SigningPrivateKey
     * from the destKeyStream, and using the specified options to connect to the router
     *
     * @throws I2PSessionException if there is a problem loading the private keys or 
     */
    public I2PSessionImpl(I2PAppContext context, InputStream destKeyStream, Properties options) throws I2PSessionException {
        _context = context;
        _log = context.logManager().getLog(I2PSessionImpl.class);
        _handlerMap = new I2PClientMessageHandlerMap(context);
        _closed = true;
        _producer = new I2CPMessageProducer(context);
        _availableMessages = new HashMap();
        try {
            readDestination(destKeyStream);
        } catch (DataFormatException dfe) {
            throw new I2PSessionException("Error reading the destination key stream", dfe);
        } catch (IOException ioe) {
            throw new I2PSessionException("Error reading the destination key stream", ioe);
        }
        loadConfig(options);
        _sessionId = null;
        _receivedStatus = new LinkedList();
        _leaseSet = null;
        _totalReconnectAttempts = 0;
    }

    /**
     * Parse the config for anything we know about
     *
     */
    private void loadConfig(Properties options) {
        _options = new Properties();
        _options.putAll(filter(options));
        _hostname = _options.getProperty(I2PClient.PROP_TCP_HOST, "localhost");
        String portNum = _options.getProperty(I2PClient.PROP_TCP_PORT, TestServer.LISTEN_PORT + "");
        try {
            _portNum = Integer.parseInt(portNum);
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid port number specified, defaulting to "
                          + TestServer.LISTEN_PORT, nfe);
            _portNum = TestServer.LISTEN_PORT;
        }
    }

    private Properties filter(Properties options) {
        Properties rv = new Properties();
        for (Iterator iter = options.keySet().iterator(); iter.hasNext();) {
            String key = (String) iter.next();
            String val = options.getProperty(key);
            if (key.startsWith("java")) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Skipping java.* property: " + key);
            } else if (key.startsWith("user")) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Skipping user.* property: " + key);
            } else if (key.startsWith("os")) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Skipping os.* property: " + key);
            } else if (key.startsWith("sun")) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Skipping sun.* property: " + key);
            } else if (key.startsWith("file")) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Skipping file.* property: " + key);
            } else if (key.startsWith("line")) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Skipping line.* property: " + key);
            } else if ((key.length() > 255) || (val.length() > 255)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not passing on property ["
                              + key
                              + "] in the session configuration as the value is too long (max = 255): "
                              + val);
            } else {
                rv.setProperty(key, val);
            }
        }
        return rv;
    }

    void setLeaseSet(LeaseSet ls) {
        _leaseSet = ls;
        if (ls != null) {
            synchronized (_leaseSetWait) {
                _leaseSetWait.notifyAll();
            }
        }
    }

    LeaseSet getLeaseSet() {
        return _leaseSet;
    }

    /**
     * Load up the destKeyFile for our Destination, PrivateKey, and SigningPrivateKey
     *
     * @throws DataFormatException if the file is in the wrong format or keys are invalid
     * @throws IOException if there is a problem reading the file
     */
    private void readDestination(InputStream destKeyStream) throws DataFormatException, IOException {
        _myDestination = new Destination();
        _privateKey = new PrivateKey();
        _signingPrivateKey = new SigningPrivateKey();
        _myDestination.readBytes(destKeyStream);
        _privateKey.readBytes(destKeyStream);
        _signingPrivateKey.readBytes(destKeyStream);
    }

    /**
     * Connect to the router and establish a session.  This call blocks until 
     * a session is granted.
     *
     * @throws I2PSessionException if there is a configuration error or the router is
     *                             not reachable
     */
    public void connect() throws I2PSessionException {
        _closed = false;
        long startConnect = _context.clock().now();
        try {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("connect begin to " + _hostname + ":" + _portNum);
            _socket = new Socket(_hostname, _portNum);
            _out = _socket.getOutputStream();
            synchronized (_out) {
                _out.write(I2PClient.PROTOCOL_BYTE);
            }
            InputStream in = _socket.getInputStream();
            _reader = new I2CPMessageReader(in, this);
            if (_log.shouldLog(Log.DEBUG)) _log.debug("before startReading");
            _reader.startReading();

            if (_log.shouldLog(Log.DEBUG)) _log.debug("Before getDate");
            sendMessage(new GetDateMessage());
            if (_log.shouldLog(Log.DEBUG)) _log.debug("After getDate / begin waiting for a response");
            while (!_dateReceived) {
                try {
                    synchronized (_dateReceivedLock) {
                        _dateReceivedLock.wait(1000);
                    }
                } catch (InterruptedException ie) {
                }
            }
            if (_log.shouldLog(Log.DEBUG)) _log.debug("After received a SetDate response");

            if (_log.shouldLog(Log.DEBUG)) _log.debug("Before producer.connect()");
            _producer.connect(this);
            if (_log.shouldLog(Log.DEBUG)) _log.debug("After  producer.connect()");

            // wait until we have created a lease set
            while (_leaseSet == null) {
                synchronized (_leaseSetWait) {
                    try {
                        _leaseSetWait.wait(1000);
                    } catch (InterruptedException ie) {
                    }
                }
            }
            long connected = _context.clock().now();
            if (_log.shouldLog(Log.INFO))
                                         _log.info("Lease set created with inbound tunnels after "
                                                   + (connected - startConnect)
                                                   + "ms - ready to participate in the network!");
        } catch (UnknownHostException uhe) {
            _closed = true;
            throw new I2PSessionException("Invalid session configuration", uhe);
        } catch (IOException ioe) {
            _closed = true;
            throw new I2PSessionException("Problem connecting to " + _hostname + " on port " + _portNum, ioe);
        }
    }

    /**
     * Pull the unencrypted data from the message that we've already prefetched and
     * notified the user that its available.
     *
     */
    public byte[] receiveMessage(int msgId) throws I2PSessionException {
        MessagePayloadMessage msg = (MessagePayloadMessage) _availableMessages.remove(new Integer(msgId));
        if (msg == null) return null;
        return msg.getPayload().getUnencryptedData();
    }

    /**
     * Report abuse with regards to the given messageId
     */
    public void reportAbuse(int msgId, int severity) throws I2PSessionException {
        if (isClosed()) throw new I2PSessionException("Already closed");
        _producer.reportAbuse(this, msgId, severity);
    }

    /**
     * Send the data to the destination.  
     * TODO: this currently always returns true, regardless of whether the message was 
     * delivered successfully.  make this wait for at least ACCEPTED
     *
     */
    public abstract boolean sendMessage(Destination dest, byte[] payload) throws I2PSessionException;

    public abstract boolean sendMessage(Destination dest, byte[] payload, SessionKey keyUsed, 
                                        Set tagsSent) throws I2PSessionException;

    public abstract void receiveStatus(int msgId, long nonce, int status);

    protected boolean isGuaranteed() {
        String reliability = _options.getProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_GUARANTEED);
        return I2PClient.PROP_RELIABILITY_GUARANTEED.equals(reliability);
    }

    protected static final Set createNewTags(int num) {
        Set tags = new HashSet();
        for (int i = 0; i < num; i++)
            tags.add(new SessionTag(true));
        return tags;
    }

    private static volatile long __notifierId = 0;
    
    /**
     * Recieve a payload message and let the app know its available
     */
    public void addNewMessage(MessagePayloadMessage msg) {
        _availableMessages.put(new Integer(msg.getMessageId().getMessageId()), msg);
        final int id = msg.getMessageId().getMessageId();
        byte data[] = msg.getPayload().getUnencryptedData();
        if ((data == null) || (data.length <= 0)) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("addNewMessage of a message with no unencrypted data",
                           new Exception("Empty message"));
        } else {
            final long size = data.length;
            Thread notifier = new I2PThread(new Runnable() {
                public void run() {
                    if (_sessionListener != null) 
                        _sessionListener.messageAvailable(I2PSessionImpl.this, id, size);
                }
            });
            long nid = ++__notifierId;
            notifier.setName("Notifier " + nid);
            notifier.setDaemon(true);
            notifier.start();
            if (_log.shouldLog(Log.INFO))
                _log.info("Notifier " + nid + " is for session " + _sessionId + ", message " + id + "]");
        }
    }

    /**
     * Recieve notification of some I2CP message and handle it if possible
     *
     */
    public void messageReceived(I2CPMessageReader reader, I2CPMessage message) {
        I2CPMessageHandler handler = _handlerMap.getHandler(message.getType());
        if (handler == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unknown message or unhandleable message received: type = "
                          + message.getType());
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Message received of type " + message.getType()
                           + " to be handled by " + handler);
            handler.handleMessage(message, this);
        }
    }

    /** 
     * Recieve notifiation of an error reading the I2CP stream
     *
     */
    public void readError(I2CPMessageReader reader, Exception error) {
        propogateError("There was an error reading data", error);
        disconnect();
    }

    /**
     * Retrieve the destination of the session
     */
    public Destination getMyDestination() { return _myDestination; }

    /**
     * Retrieve the decryption PrivateKey 
     */
    public PrivateKey getDecryptionKey() { return _privateKey; }

    /**
     * Retrieve the signing SigningPrivateKey
     */
    public SigningPrivateKey getPrivateKey() { return _signingPrivateKey; }

    /**
     * Retrieve the helper that generates I2CP messages
     */
    I2CPMessageProducer getProducer() { return _producer; }

    /**
     * Retrieve the configuration options
     */
    Properties getOptions() { return _options; }

    /** 
     * Retrieve the session's ID
     */
    SessionId getSessionId() { return _sessionId; }
    void setSessionId(SessionId id) { _sessionId = id; }

    /** configure the listener */
    public void setSessionListener(I2PSessionListener lsnr) { _sessionListener = lsnr; }

    /** has the session been closed (or not yet connected)? */
    public boolean isClosed() { return _closed; }

    /**
     * Deliver an I2CP message to the router
     *
     * @throws I2PSessionException if the message is malformed or there is an error writing it out
     */
    void sendMessage(I2CPMessage message) throws I2PSessionException {
        if (isClosed()) throw new I2PSessionException("Already closed");

        try {
            synchronized (_out) {
                message.writeMessage(_out);
                _out.flush();
            }
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Message written out and flushed");
        } catch (I2CPMessageException ime) {
            throw new I2PSessionException("Error writing out the message", ime);
        } catch (IOException ioe) {
            throw new I2PSessionException("Error writing out the message", ioe);
        }
    }

    /**
     * Pass off the error to the listener
     */
    void propogateError(String msg, Throwable error) {
        if (_log.shouldLog(Log.ERROR)) _log.error("Error occurred: " + msg, error);
        if (_sessionListener != null) _sessionListener.errorOccurred(this, msg, error);
    }

    /**
     * Tear down the session, and do NOT reconnect
     */
    public void destroySession() {
        destroySession(true);
    }

    public void destroySession(boolean sendDisconnect) {
        if (_closed) return;
        
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Destroy the session", new Exception("DestroySession()"));
        if (sendDisconnect) {
            try {
                _producer.disconnect(this);
            } catch (I2PSessionException ipe) {
                propogateError("Error destroying the session", ipe);
            }
        }
        _closed = true;
        closeSocket();
        if (_sessionListener != null) _sessionListener.disconnected(this);
    }

    /**
     * Close the socket carefully
     *
     */
    private void closeSocket() {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Closing the socket", new Exception("closeSocket"));
        _closed = true;
        if (_reader != null) _reader.stopReading();
        _reader = null;

        if (_socket != null) {
            try {
                _socket.close();
            } catch (IOException ioe) {
                propogateError("Caught an IO error closing the socket.  ignored", ioe);
            } finally {
                _socket = null; // so when propogateError calls closeSocket, it doesnt loop
            }
        }
    }

    /**
     * Recieve notification that the I2CP connection was disconnected
     */
    public void disconnected(I2CPMessageReader reader) {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Disconnected", new Exception("Disconnected"));
        disconnect();
    }

    protected void disconnect() {
        if (_closed) return;
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Disconnect() called", new Exception("Disconnect"));
        if (shouldReconnect()) {
            if (reconnect()) {
                if (_log.shouldLog(Log.INFO)) _log.info("I2CP reconnection successful");
                return;
            } else {
                if (_log.shouldLog(Log.ERROR)) _log.error("I2CP reconnection failed");
            }
        }

        if (_log.shouldLog(Log.ERROR))
            _log.error("Disconned from the router, and not trying to reconnect further.  I hope you're not hoping anything else will happen");
        if (_sessionListener != null) _sessionListener.disconnected(this);

        _closed = true;
        closeSocket();
    }

    private final static int MAX_RECONNECT_ATTEMPTS = 1;
    private final static int MAX_TOTAL_RECONNECT_ATTEMPTS = 3;

    protected boolean shouldReconnect() {
        return true;
    }

    protected boolean reconnect() {
        closeSocket();
        if (_totalReconnectAttempts < MAX_TOTAL_RECONNECT_ATTEMPTS) {
            _totalReconnectAttempts++;
        } else {
            if (_log.shouldLog(Log.CRIT))
                _log.log(Log.CRIT, "Max number of reconnects exceeded ["
                                   + _totalReconnectAttempts + "], we give up!");
            return false;
        }
        if (_log.shouldLog(Log.INFO)) _log.info("Reconnecting...");
        for (int i = 0; i < MAX_RECONNECT_ATTEMPTS; i++) {
            try {
                connect();
                return true;
            } catch (I2PSessionException ise) {
                if (_log.shouldLog(Log.ERROR)) _log.error("Error reconnecting on attempt " + i, ise);
            }
        }
        return false;
    }
}