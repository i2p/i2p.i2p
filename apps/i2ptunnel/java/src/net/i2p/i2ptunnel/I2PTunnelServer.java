/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Iterator;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
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

    private I2PSocketManager sockMgr;
    private I2PServerSocket i2pss;

    private Object lock = new Object(), slock = new Object();

    private InetAddress remoteHost;
    private int remotePort;

    private Logging l;

    private long readTimeout = -1;

    public I2PTunnelServer(InetAddress host, int port, String privData, Logging l, EventDispatcher notifyThis) {
        super(host + ":" + port + " <- " + privData, notifyThis);
        ByteArrayInputStream bais = new ByteArrayInputStream(Base64.decode(privData));
        init(host, port, bais, privData, l);
    }

    public I2PTunnelServer(InetAddress host, int port, File privkey, String privkeyname, Logging l,
                           EventDispatcher notifyThis) {
        super(host + ":" + port + " <- " + privkeyname, notifyThis);
        try {
            init(host, port, new FileInputStream(privkey), privkeyname, l);
        } catch (IOException ioe) {
            _log.error("Error starting server", ioe);
            notifyEvent("openServerResult", "error");
        }
    }

    public I2PTunnelServer(InetAddress host, int port, InputStream privData, String privkeyname, Logging l,  EventDispatcher notifyThis) {
        super(host + ":" + port + " <- " + privkeyname, notifyThis);
        init(host, port, privData, privkeyname, l);
    }

    private void init(InetAddress host, int port, InputStream privData, String privkeyname, Logging l) {
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
        I2PClient client = I2PClientFactory.createClient();
        Properties props = new Properties();
        props.putAll(System.getProperties());
        synchronized (slock) {
            sockMgr = I2PSocketManagerFactory.createManager(privData, I2PTunnel.host, Integer.parseInt(I2PTunnel.port),
                                                            props);

        }
        l.log("Ready!");
        notifyEvent("openServerResult", "ok");
        open = true;
    }

    /**
     * Start running the I2PTunnelServer.
     *
     */
    public void startRunning() {
        Thread t = new I2PThread(this);
        t.setName("Server");
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

    public void run() {
        try {
            I2PServerSocket i2pss = sockMgr.getServerSocket();
            while (true) {
                I2PSocket i2ps = i2pss.accept();
                //local is fast, so synchronously. Does not need that many
                //threads.
                try {
                    i2ps.setReadTimeout(readTimeout);
                    Socket s = new Socket(remoteHost, remotePort);
                    new I2PTunnelRunner(s, i2ps, slock, null);
                } catch (SocketException ex) {
                    i2ps.close();
                }
            }
        } catch (I2PException ex) {
            _log.error("Error while waiting for I2PConnections", ex);
        } catch (IOException ex) {
            _log.error("Error while waiting for I2PConnections", ex);
        }
    }
}

