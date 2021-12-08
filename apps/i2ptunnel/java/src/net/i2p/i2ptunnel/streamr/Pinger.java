package net.i2p.i2ptunnel.streamr;

import net.i2p.i2ptunnel.udp.*;
import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 *
 * @author welterde/zzz
 */
public class Pinger implements Source, Runnable {
    protected Sink sink;
    protected final Thread thread;
    private final Object waitlock = new Object();
    protected volatile boolean running;
    private final Log log;
    private final int fromPort;

    /**
     *  @param fromPort the I2CP from port
     *  @since 0.9.53 added ctx and fromPort params
     */
    public Pinger(I2PAppContext ctx, int fromPort) {
        this.thread = new I2PAppThread(this);
        log = ctx.logManager().getLog(getClass());
        this.fromPort = fromPort;
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }
    
    public void start() {
        this.running = true;
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
        try {
            this.sink.send(null, fromPort, 0, data);
            if (log.shouldDebug())
                log.debug("Sent unsubscribe from port " + fromPort);
        } catch (RuntimeException re) {}
    }
    
    public void run() {
        // send subscribe-message
        byte[] data = new byte[1];
        data[0] = 0;
        int i = 0;
        while(this.running) {
            try {
                this.sink.send(null, fromPort, 0, data);
                if (log.shouldDebug())
                    log.debug("Sent subscribe from port " + fromPort);
            } catch (RuntimeException re) {
                if (log.shouldWarn())
                    log.warn("error sending", re);
                break;
            }
            synchronized(this.waitlock) {
                int delay = 10000;
                if (i < 5) {
                    i++;
                    delay = 2000;
                }
                try {
                    this.waitlock.wait(delay);
                } catch(InterruptedException ie) {
                    break;
                }
            }
        }
    }
}
