package net.i2p.i2ptunnel.socks;

import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.util.Log;

/**
 * Strip a SOCKS header off a datagram, convert it to a Destination and port
 * Ref: RFC 1928
 *
 * @author zzz
 */
public class SOCKSUDPUnwrapper implements Source, Sink {
    private Sink sink;
    private final Map<I2PSocketAddress, SOCKSHeader> cache;

    /**
     * @param cache put headers here to pass to SOCKSUDPWrapper
     */
    public SOCKSUDPUnwrapper(Map<I2PSocketAddress, SOCKSHeader> cache) {
        this.cache = cache;
    }
    
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    public void start() {}

    /**
     *
     *  May throw RuntimeException from underlying sink
     *  @param ignored_from ignored
     *  @param fromPort will be passed along
     *  @param toPort ignored
     *  @since 0.9.53 added fromPort and toPort parameters
     *  @throws RuntimeException
     */
    public void send(Destination ignored_from, int fromPort, int toPort, byte[] data) {
        SOCKSHeader h;
        try {
            h = new SOCKSHeader(data);
        } catch (IllegalArgumentException iae) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(SOCKSUDPUnwrapper.class);
            log.error(iae.toString());
            return;
        }
        Destination dest = h.getDestination();
        if (dest == null) {
            // no, we aren't going to send non-i2p traffic to a UDP outproxy :)
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(SOCKSUDPUnwrapper.class);
            log.error("Destination not found: " + h.getHost());
            return;
        }

        cache.put(new I2PSocketAddress(dest, toPort), h);

        int headerlen = h.getBytes().length;
        byte unwrapped[] = new byte[data.length - headerlen];
        System.arraycopy(data, headerlen, unwrapped, 0, unwrapped.length);
        // We pass the local DatagramSocket's port through as the I2CP from port,
        // so that it will come back as the toPort in the reply,
        // and MultiSink will send it to the right SOCKSUDPWrapper/SOCKSUDPPort
        this.sink.send(dest, fromPort, h.getPort(), unwrapped);
    }
}
