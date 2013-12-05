package net.i2p.i2ptunnel.udp;

import net.i2p.data.Destination;

/**
 *
 * @author welterde
 */
public interface Sink {
    public void send(Destination src, byte[] data);
}
