/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.RouterRestartException;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;

public class I2PTunnelServer extends I2PTunnelTask implements Runnable {

    protected final Log _log;
    protected final I2PSocketManager sockMgr;
    protected volatile I2PServerSocket i2pss;

    private final Object lock = new Object();
    protected final Object slock = new Object();
    protected final Object sslLock = new Object();

    protected InetAddress remoteHost;
    protected int remotePort;
    private final boolean _usePool;
    protected final Logging l;
    private I2PSSLSocketFactory _sslFactory;

    private static final long DEFAULT_READ_TIMEOUT = 5*60*1000;
    /** default timeout to 5 minutes - override if desired */
    protected long readTimeout = DEFAULT_READ_TIMEOUT;

    /** do we use threads? default true (ignored for standard servers, always false) */
    private static final String PROP_USE_POOL = "i2ptunnel.usePool";
    private static final boolean DEFAULT_USE_POOL = true;
    public static final String PROP_USE_SSL = "useSSL";
    public static final String PROP_UNIQUE_LOCAL = "enableUniqueLocal";
    /** @since 0.9.30 */
    public static final String PROP_ALT_PKF = "altPrivKeyFile";
    /** apparently unused */
    protected static volatile long __serverId = 0;
    /** max number of threads  - this many slowlorisses will DOS this server, but too high could OOM the JVM */
    private static final String PROP_HANDLER_COUNT = "i2ptunnel.blockingHandlerCount";
    private static final int DEFAULT_HANDLER_COUNT = 65;
    /** min number of threads */
    private static final int MIN_HANDLERS = 0;
    /** how long to wait before dropping an idle thread */
    private static final long HANDLER_KEEPALIVE_MS = 30*1000;

    protected I2PTunnelTask task;
    protected boolean bidir;
    private ThreadPoolExecutor _executor;
    protected volatile ThreadPoolExecutor _clientExecutor;
    private final Map<Integer, InetSocketAddress> _socketMap = new ConcurrentHashMap<Integer, InetSocketAddress>(4);

    /** unused? port should always be specified */
    private int DEFAULT_LOCALPORT = 4488;
    protected int localPort = DEFAULT_LOCALPORT;

    /**
     *  Non-blocking
     *
     * @param privData Base64-encoded private key data,
     *                 format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelServer(InetAddress host, int port, String privData, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("Server at " + host + ':' + port, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(getClass());
        ByteArrayInputStream bais = new ByteArrayInputStream(Base64.decode(privData));
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
        _usePool = getUsePool();
        buildSocketMap(tunnel.getClientOptions());
        sockMgr = createManager(bais);
    }

    /**
     *  Non-blocking
     *
     * @param privkey file containing the private key data,
     *                format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param privkeyname the name of the privKey file, just for logging
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelServer(InetAddress host, int port, File privkey, String privkeyname, Logging l,
                           EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("Server at " + host + ':' + port, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(getClass());
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
        _usePool = getUsePool();
        buildSocketMap(tunnel.getClientOptions());
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(privkey);
            sockMgr = createManager(fis);
        } catch (IOException ioe) {
            _log.error("Cannot read private key data for " + privkeyname, ioe);
            notifyEvent("openServerResult", "error");
            throw new IllegalArgumentException("Error starting server", ioe);
        } finally {
            if (fis != null)
                try { fis.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Non-blocking
     *
     * @param privData stream containing the private key data,
     *                 format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param privkeyname the name of the privKey file, just for logging
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelServer(InetAddress host, int port, InputStream privData, String privkeyname, Logging l,  EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("Server at " + host + ':' + port, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(getClass());
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
        _usePool = getUsePool();
        buildSocketMap(tunnel.getClientOptions());
        sockMgr = createManager(privData);
    }

    /**
     *  Non-blocking
     *
     *  @param sktMgr the existing socket manager
     *  @since 0.8.9
     */
    public I2PTunnelServer(InetAddress host, int port, I2PSocketManager sktMgr,
                           Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("Server at " + host + ':' + port, notifyThis, tunnel);
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
        _log = tunnel.getContext().logManager().getLog(getClass());
        _usePool = false;
        buildSocketMap(tunnel.getClientOptions());
        sockMgr = sktMgr;
        open = true;
    }

    /** @since 0.9.8 */
    private boolean getUsePool() {
        // extending classes default to threaded, but for a standard server, we can't get slowlorissed
        boolean rv = !getClass().equals(I2PTunnelServer.class);
        if (rv) {
            String usePool = getTunnel().getClientOptions().getProperty(PROP_USE_POOL);
            if (usePool != null)
                rv = Boolean.parseBoolean(usePool);
            else
                rv = DEFAULT_USE_POOL;
        }
        return rv;
    }

    private static final int RETRY_DELAY = 20*1000;
    private static final int MAX_RETRIES = 4;

    /**
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     * @since 0.9.8
     */
    private I2PSocketManager createManager(InputStream privData) {
        Properties props = new Properties();
        props.putAll(getTunnel().getClientOptions());
        int portNum = 7654;
        if (getTunnel().port != null) {
            try {
                portNum = Integer.parseInt(getTunnel().port);
            } catch (NumberFormatException nfe) {
                _log.error("Invalid port specified [" + getTunnel().port + "], reverting to " + portNum);
            }
        }
        try {
            I2PSocketManager rv = I2PSocketManagerFactory.createDisconnectedManager(privData, getTunnel().host,
                                                                                    portNum, props);
            rv.setName("I2PTunnel Server");
            getTunnel().addSession(rv.getSession());
            String alt = props.getProperty(PROP_ALT_PKF);
            if (alt != null)
                addSubsession(rv, alt);
            return rv;
        } catch (I2PSessionException ise) {
            throw new IllegalArgumentException("Can't create socket manager", ise);
        } finally {
            try { privData.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Add a non-DSA_SHA1 subsession to the DSA_SHA1 server if necessary.
     *
     *  @return subsession, or null if none was added
     *  @since 0.9.30
     */
    private I2PSession addSubsession(I2PSocketManager sMgr, String alt) {
        File altFile = TunnelController.filenameToFile(alt);
        if (altFile == null)
            return null;
        I2PSession sess = sMgr.getSession();
        if (sess.getMyDestination().getSigType() != SigType.DSA_SHA1)
            return null;
        Properties props = new Properties();
        props.putAll(getTunnel().getClientOptions());
        // fixme get actual sig type
        String name = props.getProperty("inbound.nickname");
        if (name != null)
            props.setProperty("inbound.nickname", name + " (EdDSA)");
        name = props.getProperty("outbound.nickname");
        if (name != null)
            props.setProperty("outbound.nickname", name + " (EdDSA)");
        props.setProperty(I2PClient.PROP_SIGTYPE, "EdDSA_SHA512_Ed25519");
        FileInputStream privData = null;
        try {
            privData = new FileInputStream(altFile);
            return sMgr.addSubsession(privData, props);
        } catch (IOException ioe) {
            _log.error("Failed to add subssession", ioe);
            return null;
        } catch (I2PSessionException ise) {
            _log.error("Failed to add subssession", ise);
            return null;
        } finally {
            if (privData != null) try { privData.close(); } catch (IOException ioe) {}
        }
    }


    /**
     * Warning, blocks while connecting to router and building tunnels;
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     * @since 0.9.8
     */
    private void connectManager() {
        int retries = 0;
        while (sockMgr.getSession().isClosed()) {
            try {
                sockMgr.getSession().connect();
                // Now connect the subsessions, if any
                List<I2PSession> subs = sockMgr.getSubsessions();
                if (!subs.isEmpty()) {
                    for (I2PSession sub : subs) {
                        try {
                            sub.connect();
                            if (_log.shouldInfo())
                                _log.info("Connected subsession " + sub);
                        } catch (I2PSessionException ise) {
                            // not fatal?
                            String msg = "Unable to connect subsession " + sub;
                            this.l.log(msg);
                            _log.error(msg, ise);
                        }
                    }
                }
            } catch (I2PSessionException ise) {
                // try to make this error sensible as it will happen...
                String portNum = getTunnel().port;
                if (portNum == null)
                    portNum = "7654";
                String msg;
                if (getTunnel().getContext().isRouterContext())
                    msg = "Unable to build tunnels for the server at " + remoteHost.getHostAddress() + ':' + remotePort;
                else
                    msg = "Unable to connect to the router at " + getTunnel().host + ':' + portNum +
                             " and build tunnels for the server at " + remoteHost.getHostAddress() + ':' + remotePort;
                if (++retries < MAX_RETRIES) {
                    msg += ", retrying in " + (RETRY_DELAY / 1000) + " seconds";
                    this.l.log(msg);
                    _log.error(msg);
                } else {
                    msg += ", giving up";
                    this.l.log(msg);
                    _log.log(Log.CRIT, msg, ise);
                    throw new IllegalArgumentException(msg, ise);
                }
                try { Thread.sleep(RETRY_DELAY); } catch (InterruptedException ie) {}
            }
        }

        l.log("Tunnels ready for server at " + remoteHost.getHostAddress() + ':' + remotePort);
        notifyEvent("openServerResult", "ok");
        open = true;
    }

    /**
     *  Copy input stream to a byte array, so we can retry
     *  @since 0.7.10
     */
/****
    private static ByteArrayInputStream copyOfInputStream(InputStream is) throws IOException {
        byte[] buf = new byte[128];
        ByteArrayOutputStream os = new ByteArrayOutputStream(768);
        try {
            int read;
            while ((read = is.read(buf)) >= 0) {
                os.write(buf, 0, read);
            }
        } finally {
             try { is.close(); } catch (IOException ioe) {}
             // don't need to close BAOS
        }
        return new ByteArrayInputStream(os.toByteArray());
    }
****/
    
    /**
     * Start running the I2PTunnelServer.
     * Warning, blocks while connecting to router and building tunnels;
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public synchronized void startRunning() {
        connectManager();
        // prevent JVM exit when running outside the router
        boolean isDaemon = getTunnel().getContext().isRouterContext();
        Thread t = new I2PAppThread(this, "Server " + remoteHost + ':' + remotePort, isDaemon);
        t.start();
    }

    /**
     * Set the read idle timeout for newly-created connections (in
     * milliseconds).  After this time expires without data being reached from
     * the I2P network, the connection itself will be closed.
     */
    public void setReadTimeout(long ms) {
        readTimeout = ms;
    }
    
    /**
     * Get the read idle timeout for newly-created connections (in
     * milliseconds).
     *
     * @return The read timeout used for connections
     */
    public long getReadTimeout() {
        return readTimeout;
    }

    /**
     *  Note that the tunnel can be reopened after this by calling startRunning().
     *  This does not release all resources. In particular, the I2PSocketManager remains
     *  and it may have timer threads that continue running.
     *
     *  To release all resources permanently, call destroy().
     */
    public synchronized boolean close(boolean forced) {
        if (!open) return true;
        if (task != null) {
            task.close(forced);
        }
        synchronized (lock) {
            if (!forced && sockMgr.listSockets().size() != 0) {
                l.log("There are still active connections!");
                for (I2PSocket skt : sockMgr.listSockets()) {
                    l.log("->" + skt);
                }
                return false;
            }
            l.log("Stopping tunnels for server at " + this.remoteHost + ':' + this.remotePort);
            open = false;
            try {
                if (i2pss != null) {
                    i2pss.close();
                    i2pss = null;
                }
                I2PSession session = sockMgr.getSession();
                getTunnel().removeSession(session);
                session.destroySession();
            } catch (I2PException ex) {
                _log.error("Error destroying the session", ex);
                //System.exit(1);
            }
            //l.log("Server shut down.");
            if (_usePool && _executor != null) {
                _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
                _executor.shutdownNow();
            }
            return true;
        }
    }

    /**
     *  Note that the tunnel cannot be reopened after this by calling startRunning(),
     *  as it will destroy the underlying socket manager.
     *  This releases all resources.
     *
     *  @since 0.9.17
     */
    @Override
    public synchronized boolean destroy() {
        close(true);
        sockMgr.destroySocketManager();
        return true;
    }

    /**
     *  Update the I2PSocketManager.
     *  And since 0.9.15, the target host and port.
     *
     *  @since 0.9.1
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel || sockMgr == null)
            return;
        Properties props = tunnel.getClientOptions();
        sockMgr.setDefaultOptions(sockMgr.buildOptions(props));
        // see TunnelController.setSessionOptions()
        String h = props.getProperty(TunnelController.PROP_TARGET_HOST);
        if (h != null) {
            try {
                remoteHost = InetAddress.getByName(h);
            } catch (UnknownHostException uhe) {
                l.log("Unknown host: " + h);
            }
        }
        String p = props.getProperty(TunnelController.PROP_TARGET_PORT);
        if (p != null) {
            try {
                int port = Integer.parseInt(p);
                if (port > 0 && port <= 65535)
                    remotePort = port;
                else
                    l.log("Bad port: " + port);
            } catch (NumberFormatException nfe) {
                l.log("Bad port: " + p);
            }
        }
        buildSocketMap(props);
    }

    /**
     *  Update the ports map.
     *
     *  @since 0.9.9
     */
    private void buildSocketMap(Properties props) {
        _socketMap.clear();
        for (Map.Entry<Object, Object> e : props.entrySet()) {
            String key = (String) e.getKey();
            if (key.startsWith("targetForPort.")) {
                key = key.substring("targetForPort.".length());
                try {
                    int myPort = Integer.parseInt(key);
                    String host = (String) e.getValue();
                    int colon = host.indexOf(':');
                    int port = Integer.parseInt(host.substring(colon + 1));
                    host = host.substring(0, colon);
                    InetSocketAddress isa = new InetSocketAddress(host, port);
                    if (isa.isUnresolved())
                        l.log("Warning - cannot resolve address for port " + key + ": " + host);
                    _socketMap.put(Integer.valueOf(myPort), isa);
                } catch (NumberFormatException nfe) {
                    l.log("Bad socket spec for port " + key + ": " + e.getValue());
                } catch (IndexOutOfBoundsException ioobe) {
                    l.log("Bad socket spec for port " + key + ": " + e.getValue());
                }
            }
        }
    }

    protected int getHandlerCount() { 
        int rv = DEFAULT_HANDLER_COUNT;
        String cnt = getTunnel().getClientOptions().getProperty(PROP_HANDLER_COUNT);
        if (cnt != null) {
            try {
                rv = Integer.parseInt(cnt);
                if (rv <= 0)
                    rv = DEFAULT_HANDLER_COUNT;
            } catch (NumberFormatException nfe) {}
        }
        return rv;
    }
    
    /**
     *  If usePool is set, this starts the executor pool.
     *  Then, do the accept() loop, and either
     *  hands each I2P socket to the executor or runs it in-line.
     */
    public void run() {
        i2pss = sockMgr.getServerSocket();
        if (_log.shouldLog(Log.WARN)) {
            if (_usePool)
                _log.warn("Starting executor with " + getHandlerCount() + " threads max");
            else
                _log.warn("Threads disabled, running blockingHandles inline");
        }
        if (_usePool) {
            _executor = new CustomThreadPoolExecutor(getHandlerCount(), "ServerHandler pool " + remoteHost + ':' + remotePort);
        }
        TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
        if (tcg != null) {
            _clientExecutor = tcg.getClientExecutor();
        } else {
            // Fallback in case TCG.getInstance() is null, never instantiated
            // and we were not started by TCG.
            // Maybe a plugin loaded before TCG? Should be rare.
            // Never shut down.
            _clientExecutor = new TunnelControllerGroup.CustomThreadPoolExecutor();
        }
        I2PSocket i2ps = null;
        while (open) {
            try {
                i2ps = null;
                I2PServerSocket ci2pss = i2pss;
                if (ci2pss == null)
                    throw new I2PException("I2PServerSocket closed");
                // returns non-null as of 0.9.17
                i2ps = ci2pss.accept();
                if (_usePool) {
                    try {
                        _executor.execute(new Handler(i2ps));
                    } catch (RejectedExecutionException ree) {
                         try {
                             i2ps.reset();
                         } catch (IOException ioe) {}
                         if (open)
                             _log.logAlways(Log.WARN, "ServerHandler queue full, dropping incoming connection to " +
                                        remoteHost + ':' + remotePort +
                                        "; increase server max threads or " + PROP_HANDLER_COUNT +
                                        "; current is " + getHandlerCount());
                    }
                } else {
                    // use only for standard servers that can't get slowlorissed! Not for http or irc
                    blockingHandle(i2ps);
                }
            } catch (RouterRestartException rre) {
                // Delay and loop if router is soft restarting
                _log.logAlways(Log.WARN, "Waiting for router restart");
                if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                try {
                    Thread.sleep(2*60*1000);
                } catch (InterruptedException ie) {}
                // This should be the same as before, but we have to call getServerSocket()
                // so sockMgr will call ConnectionManager.setAllowIncomingConnections(true) again
                i2pss = sockMgr.getServerSocket();
            } catch (I2PException ipe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error accepting - KILLING THE TUNNEL SERVER", ipe);
                open = false;
                if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                break;
            } catch (ConnectException ce) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error accepting", ce);
                if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                try {
                    Thread.sleep(2*60*1000);
                } catch (InterruptedException ie) {}
                // Server socket possbily closed out from under us, perhaps as part of a router restart;
                // wait a while and try to get a new socket
                i2pss = sockMgr.getServerSocket();
            } catch(SocketTimeoutException ste) {
                // ignored, we never set the timeout
                if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
            } catch (RuntimeException e) {
                // streaming borkage
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Uncaught exception accepting", e);
                if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                // not killing the server..
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {}
            }
        }
        if (_executor != null && !_executor.isTerminating() && !_executor.isShutdown())
            _executor.shutdownNow();
    }
    
    /**
     * Not really needed for now but in case we want to add some hooks like afterExecute().
     */
    private static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        public CustomThreadPoolExecutor(int max, String name) {
             super(MIN_HANDLERS, max, HANDLER_KEEPALIVE_MS, TimeUnit.MILLISECONDS,
                   new SynchronousQueue<Runnable>(), new CustomThreadFactory(name));
        }
    }

    /** just to set the name and set Daemon */
    private static class CustomThreadFactory implements ThreadFactory {
        private final String _name;

        public CustomThreadFactory(String name) {
            _name = name;
        }

        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(_name);
            rv.setDaemon(true);
            return rv;
        }
    }

    public boolean shouldUsePool() { return _usePool; }
    
    /**
     * Run the blockingHandler.
     */
    private class Handler implements Runnable { 
        private final I2PSocket _i2ps;

        public Handler(I2PSocket socket) {
            _i2ps = socket;
        }

        public void run() {
            try {
                blockingHandle(_i2ps);   
            } catch (Throwable t) {
                _log.error("Uncaught error in i2ptunnel server", t);
            }
        }
    }
    
    /**
     *  This is run in a thread from a limited-size thread pool via Handler.run(),
     *  except for a standard server (this class, no extension, as determined in getUsePool()),
     *  it is run directly in the acceptor thread (see run()).
     *
     *  In either case, this method and any overrides must spawn a thread and return quickly.
     *  If blocking while reading the headers (as in HTTP and IRC), the thread pool
     *  may be exhausted.
     *
     *  See PROP_USE_POOL, DEFAULT_USE_POOL, PROP_HANDLER_COUNT, DEFAULT_HANDLER_COUNT
     */
    protected void blockingHandle(I2PSocket socket) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Incoming connection to '" + toString() + "' port " + socket.getLocalPort() +
                      " from: " + socket.getPeerDestination().calculateHash() + " port " + socket.getPort());
        long afterAccept = getTunnel().getContext().clock().now();
        long afterSocket = -1;
        //local is fast, so synchronously. Does not need that many
        //threads.
        try {
            socket.setReadTimeout(readTimeout);
            Socket s = getSocket(socket.getPeerDestination().calculateHash(), socket.getLocalPort());
            afterSocket = getTunnel().getContext().clock().now();
            Thread t = new I2PTunnelRunner(s, socket, slock, null, null,
                                           null, (I2PTunnelRunner.FailCallback) null);
            // run in the unlimited client pool
            //t.start();
            _clientExecutor.execute(t);

            long afterHandle = getTunnel().getContext().clock().now();
            long timeToHandle = afterHandle - afterAccept;
            if ( (timeToHandle > 1000) && (_log.shouldLog(Log.WARN)) )
                _log.warn("Took a while to handle the request for " + remoteHost + ':' + remotePort +
                          " [" + timeToHandle + ", socket create: " + (afterSocket-afterAccept) + "]");
        } catch (SocketException ex) {
            try {
                socket.reset();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error connecting to server " + remoteHost + ':' + remotePort, ex);
        } catch (IOException ex) {
            _log.error("Error while waiting for I2PConnections", ex);
        }
    }

    /**
     *  Get a regular or SSL socket depending on config and the incoming port.
     *  To configure a specific host:port as the server for incoming port xx,
     *  set option targetForPort.xx=host:port
     *
     *  @param from may be used to construct local address since 0.9.13
     *  @since 0.9.9
     */
    protected Socket getSocket(Hash from, int incomingPort) throws IOException {
        InetAddress host = remoteHost;
        int port = remotePort;
        if (incomingPort != 0 && !_socketMap.isEmpty()) {
            InetSocketAddress isa = _socketMap.get(Integer.valueOf(incomingPort));
            if (isa != null) {
                host = isa.getAddress();
                if (host == null)
                    throw new IOException("Cannot resolve " + isa.getHostName());
                port = isa.getPort();
            }
        }
        return getSocket(from, host, port);
    }

    /**
     *  Get a regular or SSL socket depending on config.
     *  The SSL config applies to all hosts/ports.
     *
     *  @param from may be used to construct local address since 0.9.13
     *  @since 0.9.9
     */
    protected Socket getSocket(Hash from, InetAddress remoteHost, int remotePort) throws IOException {
        String opt = getTunnel().getClientOptions().getProperty(PROP_USE_SSL);
        if (Boolean.parseBoolean(opt)) {
            synchronized(sslLock) {
                if (_sslFactory == null) {
                    try {
                        _sslFactory = new I2PSSLSocketFactory(getTunnel().getContext(),
                                                               true, "certificates/i2ptunnel");
                    } catch (GeneralSecurityException gse) {
                        IOException ioe = new IOException("SSL Fail");
                        ioe.initCause(gse);
                        throw ioe;
                    }
                }
            }
            return _sslFactory.createSocket(remoteHost, remotePort);
        } else {
            // as suggested in https://lists.torproject.org/pipermail/tor-dev/2014-March/006576.html
            boolean unique = Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_UNIQUE_LOCAL));
            if (unique && remoteHost.isLoopbackAddress()) {
                byte[] addr;
                if (remoteHost instanceof Inet4Address) {
                    addr = new byte[4];
                    addr[0] = 127;
                    System.arraycopy(from.getData(), 0, addr, 1, 3);
                } else {
                    addr = new byte[16];
                    addr[0] = (byte) 0xfd;
                    System.arraycopy(from.getData(), 0, addr, 1, 15);
                }
                InetAddress local = InetAddress.getByAddress(addr);
                // Javadocs say local port of 0 allowed in Java 7.
                // Not clear if supported in Java 6 or not.
                return new Socket(remoteHost, remotePort, local, 0);
            } else {
                return new Socket(remoteHost, remotePort);
            }
        }
    }
}

