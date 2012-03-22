package net.i2p.client.streaming;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer2;

/**
 *  Per-destination timer
 */
public class RetransmissionTimer extends SimpleTimer2 {

    /**
     *  @deprecated Don't use this to prestart threads, this is no longer a static instance
     *  @return a new instance as of 0.9
     */
    public static final RetransmissionTimer getInstance() {
        return new RetransmissionTimer(I2PAppContext.getGlobalContext(), "RetransmissionTimer");
    }


    /**
     *  @since 0.9
     */
    RetransmissionTimer(I2PAppContext ctx, String name) {
        super(ctx, name, false);
    }
}
