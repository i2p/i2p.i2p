/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.i2ptunnel.streamr;

// system
import java.io.File;

// i2p
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.i2ptunnel.udpTunnel.I2PTunnelUDPServerBase;
import net.i2p.util.EventDispatcher;

/**
 * Compared to a standard I2PTunnel,
 * this acts like a server on the I2P side (persistent privkey file)
 * but a client on the UDP side (receives on a configured port)
 *
 * @author welterde
 * @author zzz modded for I2PTunnel
 */
public class StreamrProducer extends I2PTunnelUDPServerBase {

    public StreamrProducer(int port,
                           File privkey, String privkeyname, Logging l,
                           EventDispatcher notifyThis, I2PTunnel tunnel) {
        // verify subscription requests
        super(true, privkey, privkeyname, l, notifyThis, tunnel);
        
        // The broadcaster
        this.multi = new MultiSource();
        this.multi.setSink(this);

        // The listener
        this.subscriber = new Subscriber(this.multi);
        setSink(this.subscriber);

        // now start udp-server
        this.server = new UDPSource(port);
        this.server.setSink(this.multi);
    }
    
    @Override
    public final void startRunning() {
        super.startRunning();
        this.server.start();
        l.log("Streamr server ready");
    }
    
    @Override
    public boolean close(boolean forced) {
        this.server.stop();
        this.multi.stop();
        return super.close(forced);
    }


    
    
    
    
    
    
    
    
    private MultiSource multi;
    private UDPSource server;
    private Sink subscriber;
}
