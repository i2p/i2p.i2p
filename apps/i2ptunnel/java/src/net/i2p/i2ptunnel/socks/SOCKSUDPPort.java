package net.i2p.i2ptunnel.socks;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;

/**
 * Implements a UDP port and Socks encapsulation / decapsulation.
 * This is for a single port. If there is demuxing for multiple
 * ports, it happens outside of here.
 *
 * TX:
 *   UDPSource -&gt; SOCKSUDPUnwrapper -&gt; (I2PSink in SOCKSUDPTunnel)
 *
 * RX:
 *   UDPSink &lt;- SOCKSUDPWrapper ( &lt;- MultiSink &lt;- I2PSource in SOCKSUDPTunnel)
 *
 * The Unwrapper passes headers to the Wrapper through a cache.
 * MultiSink routes packets based on toPort.
 *
 * @author zzz
 */
public class SOCKSUDPPort implements Source, Sink {
    private final UDPSink udpsink;
    private final UDPSource udpsource;
    private final SOCKSUDPWrapper wrapper;
    private final SOCKSUDPUnwrapper unwrapper;

    public SOCKSUDPPort(InetAddress host, int port, Map<Integer, SOCKSUDPPort> replyMap) {

        // this passes the host and port from UDPUnwrapper to UDPWrapper
        Map<I2PSocketAddress, SOCKSHeader> cache = new ConcurrentHashMap<I2PSocketAddress, SOCKSHeader>(4);

        // rcv from I2P and send to a port
        this.wrapper = new SOCKSUDPWrapper(cache);
        this.udpsink = new UDPSink(host, port);
        this.wrapper.setSink(this.udpsink);
        
        // rcv from the same port and send to I2P
        DatagramSocket sock = this.udpsink.getSocket();
        this.udpsource = new UDPSource(sock);
        this.unwrapper = new SOCKSUDPUnwrapper(cache);
        this.udpsource.setSink(this.unwrapper);
    }

    /** Socks passes this back to the client on the TCP connection */
    public int getPort() {
        return this.udpsink.getPort();
    }

    public void setSink(Sink sink) {
        this.unwrapper.setSink(sink);
    }

    public void start() {
        // the other Sources don't use start
        this.udpsource.start();
    }

    public void stop() {
        this.udpsink.stop();
        this.udpsource.stop();
    }

    /**
     *  May throw RuntimeException from underlying sink

     *  @param from will be passed along
     *  @param fromPort will be passed along
     *  @param toPort will be passed along
     *  @since 0.9.53 added fromPort and toPort parameters
     *  @throws RuntimeException
     */
    public void send(Destination from, int fromPort, int toPort, byte[] data) {
        this.wrapper.send(from, fromPort, toPort, data);
    }
}
