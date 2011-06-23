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
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Base64;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

public class I2PTunnelServer extends I2PTunnelTask implements Runnable {

    protected final Log _log;
    protected I2PSocketManager sockMgr;
    protected I2PServerSocket i2pss;

    private final Object lock = new Object();
    protected final Object slock = new Object();

    protected InetAddress remoteHost;
    protected int remotePort;
    private boolean _usePool;

    private Logging l;

    private static final long DEFAULT_READ_TIMEOUT = -1; // 3*60*1000;
    /** default timeout to 3 minutes - override if desired */
    protected long readTimeout = DEFAULT_READ_TIMEOUT;

    /** do we use threads? default true (ignored for standard servers, always false) */
    private static final String PROP_USE_POOL = "i2ptunnel.usePool";
    private static final boolean DEFAULT_USE_POOL = true;
    protected static volatile long __serverId = 0;
    /** max number of threads  - this many slowlorisses will DOS this server, but too high could OOM the JVM */
    private static final String PROP_HANDLER_COUNT = "i2ptunnel.blockingHandlerCount";
    private static final int DEFAULT_HANDLER_COUNT = 65;
    /** min number of threads */
    private static final int MIN_HANDLERS = 0;
    /** how long to wait before dropping an idle thread */
    private static final long HANDLER_KEEPALIVE_MS = 30*1000;

    protected I2PTunnelTask task = null;
    protected boolean bidir = false;
    private ThreadPoolExecutor _executor;

    private int DEFAULT_LOCALPORT = 4488;
    protected int localPort = DEFAULT_LOCALPORT;

    /**
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelServer(InetAddress host, int port, String privData, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("Server at " + host + ':' + port, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(getClass());
        ByteArrayInputStream bais = new ByteArrayInputStream(Base64.decode(privData));
        init(host, port, bais, privData, l);
    }

    /**
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelServer(InetAddress host, int port, File privkey, String privkeyname, Logging l,
                           EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("Server at " + host + ':' + port, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(getClass());
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(privkey);
            init(host, port, fis, privkeyname, l);
        } catch (IOException ioe) {
            _log.error("Error starting server", ioe);
            notifyEvent("openServerResult", "error");
        } finally {
            if (fis != null)
                try { fis.close(); } catch (IOException ioe) {}
        }
    }

    /**
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelServer(InetAddress host, int port, InputStream privData, String privkeyname, Logging l,  EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("Server at " + host + ':' + port, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(getClass());
        init(host, port, privData, privkeyname, l);
    }

    private static final int RETRY_DELAY = 20*1000;
    private static final int MAX_RETRIES = 4;

    /**
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    private void init(InetAddress host, int port, InputStream privData, String privkeyname, Logging l) {
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
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

        // copy the privData to a new BAIS, so we can always reset() it if we have to retry
        ByteArrayInputStream privDataCopy;
        try {
            privDataCopy = copyOfInputStream(privData);
        } catch (IOException ioe) {
            _log.log(Log.CRIT, "Cannot read private key data for " + privkeyname, ioe);
            return;
        }

        // extending classes default to threaded, but for a standard server, we can't get slowlorissed
        _usePool = !getClass().equals(I2PTunnelServer.class);
        if (_usePool) {
            String usePool = getTunnel().getClientOptions().getProperty(PROP_USE_POOL);
            if (usePool != null)
                _usePool = "true".equalsIgnoreCase(usePool);
            else
                _usePool = DEFAULT_USE_POOL;
        }

        // Todo: Can't stop a tunnel from the UI while it's in this loop (no session yet)
        int retries = 0;
        while (sockMgr == null) {
            synchronized (slock) {
                sockMgr = I2PSocketManagerFactory.createManager(privDataCopy, getTunnel().host, portNum,
                                                                props);

            }
            if (sockMgr == null) {
                // try to make this error sensible as it will happen...
                String msg = "Unable to connect to the router at " + getTunnel().host + ':' + portNum +
                             " and build tunnels for the server at " + getTunnel().listenHost + ':' + port;
                if (++retries < MAX_RETRIES) {
                    this.l.log(msg + ", retrying in " + (RETRY_DELAY / 1000) + " seconds");
                    _log.error(msg + ", retrying in " + (RETRY_DELAY / 1000) + " seconds");
                } else {
                    this.l.log(msg + ", giving up");
                    _log.log(Log.CRIT, msg + ", giving up");
                    throw new IllegalArgumentException(msg);
                }
                try { Thread.sleep(RETRY_DELAY); } catch (InterruptedException ie) {}
                privDataCopy.reset();
            }
        }

        sockMgr.setName("Server");
        getTunnel().addSession(sockMgr.getSession());
        l.log("Tunnels ready for server at " + getTunnel().listenHost + ':' + port);
        notifyEvent("openServerResult", "ok");
        open = true;
    }

    /**
     *  Copy input stream to a byte array, so we can retry
     *  @since 0.7.10
     */
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
    
    /**
     * Start running the I2PTunnelServer.
     *
     */
    public void startRunning() {
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

    public boolean close(boolean forced) {
        if (!open) return true;
        if (task != null) {
            task.close(forced);
        }
        synchronized (lock) {
            if (!forced && sockMgr.listSockets().size() != 0) {
                l.log("There are still active connections!");
                for (Iterator it = sockMgr.listSockets().iterator(); it.hasNext();) {
                    l.log("->" + it.next());
                }
                return false;
            }
            l.log("Stopping tunnels for server at " + this.remoteHost + ':' + this.remotePort);
            try {
                if (i2pss != null) i2pss.close();
                getTunnel().removeSession(sockMgr.getSession());
                sockMgr.getSession().destroySession();
            } catch (I2PException ex) {
                _log.error("Error destroying the session", ex);
                //System.exit(1);
            }
            //l.log("Server shut down.");
            open = false;
            if (_usePool && _executor != null) {
                _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
                _executor.shutdownNow();
            }
            return true;
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
        I2PServerSocket i2pS_S = sockMgr.getServerSocket();
        if (_log.shouldLog(Log.WARN)) {
            if (_usePool)
                _log.warn("Starting executor with " + getHandlerCount() + " threads max");
            else
                _log.warn("Threads disabled, running blockingHandles inline");
        }
        if (_usePool) {
            _executor = new CustomThreadPoolExecutor(getHandlerCount(), "ServerHandler pool " + remoteHost + ':' + remotePort);
        }
        while (open) {
            try {
                final I2PSocket i2ps = i2pS_S.accept();
                if (i2ps == null) throw new I2PException("I2PServerSocket closed");
                if (_usePool) {
                    try {
                        _executor.execute(new Handler(i2ps));
                    } catch (RejectedExecutionException ree) {
                         try {
                             i2ps.close();
                         } catch (IOException ioe) {}
                         if (open)
                             _log.logAlways(Log.WARN, "ServerHandler queue full, dropping incoming connection to " +
                                        remoteHost + ':' + remotePort +
                                        "; increase server max threads or " + PROP_HANDLER_COUNT);
                    }
                } else {
                    // use only for standard servers that can't get slowlorissed! Not for http or irc
                    blockingHandle(i2ps);
                }
            } catch (I2PException ipe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error accepting - KILLING THE TUNNEL SERVER", ipe);
                return;
            } catch (ConnectException ce) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error accepting", ce);
                // not killing the server..
                try {
                    Thread.currentThread().sleep(500);
                } catch (InterruptedException ie) {}
            } catch(SocketTimeoutException ste) {
                // ignored, we never set the timeout
            }
        }
        if (_executor != null)
            _executor.shutdownNow();
    }
    
    /**
     * Not really needed for now but in case we want to add some hooks like afterExecute().
     */
    private static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        public CustomThreadPoolExecutor(int max, String name) {
             super(MIN_HANDLERS, max, HANDLER_KEEPALIVE_MS, TimeUnit.MILLISECONDS,
                   new SynchronousQueue(), new CustomThreadFactory(name));
        }
    }

    /** just to set the name and set Daemon */
    private static class CustomThreadFactory implements ThreadFactory {
        private String _name;

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
        private I2PSocket _i2ps;

        public Handler(I2PSocket socket) {
            _i2ps = socket;
        }

        public void run() {
            blockingHandle(_i2ps);   
        }
    }
    
    protected void blockingHandle(I2PSocket socket) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Incoming connection to '" + toString() + "' from: " + socket.getPeerDestination().calculateHash().toBase64());
        long afterAccept = I2PAppContext.getGlobalContext().clock().now();
        long afterSocket = -1;
        //local is fast, so synchronously. Does not need that many
        //threads.
        try {
            socket.setReadTimeout(readTimeout);
            Socket s = new Socket(remoteHost, remotePort);
            afterSocket = I2PAppContext.getGlobalContext().clock().now();
            new I2PTunnelRunner(s, socket, slock, null, null);

            long afterHandle = I2PAppContext.getGlobalContext().clock().now();
            long timeToHandle = afterHandle - afterAccept;
            if ( (timeToHandle > 1000) && (_log.shouldLog(Log.WARN)) )
                _log.warn("Took a while to handle the request for " + remoteHost + ':' + remotePort +
                          " [" + timeToHandle + ", socket create: " + (afterSocket-afterAccept) + "]");
        } catch (SocketException ex) {
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error connecting to server " + remoteHost + ':' + remotePort, ex);
        } catch (IOException ex) {
            _log.error("Error while waiting for I2PConnections", ex);
        }
    }
}

