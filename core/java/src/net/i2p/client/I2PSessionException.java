package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PException;

/**
 * Thrown when there is a problem doing something on the session
 *
 * @author jrandom
 */
public class I2PSessionException extends I2PException {

    public I2PSessionException(String msg, Throwable t) {
        super(msg, t);
    }

    public I2PSessionException(String msg) {
        super(msg);
    }
}
