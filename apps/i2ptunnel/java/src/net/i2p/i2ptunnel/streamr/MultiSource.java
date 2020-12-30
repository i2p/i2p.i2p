package net.i2p.i2ptunnel.streamr;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.Log;

/**
 * Sends to many Sinks
 * @author welterde
 * @author zzz modded for I2PTunnel
 */
public class MultiSource implements Source, Sink {
    private final Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());

    public MultiSource() {
        this.sinks = new CopyOnWriteArrayList<Destination>();
    }
    
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    public void start() {}

    public void stop() {
        this.sinks.clear();
    }

    /**
     *  May throw RuntimeException from underlying sinks
     *  @throws RuntimeException
     */
    public void send(Destination ignored_from, byte[] data) {
        if (sinks.isEmpty()) {
            if (log.shouldDebug())
                log.debug("No subscribers to send " + data.length + " bytes to");
            return;
        }
        if (log.shouldDebug())
            log.debug("Sending " + data.length + " bytes to " + sinks.size() + " subscribers");

        for(Destination dest : this.sinks) {
            this.sink.send(dest, data);
        }
    }
    
    public void add(Destination sink) {
        this.sinks.add(sink);
    }
    
    public void remove(Destination sink) {
        this.sinks.remove(sink);
    }
    
    private Sink sink;
    private final List<Destination> sinks;
}
