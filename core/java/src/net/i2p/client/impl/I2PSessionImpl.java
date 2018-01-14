package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.Base32;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2cp.DestLookupMessage;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.data.i2cp.GetBandwidthLimitsMessage;
import net.i2p.data.i2cp.GetDateMessage;
import net.i2p.data.i2cp.HostLookupMessage;
import net.i2p.data.i2cp.HostReplyMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.internal.InternalClientManager;
import net.i2p.internal.QueuedI2CPMessageReader;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Implementation of an I2P session running over TCP.  This class is NOT thread safe -
 * only one thread should send messages at any given time
 *
 * Public only for clearCache().
 * Except for methods defined in I2PSession and I2CPMessageEventListener,
 * not maintained as a public API, not for external use.
 * Use I2PClientFactory to get an I2PClient and then createSession().
 *
 * @author jrandom
 */
public abstract class I2PSessionImpl implements I2PSession, I2CPMessageReader.I2CPMessageEventListener {
    protected final Log _log;
    /** who we are */
    private final Destination _myDestination;
    /** private key for decryption */
    private final PrivateKey _privateKey;
    /** private key for signing */
    private   /* final */   SigningPrivateKey _signingPrivateKey;
    /** configuration options */
    private final Properties _options;
    /** this session's Id */
    private SessionId _sessionId;
    /** currently granted lease set, or null */
    protected volatile LeaseSet _leaseSet;

    // subsession stuff
    // registered subsessions
    private final List<SubSession> _subsessions;
    // established subsessions
    private final ConcurrentHashMap<SessionId, SubSession> _subsessionMap;
    private final Object _subsessionLock = new Object();
    private static final String MIN_SUBSESSION_VERSION = "0.9.21";
    private volatile boolean _routerSupportsSubsessions;

    /** hostname of router - will be null if in RouterContext */
    protected final String _hostname;
    /** port num to router - will be 0 if in RouterContext */
    protected final int _portNum;
    /** socket for comm */
    protected Socket _socket;
    /** reader that always searches for messages */
    protected I2CPMessageReader _reader;
    /** writer message queue */
    protected ClientWriterRunner _writer;

    /**
     *  Used for internal connections to the router.
     *  If this is set, _socket and _writer will be null.
     *  @since 0.8.3
     */
    protected I2CPMessageQueue _queue;

    /** who we send events to */
    protected I2PSessionListener _sessionListener;

    /** class that generates new messages */
    protected final I2CPMessageProducer _producer;
    /** map of Long --&gt; MessagePayloadMessage */
    protected Map<Long, MessagePayloadMessage> _availableMessages;

    /** hashes of lookups we are waiting for */
    protected final LinkedBlockingQueue<LookupWaiter> _pendingLookups = new LinkedBlockingQueue<LookupWaiter>();
    private final AtomicInteger _lookupID = new AtomicInteger();
    protected final Object _bwReceivedLock = new Object();
    protected volatile int[] _bwLimits;
    
    protected final I2PClientMessageHandlerMap _handlerMap;
    
    /** used to separate things out so we can get rid of singletons */
    protected final I2PAppContext _context;

    /** monitor for waiting until a lease set has been granted */
    protected final Object _leaseSetWait = new Object();

    /**
     *  @since 0.9.8
     */
    protected enum State {
        /** @since 0.9.20 */
        INIT,
        OPENING,
        /** @since 0.9.11 */
        GOTDATE,
        OPEN,
        CLOSING,
        CLOSED
    }

    protected State _state = State.INIT;
    protected final Object _stateLock = new Object();

    /** 
     * thread that we tell when new messages are available who then tells us 
     * to fetch them.  The point of this is so that the fetch doesn't block the
     * reading of other messages (in turn, potentially leading to deadlock)
     *
     */
    protected AvailabilityNotifier _availabilityNotifier;

    private long _lastActivity;
    private boolean _isReduced;
    private final boolean _fastReceive;
    private volatile boolean _routerSupportsFastReceive;
    private volatile boolean _routerSupportsHostLookup;

    protected static final int CACHE_MAX_SIZE = SystemVersion.isAndroid() ? 32 : 128;
    /**
     *  Since 0.9.11, key is either a Hash or a String
     *  @since 0.8.9
     */
    private static final Map<Object, Destination> _lookupCache = new LHMCache<Object, Destination>(CACHE_MAX_SIZE);
    private static final String MIN_HOST_LOOKUP_VERSION = "0.9.11";
    private static final boolean TEST_LOOKUP = false;

    /** SSL interface (only) @since 0.8.3 */
    protected static final String PROP_ENABLE_SSL = "i2cp.SSL";
    protected static final String PROP_USER = "i2cp.username";
    protected static final String PROP_PW = "i2cp.password";

    /**
     * Use Unix domain socket (or similar) to connect to a router
     * @since 0.9.14
     */
    protected static final String PROP_DOMAIN_SOCKET = "i2cp.domainSocket";

    private static final long VERIFY_USAGE_TIME = 60*1000;

    private static final long MAX_SEND_WAIT = 10*1000;

    private static final String MIN_FAST_VERSION = "0.9.4";

    /** @param routerVersion as rcvd in the SetDateMessage, may be null for very old routers */
    void dateUpdated(String routerVersion) {
        _routerSupportsFastReceive = _context.isRouterContext() ||
                                     (routerVersion != null && routerVersion.length() > 0 &&
                                      VersionComparator.comp(routerVersion, MIN_FAST_VERSION) >= 0);
        _routerSupportsHostLookup = _context.isRouterContext() ||
                                    TEST_LOOKUP ||
                                     (routerVersion != null && routerVersion.length() > 0 &&
                                      VersionComparator.comp(routerVersion, MIN_HOST_LOOKUP_VERSION) >= 0);
        _routerSupportsSubsessions = _context.isRouterContext() ||
                                     (routerVersion != null && routerVersion.length() > 0 &&
                                      VersionComparator.comp(routerVersion, MIN_SUBSESSION_VERSION) >= 0);
        synchronized (_stateLock) {
            if (_state == State.OPENING) {
                changeState(State.GOTDATE);
            }
        }
    }

    public static final int LISTEN_PORT = 7654;

    private static final int BUF_SIZE = 32*1024;

    /**
     * for extension by SimpleSession (no dest)
     */
    protected I2PSessionImpl(I2PAppContext context, Properties options,
                             I2PClientMessageHandlerMap handlerMap) {
        this(context, options, handlerMap, null, false);
    }

    /*
     * For extension by SubSession via I2PSessionMuxedImpl and I2PSessionImpl2
     *
     * @param destKeyStream stream containing the private key data,
     *                             format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     * @since 0.9.21
     */
    protected I2PSessionImpl(I2PSessionImpl primary, InputStream destKeyStream, Properties options) throws I2PSessionException {
        this(primary.getContext(), options, primary.getHandlerMap(), primary.getProducer(), true);
        _availabilityNotifier = new AvailabilityNotifier();
        try {
            readDestination(destKeyStream);
        } catch (DataFormatException dfe) {
            throw new I2PSessionException("Error reading the destination key stream", dfe);
        } catch (IOException ioe) {
            throw new I2PSessionException("Error reading the destination key stream", ioe);
        }
    }

    /**
     * Basic setup of finals
     * @since 0.9.7
     */
    private I2PSessionImpl(I2PAppContext context, Properties options,
                           I2PClientMessageHandlerMap handlerMap,
                           I2CPMessageProducer producer,
                           boolean hasDest) {
        _context = context;
        _handlerMap = handlerMap;
        _log = context.logManager().getLog(getClass());
        _subsessions = new CopyOnWriteArrayList<SubSession>();
        _subsessionMap = new ConcurrentHashMap<SessionId, SubSession>(4);
        if (options == null)
            options = (Properties) System.getProperties().clone();
        _options = loadConfig(options);
        _hostname = getHost();
        _portNum = getPort();
        _fastReceive = Boolean.parseBoolean(_options.getProperty(I2PClient.PROP_FAST_RECEIVE));
        if (hasDest) {
            _producer = producer;
            _availableMessages = new ConcurrentHashMap<Long, MessagePayloadMessage>();
            _myDestination = new Destination();
            _privateKey = new PrivateKey();
            _signingPrivateKey = new SigningPrivateKey();
        } else {
            _producer = null;
            _availableMessages = null;
            _myDestination = null;
            _privateKey = null;
            _signingPrivateKey = null;
        }
        _routerSupportsFastReceive = _context.isRouterContext();
        _routerSupportsHostLookup = _context.isRouterContext();
        _routerSupportsSubsessions = _context.isRouterContext();
    }

    /**
     * Create a new session, reading the Destination, PrivateKey, and SigningPrivateKey
     * from the destKeyStream, and using the specified options to connect to the router
     *
     * As of 0.9.19, defaults in options are honored.
     *
     * @param destKeyStream stream containing the private key data,
     *                             format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     * @throws I2PSessionException if there is a problem loading the private keys
     */
    public I2PSessionImpl(I2PAppContext context, InputStream destKeyStream, Properties options) throws I2PSessionException {
        this(context, options, new I2PClientMessageHandlerMap(context), new I2CPMessageProducer(context), true);
        _availabilityNotifier = new AvailabilityNotifier();
        try {
            readDestination(destKeyStream);
        } catch (DataFormatException dfe) {
            throw new I2PSessionException("Error reading the destination key stream", dfe);
        } catch (IOException ioe) {
            throw new I2PSessionException("Error reading the destination key stream", ioe);
        }
    }
    
    /**
     *  Router must be connected or was connected... for now.
     *
     *  @return a new subsession, non-null
     *  @param privateKeyStream null for transient, if non-null must have same encryption keys as primary session
     *                          and different signing keys
     *  @param opts subsession options if any, may be null
     *  @since 0.9.21
     */
    public I2PSession addSubsession(InputStream privateKeyStream, Properties opts) throws I2PSessionException {
        if (!_routerSupportsSubsessions)
            throw new I2PSessionException("Router does not support subsessions");
        SubSession sub;
        synchronized(_subsessionLock) {
            if (_subsessions.size() > _subsessionMap.size())
                throw new I2PSessionException("Subsession request already pending");
            sub = new SubSession(this, privateKeyStream, opts);
            for (SubSession ss : _subsessions) {
                 if (ss.getDecryptionKey().equals(sub.getDecryptionKey()) &&
                     ss.getPrivateKey().equals(sub.getPrivateKey())) {
                    throw new I2PSessionException("Dup subsession");
                }
            }
            _subsessions.add(sub);
        }

        synchronized (_stateLock) {
            if (_state == State.OPEN) {
                _producer.connect(sub);
            } // else will be called in connect()
        }
        return sub;
    }
    
    /**
     *  @since 0.9.21
     */
    public void removeSubsession(I2PSession session) {
        if (!(session instanceof SubSession))
            return;
        synchronized(_subsessionLock) {
            _subsessions.remove(session);
            SessionId id = ((SubSession) session).getSessionId();
            if (id != null)
                _subsessionMap.remove(id);
            /// tell the subsession
            try {
                // doesn't really throw
                session.destroySession();
            } catch (I2PSessionException ise) {}
        }
        // do we need this here? subsession.destroySession() calls primary
        Destination d = session.getMyDestination();
        if (d != null)
            _context.keyRing().remove(d.calculateHash());
    }
    
    /**
     *  @return a list of subsessions, non-null, does not include the primary session
     *  @since 0.9.21
     */
    public List<I2PSession> getSubsessions() {
        synchronized(_subsessionLock) {
            return new ArrayList<I2PSession>(_subsessions);
        }
    }

    /**
     * Parse the config for anything we know about.
     * Also fill in the authorization properties if missing.
     */
    private final Properties loadConfig(Properties opts) {
        Properties options = new Properties();
        options.putAll(filter(opts));

        // auto-add auth if required, not set in the options, and we are not in the same JVM
        if ((!_context.isRouterContext()) &&
            _context.getBooleanProperty("i2cp.auth") &&
            ((!opts.containsKey(PROP_USER)) || (!opts.containsKey(PROP_PW)))) {
            String configUser = _context.getProperty(PROP_USER);
            String configPW = _context.getProperty(PROP_PW);
            if (configUser != null && configPW != null) {
                options.setProperty(PROP_USER, configUser);
                options.setProperty(PROP_PW, configPW);
            }
        }
        if (options.getProperty(I2PClient.PROP_FAST_RECEIVE) == null)
            options.setProperty(I2PClient.PROP_FAST_RECEIVE, "true");
        if (options.getProperty(I2PClient.PROP_RELIABILITY) == null)
            options.setProperty(I2PClient.PROP_RELIABILITY, "none");
        return options;
    }

    /**
     * Get I2CP host from the config
     * @since 0.9.7 was in loadConfig()
     */
    private String getHost() {
        if (_context.isRouterContext())
            // just for logging
            return "[internal connection]";
        else if (SystemVersion.isAndroid() &&
                Boolean.parseBoolean(_options.getProperty(PROP_DOMAIN_SOCKET)))
            // just for logging
            return "[Domain socket connection]";
        return _options.getProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
    }

    /**
     * Get I2CP port from the config
     * @since 0.9.7 was in loadConfig()
     */
    private int getPort() {
        if (_context.isRouterContext() ||
                (SystemVersion.isAndroid() &&
                        Boolean.parseBoolean(_options.getProperty(PROP_DOMAIN_SOCKET))))
            // just for logging
            return 0;
        String portNum = _options.getProperty(I2PClient.PROP_TCP_PORT, LISTEN_PORT + "");
        try {
            return Integer.parseInt(portNum);
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(getPrefix() + "Invalid port number specified, defaulting to "
                          + LISTEN_PORT, nfe);
            return LISTEN_PORT;
        }
    }

    /**
     *  Save some memory, don't pass along the pointless properties.
     *  As of 0.9.19, defaults from options will be promoted to real values in rv.
     *  @return a new Properties without defaults
     */
    private Properties filter(Properties options) {
        Properties rv = new Properties();
        for (String key : options.stringPropertyNames()) {
            if (key.startsWith("java.") ||
                key.startsWith("user.") ||
                key.startsWith("os.") ||
                key.startsWith("sun.") ||
                key.startsWith("awt.") ||
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
                key.equals("loggerFilenameOverride") ||
                key.equals("router.version") ||
                key.equals("i2p.dir.base") ||
                key.startsWith("networkaddress.cache.") ||
                key.startsWith("http.") ||
                key.startsWith("jetty.") ||
                key.startsWith("org.mortbay.") ||
                key.startsWith("wrapper.")) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Skipping property: " + key);
                continue;
            }
            String val = options.getProperty(key);
            // Long strings MUST be removed, even in router context,
            // as the session config properties must be serialized to be signed.
            // fixme, bytes could still be over 255 (unlikely)
            if (key.length() > 255 || val.length() > 255) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not passing on property ["
                              + key
                              + "] in the session config, key or value is too long (max = 255): "
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

    /**
     *  @since 0.9.4
     */
    public boolean getFastReceive() {
        return _fastReceive && _routerSupportsFastReceive;
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

    protected void changeState(State state) {
        if (_log.shouldInfo())
            _log.info(getPrefix() + "Change state to " + state);
        synchronized (_stateLock) {
            _state = state;
            _stateLock.notifyAll();
        }
    }

    /**
     * Load up the destKeyFile for our Destination, PrivateKey, and SigningPrivateKey
     *
     * @throws DataFormatException if the file is in the wrong format or keys are invalid
     * @throws IOException if there is a problem reading the file
     */
    private void readDestination(InputStream destKeyStream) throws DataFormatException, IOException {
        _myDestination.readBytes(destKeyStream);
        _privateKey.readBytes(destKeyStream);
        _signingPrivateKey = new SigningPrivateKey(_myDestination.getSigningPublicKey().getType());
        _signingPrivateKey.readBytes(destKeyStream);
    }

    /**
     * Connect to the router and establish a session.  This call blocks until 
     * a session is granted.
     *
     * Should be threadsafe, other threads will block until complete.
     * Disconnect / destroy from another thread may be called simultaneously and
     * will (should?) interrupt the connect.
     *
     * Connecting a primary session will not automatically connect subsessions.
     * Connecting a subsession will automatically connect the primary session
     * if not previously connected.
     *
     * @throws I2PSessionException if there is a configuration error or the router is
     *                             not reachable
     */
    public void connect() throws I2PSessionException {
        synchronized(_stateLock) {
            boolean wasOpening = false;
            boolean loop = true;
            while (loop) {
                switch (_state) {
                    case INIT:
                        loop = false;
                        break;
                    case CLOSED:
                        if (wasOpening)
                            throw new I2PSessionException("connect by other thread failed");
                        loop = false;
                        break;
                    case OPENING:
                    case GOTDATE:
                        wasOpening = true;
                        try {
                            _stateLock.wait(10*1000);
                        } catch (InterruptedException ie) {
                            throw new I2PSessionException("Interrupted", ie);
                        }
                        break;
                    case CLOSING:
                        throw new I2PSessionException("close in progress");
                    case OPEN:
                        return;
                }
            }
            changeState(State.OPENING);
        }

        _availabilityNotifier.stopNotifying();
        
        if ( (_options != null) && 
             (I2PClient.PROP_RELIABILITY_GUARANTEED.equals(_options.getProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_BEST_EFFORT))) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("I2CP guaranteed delivery mode has been removed, using best effort.");
        }
            
        boolean success = false;
        long startConnect = _context.clock().now();
        try {
            // protect w/ closeSocket()
            synchronized(_stateLock) {
                // If we are in the router JVM, connect using the internal queue
                if (_context.isRouterContext()) {
                    // _socket and _writer remain null
                    InternalClientManager mgr = _context.internalClientManager();
                    if (mgr == null)
                        throw new I2PSessionException("Router is not ready for connections");
                    // the following may throw an I2PSessionException
                    _queue = mgr.connect();
                    _reader = new QueuedI2CPMessageReader(_queue, this);
                } else {
                    if (SystemVersion.isAndroid() &&
                            _options.getProperty(PROP_DOMAIN_SOCKET) != null) {
                        try {
                            Class<?> clazz = Class.forName("net.i2p.client.DomainSocketFactory");
                            Constructor<?> ctor = clazz.getDeclaredConstructor(I2PAppContext.class);
                            Object fact = ctor.newInstance(_context);
                            Method createSocket = clazz.getDeclaredMethod("createSocket", String.class);
                            try {
                                _socket = (Socket) createSocket.invoke(fact, _options.getProperty(PROP_DOMAIN_SOCKET));
                            } catch (InvocationTargetException e) {
                                throw new I2PSessionException("Cannot create domain socket", e);
                            }
                        } catch (ClassNotFoundException e) {
                            throw new I2PSessionException("Cannot load DomainSocketFactory", e);
                        } catch (NoSuchMethodException e) {
                            throw new I2PSessionException("Cannot load DomainSocketFactory", e);
                        } catch (InstantiationException e) {
                            throw new I2PSessionException("Cannot load DomainSocketFactory", e);
                        } catch (IllegalAccessException e) {
                            throw new I2PSessionException("Cannot load DomainSocketFactory", e);
                        } catch (InvocationTargetException e) {
                            throw new I2PSessionException("Cannot load DomainSocketFactory", e);
                        }
                    } else if (Boolean.parseBoolean(_options.getProperty(PROP_ENABLE_SSL))) {
                        try {
                            I2PSSLSocketFactory fact = new I2PSSLSocketFactory(_context, false, "certificates/i2cp");
                            _socket = fact.createSocket(_hostname, _portNum);
                            _socket.setKeepAlive(true);
                        } catch (GeneralSecurityException gse) {
                            IOException ioe = new IOException("SSL Fail");
                            ioe.initCause(gse);
                            throw ioe;
                        }
                    } else {
                        _socket = new Socket(_hostname, _portNum);
                        _socket.setKeepAlive(true);
                    }
                    // _socket.setSoTimeout(1000000); // Uhmmm we could really-really use a real timeout, and handle it.
                    OutputStream out = _socket.getOutputStream();
                    out.write(I2PClient.PROTOCOL_BYTE);
                    out.flush();
                    _writer = new ClientWriterRunner(out, this);
                    _writer.startWriting();
                    InputStream in = new BufferedInputStream(_socket.getInputStream(), BUF_SIZE);
                    _reader = new I2CPMessageReader(in, this);
                }
            }
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "before startReading");
            _reader.startReading();
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Before getDate");
            Properties auth = null;
            if ((!_context.isRouterContext()) && _options.containsKey(PROP_USER) && _options.containsKey(PROP_PW)) {
                // Only supported by routers 0.9.11 or higher, but we don't know the version yet.	
                // Auth will also be sent in the SessionConfig.
                auth = new OrderedProperties();
                auth.setProperty(PROP_USER, _options.getProperty(PROP_USER));
                auth.setProperty(PROP_PW, _options.getProperty(PROP_PW));
            }
            sendMessage_unchecked(new GetDateMessage(CoreVersion.VERSION, auth));
            waitForDate();

            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Before producer.connect()");
            _producer.connect(this);
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "After producer.connect()");

            // wait until we have created a lease set
            int waitcount = 0;
            while (_leaseSet == null) {
                if (waitcount++ > 5*60) {
                    throw new IOException("No tunnels built after waiting 5 minutes. Your network connection may be down, or there is severe network congestion.");
                }
                synchronized (_leaseSetWait) {
                    // InterruptedException caught below
                    _leaseSetWait.wait(1000);
                }
                // if we got a disconnect message while waiting
                if (isClosed())
                    throw new IOException("Disconnected from router while waiting for tunnels");
            }
            if (_log.shouldLog(Log.INFO)) {
                long connected = _context.clock().now();
                 _log.info(getPrefix() + "Lease set created with inbound tunnels after "
                           + (connected - startConnect)
                           + "ms - ready to participate in the network!");
            }
            Thread notifier = new I2PAppThread(_availabilityNotifier, "ClientNotifier " + getPrefix(), true);
            notifier.start();
            startIdleMonitor();
            startVerifyUsage();
            success = true;

            // now send CreateSessionMessages for all subsessions, one at a time, must wait for each response
            synchronized(_subsessionLock) {
                for (SubSession ss : _subsessions) {
                   if (_log.shouldLog(Log.INFO))
                       _log.info(getPrefix() + "Connecting subsession " + ss);
                    _producer.connect(ss);
                }
            }

        } catch (InterruptedException ie) {
            throw new I2PSessionException("Interrupted", ie);
        } catch (UnknownHostException uhe) {
            throw new I2PSessionException(getPrefix() + "Cannot connect to the router on " + _hostname + ':' + _portNum, uhe);
        } catch (IOException ioe) {
            // Generate the best error message as this will be logged
            String msg;
            if (_context.isRouterContext())
                msg = "Failed to build tunnels";
            else if (SystemVersion.isAndroid() &&
                    _options.getProperty(PROP_DOMAIN_SOCKET) != null)
                msg = "Failed to bind to the router on " + _options.getProperty(PROP_DOMAIN_SOCKET) + " and build tunnels";
            else
                msg = "Cannot connect to the router on " + _hostname + ':' + _portNum + " and build tunnels";
            throw new I2PSessionException(getPrefix() + msg, ioe);
        } finally {
            if (success) {
                changeState(State.OPEN);
            } else {
                _availabilityNotifier.stopNotifying();
                synchronized(_stateLock) {
                    changeState(State.CLOSING);
                    try {
                        _producer.disconnect(this);
                    } catch (I2PSessionException ipe) {}
                    closeSocket();
                }
            }
        }
    }

    /**
     *  @since 0.9.11 moved from connect()
     */
    protected void waitForDate() throws InterruptedException, IOException {
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "After getDate / begin waiting for a response");
            int waitcount = 0;
            while (true) {
                if (waitcount++ > 30) {
                    throw new IOException("No handshake received from the router");
                }
                synchronized(_stateLock) {
                    if (_state == State.GOTDATE)
                        break;
                    if (_state != State.OPENING && _state != State.INIT)
                        throw new IOException("Socket closed, state=" + _state);
                    // InterruptedException caught by caller
                    _stateLock.wait(1000);
                }
            }
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "After received a SetDate response");
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
        verifyOpen();
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
    }

    /**
     *  Fire up a periodic task to check for unclaimed messages
     *  @since 0.9.1
     */
    protected void startVerifyUsage() {
        new VerifyUsage();
    }

    /**
     *  Check for unclaimed messages, without wastefully setting a timer for each
     *  message. Just copy all unclaimed ones and check some time later.
     */
    private class VerifyUsage extends SimpleTimer2.TimedEvent {
        private final List<Long> toCheck = new ArrayList<Long>();
        
        public VerifyUsage() {
             super(_context.simpleTimer2(), VERIFY_USAGE_TIME);
        }

        public void timeReached() {
            if (isClosed())
                return;
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug(getPrefix() + " VerifyUsage of " + toCheck.size());
            if (!toCheck.isEmpty()) {
                for (Long msgId : toCheck) {
                    MessagePayloadMessage removed = _availableMessages.remove(msgId);
                    if (removed != null)
                        _log.error(getPrefix() + " Client not responding? Message not processed! id=" + msgId + ": " + removed);
                }
                toCheck.clear();
            }
            toCheck.addAll(_availableMessages.keySet());
            schedule(VERIFY_USAGE_TIME);
        }
    }

    /**
     *  This notifies the client of payload messages.
     *  Needs work.
     */
    protected class AvailabilityNotifier implements Runnable {
        private final List<Long> _pendingIds;
        private final List<Integer> _pendingSizes;
        private volatile boolean _alive;
 
        public AvailabilityNotifier() {
            _pendingIds = new ArrayList<Long>(2);
            _pendingSizes = new ArrayList<Integer>(2);
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
                        msgId = _pendingIds.remove(0);
                        size = _pendingSizes.remove(0);
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
                        } catch (RuntimeException e) {
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
     *
     * We route the message based on message type AND session ID.
     *
     * The following types never contain a session ID and are not routable to
     * a subsession:
     *     BandwidthLimitsMessage, DestReplyMessage
     *
     * The following types may not contain a valid session ID
     * even when intended for a subsession, so we must take special care:
     *     SessionStatusMessage
     *
     * @param reader unused
     */
    public void messageReceived(I2CPMessageReader reader, I2CPMessage message) {
        int type = message.getType();
        SessionId id = message.sessionId();
        SessionId currId = _sessionId;
        if (id == null || id.equals(currId) ||
            (currId == null && id != null && type == SessionStatusMessage.MESSAGE_TYPE) ||
            ((id == null || id.getSessionId() == 65535) &&
             (type == HostReplyMessage.MESSAGE_TYPE || type == DestReplyMessage.MESSAGE_TYPE))) {
            // it's for us
            I2CPMessageHandler handler = _handlerMap.getHandler(type);
            if (handler != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix() + "Message received of type " + type
                               + " to be handled by " + handler.getClass().getSimpleName());
                handler.handleMessage(message, this);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getPrefix() + "Unknown message or unhandleable message received: type = "
                              + type);
            }
        } else {
            SubSession sub = _subsessionMap.get(id);
            if (sub != null) {
                // it's for a subsession
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix() + "Message received of type " + type
                               + " to be handled by " + sub);
                sub.messageReceived(reader, message);
            } else if (id != null && type == SessionStatusMessage.MESSAGE_TYPE) {
                // look for a subsession without a session
                synchronized (_subsessionLock) {
                    for (SubSession sess : _subsessions) {
                        if (sess.getSessionId() == null) {
                            sess.messageReceived(reader, message);
                            id = sess.getSessionId();
                            if (id != null) {
                                if (id.equals(_sessionId)) {
                                    // shouldnt happen
                                    sess.setSessionId(null);
                                    if (_log.shouldLog(Log.WARN))
                                        _log.warn("Dup or our session id " + id);
                                } else {
                                    SubSession old = _subsessionMap.putIfAbsent(id, sess);
                                    if (old != null) {
                                        // shouldnt happen
                                        sess.setSessionId(null);
                                        if (_log.shouldLog(Log.WARN))
                                            _log.warn("Dup session id " + id);
                                    }
                                }
                            }
                            return;
                        }
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(getPrefix() + "No session " + id + " to handle message: type = "
                                      + type);
                    }
                }
            } else {
                // it's for nobody
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getPrefix() + "No session " + id + " to handle message: type = "
                              + type);
            }
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
     *  For Subsessions
     *  @since 0.9.21
     */
    I2PClientMessageHandlerMap getHandlerMap() { return _handlerMap; }

    /**
     *  For Subsessions
     *  @since 0.9.21
     */
    I2PAppContext getContext() { return _context; }

    /**
     * Retrieve the configuration options, filtered.
     * All defaults passed in via constructor have been promoted to the primary map.
     *
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

    /**
     *  Has the session been closed (or not yet connected)?
     *  False when open and during transitions. Synchronized.
     */
    public boolean isClosed() {
        synchronized (_stateLock) {
            return _state == State.CLOSED || _state == State.INIT;
        }
    }

    /**
     *  Throws I2PSessionException if uninitialized, closed or closing.
     *  Blocks if opening.
     *
     *  @since 0.9.23
     */
    protected void verifyOpen() throws I2PSessionException {
        synchronized (_stateLock) {
            while (true) {
                switch (_state) {
                    case INIT:
                        throw new I2PSessionException("Not open, must call connect() first");

                    case OPENING:  // fall thru
                    case GOTDATE:
                        try {
                            _stateLock.wait(5*1000);
                            continue;
                        } catch (InterruptedException ie) {
                            throw new I2PSessionException("Interrupted", ie);
                        }

                    case OPEN:
                        return;

                    case CLOSING:  // fall thru
                    case CLOSED:
                        throw new I2PSessionException("Already closed");
                }
            }
        }
    }

    /**
     * Deliver an I2CP message to the router
     * As of 0.9.3, may block for several seconds if the write queue to the router is full
     *
     * @throws I2PSessionException if the message is malformed or there is an error writing it out
     */
    void sendMessage(I2CPMessage message) throws I2PSessionException {
        verifyOpen();
        sendMessage_unchecked(message);
    }

    /**
     * Deliver an I2CP message to the router.
     * Does NOT check state. Call only from connect() or other methods that need to
     * send messages when not in OPEN state.
     *
     * @throws I2PSessionException if the message is malformed or there is an error writing it out
     * @since 0.9.23
     */
    void sendMessage_unchecked(I2CPMessage message) throws I2PSessionException {
        if (_queue != null) {
            // internal
            try {
                if (!_queue.offer(message, MAX_SEND_WAIT))
                    throw new I2PSessionException("Timed out waiting while write queue was full");
            } catch (InterruptedException ie) {
                throw new I2PSessionException("Interrupted", ie);
            }
        } else {
            ClientWriterRunner writer = _writer;
            if (writer == null) {
                throw new I2PSessionException("Already closed or not open");
            } else {
                writer.addMessage(message);
            }
        }
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
     * Will interrupt an open in progress.
     */
    public void destroySession(boolean sendDisconnect) {
        synchronized(_stateLock) {
            if (_state == State.CLOSING || _state == State.CLOSED || _state == State.INIT)
                return;
            changeState(State.CLOSING);
        }
        
        if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "Destroy the session", new Exception("DestroySession()"));
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
        closeSocket();
        _subsessionMap.clear();
        if (_sessionListener != null) _sessionListener.disconnected(this);
    }

    /**
     * Close the socket carefully.
     */
    private void closeSocket() {
        if (_log.shouldLog(Log.INFO))
            _log.info(getPrefix() + "Closing the socket", new Exception("closeSocket"));
        // maybe not the right place for this, but let's be sure
        Destination d = _myDestination;
        if (d != null)
            _context.keyRing().remove(d.calculateHash());
        synchronized(_stateLock) {
            changeState(State.CLOSING);
            locked_closeSocket();
            changeState(State.CLOSED);
        }
        synchronized (_subsessionLock) {
            for (SubSession sess : _subsessions) {
                d = sess.getMyDestination();
                if (d != null)
                    _context.keyRing().remove(d.calculateHash());
                sess.changeState(State.CLOSED);
                sess.setSessionId(null);
                sess.setLeaseSet(null);
            }
        }
    }

    /**
     * Close the socket carefully.
     * Caller must change state.
     */
    private void locked_closeSocket() {
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
        setSessionId(null);
        setLeaseSet(null);
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

    /**
     * Will interrupt a connect in progress.
     */
    protected void disconnect() {
        State oldState;
        synchronized(_stateLock) {
            if (_state == State.CLOSING || _state == State.CLOSED || _state == State.INIT)
                return;
            oldState = _state;
            changeState(State.CLOSING);
        }
        if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Disconnect() called", new Exception("Disconnect"));
        // don't try to reconnect if it failed before GETTDATE
        if (oldState != State.OPENING && shouldReconnect()) {
            if (reconnect()) {
                if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "I2CP reconnection successful");
                return;
            }
            if (_log.shouldLog(Log.ERROR)) _log.error(getPrefix() + "I2CP reconnection failed");
        }

        if (_log.shouldLog(Log.ERROR))
            _log.error(getPrefix() + "Disconned from the router, and not trying to reconnect");
        if (_sessionListener != null) _sessionListener.disconnected(this);

        closeSocket();
        changeState(State.CLOSED);
        // break out of wait for initial LS in connect()
        synchronized (_leaseSetWait) {
            _leaseSetWait.notifyAll();
        }
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
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                return false;
            }
            
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
        buf.append(_state.toString()).append(' ');
        String s = _options.getProperty("inbound.nickname");
        if (s != null)
            buf.append(s);
        else
            buf.append(getClass().getSimpleName());
        SessionId id = _sessionId;
        if (id != null)
            buf.append(" #").append(id.getSessionId());
        buf.append("]: ");
        return buf.toString();
    }

    /**
     *  Called by the message handler
     *  on reception of DestReplyMessage
     *  @param d non-null
     */
    void destReceived(Destination d) {
        Hash h = d.calculateHash();
        synchronized (_lookupCache) {
            _lookupCache.put(h, d);
        }
        for (LookupWaiter w : _pendingLookups) {
            if (h.equals(w.hash)) {
                synchronized (w) {
                    w.destination = d;
                    w.notifyAll();
                }
            }
        }
    }

    /**
     *  Called by the message handler
     *  on reception of DestReplyMessage
     *  @param h non-null
     */
    void destLookupFailed(Hash h) {
        for (LookupWaiter w : _pendingLookups) {
            if (h.equals(w.hash)) {
                synchronized (w) {
                    w.notifyAll();
                }
            }
        }
    }

    /**
     *  Called by the message handler
     *  on reception of HostReplyMessage
     *  @param d non-null
     *  @since 0.9.11
     */
    void destReceived(long nonce, Destination d) {
        // notify by nonce and hash
        Hash h = d.calculateHash();
        for (LookupWaiter w : _pendingLookups) {
            if (nonce == w.nonce || h.equals(w.hash)) {
                synchronized (_lookupCache) {
                    if (w.name != null)
                        _lookupCache.put(w.name, d);
                    _lookupCache.put(h, d);
                }
                synchronized (w) {
                    w.destination = d;
                    w.notifyAll();
                }
            }
        }
    }

    /**
     *  Called by the message handler
     *  on reception of HostReplyMessage
     *  @since 0.9.11
     */
    void destLookupFailed(long nonce) {
        for (LookupWaiter w : _pendingLookups) {
            if (nonce == w.nonce) {
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
        /** the request (Hash mode) */
        public final Hash hash;
        /** the request (String mode) */
        public final String name;
        /** the request (nonce mode) */
        public final long nonce;
        /** the reply; synch on this */
        public Destination destination;

        public LookupWaiter(Hash h) {
            this(h, -1);
        }

        /** @since 0.9.11 */
        public LookupWaiter(Hash h, long nonce) {
            this.hash = h;
            this.name = null;
            this.nonce = nonce;
        }

        /** @since 0.9.11 */
        public LookupWaiter(String name, long nonce) {
            this.hash = null;
            this.name = name;
            this.nonce = nonce;
        }
    }

    /** @since 0.9.20 */
    public static void clearCache() {
        synchronized (_lookupCache) {
            _lookupCache.clear();
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
        synchronized (_stateLock) {
            // not before GOTDATE
            if (_state == State.CLOSED ||
                _state == State.INIT ||
                _state == State.OPENING) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Session closed, cannot lookup " + h);
                return null;
            }
        }
        LookupWaiter waiter;
        long nonce;
        if (_routerSupportsHostLookup) {
            nonce = _lookupID.incrementAndGet() & 0x7fffffff;
            waiter = new LookupWaiter(h, nonce);
        } else {
            nonce = 0; // won't be used
            waiter = new LookupWaiter(h);
        }
        _pendingLookups.offer(waiter);
        Destination rv = null;
        try {
            if (_routerSupportsHostLookup) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Sending HostLookup for " + h);
                SessionId id = _sessionId;
                if (id == null)
                    id = new SessionId(65535);
                sendMessage_unchecked(new HostLookupMessage(id, h, nonce, maxWait));
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Sending DestLookup for " + h);
                sendMessage_unchecked(new DestLookupMessage(h));
            }
            try {
                synchronized (waiter) {
                    waiter.wait(maxWait);
                    rv = waiter.destination;
                }
            } catch (InterruptedException ie) {
                throw new I2PSessionException("Interrupted", ie);
            }
        } finally {
            _pendingLookups.remove(waiter);
        }
        return rv;
    }

    /**
     *  Ask the router to lookup a Destination by host name.
     *  Blocking. Waits a max of 10 seconds by default.
     *
     *  This only makes sense for a b32 hostname, OR outside router context.
     *  Inside router context, just query the naming service.
     *  Outside router context, this does NOT query the context naming service.
     *  Do that first if you expect a local addressbook.
     *
     *  This will log a warning for non-b32 in router context.
     *
     *  See interface for suggested implementation.
     *
     *  Requires router side to be 0.9.11 or higher. If the router is older,
     *  this will return null immediately.
     *
     *  @since 0.9.11
     */
    public Destination lookupDest(String name) throws I2PSessionException {
        return lookupDest(name, 10*1000);
    }

    /**
     *  Ask the router to lookup a Destination by host name.
     *  Blocking. See above for details.
     *  @param maxWait ms
     *  @since 0.9.11
     *  @return null on failure
     */
    public Destination lookupDest(String name, long maxWait) throws I2PSessionException {
        if (name.length() == 0)
            return null;
        // Shortcut for b64
        if (name.length() >= 516) {
            try {
                return new Destination(name);
            } catch (DataFormatException dfe) {
                return null;
            }
        }
        // won't fit in Mapping
        if (name.length() >= 256 && !_context.isRouterContext())
            return null;
        synchronized (_lookupCache) {
            Destination rv = _lookupCache.get(name);
            if (rv != null)
                return rv;
        }
        if (isClosed()) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Session closed, cannot lookup " + name);
            return null;
        }
        if (!_routerSupportsHostLookup) {
            // do them a favor and convert to Hash lookup
            if (name.length() == 60 && name.toLowerCase(Locale.US).endsWith(".b32.i2p"))
                return lookupDest(Hash.create(Base32.decode(name.toLowerCase(Locale.US).substring(0, 52))), maxWait);
            // else unsupported
            if (_log.shouldLog(Log.WARN))
                _log.warn("Router does not support HostLookup for " + name);
            return null;
        }
        int nonce = _lookupID.incrementAndGet() & 0x7fffffff;
        LookupWaiter waiter = new LookupWaiter(name, nonce);
        _pendingLookups.offer(waiter);
        Destination rv = null;
        try {
            if (_log.shouldLog(Log.INFO))
                _log.info("Sending HostLookup for " + name);
            SessionId id = _sessionId;
            if (id == null)
                id = new SessionId(65535);
            sendMessage_unchecked(new HostLookupMessage(id, name, nonce, maxWait));
            try {
                synchronized (waiter) {
                    waiter.wait(maxWait);
                    rv = waiter.destination;
                }
            } catch (InterruptedException ie) {
                throw new I2PSessionException("Interrupted", ie);
            }
        } finally {
            _pendingLookups.remove(waiter);
        }
        return rv;
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
        synchronized (_stateLock) {
            // not before GOTDATE
            if (_state == State.CLOSED ||
                _state == State.INIT ||
                _state == State.OPENING) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Session closed, cannot get bw limits");
                return null;
            }
        }
        sendMessage_unchecked(new GetBandwidthLimitsMessage());
        try {
            synchronized (_bwReceivedLock) {
                _bwReceivedLock.wait(5*1000);
            }
        } catch (InterruptedException ie) {
            throw new I2PSessionException("Interrupted", ie);
        }
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
        boolean reduce = Boolean.parseBoolean(_options.getProperty("i2cp.reduceOnIdle"));
        boolean close = Boolean.parseBoolean(_options.getProperty("i2cp.closeOnIdle"));
        if (reduce || close) {
            updateActivity();
            _context.simpleTimer2().addEvent(new SessionIdleTimer(_context, this, reduce, close), SessionIdleTimer.MINIMUM_TIME);
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
}
