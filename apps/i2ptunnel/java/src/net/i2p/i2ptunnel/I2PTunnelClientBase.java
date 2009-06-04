/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

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

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

public abstract class I2PTunnelClientBase extends I2PTunnelTask implements Runnable {

    private static final Log _log = new Log(I2PTunnelClientBase.class);
    protected I2PAppContext _context;
    protected Logging l;

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

    // private Object conLock = new Object();
    
    /** List of Socket for those accept()ed but not yet started up */
    private List _waitingSockets = new ArrayList(); // should be final and use a factory. LINT
    /** How many connections will we allow to be in the process of being built at once? */
    private int _numConnectionBuilders;
    /** How long will we allow sockets to sit in the _waitingSockets map before killing them? */
    private int _maxWaitTime;
    
    /**
     * How many concurrent connections this I2PTunnel instance will allow to be 
     * in the process of connecting (or if less than 1, there is no limit)?
     */
    public static final String PROP_NUM_CONNECTION_BUILDERS = "i2ptunnel.numConnectionBuilders";
    /**
     * How long will we let a socket wait after being accept()ed without getting
     * pumped through a connection builder (in milliseconds).  If this time is 
     * reached, the socket is unceremoniously closed and discarded.  If the max 
     * wait time is less than 1, there is no limit.
     *
     */
    public static final String PROP_MAX_WAIT_TIME = "i2ptunnel.maxWaitTime";
    
    private static final int DEFAULT_NUM_CONNECTION_BUILDERS = 5;
    private static final int DEFAULT_MAX_WAIT_TIME = 30*1000;
    
    //public I2PTunnelClientBase(int localPort, boolean ownDest,
    //		       Logging l) {
    //    I2PTunnelClientBase(localPort, ownDest, l, (EventDispatcher)null);
    //}

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
        this.privKeyFile = pkf;
        _ownDest = ownDest; // == ! shared client


        _context = tunnel.getContext();
        _context.statManager().createRateStat("i2ptunnel.client.closeBacklog", "How many pending sockets remain when we close one due to backlog?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.client.closeNoBacklog", "How many pending sockets remain when it was removed prior to backlog timeout?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.client.manageTime", "How long it takes to accept a socket and fire it into an i2ptunnel runner (or queue it for the pool)?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("i2ptunnel.client.buildRunTime", "How long it takes to run a queued socket into an i2ptunnel runner?", "I2PTunnel", new long[] { 60*1000, 10*60*1000, 60*60*1000 });

        // no need to load the netDb with leaseSets for destinations that will never 
        // be looked up
        tunnel.getClientOptions().setProperty("i2cp.dontPublishLeaseSet", "true");
        
        boolean openNow = !Boolean.valueOf(tunnel.getClientOptions().getProperty("i2cp.delayOpen")).booleanValue();
        if (openNow) {
            while (sockMgr == null) {
                verifySocketManager();
                if (sockMgr == null) {
                    _log.log(Log.CRIT, "Unable to create socket manager (our own? " + ownDest + ")");
                    try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
                }
            }
            if (sockMgr == null) {
                l.log("Invalid I2CP configuration");
                throw new IllegalArgumentException("Socket manager could not be created");
            }
            l.log("I2P session created");

        } // else delay creating session until createI2PSocket() is called
        
        Thread t = new I2PThread(this);
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

        configurePool(tunnel);
        
        if (open && listenerReady) {
            if (openNow)
                l.log("Ready! Port " + getLocalPort());
            else
                l.log("Listening on port " + getLocalPort() + ", delaying tunnel open until required");
            notifyEvent("openBaseClientResult", "ok");
        } else {
            l.log("Error listening - please see the logs!");
            notifyEvent("openBaseClientResult", "error");
        }
    }
    
    /** 
     * build and configure the pool handling accept()ed but not yet 
     * established connections 
     *
     */
    private void configurePool(I2PTunnel tunnel) {
        _waitingSockets = new ArrayList(4);
        
        Properties opts = tunnel.getClientOptions();
        String maxWait = opts.getProperty(PROP_MAX_WAIT_TIME, DEFAULT_MAX_WAIT_TIME+"");
        try { 
            _maxWaitTime = Integer.parseInt(maxWait); 
        } catch (NumberFormatException nfe) {
            _maxWaitTime = DEFAULT_MAX_WAIT_TIME;
        }
        
        String numBuild = opts.getProperty(PROP_NUM_CONNECTION_BUILDERS, DEFAULT_NUM_CONNECTION_BUILDERS+"");
        try {
            _numConnectionBuilders = Integer.parseInt(numBuild);
        } catch (NumberFormatException nfe) {
            _numConnectionBuilders = DEFAULT_NUM_CONNECTION_BUILDERS;
        }

        for (int i = 0; i < _numConnectionBuilders; i++) {
            String name = "ClientBuilder" + _clientId + '.' + i;
            I2PThread b = new I2PThread(new TunnelConnectionBuilder(), name);
            b.setDaemon(true);
            b.start();
        }
    }

    /**
     * Sets the this.sockMgr field if it is null, or if we want a new one
     *
     * We need a socket manager before getDefaultOptions() and most other things
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

    /** this is ONLY for shared clients */
    protected synchronized I2PSocketManager getSocketManager() {
        return getSocketManager(getTunnel(), this.privKeyFile);
    }
    /** this is ONLY for shared clients */
    protected static synchronized I2PSocketManager getSocketManager(I2PTunnel tunnel) {
        return getSocketManager(tunnel, null);
    }
    /** this is ONLY for shared clients */
    protected static synchronized I2PSocketManager getSocketManager(I2PTunnel tunnel, String pkf) {
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

    protected I2PSocketManager buildSocketManager() {
        return buildSocketManager(getTunnel(), this.privKeyFile);
    }
    protected static I2PSocketManager buildSocketManager(I2PTunnel tunnel) {
        return buildSocketManager(tunnel, null);
    }
    protected static I2PSocketManager buildSocketManager(I2PTunnel tunnel, String pkf) {
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
        while (sockManager == null) {
            if (pkf != null) {
                // Persistent client dest
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(pkf);
                    sockManager = I2PSocketManagerFactory.createManager(fis, tunnel.host, portNum, props);
                } catch (IOException ioe) {
                    _log.error("Error opening key file", ioe);
                    // this is going to loop but if we break we'll get a NPE
                } finally {
                    if (fis != null)
                        try { fis.close(); } catch (IOException ioe) {}
                }
            } else {
                sockManager = I2PSocketManagerFactory.createManager(tunnel.host, portNum, props);
            }
            
            if (sockManager == null) {
                _log.log(Log.CRIT, "Unable to create socket manager");
                try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
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
    public final void startRunning() {
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
                synchronized (_waitingSockets) { _waitingSockets.notifyAll(); }
                return;
            }
            ss = new ServerSocket(localPort, 0, addr);

            // If a free port was requested, find out what we got
            if (localPort == 0) {
                localPort = ss.getLocalPort();
            }
            notifyEvent("clientLocalPort", new Integer(ss.getLocalPort()));
            l.log("Listening for clients on port " + localPort + " of " + getTunnel().listenHost);

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

            while (true) {
                Socket s = ss.accept();
                long before = System.currentTimeMillis();
                manageConnection(s);
                long total = System.currentTimeMillis() - before;
                _context.statManager().addRateData("i2ptunnel.client.manageTime", total, total);
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
        synchronized (_waitingSockets) {
            _waitingSockets.notifyAll();
        }
    }

    /**
     * Manage the connection just opened on the specified socket
     *
     * @param s Socket to take care of
     */
    protected void manageConnection(Socket s) {
        if (s == null) return;
        if (_numConnectionBuilders <= 0) {
            new I2PThread(new BlockingRunner(s), "Clinet run").start();
            return;
        }
        
        if (_maxWaitTime > 0)
            SimpleScheduler.getInstance().addEvent(new CloseEvent(s), _maxWaitTime);

        synchronized (_waitingSockets) {
            _waitingSockets.add(s);
            _waitingSockets.notifyAll();
        }
    }

    /** 
     * Blocking runner, used during the connection establishment whenever we
     * are not using the queued builders.
     *
     */
    private class BlockingRunner implements Runnable {
        private Socket _s;
        public BlockingRunner(Socket s) { _s = s; }
        public void run() {
            clientConnectionRun(_s);
        }
    }
    
    /**
     * Remove and close the socket from the waiting list, if it is still there.
     *
     */
    private class CloseEvent implements SimpleTimer.TimedEvent {
        private Socket _s;
        public CloseEvent(Socket s) { _s = s; }
        public void timeReached() {
            int remaining = 0;
            boolean stillWaiting = false;
            synchronized (_waitingSockets) {
                stillWaiting = _waitingSockets.remove(_s);
                remaining = _waitingSockets.size();
            }
            if (stillWaiting) {
                try { _s.close(); } catch (IOException ioe) {}
                if (_log.shouldLog(Log.INFO)) {
                    _context.statManager().addRateData("i2ptunnel.client.closeBacklog", remaining, 0);
                    _log.info("Closed a waiting socket because of backlog");
                }
            } else {
                _context.statManager().addRateData("i2ptunnel.client.closeNoBacklog", remaining, 0);
            }
        }
    }

    public boolean close(boolean forced) {
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
                I2PSession session = sockMgr.getSession();
                if (session != null) {
                    getTunnel().removeSession(session);
                }
            }
            l.log("Closing client " + toString());
            open = false;
            try {
                if (ss != null) ss.close();
            } catch (IOException ex) {
                ex.printStackTrace();
                return false;
            }
            l.log("Client closed.");
        }
        
        synchronized (_waitingSockets) { _waitingSockets.notifyAll(); }
        return true;
    }

    public static void closeSocket(Socket s) {
        try {
            s.close();
        } catch (IOException ex) {
            _log.error("Could not close socket", ex);
        }
    }
    
    /**
     * Pool runner pulling sockets off the waiting list and pushing them
     * through clientConnectionRun.  This dies when the I2PTunnel instance
     * is closed.
     *
     */
    private class TunnelConnectionBuilder implements Runnable {
        public void run() { 
            Socket s = null;
            while (open) {
                try {
                    synchronized (_waitingSockets) {
                        if (_waitingSockets.size() <= 0)
                            _waitingSockets.wait();
                        else
                            s = (Socket)_waitingSockets.remove(0);
                    }
                } catch (InterruptedException ie) {}
                
                if (s != null) {
                    long before = System.currentTimeMillis();
                    clientConnectionRun(s);
                    long total = System.currentTimeMillis() - before;
                    _context.statManager().addRateData("i2ptunnel.client.buildRunTime", total, 0);
                }
                s = null;
            }
        }
    }

    /**
     * Manage a connection in a separate thread. This only works if
     * you do not override manageConnection()
     */
    protected abstract void clientConnectionRun(Socket s);
}
