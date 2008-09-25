package net.i2p.client.streaming;

import net.i2p.util.SimpleTimer;

/**
 *
 */
public class RetransmissionTimer extends SimpleTimer {
    private static final RetransmissionTimer _instance = new RetransmissionTimer();
    public static final SimpleTimer getInstance() { return _instance; }
    protected RetransmissionTimer() { super("StreamingTimer"); }
}
