package net.i2p.i2ptunnel.socks;

import java.util.Map;

import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.Log;

/**
 * Track who the reply goes to
 * @author zzz
 */
public class ReplyTracker implements Source, Sink {
    private static final Log _log = new Log(MultiSink.class);

    public ReplyTracker(Sink reply, Map cache) {
        this.reply = reply;
        this.cache = cache;
    }
    
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    public void start() {}

    public void send(Destination to, byte[] data) {
        this.cache.put(to, this.reply);
        this.sink.send(to, data);
    }
    
    private Sink reply;
    private Map<Destination, Sink> cache;
    private Sink sink;
}
