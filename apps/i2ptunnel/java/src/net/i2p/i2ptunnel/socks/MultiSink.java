package net.i2p.i2ptunnel.socks;

import java.util.Map;

import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.Log;

/**
 * Sends to one of many Sinks
 * @author zzz modded from streamr/MultiSource
 */
public class MultiSink implements Source, Sink {
    private static final Log _log = new Log(MultiSink.class);

    public MultiSink(Map cache) {
        this.cache = cache;
    }
    
    /** Don't use this - put sinks in the cache */
    public void setSink(Sink sink) {}

    public void start() {}

    public void send(Destination from, byte[] data) {
        Sink s = this.cache.get(from);
        if (s == null) {
            _log.error("No where to go for " + from.calculateHash().toBase64().substring(0, 6));
            return;
        }
        s.send(from, data);
    }
    
    private Map<Destination, Sink> cache;
}
