package net.i2p.util;

/**
 *  A Westwood bandwidth estimator
 *
 *  @since 0.9.46 consolidated from streaming and udp in 0.9.50
 */
public interface BandwidthEstimator {

    /**
     * Records an arriving ack.
     * @param acked how many bytes or packets were acked with this ack
     */
    public void addSample(int acked);

    /**
     * @return the current bandwidth estimate in bytes/ms or packets/ms.
     */
    public float getBandwidthEstimate();
}
