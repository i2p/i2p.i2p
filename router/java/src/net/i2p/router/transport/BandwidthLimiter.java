package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.RouterIdentity;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Coordinate the bandwidth limiting across all classes of peers.  
 *
 */
public interface BandwidthLimiter {
    /**
     * Delay the required amount of time before returning so that receiving numBytes
     * from the peer will not violate the bandwidth limits
     */
    public void delayInbound(RouterIdentity peer, int numBytes);
    
    /**
     * Delay the required amount of time before returning so that sending numBytes
     * to the peer will not violate the bandwidth limits
     * 
     * FIXME: Added 'pulled' to fix an oversight with regards to getTotalReceiveBytes().
     *        BandwidthLimitedInputStream can pull from the outbound bandwidth, but
     *        this leads to an incorrect value from getTotalReceiveBytes() with
     *        TrivialBandwidthLimited.  This is an inelegant solution, so fix it! =)
     */
    public void delayOutbound(RouterIdentity peer, int numBytes, boolean pulled);
    
    public long getTotalSendBytes();
    public long getTotalReceiveBytes();
    
    
    static final String PROP_INBOUND_BANDWIDTH = "i2np.bandwidth.inboundKBytesPerSecond";
    static final String PROP_OUTBOUND_BANDWIDTH = "i2np.bandwidth.outboundKBytesPerSecond";
    static final String PROP_INBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.inboundBurstKBytes";
    static final String PROP_OUTBOUND_BANDWIDTH_PEAK = "i2np.bandwidth.outboundBurstKBytes";
    static final String PROP_REPLENISH_FREQUENCY = "i2np.bandwidth.replenishFrequency";
    static final String PROP_MIN_NON_ZERO_DELAY = "i2np.bandwidth.minimumNonZeroDelay";
}
