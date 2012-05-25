package net.i2p.router.peermanager;

import net.i2p.router.RouterContext;

/**
 * Quantify how fast the peer is - how fast they respond to our requests, how fast
 * they pass messages on, etc.  This should be affected both by their bandwidth/latency,
 * as well as their load.
 *
 * IMPORTANT -
 * This code has been through many iterations, and some versions were quite complex.
 * If you are considering changes, review the change control history, and
 * see the previous versions in change control to get 400+ lines of old code.
 *
 */
public class SpeedCalculator extends Calculator {
    
    public SpeedCalculator(RouterContext context) {
    }
    
    @Override
    public double calc(PeerProfile profile) {
        // measures 1 minute throughput of individual tunnels
        double d = (profile.getPeakTunnel1mThroughputKBps()*1024d) + profile.getSpeedBonus();
        if (d >= 0) return d;
        return 0.0d;
    }
}
