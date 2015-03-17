package net.i2p.i2ptunnel.socks;

import java.util.Map;

import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;

/**
 * Put a SOCKS header on a datagram
 * Ref: RFC 1928
 *
 * @author zzz
 */
public class SOCKSUDPWrapper implements Source, Sink {
    public SOCKSUDPWrapper(Map<Destination, SOCKSHeader> cache) {
        this.cache = cache;
    }
    
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    public void start() {}

    /**
     * Use the cached header, which should have the host string and port
     *
     */
    public void send(Destination from, byte[] data) {
        if (this.sink == null)
            return;

        SOCKSHeader h = cache.get(from);
        if (h == null) {
            // RFC 1928 says drop
            // h = new SOCKSHeader(from);
            return;
        }

        byte[] header = h.getBytes();
        byte wrapped[] = new byte[header.length + data.length];
        System.arraycopy(header, 0, wrapped, 0, header.length);
        System.arraycopy(data, 0, wrapped, header.length, data.length);
        this.sink.send(from, wrapped);
    }
    
    private Sink sink;
    private Map<Destination, SOCKSHeader> cache;
}
