package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2cp.DestLookupMessage;
import net.i2p.data.i2cp.GetBandwidthLimitsMessage;
import net.i2p.data.i2cp.GetDateMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.SessionId;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.internal.InternalClientManager;
import net.i2p.internal.QueuedI2CPMessageReader;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

/**
 * Implementation of an I2P session running over TCP.  This class is NOT thread safe -
 * only one thread should send messages at any given time
 *
 * @author jrandom
 */
abstract class I2PSessionImpl implements I2PSession, I2CPMessageReader.I2CPMessageEventListener {
    protected Log _log;
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

    /** hostname of router - will be null if in RouterContext */
    protected String _hostname;
    /** port num to router - will be 0 if in RouterContext */
    protected int _portNum;
    /** socket for comm */
    protected Socket _socket;
    /** reader that always searches for messages */
    protected I2CPMessageReader _reader;
    /** writer message queue */
    protected ClientWriterRunner _writer;
    /** where we pipe our messages */
    protected /* FIXME final FIXME */OutputStream _out;

    /**
     *  Used for internal connections to the router.
     *  If this is set, _socket, _writer, and _out will be null.
     *  @since 0.8.3
     */
    protected I2CPMessageQueue _queue;

    /** who we send events to */
    protected I2PSessionListener _sessionListener;

    /** class that generates new messages */
    protected I2CPMessageProducer _producer;
    /** map of Long --> MessagePayloadMessage */
    protected Map<Long, MessagePayloadMessage> _availableMessages;

    /** hashes of lookups we are waiting for */
    protected final LinkedBlockingQueue<LookupWaiter> _pendingLookups = new LinkedBlockingQueue();
    protected final Object _bwReceivedLock = new Object();
    protected int[] _bwLimits;
    
    protected I2PClientMessageHandlerMap _handlerMap;
    
    /** used to seperate things out so we can get rid of singletons */
    protected I2PAppContext _context;

    /** monitor for waiting until a lease set has been granted */
    private final Object _leaseSetWait = new Object();

    /** whether the session connection has already been closed (or not yet opened) */
    protected boolean _closed;

    /** whether the session connection is in the process of being closed */
    protected boolean _closing;

    /** have we received the current date from the router yet? */
    private boolean _dateReceived;
    /** lock that we wait upon, that the SetDateMessageHandler notifies */
    private final Object _dateReceivedLock = new Object();

    /** whether the session connection is in the process of being opened */
    protected boolean _opening;

    /** monitor for waiting until opened */
    private final Object _openingWait = new Object();
    /** 
     * thread that we tell when new messages are available who then tells us 
     * to fetch them.  The point of this is so that the fetch doesn't block the
     * reading of other messages (in turn, potentially leading to deadlock)
     *
     */
    protected AvailabilityNotifier _availabilityNotifier;

    private long _lastActivity;
    private boolean _isReduced;

    /**
     *  @since 0.8.9
     */
    private static final LookupCache _lookupCache = new LookupCache(16);

    /** SSL interface (only) @since 0.8.3 */
    protected static final String PROP_ENABLE_SSL = "i2cp.SSL";

    void dateUpdated() {
        _dateReceived = true;
        synchronized (_dateReceivedLock) {
            _dateReceivedLock.notifyAll();
        }
    }

    public static final int LISTEN_PORT = 7654;
    
    /** for extension */
    public I2PSessionImpl() {}

    /**
     * Create a new session, reading the Destination, PrivateKey, and SigningPrivateKey
     * from the destKeyStream, and using the specified options to connect to the router
     *
     * @param destKeyStream stream containing the private key data,
     *                             format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     * @throws I2PSessionException if there is a problem loading the private keys or 
     */
    public I2PSessionImpl(I2PAppContext context, InputStream destKeyStream, Properties options) throws I2PSessionException {
        _context = context;
        _log = context.logManager().getLog(I2PSessionImpl.class);
        _handlerMap = new I2PClientMessageHandlerMap(context);
        _closed = true;
        _opening = false;
        _closing = false;
        _producer = new I2CPMessageProducer(context);
        _availabilityNotifier = new AvailabilityNotifier();
        _availableMessages = new ConcurrentHashMap();
        try {
            readDestination(destKeyStream);
        } catch (DataFormatException dfe) {
            throw new I2PSessionException("Error reading the destination key stream", dfe);
        } catch (IOException ioe) {
            throw new I2PSessionException("Error reading the destination key stream", ioe);
        }
        if (options == null)
            options = System.getProperties();
        loadConfig(options);
        _sessionId = null;
        _leaseSet = null;
    }

    /**
     * Parse the config for anything we know about.
     * Also fill in the authorization properties if missing.
     */
    protected void loadConfig(Properties options) {
        _options = new Properties();
        _options.putAll(filter(options));
        if (_context.isRouterContext()) {
            // just for logging
            _hostname = "[internal connection]";
        } else {
            _hostname = _options.getProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
            String portNum = _options.getProperty(I2PClient.PROP_TCP_PORT, LISTEN_PORT + "");
            try {
                _portNum = Integer.parseInt(portNum);
            } catch (NumberFormatException nfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getPrefix() + "Invalid port number specified, defaulting to "
                              + LISTEN_PORT, nfe);
                _portNum = LISTEN_PORT;
            }
        }

        // auto-add auth if required, not set in the options, and we are not in the same JVM
        if ((!_context.isRouterContext()) &&
            Boolean.valueOf(_context.getProperty("i2cp.auth")).booleanValue() &&
            ((!options.containsKey("i2cp.username")) || (!options.containsKey("i2cp.password")))) {
            String configUser = _context.getProperty("i2cp.username");
            String configPW = _context.getProperty("i2cp.password");
            if (configUser != null && configPW != null) {
                _options.setProperty("i2cp.username", configUser);
                _options.setProperty("i2cp.password", configPW);
            }
        }
    }

    /** save some memory, don't pass along the pointless properties */
    private Properties filter(Properties options) {
        Properties rv = new Properties();
        for (Iterator iter = options.keySet().iterator(); iter.hasNext();) {
            String key = (String) iter.next();
            if (key.startsWith("java.") ||
                key.startsWith("user.") ||
                key.startsWith("os.") ||
                key.startsWith("sun.") ||
                key.startsWith("file.") ||
                key.equals("line.separator") ||
                key.equals("path.separator") ||
                key.equals("prng.buffers") ||
                key.equals("router.trustedUpdateKeys") ||
                key.startsWith("router.update") ||
                key.startsWith("routerconsole.") ||
                key.startsWith("time.") ||
                key.startsWith("stat.") ||
                key.startsWith("gnu.") ||  // gnu JVM
                key.startsWith("net.i2p.router.web.") ||  // console nonces
                key.startsWith("wrapper.")) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Skipping property: " + key);
                continue;
            }
            String val = options.getProperty(key);
            if ((key.length() > 255) || (val.length() > 255)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getPrefix() + "Not passing on property ["
                              + key
                              + "] in the session configuration as the value is too long (max = 255): "
                              + val);
            } else {
                rv.setProperty(key, val);
            }
        }
        return rv;
    }

    /**
     * Update the tunnel and bandwidth settings
     * @since 0.8.4
     */
    public void updateOptions(Properties options) {
        _options.putAll(filter(options));
        _producer.updateBandwidth(this);
        try {
            _producer.updateTunnels(this, 0);
        } catch (I2PSessionException ise) {}
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

    void setOpening(boolean ls) {
        _opening = ls;
        synchronized (_openingWait) {
            _openingWait.notifyAll();
        }
    }

    boolean getOpening() {
        return _opening;
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
        setOpening(true);
        _closed = false;
        _availabilityNotifier.stopNotifying();
        
        if ( (_options != null) && 
             (I2PClient.PROP_RELIABILITY_GUARANTEED.equals(_options.getProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_BEST_EFFORT))) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("I2CP guaranteed delivery mode has been removed, using best effort.");
        }
            
        long startConnect = _context.clock().now();
        try {
            // If we are in the router JVM, connect using the interal queue
            if (_context.isRouterContext()) {
                // _socket, _out, and _writer remain null
                InternalClientManager mgr = _context.internalClientManager();
                if (mgr == null)
                    throw new I2PSessionException("Router is not ready for connections");
                // the following may throw an I2PSessionException
                _queue = mgr.connect();
                _reader = new QueuedI2CPMessageReader(_queue, this);
            } else {
                if (Boolean.valueOf(_options.getProperty(PROP_ENABLE_SSL)).booleanValue())
                    _socket = I2CPSSLSocketFactory.createSocket(_context, _hostname, _portNum);
                else
                    _socket = new Socket(_hostname, _portNum);
                // _socket.setSoTimeout(1000000); // Uhmmm we could really-really use a real timeout, and handle it.
                _out = _socket.getOutputStream();
                _out.write(I2PClient.PROTOCOL_BYTE);
                _out.flush();
                _writer = new ClientWriterRunner(_out, this);
                InputStream in = _socket.getInputStream();
                _reader = new I2CPMessageReader(in, this);
            }
            Thread notifier = new I2PAppThread(_availabilityNotifier, "ClientNotifier " + getPrefix(), true);
            notifier.start();
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "before startReading");
            _reader.startReading();
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Before getDate");
            sendMessage(new GetDateMessage(CoreVersion.VERSION));
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "After getDate / begin waiting for a response");
            int waitcount = 0;
            while (!_dateReceived) {
                if (waitcount++ > 30) {
                    closeSocket();
                    throw new IOException("No handshake received from the router");
                }
                try {
                    synchronized (_dateReceivedLock) {
                        _dateReceivedLock.wait(1000);
                    }
                } catch (InterruptedException ie) { // nop
                }
            }
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "After received a SetDate response");

            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Before producer.connect()");
            _producer.connect(this);
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "After  producer.connect()");

            // wait until we have created a lease set
            waitcount = 0;
            while (_leaseSet == null) {
                if (waitcount++ > 5*60) {
                    try {
                        _producer.disconnect(this);
                    } catch (I2PSessionException ipe) {}
                    closeSocket();
                    throw new IOException("No tunnels built after waiting 5 minutes. Your network connection may be down, or there is severe network congestion.");
                }
                synchronized (_leaseSetWait) {
                    try {
                        _leaseSetWait.wait(1000);
                    } catch (InterruptedException ie) { // nop
                    }
                }
            }
            long connected = _context.clock().now();
            if (_log.shouldLog(Log.INFO))
                 _log.info(getPrefix() + "Lease set created with inbound tunnels after "
                           + (connected - startConnect)
                           + "ms - ready to participate in the network!");
            startIdleMonitor();
             setOpening(false);
        } catch (UnknownHostException uhe) {
            _closed = true;
            setOpening(false);
            throw new I2PSessionException(getPrefix() + "Cannot connect to the router on " + _hostname + ':' + _portNum, uhe);
        } catch (IOException ioe) {
            _closed = true;
            setOpening(false);
            throw new I2PSessionException(getPrefix() + "Cannot connect to the router on " + _hostname + ':' + _portNum, ioe);
        }
    }

    /**
     * Pull the unencrypted data from the message that we've already prefetched and
     * notified the user that its available.
     *
     */
    public byte[] receiveMessage(int msgId) throws I2PSessionException {
        MessagePayloadMessage msg = _availableMessages.remove(Long.valueOf(msgId));
        if (msg == null) {
            _log.error("Receive message " + msgId + " had no matches");
            return null;
        }
        updateActivity();
        return msg.getPayload().getUnencryptedData();
    }

    /**
     * Report abuse with regards to the given messageId
     */
    public void reportAbuse(int msgId, int severity) throws I2PSessionException {
        if (isClosed()) throw new I2PSessionException(getPrefix() + "Already closed");
        _producer.reportAbuse(this, msgId, severity);
    }

    public abstract void receiveStatus(int msgId, long nonce, int status);

/****** no end-to-end crypto
    protected static final Set createNewTags(int num) {
        Set tags = new HashSet();
        for (int i = 0; i < num; i++)
            tags.add(new SessionTag(true));
        return tags;
    }
*******/

    /**
     * Recieve a payload message and let the app know its available
     */
    public void addNewMessage(MessagePayloadMessage msg) {
        Long mid = Long.valueOf(msg.getMessageId());
        _availableMessages.put(mid, msg);
        long id = msg.getMessageId();
        byte data[] = msg.getPayload().getUnencryptedData();
        if ((data == null) || (data.length <= 0)) {
            if (_log.shouldLog(Log.CRIT))
                _log.log(Log.CRIT, getPrefix() + "addNewMessage of a message with no unencrypted data",
                           new Exception("Empty message"));
        } else {
            int size = data.length;
            _availabilityNotifier.available(id, size);
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "Notified availability for session " + _sessionId + ", message " + id);
        }
        SimpleScheduler.getInstance().addEvent(new VerifyUsage(mid), 30*1000);
    }
    protected class VerifyUsage implements SimpleTimer.TimedEvent {
        private Long _msgId;
        public VerifyUsage(Long id) { _msgId = id; }
        
        public void timeReached() {
            MessagePayloadMessage removed = _availableMessages.remove(_msgId);
            if (removed != null && !isClosed())
                _log.error("Message NOT removed!  id=" + _msgId + ": " + removed);
        }
    }

    /**
     *  This notifies the client of payload messages.
     *  Needs work.
     */
    protected class AvailabilityNotifier implements Runnable {
        private List _pendingIds;
        private List _pendingSizes;
        private boolean _alive;
 
        public AvailabilityNotifier() {
            _pendingIds = new ArrayList(2);
            _pendingSizes = new ArrayList(2);
        }
        
        public void stopNotifying() { 
            _alive = false; 
            synchronized (AvailabilityNotifier.this) {
                AvailabilityNotifier.this.notifyAll();
            }
        }
        
        public void available(long msgId, int size) {
            synchronized (AvailabilityNotifier.this) {
                _pendingIds.add(Long.valueOf(msgId));
                _pendingSizes.add(Integer.valueOf(size));
                AvailabilityNotifier.this.notifyAll();
            }
        }
        public void run() {
            _alive = true;
            while (_alive) {
                Long msgId = null;
                Integer size = null;
                synchronized (AvailabilityNotifier.this) {
                    if (_pendingIds.isEmpty()) {
                        try {
                            AvailabilityNotifier.this.wait();
                        } catch (InterruptedException ie) { // nop
                        }
                    }
                    if (!_pendingIds.isEmpty()) {
                        msgId = (Long)_pendingIds.remove(0);
                        size = (Integer)_pendingSizes.remove(0);
                    }
                }
                if ( (msgId != null) && (size != null) ) {
                    if (_sessionListener != null) {
                        try {
                            long before = System.currentTimeMillis();
                            _sessionListener.messageAvailable(I2PSessionImpl.this, msgId.intValue(), size.intValue());
                            long duration = System.currentTimeMillis() - before;
                            if ((duration > 100) && _log.shouldLog(Log.INFO)) 
                                _log.info("Message availability notification for " + msgId.intValue() + " took " 
                                           + duration + " to " + _sessionListener);
                        } catch (Exception e) {
                            _log.log(Log.CRIT, "Error notifying app of message availability", e);
                        }
                    } else {
                        _log.log(Log.CRIT, "Unable to notify an app that " + msgId + " of size " + size + " is available!");
                    }
                }
            }
        }
    }
    
    /**
     * The I2CPMessageEventListener callback.
     * Recieve notification of some I2CP message and handle it if possible.
     * @param reader unused
     */
    public void messageReceived(I2CPMessageReader reader, I2CPMessage message) {
        I2CPMessageHandler handler = _handlerMap.getHandler(message.getType());
        if (handler == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getPrefix() + "Unknown message or unhandleable message received: type = "
                          + message.getType());
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix() + "Message received of type " + message.getType()
                           + " to be handled by " + handler);
            handler.handleMessage(message, this);
        }
    }

    /** 
     * The I2CPMessageEventListener callback.
     * Recieve notifiation of an error reading the I2CP stream.
     * @param reader unused
     * @param error non-null
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
     * @return non-null, if insantiated with null options, this will be the System properties.
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
        if (isClosed())
            throw new I2PSessionException("Already closed");
        else if (_queue != null)
            _queue.offer(message);  // internal
        else if (_writer == null)
            throw new I2PSessionException("Already closed");
        else
            _writer.addMessage(message);
    }

    /**
     * Pass off the error to the listener
     * Misspelled, oh well.
     * @param error non-null
     */
    void propogateError(String msg, Throwable error) {
        // Only log as WARN if the router went away
        int level;
        String msgpfx;
        if (error instanceof EOFException) {
            level = Log.WARN;
            msgpfx = "Router closed connection: ";
        } else {
            level = Log.ERROR;
            msgpfx = "Error occurred communicating with router: ";
        }

        if (_log.shouldLog(level)) 
            _log.log(level, getPrefix() + msgpfx + msg, error);
        if (_sessionListener != null) _sessionListener.errorOccurred(this, msg, error);
    }

    /**
     * Tear down the session, and do NOT reconnect.
     *
     * Blocks if session has not been fully started.
     */
    public void destroySession() {
        destroySession(true);
    }

    /**
     * Tear down the session, and do NOT reconnect.
     * 
     * Blocks if session has not been fully started.
     */
    public void destroySession(boolean sendDisconnect) {
        while (_opening) {
            synchronized (_openingWait) {
                try {
                    _openingWait.wait(1000);
                } catch (InterruptedException ie) { // nop
                }
            }
        }
        if (_closed) return;
        
        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "Destroy the session", new Exception("DestroySession()"));
        _closing = true;   // we use this to prevent a race
        if (sendDisconnect && _producer != null) {    // only null if overridden by I2PSimpleSession
            try {
                _producer.disconnect(this);
            } catch (I2PSessionException ipe) {
                //propogateError("Error destroying the session", ipe);
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error destroying the session", ipe);
            }
        }
        // SimpleSession does not initialize
        if (_availabilityNotifier != null)
            _availabilityNotifier.stopNotifying();
        _closed = true;
        _closing = false;
        closeSocket();
        if (_sessionListener != null) _sessionListener.disconnected(this);
    }

    /**
     * Close the socket carefully
     *
     */
    private void closeSocket() {
        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "Closing the socket", new Exception("closeSocket"));
        _closed = true;
        if (_reader != null) {
            _reader.stopReading();
            _reader = null;
        }
        if (_queue != null) {
            // internal
            _queue.close();
        }
        if (_writer != null) {
            _writer.stopWriting();
            _writer = null;
        }

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
     * The I2CPMessageEventListener callback.
     * Recieve notification that the I2CP connection was disconnected.
     * @param reader unused
     */
    public void disconnected(I2CPMessageReader reader) {
        if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Disconnected", new Exception("Disconnected"));
        disconnect();
    }

    protected void disconnect() {
        if (_closed || _closing) return;
        if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Disconnect() called", new Exception("Disconnect"));
        if (shouldReconnect()) {
            if (reconnect()) {
                if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "I2CP reconnection successful");
                return;
            }
            if (_log.shouldLog(Log.ERROR)) _log.error(getPrefix() + "I2CP reconnection failed");
        }

        if (_log.shouldLog(Log.ERROR))
            _log.error(getPrefix() + "Disconned from the router, and not trying to reconnect further.  I hope you're not hoping anything else will happen");
        if (_sessionListener != null) _sessionListener.disconnected(this);

        _closed = true;
        closeSocket();
    }

    private final static int MAX_RECONNECT_DELAY = 320*1000;
    private final static int BASE_RECONNECT_DELAY = 10*1000;

    protected boolean shouldReconnect() {
        return true;
    }

    protected boolean reconnect() {
        closeSocket();
        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "Reconnecting...");
        int i = 0;
        while (true) {
            long delay = BASE_RECONNECT_DELAY << i;
            i++;
            if ( (delay > MAX_RECONNECT_DELAY) || (delay <= 0) )
                delay = MAX_RECONNECT_DELAY;
            try { Thread.sleep(delay); } catch (InterruptedException ie) {}
            
            try {
                connect();
                if (_log.shouldLog(Log.INFO)) 
                    _log.info(getPrefix() + "Reconnected on attempt " + i);
                return true;
            } catch (I2PSessionException ise) {
                if (_log.shouldLog(Log.ERROR)) 
                    _log.error(getPrefix() + "Error reconnecting on attempt " + i, ise);
            }
        }
    }
    
    /**
     * try hard to make a decent identifier as this will appear in error logs
     */
    protected String getPrefix() {
        StringBuilder buf = new StringBuilder();
        buf.append('[');
        String s = _options.getProperty("inbound.nickname");
        if (s != null)
            buf.append(s);
        else
            buf.append(getClass().getSimpleName());
        if (_sessionId != null)
            buf.append(" #").append(_sessionId.getSessionId());
        buf.append("]: ");
        return buf.toString();
    }

    /** called by the message handler */
    void destReceived(Destination d) {
        Hash h = d.calculateHash();
        synchronized (_lookupCache) {
            _lookupCache.put(h, d);
        }
        for (LookupWaiter w : _pendingLookups) {
            if (w.hash.equals(h)) {
                w.destination = d;
                synchronized (w) {
                    w.notifyAll();
                }
            }
        }
    }

    /** called by the message handler */
    void destLookupFailed(Hash h) {
        for (LookupWaiter w : _pendingLookups) {
            if (w.hash.equals(h)) {
                synchronized (w) {
                    w.notifyAll();
                }
            }
        }
    }

    /** called by the message handler */
    void bwReceived(int[] i) {
        _bwLimits = i;
        synchronized (_bwReceivedLock) {
            _bwReceivedLock.notifyAll();
        }
    }

    /**
     *  Simple object to wait for lookup replies
     *  @since 0.8.3
     */
    private static class LookupWaiter {
        /** the request */
        public final Hash hash;
        /** the reply */
        public Destination destination;

        public LookupWaiter(Hash h) {
            this.hash = h;
        }
    }

    /**
     *  Blocking. Waits a max of 10 seconds by default.
     *  See lookupDest with maxWait parameter to change.
     *  Implemented in 0.8.3 in I2PSessionImpl;
     *  previously was available only in I2PSimpleSession.
     *  Multiple outstanding lookups are now allowed.
     *  @return null on failure
     */
    public Destination lookupDest(Hash h) throws I2PSessionException {
        return lookupDest(h, 10*1000);
    }

    /**
     *  Blocking.
     *  @param maxWait ms
     *  @since 0.8.3
     *  @return null on failure
     */
    public Destination lookupDest(Hash h, long maxWait) throws I2PSessionException {
        synchronized (_lookupCache) {
            Destination rv = _lookupCache.get(h);
            if (rv != null)
                return rv;
        }
        if (_closed)
            return null;
        LookupWaiter waiter = new LookupWaiter(h);
        _pendingLookups.offer(waiter);
        try {
            sendMessage(new DestLookupMessage(h));
            try {
                synchronized (waiter) {
                    waiter.wait(maxWait);
                }
            } catch (InterruptedException ie) {}
        } finally {
            _pendingLookups.remove(waiter);
        }
        return waiter.destination;
    }

    /**
     *  Blocking. Waits a max of 5 seconds.
     *  But shouldn't take long.
     *  Implemented in 0.8.3 in I2PSessionImpl;
     *  previously was available only in I2PSimpleSession.
     *  Multiple outstanding lookups are now allowed.
     *  @return null on failure
     */
    public int[] bandwidthLimits() throws I2PSessionException {
        if (_closed)
            return null;
        sendMessage(new GetBandwidthLimitsMessage());
        try {
            synchronized (_bwReceivedLock) {
                _bwReceivedLock.wait(5*1000);
            }
        } catch (InterruptedException ie) {}
        return _bwLimits;
    }

    protected void updateActivity() {
        _lastActivity = _context.clock().now();
        if (_isReduced) {
            _isReduced = false;
            if (_log.shouldLog(Log.WARN)) 
                _log.warn(getPrefix() + "Restoring original tunnel quantity");
            try {
                _producer.updateTunnels(this, 0);
            } catch (I2PSessionException ise) {
                _log.error(getPrefix() + "bork restore from reduced");
            }
        }
    }

    public long lastActivity() {
        return _lastActivity;
    }

    public void setReduced() {
        _isReduced = true;
    }

    private void startIdleMonitor() {
        _isReduced = false;
        boolean reduce = Boolean.valueOf(_options.getProperty("i2cp.reduceOnIdle")).booleanValue();
        boolean close = Boolean.valueOf(_options.getProperty("i2cp.closeOnIdle")).booleanValue();
        if (reduce || close) {
            updateActivity();
            SimpleScheduler.getInstance().addEvent(new SessionIdleTimer(_context, this, reduce, close), SessionIdleTimer.MINIMUM_TIME);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(32);
        buf.append("Session: ");
        if (_myDestination != null)
            buf.append(_myDestination.calculateHash().toBase64().substring(0, 4));
        else
            buf.append("[null dest]");
        buf.append(getPrefix());
        return buf.toString();
    }

    /**
     *  @since 0.8.9
     */
    private static class LookupCache extends LinkedHashMap<Hash, Destination> {
        private final int _max;

        public LookupCache(int max) {
            super(max, 0.75f, true);
            _max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Hash, Destination> eldest) {
            return size() > _max;
        }
    }
}
