package net.i2p.client.streaming.impl;

/**
 *  A Westwood bandwidth estimator
 *
 *  @since 0.9.46
 */
interface BandwidthEstimator {

    /**
     * Records an arriving ack.
     * @param acked how many packets were acked with this ack
     */
    public void addSample(int acked);

    /**
     * @return the current bandwidth estimate in packets/ms.
     */
    public float getBandwidthEstimate();
}
