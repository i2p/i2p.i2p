/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.i2ptunnel.streamr;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;

/**
 * Sends to many Sinks
 * @author welterde
 * @author zzz modded for I2PTunnel
 */
public class MultiSource implements Source, Sink {
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

    public void send(Destination ignored_from, byte[] data) {
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
    private List<Destination> sinks;
}
