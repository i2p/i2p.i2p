package net.i2p.client.streaming;

import net.i2p.util.SimpleTimer2;

/**
 *
 */
public class RetransmissionTimer extends SimpleTimer2 {
    private static final RetransmissionTimer _instance = new RetransmissionTimer();
    public static final RetransmissionTimer getInstance() { return _instance; }
    protected RetransmissionTimer() { super("StreamingTimer"); }
}
