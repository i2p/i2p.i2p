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
    private Sink sink;
    private final List<MSink> sinks;
    private final Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());

    public MultiSource() {
        this.sinks = new CopyOnWriteArrayList<MSink>();
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
     *  @since 0.9.53 added fromPort and toPort parameters
     *  @throws RuntimeException
     */
    public void send(Destination ignored_from, int ignored_fromPort, int ignored_toPort, byte[] data) {
        if (sinks.isEmpty()) {
            if (log.shouldDebug())
                log.debug("No subscribers to send " + data.length + " bytes to");
            return;
        }
        if (log.shouldDebug())
            log.debug("Sending " + data.length + " bytes to " + sinks.size() + " subscribers");

        for(MSink ms : this.sinks) {
            this.sink.send(ms.dest, ms.fromPort, ms.toPort, data);
        }
    }
    
    /**
     *  @since 0.9.53 changed to MSink parameter
     */
    public void add(MSink ms) {
        sinks.add(ms);
    }
    
    /**
     *  @since 0.9.53 changed to MSink parameter
     */
    public void remove(MSink ms) {
        sinks.remove(ms);
    }

    /**
     *  @since 0.9.53
     */
    static class MSink {
        public final Destination dest;
        public final int fromPort, toPort;

        public MSink(Destination dest, int fromPort, int toPort) {
            this.dest = dest; this.fromPort = fromPort; this.toPort = toPort;
        }

        @Override
        public int hashCode() {
            return dest.hashCode() | fromPort | (toPort << 16);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MSink))
                return false;
            MSink s = (MSink) o;
            return dest.equals(s.dest) && fromPort == s.fromPort && toPort == s.toPort;
        }

        @Override
        public String toString() {
            return "from port " + fromPort + " to " + dest.toBase32() + ':' + toPort;

        }
    }
}
