/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.i2ptunnel.udp;

// i2p
import net.i2p.data.Destination;

/**
 *
 * @author welterde
 */
public interface Sink {
    public void send(Destination src, byte[] data);
}
