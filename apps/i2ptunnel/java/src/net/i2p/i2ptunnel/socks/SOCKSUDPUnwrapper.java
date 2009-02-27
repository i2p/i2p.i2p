package net.i2p.i2ptunnel.socks;

import java.util.Map;

import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.Log;

/**
 * Strip a SOCKS header off a datagram, convert it to a Destination
 * Ref: RFC 1928
 *
 * @author zzz
 */
public class SOCKSUDPUnwrapper implements Source, Sink {
    private static final Log _log = new Log(SOCKSUDPUnwrapper.class);

    /**
     * @param cache put headers here to pass to SOCKSUDPWrapper
     */
    public SOCKSUDPUnwrapper(Map<Destination, SOCKSHeader> cache) {
        this.cache = cache;
    }
    
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    public void start() {}

    /**
     *
     */
    public void send(Destination ignored_from, byte[] data) {
        SOCKSHeader h;
        try {
            h = new SOCKSHeader(data);
        } catch (IllegalArgumentException iae) {
            _log.error(iae.toString());
            return;
        }
        Destination dest = h.getDestination();
        if (dest == null) {
            // no, we aren't going to send non-i2p traffic to a UDP outproxy :)
            _log.error("Destination not found: " + h.getHost());
            return;
        }

        cache.put(dest, h);

        int headerlen = h.getBytes().length;
        byte unwrapped[] = new byte[data.length - headerlen];
        System.arraycopy(unwrapped, 0, data, headerlen, unwrapped.length);
        this.sink.send(dest, unwrapped);
    }
    
    private Sink sink;
    private Map<Destination, SOCKSHeader> cache;
}
