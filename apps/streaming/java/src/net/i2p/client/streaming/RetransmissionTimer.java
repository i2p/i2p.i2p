package net.i2p.client.streaming;

import net.i2p.util.SimpleTimer2;

/**
 *  Not clear that we really need to create our own timer group, but we do,
 *  to prevent us clogging the router's timer group.
 *  Use from outside this package is deprecated.
 *  (BOB instantiates this for thread group reasons)
 */
public class RetransmissionTimer extends SimpleTimer2 {
    private static final RetransmissionTimer _instance = new RetransmissionTimer();
    public static final RetransmissionTimer getInstance() { return _instance; }
    protected RetransmissionTimer() { super("StreamingTimer"); }
}
