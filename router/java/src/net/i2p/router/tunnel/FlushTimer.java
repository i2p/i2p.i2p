package net.i2p.router.tunnel;

import net.i2p.util.SimpleTimer;

/**
 *
 */
class FlushTimer extends SimpleTimer {
    private static final FlushTimer _instance = new FlushTimer();
    public static final SimpleTimer getInstance() { return _instance; }
    protected FlushTimer() { super("TunnelFlushTimer"); }
}
