/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.i2ptunnel.streamr;

// system
import java.util.Set;

// i2p
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.ConcurrentHashSet;

/**
 * server-mode
 * @author welterde
 * @author zzz modded from Producer for I2PTunnel
 */
public class Subscriber implements Sink {

    public Subscriber(MultiSource multi) {
        this.multi = multi;
        // subscriptions
        this.subscriptions = new ConcurrentHashSet<Destination>();
    }

    public void send(Destination dest, byte[] data) {
        if(dest == null || data.length < 1) {
            // invalid packet
            // TODO: write to log
        } else {
            byte ctrl = data[0];
            if(ctrl == 0) {
                if (!this.subscriptions.contains(dest)) {
                    // subscribe
                    System.out.println("Add subscription: " + dest.toBase64().substring(0,4));
                    this.subscriptions.add(dest);
                    this.multi.add(dest);
                } // else already subscribed
            } else if(ctrl == 1) {
                // unsubscribe
                System.out.println("Remove subscription: " + dest.toBase64().substring(0,4));
                boolean removed = this.subscriptions.remove(dest);
                if(removed)
                    multi.remove(dest);
            } else {
                // invalid packet
                // TODO: write to log
            }
        }
    }
    
    
    
    
    
    
    
    
    
    
    private Set<Destination> subscriptions;
    private MultiSource multi;
}
