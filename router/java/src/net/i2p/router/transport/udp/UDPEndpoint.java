package net.i2p.router.transport.udp;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Coordinate the low level datagram socket, managing the UDPSender and
 * UDPReceiver
 */
class UDPEndpoint {
    private final RouterContext _context;
    private final Log _log;
    private int _listenPort;
    private final UDPTransport _transport;
    private UDPSender _sender;
    private UDPReceiver _receiver;
    private DatagramSocket _socket;
    private final InetAddress _bindAddress;
    
    /**
     *  @param listenPort -1 or the requested port, may not be honored
     *  @param bindAddress null ok
     */
    public UDPEndpoint(RouterContext ctx, UDPTransport transport, int listenPort, InetAddress bindAddress) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPEndpoint.class);
        _transport = transport;
        _bindAddress = bindAddress;
        _listenPort = listenPort;
    }
    
    /** caller should call getListenPort() after this to get the actual bound port and determine success */
    public synchronized void startup() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Starting up the UDP endpoint");
        shutdown();
        _socket = getSocket();
        if (_socket == null) {
            _log.log(Log.CRIT, "UDP Unable to open a port");
            return;
        }
        _sender = new UDPSender(_context, _socket, "UDPSender");
        _receiver = new UDPReceiver(_context, _transport, _socket, "UDPReceiver");
        _sender.startup();
        _receiver.startup();
    }
    
    public synchronized void shutdown() {
        if (_sender != null) {
            _sender.shutdown();
            _receiver.shutdown();
        }
        if (_socket != null) {
            _socket.close();
        }
    }
    
    public void setListenPort(int newPort) { _listenPort = newPort; }

/*******
    public void updateListenPort(int newPort) {
        if (newPort == _listenPort) return;
        try {
            if (_bindAddress == null)
                _socket = new DatagramSocket(_listenPort);
            else
                _socket = new DatagramSocket(_listenPort, _bindAddress);
            _sender.updateListeningPort(_socket, newPort);
            // note: this closes the old socket, so call this after the sender!
            _receiver.updateListeningPort(_socket, newPort);
            _listenPort = newPort;
        } catch (SocketException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to bind on " + _listenPort);
        }
    }
********/
    
    /** 8998 is monotone, and 31000 is the wrapper outbound, so let's stay between those */
    public static final String PROP_MIN_PORT = "i2np.udp.minPort";
    public static final String PROP_MAX_PORT = "i2np.udp.maxPort";
    private static final int MIN_RANDOM_PORT = 9111;
    private static final int MAX_RANDOM_PORT = 30777;
    private static final int MAX_PORT_RETRIES = 20;

    /**
     *  Open socket using requested port in _listenPort and  bind host in _bindAddress.
     *  If _listenPort <= 0, or requested port is busy, repeatedly try a new random port.
     *  @return null on failure
     *  Sets _listenPort to actual port or -1 on failure
     */
    private DatagramSocket getSocket() {
        DatagramSocket socket = null;
        int port = _listenPort;

        for (int i = 0; i < MAX_PORT_RETRIES; i++) {
             if (port <= 0) {
                 // try random ports rather than just do new DatagramSocket()
                 // so we stay out of the way of other I2P stuff
                 int minPort = _context.getProperty(PROP_MIN_PORT, MIN_RANDOM_PORT);
                 int maxPort = _context.getProperty(PROP_MAX_PORT, MAX_RANDOM_PORT);
                 port = minPort + _context.random().nextInt(maxPort - minPort);
             }
             try {
                 if (_bindAddress == null)
                     socket = new DatagramSocket(port);
                 else
                     socket = new DatagramSocket(port, _bindAddress);
                 break;
             } catch (SocketException se) {
                 if (_log.shouldLog(Log.WARN))
                     _log.warn("Binding to port " + port + " failed: " + se);
             }
             port = -1;
        }
        if (socket == null) {
            _log.log(Log.CRIT, "SSU Unable to bind to a port on " + _bindAddress);
        } else if (port != _listenPort) {
            if (_listenPort > 0)
                _log.error("SSU Unable to bind to requested port " + _listenPort + ", using random port " + port);
            else
                _log.logAlways(Log.INFO, "UDP selected random port " + port);
        }
        _listenPort = port;
        return socket;
    }

    /** call after startup() to get actual port or -1 on startup failure */
    public int getListenPort() { return _listenPort; }
    public UDPSender getSender() { return _sender; }
    
    /**
     * Add the packet to the outobund queue to be sent ASAP (as allowed by
     * the bandwidth limiter)
     * BLOCKING if queue is full.
     */
    public void send(UDPPacket packet) { 
        _sender.add(packet); 
    }
    
    /**
     * Blocking call to receive the next inbound UDP packet from any peer.
     * @return null if we have shut down
     */
    public UDPPacket receive() { 
        if (_receiver == null)
            return null;
        return _receiver.receiveNext(); 
    }
    
    /**
     *  Clear outbound queue, probably in preparation for sending destroy() to everybody.
     *  @since 0.9.2
     */
    public void clearOutbound() {
        _sender.clear();
    }
}
