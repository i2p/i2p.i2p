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
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

public abstract class I2PTunnelClientBase extends I2PTunnelTask implements Runnable {

    protected final Log _log;
    protected final I2PAppContext _context;
    protected final Logging l;

    static final long DEFAULT_CONNECT_TIMEOUT = 60 * 1000;

    private static volatile long __clientId = 0;
    protected long _clientId;
    protected final Object sockLock = new Object(); // Guards sockMgr and mySockets
    protected I2PSocketManager sockMgr; // should be final and use a factory. LINT
    protected List mySockets = new ArrayList();
    protected boolean _ownDest;

    protected Destination dest = null;
    private int localPort;

    private boolean listenerReady = false;

    private ServerSocket ss;

    private final Object startLock = new Object();
    private boolean startRunning = false;

    // private Object closeLock = new Object();

    // private byte[] pubkey;

    private String handlerName;
    private String privKeyFile;

    // true if we are chained from a server.
    private boolean chained = false;

    /** how long to wait before dropping an idle thread */
    private static final long HANDLER_KEEPALIVE_MS = 2*60*1000;

    /**
     *  We keep a static pool of socket handlers for all clients,
     *  as there is no need for isolation on the client side.
     *  Extending classes may use it for other purposes.
     *  Not for use by servers, as there is no limit on threads.
     */
    private static ThreadPoolExecutor _executor;
    private static int _executorThreadCount;

    public I2PTunnelClientBase(int localPort, Logging l, I2PSocketManager sktMgr,
            I2PTunnel tunnel, EventDispatcher notifyThis, long clientId )
            throws IllegalArgumentException {
        super(localPort + " (uninitialized)", notifyThis, tunnel);
        chained = true;
        sockMgr = sktMgr;
        _clientId = clientId;
        this.localPort = localPort;
        this.l = l;
        this.handlerName = localPort + " #" + _clientId;
        _ownDest = true; // == ! shared client
        _context = tunnel.getContext();
        _context.statManager().createRateStat("i2ptunnel.client.closeBacklog", "How many pending sockets remain when we close one due to backlog?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.client.closeNoBacklog", "How many pending sockets remain when it was removed prior to backlog timeout?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.client.manageTime", "How long it takes to accept a socket and fire it into an i2ptunnel runner (or queue it for the pool)?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.client.buildRunTime", "How long it takes to run a queued socket into an i2ptunnel runner?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _log = _context.logManager().getLog(getClass());

        synchronized (I2PTunnelClientBase.class) {
            if (_executor == null)
                _executor = new CustomThreadPoolExecutor();
        }
        Thread t = new I2PAppThread(this, "Client " + tunnel.listenHost + ':' + localPort);
        listenerReady = false;
        t.start();
        open = true;
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
            l.log("Client ready, listening on " + tunnel.listenHost + ':' + localPort);
            notifyEvent("openBaseClientResult", "ok");
        } else {
            l.log("Client error for " + tunnel.listenHost + ':' + localPort + ", check logs");
            notifyEvent("openBaseClientResult", "error");
        }
    }

    public I2PTunnelClientBase(int localPort, boolean ownDest, Logging l, 
                               EventDispatcher notifyThis, String handlerName, 
                               I2PTunnel tunnel) throws IllegalArgumentException {
        this(localPort, ownDest, l, notifyThis, handlerName, tunnel, null);
    }

    /**
     * @param pkf null to generate a transient key
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelClientBase(int localPort, boolean ownDest, Logging l, 
                               EventDispatcher notifyThis, String handlerName, 
                               I2PTunnel tunnel, String pkf) throws IllegalArgumentException{
        super(localPort + " (uninitialized)", notifyThis, tunnel);
        _clientId = ++__clientId;
        this.localPort = localPort;
        this.l = l;
        this.handlerName = handlerName + _clientId;
        _ownDest = ownDest; // == ! shared client


        _context = tunnel.getContext();
        _context.statManager().createRateStat("i2ptunnel.client.closeBacklog", "How many pending sockets remain when we close one due to backlog?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.client.closeNoBacklog", "How many pending sockets remain when it was removed prior to backlog timeout?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.client.manageTime", "How long it takes to accept a socket and fire it into an i2ptunnel runner (or queue it for the pool)?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.client.buildRunTime", "How long it takes to run a queued socket into an i2ptunnel runner?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
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
        tunnel.getClientOptions().setProperty("i2cp.dontPublishLeaseSet", "true");
        
        boolean openNow = !Boolean.valueOf(tunnel.getClientOptions().getProperty("i2cp.delayOpen")).booleanValue();
        if (openNow) {
            while (sockMgr == null) {
                verifySocketManager();
                if (sockMgr == null) {
                    _log.error("Unable to connect to router and build tunnels for " + handlerName);
                    // FIXME there is a loop in buildSocketManager(), do we really need another one here?
                    // no matter, buildSocketManager() now throws an IllegalArgumentException
                    try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
                }
            }
            if (sockMgr == null) {
                l.log("Invalid I2CP configuration");
                throw new IllegalArgumentException("Socket manager could not be created");
            }
            l.log("Tunnels ready for client: " + handlerName);

        } // else delay creating session until createI2PSocket() is called
        
        Thread t = new I2PAppThread(this);
        t.setName("Client " + _clientId);
        listenerReady = false;
        t.start();
        open = true;
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
            if (openNow)
                l.log("Client ready, listening on " + tunnel.listenHost + ':' + localPort);
            else
                l.log("Client ready, listening on " + tunnel.listenHost + ':' + localPort + ", delaying tunnel open until required");
            notifyEvent("openBaseClientResult", "ok");
        } else {
            l.log("Client error for " + tunnel.listenHost + ':' + localPort + ", check logs");
            notifyEvent("openBaseClientResult", "error");
        }
    }
    
    /**
     * Sets the this.sockMgr field if it is null, or if we want a new one
     *
     * We need a socket manager before getDefaultOptions() and most other things
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected void verifySocketManager() {
        synchronized(sockLock) {
            boolean newManager = false;
            if (this.sockMgr == null) {
                newManager = true;
            } else {
                I2PSession sess = sockMgr.getSession();
                if (sess == null) {
                    newManager = true;
                } else if (sess.isClosed() &&
                           Boolean.valueOf(getTunnel().getClientOptions().getProperty("i2cp.closeOnIdle")).booleanValue() &&
                           Boolean.valueOf(getTunnel().getClientOptions().getProperty("i2cp.newDestOnResume")).booleanValue()) {
                    // build a new socket manager and a new dest if the session is closed.
                    getTunnel().removeSession(sess);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getTunnel().getClientOptions().getProperty("inbound.nickname") + ": Built a new destination on resume");
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
    }

    /** this is ONLY for shared clients */
    private static I2PSocketManager socketManager;


    /**
     * this is ONLY for shared clients
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected synchronized I2PSocketManager getSocketManager() {
        return getSocketManager(getTunnel(), this.privKeyFile);
    }

    /**
     * this is ONLY for shared clients
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static synchronized I2PSocketManager getSocketManager(I2PTunnel tunnel) {
        return getSocketManager(tunnel, null);
    }

    /**
     * this is ONLY for shared clients
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static synchronized I2PSocketManager getSocketManager(I2PTunnel tunnel, String pkf) {
        // shadows instance _log
        Log _log = tunnel.getContext().logManager().getLog(I2PTunnelClientBase.class);
        if (socketManager != null) {
            I2PSession s = socketManager.getSession();
            if ( (s == null) || (s.isClosed()) ) {
                if (_log.shouldLog(Log.INFO))
                    _log.info(tunnel.getClientOptions().getProperty("inbound.nickname") + ": Building a new socket manager since the old one closed [s=" + s + "]");
                if (s != null)
                    tunnel.removeSession(s);
                socketManager = buildSocketManager(tunnel, pkf);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info(tunnel.getClientOptions().getProperty("inbound.nickname") + ": Not building a new socket manager since the old one is open [s=" + s + "]");
            }
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(tunnel.getClientOptions().getProperty("inbound.nickname") + ": Building a new socket manager since there is no other one");
            socketManager = buildSocketManager(tunnel, pkf);
        }
        return socketManager;
    }

    /**
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected I2PSocketManager buildSocketManager() {
        return buildSocketManager(getTunnel(), this.privKeyFile, this.l);
    }
    /**
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
     * @param pkf absolute path or null
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static I2PSocketManager buildSocketManager(I2PTunnel tunnel, String pkf) {
        return buildSocketManager(tunnel, pkf, null);
    }

    /**
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
                _log.log(Log.CRIT, "Invalid port specified [" + tunnel.port + "], reverting to " + portNum);
            }
        }
        
        I2PSocketManager sockManager = null;
        // Todo: Can't stop a tunnel from the UI while it's in this loop (no session yet)
        int retries = 0;
        while (sockManager == null) {
            if (pkf != null) {
                // Persistent client dest
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(pkf);
                    sockManager = I2PSocketManagerFactory.createManager(fis, tunnel.host, portNum, props);
                } catch (IOException ioe) {
                    if (log != null)
                        log.log("Error opening key file " + ioe);
                    _log.error("Error opening key file", ioe);
                    throw new IllegalArgumentException("Error opening key file " + ioe);
                } finally {
                    if (fis != null)
                        try { fis.close(); } catch (IOException ioe) {}
                }
            } else {
                sockManager = I2PSocketManagerFactory.createManager(tunnel.host, portNum, props);
            }
            
            if (sockManager == null) {
                // try to make this error sensible as it will happen... sadly we can't get to the listenPort, only the listenHost
                String msg = "Unable to connect to the router at " + tunnel.host + ':' + portNum +
                             " and build tunnels for the client";
                if (++retries < MAX_RETRIES) {
                    if (log != null)
                        log.log(msg + ", retrying in " + (RETRY_DELAY / 1000) + " seconds");
                    _log.error(msg + ", retrying in " + (RETRY_DELAY / 1000) + " seconds");
                } else {
                    if (log != null)
                        log.log(msg + ", giving up");
                    _log.log(Log.CRIT, msg + ", giving up");
                    // not clear if callers can handle null
                    //return null;
                    throw new IllegalArgumentException(msg);
                }
                try { Thread.sleep(RETRY_DELAY); } catch (InterruptedException ie) {}
            }
        }
        sockManager.setName("Client");
        if (_log.shouldLog(Log.INFO))
            _log.info(tunnel.getClientOptions().getProperty("inbound.nickname") + ": Built a new socket manager [s=" + sockManager.getSession() + "]");
        tunnel.addSession(sockManager.getSession());
        return sockManager;
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
     * Actually start working on incoming connections.  *Must* be
     * called by derived classes after initialization.
     *
     */
    public void startRunning() {
        synchronized (startLock) {
            startRunning = true;
            startLock.notify();
        }
    }

    /** 
     * create the default options (using the default timeout, etc)
     *
     */
    protected I2PSocketOptions getDefaultOptions() {
        Properties defaultOpts = getTunnel().getClientOptions();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT))
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        return opts;
    }
    
    /** 
     * create the default options (using the default timeout, etc)
     *
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
     * Create a new I2PSocket towards to the specified destination,
     * adding it to the list of connections actually managed by this
     * tunnel.
     *
     * @param dest The destination to connect to
     * @return a new I2PSocket
     */
    public I2PSocket createI2PSocket(Destination dest) throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        verifySocketManager();
        return createI2PSocket(dest, getDefaultOptions());
    }

    /**
     * Create a new I2PSocket towards to the specified destination,
     * adding it to the list of connections actually managed by this
     * tunnel.
     *
     * @param dest The destination to connect to
     * @param opt Option to be used to open when opening the socket
     * @return a new I2PSocket
     *
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws InterruptedIOException if the connection timeouts
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket createI2PSocket(Destination dest, I2PSocketOptions opt) throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        I2PSocket i2ps;

        verifySocketManager();
        i2ps = sockMgr.connect(dest, opt);
        synchronized (sockLock) {
            mySockets.add(i2ps);
        }

        return i2ps;
    }

    public final void run() {
        try {
            InetAddress addr = getListenHost(l);
            if (addr == null) {
                open = false;
                synchronized (this) {
                    notifyAll();
                }
                return;
            }
            ss = new ServerSocket(localPort, 0, addr);

            // If a free port was requested, find out what we got
            if (localPort == 0) {
                localPort = ss.getLocalPort();
            }
            notifyEvent("clientLocalPort", new Integer(ss.getLocalPort()));
            // duplicates message in constructor
            //l.log("Listening for clients on port " + localPort + " of " + getTunnel().listenHost);

            // Notify constructor that port is ready
            synchronized (this) {
                listenerReady = true;
                notify();
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

            while (open) {
                Socket s = ss.accept();
                manageConnection(s);
            }
        } catch (IOException ex) {
            if (open) {
                _log.error("Error listening for connections on " + localPort, ex);
                notifyEvent("openBaseClientResult", "error");
            }
            synchronized (sockLock) {
                mySockets.clear();
            }
            open = false;
            synchronized (this) {
                notifyAll();
            }
        }
    }

    /**
     *  @return may be null if no class has been instantiated
     *  @since 0.8.8
     */
    static ThreadPoolExecutor getClientExecutor() {
        return _executor;
    }

    /**
     *  @since 0.8.8
     */
    static void killClientExecutor() {
        synchronized (I2PTunnelClientBase.class) {
            if (_executor != null) {
                _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
                _executor.shutdownNow();
                _executor = null;
            }
            // kill the shared client, so that on restart in android
            // we won't latch onto the old one
            socketManager = null;
        }
    }

    /**
     * Manage the connection just opened on the specified socket
     *
     * @param s Socket to take care of
     */
    protected void manageConnection(Socket s) {
        if (s == null) return;
        try {
            _executor.execute(new BlockingRunner(s));
        } catch (RejectedExecutionException ree) {
             // should never happen, we have an unbounded pool and never stop the executor
             try {
                 s.close();
             } catch (IOException ioe) {}
        }
    }

    /**
     * Not really needed for now but in case we want to add some hooks like afterExecute().
     */
    private static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        public CustomThreadPoolExecutor() {
             super(0, Integer.MAX_VALUE, HANDLER_KEEPALIVE_MS, TimeUnit.MILLISECONDS,
                   new SynchronousQueue(), new CustomThreadFactory());
        }
    }

    /** just to set the name and set Daemon */
    private static class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName("I2PTunnel Client Runner " + (++_executorThreadCount));
            rv.setDaemon(true);
            return rv;
        }
    }

    /** 
     * Blocking runner, used during the connection establishment
     */
    private class BlockingRunner implements Runnable {
        private Socket _s;
        public BlockingRunner(Socket s) { _s = s; }
        public void run() {
            clientConnectionRun(_s);
        }
    }
    
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
                if (!forced && mySockets.size() != 0) {
                    l.log("There are still active connections!");
                    _log.debug("can't close: there are still active connections!");
                    for (Iterator it = mySockets.iterator(); it.hasNext();) {
                        l.log("->" + it.next());
                    }
                    return false;
                }
                if (!chained) {
                    I2PSession session = sockMgr.getSession();
                    if (session != null) {
                        getTunnel().removeSession(session);
                    }
                } // else the app chaining to this one closes it!
            }
            l.log("Stopping client " + toString());
            open = false;
            try {
                if (ss != null) ss.close();
            } catch (IOException ex) {
                ex.printStackTrace();
                return false;
            }
            //l.log("Client closed.");
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
     * you do not override manageConnection()
     */
    protected abstract void clientConnectionRun(Socket s);
}
