/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel.udpTunnel;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;

import net.i2p.I2PAppContext;
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

    /**
     * Base client class that sets up an I2P Datagram client destination.
     * The UDP side is not implemented here, as there are at least
     * two possibilities:
     *
     * 1) UDP side is a "server"
     *    Example: Streamr Consumer
     *    - Configure a destination host and port
     *    - External application sends no data
     *    - Extending class must have a constructor with host and port arguments
     *
     * 2) UDP side is a client/server
     *    Example: SOCKS UDP (DNS requests?)
     *    - configure an inbound port and a destination host and port
     *    - External application sends and receives data
     *    - Extending class must have a constructor with host and 2 port arguments
     *
     * So the implementing class must create a UDPSource and/or UDPSink,
     * and must call setSink().
     *
     * @author zzz with portions from welterde's streamr
     */
 public abstract class I2PTunnelUDPClientBase extends I2PTunnelTask implements Source, Sink {

    protected I2PAppContext _context;
    protected Logging l;

    static final long DEFAULT_CONNECT_TIMEOUT = 60 * 1000;

    private static volatile long __clientId = 0;
    protected long _clientId;

    protected Destination dest = null;

    private boolean listenerReady = false;

    private ServerSocket ss;

    private final Object startLock = new Object();
    private boolean startRunning = false;

    private byte[] pubkey;

    private String handlerName;

    private Object conLock = new Object();
    
    /** How many connections will we allow to be in the process of being built at once? */
    private int _numConnectionBuilders;
    /** How long will we allow sockets to sit in the _waitingSockets map before killing them? */
    private int _maxWaitTime;
    
    private I2PSession _session;
    private Source _i2pSource;
    private Sink _i2pSink;
    private Destination _otherDest;
    /**
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
   public I2PTunnelUDPClientBase(String destination, Logging l, EventDispatcher notifyThis,
                                  I2PTunnel tunnel) throws IllegalArgumentException {
        super("UDPServer", notifyThis, tunnel);
        _clientId = ++__clientId;
        this.l = l;

        _context = tunnel.getContext();

        tunnel.getClientOptions().setProperty("i2cp.dontPublishLeaseSet", "true");
        
        // create i2pclient and destination
        I2PClient client = I2PClientFactory.createClient();
        byte[] key;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            client.createDestination(out);
            key = out.toByteArray();
        } catch(Exception exc) {
            throw new RuntimeException("failed to create i2p-destination", exc);
        }

        // create a session
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(key);
            _session = client.createSession(in, tunnel.getClientOptions());
            connected(_session);
        } catch(Exception exc) {
            throw new RuntimeException("failed to create session", exc);
        }

        // Setup the source. Always expect raw unverified datagrams.
        _i2pSource = new I2PSource(_session, false, true);

        // Setup the sink. Always send repliable datagrams.
        if (destination != null && destination.length() > 0) {
            _otherDest = _context.namingService().lookup(destination);
            if (_otherDest == null) {
                l.log("Could not resolve " + destination);
                throw new RuntimeException("failed to create session - could not resolve " + destination);
             }
            _i2pSink = new I2PSink(_session, _otherDest, false);
        } else {
            _i2pSink = new I2PSinkAnywhere(_session, false);
        }   
    }
    
    /**
     * Actually start working on outgoing connections.
     * Classes should override to start UDP side as well.
     *
     * Not specified in I2PTunnelTask but used in both
     * I2PTunnelClientBase and I2PTunnelServer so let's
     * implement it here too.
     */
    public void startRunning() {
        synchronized (startLock) {
            try {
                _session.connect();
            } catch(I2PSessionException exc) {
                throw new RuntimeException("failed to connect session", exc);
            }
            start();
            startRunning = true;
            startLock.notify();
        }
        open = true;
    }

    /**
     * I2PTunnelTask Methods
     *
     * Classes should override to close UDP side as well
     */
    public boolean close(boolean forced) {
        if (!open) return true;
        if (_session != null) {
            try {
                _session.destroySession();
            } catch (I2PSessionException ise) {}
        }
        l.log("Closing client " + toString());
        open = false;
        return true;
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
     * @param to - ignored if configured for a single destination
     * (we use the dest specified in the constructor)
     */
    public void send(Destination to, byte[] data) {
        _i2pSink.send(to, data);
    }
}
