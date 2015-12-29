package net.i2p.i2ptunnel.streamr;

import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

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

    /**
     *  Doesn't really "send" anywhere, just subscribes or unsubscribes the destination
     *
     *  @param dest to subscribe or unsubscribe
     *  @param data must be a single byte, 0 to subscribe, 1 to unsubscribe
     */
    public void send(Destination dest, byte[] data) {
        if(dest == null || data.length < 1) {
            // invalid packet
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
            if (log.shouldWarn())
                log.warn("bad subscription from " + dest);
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
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
                if (log.shouldWarn())
                    log.warn("bad subscription from " + dest);
            }
        }
    }
    
    private final Set<Destination> subscriptions;
    private final MultiSource multi;
}
