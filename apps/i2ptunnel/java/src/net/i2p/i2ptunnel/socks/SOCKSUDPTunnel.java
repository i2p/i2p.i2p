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
 *   (multiple SOCKSUDPPorts -&gt; ) I2PSink
 *
 * RX:
 *   (SOCKSUDPWrapper in multiple SOCKSUDPPorts &lt;- ) MultiSink &lt;- I2PSource
 *
 * The replies must be to the same I2CP toPort as the outbound fromPort.
 * If the server does not honor that, the replies will be dropped.
 *
 * The replies must be repliable. Raw datagrams are not supported, and would
 * require a unique source port for each target.
 *
 * Preliminary, untested, possibly incomplete.
 *
 * @author zzz modded from streamr/StreamrConsumer
 */
public class SOCKSUDPTunnel extends I2PTunnelUDPClientBase {
    private final Map<Integer, SOCKSUDPPort> ports;
    private final MultiSink<SOCKSUDPPort> demuxer;

    /**
     *  Set up a tunnel with no UDP side yet.
     *  Use add() for each port.
     */
    public SOCKSUDPTunnel(I2PTunnel tunnel) {
        super(null, tunnel, tunnel, tunnel);

        this.ports = new ConcurrentHashMap<Integer, SOCKSUDPPort>(1);
        this.demuxer = new MultiSink<SOCKSUDPPort>(ports);
        setSink(this.demuxer);
    }


    /** @return the UDP port number */
    public int add(InetAddress host, int port) {
        SOCKSUDPPort sup = new SOCKSUDPPort(host, port, ports);
        this.ports.put(Integer.valueOf(sup.getPort()), sup);
        sup.setSink(this);
        sup.start();
        return sup.getPort();
    }

    public void remove(Integer port) {
        SOCKSUDPPort sup = this.ports.remove(port);
        if (sup != null)
            sup.stop();
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
    }
}
