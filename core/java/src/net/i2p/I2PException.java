package net.i2p;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * Base class of I2P exceptions
 *
 * @author jrandom
 */
public class I2PException extends Exception {
    private Throwable _source;

    public I2PException() {
        this(null, null);
    }

    public I2PException(String msg) {
        this(msg, null);
    }

    public I2PException(String msg, Throwable source) {
        super(msg);
        _source = source;
    }

    public void printStackTrace() {
        if (_source != null) _source.printStackTrace();
        super.printStackTrace();
    }

    public void printStackTrace(PrintStream ps) {
        if (_source != null) _source.printStackTrace(ps);
        super.printStackTrace(ps);
    }

    public void printStackTrace(PrintWriter pw) {
        if (_source != null) _source.printStackTrace(pw);
        super.printStackTrace(pw);
    }
}