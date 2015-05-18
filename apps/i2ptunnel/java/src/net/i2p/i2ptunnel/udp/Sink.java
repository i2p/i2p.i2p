package net.i2p.i2ptunnel.udp;

import net.i2p.data.Destination;

/**
 *
 * @author welterde
 */
public interface Sink {
    /**
     *  @param src some implementations may ignore
     *  @throws RuntimeException in some implementations
     */
    public void send(Destination src, byte[] data);
}
