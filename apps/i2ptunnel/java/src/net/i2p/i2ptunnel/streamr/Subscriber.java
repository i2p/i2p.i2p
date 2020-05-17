package net.i2p.i2ptunnel.streamr;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.udp.*;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * server-mode
 * @author welterde
 * @author zzz modded from Producer for I2PTunnel
 */
public class Subscriber implements Sink {

    private final I2PAppContext ctx = I2PAppContext.getGlobalContext();
    private final Log log = ctx.logManager().getLog(getClass());
    private final Map<Destination, Long> subscriptions;
    private final MultiSource multi;
    private final SimpleTimer2.TimedEvent timer;
    private volatile boolean timerRunning;

    private static final int MAX_SUBSCRIPTIONS = 10;
    private static final long EXPIRATION = 60*1000;

    public Subscriber(MultiSource multi) {
        this.multi = multi;
        // subscriptions
        this.subscriptions = new ConcurrentHashMap<Destination, Long>();
        timer = new Expire();
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
            if (log.shouldWarn())
                log.warn("bad subscription from " + dest);
        } else {
            byte ctrl = data[0];
            if(ctrl == 0) {
                if (this.subscriptions.put(dest, Long.valueOf(ctx.clock().now())) == null) {
                    if (subscriptions.size() > MAX_SUBSCRIPTIONS) {
                        subscriptions.remove(dest);
                        if (log.shouldWarn())
                            log.warn("Too many subscriptions, denying: " + dest.toBase32());
                        return;
                    }
                    // subscribe
                    if (log.shouldWarn())
                        log.warn("Add subscription: " + dest.toBase32());
                    this.multi.add(dest);
                    if (!timerRunning) {
                        timer.reschedule(EXPIRATION);
                        timerRunning = true;
                    }
                } else {
                    if (log.shouldInfo())
                        log.info("Continue subscription: " + dest.toBase32());
                }
            } else if(ctrl == 1) {
                // unsubscribe
                if (log.shouldWarn())
                    log.warn("Remove subscription: " + dest.toBase32());
                if (subscriptions.remove(dest) != null)
                    multi.remove(dest);
            } else {
                // invalid packet
                if (log.shouldWarn())
                    log.warn("bad subscription from " + dest);
            }
        }
    }

    /** @since 0.9.46 */
    private class Expire extends SimpleTimer2.TimedEvent {

        public Expire() {
            super(ctx.simpleTimer2());
        }

        public void timeReached() {
            if (subscriptions.isEmpty()) {
                timerRunning = false;
                return;
            }
            long exp = ctx.clock().now() - EXPIRATION;
            for (Iterator<Map.Entry<Destination, Long>> iter = subscriptions.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<Destination, Long> e = iter.next();
                long then = e.getValue().longValue();
                if (then < exp) {
                    Destination dest = e.getKey();
                    iter.remove();
                    multi.remove(dest);
                    if (log.shouldWarn())
                        log.warn("Expired subscription: " + dest.toBase32());
                }
            }
            if (!subscriptions.isEmpty()) {
                schedule(EXPIRATION);
                timerRunning = true;
            } else {
                timerRunning = false;
            }
        }
    }
}
