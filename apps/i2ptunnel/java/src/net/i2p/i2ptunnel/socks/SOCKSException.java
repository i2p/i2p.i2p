/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.i2ptunnel.socks;

/**
 * Exception thrown by socket methods
 *
 * @author human
 */
public class SOCKSException extends Exception {

    public SOCKSException() {
        super();
    }

    public SOCKSException(String s) {
        super(s);
    }
}