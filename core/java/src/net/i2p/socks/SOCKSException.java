/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.socks;

import java.io.IOException;

/**
 * Exception thrown by socket methods
 *
 * @author human
 * @since 0.9.33 moved from net.i2p.i2ptunnel.socks, and changed to extend IOException
 */
public class SOCKSException extends IOException {

    public SOCKSException() {
        super();
    }

    public SOCKSException(String s) {
        super(s);
    }

    /** @since 0.9.27 */
    public SOCKSException(String s, Throwable t) {
        super(s, t);
    }
}
