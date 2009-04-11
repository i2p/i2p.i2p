package net.i2p.i2ptunnel.socks;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.Map;

import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.udpTunnel.I2PTunnelUDPClientBase;

/**
 * A Datagram Tunnel that can have multiple bidirectional ports on the UDP side.
 *
 * TX:
 *   (ReplyTracker in multiple SOCKSUDPPorts -> ) I2PSink
 *
 * RX:
 *   (SOCKSUDPWrapper in multiple SOCKSUDPPorts <- ) MultiSink <- I2PSource
 *
 * The reply from a dest goes to the last SOCKSUDPPort that sent to that dest.
 * If multiple ports are talking to a dest at the same time, this isn't
 * going to work very well.
 *
 * @author zzz modded from streamr/StreamrConsumer
 */
public class SOCKSUDPTunnel extends I2PTunnelUDPClientBase {

    /**
     *  Set up a tunnel with no UDP side yet.
     *  Use add() for each port.
     */
    public SOCKSUDPTunnel(I2PTunnel tunnel) {
        super(null, tunnel, tunnel, tunnel);

        this.ports = new ConcurrentHashMap(1);
        this.cache = new ConcurrentHashMap(1);
        this.demuxer = new MultiSink(this.cache);
        setSink(this.demuxer);
    }


    /** @return the UDP port number */
    public int add(InetAddress host, int port) {
        SOCKSUDPPort sup = new SOCKSUDPPort(host, port, this.cache);
        this.ports.put(Integer.valueOf(sup.getPort()), sup);
        sup.setSink(this);
        sup.start();
        return sup.getPort();
    }

    public void remove(Integer port) {
        SOCKSUDPPort sup = this.ports.remove(port);
        if (sup != null)
            sup.stop();
        for (Iterator iter = cache.entrySet().iterator(); iter.hasNext();) {
            Map.Entry<Destination, SOCKSUDPPort> e = (Map.Entry) iter.next();
            if (e.getValue() == sup)
                iter.remove();
        }
    }
    
    @Override
    public final void startRunning() {
        super.startRunning();
        // demuxer start() doesn't do anything
        startall();
    }
    
    @Override
    public boolean close(boolean forced) {
        stopall();
        return super.close(forced);
    }

    /** you should really add() after startRunning() */
    private void startall() {
    }

    private void stopall() {
         for (SOCKSUDPPort sup : this.ports.values()) {
              sup.stop();
         }
         this.ports.clear();
         this.cache.clear();
    }



    private Map<Integer, SOCKSUDPPort> ports;
    private Map<Destination, SOCKSUDPPort> cache;
    private MultiSink demuxer;
}
