/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel.udpTunnel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelTask;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

    /**
     * Base client class that sets up an I2P Datagram server destination.
     * The UDP side is not implemented here, as there are at least
     * two possibilities:
     *
     * 1) UDP side is a "client"
     *    Example: Streamr Producer
     *    - configure an inbound port
     *    - External application receives no data
     *    - Extending class must have a constructor with a port argument
     *
     * 2) UDP side is a client/server
     *    Example: DNS
     *    - configure an inbound port and a destination host and port
     *    - External application sends and receives data
     *    - Extending class must have a constructor with host and 2 port arguments
     *
     * So the implementing class must create a UDPSource and/or UDPSink,
     * and must call setSink().
     *
     * @author zzz with portions from welterde's streamr
     */

public class I2PTunnelUDPServerBase extends I2PTunnelTask implements Source, Sink {

    private final Log _log;

    private final Object lock = new Object();
    protected Object slock = new Object();

    protected Logging l;

    private static final long DEFAULT_READ_TIMEOUT = -1; // 3*60*1000;
    /** default timeout to 3 minutes - override if desired */
    protected long readTimeout = DEFAULT_READ_TIMEOUT;

    private I2PSession _session;
    private Source _i2pSource;
    private Sink _i2pSink;

    /**
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     *
     */

    public I2PTunnelUDPServerBase(boolean verify, File privkey, String privkeyname, Logging l,
                           EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("UDPServer <- " + privkeyname, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(I2PTunnelUDPServerBase.class);
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(privkey);
            init(verify, fis, privkeyname, l);
        } catch (IOException ioe) {
            _log.error("Error starting server", ioe);
            notifyEvent("openServerResult", "error");
        } finally {
            if (fis != null)
                try { fis.close(); } catch (IOException ioe) {}
        }
    }

    private void init(boolean verify, InputStream privData, String privkeyname, Logging l) {
        this.l = l;
        int portNum = 7654;
        if (getTunnel().port != null) {
            try {
                portNum = Integer.parseInt(getTunnel().port);
            } catch (NumberFormatException nfe) {
                _log.log(Log.CRIT, "Invalid port specified [" + getTunnel().port + "], reverting to " + portNum);
            }
        }

        // create i2pclient
        I2PClient client = I2PClientFactory.createClient();

        try {
            _session = client.createSession(privData, getTunnel().getClientOptions());
            connected(_session);
        } catch(I2PSessionException exc) {
            throw new RuntimeException("failed to create session", exc);
        }

        // Setup the source. Always expect repliable datagrams, optionally verify
        _i2pSource = new I2PSource(_session, verify, false);

        // Setup the sink. Always send raw datagrams.
        _i2pSink = new I2PSinkAnywhere(_session, true);
    }
    
    /**
     * Classes should override to start UDP side as well.
     *
     * Not specified in I2PTunnelTask but used in both
     * I2PTunnelClientBase and I2PTunnelServer so let's
     * implement it here too.
     */
    public void startRunning() {
        //synchronized (startLock) {
            try {
                _session.connect();
            } catch(I2PSessionException exc) {
                throw new RuntimeException("failed to connect session", exc);
            }
            start();
        //}

        notifyEvent("openServerResult", "ok");
        open = true;
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
     * I2PTunnelTask Methods
     *
     * Classes should override to close UDP side as well
     */
    public boolean close(boolean forced) {
        if (!open) return true;
        synchronized (lock) {
            l.log("Shutting down server " + toString());
            try {
                if (_session != null) {
                    _session.destroySession();
                }
            } catch (I2PException ex) {
                _log.error("Error destroying the session", ex);
            }
            l.log("Server shut down.");
            open = false;
            return true;
        }
    }

    /**
     *  Source Methods
     *
     *  Sets the receiver of the UDP datagrams from I2P
     *  Subclass must call this after constructor
     *  and before start()
     */
    public void setSink(Sink s) {
        _i2pSource.setSink(s);
    }

    /** start the source */
    public void start() {
        _i2pSource.start();
    }

    /**
     *  Sink Methods
     *
     * @param to
     *
     */
    public void send(Destination to, byte[] data) {
        _i2pSink.send(to, data);
    }
}

