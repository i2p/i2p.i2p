package net.i2p.client.streaming;

import net.i2p.I2PException;

/**
 * We attempted to have more open streams than we are willing to put up with
 *
 */
public class TooManyStreamsException extends I2PException {
    public TooManyStreamsException(String message, Throwable parent) {
        super(message, parent);
    }

    public TooManyStreamsException(String message) {
        super(message);
    }
    
    public TooManyStreamsException() {
        super();
    }
}
