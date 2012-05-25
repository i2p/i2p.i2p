package net.i2p.i2ptunnel.socks;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;

/**
 * Implements a UDP port and Socks encapsulation / decapsulation.
 * This is for a single port. If there is demuxing for multiple
 * ports, it happens outside of here.
 *
 * TX:
 *   UDPSource -> SOCKSUDPUnwrapper -> ReplyTracker ( -> I2PSink in SOCKSUDPTunnel)
 *
 * RX:
 *   UDPSink <- SOCKSUDPWrapper ( <- MultiSink <- I2PSource in SOCKSUDPTunnel)
 *
 * The Unwrapper passes headers to the Wrapper through a cache.
 * The ReplyTracker passes sinks to MultiSink through a cache.
 *
 * @author zzz
 */
public class SOCKSUDPPort implements Source, Sink {

    public SOCKSUDPPort(InetAddress host, int port, Map replyMap) {

        // this passes the host and port from UDPUnwrapper to UDPWrapper
        Map cache = new ConcurrentHashMap(4);

        // rcv from I2P and send to a port
        this.wrapper = new SOCKSUDPWrapper(cache);
        this.udpsink = new UDPSink(host, port);
        this.wrapper.setSink(this.udpsink);
        
        // rcv from the same port and send to I2P
        DatagramSocket sock = this.udpsink.getSocket();
        this.udpsource = new UDPSource(sock);
        this.unwrapper = new SOCKSUDPUnwrapper(cache);
        this.udpsource.setSink(this.unwrapper);
        this.udptracker = new ReplyTracker(this, replyMap);
        this.unwrapper.setSink(this.udptracker);
    }

    /** Socks passes this back to the client on the TCP connection */
    public int getPort() {
        return this.udpsink.getPort();
    }

    public void setSink(Sink sink) {
        this.udptracker.setSink(sink);
    }

    public void start() {
        // the other Sources don't use start
        this.udpsource.start();
    }

    public void stop() {
        this.udpsink.stop();
        this.udpsource.stop();
    }

    public void send(Destination from, byte[] data) {
        this.wrapper.send(from, data);
    }
    

    private UDPSink udpsink;
    private UDPSource udpsource;
    private SOCKSUDPWrapper wrapper;
    private SOCKSUDPUnwrapper unwrapper;
    private ReplyTracker udptracker;
}
