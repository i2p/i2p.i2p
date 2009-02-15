package net.i2p.router.tunnel;

import net.i2p.util.SimpleTimer;

/**
 *
 */
class FlushTimer extends SimpleTimer {
    /*
      Streaming lib has been moved from SimpleTimer to SimpleTimer2, eliminating the congestion.
      So there's not much left using SimpleTimer, and FlushTimer doesn't need its own 4 threads any more
      (if it ever did?...)
    */
    //private static final FlushTimer _instance = new FlushTimer();
    //public static final SimpleTimer getInstance() { return _instance; }
    //protected FlushTimer() { super("TunnelFlushTimer"); }
}
