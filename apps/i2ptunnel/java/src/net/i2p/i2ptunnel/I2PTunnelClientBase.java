/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

public abstract class I2PTunnelClientBase extends I2PTunnelTask implements Runnable {

    private static final Log _log = new Log(I2PTunnelClientBase.class);
    protected Logging l;

    private static final long DEFAULT_CONNECT_TIMEOUT = 60 * 1000;

    protected Object sockLock = new Object(); // Guards sockMgr and mySockets
    private I2PSocketManager sockMgr;
    private List mySockets = new ArrayList();

    protected Destination dest = null;
    private int localPort;

    private boolean listenerReady = false;

    private ServerSocket ss;

    private Object startLock = new Object();
    private boolean startRunning = false;

    private Object closeLock = new Object();

    private byte[] pubkey;

    private String handlerName;

    //public I2PTunnelClientBase(int localPort, boolean ownDest,
    //		       Logging l) {
    //    I2PTunnelClientBase(localPort, ownDest, l, (EventDispatcher)null);
    //}

    public I2PTunnelClientBase(int localPort, boolean ownDest, Logging l, EventDispatcher notifyThis, String handlerName) {
        super(localPort + " (uninitialized)", notifyThis);
        this.localPort = localPort;
        this.l = l;
        this.handlerName = handlerName;

        synchronized (sockLock) {
            if (ownDest) {
                sockMgr = buildSocketManager();
            } else {
                sockMgr = getSocketManager();
            }
        }
        if (sockMgr == null) throw new NullPointerException();
        l.log("I2P session created");

        Thread t = new I2PThread(this);
        t.setName("Client");
        listenerReady = false;
        t.start();
        open = true;
        synchronized (this) {
            while (!listenerReady) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }

        if (open && listenerReady) {
            l.log("Ready! Port " + getLocalPort());
            notifyEvent("openBaseClientResult", "ok");
        } else {
            l.log("Error!");
            notifyEvent("openBaseClientResult", "error");
        }
    }

    private static I2PSocketManager socketManager;

    protected static synchronized I2PSocketManager getSocketManager() {
        if (socketManager == null) {
            socketManager = buildSocketManager();
        }
        return socketManager;
    }

    protected static I2PSocketManager buildSocketManager() {
        Properties props = new Properties();
        props.putAll(System.getProperties());
        return I2PSocketManagerFactory.createManager(I2PTunnel.host, Integer.parseInt(I2PTunnel.port), props);
    }

    public final int getLocalPort() {
        return localPort;
    }

    protected final InetAddress getListenHost(Logging l) {
        try {
            return InetAddress.getByName(I2PTunnel.listenHost);
        } catch (UnknownHostException uhe) {
            l.log("Could not find listen host to bind to [" + I2PTunnel.host + "]");
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
    private I2PSocketOptions getDefaultOptions() {
        I2PSocketOptions opts = new I2PSocketOptions();
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
    public I2PSocket createI2PSocket(Destination dest) throws I2PException {
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
     */
    public I2PSocket createI2PSocket(Destination dest, I2PSocketOptions opt) throws I2PException {
        I2PSocket i2ps;

        synchronized (sockLock) {
            i2ps = sockMgr.connect(dest, opt);
            mySockets.add(i2ps);
        }

        return i2ps;
    }

    public final void run() {
        try {
            InetAddress addr = getListenHost(l);
            if (addr == null) return;
            ss = new ServerSocket(localPort, 0, addr);

            // If a free port was requested, find out what we got
            if (localPort == 0) {
                localPort = ss.getLocalPort();
            }
            notifyEvent("clientLocalPort", new Integer(ss.getLocalPort()));
            l.log("Listening for clients on port " + localPort + " of " + I2PTunnel.listenHost);

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
                manageConnection(s);
            }
        } catch (IOException ex) {
            _log.error("Error listening for connections", ex);
            notifyEvent("openBaseClientResult", "error");
        }
    }

    /**
     * Manage the connection just opened on the specified socket
     *
     * @param s Socket to take care of
     */
    protected void manageConnection(Socket s) {
        new ClientConnectionRunner(s, handlerName);
    }

    public boolean close(boolean forced) {
        if (!open) return true;
        // FIXME: here we might have to wait quite a long time if
        // there is a connection attempt atm. But without waiting we
        // might risk to create an orphan socket. Would be better
        // to return with an error in that situation quickly.
        synchronized (sockLock) {
            mySockets.retainAll(sockMgr.listSockets());
            if (!forced && mySockets.size() != 0) {
                l.log("There are still active connections!");
                _log.debug("can't close: there are still active connections!");
                for (Iterator it = mySockets.iterator(); it.hasNext();) {
                    l.log("->" + it.next());
                }
                return false;
            }
            l.log("Closing client " + toString());
            try {
                if (ss != null) ss.close();
            } catch (IOException ex) {
                ex.printStackTrace();
                return false;
            }
            l.log("Client closed.");
            open = false;
            return true;
        }
    }

    public static void closeSocket(Socket s) {
        try {
            s.close();
        } catch (IOException ex) {
            _log.error("Could not close socket", ex);
        }
    }

    public class ClientConnectionRunner extends I2PThread {
        private Socket s;

        public ClientConnectionRunner(Socket s, String name) {
            this.s = s;
            setName(name);
            start();
        }

        public void run() {
            clientConnectionRun(s);
        }
    }

    /**
     * Manage a connection in a separate thread. This only works if
     * you do not override manageConnection()
     */
    protected abstract void clientConnectionRun(Socket s);
}