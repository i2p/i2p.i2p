/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
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

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Base64;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

public class I2PTunnelServer extends I2PTunnelTask implements Runnable {

    private final static Log _log = new Log(I2PTunnelServer.class);

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

    private static final boolean DEFAULT_USE_POOL = false;
    
    public I2PTunnelServer(InetAddress host, int port, String privData, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host + ":" + port + " <- " + privData, notifyThis, tunnel);
        ByteArrayInputStream bais = new ByteArrayInputStream(Base64.decode(privData));
        String usePool = tunnel.getClientOptions().getProperty("i2ptunnel.usePool");
        if (usePool != null)
            _usePool = "true".equalsIgnoreCase(usePool);
        else
            _usePool = DEFAULT_USE_POOL;
        init(host, port, bais, privData, l);
    }

    public I2PTunnelServer(InetAddress host, int port, File privkey, String privkeyname, Logging l,
                           EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host + ":" + port + " <- " + privkeyname, notifyThis, tunnel);
        String usePool = tunnel.getClientOptions().getProperty("i2ptunnel.usePool");
        if (usePool != null)
            _usePool = "true".equalsIgnoreCase(usePool);
        else
            _usePool = DEFAULT_USE_POOL;
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

    public I2PTunnelServer(InetAddress host, int port, InputStream privData, String privkeyname, Logging l,  EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host + ":" + port + " <- " + privkeyname, notifyThis, tunnel);
        String usePool = tunnel.getClientOptions().getProperty("i2ptunnel.usePool");
        if (usePool != null)
            _usePool = "true".equalsIgnoreCase(usePool);
        else
            _usePool = DEFAULT_USE_POOL;
        init(host, port, privData, privkeyname, l);
    }

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
                _log.log(Log.CRIT, "Invalid port specified [" + getTunnel().port + "], reverting to " + portNum);
            }
        }

        while (sockMgr == null) {
            synchronized (slock) {
                sockMgr = I2PSocketManagerFactory.createManager(privData, getTunnel().host, portNum,
                                                                props);

            }
            if (sockMgr == null) {
                _log.log(Log.CRIT, "Unable to create socket manager");
                try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
            }
        }
        sockMgr.setName("Server");
        getTunnel().addSession(sockMgr.getSession());
        l.log("Ready!");
        notifyEvent("openServerResult", "ok");
        open = true;
    }

    
    private static volatile long __serverId = 0;
    
    /**
     * Start running the I2PTunnelServer.
     *
     */
    public void startRunning() {
        Thread t = new I2PThread(this);
        t.setName("Server " + (++__serverId));
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
        synchronized (lock) {
            if (!forced && sockMgr.listSockets().size() != 0) {
                l.log("There are still active connections!");
                for (Iterator it = sockMgr.listSockets().iterator(); it.hasNext();) {
                    l.log("->" + it.next());
                }
                return false;
            }
            l.log("Shutting down server " + toString());
            try {
                if (i2pss != null) i2pss.close();
                getTunnel().removeSession(sockMgr.getSession());
                sockMgr.getSession().destroySession();
            } catch (I2PException ex) {
                _log.error("Error destroying the session", ex);
                System.exit(1);
            }
            l.log("Server shut down.");
            open = false;
            return true;
        }
    }

    private static final String PROP_HANDLER_COUNT = "i2ptunnel.blockingHandlerCount";
    private static final int DEFAULT_HANDLER_COUNT = 10;
    
    protected int getHandlerCount() { 
        int rv = DEFAULT_HANDLER_COUNT;
        String cnt = getTunnel().getClientOptions().getProperty(PROP_HANDLER_COUNT);
        if (cnt != null) {
            try {
                rv = Integer.parseInt(cnt);
                if (rv <= 0)
                    rv = DEFAULT_HANDLER_COUNT;
            } catch (NumberFormatException nfe) {
                rv = DEFAULT_HANDLER_COUNT;
            }
        }
        return rv;
    }
    
    public void run() {
        if (shouldUsePool()) {
            I2PServerSocket i2pS_S = sockMgr.getServerSocket();
            int handlers = getHandlerCount();
            for (int i = 0; i < handlers; i++) {
                I2PThread handler = new I2PThread(new Handler(i2pS_S), "Handle Server " + i);
                handler.start();
            }
        } else {
            I2PServerSocket i2pS_S = sockMgr.getServerSocket();
            while (true) {
                try {
                    final I2PSocket i2ps = i2pS_S.accept();
                    if (i2ps == null) throw new I2PException("I2PServerSocket closed");
                    new I2PThread(new Runnable() { public void run() { blockingHandle(i2ps); } }).start();
                } catch (I2PException ipe) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Error accepting - KILLING THE TUNNEL SERVER", ipe);
                    return;
                } catch (ConnectException ce) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Error accepting", ce);
                    // not killing the server..
                } catch(SocketTimeoutException ste) {
                    // ignored, we never set the timeout
                }
            }
        }
    }
    
    public boolean shouldUsePool() { return _usePool; }
    
    /**
     * minor thread pool to pull off the accept() concurrently.  there are still lots
     * (and lots) of wasted threads within the I2PTunnelRunner, but its a start
     *
     */
    private class Handler implements Runnable { 
        private I2PServerSocket _serverSocket;
        public Handler(I2PServerSocket serverSocket) {
            _serverSocket = serverSocket;
        }
        public void run() {
            while (open) {
                try {
                    blockingHandle(_serverSocket.accept());   
                } catch (I2PException ex) {
                    _log.error("Error while waiting for I2PConnections", ex);
                    return;
                } catch (IOException ex) {
                    _log.error("Error while waiting for I2PConnections", ex);
                    return;
                }
            }
        }
    }
    
    protected void blockingHandle(I2PSocket socket) {
        long afterAccept = I2PAppContext.getGlobalContext().clock().now();
        long afterSocket = -1;
        //local is fast, so synchronously. Does not need that many
        //threads.
        try {
            socket.setReadTimeout(readTimeout);
            Socket s = new Socket(remoteHost, remotePort);
            afterSocket = I2PAppContext.getGlobalContext().clock().now();
            new I2PTunnelRunner(s, socket, slock, null, null);
        } catch (SocketException ex) {
            try {
                socket.close();
            } catch (IOException ioe) {
                _log.error("Error while closing the received i2p con", ex);
            }
        } catch (IOException ex) {
            _log.error("Error while waiting for I2PConnections", ex);
        }

        long afterHandle = I2PAppContext.getGlobalContext().clock().now();
        long timeToHandle = afterHandle - afterAccept;
        if (timeToHandle > 1000)
            _log.warn("Took a while to handle the request [" + timeToHandle + ", socket create: " + (afterSocket-afterAccept) + "]");
    }
}

