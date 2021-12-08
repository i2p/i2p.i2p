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
        UDPSink udps = new UDPSink(host, port);
        int localPort = udps.getPort();
        this.sink = udps;
        setSink(this.sink);
        
        // create pinger
        this.pinger = new Pinger(_context, localPort);
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
    
    private final UDPSink sink;
    private final Pinger pinger;
}
