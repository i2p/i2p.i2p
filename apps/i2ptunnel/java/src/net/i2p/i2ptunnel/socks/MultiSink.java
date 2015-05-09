package net.i2p.i2ptunnel.socks;

import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.Log;

/**
 * Sends to one of many Sinks
 * @author zzz modded from streamr/MultiSource
 */
public class MultiSink<S extends Sink> implements Source, Sink {

    public MultiSink(Map<Destination, S> cache) {
        this.cache = cache;
    }
    
    /** Don't use this - put sinks in the cache */
    public void setSink(Sink sink) {}

    public void start() {}

    /**
     *  May throw RuntimeException from underlying sinks
     *  @throws RuntimeException
     */
    public void send(Destination from, byte[] data) {
        Sink s = this.cache.get(from);
        if (s == null) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(MultiSink.class);
            log.error("No where to go for " + from.calculateHash().toBase64().substring(0, 6));
            return;
        }
        s.send(from, data);
    }
    
    private Map<Destination, S> cache;
}
