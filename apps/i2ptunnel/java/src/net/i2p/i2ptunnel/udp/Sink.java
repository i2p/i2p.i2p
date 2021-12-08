package net.i2p.i2ptunnel.udp;

import net.i2p.data.Destination;

/**
 *
 * @author welterde
 */
public interface Sink {
    /**
     *  @param fromPort I2CP source port, 0-65535
     *  @param toPort I2CP destination port, 0-65535
     *  @param src some implementations may ignore, may be null in some implementations
     *  @since 0.9.53 added fromPort and toPort parameters, breaking change, sorry
     *  @throws RuntimeException in some implementations
     */
    public void send(Destination src, int fromPort, int toPort, byte[] data);
}
