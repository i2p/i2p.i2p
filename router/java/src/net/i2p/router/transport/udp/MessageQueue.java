package net.i2p.router.transport.udp;

import net.i2p.router.OutNetMessage;

/**
 * Base queue for messages not yet packetized
 */
interface MessageQueue {
    /**
     * Get the next message, blocking until one is found or the expiration
     * reached.
     *
     * @param blockUntil expiration, or -1 if indefinite
     */
    public OutNetMessage getNext(long blockUntil);
    /**
     * Add on a new message to the queue
     */
    public void add(OutNetMessage message);
}
