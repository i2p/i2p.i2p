package net.i2p.router.transport.udp;

/**
 *  A Westwood bandwidth estimator
 *
 *  @since 0.9.49 adapted from streaming
 */
interface BandwidthEstimator {

    /**
     * Records an arriving ack.
     * @param acked how many bytes were acked with this ack
     */
    public void addSample(int acked);

    /**
     * @return the current bandwidth estimate in bytes/ms.
     */
    public float getBandwidthEstimate();
}
