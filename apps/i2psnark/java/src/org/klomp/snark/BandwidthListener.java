package org.klomp.snark;

/**
 *  Bandwidth and bandwidth limits
 *
 *  Maintain three bandwidth estimators:
 *  Sent, received, and requested.
 *
 *  @since 0.9.62
 */
public interface BandwidthListener {

    /**
     * The average rate in Bps
     */
    public long getUploadRate();

    /**
     * The average rate in Bps
     */
    public long getDownloadRate();

    /**
     * We unconditionally sent this many bytes
     */
    public void uploaded(int size);

    /**
     * We unconditionally received this many bytes
     */
    public void downloaded(int size);

    /**
     * Should we send this many bytes?
     * Do NOT call uploaded() if this returns true.
     */
    public boolean shouldSend(int size);

    /**
     * Should we request this many bytes?
     */
    public boolean shouldRequest(Peer peer, int size);

    /**
     * Current limit in BPS
     */
    public long getUpBWLimit();

    /**
     * Current limit in BPS
     */
    public long getDownBWLimit();

    /**
     * Are we currently over the limit?
     */
    public boolean overUpBWLimit();

    /**
     * Are we currently over the limit?
     */
    public boolean overDownBWLimit();
}
