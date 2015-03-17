package net.i2p.internal;

import net.i2p.client.I2PSessionException;

/**
 * A manager for the in-JVM I2CP message interface
 *
 * @author zzz
 * @since 0.8.3
 */
public interface InternalClientManager {

    /**
     *  Connect to the router, receiving a message queue to talk to the router with.
     *  @throws I2PSessionException if the router isn't ready
     */
    public I2CPMessageQueue connect() throws I2PSessionException;
}
