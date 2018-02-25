/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.crypto.SigType;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;

public abstract class I2PTunnelClientBase extends I2PTunnelTask implements Runnable {

    protected final Log _log;
    protected final I2PAppContext _context;
    protected final Logging l;

    static final long DEFAULT_CONNECT_TIMEOUT = 60 * 1000;

    private static final AtomicLong __clientId = new AtomicLong();
    protected long _clientId;
    protected final Object sockLock = new Object(); // Guards sockMgr and mySockets
    protected I2PSocketManager sockMgr; // should be final and use a factory. LINT
    protected final List<I2PSocket> mySockets = new ArrayList<I2PSocket>();
    protected boolean _ownDest;

    protected Destination dest;
    private volatile int localPort;
    private final String _handlerName;

    /**
     *  Protected for I2Ping since 0.9.11. Not for use outside package.
     */
    protected boolean listenerReady;

    protected ServerSocket ss;

    private final Object startLock = new Object();
    private boolean startRunning;

    // private Object closeLock = new Object();

    // private byte[] pubkey;

    private String privKeyFile;

    // true if we are chained from a server.
    private boolean chained;

    private volatile ThreadPoolExecutor _executor;

    /** this is ONLY for shared clients */
    private static I2PSocketManager socketManager;

    /**
     *  Only destroy and replace a static shared client socket manager if it's been connected before
     *  @since 0.9.20
     */
    private enum SocketManagerState { INIT, CONNECTED }
    private static SocketManagerState _socketManagerState = SocketManagerState.INIT;

    public static final String PROP_USE_SSL = I2PTunnelServer.PROP_USE_SSL;

    /**
     * This constructor is used to add a client to an existing socket manager.
     * <p>
     * As of 0.9.21 this does NOT open the local socket. You MUST call
     * {@link #startRunning()} for that. The local socket will be opened
     * immediately (ignoring the <code>i2cp.delayOpen</code> option).
     *
     *  @param localPort if 0, use any port, get actual port selected with getLocalPort()
     *  @param sktMgr the existing socket manager
     */
    public I2PTunnelClientBase(int localPort, Logging l, I2PSocketManager sktMgr,
            I2PTunnel tunnel, EventDispatcher notifyThis, long clientId )
            throws IllegalArgumentException {
        super(localPort + " (uninitialized)", notifyThis, tunnel);
        chained = true;
        sockMgr = sktMgr;
        _clientId = clientId;
        _handlerName = "chained";
        this.localPort = localPort;
        this.l = l;
        _ownDest = true; // == ! shared client
        _context = tunnel.getContext();
        initStats();
        _log = _context.logManager().getLog(getClass());
    }

    /**
     * The main constructor.
     * <p>
     * As of 0.9.21 this is fast, and does NOT connect the manager to the router,
     * or open the local socket. You MUST call startRunning() for that.
     * <p>
     * (0.9.20 claimed to be fast, but due to a bug it DID connect the manager
     * to the router. It did NOT open the local socket however, so it was still
     * necessary to call startRunning() for that.)
     *
     * @param localPort if 0, use any port, get actual port selected with getLocalPort()
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we can't create a socketManager
     */
    public I2PTunnelClientBase(int localPort, boolean ownDest, Logging l, 
                               EventDispatcher notifyThis, String handlerName, 
                               I2PTunnel tunnel) throws IllegalArgumentException {
        this(localPort, ownDest, l, notifyThis, handlerName, tunnel, null);
    }

    /**
     * Use this to build a client with a persistent private key.
     * <p>
     * As of 0.9.21 this is fast, and does NOT connect the manager to the router,
     * or open the local socket. You MUST call startRunning() for that.
     * <p>
     * (0.9.20 claimed to be fast, but due to a bug it DID connect the manager
     * to the router. It did NOT open the local socket however, so it was still
     * necessary to call startRunning() for that.)
     *
     * @param localPort if 0, use any port, get actual port selected with getLocalPort()
     * @param pkf Path to the private key file, or null to generate a transient key
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we can't create a socketManager
     */
    public I2PTunnelClientBase(int localPort, boolean ownDest, Logging l, 
                               EventDispatcher notifyThis, String handlerName, 
                               I2PTunnel tunnel, String pkf) throws IllegalArgumentException{
        super(localPort + " (uninitialized)", notifyThis, tunnel);
        _clientId = __clientId.incrementAndGet();
        this.localPort = localPort;
        this.l = l;
        _ownDest = ownDest; // == ! shared client
        _handlerName = handlerName;

        _context = tunnel.getContext();
        initStats();
        _log = _context.logManager().getLog(getClass());

        // normalize path so we can find it
        if (pkf != null) {
            File keyFile = new File(pkf);
            if (!keyFile.isAbsolute())
                keyFile = new File(_context.getConfigDir(), pkf);
            this.privKeyFile = keyFile.getAbsolutePath();
        }

        // no need to load the netDb with leaseSets for destinations that will never 
        // be looked up
        boolean dccEnabled = (this instanceof I2PTunnelIRCClient) &&
                      Boolean.parseBoolean(tunnel.getClientOptions().getProperty(I2PTunnelIRCClient.PROP_DCC));
        if (!dccEnabled)
            tunnel.getClientOptions().setProperty("i2cp.dontPublishLeaseSet", "true");
        if (tunnel.getClientOptions().getProperty("i2p.streaming.answerPings") == null)
            tunnel.getClientOptions().setProperty("i2p.streaming.answerPings", "false");
    }

    private void initStats() {
        //_context.statManager().createRateStat("i2ptunnel.client.closeBacklog", "How many pending sockets remain when we close one due to backlog?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        //_context.statManager().createRateStat("i2ptunnel.client.closeNoBacklog", "How many pending sockets remain when it was removed prior to backlog timeout?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        //_context.statManager().createRateStat("i2ptunnel.client.manageTime", "How long it takes to accept a socket and fire it into an i2ptunnel runner (or queue it for the pool)?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        //_context.statManager().createRateStat("i2ptunnel.client.buildRunTime", "How long it takes to run a queued socket into an i2ptunnel runner?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }

    /**
     * Create the manager if it doesn't exist, AND connect it to the router and
     * build tunnels.
     *
     * Sets the this.sockMgr field if it is null, or if we want a new one.
     * This may take a LONG time if building a new manager.
     *
     * We need a socket manager before getDefaultOptions() and most other things
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected void verifySocketManager() {
        synchronized(sockLock) {
            boolean newManager = false;
            // other shared client could have destroyed it
            if (this.sockMgr == null || this.sockMgr.isDestroyed()) {
                newManager = true;
            } else {
                I2PSession sess = sockMgr.getSession();
                if (sess.isClosed() &&
                           Boolean.parseBoolean(getTunnel().getClientOptions().getProperty("i2cp.closeOnIdle")) &&
                           Boolean.parseBoolean(getTunnel().getClientOptions().getProperty("i2cp.newDestOnResume"))) {
                    // build a new socket manager and a new dest if the session is closed.
                    getTunnel().removeSession(sess);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getTunnel().getClientOptions().getProperty("inbound.nickname") + ": Built a new destination on resume");
                    // make sure the old one is closed
                    // if it's shared client, it will be destroyed in getSocketManager()
                    // with the correct locking
                    boolean shouldDestroy;
                    synchronized(I2PTunnelClientBase.class) {
                        shouldDestroy = sockMgr != socketManager;
                    }
                    if (shouldDestroy)
                        sockMgr.destroySocketManager();
                    newManager = true;
                }  // else the old socket manager will reconnect the old session if necessary
            }
            if (newManager) {
                if (_ownDest)
                    this.sockMgr = buildSocketManager();
                else
                    this.sockMgr = getSocketManager();
            }
        }
        connectManager();
    }

    /**
     * This is ONLY for shared clients.
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected I2PSocketManager getSocketManager() {
        return getSocketManager(getTunnel(), this.privKeyFile);
    }

    /**
     * This is ONLY for shared clients.
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static I2PSocketManager getSocketManager(I2PTunnel tunnel) {
        return getSocketManager(tunnel, null);
    }

    /**
     * This is ONLY for shared clients.
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static synchronized I2PSocketManager getSocketManager(I2PTunnel tunnel, String pkf) {
        // shadows instance _log
        Log _log = tunnel.getContext().logManager().getLog(I2PTunnelClientBase.class);
        if (socketManager != null && !socketManager.isDestroyed()) {
            I2PSession s = socketManager.getSession();
            if (s.isClosed() && _socketManagerState != SocketManagerState.INIT) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(tunnel.getClientOptions().getProperty("inbound.nickname") + ": Building a new socket manager since the old one closed [s=" + s + "]");
                tunnel.removeSession(s);
                // make sure the old one is closed
                socketManager.destroySocketManager();
                _socketManagerState = SocketManagerState.INIT;
                // We could be here a LONG time, holding the lock
                socketManager = buildSocketManager(tunnel, pkf);
                // FIXME may not be the right place for this
                I2PSession sub = addSubsession(tunnel);
                if (sub != null && _log.shouldLog(Log.WARN))
                    _log.warn("Added subsession " + sub);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info(tunnel.getClientOptions().getProperty("inbound.nickname") + ": Not building a new socket manager since the old one is open [s=" + s + "]");
                // If some other tunnel created the session, we need to add it
                // as our session too.
                // It's a Set in I2PTunnel
                tunnel.addSession(s);
            }
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(tunnel.getClientOptions().getProperty("inbound.nickname") + ": Building a new socket manager since there is no other one");
            socketManager = buildSocketManager(tunnel, pkf);
            I2PSession sub = addSubsession(tunnel);
            if (sub != null && _log.shouldLog(Log.WARN))
                _log.warn("Added subsession " + sub);
        }
        return socketManager;
    }

    /**
     *  Add a DSA_SHA1 subsession to the shared client if necessary.
     *
     *  @return subsession, or null if none was added
     *  @since 0.9.20
     */
    protected static synchronized I2PSession addSubsession(I2PTunnel tunnel) {
        I2PSession sess = socketManager.getSession();
        if (sess.getMyDestination().getSigType() == SigType.DSA_SHA1)
            return null;
        Properties props = new Properties();
        props.putAll(tunnel.getClientOptions());
        String name = props.getProperty("inbound.nickname");
        if (name != null)
            props.setProperty("inbound.nickname", name + " (DSA)");
        name = props.getProperty("outbound.nickname");
        if (name != null)
            props.setProperty("outbound.nickname", name + " (DSA)");
        props.setProperty(I2PClient.PROP_SIGTYPE, "DSA_SHA1");
        try {
            return socketManager.addSubsession(null, props);
        } catch (I2PSessionException ise) {
            Log log = tunnel.getContext().logManager().getLog(I2PTunnelClientBase.class);
            if (log.shouldLog(Log.WARN))
                log.warn("Failed to add subssession", ise);
            return null;
        }
    }

    /**
     *  Kill the shared client, so that on restart in android
     *  we won't latch onto the old one
     *
     *  @since 0.9.18
     */
    protected static synchronized void killSharedClient() {
        socketManager = null;
    }

    /**
     * For NON-SHARED clients (ownDest = true).
     *
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected I2PSocketManager buildSocketManager() {
        return buildSocketManager(getTunnel(), this.privKeyFile, this.l);
    }

    /**
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static I2PSocketManager buildSocketManager(I2PTunnel tunnel) {
        return buildSocketManager(tunnel, null);
    }

    private static final int RETRY_DELAY = 20*1000;
    private static final int MAX_RETRIES = 4;

    /**
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @param pkf absolute path or null
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static I2PSocketManager buildSocketManager(I2PTunnel tunnel, String pkf) {
        return buildSocketManager(tunnel, pkf, null);
    }

    /**
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @param pkf absolute path or null
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static I2PSocketManager buildSocketManager(I2PTunnel tunnel, String pkf, Logging log) {
        // shadows instance _log
        Log _log = tunnel.getContext().logManager().getLog(I2PTunnelClientBase.class);
        Properties props = new Properties();
        props.putAll(tunnel.getClientOptions());
        int portNum = 7654;
        if (tunnel.port != null) {
            try {
                portNum = Integer.parseInt(tunnel.port);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Invalid port specified [" + tunnel.port + "]", nfe);
            }
        }
        
        I2PSocketManager sockManager = null;
        FileInputStream fis = null;
        try {
            if (pkf != null) {
                // Persistent client dest
                fis = new FileInputStream(pkf);
                sockManager = I2PSocketManagerFactory.createDisconnectedManager(fis, tunnel.host, portNum, props);
            } else {
                sockManager = I2PSocketManagerFactory.createDisconnectedManager(null, tunnel.host, portNum, props);
            }
        } catch (I2PSessionException ise) {
            throw new IllegalArgumentException("Can't create socket manager", ise);
        } catch (IOException ioe) {
            if (log != null)
                log.log("Error opening key file " + ioe);
            _log.error("Error opening key file", ioe);
            throw new IllegalArgumentException("Error opening key file", ioe);
        } finally {
            if (fis != null)
                try { fis.close(); } catch (IOException ioe) {}
        }
        sockManager.setName("Client");
        if (_log.shouldLog(Log.INFO))
            _log.info(tunnel.getClientOptions().getProperty("inbound.nickname") + ": Built a new socket manager [s=" + sockManager.getSession() + "]");
        tunnel.addSession(sockManager.getSession());
        return sockManager;
    }


    /**
     * Warning, blocks while connecting to router and building tunnels;
     * This may take a LONG time.
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     * @since 0.9.20
     */
    private void connectManager() {
        int retries = 0;
        while (sockMgr.getSession().isClosed()) {
            try {
                sockMgr.getSession().connect();
                synchronized(I2PTunnelClientBase.class) {
                    if (sockMgr == socketManager)
                        _socketManagerState = SocketManagerState.CONNECTED;
                }
            } catch (I2PSessionException ise) {
                // shadows instance _log
                Log _log = getTunnel().getContext().logManager().getLog(I2PTunnelClientBase.class);
                Logging log = this.l;
                // try to make this error sensible as it will happen...
                String portNum = getTunnel().port;
                if (portNum == null)
                    portNum = "7654";
                String msg;
                if (getTunnel().getContext().isRouterContext())
                    msg = "Unable to build tunnels for the client";
                else
                    msg = "Unable to connect to the router at " + getTunnel().host + ':' + portNum +
                             " and build tunnels for the client";
                if (++retries < MAX_RETRIES) {
                    if (log != null)
                        log.log(msg + ", retrying in " + (RETRY_DELAY / 1000) + " seconds");
                    _log.error(msg + ", retrying in " + (RETRY_DELAY / 1000) + " seconds", ise);
                } else {
                    if (log != null)
                        log.log(msg + ", giving up");
                    _log.log(Log.CRIT, msg + ", giving up", ise);
                    throw new IllegalArgumentException(msg, ise);
                }
                try { Thread.sleep(RETRY_DELAY); } catch (InterruptedException ie) {}
            }
        }
    }

    public final int getLocalPort() {
        return localPort;
    }

    protected final InetAddress getListenHost(Logging l) {
        try {
            return InetAddress.getByName(getTunnel().listenHost);
        } catch (UnknownHostException uhe) {
            l.log("Could not find listen host to bind to [" + getTunnel().host + "]");
            _log.error("Error finding host to bind", uhe);
            notifyEvent("openBaseClientResult", "error");
            return null;
        }
    }

    /**
     * Actually open the local socket and start working on incoming connections.  *Must* be
     * called by derived classes after initialization.
     *
     * (this wasn't actually true until 0.9.20)
     *
     * This will be fast if i2cp.delayOpen is true, but could take
     * a LONG TIME if it is false, as it connects to the router and builds tunnels.
     *
     * Extending classes must check the value of boolean open after calling
     * super.startRunning(), if false then something went wrong.
     *
     */
    public void startRunning() {
        boolean openNow = !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty("i2cp.delayOpen"));
        if (openNow) {
            while (sockMgr == null) {
                verifySocketManager();
                if (sockMgr == null) {
                    _log.error("Unable to connect to router and build tunnels for " + _handlerName);
                    // FIXME there is a loop in buildSocketManager(), do we really need another one here?
                    // no matter, buildSocketManager() now throws an IllegalArgumentException
                    try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
                } else {
                    l.log("Tunnels ready for client: " + _handlerName);
                }
            }
            // can't be null unless we limit the loop above
            //if (sockMgr == null) {
            //    l.log("Invalid I2CP configuration");
            //    throw new IllegalArgumentException("Socket manager could not be created");
            //}

        } // else delay creating session until createI2PSocket() is called
        startup();
    }
        
    private void startup() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("startup " + _clientId, new Exception("I did it"));
        // prevent JVM exit when running outside the router
        boolean isDaemon = getTunnel().getContext().isRouterContext();
        open = true;
        Thread t = new I2PAppThread(this, "I2PTunnel Client " + getTunnel().listenHost + ':' + localPort, isDaemon);
        t.start();
        synchronized (this) {
            while (!listenerReady && open) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }

        if (open && listenerReady) {
            boolean openNow = !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty("i2cp.delayOpen"));
            if (openNow || chained)
                l.log("Client ready, listening on " + getTunnel().listenHost + ':' + localPort);
            else
                l.log("Client ready, listening on " + getTunnel().listenHost + ':' + localPort + ", delaying tunnel open until required");
            notifyEvent("openBaseClientResult", "ok");
        } else {
            l.log("Client error for " + getTunnel().listenHost + ':' + localPort + ", check logs");
            notifyEvent("openBaseClientResult", "error");
        }
        synchronized (startLock) {
            startRunning = true;
            startLock.notify();
        }
    }

    /** 
     * Create the default options (using the default timeout, etc).
     * Warning, this does not make a copy of I2PTunnel's client options,
     * it modifies them directly.
     */
    protected I2PSocketOptions getDefaultOptions() {
        Properties defaultOpts = getTunnel().getClientOptions();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        return opts;
    }
    
    /** 
     * Create the default options (using the default timeout, etc).
     * Warning, this does not make a copy of I2PTunnel's client options,
     * it modifies them directly.
     * Do not use overrides for per-socket options.
     */
    protected I2PSocketOptions getDefaultOptions(Properties overrides) {
        Properties defaultOpts = getTunnel().getClientOptions();
        defaultOpts.putAll(overrides);
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        return opts;
    }

    /**
     *  Update the I2PSocketManager.
     *
     *  @since 0.9.1
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel)
            return;
        I2PSocketManager sm = _ownDest ? sockMgr : socketManager;
        if (sm == null)
            return;
        Properties props = tunnel.getClientOptions();
        sm.setDefaultOptions(sm.buildOptions(props));
    }

    /**
     * Create a new I2PSocket towards to the specified destination,
     * adding it to the list of connections actually managed by this
     * tunnel.
     *
     * @param dest The destination to connect to, non-null
     * @return a new I2PSocket
     */
    public I2PSocket createI2PSocket(Destination dest) throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        return createI2PSocket(dest, 0);
    }

    /**
     * Create a new I2PSocket towards to the specified destination,
     * adding it to the list of connections actually managed by this
     * tunnel.
     *
     * @param dest The destination to connect to, non-null
     * @param port The destination port to connect to 0 - 65535
     * @return a new I2PSocket
     * @since 0.9.9
     */
    public I2PSocket createI2PSocket(Destination dest, int port)
                throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        verifySocketManager();
        I2PSocketOptions opts = getDefaultOptions();
        opts.setPort(port);
        return createI2PSocket(dest, opts);
    }

    /**
     * Create a new I2PSocket towards to the specified destination,
     * adding it to the list of connections actually managed by this
     * tunnel.
     *
     * @param dest The destination to connect to, non-null
     * @param opt Option to be used to open when opening the socket
     * @return a new I2PSocket
     *
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws InterruptedIOException if the connection timeouts
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket createI2PSocket(Destination dest, I2PSocketOptions opt) throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        if (dest == null)
            throw new NullPointerException();
        I2PSocket i2ps;

        verifySocketManager();
        i2ps = sockMgr.connect(dest, opt);
        synchronized (sockLock) {
            mySockets.add(i2ps);
        }

        return i2ps;
    }

    /**
     *  Non-final since 0.9.11.
     *  open will be true before being called.
     *  Any overrides must set listenerReady = true and then notifyAll() if setup is successful,
     *  and must call close() and then notifyAll() on failure or termination.
     */
    public void run() {
        InetAddress addr = getListenHost(l);
        if (addr == null) {
            close(true);
            open = false;
            synchronized (this) {
                notifyAll();
            }
            return;
        }
        try {
            Properties opts = getTunnel().getClientOptions();
            boolean useSSL = Boolean.parseBoolean(opts.getProperty(PROP_USE_SSL));
            if (useSSL) {
                // was already done in GeneralHelper.updateTunnelConfig() when saving the config
                // we should never be generating the cert here.
                // add the local interface and all targets to the cert
                Set<String> altNames = new HashSet<String>(4);
                String intfc = getTunnel().listenHost;
                if (intfc != null && !intfc.equals("0.0.0.0") && !intfc.equals("::") &&
                    !intfc.equals("0:0:0:0:0:0:0:0"))
                    altNames.add(intfc);
                // We can't easily get to the targetDestination property,
                // or the _addrs List in I2PTunnelClient, or the target argument in I2PTunnel from here,
                // but it shouldn't matter, we should never be generating the cert here.
                //String targets = ...
                //if (targets != null) {
                //    StringTokenizer tok = new StringTokenizer(targets, ", ");
                //    while (tok.hasMoreTokens()) {
                //        String h = tok.nextToken();
                //        int colon = h.indexOf(':');
                //        if (colon >= 0)
                //            h = h.substring(0, colon);
                //        altNames.add(h);
                //    }
                //}
                boolean wasCreated = SSLClientUtil.verifyKeyStore(opts, "", altNames);
                if (wasCreated) {
                    // From here, we can't save the config.
                    // We shouldn't get here, as SSL isn't the default, so it would
                    // be enabled via the GUI only.
                    // If it was done manually, the keys will be regenerated at every startup,
                    // which is bad.
                    _log.logAlways(Log.WARN, "Created new i2ptunnel SSL keys but can't save the config, disable and enable via i2ptunnel GUI");
                }
                SSLServerSocketFactory fact = SSLClientUtil.initializeFactory(opts);
                ss = fact.createServerSocket(localPort, 0, addr);
                I2PSSLSocketFactory.setProtocolsAndCiphers((SSLServerSocket) ss);
            } else {
                ss = new ServerSocket(localPort, 0, addr);
            }

            // If a free port was requested, find out what we got
            if (localPort == 0) {
                localPort = ss.getLocalPort();
            }
            notifyEvent("clientLocalPort", Integer.valueOf(ss.getLocalPort()));
            // duplicates message in constructor
            //l.log("Listening for clients on port " + localPort + " of " + getTunnel().listenHost);

            // Notify constructor that port is ready
            synchronized (this) {
                listenerReady = true;
                notifyAll();
            }

            // Wait until we are authorized to process data
            synchronized (startLock) {
                while (!startRunning) {
                    try {
                        startLock.wait();
                    } catch (InterruptedException ie) {
                    }
                }
            }

            TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
            if (tcg != null) {
                _executor = tcg.getClientExecutor();
            } else {
                // Fallback in case TCG.getInstance() is null, never instantiated
                // and we were not started by TCG.
                // Maybe a plugin loaded before TCG? Should be rare.
                // Never shut down.
                _executor = new TunnelControllerGroup.CustomThreadPoolExecutor();
            }
            while (open) {
                Socket s = ss.accept();
                manageConnection(s);
            }
        } catch (IOException ex) {
            synchronized (sockLock) {
                mySockets.clear();
            }
            if (open) {
                _log.error("Error listening for connections on " + addr + " port " + localPort, ex);
                l.log("Error listening for connections on " + addr + " port " + localPort + ": " + ex);
                notifyEvent("openBaseClientResult", "error");
                close(true);
            }
            synchronized (this) {
                notifyAll();
            }
        }
    }

    /**
     * Manage the connection just opened on the specified socket
     *
     * @param s Socket to take care of
     */
    protected void manageConnection(Socket s) {
        if (s == null) return;
        ThreadPoolExecutor tpe = _executor;
        if (tpe == null) {
            _log.error("No executor for socket!");
             try {
                 s.close();
             } catch (IOException ioe) {}
            return;
        }
        try {
            tpe.execute(new BlockingRunner(s));
        } catch (RejectedExecutionException ree) {
             // should never happen, we have an unbounded pool and never stop the executor
             try {
                 s.close();
             } catch (IOException ioe) {}
        }
    }

    /** 
     * Blocking runner, used during the connection establishment
     */
    private class BlockingRunner implements Runnable {
        private final Socket _s;
        public BlockingRunner(Socket s) { _s = s; }
        public void run() {
            try {
                clientConnectionRun(_s);
            } catch (Throwable t) {
                // probably an IllegalArgumentException from
                // connecting to the router in a delay-open or
                // close-on-idle tunnel (in connectManager() above)
                _log.error("Uncaught error in i2ptunnel client", t);
                try { _s.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    /**
     *  Note that the tunnel can be reopened after this by calling startRunning().
     *  This may not release all resources. In particular, the I2PSocketManager remains
     *  and it may have timer threads that continue running.
     *
     *  To release all resources permanently, call destroy().
     *
     *  Does nothing if open is already false.
     *  Sets open = false but does not notifyAll().
     *
     *  @return success
     */
    public boolean close(boolean forced) {
        if (_log.shouldLog(Log.INFO))
            _log.info("close() called: forced = " + forced + " open = " + open + " sockMgr = " + sockMgr);
        if (!open) return true;
        // FIXME: here we might have to wait quite a long time if
        // there is a connection attempt atm. But without waiting we
        // might risk to create an orphan socket. Would be better
        // to return with an error in that situation quickly.
        synchronized (sockLock) {
            if (sockMgr != null) {
                mySockets.retainAll(sockMgr.listSockets());
                if ((!forced) && (!mySockets.isEmpty())) {
                    l.log("Not closing, there are still active connections!");
                    _log.debug("can't close: there are still active connections!");
                    for (I2PSocket s : mySockets) {
                        l.log("  -> " + s.toString());
                    }
                    return false;
                }
                if (!chained) {
                    I2PSession session = sockMgr.getSession();
                    getTunnel().removeSession(session);
                    if (_ownDest) {
                        try {
                            session.destroySession();
                        } catch (I2PException ex) {}
                    }
                    // TCG will try to destroy it too
                } // else the app chaining to this one closes it!
            }
            l.log("Stopping client " + toString());
            open = false;
            try {
                if (ss != null) ss.close();
            } catch (IOException ex) {
                if (_log.shouldDebug())
                    _log.debug("error closing", ex);
                return false;
            }
            //l.log("Client closed.");
        }
        return true;
    }

    /**
     *  Note that the tunnel cannot be reopened after this by calling startRunning(),
     *  as it will destroy the underlying socket manager.
     *  This releases all resources if not a shared client.
     *  For shared client, the router will kill all the remaining streaming timers at shutdown.
     *
     *  @since 0.9.17
     */
    @Override
    public synchronized boolean destroy() {
        close(true);
        if (_ownDest) {
            I2PSocketManager sm = sockMgr;
            if (sm != null)
                sm.destroySocketManager();
        }
        return true;
    }

    public static void closeSocket(Socket s) {
        try {
            s.close();
        } catch (IOException ex) {
            //_log.error("Could not close socket", ex);
        }
    }
    
    /**
     * Manage a connection in a separate thread. This only works if
     * you do not override manageConnection().
     *
     * This is run in a thread from an unlimited-size thread pool,
     * so it may block or run indefinitely.
     */
    protected abstract void clientConnectionRun(Socket s);
}
