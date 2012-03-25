package net.i2p;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */



/**
 * Base class of I2P exceptions
 *
 * This was originally used to provide chained exceptions, but
 * those were added to Exception in Java 1.4, so this class provides nothing
 * extra at the moment.
 *
 * @author jrandom
 */
public class I2PException extends Exception {

    public I2PException() {
        super();
    }

    public I2PException(String msg) {
        super(msg);
    }

    public I2PException(String msg, Throwable cause) {
        super(msg, cause);
    }
    
    /** @since 0.8.2 */
    public I2PException(Throwable cause) {
        super(cause);
    }
}
