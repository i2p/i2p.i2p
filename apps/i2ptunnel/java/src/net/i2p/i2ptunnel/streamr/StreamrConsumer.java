/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.i2ptunnel.streamr;

import java.net.InetAddress;

import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.i2ptunnel.udpTunnel.I2PTunnelUDPClientBase;
import net.i2p.util.EventDispatcher;

/**
 * Compared to a standard I2PTunnel,
 * this acts like a client on the I2P side (no privkey file)
 * but a server on the UDP side (sends to a configured host/port)
 *
 * @author welterde
 * @author zzz modded for I2PTunnel
 */
public class StreamrConsumer extends I2PTunnelUDPClientBase {

    public StreamrConsumer(InetAddress host, int port, String destination,
                           Logging l, EventDispatcher notifyThis,
                           I2PTunnel tunnel) {
        super(destination, l, notifyThis, tunnel);

        // create udp-destination
        this.sink = new UDPSink(host, port);
        setSink(this.sink);
        
        // create pinger
        this.pinger = new Pinger();
        this.pinger.setSink(this);
    }
    
    @Override
    public final void startRunning() {
        super.startRunning();
        // send subscribe-message
        this.pinger.start();
        l.log("Streamr client ready");
    }
    
    @Override
    public boolean close(boolean forced) {
        // send unsubscribe-message
        this.pinger.stop();
        this.sink.stop();
        return super.close(forced);
    }








    
    
    private UDPSink sink;
    private Pinger pinger;
}
