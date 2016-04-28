package net.i2p.sam;

/**
 * Something that can be stopped by the SAMBridge.
 *
 * @since 0.9.20
 */
public interface Handler {

    /**
     * Stop the handler
     */
    public void stopHandling();
}
