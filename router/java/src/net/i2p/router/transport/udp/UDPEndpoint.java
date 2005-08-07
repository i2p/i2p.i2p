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
public class UDPEndpoint {
    private RouterContext _context;
    private Log _log;
    private int _listenPort;
    private UDPTransport _transport;
    private UDPSender _sender;
    private UDPReceiver _receiver;
    
    public UDPEndpoint(RouterContext ctx, UDPTransport transport, int listenPort) throws SocketException {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPEndpoint.class);
        _transport = transport;
        
        _listenPort = listenPort;
    }
    
    public void startup() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Starting up the UDP endpoint");
        shutdown();
        try {
            DatagramSocket socket = new DatagramSocket(_listenPort);
            _sender = new UDPSender(_context, socket, "UDPSend on " + _listenPort);
            _receiver = new UDPReceiver(_context, _transport, socket, "UDPReceive on " + _listenPort);
            _sender.startup();
            _receiver.startup();
        } catch (SocketException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to bind on " + _listenPort, se);
        }
    }
    
    public void shutdown() {
        if (_sender != null) {
            _sender.shutdown();
            _receiver.shutdown();
        }
    }
    
    public void setListenPort(int newPort) { _listenPort = newPort; }
    public void updateListenPort(int newPort) {
        if (newPort == _listenPort) return;
        try {
            DatagramSocket socket = new DatagramSocket(newPort);
            _sender.updateListeningPort(socket, newPort);
            // note: this closes the old socket, so call this after the sender!
            _receiver.updateListeningPort(socket, newPort);
            _listenPort = newPort;
        } catch (SocketException se) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to bind on " + _listenPort);
        }
    }
    
    public int getListenPort() { return _listenPort; }
    public UDPSender getSender() { return _sender; }
    
    /**
     * Add the packet to the outobund queue to be sent ASAP (as allowed by
     * the bandwidth limiter)
     *
     * @return number of packets in the send queue
     */
    public int send(UDPPacket packet) { return _sender.add(packet); }
    
    /**
     * Blocking call to receive the next inbound UDP packet from any peer.
     */
    public UDPPacket receive() { return _receiver.receiveNext(); }
}
