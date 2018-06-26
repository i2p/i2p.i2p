package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;

/**
 * Handle the establishment
 *
 */
interface EstablishState {
    
    /**
     * Parse the contents of the buffer as part of the handshake.
     *
     * All data must be copied out of the buffer as Reader.processRead()
     * will return it to the pool.
     *
     * If there are additional data in the buffer after the handshake is complete,
     * the EstablishState is responsible for passing it to NTCPConnection.
     *
     * @throws IllegalStateException
     */
    public void receive(ByteBuffer src);

    /**
     * Does nothing. Outbound (Alice) must override.
     * We are establishing an outbound connection, so prepare ourselves by
     * queueing up the write of the first part of the handshake
     *
     * @throws IllegalStateException
     */
    public void prepareOutbound();

    /** did the handshake fail for some reason? */
    public boolean isCorrupt();

    /**
     *  If synchronized on this, fails with
     *  deadlocks from all over via CSFI.isEstablished().
     *  Also CSFI.getFramedAveragePeerClockSkew().
     *
     *  @return is the handshake complete and valid?
     */
    public boolean isComplete();

    /**
     *  Get the NTCP version
     *  @return 1, 2, or 0 if unknown
     *  @since 0.9.35
     */
    public int getVersion();

    /**
     *  Release resources on timeout.
     *  @param e may be null
     *  @since 0.9.16
     */
    public void close(String reason, Exception e);

}
