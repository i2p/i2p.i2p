package net.i2p.i2ptunnel.socks;

import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.Log;

/**
 * Sends to one of many Sinks based on the toPort
 *
 * @author zzz modded from streamr/MultiSource
 */
public class MultiSink<S extends Sink> implements Source, Sink {
    private final Map<Integer, S> cache;

    /**
     *  @param cache map of toPort to Sink
     */
    public MultiSink(Map<Integer, S> cache) {
        this.cache = cache;
    }
    
    /** Don't use this - put sinks in the cache */
    public void setSink(Sink sink) {}

    public void start() {}

    /**
     *  Send to a single sink looked up by toPort
     *
     *  May throw RuntimeException from underlying sinks
     *
     *  @param from passed along
     *  @param fromPort passed along
     *  @param toPort passed along
     *  @since 0.9.53 added fromPort and toPort parameters
     *  @throws RuntimeException
     */
    public void send(Destination from, int fromPort, int toPort, byte[] data) {
        Sink s = cache.get(toPort);
        if (s == null && toPort == 0 && cache.size() == 1) {
            // for now, do the server a favor if the toPort isn't specified
            for (Sink ss : cache.values()) {
                s = ss;
                break;
            }
        }
        if (s == null) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(MultiSink.class);
            String frm = (from != null) ? from.toBase32() : "raw";
            if (log.shouldWarn())
                log.warn("No where to go for " + frm + " port " + fromPort + " to port " + toPort);
            return;
        }
        s.send(from, fromPort, toPort, data);
    }
}
