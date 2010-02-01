package net.i2p.i2ptunnel.streamr;

import net.i2p.i2ptunnel.udp.*;

/**
 *
 * @author welterde/zzz
 */
public class Pinger implements Source, Runnable {
    public Pinger() {
        this.thread = new Thread(this);
    }
    public void setSink(Sink sink) {
        this.sink = sink;
    }
    
    public void start() {
        this.running = true;
        //this.waitlock = new Object();
        this.thread.start();
    }
    
    public void stop() {
        this.running = false;
        synchronized(this.waitlock) {
            this.waitlock.notifyAll();
        }
        // send unsubscribe-message
        byte[] data = new byte[1];
        data[0] = 1;
        this.sink.send(null, data);
    }
    
    public void run() {
        // send subscribe-message
        byte[] data = new byte[1];
        data[0] = 0;
        int i = 0;
        while(this.running) {
            //System.out.print("p");
            this.sink.send(null, data);
            synchronized(this.waitlock) {
                int delay = 10000;
                if (i < 5) {
                    i++;
                    delay = 2000;
                }
                try {
                    this.waitlock.wait(delay);
                } catch(InterruptedException ie) {}
            }
        }
    }

    protected Sink sink;
    protected Thread thread;
    private final Object waitlock = new Object();
    protected boolean running;
}
