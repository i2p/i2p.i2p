package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;

/**
 * Handle the establishment
 *
 */
interface EstablishState {
    
    /**
     * parse the contents of the buffer as part of the handshake.  if the
     * handshake is completed and there is more data remaining, the data are
     * copieed out so that the next read will be the (still encrypted) remaining
     * data (available from getExtraBytes)
     *
     * All data must be copied out of the buffer as Reader.processRead()
     * will return it to the pool.
     */
    public void receive(ByteBuffer src);

    /**
     * Does nothing. Outbound (Alice) must override.
     * We are establishing an outbound connection, so prepare ourselves by
     * queueing up the write of the first part of the handshake
     */
    public void prepareOutbound();

    /**
     *  Was this connection failed because of clock skew?
     */
    public boolean getFailedBySkew();

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
     * if complete, this will contain any bytes received as part of the
     * handshake that were after the actual handshake.  This may return null.
     */
    public byte[] getExtraBytes();

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

    public String getError();

    public Exception getException();
}
