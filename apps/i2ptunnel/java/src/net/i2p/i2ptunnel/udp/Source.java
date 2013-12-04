package net.i2p.i2ptunnel.udp;

/**
 *
 * @author welterde
 */
public interface Source {
    public void setSink(Sink sink);
    public void start();
}
